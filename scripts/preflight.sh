#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/local-env.sh
failed=0
check() {
  if "$@"; then printf 'PASS: %s\n' "$1"; else printf 'FAIL: %s\n' "$1" >&2; failed=1; fi
}
check git --version
check java -version
if [[ "$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p')" != 25 ]]; then
  echo 'FAIL: Java 25 required; select JAVA_HOME (see docs/local-runbook.md)' >&2
  failed=1
fi
check ./mvnw --version
check docker version
check docker compose version
check uv --version
if [[ "${1:-}" == --models ]]; then
  check ollama --version
  check curl --fail --silent --max-time 5 http://127.0.0.1:11434/api/version
  check bash -c 'cd benchmark && uv run python -c '\''from memos_benchmark.dataset import load_dataset; from memos_benchmark.ollama import OllamaClient; from pathlib import Path; d=load_dataset(Path("datasets/memos-assistant-smoke/v1/manifest.json")); print(OllamaClient("http://127.0.0.1:11434").inspect(d[0]["selected_models"]))'\'''
fi
exit "$failed"
