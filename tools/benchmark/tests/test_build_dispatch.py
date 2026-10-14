import json
import tempfile
import unittest
from pathlib import Path
from benchmark.build_dispatch import dispatch
from benchmark.device import LabError


class DispatchTests(unittest.TestCase):
    def test_validation_and_dispatch_and_duplicate_refusal(self):
        for execute in (False, True):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                plan = {'schema_version': 1, 'component': 'dxvk', 'repository': 'owner/repo', 'commit': 'a'*40,
                        'ref': 'development', 'workflow': '.github/workflows/build.yml', 'inputs': {'mode': 'debug'}}
                source = root/'plan.json'; source.write_text(json.dumps(plan))
                calls = []
                def command(*args):
                    calls.append(args)
                    if '--method' in args: return b''
                    return json.dumps({'sha': 'a'*40} if '/commits/' in args[1] else {'path': plan['workflow'], 'state': 'active', 'id': 7}).encode()
                result = dispatch(source, root/'ledger.json', execute, command)
                self.assertEqual(result['status'], 'dispatched_uncorrelated' if execute else 'validated')
                self.assertEqual(sum('--method' in c for c in calls), int(execute))
                with self.assertRaises(FileExistsError): dispatch(source, root/'ledger.json', execute, command)

    def test_moved_ref_never_dispatches(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            plan = {'schema_version': 1, 'component': 'fex', 'repository': 'owner/repo', 'commit': 'a'*40,
                    'ref': 'dev', 'workflow': '.github/workflows/build.yml', 'inputs': {}}
            source = root/'plan.json'; source.write_text(json.dumps(plan))
            with self.assertRaises(LabError): dispatch(source, root/'ledger.json', True, lambda *a: b'{"sha":"moved"}')
            self.assertEqual(json.loads((root/'ledger.json').read_text())['status'], 'failed')
