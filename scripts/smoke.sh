#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

./mvnw -B -ntp -DskipTests package

api_jar="$(find applications/memos-api/target -name '*-exec.jar' -print -quit)"
worker_jar="$(find applications/memos-worker/target -name '*-exec.jar' -print -quit)"

java -jar "$api_jar" >build-api.log 2>&1 &
api_pid=$!
java -jar "$worker_jar" >build-worker.log 2>&1 &
worker_pid=$!

cleanup() {
    kill "$api_pid" "$worker_pid" 2>/dev/null || true
}
trap cleanup EXIT

wait_for_health() {
    local url="$1"
    for _ in $(seq 1 60); do
        if curl --fail --silent "$url" | grep -q '"status":"UP"'; then
            return 0
        fi
        sleep 1
    done
    return 1
}

wait_for_health "http://localhost:${MEMOS_API_PORT:-8080}/livez"
wait_for_health "http://localhost:${MEMOS_API_PORT:-8080}/readyz"
wait_for_health "http://localhost:${MEMOS_WORKER_PORT:-8081}/livez"
wait_for_health "http://localhost:${MEMOS_WORKER_PORT:-8081}/readyz"

if curl --silent --output /dev/null --write-out '%{http_code}' \
    "http://localhost:${MEMOS_WORKER_PORT:-8081}/v1/memories" | grep -q '^2'; then
    echo "worker unexpectedly exposes a business endpoint" >&2
    exit 1
fi

echo "API and worker health checks passed"
