import json
import re
import subprocess
import tempfile
import hashlib
from contextlib import contextmanager
from pathlib import Path


class LabError(RuntimeError):
    pass


PACKAGE = "app.gamenative"
BRIDGE = "app.gamenative.test/androidx.test.runner.AndroidJUnitRunner"


class Device:
    def __init__(self, serial, adb="adb", execute=subprocess.run):
        if not re.fullmatch(r"[A-Za-z0-9_.:-]+", serial):
            raise LabError("An explicit valid device serial is required")
        self.serial, self.adb, self.execute = serial, adb, execute

    @contextmanager
    def lease(self):
        import fcntl
        path = Path(tempfile.gettempdir()) / ("gamenative-lab-" + hashlib.sha256(self.serial.encode()).hexdigest() + ".lock")
        with path.open("a") as stream:
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise LabError("Another host operation owns this device") from exc
            try:
                yield
            finally:
                fcntl.flock(stream, fcntl.LOCK_UN)

    def call(self, *args, timeout=30, data=None, stream=None):
        try:
            result = self.execute(
                [self.adb, "-s", self.serial, *args], input=data, stdin=stream,
                capture_output=True, timeout=timeout, check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise LabError(f"ADB failed: {exc}") from exc
        if result.returncode:
            raise LabError(result.stderr.decode(errors="replace") or "ADB failed")
        return result.stdout.decode(errors="replace").strip()

    def info(self):
        if self.call("get-state") != "device":
            raise LabError("Device must be online and authorized")
        return {
            "serial": self.serial,
            "model": self.call("shell", "getprop", "ro.product.model"),
            "soc": self.call("shell", "getprop", "ro.soc.model"),
            "android": self.call("shell", "getprop", "ro.build.version.release"),
            "fingerprint": self.call("shell", "getprop", "ro.build.fingerprint"),
            "abi": self.call("shell", "getprop", "ro.product.cpu.abilist"),
        }

    def assert_idle_debug(self):
        # Instrumentation can kill an existing target process: never invoke it
        # while the app or guest runtime is active, including read-only commands.
        processes = self.call("shell", "ps", "-A", "-o", "NAME")
        for name in processes.splitlines()[1:]:
            if re.search(r"app\.gamenative|wine|wineserver|FEX|fex|box64|box86", name):
                raise LabError("Close GameNative and all guest processes before using the bridge")
        # run-as is an authoritative debuggable-package gate, no dumpsys substring guess.
        self.call("shell", "run-as", PACKAGE, "true")
        instrumentation = self.call("shell", "pm", "list", "instrumentation")
        if not any(BRIDGE in line and "(target=app.gamenative)" in line for line in instrumentation.splitlines()):
            raise LabError("Matching debug androidTest APK is not installed")

    def bridge(self, request, timeout=180):
        run_id = request["run_id"]
        if not re.fullmatch(r"[a-f0-9]{32}", run_id):
            raise LabError("Invalid run ID")
        self.assert_idle_debug()
        path = f"cache/lab-{run_id}.json"
        self.call("exec-out", "run-as", PACKAGE, "tee", path,
                  data=json.dumps(request, allow_nan=False).encode())
        try:
            output = self.call(
                "shell", "am", "instrument", "-w", "-r", "-e", "class",
                "app.gamenative.benchmark.BenchmarkBridgeTest", "-e", "lab_request",
                path, BRIDGE, timeout=timeout,
            )
            matches = re.findall(r"LAB_RESULT=(\{[^\r\n]*\})", output)
            if len(matches) != 1:
                raise LabError("Instrumentation did not return one structured result: " + output[-2000:])
            result = json.loads(matches[0])
            if result.get("protocol_version") != 1 or result.get("run_id") != run_id or result.get("status") != "complete":
                raise LabError("Bridge rejected operation: " + json.dumps(result))
            return result
        finally:
            # Only remove this request, never configs, caches or application data.
            self.call("shell", "run-as", PACKAGE, "rm", "-f", path)
