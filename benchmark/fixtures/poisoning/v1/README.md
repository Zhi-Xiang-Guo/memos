# Memory-poisoning boundary fixture v1

This deterministic Feature 5 fixture verifies one narrow defense: adversarial memory text is
escaped and rendered only as text below `<memory-evidence trust="untrusted-data">`. The runner
parses the rendered XML, checks that no injected element became structure, and verifies that the
original text round-trips exactly.

This is not evidence that an LLM will ignore every prompt injection, that admission policy detects
every poisoned write, or that MemOS is generally resistant to memory poisoning. Those claims need
real-model write-to-use red-team evaluation in Feature 6.
