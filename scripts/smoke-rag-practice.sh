#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
./mvnw -B -ntp -DskipTests package
api_pid=""
worker_pid=""
cleanup() {
  if [[ -n "$api_pid" ]]; then kill "$api_pid" 2>/dev/null || true; fi
  if [[ -n "$worker_pid" ]]; then kill "$worker_pid" 2>/dev/null || true; fi
  wait "$api_pid" "$worker_pid" 2>/dev/null || true
}
trap cleanup EXIT
java -jar applications/memos-api/target/memos-api-0.1.0-SNAPSHOT-exec.jar > build-rag-api.log 2>&1 &
api_pid=$!
java -jar applications/memos-worker/target/memos-worker-0.1.0-SNAPSHOT-exec.jar > build-rag-worker.log 2>&1 &
worker_pid=$!
for port in "${MEMOS_API_PORT:-8080}" "${MEMOS_WORKER_PORT:-8081}"; do
  ready=false
  for _ in $(seq 1 60); do
    if ! kill -0 "$api_pid" "$worker_pid" 2>/dev/null; then
      echo "RAG smoke service exited; inspect build-rag-api.log/build-rag-worker.log" >&2
      exit 1
    fi
    if curl --fail --silent --max-time 1 "http://localhost:$port/readyz" > /dev/null; then
      ready=true
      break
    fi
    sleep 1
  done
  if [[ "$ready" != true ]]; then echo "RAG smoke readiness timeout" >&2; exit 1; fi
done
python3 scripts/smoke-rag-practice.py --base-url "http://localhost:${MEMOS_API_PORT:-8080}"
