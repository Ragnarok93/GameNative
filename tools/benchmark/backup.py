"""Read-only, offline backups before lab APK updates. No restoration command."""
import hashlib
import os
import subprocess
import tarfile
import uuid
from pathlib import Path, PurePosixPath
from .device import LabError, PACKAGE
from .telemetry import Telemetry

ROOTS = ("files/imagefs_shared/home", "files/datastore", "databases", "shared_prefs",
         "files/profiles", "files/amazon", "files/epic", "files/lsfg")


def hash_stream(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
    return digest.hexdigest()


def verify_archive(path):
    path = Path(path)
    if path.stat().st_size < 1024 or path.stat().st_size % 512:
        raise LabError("Incomplete backup archive")
    with path.open("rb") as stream:
        stream.seek(-1024, 2)
        if stream.read() != bytes(1024):
            raise LabError("Backup archive is missing its end markers")
    roots, critical, links = set(), {}, []
    files, total = 0, 0
    try:
        with tarfile.open(path, "r:") as archive:
            for entry in archive:
                name = str(PurePosixPath(entry.name))
                if PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts:
                    raise LabError("Unsafe backup archive path")
                root = next((root for root in ROOTS if name == root or name.startswith(root + "/")), None)
                if root is None:
                    raise LabError("Unexpected backup archive root")
                roots.add(root)
                if entry.issym() or entry.islnk():
                    links.append({"path": name, "target": entry.linkname})
                elif entry.isfile():
                    files += 1
                    total += entry.size
                    if name.endswith("/.container") or name.startswith(("databases/", "files/datastore/", "shared_prefs/", "files/profiles/", "files/amazon/", "files/epic/")) or name.lower().endswith(".sav"):
                        with archive.extractfile(entry) as stream:
                            critical[name] = hash_stream(stream)
                elif not entry.isdir():
                    raise LabError("Unexpected special file in backup")
        if roots != set(ROOTS) or "databases/pluvia.db" not in critical:
            raise LabError("Backup is missing required app data")
    except (tarfile.TarError, OSError) as exc:
        raise LabError(f"Invalid backup archive: {exc}") from exc
    with path.open("rb") as stream:
        digest = hash_stream(stream)
    return {"archive_sha256": digest, "file_count": files, "payload_bytes": total,
            "critical_file_sha256": critical, "symlinks_and_hardlinks": links,
            "scope": list(ROOTS), "external_symlink_targets_copied": False}


def backup(device, destination):
    run_id = uuid.uuid4().hex
    telemetry = Telemetry(destination, run_id)
    os.chmod(telemetry.directory, 0o700)
    telemetry.event("validating", operation="offline-backup")
    partial = telemetry.directory / "app-data.tar.partial"
    try:
        with device.lease():
            device.info()
            device.assert_idle_debug(require_bridge=False)
            telemetry.artifact("device.json", device.info())
            telemetry.event("copying", scope=list(ROOTS))
            fd = os.open(partial, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(fd, "wb") as output:
                try:
                    result = device.execute(
                        # Shell v2 keeps remote stderr out of the binary archive
                        # and propagates tar's exit status. Wine server sockets
                        # are disposable IPC, not recoverable application data.
                        [device.adb, "-s", device.serial, "shell", "-T", "run-as", PACKAGE,
                         "tar", "--exclude=*/.wine/.wineserver",
                         "--exclude=*/.wine/.wineserver/*", "-cf", "-", *ROOTS],
                        stdout=output, stderr=subprocess.PIPE, timeout=1800, check=False,
                    )
                except (OSError, subprocess.TimeoutExpired) as exc:
                    raise LabError(f"Backup transfer failed: {exc}") from exc
                output.flush()
                os.fsync(output.fileno())
            if result.returncode:
                raise LabError(result.stderr.decode(errors="replace") or "Backup transfer failed")
            device.assert_idle_debug(require_bridge=False)
            telemetry.event("verifying")
            report = verify_archive(partial)
            complete = partial.with_name("app-data.tar")
            partial.rename(complete)
            telemetry.artifact("manifest.json", {"run_id": run_id, "status": "complete", **report})
            telemetry.event("complete", archive_sha256=report["archive_sha256"])
            return {"run_id": run_id, "archive": str(complete),
                    "manifest": str(telemetry.directory / "manifest.json"),
                    "archive_sha256": report["archive_sha256"], "external_symlink_targets_copied": False}
    except BaseException as exc:
        telemetry.event("failed", error=str(exc))
        raise
