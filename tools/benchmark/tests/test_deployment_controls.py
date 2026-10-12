import io
import json
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from contextlib import nullcontext
from test_runner import profile
from benchmark.components import sha256
from benchmark.drivers import validate_driver
from benchmark.package_build import package_dxvk
from benchmark.control import command, export_route
from benchmark.recovery_probe import probe
from benchmark.device import LabError


class DeploymentControlTests(unittest.TestCase):
    def driver(self, path, escape=False):
        header = bytearray(64); header[:5] = b"\x7fELF\x02"; header[18:20] = (183).to_bytes(2, "little")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("meta.json", json.dumps({"name":"Lab Driver", "libraryName":"libdriver.so", "driverVersion":"test"}))
            archive.writestr("libdriver.so", header)
            if escape: archive.writestr("../escape", b"unsafe")

    def test_driver_hash_identity_and_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "driver.zip"
            self.driver(path)
            self.assertEqual(validate_driver(path, sha256(path))["name"], "Lab Driver")
            with self.assertRaises(LabError): validate_driver(path, "0"*64)
            self.driver(path, escape=True)
            with self.assertRaises(LabError): validate_driver(path, sha256(path))

    def test_dxvk_package_is_reproducible_and_architecture_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for arch, machine in (("x32",0x14c),("x64",0xa641)):
                (root/arch).mkdir()
                for name in ("d3d8.dll","d3d9.dll","d3d10core.dll","d3d11.dll","dxgi.dll"):
                    data = bytearray(128); data[:2]=b"MZ"; data[60:64]=(64).to_bytes(4,"little")
                    data[64:68]=b"PE\0\0"; data[68:70]=machine.to_bytes(2,"little")
                    (root/arch/name).write_bytes(data)
            first = package_dxvk(root,"test",1,root/"a.wcp")
            second = package_dxvk(root,"test",1,root/"b.wcp")
            self.assertEqual(first["sha256"], second["sha256"])
            (root/"x64/d3d11.dll").write_bytes(b"invalid")
            with self.assertRaises(LabError): package_dxvk(root,"test",1,root/"bad.wcp")

    def test_stale_control_session_cannot_upload_input(self):
        class Fake:
            serial="mock"
            def call(self,*args,**kwargs): return json.dumps({"run_id":"b"*32})
        with self.assertRaisesRegex(LabError,"no longer"):
            command(Fake(), "a"*32, {"type":"key","code":51,"duration_ms":1000})

    def test_route_export_preserves_phase_and_held_key_timing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); value=profile(); value["workload"]["kind"]="exploration"
            (root/"profile.json").write_text(json.dumps(value))
            events=[{"phase":"WARMING_UP","at_ms":1000,"action":{"type":"key","code":66,"duration_ms":0}},
                    {"phase":"MEASURING","at_ms":500,"action":{"type":"look","dx":120,"dy":0,"duration_ms":500}}]
            (root/"device-interactive-input.jsonl").write_text('\n'.join(json.dumps(e) for e in events)+'\n')
            report=export_route(root,root/"route.json")
            route=json.loads((root/"route.json").read_text())
            self.assertEqual(route["setup_actions"][0]["code"],66)
            self.assertEqual(route["actions"][0]["at_ms"],500)
            self.assertTrue(report["replay_validation_required"])

    def test_recovery_probe_requires_real_mutation_and_exact_restore(self):
        class Fake:
            def lease(self): return nullcontext()
            def call(self,*args): return "b"*64+" config"
        def execute(device, request, results):
            operation=request["operation"]
            if operation=="inspect": return {"result":{"config_sha256":"a"*64,"config_path":"config","config":{"showFPS":False}}}
            if operation=="config-roundtrip": raise LabError("process died")
            if operation=="list": raise LabError("Pending recovery must complete first")
            return {"run_id":"c"*32,"result":{"recovered":True}}
        with patch("benchmark.recovery_probe.execute",side_effect=execute):
            self.assertTrue(probe(Fake(),"BENCHMARK_TEST","ignored")["exact_restore_verified"])
        with self.assertRaises(LabError): probe(Fake(),"CUSTOM_GAME_1","ignored")
