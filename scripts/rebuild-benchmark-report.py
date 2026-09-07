#!/usr/bin/env python3
"""Rebuild an immutable package from manifest/raw rows, without model calls.

Run through benchmark's uv environment. The destination must not already exist.
This is a separate-process reproduction with the same metric implementation.
"""

import argparse
import json
from pathlib import Path

from memos_benchmark.artifacts import generate_costs, verify_package, write_package
from memos_benchmark.dataset import load_dataset
from memos_benchmark.metrics import generate_metrics


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--destination", required=True, type=Path)
    parser.add_argument("--dataset-manifest", required=True, type=Path)
    args = parser.parse_args()
    dataset, scenarios = load_dataset(args.dataset_manifest)
    manifest = json.loads((args.source / "manifest.json").read_text(encoding="utf-8"))
    rows = {
        name: [
            json.loads(line) for line in (args.source / f"{name}.jsonl").read_text().splitlines()
        ]
        for name in ("cases", "writes", "retrieval", "answers", "timings")
    }
    metrics = generate_metrics(
        dataset, scenarios, manifest, rows["answers"], rows["retrieval"], rows["timings"]
    )
    costs = generate_costs(manifest, rows["writes"], rows["retrieval"], rows["answers"])
    write_package(args.destination, manifest=manifest, metrics=metrics, costs=costs, **rows)
    regenerated = verify_package(args.destination, args.dataset_manifest)
    original = verify_package(args.source, args.dataset_manifest)
    if regenerated["package_sha256"] != original["package_sha256"]:
        raise ValueError("rebuilt package differs from original package")
    print(json.dumps({"byte_identical": True, **regenerated}, sort_keys=True))


if __name__ == "__main__":
    main()
