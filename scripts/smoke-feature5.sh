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
response_file=$(mktemp)
api_pid=
worker_pid=

cleanup() {
  status=$?
  if [[ -n $worker_pid ]]; then kill "$worker_pid" 2>/dev/null || true; fi
  if [[ -n $api_pid ]]; then kill "$api_pid" 2>/dev/null || true; fi
  wait "$worker_pid" 2>/dev/null || true
  wait "$api_pid" 2>/dev/null || true
  if [[ $status -ne 0 ]]; then
    echo 'Feature 5 smoke failed; API log follows.' >&2
    tail -100 "$api_log" >&2 || true
    echo 'Feature 5 smoke failed; worker log follows.' >&2
    tail -100 "$worker_log" >&2 || true
  fi
  rm -f "$api_log" "$worker_log" "$response_file"
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
  MEMOS_WORKER_POLL_DELAY=100ms MEMOS_DELETION_POLL_DELAY=100ms \
    "$java_bin" -jar "$worker_jar" >>"$worker_log" 2>&1 &
  worker_pid=$!
  for _ in {1..120}; do
    if curl --fail --silent http://localhost:8081/readyz >/dev/null; then return; fi
    sleep 0.25
  done
  curl --fail --silent http://localhost:8081/readyz >/dev/null
}

stop_worker() {
  kill "$worker_pid"
  wait "$worker_pid" 2>/dev/null || true
  worker_pid=
}

db_query() {
  docker compose exec -T postgres psql -U "${MEMOS_DB_USER:-memos}" \
    -d "${MEMOS_DB_NAME:-memos}" -Atqc "$1"
}

expect_http() {
  local label=$1
  local expected=$2
  shift 2
  local status
  status=$(curl --silent --show-error --output "$response_file" --write-out '%{http_code}' "$@")
  if [[ $status != "$expected" ]]; then
    echo "$label returned HTTP $status; expected $expected." >&2
    cat "$response_file" >&2
    return 1
  fi
}

wait_for_memory() {
  local expected_tenant=$1
  local expected_user=$2
  local expected_agent=$3
  local found=
  for _ in {1..240}; do
    found=$(db_query "SELECT memory_id FROM memos.memory_lineage WHERE tenant_id = '$expected_tenant' AND user_id = '$expected_user' AND agent_id = '$expected_agent' AND lifecycle_state = 'ACTIVE'")
    if [[ -n $found ]] && \
       [[ $(db_query "SELECT count(*) FROM memos.memory_search_projection WHERE tenant_id = '$expected_tenant' AND memory_id = '$found'::uuid") = 1 ]]; then
      printf '%s' "$found"
      return
    fi
    sleep 0.25
  done
  return 1
}

wait_for_deletion() {
  local operation_id=$1
  local token=$2
  local url=$3
  local state=
  for _ in {1..240}; do
    state=$(curl --fail --silent -H "Authorization: Bearer $token" "$url/$operation_id" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])')
    if [[ $state = COMPLETED ]]; then return; fi
    sleep 0.25
  done
  test "$state" = COMPLETED
}

start_api
start_worker

suffix="$(date +%s)-$$"
tenant_id="feature5-tenant-$suffix"
user_id="feature5-user-$suffix"
agent_id="feature5-agent-$suffix"
subject_id="feature5-subject-$suffix"
user_token=$(python3 scripts/generate-dev-jwt.py --tenant "$tenant_id" --user "$user_id" \
  --agent "$agent_id" --subject "$subject_id" --role USER)
foreign_token=$(python3 scripts/generate-dev-jwt.py --tenant "$tenant_id" --user "foreign-$suffix" \
  --agent "$agent_id" --subject "foreign-subject-$suffix" --role USER)
operator_token=$(python3 scripts/generate-dev-jwt.py --tenant "$tenant_id" --user "$user_id" \
  --agent "$agent_id" --subject "operator-subject-$suffix" --role OPERATOR)
admin_token=$(python3 scripts/generate-dev-jwt.py --tenant "$tenant_id" --user "privacy-admin-$suffix" \
  --agent "privacy-agent-$suffix" --subject "privacy-subject-$suffix" --role PRIVACY_ADMIN)

expect_http 'forged scope headers' 401 \
  -H "X-Tenant-Id: $tenant_id" -H "X-User-Id: $user_id" -H "X-Agent-Id: $agent_id" \
  http://localhost:8080/v1/memories

source_id="feature5-memory-source-$suffix"
source_body=$(printf '{"sourceId":"%s","sessionId":"feature5-session","actorType":"USER","sourceType":"CONVERSATION_MESSAGE","trustLevel":"DIRECT_USER","occurredAt":"2026-08-30T02:00:00Z","payload":{"content":"I prefer a dark editor theme."}}' "$source_id")
expect_http 'memory source ingestion' 202 \
  -H 'Content-Type: application/json' -H "Idempotency-Key: feature5-source-$suffix" \
  -H "Authorization: Bearer $user_token" --data "$source_body" \
  http://localhost:8080/v1/source-events
memory_id=$(wait_for_memory "$tenant_id" "$user_id" "$agent_id")
projection_job_id=$(db_query "SELECT source_job_id FROM memos.memory_projection_checkpoint WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")
test -n "$projection_job_id"

trace_body='{"query":"dark editor theme","mode":"HYBRID","predicate":"preference.editor.theme","limit":5,"maxTokens":800}'
expect_http 'user trace access' 403 \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $user_token" \
  --data "$trace_body" http://localhost:8080/v1/retrieval/trace
expect_http 'operator trace access' 200 \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $operator_token" \
  --data "$trace_body" http://localhost:8080/v1/retrieval/trace
test "$(db_query "SELECT count(*) FROM memos.audit_event WHERE tenant_id = '$tenant_id' AND actor_id = 'operator-subject-$suffix' AND action = 'RETRIEVAL_TRACE_ACCESSED' AND outcome = 'SUCCEEDED'")" = 1

expect_http 'cross-scope memory deletion' 404 -X POST \
  -H 'Content-Type: application/json' -H "Idempotency-Key: foreign-delete-$suffix" \
  -H "Authorization: Bearer $foreign_token" --data '{"policyBasis":"USER_REQUEST"}' \
  http://localhost:8080/v1/deletions/memories/"$memory_id"

stop_worker
expect_http 'memory deletion request' 202 -X POST \
  -H 'Content-Type: application/json' -H "Idempotency-Key: memory-delete-$suffix" \
  -H "Authorization: Bearer $user_token" --data '{"policyBasis":"USER_REQUEST"}' \
  http://localhost:8080/v1/deletions/memories/"$memory_id"
memory_operation_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["operationId"])' "$response_file")
test "$(db_query "SELECT lifecycle_state FROM memos.memory_lineage WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = DELETE_REQUESTED
test "$(db_query "SELECT count(*) FROM memos.memory_search_projection WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = 0
expect_http 'hidden memory inspection' 404 \
  -H "Authorization: Bearer $user_token" \
  http://localhost:8080/v1/memories/"$memory_id"
expect_http 'old projection job replay' 409 -X POST \
  -H "Authorization: Bearer $user_token" \
  http://localhost:8080/v1/materialization-jobs/"$projection_job_id"/replay

start_worker
wait_for_deletion "$memory_operation_id" "$user_token" 'http://localhost:8080/v1/deletions'
test "$(db_query "SELECT lifecycle_state FROM memos.memory_lineage WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = ERASED
test "$(db_query "SELECT count(*) FROM memos.memory_version WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid AND (content_state <> 'ERASED' OR normalized_content IS NOT NULL OR content_fingerprint IS NOT NULL)")" = 0
test "$(db_query "SELECT count(*) FROM memos.erasure_tombstone WHERE tenant_id = '$tenant_id' AND object_type = 'MEMORY_LINEAGE' AND object_id = '$memory_id'::uuid")" = 1

stop_worker
start_worker
sleep 1
test "$(db_query "SELECT count(*) FROM memos.memory_search_projection WHERE tenant_id = '$tenant_id' AND memory_id = '$memory_id'::uuid")" = 0

deleted_user_id="feature5-erased-user-$suffix"
deleted_agent_id="feature5-erased-agent-$suffix"
deleted_subject_id="feature5-erased-subject-$suffix"
deleted_user_token=$(python3 scripts/generate-dev-jwt.py --tenant "$tenant_id" --user "$deleted_user_id" \
  --agent "$deleted_agent_id" --subject "$deleted_subject_id" --role USER)
user_source_id="feature5-user-source-$suffix"
user_source_body=$(printf '{"sourceId":"%s","sessionId":"feature5-user-session","actorType":"USER","sourceType":"CONVERSATION_MESSAGE","trustLevel":"DIRECT_USER","occurredAt":"2026-08-30T02:01:00Z","payload":{"content":"I prefer a dark editor theme."}}' "$user_source_id")
expect_http 'user-scope source ingestion' 202 \
  -H 'Content-Type: application/json' -H "Idempotency-Key: feature5-user-source-$suffix" \
  -H "Authorization: Bearer $deleted_user_token" --data "$user_source_body" \
  http://localhost:8080/v1/source-events
deleted_memory_id=$(wait_for_memory "$tenant_id" "$deleted_user_id" "$deleted_agent_id")
test -n "$deleted_memory_id"

expect_http 'ordinary user privacy-admin route' 403 -X POST \
  -H 'Content-Type: application/json' -H "Idempotency-Key: forbidden-user-delete-$suffix" \
  -H "Authorization: Bearer $user_token" --data '{"policyBasis":"LEGAL_ERASURE"}' \
  http://localhost:8080/v1/admin/deletions/users/"$deleted_user_id"
expect_http 'privacy-admin user deletion' 202 -X POST \
  -H 'Content-Type: application/json' -H "Idempotency-Key: user-delete-$suffix" \
  -H "Authorization: Bearer $admin_token" --data '{"policyBasis":"LEGAL_ERASURE"}' \
  http://localhost:8080/v1/admin/deletions/users/"$deleted_user_id"
user_operation_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["operationId"])' "$response_file")
wait_for_deletion "$user_operation_id" "$admin_token" 'http://localhost:8080/v1/admin/deletions'
test "$(db_query "SELECT count(*) FROM memos.source_event WHERE tenant_id = '$tenant_id' AND user_id = '$deleted_user_id' AND (deletion_state <> 'ERASED' OR payload <> '{}'::jsonb OR content_fingerprint IS NOT NULL OR request_fingerprint IS NOT NULL)")" = 0
test "$(db_query "SELECT count(*) FROM memos.memory_search_projection WHERE tenant_id = '$tenant_id' AND user_id = '$deleted_user_id'")" = 0

post_delete_body=$(printf '{"sourceId":"post-delete-%s","sessionId":"feature5-user-session","actorType":"USER","sourceType":"CONVERSATION_MESSAGE","trustLevel":"DIRECT_USER","occurredAt":"2026-08-30T02:02:00Z","payload":{"content":"content must stay rejected"}}' "$suffix")
expect_http 'post-user-delete ingestion' 409 \
  -H 'Content-Type: application/json' -H "Idempotency-Key: post-delete-$suffix" \
  -H "Authorization: Bearer $deleted_user_token" --data "$post_delete_body" \
  http://localhost:8080/v1/source-events
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["code"] == "USER_SCOPE_ERASED"' "$response_file"

if grep -Fq 'content must stay rejected' "$api_log" "$worker_log"; then
  echo 'Application logs leaked rejected memory content.' >&2
  exit 1
fi

echo 'Feature 5 JWT/RBAC, audit, governed erasure, replay defense, and resurrection prevention smoke passed.'
