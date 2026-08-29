#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/.." && pwd)
cd "$repo_root"

java_bin=java
if [[ -n ${JAVA_HOME:-} ]]; then java_bin="$JAVA_HOME/bin/java"; fi

./mvnw -B -ntp -DskipTests package

api_jar=$(find applications/memos-api/target -name '*-exec.jar' -print -quit)
worker_jar=$(find applications/memos-worker/target -name '*-exec.jar' -print -quit)
api_log=$(mktemp)
worker_log=$(mktemp)
receipt_file=$(mktemp)
retrieval_file=$(mktemp)
trace_file=$(mktemp)
headers_file=$(mktemp)
mutation_file=$(mktemp)
latency_file=$(mktemp)
api_pid=
worker_pid=

cleanup() {
  status=$?
  if [[ -n $worker_pid ]]; then kill "$worker_pid" 2>/dev/null || true; fi
  if [[ -n $api_pid ]]; then kill "$api_pid" 2>/dev/null || true; fi
  wait "$worker_pid" 2>/dev/null || true
  wait "$api_pid" 2>/dev/null || true
  if [[ $status -ne 0 ]]; then
    echo 'Feature 4 smoke failed; API log follows.' >&2
    tail -80 "$api_log" >&2 || true
    echo 'Feature 4 smoke failed; worker log follows.' >&2
    tail -80 "$worker_log" >&2 || true
  fi
  rm -f "$api_log" "$worker_log" "$receipt_file" "$retrieval_file" "$trace_file" \
    "$headers_file" "$mutation_file"
  rm -f "$latency_file"
  return "$status"
}
trap cleanup EXIT

start_api() {
  "$java_bin" -jar "$api_jar" >"$api_log" 2>&1 &
  api_pid=$!
  for _ in {1..120}; do
    if curl --fail --silent http://localhost:8080/readyz >/dev/null; then return; fi
    sleep 0.25
  done
  curl --fail --silent http://localhost:8080/readyz >/dev/null
}

start_worker() {
  MEMOS_WORKER_POLL_DELAY=100ms "$java_bin" -jar "$worker_jar" >"$worker_log" 2>&1 &
  worker_pid=$!
  for _ in {1..120}; do
    if curl --fail --silent http://localhost:8081/readyz >/dev/null; then return; fi
    sleep 0.25
  done
  curl --fail --silent http://localhost:8081/readyz >/dev/null
}

db_query() {
  docker compose exec -T postgres psql -U "${MEMOS_DB_USER:-memos}" \
    -d "${MEMOS_DB_NAME:-memos}" -Atqc "$1"
}

expect_http() {
  local label=$1
  local expected=$2
  local output=$3
  shift 3
  local status=
  if ! status=$(curl --silent --show-error --output "$output" --write-out '%{http_code}' "$@"); then
    echo "$label failed before receiving an HTTP response." >&2
    return 1
  fi
  if [[ $status != "$expected" ]]; then
    local error_code
    error_code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("code", "UNKNOWN"))' "$output" 2>/dev/null || echo UNKNOWN)
    echo "$label returned HTTP $status code=$error_code" >&2
    return 1
  fi
}

wait_for_projection() {
  local expected_sequence=$1
  local state=
  for _ in {1..240}; do
    state=$(db_query "SELECT state FROM memos.outbox_job WHERE tenant_id = '$tenant_id' AND job_type = 'PROJECTION_BUILD' ORDER BY created_at DESC LIMIT 1")
    if [[ $state = SUCCEEDED ]] && \
       [[ $(db_query "SELECT transition_sequence FROM memos.memory_projection_checkpoint WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid") = "$expected_sequence" ]]; then
      return
    fi
    sleep 0.25
  done
  test "$state" = SUCCEEDED
  test "$(db_query "SELECT transition_sequence FROM memos.memory_projection_checkpoint WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = "$expected_sequence"
}

start_api
start_worker

suffix="$(date +%s)-$$"
tenant_id="feature4-tenant-$suffix"
user_id="feature4-user-$suffix"
agent_id="feature4-agent-$suffix"
source_id="feature4-source-$suffix"
body=$(printf '{"sourceId":"%s","sessionId":"feature4-session","actorType":"USER","sourceType":"CONVERSATION_MESSAGE","trustLevel":"DIRECT_USER","occurredAt":"2026-08-30T00:00:00Z","payload":{"content":"I prefer a dark editor theme."}}' "$source_id")
user_token=$(python3 scripts/generate-dev-jwt.py --tenant "$tenant_id" --user "$user_id" \
  --agent "$agent_id" --subject "feature4-subject-$suffix" --role USER)
operator_token=$(python3 scripts/generate-dev-jwt.py --tenant "$tenant_id" --user "$user_id" \
  --agent "$agent_id" --subject "feature4-operator-$suffix" --role OPERATOR)
foreign_token=$(python3 scripts/generate-dev-jwt.py --tenant "$tenant_id" --user foreign-user \
  --agent "$agent_id" --subject "feature4-foreign-$suffix" --role USER)
scope_headers=(-H "Authorization: Bearer $user_token")

curl --fail --silent --output "$receipt_file" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: feature4-key-$suffix" \
  "${scope_headers[@]}" \
  --data "$body" \
  http://localhost:8080/v1/source-events
source_event_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceEventId"])' "$receipt_file")

memory_id=
for _ in {1..240}; do
  memory_id=$(db_query "SELECT memory_id FROM memos.memory_lineage WHERE tenant_id = '$tenant_id' AND user_id = '$user_id' AND agent_id = '$agent_id'")
  if [[ -n $memory_id ]]; then break; fi
  sleep 0.25
done
test -n "$memory_id"
wait_for_projection 1

test "$(db_query "SELECT projected_version_count FROM memos.memory_projection_checkpoint WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = 1
test "$(db_query "SELECT count(*) FROM memos.memory_search_projection WHERE tenant_id = '$tenant_id' AND user_id = '$user_id' AND agent_id = '$agent_id' AND truth_status = 'CURRENT'")" = 1

retrieval_body='{"query":"Which editor theme do I prefer?","mode":"HYBRID","predicate":"preference.editor.theme","limit":5,"maxTokens":800}'
trace_body='{"query":"dark editor theme","mode":"HYBRID","predicate":"preference.editor.theme","limit":5,"maxTokens":800}'
curl --fail --silent --output "$retrieval_file" \
  -H 'Content-Type: application/json' "${scope_headers[@]}" \
  --data "$retrieval_body" http://localhost:8080/v1/retrieval
python3 - "$memory_id" "$retrieval_file" <<'PY'
import json
import sys

memory_id, path = sys.argv[1:]
response = json.load(open(path))
assert response["gate"] == {"retrieve": True, "reason": "MEMORY_RETRIEVAL_REQUIRED"}
assert [item["memoryId"] for item in response["memories"]] == [memory_id]
assert response["memories"][0]["normalizedContent"] == "The user prefers a dark editor theme."
assert response["memories"][0]["components"] == []
assert response["memories"][0]["watermark"]["transitionSequence"] == 1
assert response["context"]["selected"] == 1
assert 'trust="untrusted-data"' in response["context"]["rendered"]
assert response["trace"] is None
PY

for _ in {1..40}; do
  curl --fail --silent --output "$retrieval_file" --write-out '%{time_total}\n' \
    -H 'Content-Type: application/json' "${scope_headers[@]}" \
    --data "$retrieval_body" http://localhost:8080/v1/retrieval >>"$latency_file"
done
python3 - "$latency_file" "$retrieval_file" <<'PY'
import json
import math
import sys

samples = sorted(float(value) * 1000 for value in open(sys.argv[1]) if value.strip())
response = json.load(open(sys.argv[2]))

def percentile(value):
    return samples[max(0, math.ceil(value * len(samples)) - 1)]

print(
    "Feature 4 deterministic smoke sample: "
    f"n={len(samples)} p50_ms={percentile(0.50):.3f} "
    f"p95_ms={percentile(0.95):.3f} p99_ms={percentile(0.99):.3f} "
    f"context_tokens={response['context']['tokens']}"
)
PY

wrong_trace_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H 'Content-Type: application/json' \
  "${scope_headers[@]}" --data "$trace_body" \
  http://localhost:8080/v1/retrieval/trace)
test "$wrong_trace_status" = 403

trace_status=$(curl --silent --show-error --output "$trace_file" --write-out '%{http_code}' \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $operator_token" \
  --data "$trace_body" \
  http://localhost:8080/v1/retrieval/trace)
if [[ $trace_status != 200 ]]; then
  trace_error_code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("code", "UNKNOWN"))' "$trace_file" 2>/dev/null || echo UNKNOWN)
  echo "Trace retrieval returned HTTP $trace_status code=$trace_error_code" >&2
  exit 1
fi
python3 - "$trace_file" <<'PY'
import json
import sys

response = json.load(open(sys.argv[1]))
assert response["trace"]["componentCandidateCount"] >= 1
assert response["trace"]["embeddingProvider"] == "deterministic-local"
assert response["trace"]["embeddingModelVersion"] == "deterministic-hashing-64-v1"
assert {component["source"] for component in response["memories"][0]["components"]} >= {"VECTOR", "LEXICAL"}
PY

expect_http 'cross-scope retrieval' 200 "$retrieval_file" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $foreign_token" \
  --data "$retrieval_body" http://localhost:8080/v1/retrieval
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["memories"] == []' "$retrieval_file"

expect_http 'gated retrieval' 200 "$retrieval_file" \
  -H 'Content-Type: application/json' "${scope_headers[@]}" \
  --data '{"query":"thanks"}' http://localhost:8080/v1/retrieval
python3 -c 'import json,sys; value=json.load(open(sys.argv[1])); assert value["gate"]["retrieve"] is False and value["memories"] == []' "$retrieval_file"

expect_http 'memory inspection' 200 "$retrieval_file" --dump-header "$headers_file" \
  "${scope_headers[@]}" http://localhost:8080/v1/memories/"$memory_id"
etag=$(awk 'tolower($1) == "etag:" {gsub("\r", "", $2); print $2}' "$headers_file")
version_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versions"][0]["versionId"])' "$retrieval_file")
test -n "$etag"

invalidation_source_id="feature4-invalidation-$suffix"
invalidation_source_body=$(printf '{"sourceId":"%s","sessionId":"feature4-session","actorType":"USER","sourceType":"DIRECT_MEMORY_COMMAND","trustLevel":"DIRECT_USER","occurredAt":"2026-08-30T00:01:00Z","payload":{"content":"Forget the selected memory."}}' "$invalidation_source_id")
expect_http 'invalidation evidence ingestion' 202 "$receipt_file" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: feature4-invalidation-source-$suffix" \
  "${scope_headers[@]}" --data "$invalidation_source_body" \
  http://localhost:8080/v1/source-events
invalidation_source_event_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceEventId"])' "$receipt_file")

invalidation_body=$(printf '{"versionId":"%s","sourceEventId":"%s","reason":"USER_INVALIDATION"}' "$version_id" "$invalidation_source_event_id")
expect_http 'memory invalidation' 200 "$mutation_file" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: feature4-invalidate-$suffix" \
  -H "If-Match: $etag" "${scope_headers[@]}" --data "$invalidation_body" \
  http://localhost:8080/v1/memories/"$memory_id"/invalidations
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["disposition"] == "APPLIED"' "$mutation_file"
wait_for_projection 2

test "$(db_query "SELECT projected_version_count FROM memos.memory_projection_checkpoint WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = 0
test "$(db_query "SELECT count(*) FROM memos.memory_search_projection WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = 0

expect_http 'post-invalidation retrieval' 200 "$retrieval_file" \
  -H 'Content-Type: application/json' "${scope_headers[@]}" \
  --data "$retrieval_body" http://localhost:8080/v1/retrieval
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["memories"] == []' "$retrieval_file"

kill "$worker_pid"
wait "$worker_pid" 2>/dev/null || true
worker_pid=
start_worker
sleep 1
test "$(db_query "SELECT count(*) FROM memos.memory_search_projection WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = 0

if grep -Fq 'I prefer a dark editor theme.' "$api_log" "$worker_log"; then
  echo 'Application logs leaked source or memory content.' >&2
  exit 1
fi

echo "Feature 4 scoped hybrid retrieval, diagnostics, invalidation cleanup, and restart smoke passed."
