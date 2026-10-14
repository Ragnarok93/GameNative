# GameNative development and device lab

Use the WSL checkout and host tools for device testing. Preserve unrelated work.
Run `python3 -m unittest discover -s tools/benchmark/tests -v` after host-tool changes.
Use the `gamenative-device-testing` project skill for lab operations; its procedure
is documented in `docs/device-testing.md`.

All Codex test APKs must use the existing Upgradeable Debug APK workflow/key,
`app.gamenative` application ID, and shared time-based version code. Do not
uninstall or clear data to repair a signing/version mismatch; rebuild a fresh
artifact with the correct identity. See `docs/codex-upgradeable-builds.md`.

Read-only device checks and local/mock validation are authorized by lab work.
Before installing an APK or making device-wide changes, check for explicit user
authorization in the current session; ask only if it has not already been given.
Never interrupt an active game, mutate its container, or expose lab commands
through exported production activities/receivers. Phase 1 configuration checks
are limited to dedicated `BENCHMARK_` sandboxes while the device is idle.

Report requested configuration separately from persisted readback and effective
runtime configuration. Phase 1 produces no FPS/frametime claims. Stable promotion
requires measured repeatable baseline comparisons, not a successful build.

For game sessions use schema-v2 profiles and `docs/benchmark-campaigns.md`.
Discover routes with exploration/screenshots/guest controls, then freeze and replay
them for comparative trials. Keep production saves unchanged; benchmark prefixes
receive pinned copies. Do not infer source-game FPS from synthetic/display frames,
accept partial ring coverage as complete, or fabricate source/binary hashes.

## Engineering workflow: Agent Skills

At session start or a phase change, use the installed `using-agent-skills` skill
when available; load only the phase skills needed for the current task. Do not
run the full lifecycle for every small fix. If a skill is unavailable in WSL,
state that limit and apply the project gates below, without claiming invocation.

- State material assumptions before non-trivial changes; resolve contradictory
  requirements before risky action. Existing user authorization remains scoped.
- Define the next observable acceptance event, implement one small slice, and
  verify it before expanding scope. Prefer native APIs/CLI over UI retries.
- Bugs: debugging/error recovery, targeted regression test, scoped review.
  Optimization: performance-optimization; measure a pinned baseline first.
  Critical logs: observability-and-instrumentation during implementation.
- Keep completion distinct from build success: tests, applicable lint/build,
  runtime behavior, integration, documentation and rollback must be evidenced.
  Mark pending device validation explicitly; do not label it done.
- For component trials, record source, package and mapped runtime hashes plus
  imported/selected/loaded/gameplay-captured stages. Same hashes are not new builds;
  unmatched gameplay captures are not measured improvements.
- Reuse completed checks/artifacts. Report meaningful transitions or failures,
  not unchanged polls. Preserve detailed logs without rereading them per tick.
- Review scope, simplicity and relevant security before commit; obtain required
  human review/authorization before merge/deploy. Never infer broader permissions.
