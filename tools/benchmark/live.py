"""Existing-container diagnostic navigation; never a promotion benchmark."""
import hashlib
import json
import re
import shlex
import subprocess
import time
import uuid
from pathlib import Path
from contextlib import contextmanager
from datetime import datetime, timezone
from .device import Device, LabError, PACKAGE
from .navigation import execute_session
from .telemetry import Telemetry
from .collectors import Collectors
from .guest_cpu import summarize_cpu
from .log_archive import archive_log

LIVE_LAYER_PATTERN = r'^SurfaceView\[app\.gamenative/app\.gamenative\.MainActivity\]@\d+\(BLAST\)#\d+$'


@contextmanager
def command_ledger(device, root):
    original=device.execute
    def execute(*args, **kwargs):
        record={'utc':datetime.now(timezone.utc).isoformat(),'command':args[0]}
        try:
            result=original(*args,**kwargs)
            record.update(exit_status=result.returncode,
                stdout=(result.stdout or b'').decode(errors='replace') if isinstance(result.stdout,(bytes,type(None))) else str(result.stdout),
                stderr=(result.stderr or b'').decode(errors='replace') if isinstance(result.stderr,(bytes,type(None))) else str(result.stderr))
            return result
        except (OSError, subprocess.TimeoutExpired) as exc:
            record.update(error=str(exc),exit_status=None)
            raise
        finally:
            with (root/'commands.jsonl').open('a') as f:
                f.write(json.dumps(record)+'\n')
    device.execute=execute
    try:yield
    finally:device.execute=original


def validate_route(value):
    if not isinstance(value, dict) or set(value) != {'schema_version', 'description', 'actions'} or value['schema_version'] != 1:
        raise LabError('Invalid live route schema')
    if not isinstance(value['description'], str) or not value['description'].strip():
        raise LabError('Route description required')
    actions=value['actions']
    if not isinstance(actions,list) or not 0 <= len(actions) <= 128:
        raise LabError('Route requires 1..128 actions')
    for a in actions:
        if not isinstance(a,dict) or set(a) != {'keys','hold_ms'}:
            raise LabError('Invalid live action')
        if (not isinstance(a['keys'],list) or len(a['keys']) != 2 or len(set(a['keys'])) != 2
            or any(type(k) is not int or k not in {29,32,47,51,59} for k in a['keys'])
            or type(a['hold_ms']) is not int or not 1 <= a['hold_ms'] <= 10000):
            raise LabError('Only bounded WASD/Shift combinations allowed')
    return [(a['keys'],a['hold_ms']) for a in actions]


def game_pid(device, uid=None):
    raw=device.call('shell','ps','-A','-o','UID,PID,NAME',timeout=5)
    if uid is None:uid=device.call('shell','run-as',PACKAGE,'id','-u',timeout=5)
    values=[]
    for line in raw.splitlines():
        f=line.split(maxsplit=2)
        if len(f)==3 and f[0]==uid and f[2].lower().endswith('ff7remake_.exe'):
            values.append(f[1])
    if len(values)!=1 or not values[0].isdigit():
        raise LabError('Expected exactly one FF7R guest; no gameplay inference from process presence')
    return values[0]


def attest(device,container,check_alias=True):
    if not re.fullmatch(r'CUSTOM_GAME_[0-9]+',container):
        raise LabError('Existing custom-game container required')
    base='files/imagefs_shared/home/xuser-'+container
    config=device.call('shell','run-as',PACKAGE,'cat',base+'/.container',timeout=5)
    engine=base+'/.wine/drive_c/users/xuser/Documents/My Games/FINAL FANTASY VII REMAKE/Saved/Config/WindowsNoEditor/Engine.ini'
    engine_hash=device.call('shell','run-as',PACKAGE,'sha256sum',shlex.quote(engine),timeout=5).split()[0]
    alias=device.call('shell','run-as',PACKAGE,'readlink','files/imagefs/home/xuser',timeout=5)
    if check_alias and alias != './xuser-'+container:
        raise LabError('Active home alias does not identify requested existing container')
    return {'container':container,'config':json.loads(config),
            'config_sha256':device.call('shell','run-as',PACKAGE,'sha256sum',base+'/.container',timeout=5).split()[0],
            'engine_ini_sha256':engine_hash,'active_alias':alias}


def capture(device,root,name):
    remote='/data/local/tmp/gamenative-live-'+root.name+'-'+name+'.png'
    device.call('shell','screencap','-p',remote,timeout=8)
    out=root/(name+'.png')
    if device.adb.lower().endswith('.exe'):
        # Native adb cannot write a WSL Linux path. Use mounted Windows storage.
        host=Path('/mnt/c/Users/Admin/GameNative-lab')/(root.name+'-'+name+'.png')
        target='C:/Users/Admin/GameNative-lab/'+host.name
    else:
        host=out;target=str(out)
    device.call('pull',remote,target,timeout=15)
    data=host.read_bytes()
    if not data.startswith(b'\x89PNG\r\n\x1a\n') or not data.endswith(b'\0\0\0\0IEND\xaeB`\x82'):
        raise LabError('Invalid screenshot evidence')
    expected=device.call('shell','sha256sum',remote,timeout=5).split()[0]
    if hashlib.sha256(data).hexdigest()!=expected:raise LabError('Screenshot hash mismatch')
    out.write_bytes(data)
    return str(out)


class IncrementalFatalLog:
    """Scan every newly appended byte once; retain the complete on-disk log."""
    def __init__(self, path):
        self.path=Path(path);self.offset=0;self.carry=b'';self.identity=None;self.bytes_read=0
    def poll(self):
        with self.path.open('rb') as f:
            stat=self.path.stat();identity=(stat.st_dev,stat.st_ino)
            if self.identity!=identity or stat.st_size<self.offset:
                self.offset=0;self.carry=b''
            self.identity=identity;f.seek(self.offset)
            while True:
                chunk=f.read(65536)
                if not chunk:break
                self.bytes_read+=len(chunk);self.offset+=len(chunk)
                text=self.carry+chunk
                if b'VK_ERROR_DEVICE_LOST' in text or b'Fatal signal' in text:return True
                self.carry=text[-32:]
        return False


def hash_file(path):
    value=hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda:f.read(1048576),b''):value.update(chunk)
    return value.hexdigest()


def analyze(directory):
    root=Path(directory)
    result=json.loads((root/'result.json').read_text())
    diag=json.loads((root/'diagnostic-manifest.json').read_text())
    available={f['path'] for f in diag['files']}
    log_sources=[c+'.full.log' if c+'.full.log' in available else c+'.log'
        for c in ('dxgi','d3d11') if c+'.full.log' in available or c+'.log' in available]
    windows=[];queue={};device_lost=0
    # One streaming pass: detailed logs remain intact, without materializing
    # hundreds of MB repeatedly or scanning them separately for each label.
    for name in log_sources:
        with (root/name).open(errors='replace') as source:
            for line in source:
                device_lost+=line.count('VK_ERROR_DEVICE_LOST')
                if 'DXVK-FF7R-FRAMES:' in line:
                    windows.append(dict((k,int(v)) for k,v in re.findall(r'(ts_us|mean_us|p95_us|p99_us|max_us)=(\d+)',line)))
                for label in ('GPU-WAIT','QUEUE-SUBMIT','ENQUEUE','QUEUE-PRESENT'):
                    if 'DXVK-FF7R-'+label+':' in line:
                        queue[label]=dict((k,int(v)) for k,v in re.findall(r'(samples|mean_us|max_us|over_10ms|over_33ms)=(\d+)',line))
    samples=[json.loads(l) for l in (root/'events.jsonl').read_text().splitlines()]
    presentation=json.loads((root/'presentation.json').read_text()) if (root/'presentation.json').exists() else {'unavailable':'No presentation collector result'}
    thermals=[e['thermal_status'] for e in samples if 'thermal_status' in e]
    cpu=[e['system_cpu_busy_percent'] for e in samples if 'system_cpu_busy_percent' in e]
    gpu=[]
    for e in samples:
        raw=e.get('gpu_busy_raw','').split()
        if len(raw)==2 and all(v.isdigit() for v in raw) and 0<int(raw[1]) and int(raw[0])<=int(raw[1]):
            gpu.append(100*int(raw[0])/int(raw[1]))
    report={'status':result['status'],'regression_eligible':False,
        'scope':'Existing-prefix exploratory sample, not source-game FPS or controlled A/B',
        'device_lost_occurrences':device_lost,
        'dxvk_window_summaries':windows,'thermal_statuses':thermals,
        'dxvk_queue_cumulative':queue,
        'system_cpu_busy_percent_samples':cpu,
        'guest_process_cpu':summarize_cpu(samples),
        'gpu_busy_percent_samples':gpu,
        'system_cpu_scope':'Whole-device proc/stat deltas, not game-only CPU utilization',
        'source_game_fps':{'available':False,'reason':'No independently instrumented source presents'},
        'log_sources':log_sources,
        'log_window_scope':'DXVK files include startup; summaries not guaranteed measurement-only',
        'unavailable':diag['unavailable'],
        'presentation_coverage':presentation,
        'fex_profiling':{'available':False,'reason':'No translated-code profiler captured; process stat/wchan are not FEX execution counters'},
        'turnip_fault_dump':{'available':False,'reason':'Unprivileged driver faults may be absent from logcat'},
        'opportunities':['Device loss: controlled DXVK/Turnip/LSFG isolation before tuning',
            'GPU wait vs submit: inspect GPU workload and sync, never remove waits blindly',
            'Capture per-process CPU counters; frequency-derived CPU labels are not utilization',
            'Repeat validated route with frozen settings and independent presentation timing'],
        'automatic_promotion':False}
    encoded=json.dumps(report,indent=2)+'\n'
    name='analysis'
    if (root/'analysis.json').exists() and (root/'analysis.json').read_text()!=encoded:
        name='analysis-revision-'+hashlib.sha256(encoded.encode()).hexdigest()[:16]
    (root/(name+'.json')).write_text(encoded)
    markdown='# Live diagnostic analysis\n\nStatus: '+str(result['status'])+'; regression eligible: false.\n\n'
    markdown+='Device-loss occurrences in collected DXVK files: '+str(report['device_lost_occurrences'])+' (not unique crash count).\n\n'
    markdown+='Cumulative DXVK queue records (not per-frame GPU durations):\n```json\n'+json.dumps(queue,indent=2)+'\n```\n\n'
    markdown+='Thermal statuses: '+str(thermals)+'. GPU busy mean: '+(str(round(sum(gpu)/len(gpu),2))+'%' if gpu else 'unavailable')+'.\n\n'
    markdown+='Whole-device CPU busy mean: '+(str(round(sum(cpu)/len(cpu),2))+'%' if cpu else 'unavailable')+'; not game-only utilization.\n\n'
    markdown+='Presentation/frequency collector limitations: '+json.dumps(presentation.get('unavailable',{}))+'\n\n'
    markdown+='Guest CPU: '+json.dumps(report['guest_process_cpu'])+'\n\n'
    markdown+='Source FPS, complete FEX profiling, unique driver GPU faults and route-progress validation are unavailable. DXVK logs may include startup; no aggregate percentiles from window summaries.\n\n'
    markdown+='## Next controlled experiments\n'+''.join('- '+x+'\n' for x in report['opportunities'])
    (root/(name+'.md')).write_text(markdown)
    if name!='analysis':
        (root/(name+'.hashes.json')).write_text(json.dumps({name+'.json':hashlib.sha256(encoded.encode()).hexdigest(),name+'.md':hashlib.sha256(markdown.encode()).hexdigest()},indent=2)+'\n')
    return report


def _run_live(device,container,route_path,results,*,duration=300,confirmed=False,allow_close=False,component_verification=None):
    route=json.loads(Path(route_path).read_text()); actions=validate_route(route)
    if not confirmed or not allow_close:
        raise LabError('Requires visually confirmed gameplay and explicit permission to close the current game')
    if type(duration) is not int or not 1<=duration<=300:
        raise LabError('Duration must be 1..300 seconds')
    with device.lease():
        initial=attest(device,container); uid=device.call('shell','run-as',PACKAGE,'id','-u',timeout=5);pid=game_pid(device,uid)
        t=Telemetry(results,uuid.uuid4().hex)
        loaded=None
        if component_verification:
            from .direct_control import pin,verify_loaded
            identity,_=pin(component_verification)
            loaded=verify_loaded(device,pid,identity,container)
            t.artifact('runtime-component.json',loaded)
            t.event('LOADED_VERIFIED',**loaded)
        from .direct_control import verify_gpu_environment
        gpu_environment=verify_gpu_environment(device,pid,initial['config'])
        if gpu_environment:t.artifact('gpu-runtime-environment.json',gpu_environment)
        t.artifact('before.json',initial); t.artifact('device.json',device.info());t.artifact('route.json',route)
        t.artifact('request.json',{'serial':device.serial,'container':container,'duration_s':duration,
            'guest_pid':pid,'initial_gameplay_visually_confirmed':True,'subsequent_scene_validation':'unavailable',
            'route_progress_validation':'unavailable','save_policy':'Existing prefix may autosave',
            'config_mutation':False,'regression_eligible':False})
        logger_out=(t.directory/'logcat.txt').open('wb'); logger_err=(t.directory/'logcat-stderr.txt').open('wb')
        logger=None
        fatal_log=IncrementalFatalLog(t.directory/'logcat.txt')
        collectors=Collectors(device,t,{'layer_pattern':LIVE_LAYER_PATTERN,'sample_interval_ms':1000})
        manifest={'files':[],'unavailable':[]}
        def save(name,value):
            data=value.encode();(t.directory/name).write_bytes(data)
            manifest['files'].append({'path':name,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'scope':'bounded tail or snapshot; timestamps must be checked'})
        def collect():
            requests={
                'dxgi.log':('shell','tail','-c','2097152','/storage/emulated/0/Download/FF7RLogs/ff7remake__dxgi.log'),
                'd3d11.log':('shell','tail','-c','2097152','/storage/emulated/0/Download/FF7RLogs/ff7remake__d3d11.log'),
                'crash.log':('logcat','-b','crash','-d'),
                'kernel.txt':('shell','dmesg'),
                'package.txt':('shell','dumpsys','package',PACKAGE),
                'processes.txt':('shell','ps','-A','-o','UID,PID,PPID,NAME'),
                'guest-stat.txt':('shell','run-as',PACKAGE,'cat','/proc/'+pid+'/stat'),
                'guest-wchan.txt':('shell','run-as',PACKAGE,'cat','/proc/'+pid+'/wchan'),
                'guest-maps.txt':('shell','run-as',PACKAGE,'cat','/proc/'+pid+'/maps'),
                'guest-status.txt':('shell','run-as',PACKAGE,'cat','/proc/'+pid+'/status'),
                'guest-threads.txt':('shell','run-as',PACKAGE,'sh','-c',shlex.quote(
                    'n=0; for p in /proc/'+pid+'/task/*/stat; do [ "$n" -ge 32 ] && break; cat "$p"; n=$((n+1)); done'))}
            for n,args in requests.items():
                try: save(n,device.call(*args,timeout=8))
                except (LabError,OSError) as e: manifest['unavailable'].append({'path':n,'error':str(e)})
            try:
                power_root='/storage/emulated/0/Android/data/'+PACKAGE+'/files/powercontrol/'
                names=device.call('shell','ls','-t',power_root,timeout=5).splitlines()
                latest=next((n for n in names if re.fullmatch(r'metrics-[0-9]+\.jsonl',n)),None)
                if latest is None:raise LabError('No GameNative power metrics file')
                save('power-metrics.jsonl',device.call('shell','tail','-c','1048576',power_root+latest,timeout=8))
            except LabError as exc:manifest['unavailable'].append({'path':'power-metrics.jsonl','error':str(exc)})
            if measurement_offset is not None:
                try:save('measurement-dxgi.log',device.call('shell','tail','-c','+'+str(measurement_offset+1),'/storage/emulated/0/Download/FF7RLogs/ff7remake__dxgi.log',timeout=8))
                except LabError as exc:manifest['unavailable'].append({'path':'measurement-dxgi.log','error':str(exc)})
            runtime={'hashes':[], 'unavailable':[], 'scope':'Mapped paths at collection, not complete guest DLL attestation'}
            mapped=t.directory/'guest-maps.txt'
            if mapped.exists():
                paths=set()
                for line in mapped.read_text().splitlines():
                    fields=line.split(maxsplit=5)
                    if len(fields)==6 and fields[5].startswith('/') and re.search(r'fex|dxgi|d3d11|vulkan|lsfg',fields[5],re.I) and not fields[5].endswith(' (deleted)'):
                        paths.add(fields[5])
                for remote in sorted(paths)[:16]:
                    try:
                        value=device.call('shell','run-as',PACKAGE,'sha256sum',shlex.quote(remote),timeout=5)
                        runtime['hashes'].append({'path':remote,'sha256':value.split()[0]})
                    except LabError as exc:runtime['unavailable'].append({'path':remote,'error':str(exc)})
                runtime['truncated']=len(paths)>16
            else:runtime['unavailable'].append({'path':'guest-maps','error':'Process maps unavailable'})
            t.artifact('runtime-binary-hashes.json',runtime)
            collectors.finish()
            t.artifact('diagnostic-manifest.json',manifest)
            return manifest
        count=0
        last_sample=0.0
        measurement_offset=None
        def observe():
            nonlocal count, logger, last_sample, measurement_offset
            if count==0:
                logger=subprocess.Popen([device.adb,'-s',device.serial,'logcat','-v','threadtime','-T','1'],stdout=logger_out,stderr=logger_err)
                capture(device,t.directory,'initial-gameplay')
                measurement_offset=int(device.call('shell','stat','-c','%s','/storage/emulated/0/Download/FF7RLogs/ff7remake__dxgi.log',timeout=5))
                t.artifact('measurement-log-offset.json',{'dxgi_byte_offset':measurement_offset,'first_post_start_window_may_overlap':True})
                t.event('measurement_start',device_uptime=device.call('shell','cat','/proc/uptime',timeout=5))
            if (t.directory/'STOP').exists():return False
            if game_pid(device,uid)!=pid:raise LabError('Guest identity changed')
            if fatal_log.poll():
                t.event('fatal_detected');return False
            if (actions and count%3==0) or (not actions and time.monotonic()-last_sample>=10):
                last_sample=time.monotonic()
                try:
                    stat=device.call('shell','run-as',PACKAGE,'cat','/proc/'+pid+'/stat',timeout=5)
                    t.event('guest_cpu_counter',pid=pid,stat=stat,clock_ticks=device.call('shell','getconf','CLK_TCK',timeout=5))
                except LabError as exc:t.event('guest_cpu_counter_unavailable',error=str(exc))
                collectors.sample()
                capture(device,t.directory,'observation-'+str(count))
            if actions:t.event('input_start',action=route['actions'][count%len(actions)],guest_pid=pid)
            else:t.event('stationary_observation',guest_pid=pid)
            count+=1
            return True
        def close():
            # Refuse closing a replacement game. The dead original may have no PID.
            try:
                current=game_pid(device)
            except LabError as exc:
                if not str(exc).startswith('Expected exactly one FF7R'):raise
                processes=device.call('shell','ps','-A','-o','UID,PID,NAME',timeout=5)
                if 'ff7remake_.exe' in processes.lower():raise LabError('Ambiguous guest ownership; refusing close')
                current=None
            if current is not None and current!=pid:raise LabError('Refusing to close a replacement guest')
            device.call('shell','am','force-stop',PACKAGE,timeout=8)
            uid=device.call('shell','run-as',PACKAGE,'id','-u',timeout=5)
            deadline=time.monotonic()+15
            while True:
                processes=device.call('shell','ps','-A','-o','UID,PID,NAME',timeout=5)
                if not any(row.split() and row.split()[0]==uid for row in processes.splitlines()):break
                if time.monotonic()>=deadline:
                    save('processes-after-close.txt',processes)
                    raise LabError('GameNative UID processes remain after bounded retirement wait')
                time.sleep(.25)
            save('processes-after-close.txt',processes)
        try:
            with command_ledger(device,t.directory):
                result=execute_session(device,actions,observe,collect,close,duration_s=duration,stationary=not actions)
        finally:
            if logger is not None:
                logger.terminate()
                try:logger.wait(timeout=5)
                except subprocess.TimeoutExpired:logger.kill();logger.wait()
            logger_out.close();logger_err.close()
            t.artifact('logcat-process.json',{'command':[device.adb,'-s',device.serial,'logcat','-v','threadtime','-T','1'], 'pid':logger.pid if logger else None,'exit_status':logger.returncode if logger else None,'termination':'owned logger stopped after session'})
        # Full logs are pulled only after ownership-checked close succeeds;
        # retain bounded pre-close tails and failures as supplemental evidence.
        if result.get('close_completed'):
            with command_ledger(device,t.directory):
                for component in ('dxgi','d3d11'):
                    name=component+'.full.log'
                    try:
                        manifest['files'].append(archive_log(device,
                            '/storage/emulated/0/Download/FF7RLogs/ff7remake__'+component+'.log',t.directory/name))
                    except (LabError,OSError,subprocess.SubprocessError) as exc:
                        manifest['unavailable'].append({'path':name,'error':str(exc)})
                trace = re.search(r'(?:^|\s)MESA_GPU_TRACEFILE=/storage/emulated/0/Download/FF7RLogs/(gpu-[a-f0-9]{32})-%p-%i\.log(?:\s|$)', initial['config'].get('envVars',''))
                if trace:
                    try:
                        directory='/storage/emulated/0/Download/FF7RLogs/'
                        paths=device.call('shell','find',directory,'-maxdepth','1','-type','f','-name',shlex.quote(trace.group(1)+'-*.log')).splitlines()
                        if not paths:raise LabError('Requested GPU trace did not produce files; timestamp support unverified')
                        if len(paths)>64:raise LabError('GPU trace context count exceeds bounded archive limit')
                        for path in paths:
                            if not re.fullmatch(re.escape(directory+trace.group(1))+r'-[0-9]+-[0-9]+\.log',path):raise LabError('Unexpected GPU trace identity')
                            record=archive_log(device,path,t.directory/Path(path).name)
                            record['scope']='Run-scoped GPU trace, includes startup; GPU durations/path must be parsed and correlated'
                            manifest['files'].append(record)
                            if record['bytes']==0:
                                manifest['unavailable'].append({'path':Path(path).name,'error':'Empty context; no GPU timing evidence. Other contexts still collected.'})
                    except (LabError,OSError,subprocess.SubprocessError) as exc:
                        manifest['unavailable'].append({'path':'gpu-trace','error':str(exc)})
            t.artifact('diagnostic-manifest.json',manifest)
        after=attest(device,container); t.artifact('after.json',after)
        unchanged=(after['config_sha256']==initial['config_sha256'] and after['engine_ini_sha256']==initial['engine_ini_sha256'])
        result['configuration_unchanged']=unchanged
        if not unchanged:result['status']='failed';result['errors'].append({'stage':'integrity','error':'Config or Engine.ini changed'})
        t.artifact('diagnostic-manifest.json',manifest);t.artifact('result.json',result)
        t.event('retired',status=result['status'],close_verified=result['close_completed'],device_uptime=device.call('shell','cat','/proc/uptime',timeout=5))
        if loaded:
            t.event('GAMEPLAY_CAPTURED',component=loaded,status=result['status'],regression_eligible=False)
        report=analyze(t.directory)
        hashes={p.name:hash_file(p) for p in t.directory.iterdir() if p.is_file() and p.name!='hashes.json'}
        t.artifact('hashes.json',hashes)
        return {'directory':str(t.directory),'result':result,'analysis':report}

def run_live(device,container,route_path,results,*,duration=300,confirmed=False,allow_close=False,component_verification=None):
    return _run_live(device,container,route_path,results,duration=duration,
                     confirmed=confirmed,allow_close=allow_close,component_verification=component_verification)


def launch_live(device, container, results):
    """Normal exported launch, never instrumentation or container overrides."""
    if not re.fullmatch(r'CUSTOM_GAME_[0-9]+',container):
        raise LabError('Existing custom-game container required')
    with device.lease():
        processes=device.call('shell','ps','-A','-o','UID,PID,NAME',timeout=5)
        uid=device.call('shell','run-as',PACKAGE,'id','-u',timeout=5)
        guests=[r for r in processes.splitlines() if r.split() and r.split()[0]==uid
                and ('.exe' in r.lower() or 'wineserver' in r.lower())]
        if guests:raise LabError('Guest already active; refusing duplicate launch')
        before=attest(device,container,check_alias=False)
        t=Telemetry(results,uuid.uuid4().hex)
        t.artifact('before.json',before)
        with command_ledger(device,t.directory):
            output=device.call('shell','am','start','-n',PACKAGE+'/'+PACKAGE+'.MainActivity',
                '-a',PACKAGE+'.LAUNCH_GAME','--ei','app_id',container.split('_')[-1],
                '--es','game_source','CUSTOM_GAME',timeout=15)
        t.artifact('launch.json',{'stdout':output,'container':container,'overrides':None,
            'gameplay_confirmed':False,'normal_gameplay_may_autosave':True})
        return {'directory':str(t.directory),'launched':True,'gameplay_confirmed':False,
            'next':'Visually verify title/Continue/loading/gameplay, then use live-session'}
