"""Observe/control only an active, instrumentation-owned exploration session."""
import fcntl
import hashlib
import json
import re
import subprocess
import tempfile
import time
import uuid
from pathlib import Path
from .device import LabError, PACKAGE
from .run_profiles import require


def status(device, run_id):
    require(isinstance(run_id, str) and re.fullmatch(r"[a-f0-9]{32}", run_id), "Invalid run ID")
    pending = json.loads(device.call("shell", "run-as", PACKAGE, "cat", "files/benchmark-lab/pending.json"))
    require(pending.get("run_id") == run_id, "Session is no longer the active transaction")
    value = json.loads(device.call("shell", "run-as", PACKAGE, "cat", f"files/benchmark-lab/{run_id}/status.json"))
    require(value.get("run_id") == run_id, "Status belongs to a different run")
    return value


def command(device, run_id, action, output=None):
    require(isinstance(action, dict) and action.get("type") in {"key", "tap", "look", "screenshot"}, "Unsupported control action")
    root = f"files/benchmark-lab/{run_id}"
    key = hashlib.sha256((device.serial + run_id).encode()).hexdigest()
    with (Path(tempfile.gettempdir()) / ("gamenative-control-" + key + ".lock")).open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        current = status(device, run_id)
        require(current["phase"] in {"WARMING_UP", "MEASURING"}, "Controls require a ready exploration session")
        command_id = uuid.uuid4().hex
        payload = json.dumps({"run_id": run_id, "command_id": command_id, "action": action}, allow_nan=False).encode()
        require(len(payload) <= 8192, "Control request too large")
        device.call("shell", "run-as", PACKAGE, "mkdir", "-p", root + "/commands")
        upload = root + "/commands/" + command_id + ".upload"
        final = root + "/commands/" + command_id + ".json"
        try:
            device.call("shell", "-T", "run-as", PACKAGE, "tee", upload, data=payload)
            device.call("shell", "run-as", PACKAGE, "mv", upload, final)
            deadline = time.monotonic() + 30
            reply = None
            while time.monotonic() < deadline:
                try:
                    reply = json.loads(device.call("shell", "run-as", PACKAGE, "cat", root + "/" + command_id + ".reply.json", timeout=5))
                    break
                except LabError:
                    time.sleep(.1)
            require(reply is not None and reply.get("command_id") == command_id and reply.get("run_id") == run_id, "Control timed out or mismatched reply")
            require(reply.get("status") == "complete", "Control rejected: " + str(reply.get("error")))
            if action["type"] == "screenshot":
                require(output is not None, "Screenshot output path required")
                remote = reply.get("screenshot")
                require(remote == root + "/" + command_id + ".png", "Invalid screenshot path")
                path = Path(output)
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open("xb") as stream:
                    result = device.execute([device.adb, "-s", device.serial, "exec-out", "run-as", PACKAGE, "cat", remote],
                        stdout=stream, stderr=subprocess.PIPE, timeout=30, check=False)
                binary = path.read_bytes()
                require(result.returncode == 0 and binary.startswith(b"\x89PNG\r\n\x1a\n") and binary.endswith(b"\0\0\0\0IEND\xaeB`\x82"), "Screenshot transfer failed")
                reply["host_path"] = str(path)
            return reply
        finally:
            device.call("shell", "run-as", PACKAGE, "rm", "-f", upload, final)


def export_route(run_directory, destination):
    root = Path(run_directory)
    profile = json.loads((root / "profile.json").read_text())
    require(profile["workload"]["kind"] == "exploration", "Route export requires exploration evidence")
    events = [json.loads(line) for line in (root / "device-interactive-input.jsonl").read_text().splitlines()]
    route = {"setup_actions": [], "actions": []}
    for event in events:
        key = "setup_actions" if event["phase"] == "WARMING_UP" else "actions"
        route[key].append(dict(event["action"], at_ms=event["at_ms"]))
    Path(destination).write_text(json.dumps(route, indent=2) + "\n")
    return {"route": str(destination), "actions_recorded": len(events), "replay_validation_required": True}
