#!/usr/bin/env python3
"""Generate a short-lived local HS256 token for MemOS smoke tests."""

import argparse
import base64
import hashlib
import hmac
import json
import os
import time


def encoded(value: dict[str, object]) -> str:
    raw = json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--agent", required=True)
    parser.add_argument("--subject", required=True)
    parser.add_argument("--role", action="append", required=True)
    parser.add_argument("--lifetime-seconds", type=int, default=900)
    arguments = parser.parse_args()
    if arguments.lifetime_seconds < 1:
        parser.error("--lifetime-seconds must be positive")

    issuer = os.environ.get("MEMOS_JWT_ISSUER", "memos-local")
    audience = os.environ.get("MEMOS_JWT_AUDIENCE", "memos-api")
    secret = os.environ.get(
        "MEMOS_JWT_HMAC_SECRET", "memos-local-development-secret-change-me-now"
    ).encode("utf-8")
    if len(secret) < 32:
        parser.error("MEMOS_JWT_HMAC_SECRET must contain at least 32 bytes")

    now = int(time.time())
    header = encoded({"alg": "HS256", "typ": "JWT"})
    payload = encoded(
        {
            "agent_id": arguments.agent,
            "aud": [audience],
            "exp": now + arguments.lifetime_seconds,
            "iat": now,
            "iss": issuer,
            "roles": arguments.role,
            "sub": arguments.subject,
            "tenant_id": arguments.tenant,
            "user_id": arguments.user,
        }
    )
    signing_input = f"{header}.{payload}".encode("ascii")
    signature = base64.urlsafe_b64encode(
        hmac.new(secret, signing_input, hashlib.sha256).digest()
    ).rstrip(b"=")
    print(f"{header}.{payload}.{signature.decode('ascii')}")


if __name__ == "__main__":
    main()
