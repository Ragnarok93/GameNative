# Codex / Master / LSFG install-over builds

Use the **Upgradeable Debug APK** workflow and its `GameNative-upgradeable-debug`
artifact for device testing on `Codex-Automated-Testing`, `master`, and LSFG
feature/fix branches. The same workflow runs automatically on these branches;
it can also be dispatched manually on a selected branch.

All these builds retain:

- Application ID: `app.gamenative` (no Codex package suffix).
- Persistent non-production signing key: `.github/debug-signing/gamenative-upgrade-debug.jks`.
- Signer SHA-256: `0ff2ee400fcbaa8bf57a48f0297e49f139be8255fead61f96f409c3e11814d7a`.
- The existing minute-based version-code sequence, independent of branch and release version.
- Signing, package/version, and pinned native-runtime checks before artifact publication.

To switch branches, build a **fresh** artifact from the destination branch, then
install it as an update with `adb -s SERIAL install -r GameNative-upgradeable-debug.apk`.
Do not uninstall the app or clear its data. Older saved artifacts may have a lower
version code and cannot serve as normal updates; rebuild the destination branch
instead of forcing a downgrade. PR validation artifacts and ordinary local SDK
debug-key builds do not belong to this signing identity.

Before a physical-device update, back up valuable saves/container data using the
existing export procedures. Compare the installed package's version code and
signing certificate with `build-metadata.txt` and `signing-report.txt`. Stop on an
identity mismatch; never resolve it by uninstalling. After the update, confirm
accounts, installed game entries, containers, settings, and saves remain available.
Repeat this acceptance check with a fresh LSFG build after the Codex build.
No physical-device update or data-preservation check has been performed locally.

For a local Codex build, use the checked-in key explicitly:

```powershell
$code = 1000000 + [long][Math]::Floor(([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - 1577836800) / 60)
./gradlew.bat -PupgradeSafeDebugSigning=true "-PciVersionCode=$code" "-PciVersionName=upgradeable-debug-local" :app:assembleLegacyDebug
```

Local builds must also rebuild the pinned LSFG runtime and Vulkan renderer as
described in the workflow's preparation actions, and verify the resulting APK's
certificate/package/version before installation. Prefer the CI artifact, which
includes those checks and native provenance metadata.

An install-over update preserves Android app data; it does not guarantee that an
older branch can read database/configuration formats written by a newer branch.
Review schema migrations before switching back to older code, and retain backups.
Production-signed APKs require their own compatible signing lineage and are not
covered by the upgradeable debug key.
