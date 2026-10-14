# Pinned component build sets

`build-set` is a host-only CI resolver/collector for GameNative, DXVK, FEX,
Turnip, Proton and LSFG. It does not launch workflows, compile code, install
components, claim compatibility, or promote builds. Dispatch adapters and a
validated end-to-end build/test pipeline remain roadmap work.

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
