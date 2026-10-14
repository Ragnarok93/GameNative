"""Resolve a complete, pinned component build set without touching a device."""
import hashlib
import json
import re
import subprocess
from pathlib import Path
from .device import LabError

COMPONENTS = {'gamenative', 'dxvk', 'fex', 'turnip', 'proton', 'lsfg'}


def validate(manifest):
    if not isinstance(manifest, dict) or set(manifest) != {'schema_version', 'components'} or manifest['schema_version'] != 1:
        raise LabError('Build set requires schema_version 1 and components')
    entries = manifest['components']
    if not isinstance(entries, dict) or set(entries) != COMPONENTS:
        raise LabError('Build set must explicitly pin all six components')
    for name, entry in entries.items():
        if not isinstance(entry, dict) or set(entry) != {'repository', 'commit', 'workflow', 'run_id', 'artifact', 'build_inputs'}:
            raise LabError(f'{name}: incomplete build identity')
        if not isinstance(entry['repository'], str) or not isinstance(entry['commit'], str) or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', entry['repository']) or not re.fullmatch(r'[a-f0-9]{40}', entry['commit']):
            raise LabError(f'{name}: explicit repository/full commit required')
        if type(entry['run_id']) is not int or entry['run_id'] <= 0:
            raise LabError(f'{name}: positive run ID required')
        if not isinstance(entry['workflow'], str) or not re.fullmatch(r'\.github/workflows/[A-Za-z0-9_.-]+\.ya?ml', entry['workflow']):
            raise LabError(f'{name}: exact workflow path required')
        if not isinstance(entry['artifact'], str) or not entry['artifact'].strip():
            raise LabError(f'{name}: exact artifact name required')
        if not isinstance(entry['build_inputs'], dict) or not entry['build_inputs']:
            raise LabError(f'{name}: compiler/dependency inputs must be declared')
    return manifest


def gh(*args):
    try:
        result = subprocess.run(['gh', *args], capture_output=True, timeout=900, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise LabError(f'Build-set GitHub operation failed: {exc}') from exc
    if result.returncode:
        raise LabError(result.stderr.decode(errors='replace') or 'GitHub command failed')
    return result.stdout


def resolve(manifest_path, output, download=False, command=gh):
    manifest = validate(json.loads(Path(manifest_path).read_text()))
    root = Path(output).resolve()
    if root.exists():
        raise LabError('Refusing to overwrite build-set evidence; choose a fresh output directory')
    root.mkdir(parents=True)
    report = {'schema_version': 1, 'status': 'resolving', 'components': {},
              'compatibility': 'not established by build success',
              'provenance_scope': 'CI identity and artifact bytes; declared build_inputs are not independently attested'}
    ledger = root / 'build-set.json'
    def save():
        ledger.write_text(json.dumps(report, indent=2) + '\n')
    save()
    try:
        for name in sorted(COMPONENTS):
            entry = manifest['components'][name]
            base = f"repos/{entry['repository']}/actions/runs/{entry['run_id']}"
            run = json.loads(command('api', base))
            if run.get('head_sha') != entry['commit'] or run.get('status') != 'completed' or run.get('conclusion') != 'success' or run.get('path') != entry['workflow']:
                raise LabError(f'{name}: run must succeed at exact commit and workflow')
            artifacts = json.loads(command('api', base + '/artifacts?per_page=100'))
            # Refuse truncated inventories rather than selecting an ambiguous item.
            if artifacts.get('total_count') != len(artifacts.get('artifacts', [])):
                raise LabError(f'{name}: artifact inventory requires pagination')
            found = [a for a in artifacts['artifacts'] if a['name'] == entry['artifact'] and not a['expired']]
            if len(found) != 1:
                raise LabError(f'{name}: exactly one unexpired artifact required')
            record = {**entry, 'artifact_id': found[0]['id'], 'run_url': run.get('html_url'), 'files': []}
            report['components'][name] = record
            save()
            if download:
                directory = root / name
                command('run', 'download', str(entry['run_id']), '--repo', entry['repository'], '--name', entry['artifact'], '--dir', str(directory))
                for path in sorted(directory.rglob('*')):
                    if path.is_symlink():
                        raise LabError(f'{name}: artifact contains a symlink')
                    if path.is_file():
                        with path.open('rb') as stream:
                            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                        record['files'].append({'path': str(path.relative_to(root)), 'bytes': path.stat().st_size, 'sha256': digest})
                if not record['files']:
                    raise LabError(f'{name}: downloaded artifact is empty')
                save()
        report['status'] = 'downloaded' if download else 'resolved'
        save()
    except Exception as exc:
        report.update(status='failed', error=str(exc))
        save()
        raise
    return {'report': str(ledger), **report}
