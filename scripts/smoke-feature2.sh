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
api_pid=
worker_pid=

cleanup() {
  status=$?
  if [[ -n $worker_pid ]]; then kill "$worker_pid" 2>/dev/null || true; fi
  if [[ -n $api_pid ]]; then kill "$api_pid" 2>/dev/null || true; fi
  wait "$worker_pid" 2>/dev/null || true
  wait "$api_pid" 2>/dev/null || true
  if [[ $status -ne 0 ]]; then
    echo 'Feature 2 smoke failed; API log follows.' >&2
    tail -80 "$api_log" >&2 || true
    echo 'Feature 2 smoke failed; worker log follows.' >&2
    tail -80 "$worker_log" >&2 || true
  fi
  rm -f "$api_log" "$worker_log" "$receipt_file"
  return "$status"
}
trap cleanup EXIT

"$java_bin" -jar "$api_jar" >"$api_log" 2>&1 &
api_pid=$!
for _ in {1..120}; do
  if curl --fail --silent http://localhost:8080/readyz >/dev/null; then break; fi
  sleep 0.25
done
curl --fail --silent http://localhost:8080/readyz >/dev/null

suffix="$(date +%s)-$$"
source_id="feature2-source-$suffix"
body=$(printf '{"sourceId":"%s","sessionId":"feature2-session","actorType":"USER","sourceType":"CONVERSATION_MESSAGE","trustLevel":"DIRECT_USER","occurredAt":"2026-08-27T00:00:00Z","payload":{"content":"I prefer a dark editor theme."}}' "$source_id")
user_token=$(python3 scripts/generate-dev-jwt.py --tenant feature2-tenant --user feature2-user \
  --agent feature2-agent --subject feature2-subject --role USER)

curl --fail --silent --output "$receipt_file" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: feature2-key-$suffix" \
  -H "Authorization: Bearer $user_token" \
  --data "$body" \
  http://localhost:8080/v1/source-events
job_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["materializationJobId"])' "$receipt_file")

"$java_bin" -jar "$worker_jar" >"$worker_log" 2>&1 &
worker_pid=$!
job_url="http://localhost:8080/v1/materialization-jobs/$job_id"
scope_headers=(-H "Authorization: Bearer $user_token")
state=PENDING
for _ in {1..120}; do
  state=$(curl --fail --silent "${scope_headers[@]}" "$job_url" | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])')
  if [[ $state = SUCCEEDED ]]; then break; fi
  sleep 0.25
done
test "$state" = SUCCEEDED

db_query() {
  docker compose exec -T postgres psql -U "${MEMOS_DB_USER:-memos}" -d "${MEMOS_DB_NAME:-memos}" -Atqc "$1"
}

test "$(db_query "SELECT count(*) FROM memos.extraction_run WHERE extraction_job_id = '$job_id'::uuid")" = 1
test "$(db_query "SELECT count(*) FROM memos.memory_candidate candidate JOIN memos.extraction_run run USING (tenant_id, run_id) WHERE run.extraction_job_id = '$job_id'::uuid AND candidate.content_state = 'AVAILABLE'")" = 1
test "$(db_query "SELECT count(*) FROM memos.candidate_policy_decision decision JOIN memos.extraction_run run USING (tenant_id, run_id) WHERE run.extraction_job_id = '$job_id'::uuid AND decision.decision = 'REMEMBER'")" = 1
candidate_job_id=$(db_query "SELECT job_id FROM memos.outbox_job WHERE job_type = 'CANDIDATE_MATERIALIZATION' AND source_event_id = (SELECT source_event_id FROM memos.outbox_job WHERE job_id = '$job_id'::uuid)")
test -n "$candidate_job_id"
candidate_state=PENDING
for _ in {1..120}; do
  candidate_state=$(db_query "SELECT state FROM memos.outbox_job WHERE job_id = '$candidate_job_id'::uuid")
  if [[ $candidate_state = SUCCEEDED ]]; then break; fi
  sleep 0.25
done
test "$candidate_state" = SUCCEEDED
test "$(db_query "SELECT count(*) FROM memos.memory_version version JOIN memos.memory_source source USING (tenant_id, memory_id, version_id) WHERE source.source_event_id = (SELECT source_event_id FROM memos.outbox_job WHERE job_id = '$job_id'::uuid) AND version.content_state = 'AVAILABLE'")" = 1
if grep -Fq 'I prefer a dark editor theme.' "$api_log" "$worker_log"; then
  echo 'Application logs leaked source or candidate content.' >&2
  exit 1
fi

echo "Feature 2 extraction, policy, atomic candidate commit, and compatible downstream processing smoke passed."
