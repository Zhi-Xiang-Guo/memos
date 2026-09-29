#!/usr/bin/env python3
"""Exercise the credential-free memory -> retrieval -> tool -> SSE -> answer chain.

Run against the default fake-provider profile. It checks mechanics, not model quality.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def token(scope: str) -> str:
    return subprocess.check_output(
        [
            "python3",
            str(ROOT / "scripts/generate-dev-jwt.py"),
            "--tenant",
            scope,
            "--user",
            "practice-user",
            "--agent",
            "practice-agent",
            "--subject",
            "practice",
            "--role",
            "USER",
        ],
        text=True,
    ).strip()


def request(base: str, path: str, bearer: str, body=None, key=None):
    headers = {"Authorization": "Bearer " + bearer}
    if key:
        headers["Idempotency-Key"] = key
    if body is not None:
        headers["Content-Type"] = "application/json"
    return Request(
        base + path, data=None if body is None else json.dumps(body).encode(), headers=headers
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:18080")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    scope = "rag-practice-" + uuid.uuid4().hex
    bearer = token(scope)
    event_id = uuid.uuid4().hex
    event = {
        "sourceId": event_id,
        "sessionId": event_id,
        "actorType": "USER",
        "sourceType": "CONVERSATION_MESSAGE",
        "trustLevel": "DIRECT_USER",
        "occurredAt": datetime.now(timezone.utc).isoformat(),
        "payload": {"content": "I prefer a dark editor theme."},
    }
    with urlopen(
        request(base, "/v1/source-events", bearer, event, event_id), timeout=10
    ) as response:
        receipt = json.load(response)
    deadline = time.monotonic() + 60
    while True:
        with urlopen(
            request(
                base, "/v1/source-events/" + receipt["sourceEventId"] + "/materialization", bearer
            ),
            timeout=5,
        ) as response:
            state = json.load(response)
        if state["status"] == "SUCCEEDED":
            break
        if state["status"] == "FAILED" or time.monotonic() >= deadline:
            raise RuntimeError("materialization did not succeed")
        time.sleep(0.2)
    body = {"question": "What editor theme do I prefer?", "toolCalling": True}
    with urlopen(request(base, "/v1/answers", bearer, body), timeout=70) as response:
        answer = json.load(response)
    assert answer["abstain"] is True and answer["citations"] == [], "expected fake profile"
    with urlopen(request(base, "/v1/answers/stream", bearer, body), timeout=70) as response:
        raw = response.read(1_048_577).decode()
    assert len(raw.encode()) <= 1_048_576
    events = []
    for block in raw.replace("\r\n", "\n").split("\n\n"):
        lines = block.splitlines()
        names = [line[6:] for line in lines if line.startswith("event:")]
        data = "\n".join(line[5:] for line in lines if line.startswith("data:"))
        if names:
            events.append((names[0], json.loads(data)))
    names = [name for name, _ in events]
    assert names == ["tool", "evidence", "delta", "answer", "done"], names
    evidence = next(value for name, value in events if name == "evidence")
    assert evidence["selectedIds"] and set(evidence["selectedIds"]) <= set(evidence["rankedIds"])
    other = token(scope + "-other")
    with urlopen(
        request(base, "/v1/retrieval", other, {"query": body["question"]}), timeout=10
    ) as response:
        assert json.load(response)["memories"] == []
    try:
        urlopen(request(base, "/v1/answers", "invalid", body), timeout=10)
        raise AssertionError("invalid bearer was accepted")
    except HTTPError as error:
        assert error.code == 401
    print(
        "PASS: ingestion, settlement, scoped retrieval, tool, incremental SSE, "
        "validated answer, isolation, authentication"
    )
    print("Fake-provider mechanics only; no real-model quality score was produced.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
