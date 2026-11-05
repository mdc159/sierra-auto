"""List what the launcher delivers (names only) and what the gateway serves.

Usage: python p00_models.py
"""
from __future__ import annotations

import os
import re

import httpx

from probe_common import Clock, gateway, save, say, short

FIELDS = ("mode", "litellm_provider", "supports_function_calling", "input_cost_per_token",
          "output_cost_per_token", "input_cost_per_second", "input_cost_per_character",
          "input_cost_per_audio_token", "output_cost_per_audio_token")
INTERESTING = re.compile(r"(OPENAI|LITELLM|LANGFUSE|ELEVEN|GROQ|GEMINI|GOOGLE|XAI|DEEPGRAM|CARTESIA)", re.I)


def main() -> None:
    names = sorted(n for n in os.environ if INTERESTING.search(n))
    say("environment variable names delivered (values never shown):")
    for n in names:
        say("  ", n, "(set)" if os.environ[n] else "(empty)")

    base, key = gateway()
    say("gateway base:", base)
    headers = {"Authorization": f"Bearer {key}"}
    out: dict = {"env_names": names, "base_url": base}
    with httpx.Client(timeout=30) as http:
        clock = Clock()
        r = http.get(f"{base}/models", headers=headers)
        out["models_status"] = r.status_code
        out["models_ms"] = clock.lap()
        ids = sorted(m["id"] for m in r.json().get("data", [])) if r.status_code == 200 else []
        out["model_count"] = len(ids)
        say(f"/v1/models -> {r.status_code}, {len(ids)} models, {out['models_ms']} ms")
        say("(per-model mode and price are written to results/00_models.json)")

        # LiteLLM extras: per-model mode (chat, audio_transcription, audio_speech, realtime)
        root = base[:-3] if base.endswith("/v1") else base
        for path in ("/model/info", "/health/readiness"):
            try:
                r = http.get(f"{root}{path}", headers=headers)
            except httpx.HTTPError as e:
                out[path] = {"error": short(repr(e))}
                continue
            entry: dict = {"status": r.status_code}
            if r.status_code == 200:
                try:
                    data = r.json()
                except ValueError:
                    data = None
                if path.endswith("readiness") and isinstance(data, dict):
                    entry["litellm_version"] = data.get("litellm_version")
                    entry["status_text"] = data.get("status")
                    entry["keys"] = sorted(data.keys())
                elif isinstance(data, dict) and isinstance(data.get("data"), list):
                    rows = []
                    for m in data["data"]:
                        info = m.get("model_info") or m
                        row = {"name": m.get("model_name") or m.get("model_group")}
                        for field in FIELDS:
                            if info.get(field) is not None:
                                row[field] = info[field]
                        rows.append(row)
                    rows.sort(key=lambda row: row["name"] or "")
                    entry["rows"] = rows
            else:
                entry["body"] = short(r.text, 300)
            out[path] = entry
            say(path, "->", r.status_code, entry.get("litellm_version", ""))
        # This key's own budget and spend so far, for keeping an eye on cost.
        r = http.get(f"{root}/key/info", headers=headers)
        entry = {"status": r.status_code}
        if r.status_code == 200:
            info = r.json().get("info") or {}
            for field in ("spend", "max_budget", "budget_duration", "budget_reset_at"):
                entry[field] = info.get(field)
        else:
            entry["body"] = short(r.text, 300)
        say("/key/info (shown here only, not saved) ->", entry)
    save("00_models", out)


if __name__ == "__main__":
    main()
