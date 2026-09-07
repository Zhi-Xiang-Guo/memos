#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/local-env.sh
mode="${1:-fake}"
export MEMOS_EMBEDDING_PROVIDER=fake MEMOS_EMBEDDING_MODEL_VERSION=deterministic-hashing-1024-v1
export MEMOS_EMBEDDING_DIMENSIONS=1024 MEMOS_EXTRACTION_PROVIDER=fake
export MEMOS_EXTRACTION_MODEL_VERSION=deterministic-fixture-v1
export MEMOS_EXTRACTION_PROMPT_VERSION=candidate-extraction-v1
case "$mode" in
  fake) ;;
  ollama|ollama-temporal-v2|ollama-policy-v3)
    if [[ "$mode" = ollama-temporal-v2 ]]; then
      export MEMOS_EXTRACTION_PROMPT_VERSION=candidate-extraction-temporal-v2
    elif [[ "$mode" = ollama-policy-v3 ]]; then
      export MEMOS_EXTRACTION_PROMPT_VERSION=candidate-extraction-policy-v3
    fi
    export MEMOS_EXTRACTION_PROVIDER=ollama MEMOS_EMBEDDING_PROVIDER=ollama
    export MEMOS_EXTRACTION_BASE_URL=http://127.0.0.1:11434
    export MEMOS_EMBEDDING_BASE_URL=http://127.0.0.1:11434
    export MEMOS_EXTRACTION_MODEL_TAG=qwen3:4b
    export MEMOS_EXTRACTION_MODEL_DIGEST=359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7
    export MEMOS_EXTRACTION_MODEL_VERSION="sha256:$MEMOS_EXTRACTION_MODEL_DIGEST"
    export MEMOS_EMBEDDING_MODEL_TAG=qwen3-embedding:0.6b
    export MEMOS_EMBEDDING_MODEL_DIGEST=ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d
    export MEMOS_EMBEDDING_MODEL_VERSION="sha256:$MEMOS_EMBEDDING_MODEL_DIGEST"
    export MEMOS_EXTRACTION_TIMEOUT=300s
    ./scripts/preflight.sh --models
    ;;
  codex)
    export MEMOS_EXTRACTION_PROVIDER=codex-proxy
    export MEMOS_EXTRACTION_PROMPT_VERSION=candidate-extraction-v1-inline-schema
    export MEMOS_EXTRACTION_BASE_URL=http://127.0.0.1:31415/v1
    export MEMOS_EXTRACTION_API_KEY="$(cat "$HOME/.codex/codex-api-proxy-token")"
    export MEMOS_EXTRACTION_MODEL_TAG=gpt-5.6-luna
    # Explicit development attestation; not an immutable hosted-model snapshot.
    export MEMOS_EXTRACTION_MODEL_VERSION=local-codex-proxy-dev-unpinned
    export MEMOS_EXTRACTION_TIMEOUT=300s
    ;;
  *) echo 'Usage: scripts/run-local.sh [fake|ollama|ollama-temporal-v2|ollama-policy-v3|codex]' >&2; exit 2 ;;
esac
mkdir -p logs
api_jar=applications/memos-api/target/memos-api-0.1.0-SNAPSHOT-exec.jar
worker_jar=applications/memos-worker/target/memos-worker-0.1.0-SNAPSHOT-exec.jar
[[ -f "$api_jar" && -f "$worker_jar" ]] || { echo 'Run ./mvnw -B -ntp clean verify first' >&2; exit 1; }
java -Dserver.address=127.0.0.1 -jar "$api_jar" > logs/api.log 2>&1 & api_pid=$!
java -Dserver.address=127.0.0.1 -jar "$worker_jar" > logs/worker.log 2>&1 & worker_pid=$!
trap 'kill "$api_pid" "$worker_pid" 2>/dev/null || true; wait || true' EXIT INT TERM
for _ in {1..90}; do
  kill -0 "$api_pid" "$worker_pid" 2>/dev/null || { echo 'Startup failed: inspect logs/api.log and logs/worker.log' >&2; exit 1; }
  if curl -fsS "http://127.0.0.1:${MEMOS_API_PORT:-8080}/readyz" >/dev/null 2>&1 &&
     curl -fsS "http://127.0.0.1:${MEMOS_WORKER_PORT:-8081}/readyz" >/dev/null 2>&1; then
    echo "MemOS ready ($mode); Ctrl-C stops both processes."
    wait "$api_pid" "$worker_pid"
    exit
  fi
  sleep 1
done
echo 'Readiness timeout; inspect logs' >&2
exit 1
