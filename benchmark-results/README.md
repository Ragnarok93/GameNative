# Local lab evidence

Each operation creates a unique run-ID directory containing `events.jsonl`,
`request.json`, `device.json`, and, on success, `result.json`; profile operations
also include `profile.json`. Failed or interrupted runs remain evidence, never
successful benchmarks. JSONL timestamps label host UTC and host monotonic time.

Generated results are local and ignored by Git; do not commit account paths,
device identifiers or game diagnostics without reviewing them. Phase 1 contains
no performance measurements or effective guest-runtime configuration.
