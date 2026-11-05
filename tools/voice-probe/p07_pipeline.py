"""End to end, turn-based: recorded speech in, first spoken audio out.

The clock starts when the clip is complete (the moment the driver stops
talking and the app decides the turn is over) and stops at the first audio
byte of the spoken reply:

    upload clip -> transcript -> streaming chat -> first full sentence
                -> text-to-speech request for that sentence -> first audio byte

The silence the app waits for before deciding the turn is over is NOT in this
figure; add it on top. Each combination runs twice on the short clip and once
on the long one, over connections that are already open.

Usage: python p07_pipeline.py                         chat model as listed in COMBOS
       python p07_pipeline.py chat=MODEL name=LABEL   another chat model, to results/07_pipeline_LABEL.json
       python p07_pipeline.py stt=MODEL combos=1,2    another gateway speech-to-text model; only
                                                      the first and second combinations
"""
from __future__ import annotations

import json
import re
import sys

import httpx

from probe_common import AUDIO, RESULTS, Clock, elevenlabs_key, gateway, gateway_rtt, save, say, short

ELEVEN = "https://api.elevenlabs.io"
SYSTEM = ("You are Sierra, a voice assistant in a pickup truck. Replies are spoken aloud. "
          "Answer in exactly two short sentences.")
# Terminator followed by white space; see p04_chat.py.
SENTENCE_END = re.compile(r"[.!?][\"')\]]?\s")

COMBOS = [
    {"name": "all gateway", "stt": ("gateway", "groq/whisper-large-v3-turbo"),
     "chat": "xai/grok-4.20-non-reasoning", "tts": ("gateway", "openai/gpt-4o-mini-tts")},
    {"name": "gateway text, ElevenLabs voice", "stt": ("gateway", "groq/whisper-large-v3-turbo"),
     "chat": "xai/grok-4.20-non-reasoning", "tts": ("elevenlabs", "eleven_flash_v2_5")},
    {"name": "ElevenLabs ears and voice, gateway chat", "stt": ("elevenlabs", "scribe_v2"),
     "chat": "xai/grok-4.20-non-reasoning", "tts": ("elevenlabs", "eleven_flash_v2_5")},
]
PLAN = ["short_clean", "short_clean", "long_clean"]


class Stack:
    def __init__(self) -> None:
        self.base, self.key = gateway()
        self.xi = elevenlabs_key()
        self.voice = json.loads((RESULTS / "06_elevenlabs.json").read_text(encoding="utf-8"))["voice"]["voice_id"]
        self.gw = httpx.Client(timeout=60)
        self.el = httpx.Client(timeout=60)

    def transcribe(self, where: str, model: str, name: str, audio: bytes) -> str:
        if where == "gateway":
            r = self.gw.post(f"{self.base}/audio/transcriptions", headers={"Authorization": f"Bearer {self.key}"},
                             files={"file": (name, audio, "audio/wav")},
                             data={"model": model, "response_format": "json", "language": "en"})
        else:
            r = self.el.post(f"{ELEVEN}/v1/speech-to-text", headers={"xi-api-key": self.xi},
                             files={"file": (name, audio, "audio/wav")},
                             data={"model_id": model, "language_code": "en", "tag_audio_events": "false"})
        r.raise_for_status()
        return r.json()["text"].strip()

    def first_audio(self, where: str, model: str, sentence: str) -> None:
        """Send one sentence to text-to-speech and return as soon as audio starts arriving."""
        if where == "gateway":
            request = self.gw.stream("POST", f"{self.base}/audio/speech",
                                     headers={"Authorization": f"Bearer {self.key}"},
                                     json={"model": model, "voice": "alloy", "input": sentence, "response_format": "pcm"})
        else:
            request = self.el.stream("POST", f"{ELEVEN}/v1/text-to-speech/{self.voice}/stream",
                                     params={"output_format": "pcm_24000"}, headers={"xi-api-key": self.xi},
                                     json={"text": sentence, "model_id": model})
        with request as r:
            r.raise_for_status()
            for chunk in r.iter_raw():
                if chunk:
                    return

    def run(self, combo: dict, clip: str) -> dict:
        audio = (AUDIO / f"{clip}.wav").read_bytes()
        row: dict = {"combo": combo["name"], "clip": clip}
        clock = Clock()
        try:
            heard = self.transcribe(*combo["stt"], f"{clip}.wav", audio)
            row["stt_ms"] = clock.lap()
            row["heard"] = heard
            text = ""
            with self.gw.stream("POST", f"{self.base}/chat/completions",
                                headers={"Authorization": f"Bearer {self.key}"},
                                json={"model": combo["chat"], "stream": True, "max_tokens": 400,
                                      "messages": [{"role": "system", "content": SYSTEM},
                                                   {"role": "user", "content": heard}]}) as r:
                r.raise_for_status()
                for line in r.iter_lines():
                    if not line.startswith("data:") or line[5:].strip() == "[DONE]":
                        continue
                    for choice in json.loads(line[5:]).get("choices", []):
                        piece = (choice.get("delta") or {}).get("content")
                        if piece:
                            row.setdefault("first_token_at_ms", clock.lap())
                            text += piece
                    if SENTENCE_END.search(text):
                        break
            row["first_sentence_at_ms"] = clock.lap()
            row["sentence"] = text.strip()
            self.first_audio(*combo["tts"], text.strip())
            row["first_audio_at_ms"] = clock.lap()
            row["stages_ms"] = {"stt": row["stt_ms"],
                                "chat_to_first_sentence": row["first_sentence_at_ms"] - row["stt_ms"],
                                "tts_to_first_byte": row["first_audio_at_ms"] - row["first_sentence_at_ms"]}
        except (httpx.HTTPError, KeyError, ValueError) as e:
            row["error"] = short(repr(e))
        return row


def main() -> None:
    args = dict(a.split("=", 1) for a in sys.argv[1:] if "=" in a)
    wanted = [int(n) for n in args.get("combos", "1,2,3").split(",")]
    combos = [c for i, c in enumerate(COMBOS, 1) if i in wanted]
    for combo in combos:
        combo["chat"] = args.get("chat", combo["chat"])
        if combo["stt"][0] == "gateway" and args.get("stt"):
            combo["stt"] = ("gateway", args["stt"])
    stack = Stack()
    rtt = gateway_rtt(stack.gw, stack.base)
    stack.el.get(f"{ELEVEN}/v1/user", headers={"xi-api-key": stack.xi})  # open the ElevenLabs connection
    rows = []
    for clip in PLAN:
        for combo in combos:
            row = stack.run(combo, clip)
            rows.append(row)
            say(f"{combo['name']:40s} {clip:11s} end_to_end={row.get('first_audio_at_ms')} "
                f"stages={row.get('stages_ms')} {row.get('error', '')}")
    save("07_pipeline" + (f"_{args['name']}" if args.get("name") else ""), {"gateway_rtt_ms": rtt, "combos": combos, "system": SYSTEM, "rows": rows})


if __name__ == "__main__":
    main()
