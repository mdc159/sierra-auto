#!/usr/bin/env -S uv run --script
"""Reconcile one phone-to-bridge Tailscale grant; preserve all other policy rules.

Run with OAuth client credentials supplied by the Studio54 launcher. A legacy
TAILSCALE_API_KEY is also supported. Default is read-only.
Use --apply to validate and save with the server's ETag. Evidence must be a
new directory under the canonical checkout's .local; the destination is
validated before any API request is made.
"""
from __future__ import annotations

import argparse
import base64
import copy
import ipaddress
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.tailscale.com/api/v2/tailnet/-/acl"
TOKEN_URL = "https://api.tailscale.com/api/v2/oauth/token"


class TailnetAuth:
    """Mint short-lived tokens from a vault-held OAuth client; never persist tokens."""

    def __init__(self, opener, env=None, clock=time.monotonic):
        env = os.environ if env is None else env
        self.client_id = env.get("TAILSCALE_OAUTH_CLIENT_ID", "")
        self.client_secret = env.get("TAILSCALE_OAUTH_CLIENT_SECRET", "")
        self.api_key = env.get("TAILSCALE_API_KEY", "")
        self.opener, self.clock = opener, clock
        self.token, self.refresh_at = None, 0.0
        if bool(self.client_id) != bool(self.client_secret):
            raise RuntimeError("Incomplete Tailscale OAuth credentials in Studio54; both client ID and secret are required")
        self.oauth = bool(self.client_id and self.client_secret)
        if not self.oauth and not self.api_key:
            raise RuntimeError("Tailscale credentials missing; use the Studio54 launcher")

    def header(self):
        if not self.oauth:
            return "Basic " + base64.b64encode((self.api_key + ":").encode()).decode()
        if self.token is None or self.clock() >= self.refresh_at:
            payload = urllib.parse.urlencode({
                "grant_type": "client_credentials",
                "client_id": self.client_id,
                "client_secret": self.client_secret,
            }).encode()
            request = urllib.request.Request(TOKEN_URL, data=payload, method="POST", headers={
                "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"})
            with self.opener.open(request, timeout=25) as response:
                data = json.loads(response.read())
            token = data.get("access_token")
            lifetime = data.get("expires_in")
            if (not isinstance(token, str) or not token or any(c.isspace() for c in token)
                    or data.get("token_type", "").lower() != "bearer"
                    or not isinstance(lifetime, (int, float)) or isinstance(lifetime, bool)
                    or lifetime <= 0):
                raise RuntimeError("Tailscale returned an invalid OAuth token response; no credentials logged")
            self.token = token
            self.refresh_at = self.clock() + lifetime - min(60, lifetime / 10)
        return "Bearer " + self.token

    def invalidate(self):
        self.token = None


def api_request(opener, auth, method="GET", body=None, suffix="", etag=None):
    for attempt in range(2):
        headers = {"Authorization": auth.header(), "Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if etag:
            headers["If-Match"] = etag
        request = urllib.request.Request(API + suffix, data=body, headers=headers, method=method)
        try:
            with opener.open(request, timeout=25) as response:
                return response.read().decode(), response.headers.get("ETag")
        except urllib.error.HTTPError as error:
            # Only an expired/rejected access token gets one refresh. A revoked
            # client, missing scope, conflict or network error is surfaced as-is.
            if error.code != 401 or not auth.oauth or attempt != 0:
                raise
            error.close()
            auth.invalidate()


def parse_policy(raw: str) -> dict:
    # Preserve quoted strings while removing HuJSON comments and trailing commas.
    clean = re.sub(r'("(?:[^"\\]|\\.)*")|//[^\n]*|/\*.*?\*/',
                   lambda m: m.group(1) or "", raw, flags=re.S)
    clean = re.sub(r'("(?:[^"\\]|\\.)*")|,\s*(?=[}\]])',
                   lambda m: m.group(1) or "", clean)
    policy = json.loads(clean)
    if not isinstance(policy, dict):
        raise ValueError("Expected a policy object")
    return policy


def private_address(value: str) -> str:
    address = ipaddress.ip_address(value)
    networks = (ipaddress.ip_network("100.64.0.0/10"), ipaddress.ip_network("fd7a:115c:a1e0::/48"))
    if not any(address in net for net in networks if address.version == net.version):
        raise ValueError("Use a single Tailscale device address")
    return str(address)


def reconcile(policy: dict, source: str, destination: str, port: int) -> tuple[dict, bool]:
    source, destination = private_address(source), private_address(destination)
    if not 1 <= port <= 65535:
        raise ValueError("Invalid TCP port")
    updated = copy.deepcopy(policy)
    grant = {"src": [source], "dst": [destination], "ip": [f"tcp:{port}"]}
    grants = updated.setdefault("grants", [])
    if not isinstance(grants, list):
        raise ValueError("Expected grants array")
    if grant in grants:
        return updated, False
    grants.append(grant)
    return updated, True


def canonical_root(start: Path | None = None) -> Path:
    """Main checkout root for this script (linked worktrees resolve to their common root)."""
    current = (start or Path(__file__)).resolve()
    for candidate in [current, *current.parents]:
        marker = candidate / ".git"
        if marker.is_dir():
            return candidate
        if marker.is_file():
            try:
                text = marker.read_text(encoding="utf-8", errors="replace").strip()
            except OSError:
                continue
            if not text.startswith("gitdir:"):
                continue
            gitdir = Path(text[len("gitdir:"):].strip())
            if not gitdir.is_absolute():
                gitdir = candidate / gitdir
            # <main>/.git/worktrees/<name> -> <main>, the git-common-dir parent.
            common = gitdir.resolve().parents[2]
            if (common / ".git").is_dir():
                return common
    raise RuntimeError("Cannot locate the canonical checkout root; run from a sierra-auto checkout")


def validate_evidence(destination: Path, root: Path | None = None) -> Path:
    """Return the absolute evidence directory, or reject the destination.

    The destination must be a new directory strictly inside the canonical
    checkout's .local, reachable without traversing any symlink or junction.
    No network request or directory creation happens here.
    """
    root = (root or canonical_root()).resolve()
    local_root = root / ".local"
    target = Path(os.path.abspath(destination))
    if os.path.lexists(target):
        raise ValueError(f"Evidence directory already exists: {target}")
    prefix = os.path.normcase(str(local_root)) + os.sep
    if not os.path.normcase(str(target)).startswith(prefix):
        raise ValueError(f"--evidence must be a new directory under {local_root}; got {target}")
    ancestor = target.parent
    while True:
        if os.path.normcase(os.path.realpath(ancestor)) != os.path.normcase(os.path.abspath(ancestor)):
            raise ValueError(f"--evidence must not traverse a link: {ancestor}")
        if ancestor == root:
            break
        ancestor = ancestor.parent
    return target


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="Phone Tailscale IP")
    parser.add_argument("--destination", required=True, help="Bridge host Tailscale IP")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--evidence", type=Path, required=True,
                        help="New evidence directory under the canonical checkout's .local")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    # Validate inputs and the evidence destination before making any request.
    reconcile({}, args.source, args.destination, args.port)
    evidence = validate_evidence(args.evidence)
    opener = urllib.request.build_opener(NoRedirect())
    auth = TailnetAuth(opener)

    def request(method="GET", body=None, suffix="", etag=None):
        return api_request(opener, auth, method, body, suffix, etag)

    raw, etag = request()
    policy = parse_policy(raw)
    updated, changed = reconcile(policy, args.source, args.destination, args.port)
    evidence.mkdir(parents=True, exist_ok=False)
    (evidence / "before.hujson").write_text(raw, encoding="utf-8")
    payload = json.dumps(updated, indent=2).encode()
    (evidence / "candidate.json").write_bytes(payload)
    summary = {"source": args.source, "destination": args.destination, "tcp_port": args.port,
               "changed": changed, "applied": False}
    if args.apply and changed:
        if not etag:
            raise RuntimeError("No policy ETag returned; refusing an unprotected update")
        validation, _ = request("POST", payload, "/validate")
        if validation and json.loads(validation).get("errors"):
            raise RuntimeError("Policy validation returned errors; nothing applied")
        request("POST", payload, etag=etag)
        saved, _ = request()
        if parse_policy(saved) != updated:
            raise RuntimeError("Policy changed after save; inspect evidence before retrying")
        (evidence / "after.json").write_text(saved, encoding="utf-8")
        summary["applied"] = True
    (evidence / "result.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Tailscale API returned HTTP {error.code}; no credentials logged") from None
    except (ValueError, RuntimeError, OSError) as error:
        raise SystemExit(str(error)) from None
