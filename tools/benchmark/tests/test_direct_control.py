import json,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from contextlib import nullcontext
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from benchmark.direct_control import pin,require_api,ensure_imported,select,verify_loaded
from benchmark.device import LabError,PACKAGE
class Device:
    adb='adb';serial='mock'
    def lease(self):return nullcontext()
    def execute(self,*a,**k):raise AssertionError('Unexpected transfer')
    def call(self,*a,**k):
        if a[-1].endswith('/maps'):return self.maps
        return self.digest+' file'
class DirectTests(unittest.TestCase):
    def doc(self,root):
        p=root/'verification.json'
        p.write_text(json.dumps({'source_commit':'a'*40,'validated':{'type':'DXVK','path':'fixture.wcp','sha256':'b'*64,'version':'candidate','version_code':1},'payload_sha256':{arch+'/'+dll:'c'*64 for arch in ['x32','x64'] for dll in ['d3d11.dll','dxgi.dll']}}));return p
    def test_pin_rejects_missing_hash_and_short_commit(self):
        with tempfile.TemporaryDirectory() as td:
            p=self.doc(Path(td));self.assertEqual(pin(p)[0]['version'],'candidate')
            d=json.loads(p.read_text());d['source_commit']='a'*8;p.write_text(json.dumps(d))
            with self.assertRaises(LabError):pin(p)
    def test_old_api_fails_once_no_gui(self):
        with patch('benchmark.direct_control.execute',return_value={'result':{'api_revision':1}}) as call:
            with self.assertRaises(LabError):require_api(Device())
            self.assertEqual(call.call_count,1)
    def test_existing_identical_component_skips_transfer(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);p=self.doc(root)
            with patch('benchmark.direct_control.validate_package'),patch('benchmark.direct_control.execute',side_effect=[{'result':{'api_revision':2,'direct_existing_dxvk':True}},{'result':{'installed':True}}]):
                result=ensure_imported(Device(),p,root/'out')
            self.assertTrue(result['already_installed'])
            self.assertEqual(json.loads((root/'out/component-lifecycle.jsonl').read_text())['stage'],'IMPORTED_VERIFIED')
    def test_selection_requires_explicit_authorization(self):
        with self.assertRaises(LabError):select(Device(),'unused','unused')
    def test_prefix_hash_alone_is_not_loaded(self):
        d=Device();d.maps='';d.digest='c'*64
        with self.assertRaises(LabError):verify_loaded(d,7,{'payload_sha256':{}},'CUSTOM_GAME_227938910')
    def test_loaded_identity_and_hash_mismatch(self):
        d=Device();base='/data/user/0/'+PACKAGE+'/files/imagefs_shared/home/xuser-CUSTOM_GAME_227938910/.wine/drive_c/windows/system32/'
        d.maps='\n'.join('000-fff r-xp 0 00:00 1 '+base+dll for dll in ['d3d11.dll','dxgi.dll']);d.digest='c'*64
        identity={'source_commit':'a'*40,'version':'candidate','payload_sha256':{'x64/d3d11.dll':'c'*64,'x64/dxgi.dll':'c'*64}}
        self.assertTrue(verify_loaded(d,7,identity,'CUSTOM_GAME_227938910')['runtime_loaded_verified'])
        d.digest='d'*64
        with self.assertRaises(LabError):verify_loaded(d,7,identity,'CUSTOM_GAME_227938910')

    def test_gpu_experiment_refuses_old_apk_before_mutation(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);v=self.doc(root)
            with patch('benchmark.direct_control.execute',return_value={'result':{'api_revision':2,'direct_existing_dxvk':True}}) as call:
                with self.assertRaises(LabError):select(Device(),v,root/'out',allow_existing=True,turnip_autotune='profiled')
                self.assertEqual(call.call_count,1)
    def test_invalid_gpu_experiment_never_calls_device(self):
        with self.assertRaises(LabError):select(Device(),'unused','unused',allow_existing=True,turnip_autotune='arbitrary')

    def test_effective_gpu_env_required_before_measurement(self):
        from benchmark.direct_control import verify_gpu_environment
        d=Device();d.call=lambda *a,**kw:'TU_AUTOTUNE_ALGO=profiled\0MESA_GPU_TRACES=print\0TOKEN=not-exported\0'
        result=verify_gpu_environment(d,7,{'envVars':'TU_AUTOTUNE_ALGO=profiled MESA_GPU_TRACES=print'})
        self.assertTrue(result['verified']);self.assertNotIn('TOKEN',result['effective'])
        with self.assertRaises(LabError):verify_gpu_environment(d,7,{'envVars':'TU_AUTOTUNE_ALGO=bandwidth'})
    def test_no_gpu_options_do_not_read_environment(self):
        from benchmark.direct_control import verify_gpu_environment
        d=Device();d.call=lambda *a,**k:self.fail('Should not read environment')
        self.assertIsNone(verify_gpu_environment(d,7,{'envVars':'TU_DEBUG=noconform'}))
