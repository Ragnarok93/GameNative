# S20+ Phase 1 acceptance, 2026-10-12

Device: Galaxy S20+ SM-G986U1, SM8250, Android 13. The user authorized
upgradeable APK updates and dedicated configuration tests, and waived the full
backup for this first test.

The app updated in place from version code 4564361 to 4565833, source
5cc918a0a9c5116095a6b826fb77fc916124fb8e. The matching instrumentation APK
installed successfully. The stable signing certificate was verified before the
update; the application ID and Linux UID remained unchanged.

Physical checks passed:

- Debug instrumentation component inventory: run
  `2a70053c744c4ff898b657fa280fa61e`.
- Created `BENCHMARK_FF7R` from `CUSTOM_GAME_227938910` through ContainerManager,
  with a separate initialized prefix and no copied game saves: run
  `00c99fcbdfb34496aeace35d77db8019`.
- Roundtrip of 1152x648, `showFPS=false`, empty environment variables and empty
  DXVK configuration; exact original bytes restored: run
  `52414a415e194c9d81f9b0aaf44e7cb3`.
- Wrong type, unknown key and production-container mutation requests rejected.
- Recovery with no pending journal returned `recovered=false`.
- A second host lease for the same device was refused while the first was held.

Source config SHA-256 before update and after sandbox tests:
`ce07bc3508a02339c4c9caad848a4ebc720f565d9c997c4ea83cc69ee77107f6`.
Sandbox config SHA-256 before and after roundtrips:
`87089b49c694f5c1fbcf5cd2d4ef68166177c6be33d8d9bd1c428c2cbd36d884`.
Private run evidence is under the ignored `benchmark-results/` directories.

The empty graphics-driver configuration check exposed an existing serializer
issue: omitting an empty value makes a fresh Container reload its default. The
bridge refused the readback mismatch and restored the original configuration
(run `c351eb92070846c3a04c1b4f0231c2f8`). A serializer fix and regression test
are included in 4be0cbb1. CI run 38218441486 passed the host tests, Android
regressions (including the new persistence test), APK assembly and identity/native
provenance checks. Both downloaded APK hashes and signatures were verified.

The corrected app and matching instrumentation APK then updated in place to
version code 4565897, `upgradeable-debug-4be0cbb1ff3b`, with UID unchanged.
Roundtrip `4db53260321d44f4b6c4ec058eaaa5b5` passed with explicit empty
graphics-driver configuration as well as all preceding fields. Source and sandbox
hashes above remained identical. Wrong types, unknown keys and production
mutation were rejected again, and recovery reported no pending journal. All 11
FFVII `.sav` files under its Documents directory had identical hashes before this
second APK update and after the completed checks. This verifies those files,
not every account or externally stored save.

The first request upload timed out because the ADB exec-out transport did not
close the remote input. Switching request uploads to shell-v2 (`shell -T`) was
verified physically. An earlier full-backup attempt remained incomplete;
exec-out mixed remote tar errors into archive bytes, and stale Wine sockets
caused tar failures. The helper now uses shell-v2 and excludes disposable
`.wine/.wineserver` directories. Small real-device transport checks and host
tests passed; a complete backup has not been rerun.
The component staging transport also passed a real-device 1 MiB binary/hash
smoke check; its staged file was removed and no component was installed.

Still pending: component package installation, process-death recovery,
corrupt-snapshot recovery refusal, active-game guard on a real running guest,
release-build refusal, save/account verification and switching back to another
branch's fresh upgradeable build. No game was launched and no FPS, runtime
configuration attestation, thermal or presentation measurements were produced.
