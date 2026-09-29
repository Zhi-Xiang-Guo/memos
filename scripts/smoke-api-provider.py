#!/usr/bin/env python3
"""Check a deployed real-provider memory pipeline, idempotency and scope isolation."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--timeout", type=float, default=240)
    arguments = parser.parse_args()
    if not os.environ.get("MEMOS_JWT_HMAC_SECRET"):
        parser.error("export the deployment's MEMOS_JWT_HMAC_SECRET before running")
    root = Path(__file__).resolve().parent
    suffix = uuid.uuid4().hex

    def token(user: str, roles: tuple[str, ...]) -> str:
        command = [
            "python3",
            str(root / "generate-dev-jwt.py"),
            "--tenant",
            f"api-smoke-{suffix}",
            "--user",
            user,
            "--agent",
            "api-smoke",
            "--subject",
            "local-smoke",
        ]
        for role in roles:
            command.extend(["--role", role])
        return subprocess.check_output(command, text=True).strip()

    own_token = token("owner", ("USER", "OPERATOR"))
    foreign_token = token("other-user", ("USER",))

    def request(
        path: str, body: dict | None = None, bearer: str = own_token, key: str | None = None
    ) -> tuple[int, dict]:
        headers = {"Authorization": f"Bearer {bearer}", "Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        data = json.dumps(body).encode() if body is not None else None
        try:
            with urlopen(
                Request(arguments.base_url + path, data=data, headers=headers), timeout=90
            ) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    body = {
        "sourceId": f"source-{suffix}",
        "sessionId": f"session-{suffix}",
        "actorType": "USER",
        "sourceType": "CONVERSATION_MESSAGE",
        "trustLevel": "DIRECT_USER",
        "occurredAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "payload": {"content": "I prefer a dark editor theme."},
    }
    code, receipt = request("/v1/source-events", body, key=f"key-{suffix}")
    assert code == 202, f"ingestion HTTP {code}: {receipt.get('code')}"
    duplicate_code, duplicate = request("/v1/source-events", body, key=f"key-{suffix}")
    assert duplicate_code in (200, 202)
    assert receipt["sourceEventId"] == duplicate["sourceEventId"], (
        "duplicate ingestion changed identity"
    )
    source_path = f"/v1/source-events/{receipt['sourceEventId']}/materialization"
    deadline = time.monotonic() + arguments.timeout
    while time.monotonic() < deadline:
        code, state = request(source_path)
        assert code == 200
        if state["status"] == "SUCCEEDED":
            break
        assert state["status"] != "FAILED", (
            "materialization failed; inspect content-safe job error classes"
        )
        time.sleep(1)
    else:
        raise TimeoutError("source-to-projection settlement exceeded the smoke deadline")

    query = {
        "query": "Which editor theme do I prefer?",
        "mode": "VECTOR_ONLY",
        "limit": 5,
        "maxTokens": 800,
    }
    code, retrieval = request("/v1/retrieval/trace", query)
    assert code == 200, f"retrieval HTTP {code}: {retrieval.get('code')}"
    assert retrieval["trace"]["embeddingProvider"] == "openai-compatible"
    assert retrieval["trace"]["embeddingInputTokens"] > 0
    assert retrieval["context"]["selected"] > 0
    assert "dark" in retrieval["context"]["rendered"].lower()
    assert retrieval["context"]["tokens"] <= 800
    assert retrieval["context"]["tokenCountProviderCalls"] > 0
    code, foreign = request("/v1/retrieval", query, bearer=foreign_token)
    assert code == 200 and foreign["memories"] == [], (
        "foreign user could retrieve the owner's memory"
    )
    code, _ = request(source_path, bearer=foreign_token)
    assert code == 404, "foreign user could inspect the owner's source"
    print(
        json.dumps(
            {
                "result": "PASS",
                "pipeline": "ingest/extract/authority/embed/vector-search/context",
                "provider": retrieval["trace"]["embeddingProvider"],
                "embeddingModelVersion": retrieval["trace"]["embeddingModelVersion"],
                "selectedMemories": retrieval["context"]["selected"],
                "contextTokens": retrieval["context"]["tokens"],
                "idempotency": "PASS",
                "userIsolation": "PASS",
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
