"""Command-line entry point for local benchmark environment checks."""

from __future__ import annotations

import argparse

from memos_benchmark.client import MemosClient


def main() -> int:
    parser = argparse.ArgumentParser(description="Check a MemOS endpoint")
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()
    status = MemosClient(args.base_url).readiness()
    print(f"MemOS readiness: {status.status}")
    return 0 if status.status == "UP" else 1
