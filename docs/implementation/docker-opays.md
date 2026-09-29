# Local Docker deployment and OpenAI-compatible embedding

This deployment adds an API embedding adapter alongside the deterministic fake and Ollama
providers. API and worker use the same embedding configuration for query vectors and persisted
projections. PostgreSQL remains the authority; no extra vector database is introduced.

## Models and boundaries

| Role | Local OPAY configuration |
|---|---|
| Candidate extraction | `deepseek-v4-flash@us`, through the existing structured-extraction adapter |
| Projection, query, context token usage | `nebius/Qwen/Qwen3-Embedding-8B`, 1024 dimensions |
| Reranking | Disabled by default; existing fused ordering remains available |

VLM, MinerU and web search are not dependencies of this memory ingestion/retrieval deployment.
Candidate extraction settings affect memory formation, not answer generation. A chat/answering
application requires its own integration; this deployment does not add a frontend.

This deployment explicitly selects `candidate-extraction-v2`, which defines semantic preferences
versus procedural instructions. The original v1 prompt/schema and frozen benchmark files are not
modified. Real-provider resource loading rejects unknown prompt versions rather than recording
a label unrelated to the actual prompt. The deterministic write/authorization policy is unchanged.

1024 dimensions match the existing V007 partial HNSW index. A same-sized vector from another
model is still a different projection: existing data must be explicitly rebuilt before changing
the model version. This deployment uses its own PostgreSQL volume and does not convert any
previous deployment's memory vectors.

The `*-deploy-20260929` values identify the deployed configuration. OPAY can normalize its
response model IDs, and no immutable backing-model digest has been attested by the gateway.
These labels are not independently verified model snapshots. The frozen Ollama benchmark
configuration and `NOT RUN` result status remain unchanged.

## Start and inspect

The API and worker images build with the checked-in Maven 3.9.16 Wrapper and JDK 25. Containers
run as an unprivileged user with bounded Java heap sizes. The root Compose file now includes
the `postgres`, `api` and `worker` services under the Docker Desktop group `memos`.

Use [the OPAY environment template](../../docker/opays.env.example) for the local settings;
`.env` is ignored by Git and excluded from Docker builds. Populate the OPAY key and JWT signing
secret in that file. Quote secrets containing `$` with single quotes. The environment defaults
in [.env.example](../../.env.example) still use deterministic fake providers.

```bash
docker compose up -d --build --wait
docker compose ps
curl --fail http://127.0.0.1:18080/readyz
curl --fail http://127.0.0.1:18081/readyz
```

The OPAY template publishes only loopback ports: API `18080`, worker management `18081`, and
PostgreSQL `15432`. Compose connects applications to `postgres:5432` internally. The worker's
readiness endpoint is not a chat UI, and the project does not currently ship a frontend.

Authenticated memory API calls use the existing JWT helper. Export the local environment before
running the real-provider smoke:

```bash
set -a
source .env
set +a
python3 scripts/smoke-api-provider.py --base-url http://127.0.0.1:18080
```

The smoke creates a synthetic dark-theme preference in a unique scope, checks duplicate-ingest
identity, waits on observable source materialization, checks real vector-only retrieval and
context-token usage, and verifies another user cannot read the memory or source status. It retains
only its own synthetic smoke data and does not reset existing business data.

For another OpenAI-compatible embedding service, configure:

```text
MEMOS_EMBEDDING_PROVIDER=openai-compatible
MEMOS_EMBEDDING_BASE_URL=<API base ending before /embeddings>
MEMOS_EMBEDDING_API_KEY=<provider key>
MEMOS_EMBEDDING_MODEL_TAG=<request model ID>
MEMOS_EMBEDDING_MODEL_VERSION=<deployment-attested projection identity>
MEMOS_EMBEDDING_DIMENSIONS=1024
MEMOS_EMBEDDING_TIMEOUT=60s
```

Both API and worker must receive matching values. Requests send an array with one text,
`dimensions`, and `encoding_format=float`. Providers must return one indexed finite vector with
the expected dimension and integer `usage.prompt_tokens`; services lacking this contract need a
separate adapter or token counter. The adapter bounds response bytes and request time, calls
outside transactions, rejects stale requested versions, and maps HTTP 429/5xx/transport failures
to retryable projection errors. Invalid dimensions/protocol/usage and 4xx are permanent failures.
Exceptions omit raw provider content and secrets.

## Verification

On 2026-09-29, the direct OPAY contract probe returned a 1024-dimensional vector with token usage,
and DeepSeek accepted seeded JSON-schema output. Twelve new API embedding cases and four existing
Ollama adapter cases passed on JDK 25, including dimension/version mismatch, malformed or oversized
responses, nonfinite values, timeout, retry/dead mapping, and content-safe errors.

All three deployed containers passed readiness. The real smoke passed ingest → DeepSeek
extraction → authoritative memory → Qwen API projection → vector-only retrieval → complete-context
token counting: one memory was selected and its complete context counted 150 tokens. Duplicate
ingest preserved identity and a foreign user saw neither the memory nor its source status.

The initial v1 extraction proposed PROCEDURAL for an ordinary preference, producing a policy
review and no memory. This evidence was retained. The separately selected v2 prompt produced the
successful fresh-scope smoke; it does not establish general extraction quality or change policy.

Two resource-version tests preserve v1/schema compatibility and reject unknown labels. The
isolated publication snapshot passed the complete Maven Wrapper Java 25 `clean verify`, including
PostgreSQL integration, 65 Python workspace tests with format/lint, the smoke script's format/lint,
and links in 66 Markdown files. The unrelated concurrent RAG-practice changes were excluded from
the published snapshot and runtime images. Both runtime images were built from that snapshot.
These checks establish integration behavior, not formal model-quality or cost results.
