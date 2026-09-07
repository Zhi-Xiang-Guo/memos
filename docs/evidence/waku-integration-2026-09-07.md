# Waku Agent consumer integration — 2026-09-07

Status: `CONFIRMED` local integration evidence. This is not production traffic or a scale result.

## Published identities

- MemOS branch: `feat_evidence-gate`, post-gate runtime through `0ce3ac0`.
- Waku Agent branch: `feat_memos-integration`, commit
  [`b75adf2`](https://github.com/Zhi-Xiang-Guo/waku-agent/commit/b75adf2).
- Runtime: Java 25.0.4.1, PostgreSQL 18.6, pgvector 0.8.6, Ollama 0.33.3,
  Qwen3 4b and Qwen3 embedding 0.6b at the full digests recorded in the
  [dev report](../benchmark/runs/dev-20260907-0ce3ac0-01/report.md).

## Adapter boundary

Waku selects `WAKU_SEMANTIC_STORE=memos`. The adapter uses standard-library HTTP and implements
all six Waku `FactStore` methods plus settlement. A verified bearer token supplies tenant, user,
agent and write roles. Request bodies cannot override scope.

- `add`: submit a `DIRECT_MEMORY_COMMAND`, poll the source aggregate, and return only after
  extraction, authority and search projection settle.
- `search` / `search_with_ids`: call bounded hybrid retrieval and expose only versions selected
  into the MemOS context. A configurable adapter threshold plus meaningful query-term overlap
  prevents rank-only misses from becoming Waku facts.
- `list`: page scoped lineages and resolve their current versions.
- `update`: prove the replacement source created a version, invalidate prior active versions with
  the latest ETag, and poll until stale projected versions are no longer selected.
- `delete`: request governed self-service erasure; MemOS hides the target in the request
  transaction and performs fenced asynchronous cleanup.
- `settle`: wait for any writes still owned by the adapter without a blind fixed sleep.

## Verification

An isolated Waku virtual environment was created from its declared dependencies. Offline adapter,
registry, dashboard-asset and conformance tests passed. A real MemOS scope then exercised jasmine
tea write, oolong correction and deletion. Observed Waku results were one initial value, one updated
value, then an empty retrieval after deletion.

The opt-in real backend ran Waku's parameterized conformance suite in a fresh JWT scope:

```text
12 passed, 63 deselected in 93.16s
```

It covered method presence, write/read, miss, ID-bearing search, update, delete, unknown IDs, list,
Cyrillic, CJK and readiness. Waku's repository release gate then reported:

```text
619 passed, 73 skipped
GATE OPEN — safe to release.
```

The optional judge suite skipped because no Anthropic key was configured. No Waku `.waku` runtime
directory was cleared or modified. Ollama, Podman and the Java processes were started manually;
no login service was installed.

## Limits

The current adapter's `0.02` RRF threshold was chosen to reject observed one-channel rank-only
misses while retaining two-channel evidence; lexical overlap can retain a relevant one-channel
candidate. It is a Waku contract default, not a universal calibrated threshold. The integration
uses one agent-scoped JWT and does not establish cross-agent sharing, production identity-provider
rotation, representative freshness/latency, high load, or backup/provider erasure.
