"""Streaming chat through the gateway: time to first token, and tool calls.

POST {base}/chat/completions with stream=true.
  * plain: a driving question answered in two sentences (twice per model);
  * tools: a request that should call find_places(category) (once per model).
Models are interleaved in rounds so no model always goes first.

Usage: python p04_chat.py                      default candidates
       python p04_chat.py model-a model-b      only these models
       python p04_chat.py effort=low name=low model-a
                                               add reasoning_effort to each request and
                                               write results/04_chat_low.json
       python p04_chat.py runs=5 mix tools=0 name=repeat model-a
                                               five plain runs alternating two questions,
                                               no tool run (a look at run-to-run spread)
"""
from __future__ import annotations

import json
import re
import sys

import httpx

from probe_common import Clock, gateway, gateway_rtt, litellm_meta, save, say, short

MODELS = [
    "groq/openai/gpt-oss-20b",
    "groq/openai/gpt-oss-120b",
    "gemini/gemini-3.5-flash-lite",
    "gemini/gemini-3.8-flash",
    "openai/gpt-6-luna",
    "openai/gpt-4.1-nano",
    "xai/grok-4.20-non-reasoning",
]
SYSTEM = ("You are Sierra, a voice assistant in a pickup truck. Replies are spoken aloud. "
          "Answer in exactly two short sentences.")
QUESTION = "I'm towing a camper uphill and my transmission temperature just hit 215 degrees. Should I be worried?"
PLACES_QUESTION = "Find the nearest gas station with diesel on my route."
TOOL_REQUEST = "Find me a diesel station nearby."
TOOLS = [{
    "type": "function",
    "function": {
        "name": "find_places",
        "description": "Find places of one category near the truck's current position.",
        "parameters": {
            "type": "object",
            "properties": {
                "category": {
                    "type": "string",
                    "enum": ["fuel", "diesel", "food", "coffee", "rest_area", "hotel", "parking"],
                    "description": "The kind of place to look for.",
                },
            },
            "required": ["category"],
        },
    },
}]
# A sentence is over when its terminator is followed by white space. Waiting for
# that next character avoids cutting "3.2 miles" at "3." when a chunk ends there;
# a reply that ends without trailing space is closed when the stream ends.
SENTENCE_END = re.compile(r"[.!?][\"')\]]?\s")


def stream_chat(http: httpx.Client, base: str, key: str, body: dict) -> dict:
    row: dict = {"model": body["model"]}
    text, reasoning_chars, calls = "", 0, {}
    clock = Clock()
    try:
        with http.stream("POST", f"{base}/chat/completions",
                         headers={"Authorization": f"Bearer {key}"}, json=body) as r:
            row["status"] = r.status_code
            row["headers_ms"] = clock.lap()
            row.update(litellm_meta(r.headers))
            if r.status_code != 200:
                row["error"] = short(r.read().decode("utf-8", "replace"))
                return row
            for line in r.iter_lines():
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                event = json.loads(data)
                if event.get("usage"):
                    row["usage"] = {k: v for k, v in event["usage"].items() if isinstance(v, int)}
                for choice in event.get("choices", []):
                    delta = choice.get("delta") or {}
                    if choice.get("finish_reason"):
                        row["finish_reason"] = choice["finish_reason"]
                    thought = delta.get("reasoning_content") or delta.get("reasoning")
                    if thought:
                        row.setdefault("first_reasoning_ms", clock.lap())
                        reasoning_chars += len(thought)
                    if delta.get("content"):
                        row.setdefault("first_token_ms", clock.lap())
                        text += delta["content"]
                        if "first_sentence_ms" not in row and SENTENCE_END.search(text):
                            row["first_sentence_ms"] = clock.lap()
                    for tc in delta.get("tool_calls") or []:
                        row.setdefault("first_tool_delta_ms", clock.lap())
                        slot = calls.setdefault(tc.get("index", 0), {"name": "", "arguments": ""})
                        fn = tc.get("function") or {}
                        slot["name"] += fn.get("name") or ""
                        slot["arguments"] += fn.get("arguments") or ""
            row["total_ms"] = clock.lap()
            if text:
                row.setdefault("first_sentence_ms", row["total_ms"])
    except (httpx.HTTPError, ValueError) as e:
        row["error"] = short(repr(e))
        return row
    row["text"] = text
    if reasoning_chars:
        row["reasoning_chars"] = reasoning_chars
    if calls:
        row["tool_calls"] = [calls[i] for i in sorted(calls)]
    return row


def judge_tool_call(row: dict) -> str:
    calls = row.get("tool_calls")
    if not calls:
        return "no tool call"
    call = calls[0]
    try:
        args = json.loads(call["arguments"])
    except ValueError:
        return "arguments are not valid JSON"
    if call["name"] != "find_places":
        return f"wrong function name {call['name']!r}"
    if not isinstance(args, dict) or "category" not in args:
        return "missing category"
    return "ok" if args["category"] in ("diesel", "fuel") else f"ok but odd category {args['category']!r}"


def main() -> None:
    base, key = gateway()
    args = sys.argv[1:]
    extra = {}
    for a in [a for a in args if a.startswith("effort=")]:
        extra["reasoning_effort"] = a.split("=", 1)[1]
    models = [a for a in args if "=" not in a and a != "mix"] or MODELS
    runs = int(next((a.split("=", 1)[1] for a in args if a.startswith("runs=")), 2))
    with_tools = "tools=0" not in args
    questions = [QUESTION, PLACES_QUESTION] if "mix" in args else [QUESTION]
    plain, tools = [], []
    with httpx.Client(timeout=120) as http:
        rtt = gateway_rtt(http, base)
        for attempt in range(1, runs + 1):
            question = questions[(attempt - 1) % len(questions)]
            for model in models:
                row = stream_chat(http, base, key, {
                    "model": model, "stream": True, "stream_options": {"include_usage": True},
                    "max_tokens": 400, **extra,
                    "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": question}],
                })
                row["attempt"] = attempt
                row["question"] = "places" if question == PLACES_QUESTION else "transmission"
                plain.append(row)
                say(f"{model:32s} plain #{attempt} {row.get('status')} first_token={row.get('first_token_ms')} "
                    f"first_sentence={row.get('first_sentence_ms')} total={row.get('total_ms')} "
                    f"reasoning={row.get('reasoning_chars', 0)} {row.get('error', '')[:300]}")
        for model in models if with_tools else []:
            row = stream_chat(http, base, key, {
                "model": model, "stream": True, "max_tokens": 400, **extra,
                "tools": TOOLS, "tool_choice": "auto",
                "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": TOOL_REQUEST}],
            })
            row["verdict"] = judge_tool_call(row) if row.get("status") == 200 else "request failed"
            tools.append(row)
            say(f"{model:32s} tools {row.get('status')} {row['verdict']} total={row.get('total_ms')} "
                f"calls={row.get('tool_calls')} {row.get('error', '')[:300]}")
    label = next((a.split("=", 1)[1] for a in args if a.startswith("name=")), "")
    name = "04_chat" + (f"_{label}" if label else "")
    save(name, {"gateway_rtt_ms": rtt, "system": SYSTEM, "question": QUESTION, "tool_request": TOOL_REQUEST,
                "tools": TOOLS, "extra_params": extra, "plain": plain, "tool_runs": tools})


if __name__ == "__main__":
    main()
