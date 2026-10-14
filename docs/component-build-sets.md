# Pinned component build sets

## WSL with native Windows ADB

If large USB transfers stall through WSL USB/IP, the runner can invoke native
Windows ADB while repository/Python work remains in WSL:

```bash
export GAMENATIVE_ADB=/mnt/c/Users/Admin/AppData/Local/Android/platform-tools/adb.exe
python3 tools/benchmark.py --serial R5CN102RCQZ device-info
```

Detach the device from usbipd before using native Windows USB. Do not run competing
Windows/Linux ADB servers against it. Local file arguments passed to native ADB
need Windows-accessible paths; the lab's shell-stdin component staging does not
use `adb push` paths. Revalidate binary transfers and traces on a changed transport.

`build-set` is a host-only CI resolver/collector for GameNative, DXVK, FEX,
Turnip, Proton and LSFG. It does not launch workflows, compile code, install
components, claim compatibility, or promote builds. Dispatch adapters and a
validated end-to-end build/test pipeline remain roadmap work.

## Dispatch a pinned workflow

`dispatch-build --manifest PLAN.json --output NEW_LEDGER.json` validates a ref
and active workflow without submitting a build. Add `--execute` to submit it.
The plan has exactly `schema_version: 1`, `component`, `repository`, `commit`,
`ref`, `workflow`, and `inputs` (string key/value map). Component names are the
same six names below. GameNative requires `.github/workflows/upgradeable-debug.yml`.
Other workflows must be explicitly selected from their actual repositories.

The ref must resolve to the pinned full commit at preflight. GitHub dispatches
by branch/tag, so a concurrent ref move after preflight is still possible; the
resulting run MUST be verified against the intended commit before collection.
This command does not claim the build started at the requested commit, correlate
a run ID, wait for completion, or infer success from dispatch acceptance.

A durable ledger is written before submission. `dispatched_uncorrelated` means
the API accepted the request; `dispatch_unknown` means acceptance is uncertain.
Never blindly retry either state. Inspect GitHub for the matching workflow/ref,
event, timing and exact commit first, then supply its verified run ID to build-set.
Existing ledger paths refuse reuse. An HTTP error or timeout may leave a real
build running; no automatic cancellation or resubmission occurs.

```bash
python3 tools/benchmark.py build-set --manifest /path/to/pinned-builds.json \
  --output /path/to/fresh-evidence
# Add --download to retrieve each named artifact and hash every extracted file.
```

Manifest schema version is 1. The only top-level fields are `schema_version` and
`components`. Components must contain exactly `gamenative`, `dxvk`, `fex`,
`turnip`, `proton`, and `lsfg`. Each component entry has exactly these fields:

```json
{
  "repository": "OWNER/REPOSITORY",
  "commit": "FULL_40_CHARACTER_LOWERCASE_COMMIT",
  "workflow": ".github/workflows/build.yml",
  "run_id": 123,
  "artifact": "EXACT_ARTIFACT_NAME",
  "build_inputs": {"compiler": "PINNED_COMPILER", "dependencies": "PINNED_REVISIONS"}
}
```

This fragment is illustrative, not a runnable build identity. Resolve actual
repository workflows and successful runs before creating a complete manifest.
Each CI run must match the exact head commit and workflow path, be completed and
successful, and expose exactly one unexpired artifact of the named identity.
Truncated artifact inventories fail closed. Existing output directories are never
overwritten. Failed retrieval retains a `failed` build-set.json and partial files;
it must not be treated as a complete build set. Downloads do not execute build
scripts. Run only against trusted repositories/artifacts.

`build_inputs` is a declaration, not proof that CI actually used those inputs.
Artifact SHA-256 hashes establish the collected bytes, not authenticity against an
independently supplied expected hash. Existing package/runtime pin validation is
still required before deployment. A set of successful builds is not a tested
compatibility matrix. LSFG may be built as part of an APK; its native commit and
binary hashes must additionally agree with the GameNative build metadata.
