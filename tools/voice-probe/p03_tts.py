"""Text-to-speech through the gateway: formats, time to first audio, streaming.

POST {base}/audio/speech with JSON {model, voice, input, response_format}.
For each model:
  * a short sentence in pcm, wav, mp3 and opus (does the format work, and do
    the first bytes really look like that format);
  * the short sentence in pcm twice more, for a steadier time-to-first-byte;
  * a long passage in pcm, with the arrival time of every chunk, to tell real
    streaming from a gateway that buffers the whole file.

Usage: python p03_tts.py           main run
       python p03_tts.py extras    follow-up: a one-word input (the floor), two
                                   other OpenAI models, other Gemini models
"""
from __future__ import annotations

import sys

import httpx

from probe_common import Clock, gateway, gateway_rtt, litellm_meta, save, say, short

MODELS = {
    "openai/gpt-4o-mini-tts": "alloy",
    "openai/tts-1": "alloy",
    "gemini/gemini-3.8-flash-lite-tts": "Kore",
    "groq/canopylabs/orpheus-v1-english": "tara",
}
FORMATS = ["pcm", "wav", "mp3", "opus"]
SHORT = "The nearest diesel is two miles ahead on the right."
LONG = (
    "The nearest diesel is two miles ahead on the right, at the truck stop just past exit 124. "
    "After that the next one is in Madras, about thirty five miles north. "
    "Your transmission is at one hundred ninety degrees, which is normal for this grade, "
    "and there is a rest area with truck parking eighteen miles ahead."
)


def looks_like(fmt: str, head: bytes) -> str:
    if head[:4] == b"RIFF":
        return "wav"
    if head[:4] == b"OggS":
        return "ogg/opus"
    if head[:3] == b"ID3" or (len(head) > 1 and head[0] == 0xFF and head[1] & 0xE0 == 0xE0):
        return "mp3"
    if head[:4] == b"fLaC":
        return "flac"
    if head[:1] in (b"{", b"<") or head[:5] == b"data:" or head[:6] == b"event:":
        return "text"
    return "raw (pcm if that was asked for)"


def speak(http: httpx.Client, base: str, key: str, body: dict) -> dict:
    """One request; returns timing, sizes and the arrival timeline of the chunks."""
    row: dict = {"model": body["model"], "response_format": body.get("response_format"), "chars": len(body["input"])}
    clock = Clock()
    try:
        with http.stream("POST", f"{base}/audio/speech",
                         headers={"Authorization": f"Bearer {key}"}, json=body) as r:
            row["status"] = r.status_code
            row["headers_ms"] = clock.lap()
            row["content_type"] = r.headers.get("content-type")
            row["content_length_header"] = r.headers.get("content-length")
            row["transfer_encoding"] = r.headers.get("transfer-encoding")
            row.update(litellm_meta(r.headers))
            if r.status_code != 200:
                row["error"] = short(r.read().decode("utf-8", "replace"))
                return row
            timeline, total, head = [], 0, b""
            for chunk in r.iter_raw():
                if not chunk:
                    continue
                if not timeline:
                    row["first_byte_ms"] = clock.lap()
                if len(head) < 16:
                    head += chunk[:16]
                total += len(chunk)
                timeline.append((clock.lap(), total))
            row["total_ms"] = clock.lap()
    except httpx.HTTPError as e:
        row["error"] = short(repr(e))
        return row
    row["bytes"] = total
    row["chunks"] = len(timeline)
    row["looks_like"] = looks_like(body.get("response_format", ""), head)
    if timeline:
        def when(fraction: float) -> int:
            return next(t for t, n in timeline if n >= total * fraction)
        row["ms_at_25_50_75_pct"] = [when(0.25), when(0.5), when(0.75)]
        row["spread_ms"] = row["total_ms"] - row["first_byte_ms"]
    if body.get("response_format") == "pcm" and row["looks_like"].startswith("raw"):
        row["audio_seconds_if_24k_mono_s16"] = round(total / 48000, 2)
    return row


def show(tag: str, row: dict) -> None:
    say(f"{row['model']:36s} {tag:9s} {row.get('status')} first={row.get('first_byte_ms')} total={row.get('total_ms')} "
        f"bytes={row.get('bytes')} chunks={row.get('chunks')} like={row.get('looks_like')} "
        f"len_hdr={row.get('content_length_header')} {row.get('error', '')[:300]}")


def main() -> None:
    base, key = gateway()
    out: dict = {"request": "POST /v1/audio/speech json model,voice,input,response_format (body read as a stream)",
                 "short_text": SHORT, "long_text": LONG, "voices": MODELS,
                 "formats": [], "repeat_short_pcm": [], "long_pcm": [], "sse": []}
    with httpx.Client(timeout=120) as http:
        out["gateway_rtt_ms"] = gateway_rtt(http, base)
        for model, voice in MODELS.items():
            for fmt in FORMATS:
                row = speak(http, base, key, {"model": model, "voice": voice, "input": SHORT, "response_format": fmt})
                out["formats"].append(row)
                show(fmt, row)
        for _ in range(2):
            for model, voice in MODELS.items():
                row = speak(http, base, key, {"model": model, "voice": voice, "input": SHORT, "response_format": "pcm"})
                out["repeat_short_pcm"].append(row)
                show("pcm again", row)
        for model, voice in MODELS.items():
            row = speak(http, base, key, {"model": model, "voice": voice, "input": LONG, "response_format": "pcm"})
            out["long_pcm"].append(row)
            show("long pcm", row)
        # OpenAI's own streaming switch for the gpt-4o-mini-tts family
        row = speak(http, base, key, {"model": "openai/gpt-4o-mini-tts", "voice": "alloy", "input": LONG,
                                      "response_format": "pcm", "stream_format": "sse"})
        out["sse"].append(row)
        show("long sse", row)
    save("03_tts", out)


def extras() -> None:
    base, key = gateway()
    plan = [
        ("openai/gpt-4o-mini-tts", "alloy", "Okay.", "tiny"),
        ("openai/tts-1", "alloy", "Okay.", "tiny"),
        ("openai/gpt-4o-mini-tts", "alloy", "Okay.", "tiny"),
        ("openai/tts-1", "alloy", "Okay.", "tiny"),
        ("openai/gpt-4o-mini-tts-2025-12-15", "alloy", SHORT, "short"),
        ("openai/tts-1-hd", "alloy", SHORT, "short"),
        ("gemini/gemini-3.8-flash-tts", "Kore", SHORT, "short"),
        ("gemini/gemini-2.5-flash-preview-tts", "Kore", SHORT, "short"),
        ("gemini/gemini-3.8-flash-lite-tts", "Kore", LONG, "long"),
    ]
    rows = []
    with httpx.Client(timeout=120) as http:
        gateway_rtt(http, base, 1)
        for model, voice, text, tag in plan:
            row = speak(http, base, key, {"model": model, "voice": voice, "input": text, "response_format": "pcm"})
            row["input"] = tag
            rows.append(row)
            show(tag, row)
    save("03_tts_extras", {"rows": rows})


if __name__ == "__main__":
    extras() if sys.argv[1:] == ["extras"] else main()
