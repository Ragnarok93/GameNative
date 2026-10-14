import subprocess
import tempfile
import unittest
from benchmark.trace import Trace
from benchmark.telemetry import Telemetry
from benchmark.device import LabError


class TraceTests(unittest.TestCase):
    def test_binary_trace_corruption_is_rejected(self):
        class Fake:
            adb = "adb"
            serial = "SERIAL"
            def call(self, *args, **kwargs):
                if "perfetto" in args: return "1234"
                if "cat" in args: return "/system/bin/perfetto\0-o\0" + trace.remote + "\0"
                if "-0" in args: raise LabError("retired")
                if "sha256sum" in args: return "0"*64 + " remote"
                return ""
            def execute(self, args, **kwargs):
                assert "exec-out" in args
                kwargs["stdout"].write(b"corrupted trace")
                return subprocess.CompletedProcess(args, 0, stderr=b"")
        with tempfile.TemporaryDirectory() as tmp:
            trace = Trace(Fake(), Telemetry(tmp, "c"*32))
            trace.start(1000)
            with self.assertRaisesRegex(LabError, "hash mismatch"):
                trace.finish()
    def test_never_signals_a_reused_unrelated_pid(self):
        class Fake:
            def __init__(self): self.calls=[]
            def call(self,*args,**kwargs):
                self.calls.append(args)
                if "perfetto" in args: return "1234"
                if "cat" in args: return "/system/bin/perfetto\0-o\0/data/misc/perfetto-traces/somebody-else.pftrace\0"
                return ""
        with tempfile.TemporaryDirectory() as tmp:
            device=Fake(); trace=Trace(device,Telemetry(tmp,"a"*32)); trace.start(1000)
            with self.assertRaisesRegex(LabError,"ownership changed"): trace.finish()
            self.assertFalse(any("kill" in call for call in device.calls))

    def test_invalid_background_pid_is_not_accepted(self):
        class Fake:
            def call(self,*args,**kwargs): return "not a PID"
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(LabError): Trace(Fake(),Telemetry(tmp,"b"*32)).start(1000)
