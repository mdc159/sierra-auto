"""Speech-to-text through the gateway: latency and accuracy per model.

POST {base}/audio/transcriptions, multipart: file, model, response_format=json,
language=en. Clean clips are sent twice per model (first and repeat), noisy
clips once (15 dB noise on both clips, 3 dB on the long one). Models are interleaved so no model always goes first.

Usage: python p02_stt.py
"""
from __future__ import annotations

import json

import httpx

from probe_common import AUDIO, RESULTS, Clock, gateway, gateway_rtt, litellm_meta, save, say, short, word_error_rate

MODELS = [
    "groq/whisper-large-v3-turbo",
    "groq/whisper-large-v3",
    "openai/gpt-4o-mini-transcribe",
    "openai/gpt-4o-transcribe",
]
PLAN = [("short_clean", 2), ("long_clean", 2), ("short_noisy", 1), ("long_noisy", 1), ("long_heavy", 1)]


def main() -> None:
    base, key = gateway()
    clips = json.loads((RESULTS / "01_clips.json").read_text(encoding="utf-8"))["clips"]
    rows = []
    with httpx.Client(timeout=90) as http:
        rtt = gateway_rtt(http, base)
        say("gateway round trip (GET /health/liveliness), ms:", rtt)
        for clip, repeats in PLAN:
            truth = clips[clip.split("_")[0]]
            audio = (AUDIO / f"{clip}.wav").read_bytes()
            for attempt in range(1, repeats + 1):
                for model in MODELS:
                    clock = Clock()
                    try:
                        r = http.post(
                            f"{base}/audio/transcriptions",
                            headers={"Authorization": f"Bearer {key}"},
                            files={"file": (f"{clip}.wav", audio, "audio/wav")},
                            data={"model": model, "response_format": "json", "language": "en"},
                        )
                    except httpx.HTTPError as e:
                        rows.append({"model": model, "clip": clip, "attempt": attempt, "error": short(repr(e))})
                        say(model, clip, "ERROR", short(repr(e), 200))
                        continue
                    total = clock.lap()
                    row = {
                        "model": model, "clip": clip, "attempt": attempt,
                        "clip_seconds": truth["seconds"], "status": r.status_code,
                        "total_ms": total, **litellm_meta(r.headers),
                    }
                    if r.status_code == 200:
                        heard = r.json().get("text", "")
                        row["text"] = heard
                        row["wer"] = word_error_rate(truth["text"], heard)
                    else:
                        row["error"] = short(r.text)
                    rows.append(row)
                    say(f"{model:34s} {clip:12s} #{attempt} {r.status_code} {total:5d} ms "
                        f"wer={row.get('wer')} gw={row.get('response_duration_ms')}")
    save("02_stt", {"gateway_rtt_ms": rtt, "request": "POST /v1/audio/transcriptions multipart file,model,response_format=json,language=en",
                    "truth": {k: v["text"] for k, v in clips.items()}, "rows": rows})


if __name__ == "__main__":
    main()
