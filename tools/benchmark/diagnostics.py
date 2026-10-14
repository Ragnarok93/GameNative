"""Best-effort failure evidence: never upgrades incomplete runs to benchmarks."""
import hashlib
import re
import shlex
from datetime import datetime, timezone
from .device import LabError, PACKAGE


def collect(device, telemetry, run_id):
    if not re.fullmatch(r'[a-f0-9]{32}', run_id):
        raise LabError('Invalid diagnostic run ID')
    root = f'files/benchmark-lab/{run_id}'
    requests = {
        'device-final-status.json': ('shell', 'run-as', PACKAGE, 'cat', root+'/status.json'),
        'device-events.jsonl': ('shell', 'run-as', PACKAGE, 'cat', root+'/events.jsonl'),
        'device-failure-diagnostics.json': ('shell', 'run-as', PACKAGE, 'cat', root+'/failure-diagnostics.json'),
        'device-processes.txt': ('shell', 'ps', '-A', '-o', 'UID,PID,PPID,NAME'),
        'device-thermal.txt': ('shell', 'dumpsys', 'thermalservice'),
    }
    manifest = {'run_id': run_id, 'collection_utc': datetime.now(timezone.utc).isoformat(),
                'files': [], 'unavailable': [],
                'scope': 'Diagnostic evidence only; not successful benchmark or source-game FPS'}
    manifest['process_scope'] = 'Host-collected process/thermal snapshots reflect collection time, not necessarily trial time'
    for name, args in requests.items():
        try:
            value = device.call(*args, timeout=15)
            data = value.encode('utf-8')
            clipped = len(data) > 2*1024*1024
            data = data[:2*1024*1024]
            (telemetry.directory/name).write_bytes(data)
            manifest['files'].append({'path': name, 'bytes': len(data),
                'sha256': hashlib.sha256(data).hexdigest(), 'truncated': clipped})
        except (LabError, OSError) as exc:
            manifest['unavailable'].append({'path': name, 'error': str(exc)})
    try:
        logs = device.call('shell', 'run-as', PACKAGE, 'find', root+'/dxvk', '-type', 'f', timeout=15).splitlines()
        for remote in logs[:16]:
            filename = remote.removeprefix(root+'/dxvk/')
            if not remote.startswith(root+'/dxvk/') or not re.fullmatch(r'[A-Za-z0-9_.-]+\.log', filename):
                raise LabError('Invalid diagnostic log path')
            value = device.call('shell', 'run-as', PACKAGE, 'tail', '-c', '2097152', shlex.quote(remote), timeout=15)
            data = value.encode('utf-8')
            output = telemetry.directory/'failure-dxvk'/filename
            output.parent.mkdir(exist_ok=True)
            output.write_bytes(data)
            manifest['files'].append({'path': str(output.relative_to(telemetry.directory)),
                'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                'scope': 'last at most 2 MiB; not guaranteed complete'})
    except (LabError, OSError) as exc:
        manifest['unavailable'].append({'path': 'failure-dxvk', 'error': str(exc)})
    telemetry.artifact('diagnostic-manifest.json', manifest)
    return manifest
