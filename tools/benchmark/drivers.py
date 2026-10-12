import json
import re
import stat
import zipfile
from pathlib import Path, PurePosixPath
from .components import sha256
from .device import LabError


def validate_driver(path, expected_hash):
    if sha256(path) != expected_hash:
        raise LabError("Driver package SHA-256 mismatch")
    try:
        with zipfile.ZipFile(path) as archive:
            names = set()
            size = 0
            for item in archive.infolist():
                name = PurePosixPath(item.filename)
                mode = item.external_attr >> 16
                if name.is_absolute() or ".." in name.parts or "\\" in item.filename or str(name) in names or stat.S_ISLNK(mode):
                    raise LabError("Unsafe driver archive member")
                names.add(str(name))
                size += item.file_size
                if size > 256 * 1024**2 or len(names) > 1024:
                    raise LabError("Driver archive exceeds limits")
            meta_item = archive.getinfo("meta.json")
            if meta_item.file_size > 65536:
                raise LabError("Driver metadata too large")
            meta = json.loads(archive.read(meta_item))
            name, library = meta["name"], meta["libraryName"]
            if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 ._()+-]{0,127}", name):
                raise LabError("Unsafe driver identity")
            if not isinstance(library, str) or not re.fullmatch(r"[A-Za-z0-9_.+-]+\.so", library) or library not in names:
                raise LabError("Missing/unsafe driver library")
            if meta.get("packageType", "icd") != "icd":
                raise LabError("This command installs ICD drivers only")
            binary = archive.read(library)
            if len(binary) < 20 or binary[:5] != b"\x7fELF\x02" or int.from_bytes(binary[18:20], "little") != 183:
                raise LabError("Driver is not an AArch64 ELF library")
            version = meta["driverVersion"]
            if not isinstance(version, str) or not version:
                raise LabError("Driver version required")
    except (zipfile.BadZipFile, KeyError, ValueError, TypeError, NotImplementedError) as exc:
        raise LabError(f"Invalid driver ZIP: {exc}") from exc
    return {"path": str(Path(path).resolve()), "sha256": expected_hash, "name": name,
            "version": version, "library": library}
