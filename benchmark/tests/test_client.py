from __future__ import annotations

import io
import json
from unittest.mock import patch

from memos_benchmark.client import MemosClient


class FakeResponse(io.BytesIO):
    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()


def test_readiness_uses_public_health_contract() -> None:
    response = FakeResponse(json.dumps({"status": "UP"}).encode())
    with patch("memos_benchmark.client.urlopen", return_value=response) as urlopen:
        status = MemosClient("http://example.test/").readiness()

    assert status.status == "UP"
    urlopen.assert_called_once_with("http://example.test/readyz", timeout=5.0)
