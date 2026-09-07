# Local environment observation — 2026-09-07

`CONFIRMED` local observations; these are environment/mechanism facts, not benchmark results.

- Apple M4, macOS 26.6.2 (25G83), arm64, 16 GiB RAM; about 40 GiB initially available on the workspace volume.
- Login shell selected Temurin Java 8; existing Homebrew OpenJDK 25.0.4.1 is now selected only by
  sourcing `scripts/local-env.sh`. No global Java default was changed.
- Checked-in Maven Wrapper: 3.9.16. System Maven is not used.
- Installed Docker CLI 29.8.0, Compose 5.5.1 and Ollama 0.33.3 via Homebrew; its dependency
  resolution also updated OpenSSL, SQLite and Python 3.14 patch versions and installed MLX.
- Podman client 6.1.0; created a manual `memos-local` VM with 2 CPUs, 3072 MiB RAM and 20 GiB
  virtual disk. Slow network pull was interrupted in favor of an existing local compressed
  machine image whose SHA-256 was independently verified as
  `e9bd720c2fbe75210332fa89d19c23260865604fef7b9e188dff8a9b27884533`.
  Zstd integrity validation succeeded. The first boot was interrupted when its tool process group
  ended; the following boot hit an Ignition group-file lock. Its log is retained as
  `logs/vm-first-boot-failure.log`. The still-empty task-created VM was recreated from the verified
  cache, with the startup terminal kept alive. This cache reuse is a local observation;
  fresh clones use `podman machine init` to download an image normally.
- Ollama is a manual loopback process, one loaded model and one parallel inference at a time.
  `brew services list` reports Ollama and Podman `none`. No new login service was registered.
- Existing Codex compatibility proxy at loopback 31415 passed health and authenticated model-list
  reads. No proxy token was printed. Existing proxy startup configuration was left unchanged.
- Initial Java baseline failed solely at missing Docker/Testcontainers environment; retained
  local `logs/baseline-java.log`. Preflight before VM boot also retained in `logs/`.
- Python formatter/linter and 65 tests passed before changes. Compilation/package with tests
  skipped passed, which is explicitly not a substitute for PostgreSQL integration validation.

Final integration and model-run evidence belongs in [progress](../progress.md) and
[benchmark results](../benchmark/results.md), with raw runtime logs under ignored `logs/`.

## Verified runtime checkpoint

PostgreSQL 18.6 and pgvector 0.8.6 run in the named local VM, with the repository-pinned image
and persistent volume. All six process smokes passed. Java verification passed 193 tests and
Python 65 tests; these are local implementation checks. Ollama model identity preflight verified
both frozen digests (Qwen3 4b extraction/answer and Qwen3 embedding 0.6b, 1024 dimensions).
After installation/downloads, the workspace volume had about 32 GiB available.

The Codex mode's initial end-to-end call was quarantined with `UNKNOWN_FIELD` at `$.$schema`:
it produced schema metadata instead of an exact candidate instance. The failed scoped receipt,
settlement and usage are retained in `logs/codex-end-to-end-failed-unknown-field.json`. The prompt
now explicitly distinguishes an instance from its schema; strict decoding remains unchanged.
This development-specific prompt is separate from frozen Ollama benchmark prompts.

The subsequent adjusted Codex-mode request passed extraction, candidate materialization and
projection, and returned one scoped memory. Its separate artifact is `logs/codex-end-to-end.json`;
it does not overwrite the retained failed package. On switching to Ollama, the documented explicit
maintenance transaction advanced the database to generation 2 and queued Qwen projection rebuilds.

A clean detached checkout at c1b5225 compiled and passed all Java tests in 26.922 seconds, then
started both real-provider processes from its own rebuilt JARs. PostgreSQL and model caches were
reused; this is a clean-code checkout test, not a second newly provisioned machine. During the
frozen run the host reported 17,323.88 MiB swap in use (global observation, not attributable solely
to MemOS). Existing desktop workloads were not terminated. Latencies describe this local run
only and cannot establish representative SLOs or a controlled hardware performance comparison.

## Fresh GitHub clone and final manual runtime

An actual SSH clone of feat_evidence-gate from GitHub at d82376e was created at
`/Users/guozhixiang/Agent/memos-fresh-clone-20260907`. The clone was clean, passed preflight including
the frozen model digests, then passed Maven Wrapper clean verify (193 tests, no failures/errors/
skips, 26.053 seconds); the original workspace retains `logs/fresh-clone-verify.log`. Installed
prerequisites, Maven cache, PostgreSQL and model files were reused, so this is not independent
provisioning on a second machine. Testcontainers exercised fresh database migrations.

After the frozen artifact was sealed and reconstructed, both Qwen Java processes were stopped.
An explicit maintenance transaction selected deterministic-hashing-1024-v1 as generation 3 for
the separate Codex development mode. API and worker now run from the actual clone's rebuilt JARs
on loopback 8080/8081, both readiness UP. Existing Codex proxy remains at 31415; its token was not
printed. Ollama's task-owned manual process was stopped. brew services still reports ollama and
podman none. The VM and PostgreSQL remain manually running; no boot/login startup was added.
This mode uses real Codex extraction with deterministic development embeddings and is not the
configuration that generated the sealed Qwen benchmark. Original authority and artifacts remain.
