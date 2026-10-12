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
