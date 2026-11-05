"""Make the test clips: speech with known text, 16 kHz mono 16-bit WAV.

The speech comes from the gateway's own text-to-speech (24 kHz PCM), is
resampled to 16 kHz, and each clip also gets two variants with road-like noise
(15 dB and 3 dB signal-to-noise). Clips land in audio/ (not committed); the texts and sizes are written
to results/01_clips.json.

Usage: python p01_make_clips.py
"""
from __future__ import annotations

import wave

import httpx
import numpy as np

from probe_common import AUDIO, Clock, gateway, resample, save, say, short

TTS_MODEL = "openai/gpt-4o-mini-tts"
VOICE = "alloy"
SNR_DB = {"noisy": 15.0, "heavy": 3.0}  # light cabin noise, and a hard case

CLIPS = {
    "short": "Find the nearest gas station with diesel on my route.",
    "long": (
        "I'm towing the camper up Highway 97 toward Bend tonight. "
        "What is the transmission temperature right now, and is there a rest area "
        "with truck parking in the next thirty miles? "
        "Also remind me to check the trailer brakes."
    ),
}


def road_noise(n: int, rng: np.random.Generator) -> np.ndarray:
    """Low-frequency-weighted noise, roughly like cabin road noise."""
    white = rng.standard_normal(n)
    spectrum = np.fft.rfft(white)
    freqs = np.fft.rfftfreq(n, d=1 / 16000)
    spectrum *= 1 / np.maximum(freqs, 40.0) ** 0.8
    return np.fft.irfft(spectrum, n)


def write_wav(path, samples: np.ndarray) -> None:
    pcm = np.clip(np.round(samples), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(pcm.tobytes())


def main() -> None:
    base, key = gateway()
    AUDIO.mkdir(exist_ok=True)
    rng = np.random.default_rng(54)
    out: dict = {"tts_model": TTS_MODEL, "voice": VOICE, "snr_db": SNR_DB, "clips": {}}
    with httpx.Client(timeout=60) as http:
        for name, text in CLIPS.items():
            clock = Clock()
            r = http.post(
                f"{base}/audio/speech",
                headers={"Authorization": f"Bearer {key}"},
                json={"model": TTS_MODEL, "voice": VOICE, "input": text, "response_format": "pcm"},
            )
            took = clock.lap()
            if r.status_code != 200:
                say(name, "TTS failed", r.status_code, short(r.text))
                out["clips"][name] = {"error": short(r.text)}
                continue
            x = np.frombuffer(r.content[: len(r.content) // 2 * 2], dtype="<i2").astype(np.float64)
            y = resample(x, 2, 3, 24000)
            clean = AUDIO / f"{name}_clean.wav"
            write_wav(clean, y)
            speech_rms = np.sqrt(np.mean(y[np.abs(y) > 200] ** 2))
            for label, snr in SNR_DB.items():
                noise = road_noise(len(y), rng)
                noise *= speech_rms / (10 ** (snr / 20)) / np.sqrt(np.mean(noise**2))
                write_wav(AUDIO / f"{name}_{label}.wav", y + noise)
            seconds = round(len(y) / 16000, 2)
            out["clips"][name] = {
                "text": text,
                "seconds": seconds,
                "tts_ms": took,
                "clean_bytes": clean.stat().st_size,
            }
            say(f"{name}: {seconds}s, tts {took} ms, {clean.stat().st_size} bytes")
    save("01_clips", out)


if __name__ == "__main__":
    main()
