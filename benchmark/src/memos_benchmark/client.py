"""Small standard-library client used by the benchmark harness."""

from __future__ import annotations

import json
from dataclasses import dataclass
from urllib.request import urlopen


@dataclass(frozen=True)
class HealthStatus:
    status: str


class MemosClient:
    def __init__(self, base_url: str = "http://localhost:8080") -> None:
        self._base_url = base_url.rstrip("/")

    def readiness(self, timeout_seconds: float = 5.0) -> HealthStatus:
        with urlopen(f"{self._base_url}/readyz", timeout=timeout_seconds) as response:  # noqa: S310
            payload = json.load(response)
        return HealthStatus(status=str(payload["status"]))
