#!/usr/bin/env python3
"""Publish Sierra's Hermes route to an EXISTING Studio54 phone project.

Run on Studio54; the bridge token arrives as JSON {"token": "..."} on stdin,
never on the command line or in a credential export. This reconciles only the
two Sierra profile entries. It does not enroll identities or change gateway keys.
See docs/MICROPHONE-DIAGNOSTICS.md for deployment and verification.
"""
import argparse
import json
import pathlib
import sys
import urllib.parse
import urllib.request


def profile_values(base_url, token):
    url = urllib.parse.urlsplit(base_url)
    if (url.scheme != "https" or not (url.hostname or "").endswith(".ts.net")
            or url.username or url.password or url.query or url.fragment
            or url.path.rstrip("/") not in ("", "/v1")):
        raise ValueError("Use an HTTPS Tailscale bridge base URL, optionally ending /v1")
    if not isinstance(token, str) or not token.strip() or any(c.isspace() for c in token):
        raise ValueError("A nonempty bridge token is required on stdin")
    return {"SIERRA_BRIDGE_URL": base_url.rstrip("/"), "SIERRA_BRIDGE_TOKEN": token}


def verify_bridge(values):
    base = values["SIERRA_BRIDGE_URL"]
    if not base.endswith("/v1"):
        base += "/v1"
    request = urllib.request.Request(base + "/models", headers={
        "Authorization": "Bearer " + values["SIERRA_BRIDGE_TOKEN"],
    })
    with urllib.request.urlopen(request, timeout=15) as response:
        data = json.load(response)
    if not any(m.get("id") == "hermes" and m.get("owned_by") == "sierra-bridge/hermes"
               for m in data.get("data", [])):
        raise ValueError("The authenticated bridge did not advertise the Hermes adapter")


def reconcile(vault, project_slug, values):
    project_id = vault.find_project(project_slug)
    if not project_id:
        raise ValueError("Phone project must already be enrolled through Studio54")
    result = vault.set_secrets(project_id, "/", values)
    # Remove only the matching aliases created during this setup's pre-release probe.
    # Do not delete unrelated or subsequently changed vault values.
    for old, current in (("SIERRA_HERMES_BASE_URL", "SIERRA_BRIDGE_URL"),
                         ("SIERRA_HERMES_API_KEY", "SIERRA_BRIDGE_TOKEN")):
        if vault.get_secret(project_id, "/", old) == values[current]:
            if vault.get_secret(project_id, "/", current) != values[current]:
                raise ValueError("Profile verification failed before alias cleanup")
            status, _ = vault.api("DELETE", f"/api/v4/secrets/{old}", {
                "projectId": project_id, "environment": "prod", "secretPath": "/",
            })
            if status not in (200, 404):
                raise ValueError("Profile saved but alias cleanup failed")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="s54-agent-sierra-a54")
    parser.add_argument("--bridge-url", default="https://victoria.tailfedd3b.ts.net:8443/v1")
    parser.add_argument("--studio54", type=pathlib.Path, default=pathlib.Path("/opt/studio54"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check-only", action="store_true", help="Validate from a client on the phone's network path; write nothing")
    mode.add_argument("--client-verified", action="store_true", help="Publish the identical input after --check-only succeeded on the client")
    args = parser.parse_args()
    try:
        values = profile_values(args.bridge_url, json.load(sys.stdin)["token"])
        if not args.client_verified:
            verify_bridge(values)
        if args.check_only:
            print("Hermes bridge authentication and adapter verified from client.")
            return 0
        sys.path.insert(0, str(args.studio54))
        import bootstrap
        bootstrap.ENV_FILE = str(args.studio54 / ".env")
        bootstrap.INFISICAL = "http://100.88.239.16:8080"
        import enroll
        env = bootstrap.read_env()
        vault = enroll.AgentVault(env["CONTROLLER_TOKEN"], env["INFISICAL_ORG_ID"])
        created, updated = reconcile(vault, args.project, values)
        print(f"Hermes profile verified: {created} entries created, {updated} updated in {args.project}.")
    except Exception as error:
        # Provider errors may contain credentials; report a class/status, never their raw body.
        print(f"Profile not published: {type(error).__name__}; status={getattr(error, 'code', 'n/a')}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
