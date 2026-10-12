"""Pin FFVII Continue inputs without copying or changing production saves."""
import re
import shlex
import json
from pathlib import Path
from .device import LabError, PACKAGE


def snapshot(device, source, destination):
    if not re.fullmatch(r"(?:STEAM|CUSTOM_GAME)_[0-9]+", source):
        raise LabError("Invalid game source")
    with device.lease():
        device.assert_idle_debug(require_bridge=False)
        root = f"files/imagefs_shared/home/xuser-{source}/"
        documents = root + ".wine/drive_c/users/xuser/Documents"
        files = device.call("shell", "run-as", PACKAGE, "find", documents, "-type", "f").splitlines()
        selected = [name for name in files if name.lower().endswith(".sav") or name.rsplit('/', 1)[-1].lower() in {"gameusersettings.ini", "input.ini"}]
        if not selected or len(selected) > 32:
            raise LabError("Expected 1-32 save/settings files; choose a narrower workload explicitly")
        saves = []
        for name in sorted(selected):
            if not name.startswith(root):
                raise LabError("Unexpected save root")
            digest = device.call("shell", "run-as", PACKAGE, "sha256sum", shlex.quote(name)).split()[0]
            saves.append({"path": name.removeprefix(root), "sha256": digest})
        device.assert_idle_debug(require_bridge=False)
        result = {"source_container_id": source, "selection": "Continue using frozen common/device metadata and all save slots",
                  "saves": saves, "production_data_copied": False, "timestamp_preservation": "Device staging preserves each source file timestamp"}
        path = Path(destination)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x") as stream:
            stream.write(json.dumps(result, indent=2) + "\n")
        return {"manifest": str(path), "pinned_files": len(saves), "source_unchanged": True}
