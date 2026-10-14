"""Verified debug API transactions. Never fall back to GUI or claim import means testing."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid
from .device import LabError,PACKAGE
from .components import validate_package,sha256
from .orchestrator import execute
from .live import command_ledger

def event(root,stage,**fields):
    with (Path(root)/'component-lifecycle.jsonl').open('a') as f:
        f.write(json.dumps(dict(stage=stage,time_unix=time.time(),**fields))+'\n')
def pin(verification):
    doc=json.loads(Path(verification).read_text());v=doc['validated'];source=doc['source_commit']
    if not re.fullmatch('[a-f0-9]{40}',source):raise LabError('Exact source commit required')
    keys=['x64/d3d11.dll','x64/dxgi.dll','x32/d3d11.dll','x32/dxgi.dll']
    hashes={k:doc['payload_sha256'][k] for k in keys}
    if v['type']!='DXVK' or any(not re.fullmatch('[a-f0-9]{64}',s) for s in hashes.values()):raise LabError('Verified DXVK payload pin required')
    return dict(type='DXVK',version=v['version'],version_code=v['version_code'],source_commit=source,payload_sha256=hashes),v

def require_api(device):
    try:
        result=execute(device,{'operation':'capabilities'},'/tmp/gamenative-capability-check')['result']
    except LabError as exc:
        raise LabError('Direct control requires the API-v2 debug APK pair; no GUI fallback. '+str(exc)) from exc
    if result.get('api_revision',0)<2 or result.get('direct_existing_dxvk') is not True:
        raise LabError('Install the matching API-v2 debug APK pair. GUI fallback is disabled.')
    return result

def ensure_imported(device,verification,root):
    root=Path(root);root.mkdir(parents=True,exist_ok=False);identity,package=pin(verification)
    validate_package(package['path'],'dxvk',identity['version'],package['sha256'])
    with device.lease(), command_ledger(device,root):
        require_api(device)
        inspection=execute(device,{'operation':'component-inspect',**identity},root/'inspect')['result']
        if inspection['installed']:
            event(root,'IMPORTED_VERIFIED',component=identity,already_installed=True,attestation=inspection)
            return dict(already_installed=True,component=identity,attestation=inspection)
        token=uuid.uuid4().hex;staged='cache/lab-component-'+token+'.wcp'
        if str(device.adb).lower().endswith('.exe'):
            base=os.environ.get('GAMENATIVE_WINDOWS_STAGING')
            if not base or not str(base).startswith('/mnt/'):raise LabError('Native Windows staging path required')
            local=Path(base)/('component-'+token+'.wcp');local.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(package['path'],local)
            destination=subprocess.check_output(['wslpath','-w',str(local)],text=True).strip()
        else:destination=package['path']
        remote='/storage/emulated/0/Android/data/'+PACKAGE+'/files/component-'+token+'.wcp'
        device.call('push',destination,remote,timeout=120)
        if device.call('shell','sha256sum',remote).split()[0]!=package['sha256']:raise LabError('Device staging hash mismatch')
        device.call('shell','run-as',PACKAGE,'cp',remote,staged)
        if device.call('shell','run-as',PACKAGE,'sha256sum',staged).split()[0]!=package['sha256']:raise LabError('Private staging hash mismatch')
        execute(device,{'operation':'install','package':staged,**package},root/'install')
        inspection=execute(device,{'operation':'component-inspect',**identity},root/'readback')['result']
        if not inspection['installed']:raise LabError('Installed component not verified')
        event(root,'IMPORTED_VERIFIED',component=identity,already_installed=False,attestation=inspection)
        return dict(already_installed=False,component=identity,attestation=inspection)

def select(device,verification,root,*,allow_existing=False,render_passes=True):
    if allow_existing is not True:raise LabError('Explicit existing-container authorization required')
    root=Path(root);root.mkdir(parents=True,exist_ok=False);identity,_=pin(verification)
    with device.lease(), command_ledger(device,root):
        require_api(device)
        before=execute(device,{'operation':'existing-dxvk-inspect','container_id':'CUSTOM_GAME_227938910'},root/'before')['result']
        result=execute(device,{'operation':'existing-dxvk-select','container_id':'CUSTOM_GAME_227938910',
            'allow_existing_container':True,'expected_config_sha256':before['config_sha256'],
            'expected_engine_sha256':before['engine_sha256'],'component':identity,'render_passes':render_passes},root/'selection')['result']
        (root/'selection.json').write_text(json.dumps(result,indent=2))
        event(root,'SELECTED_VERIFIED',component=identity,selection_run_id=result['selection_run_id'],runtime_loaded=False,gameplay_measured=False)
        return result

def restore(device,selection,root):
    selection=json.loads(Path(selection).read_text());root=Path(root);root.mkdir(parents=True,exist_ok=False)
    with device.lease(), command_ledger(device,root):
        require_api(device)
        result=execute(device,{'operation':'existing-dxvk-restore','container_id':'CUSTOM_GAME_227938910',
            'allow_existing_container':True,'selection_run_id':selection['selection_run_id']},root)['result']
        event(root,'SELECTION_RESTORED',runtime_dll_restore_verified=False)
        return result

def verify_loaded(device,guest_pid,identity,container):
    maps=device.call('shell','run-as',PACKAGE,'cat','/proc/'+str(guest_pid)+'/maps')
    base='/data/user/0/'+PACKAGE+'/files/imagefs_shared/home/xuser-'+container+'/.wine/drive_c/windows/system32/'
    result={}
    for dll in ['d3d11.dll','dxgi.dll']:
        path=base+dll
        if not any(line.split(maxsplit=5)[-1]==path for line in maps.splitlines()):raise LabError('Expected DLL is not mapped by guest: '+dll)
        actual=device.call('shell','run-as',PACKAGE,'sha256sum',path).split()[0]
        if actual!=identity['payload_sha256']['x64/'+dll]:raise LabError('Mapped runtime DLL does not match selected build: '+dll)
        result[dll]=actual
    return dict(source_commit=identity['source_commit'],version=identity['version'],mapped_dll_sha256=result,runtime_loaded_verified=True)
