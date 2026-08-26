#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/.." && pwd)
cd "$repo_root"

./mvnw -B -ntp -DskipTests package

api_jar=$(find applications/memos-api/target -name '*-exec.jar' -print -quit)
worker_jar=$(find applications/memos-worker/target -name '*-exec.jar' -print -quit)
api_log=$(mktemp)
worker_log=$(mktemp)
receipt_file=$(mktemp)
duplicate_file=$(mktemp)
conflict_file=$(mktemp)
api_pid=
worker_pid=

cleanup() {
  if [[ -n $worker_pid ]]; then kill "$worker_pid" 2>/dev/null || true; fi
  if [[ -n $api_pid ]]; then kill "$api_pid" 2>/dev/null || true; fi
  wait "$worker_pid" 2>/dev/null || true
  wait "$api_pid" 2>/dev/null || true
  rm -f "$api_log" "$worker_log" "$receipt_file" "$duplicate_file" "$conflict_file"
}
trap cleanup EXIT

java -jar "$api_jar" >"$api_log" 2>&1 &
api_pid=$!

for _ in {1..120}; do
  if curl --fail --silent http://localhost:8080/readyz >/dev/null; then break; fi
  sleep 0.25
done
curl --fail --silent http://localhost:8080/readyz >/dev/null

suffix="$(date +%s)-$$"
source_id="smoke-source-$suffix"
idempotency_key="smoke-key-$suffix"
marker="feature1-sensitive-marker-$suffix"
body=$(printf '{"sourceId":"%s","sessionId":"smoke-session","actorType":"USER","sourceType":"CONVERSATION_MESSAGE","trustLevel":"DIRECT_USER","occurredAt":"2026-08-26T12:00:00Z","payload":{"content":"%s"}}' "$source_id" "$marker")

status=$(curl --silent --output "$receipt_file" --write-out '%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $idempotency_key" \
  -H 'X-Tenant-Id: smoke-tenant' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Agent-Id: smoke-agent' \
  --data "$body" \
  http://localhost:8080/v1/source-events)
test "$status" = 202

duplicate_status=$(curl --silent --output "$duplicate_file" --write-out '%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $idempotency_key" \
  -H 'X-Tenant-Id: smoke-tenant' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Agent-Id: smoke-agent' \
  --data "$body" \
  http://localhost:8080/v1/source-events)
test "$duplicate_status" = 200

job_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["materializationJobId"])' "$receipt_file")
duplicate_job_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["materializationJobId"])' "$duplicate_file")
test "$job_id" = "$duplicate_job_id"

conflicting_body=${body/$source_id/conflicting-$source_id}
conflict_status=$(curl --silent --output "$conflict_file" --write-out '%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $idempotency_key" \
  -H 'X-Tenant-Id: smoke-tenant' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Agent-Id: smoke-agent' \
  --data "$conflicting_body" \
  http://localhost:8080/v1/source-events)
test "$conflict_status" = 409
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["code"] == "IDEMPOTENCY_KEY_REUSED"' "$conflict_file"

job_url="http://localhost:8080/v1/materialization-jobs/$job_id"
scope_headers=(-H 'X-Tenant-Id: smoke-tenant' -H 'X-User-Id: smoke-user' -H 'X-Agent-Id: smoke-agent')
pending=$(curl --fail --silent "${scope_headers[@]}" "$job_url")
python3 -c 'import json,sys; assert json.loads(sys.argv[1])["state"] == "PENDING"' "$pending"

java -jar "$worker_jar" >"$worker_log" 2>&1 &
worker_pid=$!
for _ in {1..120}; do
  state=$(curl --fail --silent "${scope_headers[@]}" "$job_url" | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])')
  if [[ $state = SUCCEEDED ]]; then break; fi
  sleep 0.25
done
test "$state" = SUCCEEDED

status_body=$(curl --fail --silent "${scope_headers[@]}" "$job_url")
wrong_scope_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H 'X-Tenant-Id: smoke-tenant' -H 'X-User-Id: other-user' -H 'X-Agent-Id: smoke-agent' \
  "$job_url")
test "$wrong_scope_status" = 404
replay_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -X POST "${scope_headers[@]}" "$job_url/replay")
test "$replay_status" = 409
if [[ $status_body == *leaseOwner* || $status_body == *leaseToken* || $status_body == *payload* ]]; then
  echo 'Job status leaked an internal field.' >&2
  exit 1
fi
if grep -Fq "$marker" "$api_log" "$worker_log"; then
  echo 'Application logs leaked source content.' >&2
  exit 1
fi

echo "Feature 1 ingestion, durable pending state, worker recovery, and redaction smoke passed."
