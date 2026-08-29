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
list_file=$(mktemp)
inspect_file=$(mktemp)
current_file=$(mktemp)
history_file=$(mktemp)
headers_file=$(mktemp)
mutation_file=$(mktemp)
as_of_file=$(mktemp)
diff_file=$(mktemp)
api_pid=
worker_pid=

cleanup() {
  status=$?
  if [[ -n $worker_pid ]]; then kill "$worker_pid" 2>/dev/null || true; fi
  if [[ -n $api_pid ]]; then kill "$api_pid" 2>/dev/null || true; fi
  wait "$worker_pid" 2>/dev/null || true
  wait "$api_pid" 2>/dev/null || true
  if [[ $status -ne 0 ]]; then
    echo 'Feature 3 smoke failed; API log follows.' >&2
    tail -80 "$api_log" >&2 || true
    echo 'Feature 3 smoke failed; worker log follows.' >&2
    tail -80 "$worker_log" >&2 || true
  fi
  rm -f "$api_log" "$worker_log" "$receipt_file" "$list_file" "$inspect_file" \
    "$current_file" "$history_file" "$headers_file" "$mutation_file" "$as_of_file" \
    "$diff_file"
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

db_query() {
  docker compose exec -T postgres psql -U "${MEMOS_DB_USER:-memos}" \
    -d "${MEMOS_DB_NAME:-memos}" -Atqc "$1"
}

start_api
MEMOS_WORKER_POLL_DELAY=5s "$java_bin" -jar "$worker_jar" >"$worker_log" 2>&1 &
worker_pid=$!

suffix="$(date +%s)-$$"
tenant_id="feature3-tenant-$suffix"
user_id="feature3-user-$suffix"
agent_id="feature3-agent-$suffix"
source_id="feature3-source-$suffix"
body=$(printf '{"sourceId":"%s","sessionId":"feature3-session","actorType":"USER","sourceType":"CONVERSATION_MESSAGE","trustLevel":"DIRECT_USER","occurredAt":"2026-08-27T00:00:00Z","payload":{"content":"I prefer a dark editor theme."}}' "$source_id")
user_token=$(python3 scripts/generate-dev-jwt.py --tenant "$tenant_id" --user "$user_id" \
  --agent "$agent_id" --subject "feature3-subject-$suffix" --role USER)
foreign_token=$(python3 scripts/generate-dev-jwt.py --tenant "$tenant_id" --user foreign-user \
  --agent "$agent_id" --subject "feature3-foreign-$suffix" --role USER)
scope_headers=(-H "Authorization: Bearer $user_token")

curl --fail --silent --output "$receipt_file" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: feature3-key-$suffix" \
  "${scope_headers[@]}" \
  --data "$body" \
  http://localhost:8080/v1/source-events
source_job_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["materializationJobId"])' "$receipt_file")

source_state=PENDING
for _ in {1..160}; do
  source_state=$(db_query "SELECT state FROM memos.outbox_job WHERE job_id = '$source_job_id'::uuid")
  if [[ $source_state = SUCCEEDED ]]; then break; fi
  sleep 0.25
done
test "$source_state" = SUCCEEDED

candidate_job_id=
for _ in {1..160}; do
  candidate_job_id=$(db_query "SELECT job_id FROM memos.outbox_job WHERE tenant_id = '$tenant_id' AND job_type = 'CANDIDATE_MATERIALIZATION' ORDER BY created_at DESC LIMIT 1")
  if [[ -n $candidate_job_id ]]; then
    candidate_state=$(db_query "SELECT state FROM memos.outbox_job WHERE job_id = '$candidate_job_id'::uuid")
    if [[ $candidate_state = SUCCEEDED ]]; then break; fi
  fi
  sleep 0.25
done
test -n "$candidate_job_id"
test "$candidate_state" = SUCCEEDED

memory_id=$(db_query "SELECT memory_id FROM memos.memory_lineage WHERE tenant_id = '$tenant_id' AND user_id = '$user_id' AND agent_id = '$agent_id'")
test -n "$memory_id"
test "$(db_query "SELECT count(*) FROM memos.memory_version WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = 1
test "$(db_query "SELECT count(*) FROM memos.memory_state_transition WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid AND operation = 'CREATE'")" = 1
test "$(db_query "SELECT count(*) FROM memos.memory_source WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = 1
test "$(db_query "SELECT count(*) FROM memos.memory_current_state WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid AND status = 'CURRENT'")" = 1
test "$(db_query "SELECT count(*) FROM memos.outbox_job WHERE tenant_id = '$tenant_id' AND job_type = 'PROJECTION_BUILD' AND state = 'PENDING'")" = 1

curl --fail --silent "${scope_headers[@]}" http://localhost:8080/v1/memories >"$list_file"
curl --fail --silent "${scope_headers[@]}" http://localhost:8080/v1/memories/"$memory_id" >"$inspect_file"
curl --fail --silent "${scope_headers[@]}" http://localhost:8080/v1/memories/"$memory_id"/current >"$current_file"
curl --fail --silent "${scope_headers[@]}" http://localhost:8080/v1/memories/"$memory_id"/history >"$history_file"
python3 - "$memory_id" "$list_file" "$inspect_file" "$current_file" "$history_file" <<'PY'
import json
import sys

memory_id, *paths = sys.argv[1:]
listing, inspection, current, history = (json.load(open(path)) for path in paths)
assert [item["memoryId"] for item in listing["items"]] == [memory_id]
assert inspection["summary"]["memoryId"] == memory_id
assert inspection["versions"][0]["value"] == "dark"
assert inspection["versions"][0]["status"] == "CURRENT"
assert current["current"][0]["normalizedContent"] == "The user prefers a dark editor theme."
assert history["transitions"][0]["operation"] == "CREATE"
PY

original_version_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["current"][0]["versionId"])' "$current_file")
correction_source_id="feature3-correction-$suffix"
correction_body=$(printf '{"sourceId":"%s","sessionId":"feature3-session","actorType":"USER","sourceType":"DIRECT_MEMORY_COMMAND","trustLevel":"DIRECT_USER","occurredAt":"2026-08-27T00:01:00Z","payload":{"content":"I prefer a dark editor theme."}}' "$correction_source_id")
curl --fail --silent --output "$receipt_file" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: feature3-correction-source-$suffix" \
  "${scope_headers[@]}" \
  --data "$correction_body" \
  http://localhost:8080/v1/source-events
correction_source_event_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceEventId"])' "$receipt_file")
correction_source_job_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["materializationJobId"])' "$receipt_file")
for _ in {1..160}; do
  correction_source_state=$(db_query "SELECT state FROM memos.outbox_job WHERE job_id = '$correction_source_job_id'::uuid")
  if [[ $correction_source_state = SUCCEEDED ]]; then break; fi
  sleep 0.25
done
test "$correction_source_state" = SUCCEEDED
kill "$worker_pid"
wait "$worker_pid" 2>/dev/null || true
worker_pid=
correction_candidate_state=$(db_query "SELECT state FROM memos.outbox_job WHERE tenant_id = '$tenant_id' AND source_event_id = '$correction_source_event_id'::uuid AND job_type = 'CANDIDATE_MATERIALIZATION'")
test "$correction_candidate_state" = PENDING
correction_candidate_id=$(db_query "SELECT candidate_id FROM memos.memory_candidate WHERE tenant_id = '$tenant_id' AND source_event_id = '$correction_source_event_id'::uuid AND content_state = 'AVAILABLE'")
test -n "$correction_candidate_id"

curl --fail --silent --dump-header "$headers_file" "${scope_headers[@]}" \
  http://localhost:8080/v1/memories/"$memory_id" >"$inspect_file"
etag=$(awk 'tolower($1) == "etag:" {gsub("\r", "", $2); print $2}' "$headers_file")
test -n "$etag"
mutation_body=$(printf '{"incorrectVersionId":"%s","sourceEventId":"%s","candidateId":"%s","reason":"USER_CORRECTION"}' "$original_version_id" "$correction_source_event_id" "$correction_candidate_id")
curl --fail --silent --output "$mutation_file" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: feature3-mutation-$suffix" \
  -H "If-Match: $etag" "${scope_headers[@]}" --data "$mutation_body" \
  http://localhost:8080/v1/memories/"$memory_id"/corrections
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["disposition"] == "APPLIED"' "$mutation_file"
curl --fail --silent --output "$mutation_file" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: feature3-mutation-$suffix" \
  -H "If-Match: $etag" "${scope_headers[@]}" --data "$mutation_body" \
  http://localhost:8080/v1/memories/"$memory_id"/corrections
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["disposition"] == "REPLAYED"' "$mutation_file"
stale_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H 'Content-Type: application/json' -H "Idempotency-Key: feature3-stale-$suffix" \
  -H "If-Match: $etag" "${scope_headers[@]}" --data "$mutation_body" \
  http://localhost:8080/v1/memories/"$memory_id"/corrections)
test "$stale_status" = 412

"$java_bin" -jar "$worker_jar" >>"$worker_log" 2>&1 &
worker_pid=$!
for _ in {1..160}; do
  correction_candidate_state=$(db_query "SELECT state FROM memos.outbox_job WHERE tenant_id = '$tenant_id' AND source_event_id = '$correction_source_event_id'::uuid AND job_type = 'CANDIDATE_MATERIALIZATION'")
  if [[ $correction_candidate_state = SUCCEEDED ]]; then break; fi
  sleep 0.25
done
test "$correction_candidate_state" = SUCCEEDED

curl --fail --silent "${scope_headers[@]}" http://localhost:8080/v1/memories/"$memory_id"/history >"$history_file"
curl --fail --silent "${scope_headers[@]}" 'http://localhost:8080/v1/memories/'"$memory_id"'/as-of?at=2100-01-01T00:00:00Z' >"$as_of_file"
curl --fail --silent "${scope_headers[@]}" 'http://localhost:8080/v1/memories/'"$memory_id"'/diff?fromExclusive=1970-01-01T00:00:00Z&toInclusive=2100-01-01T00:00:00Z' >"$diff_file"
python3 - "$original_version_id" "$history_file" "$as_of_file" "$diff_file" <<'PY'
import json
import sys

original, history_path, as_of_path, diff_path = sys.argv[1:]
history = json.load(open(history_path))
statuses = {version["versionId"]: version["status"] for version in history["versions"]}
assert statuses[original] == "INVALIDATED"
assert list(statuses.values()).count("CURRENT") == 1
assert history["transitions"][-1]["operation"] == "INVALIDATE"
assert len(json.load(open(as_of_path))["versions"]) == 2
diff = json.load(open(diff_path))
assert len(diff["appendedVersions"]) == 2
assert len(diff["transitions"]) >= 2
PY

wrong_scope_status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H "Authorization: Bearer $foreign_token" \
  http://localhost:8080/v1/memories/"$memory_id")
test "$wrong_scope_status" = 404

kill "$api_pid"
wait "$api_pid" 2>/dev/null || true
api_pid=
: >"$api_log"
start_api
curl --fail --silent "${scope_headers[@]}" http://localhost:8080/v1/memories/"$memory_id"/current >"$current_file"
python3 -c 'import json,sys; value=json.load(open(sys.argv[1])); assert value["current"][0]["value"] == "dark"' "$current_file"

if grep -Fq 'I prefer a dark editor theme.' "$api_log" "$worker_log"; then
  echo 'Application logs leaked source or memory content.' >&2
  exit 1
fi

echo "Feature 3 temporal authority, routed worker, scoped inspection, and restart persistence smoke passed."
