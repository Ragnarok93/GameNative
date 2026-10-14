from pathlib import Path
import hashlib
import tempfile
import unittest
from unittest.mock import Mock
from tools.benchmark.log_archive import archive_log
from tools.benchmark.device import LabError, Device
REMOTE='/storage/emulated/0/Download/FF7RLogs/ff7remake__d3d11.log'
class ArchiveTest(unittest.TestCase):
    def device(self,data,bad=False):
        d=Mock(spec=Device);d.adb='adb'; h=hashlib.sha256(data).hexdigest();n=0
        def call(*args,**kwargs):
            nonlocal n
            if args[0]=='pull':Path(args[2]).write_bytes(data);return '1 file pulled'
            n+=1;return ('0'*64 if bad and n==2 else h)+'  '+REMOTE
        d.call.side_effect=call;return d
    def test_full_bytes_remote_hash_and_idle(self):
        data=b'a'*2097153+b'\x00\xff\r\n'
        with tempfile.TemporaryDirectory() as p:
            d=self.device(data);o=Path(p)/'full.log';r=archive_log(d,REMOTE,o)
            self.assertEqual(o.read_bytes(),data);self.assertEqual(r['bytes'],len(data))
            d.assert_idle_debug.assert_called_once_with(require_bridge=False)
            with self.assertRaises(LabError):archive_log(d,REMOTE,o)
    def test_mutating_remote_never_publishes_archive(self):
        with tempfile.TemporaryDirectory() as p:
            o=Path(p)/'full.log'
            with self.assertRaises(LabError):archive_log(self.device(b'data',bad=True),REMOTE,o)
            self.assertFalse(o.exists())
    def test_idle_refusal_prevents_pull(self):
        d=self.device(b'data');d.assert_idle_debug.side_effect=LabError('active guest')
        with tempfile.TemporaryDirectory() as p:
            with self.assertRaises(LabError):archive_log(d,REMOTE,Path(p)/'full.log')
        d.call.assert_not_called()
    def test_native_requires_explicit_staging(self):
        d=self.device(b'data');d.adb='/mnt/c/adb.exe'
        with tempfile.TemporaryDirectory() as p:
            with self.assertRaises(LabError):archive_log(d,REMOTE,Path(p)/'full.log',windows_staging='/tmp/no')
        self.assertFalse(any(c.args[0]=='pull' for c in d.call.call_args_list))

    def test_run_scoped_gpu_trace_only(self):
        remote='/storage/emulated/0/Download/FF7RLogs/gpu-'+('a'*32)+'-123-0.log'
        with tempfile.TemporaryDirectory() as td:
            result=archive_log(self.device(b'gpu timing'),remote,Path(td)/'trace.log')
            self.assertEqual(result['bytes'],10)
            for invalid in [remote.replace('a'*32,'../escape'),remote.replace('-123-0','-%p-%i'),remote.replace('-123-0','-other')]:
                with self.assertRaises(LabError):archive_log(self.device(b'x'),invalid,Path(td)/'bad.log')
