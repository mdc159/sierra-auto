"""ElevenLabs directly (not through the gateway): streaming speech and transcription.

  * GET /v1/models and /v2/voices to confirm the model ids on this account and
    pick a voice (ids from ElevenLabs' docs, checked against the account).
  * POST /v1/text-to-speech/{voice_id}/stream?output_format=pcm_24000 with
    {text, model_id}: time to first audio byte and chunk timeline.
  * POST /v1/speech-to-text (multipart model_id, file): latency and accuracy on
    the same clips the gateway models heard.

Usage: python p06_elevenlabs.py
"""
from __future__ import annotations

import json

import httpx

from probe_common import AUDIO, RESULTS, Clock, elevenlabs_key, save, say, short, word_error_rate

API = "https://api.elevenlabs.io"
TTS_PLAN = [  # (model_id, which text)
    ("eleven_flash_v2_5", "short"),
    ("eleven_v4_turbo", "short"),
    ("eleven_flash_v2_5", "short"),
    ("eleven_v4_turbo", "short"),
    ("eleven_v4", "short"),
    ("eleven_flash_v2_5", "long"),
    ("eleven_v4_turbo", "long"),
]
STT_MODEL = "scribe_v2"
STT_PLAN = ["short_clean", "long_clean", "short_clean", "long_noisy", "long_heavy"]
SHORT = "The nearest diesel is two miles ahead on the right."
LONG = (
    "The nearest diesel is two miles ahead on the right, at the truck stop just past exit 124. "
    "After that the next one is in Madras, about thirty five miles north. "
    "Your transmission is at one hundred ninety degrees, which is normal for this grade, "
    "and there is a rest area with truck parking eighteen miles ahead."
)


def speak(http: httpx.Client, key: str, voice_id: str, model_id: str, text: str) -> dict:
    row: dict = {"model_id": model_id, "chars": len(text)}
    clock = Clock()
    try:
        with http.stream("POST", f"{API}/v1/text-to-speech/{voice_id}/stream",
                         params={"output_format": "pcm_24000"},
                         headers={"xi-api-key": key},
                         json={"text": text, "model_id": model_id}) as r:
            row["status"] = r.status_code
            row["headers_ms"] = clock.lap()
            row["content_type"] = r.headers.get("content-type")
            if r.status_code != 200:
                row["error"] = short(r.read().decode("utf-8", "replace"))
                return row
            timeline, total = [], 0
            for chunk in r.iter_raw():
                if not chunk:
                    continue
                if not timeline:
                    row["first_byte_ms"] = clock.lap()
                total += len(chunk)
                timeline.append((clock.lap(), total))
            row["total_ms"] = clock.lap()
    except httpx.HTTPError as e:
        row["error"] = short(repr(e))
        return row
    row["bytes"] = total
    row["chunks"] = len(timeline)
    row["audio_seconds"] = round(total / 48000, 2)
    if timeline:
        row["ms_at_25_50_75_pct"] = [next(t for t, n in timeline if n >= total * f) for f in (0.25, 0.5, 0.75)]
    return row


def main() -> None:
    key = elevenlabs_key()
    out: dict = {"api": API, "tts": [], "stt": []}
    clips = json.loads((RESULTS / "01_clips.json").read_text(encoding="utf-8"))["clips"]
    with httpx.Client(timeout=120) as http:
        rtt = []
        for _ in range(3):
            clock = Clock()
            http.get(f"{API}/v1/models", headers={"xi-api-key": key})
            rtt.append(clock.lap())
        out["api_rtt_ms"] = rtt

        r = http.get(f"{API}/v1/models", headers={"xi-api-key": key})
        out["models_status"] = r.status_code
        if r.status_code == 200:
            out["tts_models_on_account"] = sorted(m["model_id"] for m in r.json() if m.get("can_do_text_to_speech"))
        else:
            out["models_error"] = short(r.text)
        say("GET /v1/models", r.status_code, out.get("tts_models_on_account"), "rtt ms", rtt)

        r = http.get(f"{API}/v2/voices", params={"page_size": 20}, headers={"xi-api-key": key})
        voices = r.json().get("voices", []) if r.status_code == 200 else []
        out["voices_status"] = r.status_code
        if not voices:
            out["voices_error"] = short(r.text)
            say("no voices:", r.status_code, short(r.text, 300))
            save("06_elevenlabs", out)
            return
        voice = next((v for v in voices if v.get("category") == "premade"), voices[0])
        out["voice"] = {"voice_id": voice["voice_id"], "name": voice.get("name"), "category": voice.get("category")}
        say("voice:", out["voice"])

        for model_id, which in TTS_PLAN:
            row = speak(http, key, voice["voice_id"], model_id, SHORT if which == "short" else LONG)
            row["input"] = which
            out["tts"].append(row)
            say(f"tts {model_id:20s} {which:5s} {row.get('status')} first={row.get('first_byte_ms')} "
                f"total={row.get('total_ms')} audio_s={row.get('audio_seconds')} {row.get('error', '')[:300]}")

        for clip in STT_PLAN:
            truth = clips[clip.split("_")[0]]
            audio = (AUDIO / f"{clip}.wav").read_bytes()
            clock = Clock()
            r = http.post(f"{API}/v1/speech-to-text", headers={"xi-api-key": key},
                          files={"file": (f"{clip}.wav", audio, "audio/wav")},
                          data={"model_id": STT_MODEL, "language_code": "en", "tag_audio_events": "false"})
            row = {"model_id": STT_MODEL, "clip": clip, "clip_seconds": truth["seconds"],
                   "status": r.status_code, "total_ms": clock.lap()}
            if r.status_code == 200:
                row["text"] = r.json().get("text", "")
                row["wer"] = word_error_rate(truth["text"], row["text"])
            else:
                row["error"] = short(r.text)
            out["stt"].append(row)
            say(f"stt {STT_MODEL} {clip:12s} {r.status_code} {row['total_ms']} ms wer={row.get('wer')} {row.get('error', '')[:300]}")
    save("06_elevenlabs", out)


if __name__ == "__main__":
    main()
