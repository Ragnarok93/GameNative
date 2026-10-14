"""Read-only full DXVK log archival after verified process retirement."""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from .device import LabError

def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()

def archive_log(device,remote,output,*,windows_staging=None,convert=None):
    output=Path(output)
    if output.exists():raise LabError('Archive output already exists')
    if not re.fullmatch(r'/storage/emulated/0/Download/FF7RLogs/(?:ff7remake__(?:dxgi|d3d11)|gpu-[a-f0-9]{32}-[0-9]+-[0-9]+)\.log',remote):
        raise LabError('Only known FF7R diagnostic logs may be archived')
    device.assert_idle_debug(require_bridge=False)
    def remote_hash():
        fields=device.call('shell','sha256sum',remote,timeout=30).split()
        value=fields[0] if fields else ''
        if not re.fullmatch(r'[a-fA-F0-9]{64}',value):raise LabError('Invalid remote log SHA-256')
        return value.lower()
    before=remote_hash()
    native=str(device.adb).lower().endswith('.exe')
    if native:
        staging=windows_staging or os.environ.get('GAMENATIVE_WINDOWS_STAGING')
        if not staging or not Path(staging).is_absolute() or not str(staging).startswith('/mnt/'):
            raise LabError('Native Windows ADB requires explicit GAMENATIVE_WINDOWS_STAGING under /mnt/')
        stage_root=Path(staging);stage_root.mkdir(parents=True,exist_ok=True)
    else:stage_root=output.parent
    stage=Path(tempfile.mkdtemp(prefix='verified-log-',dir=stage_root))/'payload.log'
    if native:
        if convert is None:
            def convert(p):
                q=subprocess.run(['wslpath','-w',str(p)],capture_output=True,text=True,check=True)
                return q.stdout.strip()
        destination=convert(stage)
        if not destination:raise LabError('Empty Windows destination')
    else:destination=str(stage)
    device.call('pull',remote,destination,timeout=120)
    after=remote_hash(); local=digest(stage)
    if before!=after or local!=after:
        raise LabError('Full log archive hash mismatch or remote log changed; staging retained: '+str(stage))
    # Exclusive output creation: neither old evidence nor remote logs are replaced.
    with stage.open('rb') as src, output.open('xb') as dst:shutil.copyfileobj(src,dst)
    if digest(output)!=after:raise LabError('Archive copy hash mismatch')
    return dict(path=output.name,sha256=after,bytes=output.stat().st_size,
        remote_path=remote,remote_sha256_before=before,remote_sha256_after=after,
        staging_path=str(stage),scope='Full current log after UID retirement; may include startup, not measurement-only')
