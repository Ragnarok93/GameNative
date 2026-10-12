"""Deterministic WCP packaging of trusted CI DXVK DLL output."""
import io
import json
import re
import tarfile
from pathlib import Path
from .components import validate_package, sha256
from .device import LabError


def package_dxvk(directory, version, code, output):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.+-]{0,127}", version) or type(code) is not int or not 0 <= code < 2**31:
        raise LabError("Invalid immutable DXVK package identity")
    directory, output = Path(directory), Path(output)
    if output.exists():
        raise LabError("Refusing to overwrite package evidence")
    files, payload = [], {}
    for architecture, target, machines in [("x32", "syswow64", {0x14c}), ("x64", "system32", {0x8664, 0xa641, 0xa64e})]:
        for name in ("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll"):
            path = directory / architecture / name
            data = path.read_bytes()
            if len(data) < 64 or data[:2] != b"MZ":
                raise LabError("Invalid PE DLL: " + str(path))
            offset = int.from_bytes(data[60:64], "little")
            if offset + 6 > len(data) or data[offset:offset+4] != b"PE\0\0" or int.from_bytes(data[offset+4:offset+6], "little") not in machines:
                raise LabError("Unexpected DLL architecture: " + str(path))
            relative = architecture + "/" + name
            payload[relative] = data
            files.append({"source": relative, "target": "${" + target + "}/" + name})
    manifest = {"type": "DXVK", "versionName": version, "versionCode": code,
                "description": "Pinned lab DXVK build; performance/stability not promoted", "files": files}
    payload["profile.json"] = json.dumps(manifest, indent=2, sort_keys=True).encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with tarfile.open(output, "x:xz", format=tarfile.USTAR_FORMAT) as archive:
            for name, data in sorted(payload.items()):
                entry = tarfile.TarInfo(name)
                entry.size, entry.mode, entry.mtime = len(data), 0o644, 0
                archive.addfile(entry, io.BytesIO(data))
        digest = sha256(output)
        validated = validate_package(output, "dxvk", version, digest)
        output.with_suffix(output.suffix + ".sha256").write_text(digest + "  " + output.name + "\n")
        return validated
    except BaseException:
        # Preserve failures as evidence and never label them complete packages.
        if output.exists(): output.rename(output.with_suffix(output.suffix + ".partial"))
        raise
