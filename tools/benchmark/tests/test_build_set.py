import json
import tempfile
import unittest
from pathlib import Path
from benchmark.build_set import COMPONENTS, resolve, validate
from benchmark.device import LabError


class BuildSetTests(unittest.TestCase):
    def manifest(self):
        return {'schema_version': 1, 'components': {name: {
            'repository': 'owner/repo', 'commit': 'a' * 40,
            'workflow': '.github/workflows/build.yml', 'run_id': 123,
            'artifact': 'package', 'build_inputs': {'compiler': 'declared-only'}
        } for name in COMPONENTS}}

    def command(self, *args):
        if args[0] == 'run':
            directory = Path(args[-1]); directory.mkdir()
            (directory / 'payload').write_bytes(b'fixture')
            return b''
        if 'artifacts?' in args[1]:
            return json.dumps({'total_count': 1, 'artifacts': [{'id': 1, 'name': 'package', 'expired': False}]}).encode()
        return json.dumps({'head_sha': 'a'*40, 'path': '.github/workflows/build.yml', 'status': 'completed', 'conclusion': 'success'}).encode()

    def test_downloads_all_components_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp)/'manifest.json'; manifest.write_text(json.dumps(self.manifest()))
            output = Path(tmp)/'evidence'
            result = resolve(manifest, output, True, self.command)
            self.assertEqual(result['status'], 'downloaded')
            self.assertEqual(set(result['components']), COMPONENTS)
            self.assertTrue(all(len(e['files'][0]['sha256']) == 64 for e in result['components'].values()))
            with self.assertRaises(LabError): resolve(manifest, output, command=self.command)

    def test_rejects_missing_component_and_unpinned_run(self):
        manifest = self.manifest(); del manifest['components']['proton']
        with self.assertRaises(LabError): validate(manifest)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'manifest.json'; path.write_text(json.dumps(self.manifest()))
            with self.assertRaises(LabError):
                resolve(path, Path(tmp)/'out', command=lambda *a: b'{"status":"completed","conclusion":"failure"}')
            self.assertEqual(json.loads((Path(tmp)/'out/build-set.json').read_text())['status'], 'failed')
