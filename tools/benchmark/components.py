import hashlib
import json
import tarfile
import re
from pathlib import Path, PurePosixPath
from .device import LabError

TYPES = {"dxvk": "DXVK", "fex": "FEXCore", "turnip": "Turnip", "proton": "Proton"}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_package(path, component, version, expected_hash):
    if component not in TYPES:
        raise LabError("Unsupported component")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.+-]{0,127}", version):
        raise LabError("Version must be a safe content directory name")
    actual_hash = sha256(path)
    if actual_hash != expected_hash:
        raise LabError("Package SHA-256 mismatch")
    # Tar headers are inspected without extracting anything on the host. Phase 1
    # supports XZ WCPs; reject zstd explicitly rather than pretend validation ran.
    try:
        with tarfile.open(path, "r:xz") as archive:
            members = {}
            total_size = 0
            for entry in archive:
                name = PurePosixPath(entry.name)
                if name.is_absolute() or ".." in name.parts or "\\" in entry.name:
                    raise LabError("Unsafe archive path")
                normalized = str(name)
                if normalized in members or not (entry.isfile() or entry.isdir()):
                    raise LabError("Duplicate archive path or unsupported links/devices")
                members[normalized] = entry
                total_size += entry.size
                if total_size > 4 * 1024**3:
                    raise LabError("Archive expanded size exceeds Phase 1 limit")
                if len(members) > 100000:
                    raise LabError("Archive contains too many members")
            manifest = members.get("profile.json")
            if not manifest or not manifest.isfile() or manifest.size > 1048576:
                raise LabError("Missing or oversized profile.json")
            profile = json.load(archive.extractfile(manifest))
            if profile.get("type") != TYPES[component] or profile.get("versionName") != version:
                raise LabError("Content type/version does not match requested pin")
            if not isinstance(profile.get("description"), str):
                raise LabError("Content description is required")
            if component == "proton" and not version.lower().startswith("proton"):
                raise LabError("Use a proton-prefixed versionName to avoid installer name normalization")
            if type(profile.get("versionCode")) is not int or profile["versionCode"] < 0:
                raise LabError("Invalid content versionCode")
            files = profile.get("files")
            if not isinstance(files, list) or not files:
                raise LabError("Content manifest must list payload files")
            for entry in files:
                source, target = entry.get("source"), entry.get("target")
                if not isinstance(source, str) or not isinstance(target, str):
                    raise LabError("Invalid content file mapping")
                item = members.get(str(PurePosixPath(source)))
                if not item or not item.isfile() or ".." in PurePosixPath(source).parts:
                    raise LabError("Missing or unsafe manifest source")
                if ".." in PurePosixPath(target).parts or "\\" in target or not target.startswith("${"):
                    raise LabError("Unsafe manifest target")
    except (tarfile.TarError, OSError, ValueError, TypeError, AttributeError) as exc:
        raise LabError(f"Invalid XZ WCP: {exc}") from exc
    return {"path": str(Path(path).resolve()), "sha256": actual_hash,
            "type": TYPES[component], "version": version, "version_code": profile["versionCode"]}
