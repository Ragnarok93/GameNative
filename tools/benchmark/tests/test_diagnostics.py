import tempfile
import unittest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmark.diagnostics import collect
from benchmark.telemetry import Telemetry
from benchmark.device import LabError


class DiagnosticTests(unittest.TestCase):
    def test_partial_failure_evidence_is_hashed_and_unavailability_explicit(self):
        class Device:
            def call(self, *args, **kwargs):
                if 'find' in args: return ''
                if args[-1].endswith('failure-diagnostics.json'): raise LabError('missing')
                return 'literal diagnostic output'
        with tempfile.TemporaryDirectory() as tmp:
            telemetry = Telemetry(tmp, 'a'*32)
            report = collect(Device(), telemetry, 'a'*32)
            self.assertEqual(len(report['files']), 4)
            self.assertEqual(len(report['unavailable']), 1)
            self.assertEqual((telemetry.directory/'device-processes.txt').read_text(), 'literal diagnostic output')
            self.assertTrue(all(len(f['sha256']) == 64 for f in report['files']))

    def test_invalid_run_refuses_device_access(self):
        with self.assertRaises(LabError): collect(None, None, '../outside')
