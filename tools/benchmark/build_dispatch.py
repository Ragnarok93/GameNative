"""Explicit, pinned GitHub workflow dispatch with durable uncertainty records."""
import json
import os
import re
from pathlib import Path
from urllib.parse import quote
from .build_set import COMPONENTS, gh
from .device import LabError


def dispatch(manifest_path, output, execute=False, command=gh):
    plan = json.loads(Path(manifest_path).read_text())
    fields = {'schema_version', 'component', 'repository', 'commit', 'ref', 'workflow', 'inputs'}
    if not isinstance(plan, dict) or set(plan) != fields or type(plan['schema_version']) is not int or plan['schema_version'] != 1:
        raise LabError('Dispatch requires an exact schema-version-1 plan')
    if plan['component'] not in COMPONENTS:
        raise LabError('Unknown build component')
    for key, pattern in [('repository', r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+'),
                         ('commit', r'[a-f0-9]{40}'),
                         ('workflow', r'\.github/workflows/[A-Za-z0-9_.-]+\.ya?ml')]:
        if not isinstance(plan[key], str) or not re.fullmatch(pattern, plan[key]):
            raise LabError(f'Invalid {key}')
    if not isinstance(plan['ref'], str) or not plan['ref'] or any(c.isspace() for c in plan['ref']):
        raise LabError('Explicit branch or tag ref required')
    if not isinstance(plan['inputs'], dict) or not all(isinstance(k, str) and re.fullmatch(r'[A-Za-z0-9_-]+', k) and isinstance(v, str) for k, v in plan['inputs'].items()):
        raise LabError('Workflow inputs must be string key/value pairs')
    if plan['component'] == 'gamenative' and plan['workflow'] != '.github/workflows/upgradeable-debug.yml':
        raise LabError('GameNative lab builds require the upgradeable debug workflow')
    target = Path(output).resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    record = {**plan, 'status': 'validating', 'run_id': None,
              'warning': 'Dispatch acceptance is not build success; correlate exact workflow/ref/commit before collecting'}
    # Exclusive creation prevents repeating a potentially accepted dispatch.
    with target.open('x') as stream:
        stream.write(json.dumps(record, indent=2) + '\n'); stream.flush(); os.fsync(stream.fileno())
    def save():
        temporary = target.with_suffix(target.suffix + '.tmp')
        with temporary.open('w') as stream:
            stream.write(json.dumps(record, indent=2) + '\n'); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, target)
    attempted = False
    try:
        base = f"repos/{plan['repository']}"
        head = json.loads(command('api', base + '/commits/' + quote(plan['ref'], safe='')))
        if head.get('sha') != plan['commit']:
            raise LabError('Dispatch ref moved or does not match pinned commit')
        workflow = json.loads(command('api', base + '/actions/workflows/' + quote(Path(plan['workflow']).name, safe='')))
        if workflow.get('path') != plan['workflow'] or workflow.get('state') != 'active':
            raise LabError('Workflow must be active and match exact path')
        record['workflow_id'] = workflow['id']
        record['status'] = 'validated'
        save()
        if execute:
            record['status'] = 'dispatch_pending'
            save()
            attempted = True
            # argv, not shell interpolation; no workflow inputs are executed locally.
            arguments = ['api', '--method', 'POST', base + f"/actions/workflows/{workflow['id']}/dispatches", '-f', 'ref=' + plan['ref']]
            for key, value in sorted(plan['inputs'].items()):
                arguments += ['-f', f'inputs[{key}]={value}']
            command(*arguments)
            record['status'] = 'dispatched_uncorrelated'
            save()
    except Exception as exc:
        record.update(status='dispatch_unknown' if attempted else 'failed', error=str(exc))
        save()
        raise
    return {'report': str(target), **record}
