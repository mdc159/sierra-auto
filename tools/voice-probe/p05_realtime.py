"""Realtime speech models through the gateway's websocket route.

Connects to ws://<gateway>/v1/realtime?model=<model> with the bearer key,
waits for session.created, then asks for a one-sentence spoken reply to a
text prompt, twice in the same session. Records every event type with its
arrival time, the time to the first audio delta and the exact error if any.

With "audio" it instead speaks the short test clip into the session:
  * manual: turn detection off, whole clip appended, then commit and
    response.create; the clock starts at the commit (all audio is already
    uploaded, as it would be when the driver stops talking);
  * vad: server turn detection on, clip sent at real-time pace followed by
    silence; the clock starts when the last speech chunk is sent, so the
    figure includes the server's end-of-speech wait.

Usage: python p05_realtime.py                    default models, text prompt
       python p05_realtime.py name=x model-a     only these, to results/05_realtime_x.json
       python p05_realtime.py audio [model ...]  spoken input, to results/05_realtime_audio.json
"""
from __future__ import annotations

import asyncio
import base64
import json
import sys

import websockets
from websockets.exceptions import ConnectionClosed, InvalidStatus

from probe_common import AUDIO, Clock, gateway, read_wav_16k, resample, save, say, short

MODELS = [
    "openai/gpt-realtime-2.1",
    "openai/gpt-realtime-2.1-mini",
    "gemini/gemini-3.8-live",
    "gemini/gemini-3.1-flash-live-preview",
    "xai/grok-voice-3.0",
]
PROMPTS = [
    "Say hello to the driver in one short sentence.",
    "In one short sentence, is 215 degrees too hot for a transmission while towing?",
]
AUDIO_DELTAS = ("response.output_audio.delta", "response.audio.delta")
TRANSCRIPT_DELTAS = ("response.output_audio_transcript.delta", "response.audio_transcript.delta",
                     "response.output_text.delta", "response.text.delta")


async def one_turn(ws, prompt: str) -> dict:
    turn: dict = {"prompt": prompt, "events": []}
    await ws.send(json.dumps({
        "type": "conversation.item.create",
        "item": {"type": "message", "role": "user", "content": [{"type": "input_text", "text": prompt}]},
    }))
    clock = Clock()
    await ws.send(json.dumps({"type": "response.create"}))
    audio_bytes, transcript, counts = 0, "", {}
    try:
        while True:
            raw = await asyncio.wait_for(ws.recv(), timeout=25)
            event = json.loads(raw)
            kind = event.get("type", "?")
            counts[kind] = counts.get(kind, 0) + 1
            if counts[kind] == 1:
                turn["events"].append([clock.lap(), kind])
            if kind in AUDIO_DELTAS:
                turn.setdefault("first_audio_ms", clock.lap())
                audio_bytes += len(base64.b64decode(event.get("delta", "")))
            elif kind in TRANSCRIPT_DELTAS:
                turn.setdefault("first_text_ms", clock.lap())
                transcript += event.get("delta", "")
            elif kind == "error":
                turn.setdefault("errors", []).append(short(json.dumps(event.get("error", event))))
            elif kind == "response.done":
                response = event.get("response", {})
                turn["status"] = response.get("status")
                if response.get("status_details"):
                    turn["status_details"] = short(json.dumps(response["status_details"]))
                usage = response.get("usage") or {}
                turn["usage"] = {k: v for k, v in usage.items() if isinstance(v, int)}
                break
    except asyncio.TimeoutError:
        turn["timeout"] = "no event for 25 s"
    turn["total_ms"] = clock.lap()
    turn["audio_bytes"] = audio_bytes
    turn["transcript"] = transcript
    turn["event_counts"] = counts
    return turn


async def probe(ws_base: str, key: str, model: str) -> dict:
    row: dict = {"model": model, "url": f"{ws_base}/realtime?model={model}"}
    clock = Clock()
    try:
        async with websockets.connect(row["url"], additional_headers={"Authorization": f"Bearer {key}"},
                                      open_timeout=20, max_size=None) as ws:
            row["connect_ms"] = clock.lap()
            try:
                first = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
            except asyncio.TimeoutError:
                row["first_event"] = "none within 15 s"
                first = {}
            if first:
                row["first_event"] = first.get("type")
                row["first_event_ms"] = clock.lap()
                session = first.get("session") or {}
                audio = session.get("audio") or {}
                row["session"] = {
                    "model": session.get("model"),
                    "type": session.get("type"),
                    "object": session.get("object"),
                    "output_modalities": session.get("output_modalities") or session.get("modalities"),
                    "output_audio_format": (audio.get("output") or {}).get("format") or session.get("output_audio_format"),
                    "input_audio_format": (audio.get("input") or {}).get("format") or session.get("input_audio_format"),
                    "voice": (audio.get("output") or {}).get("voice") or session.get("voice"),
                }
                if first.get("type") == "error":
                    row["error"] = short(json.dumps(first))
            row["turns"] = [await one_turn(ws, p) for p in PROMPTS]
    except InvalidStatus as e:
        row["error"] = f"handshake rejected: HTTP {e.response.status_code} {short((e.response.body or b'').decode('utf-8', 'replace'))}"
    except ConnectionClosed as e:
        row["error"] = f"connection closed: code={e.rcvd.code if e.rcvd else None} reason={short(e.rcvd.reason if e.rcvd else '')}"
    except Exception as e:  # report whatever the gateway did, verbatim
        row["error"] = short(f"{type(e).__name__}: {e}")
    return row


async def main() -> None:
    base, key = gateway()
    ws_base = base.replace("https://", "wss://").replace("http://", "ws://")
    args = sys.argv[1:]
    label = next((a.split("=", 1)[1] for a in args if a.startswith("name=")), "")
    models = [a for a in args if "=" not in a] or MODELS
    rows = []
    for model in models:
        row = await probe(ws_base, key, model)
        rows.append(row)
        say(f"{model}: connect={row.get('connect_ms')} first_event={row.get('first_event')} {row.get('error', '')}")
        for t in row.get("turns", []):
            say(f"    first_audio={t.get('first_audio_ms')} total={t.get('total_ms')} audio_bytes={t.get('audio_bytes')} "
                f"status={t.get('status')} text={t.get('transcript')!r} {t.get('errors', '')} {t.get('timeout', '')}")
    save("05_realtime" + (f"_{label}" if label else ""),
         {"request": "websocket /v1/realtime?model=..., header Authorization: Bearer <key>", "rows": rows})


def clip_pcm(rate: int) -> bytes:
    """The short clean clip as 16-bit PCM at the session's input rate."""
    import numpy as np

    x = read_wav_16k(AUDIO / "short_clean.wav")
    if rate == 24000:
        x = resample(x, 3, 2, 16000)
    return np.clip(np.round(x), -32768, 32767).astype("<i2").tobytes()


async def collect(ws, holder: dict, turn: dict, idle: float = 12.0) -> None:
    """Read events until response.done; times count from holder['clock'] once it is set."""
    audio_bytes, transcript, heard, counts = 0, "", "", {}
    try:
        while True:
            event = json.loads(await asyncio.wait_for(ws.recv(), timeout=idle))
            kind = event.get("type", "?")
            clock = holder.get("clock")
            at = clock.lap() if clock else None
            counts[kind] = counts.get(kind, 0) + 1
            if counts[kind] == 1:
                turn["events"].append([at, kind])
            if kind in AUDIO_DELTAS:
                turn.setdefault("first_audio_ms", at)
                audio_bytes += len(base64.b64decode(event.get("delta", "")))
            elif kind in TRANSCRIPT_DELTAS:
                transcript += event.get("delta", "")
            elif kind.startswith("conversation.item.input_audio_transcription") and event.get("transcript"):
                heard = event["transcript"]
            elif kind == "error":
                turn.setdefault("errors", []).append(short(json.dumps(event.get("error", event))))
            elif kind == "response.done":
                turn["status"] = (event.get("response") or {}).get("status")
                break
    except asyncio.TimeoutError:
        turn["timeout"] = f"no event for {idle:.0f} s"
    except ConnectionClosed as e:
        turn["closed"] = f"code={e.rcvd.code if e.rcvd else None} reason={short(e.rcvd.reason if e.rcvd else '')}"
    turn["audio_bytes"] = audio_bytes
    turn["reply_transcript"] = transcript
    if heard:
        turn["input_transcript"] = heard
    turn["event_counts"] = counts


async def spoken(ws_base: str, key: str, model: str, mode: str) -> dict:
    rate = 16000 if model.startswith("gemini/") else 24000
    pcm = clip_pcm(rate)
    step = rate * 2 // 10  # 100 ms of 16-bit mono
    chunks = [pcm[i:i + step] for i in range(0, len(pcm), step)]
    turn: dict = {"model": model, "mode": mode, "input_rate": rate, "clip": "short_clean", "events": []}
    detection = None if mode == "manual" else {"type": "server_vad", "silence_duration_ms": 500, "create_response": True}
    try:
        async with websockets.connect(f"{ws_base}/realtime?model={model}",
                                      additional_headers={"Authorization": f"Bearer {key}"},
                                      open_timeout=20, max_size=None) as ws:
            first = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
            turn["first_event"] = first.get("type")
            await ws.send(json.dumps({"type": "session.update", "session": {
                "type": "realtime",
                "instructions": "You are a truck voice assistant. Reply in one short sentence.",
                "audio": {"input": {"format": {"type": "audio/pcm", "rate": rate}, "turn_detection": detection}},
            }}))
            holder: dict = {}
            reader = asyncio.create_task(collect(ws, holder, turn))
            await asyncio.sleep(0.5)  # let session.updated (or an error) arrive before audio
            if mode == "manual":
                for chunk in chunks:
                    await ws.send(json.dumps({"type": "input_audio_buffer.append",
                                              "audio": base64.b64encode(chunk).decode()}))
                holder["clock"] = Clock()
                await ws.send(json.dumps({"type": "input_audio_buffer.commit"}))
                await ws.send(json.dumps({"type": "response.create"}))
            else:
                for chunk in chunks:
                    await ws.send(json.dumps({"type": "input_audio_buffer.append",
                                              "audio": base64.b64encode(chunk).decode()}))
                    await asyncio.sleep(0.1)
                holder["clock"] = Clock()  # end of speech
                silence = base64.b64encode(bytes(step)).decode()
                for _ in range(40):  # up to 4 s of silence, stopping once the reply starts
                    if reader.done() or "first_audio_ms" in turn:
                        break
                    await ws.send(json.dumps({"type": "input_audio_buffer.append", "audio": silence}))
                    await asyncio.sleep(0.1)
            await reader
            turn["total_ms"] = holder["clock"].lap()
    except InvalidStatus as e:
        turn["error"] = f"handshake rejected: HTTP {e.response.status_code} {short((e.response.body or b'').decode('utf-8', 'replace'))}"
    except Exception as e:  # report whatever the gateway did, verbatim
        turn["error"] = short(f"{type(e).__name__}: {e}")
    return turn


async def audio_main(models: list[str]) -> None:
    base, key = gateway()
    ws_base = base.replace("https://", "wss://").replace("http://", "ws://")
    rows = []
    for model in models or ["openai/gpt-realtime-2.1", "openai/gpt-realtime-2.1-mini",
                            "gemini/gemini-3.8-live", "xai/grok-voice-3.0"]:
        for mode in ("manual", "vad"):
            turn = await spoken(ws_base, key, model, mode)
            rows.append(turn)
            say(f"{model:30s} {mode:6s} first_audio={turn.get('first_audio_ms')} total={turn.get('total_ms')} "
                f"reply={turn.get('reply_transcript')!r} {turn.get('errors', '')} {turn.get('timeout', '')} "
                f"{turn.get('closed', '')} {turn.get('error', '')}")
    save("05_realtime_audio", {"truth": "Find the nearest gas station with diesel on my route.", "rows": rows})


if __name__ == "__main__":
    if sys.argv[1:2] == ["audio"]:
        asyncio.run(audio_main(sys.argv[2:]))
    else:
        asyncio.run(main())
