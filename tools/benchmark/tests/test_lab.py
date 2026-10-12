import copy
import io
import json
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmark.components import sha256, validate_package
from benchmark.device import BRIDGE, Device, LabError
from benchmark.orchestrator import execute
from benchmark.profiles import load_profile
from benchmark.compare import compare


class MockAdb:
    def __init__(self, active=False, result_status="complete"):
        self.calls = []
        self.request = None
        self.active = active
        self.result_status = result_status

    def __call__(self, argv, **kwargs):
        self.calls.append(argv)
        assert argv[:3] == ["adb", "-s", "test-device"]
        args = argv[3:]
        output = ""
        if args == ["get-state"]:
            output = "device"
        elif args[:2] == ["shell", "getprop"]:
            output = {"ro.product.model": "SM-G986U1", "ro.soc.model": "SM8250"}.get(args[2], "test")
        elif args[:2] == ["shell", "ps"]:
            output = "NAME\n" + ("app.gamenative\n" if self.active else "init\n")
        elif args[:4] == ["shell", "pm", "list", "instrumentation"]:
            output = "instrumentation:" + BRIDGE + " (target=app.gamenative)"
        elif args[:4] == ["exec-out", "run-as", "app.gamenative", "tee"]:
            self.request = json.loads(kwargs["input"])
        elif args[:3] == ["shell", "am", "instrument"]:
            output = "INSTRUMENTATION_STATUS: stream=LAB_RESULT=" + json.dumps(
                {"protocol_version": 1, "status": self.result_status, "run_id": self.request["run_id"]})
        return subprocess.CompletedProcess(argv, 0, output.encode(), b"")


class LabTests(unittest.TestCase):
    def test_explicit_serial_and_no_shell_injection(self):
        for serial in ("", "a; reboot", "x y", "$(id)"):
            with self.assertRaises(LabError):
                Device(serial)

    def test_active_app_refuses_instrumentation(self):
        mock = MockAdb(active=True)
        with self.assertRaises(LabError):
            Device("test-device", execute=mock).bridge({"run_id": "a" * 32})
        self.assertFalse(any("instrument" in call for call in mock.calls))

    def test_device_failure_and_timeout_propagate(self):
        def fail(argv, **kwargs):
            return subprocess.CompletedProcess(argv, 1, b"", b"unauthorized")
        with self.assertRaisesRegex(LabError, "unauthorized"):
            Device("test-device", execute=fail).info()
        def timeout(argv, **kwargs):
            raise subprocess.TimeoutExpired(argv, 1)
        with self.assertRaises(LabError):
            Device("test-device", execute=timeout).info()

    def test_bridge_response_id_checked_and_request_retired(self):
        mock = MockAdb()
        result = Device("test-device", execute=mock).bridge({"run_id": "a" * 32})
        self.assertEqual(result["status"], "complete")
        self.assertEqual(mock.calls[-1][-2:], ["-f", "cache/lab-" + "a" * 32 + ".json"])

    def test_rejected_bridge_never_records_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(LabError):
                execute(Device("test-device", execute=MockAdb(result_status="failed")), {"operation": "list"}, tmp)
            events = [json.loads(line) for line in next(Path(tmp).glob("*/events.jsonl")).read_text().splitlines()]
            self.assertEqual(events[-1]["event"], "failed")
            self.assertNotIn("complete", [event["event"] for event in events])

    def test_success_telemetry_and_phase1_comparison(self):
        with tempfile.TemporaryDirectory() as tmp:
            device = Device("test-device", execute=MockAdb())
            left = execute(device, {"operation": "list"}, tmp)
            right = execute(device, {"operation": "list"}, tmp)
            report = compare(left["results"], right["results"])
            self.assertFalse(report["measured_performance"])
            self.assertEqual(report["changes"], {})
            events = [json.loads(line) for line in (Path(left["results"]) / "events.jsonl").read_text().splitlines()]
            self.assertEqual([e["sequence"] for e in events], [1, 2, 3])
            self.assertTrue(all(e["run_id"] == left["run_id"] for e in events))

    def test_host_device_lease_excludes_concurrent_runner(self):
        device = Device("test-device")
        with device.lease():
            with self.assertRaises(LabError):
                with Device("test-device").lease():
                    self.fail("Second runner acquired the device")

    def package(self, root, member="d3d11.dll", link=False):
        path = Path(root) / "test.wcp"
        profile = {"type": "DXVK", "versionName": "2.7.1", "versionCode": 1,
                   "description": "test", "files": [{"source": "d3d11.dll", "target": "${system32}/d3d11.dll"}]}
        with tarfile.open(path, "w:xz") as archive:
            data = json.dumps(profile).encode()
            manifest = tarfile.TarInfo("profile.json")
            manifest.size = len(data)
            archive.addfile(manifest, io.BytesIO(data))
            dll = tarfile.TarInfo(member)
            if link:
                dll.type, dll.linkname = tarfile.SYMTYPE, "/etc/passwd"
                archive.addfile(dll)
            else:
                dll.size = 1
                archive.addfile(dll, io.BytesIO(b"x"))
        return path

    def test_package_hash_manifest_and_payload_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = self.package(tmp)
            self.assertEqual(validate_package(path, "dxvk", "2.7.1", sha256(path))["version_code"], 1)
            for version, digest in (("2.7.2", sha256(path)), ("2.7.1", "0" * 64), ("../escape", sha256(path))):
                with self.assertRaises(LabError):
                    validate_package(path, "dxvk", version, digest)
            for member, link in (("../escape", False), ("d3d11.dll", True), ("missing.dll", False)):
                path = self.package(tmp, member, link)
                with self.assertRaises(LabError):
                    validate_package(path, "dxvk", "2.7.1", sha256(path))

    def test_profile_explicit_false_and_empty_preserved(self):
        root = Path(__file__).resolve().parents[3]
        profile = json.loads((root / "benchmark-profiles/ff7r-sd865.example.json").read_text())
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "profile.json"
            path.write_text(json.dumps(profile))
            with self.assertRaises(LabError):
                load_profile(path)
            for pin in profile["pins"].values():
                pin["source_sha256"] = "a" * 64
            profile["config"].update(showFPS=False, envVars="")
            path.write_text(json.dumps(profile))
            self.assertIs(load_profile(path)["config"]["showFPS"], False)
            self.assertEqual(load_profile(path)["config"]["envVars"], "")
            for bad in ({"showFPS": 0}, {"screenSize": "0x0"}, {"unknown": "value"}):
                invalid = copy.deepcopy(profile)
                invalid["config"] = bad
                path.write_text(json.dumps(invalid))
                with self.assertRaises(LabError):
                    load_profile(path)


if __name__ == "__main__":
    unittest.main()
