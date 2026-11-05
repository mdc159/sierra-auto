"""Fake CLI agent for the command adapter: reads stdin, prints a reply.

Behaviour is chosen by the text so tests can cover errors and timeouts:
  "fail"   -> exit 3 with a message on stderr
  "silent" -> print nothing
  "sleep"  -> sleep 5 seconds first
  "json"   -> stdin is a JSON messages array; reply with its length
"""

import json
import os
import sys
import time


def main() -> int:
    text = sys.stdin.read()
    if text.startswith("["):
        messages = json.loads(text)
        roles = ",".join(m["role"] for m in messages)
        print(f"messages={len(messages)} roles={roles}")
        return 0
    if "fail" in text:
        print("boom: the agent broke", file=sys.stderr)
        return 3
    if "silent" in text:
        return 0
    if "sleep" in text:
        time.sleep(5)
    conv = os.environ.get("SIERRA_CONVERSATION_ID", "?")
    print(f"echo[{conv}]: {text}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
