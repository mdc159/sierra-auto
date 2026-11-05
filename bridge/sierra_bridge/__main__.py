"""``sierra-bridge`` command line."""

from __future__ import annotations

import argparse
import logging
import os
import signal
import sys

from . import __version__
from .config import CONFIG_ENV, TOKEN_ENV, ConfigError, load, token_from_env
from .server import create_server


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="sierra-bridge",
        description="OpenAI-compatible bridge from the Sierra Auto app to CLI, Pi, Hermes and other agents.",
    )
    p.add_argument("--config", default=os.environ.get(CONFIG_ENV, "bridge.toml"), help="TOML config file (default: $%s or ./bridge.toml)" % CONFIG_ENV)
    p.add_argument("--host", help="override [server] host")
    p.add_argument("--port", type=int, help="override [server] port")
    p.add_argument("--check", action="store_true", help="validate the config and token, then exit")
    p.add_argument("--log-level", default="INFO", choices=["DEBUG", "INFO", "WARNING", "ERROR"])
    p.add_argument("--version", action="version", version=f"sierra-bridge {__version__}")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=getattr(logging, args.log_level), format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        config = load(args.config)
        token = token_from_env()
        server = create_server(config, token, host=args.host, port=args.port)
    except ConfigError as e:
        print(f"sierra-bridge: {e}", file=sys.stderr)
        return 2
    except OSError as e:
        print(f"sierra-bridge: cannot listen: {e}", file=sys.stderr)
        return 2

    host, port = server.server_address[:2]
    if args.check:
        server.server_close()
        print(f"OK: {len(config.models)} model(s) configured; would listen on {host}:{port}")
        return 0

    logging.getLogger("sierra_bridge").info(
        "listening on http://%s:%d/v1 with models: %s (token from $%s)",
        host, port, ", ".join(config.models), TOKEN_ENV,
    )

    def stop(signum, frame):  # noqa: ARG001
        logging.getLogger("sierra_bridge").info("shutting down")
        server.shutdown()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        server.serve_forever()
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
