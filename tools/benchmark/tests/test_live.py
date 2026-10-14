import re
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from benchmark.device import LabError
from benchmark.live import validate_route, run_live, analyze, command_ledger, attest, launch_live, LIVE_LAYER_PATTERN
from benchmark.navigation import execute_session

class Fake:
    serial='mock';adb='/bin/echo';execute=staticmethod(subprocess.run)
    def __init__(self):self.alive=True;self.calls=[];self.now=0
    def lease(self):return nullcontext()
    def assert_idle_debug(self,require_bridge=True):raise LabError('Mock full archival unavailable')
    def info(self):return {'model':'mock'}
    def call(self,*a,**kw):
        self.calls.append(a)
        if a[:3]==('shell','input','keycombination'):self.now+=int(a[4])/1000;return ''
        if a[:3]==('shell','am','force-stop'):self.alive=False;return ''
        if a[:2]==('shell','ps'):return 'UID PID NAME\n'+('10208 7 ff7remake_.exe' if self.alive else '')
        if a[-2:]==('id','-u'):return '10208'
        if a[-1].endswith('/.container'):return '{"screenSize":"1280x720"}'
        if 'sha256sum' in a:return 'a'*64+' file'
        if 'readlink' in a:return './xuser-CUSTOM_GAME_1'
        if a[-1]=='/proc/uptime':return '100 50'
        if a[:3]==('shell','stat','-c'):return '0'
        if a[-1].endswith('d3d11.log'):return 'DXVK queue info'
        if a[-1].endswith('dxgi.log'):return 'DXVK-FF7R-FRAMES: ts_us=101 mean_us=33000 p95_us=50000'
        return ''

class Collector:
    def __init__(self,d,t,o):self.t=t
    def sample(self):self.t.event('device_sample',thermal_status=4)
    def finish(self):self.t.artifact('presentation.json',{'coverage':'mock'})

class LiveTests(unittest.TestCase):
    def test_route_rejects_system_keys_and_unbounded_holds(self):
        for keys,hold in [([3,59],1000),([51,51],1000),([51,59],10001),([True,59],1)]:
            with self.assertRaises(LabError):validate_route({'schema_version':1,'description':'x','actions':[{'keys':keys,'hold_ms':hold}]})
    def test_permission_failure_no_device_calls(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td)/'route.json';p.write_text(json.dumps({'schema_version':1,'description':'x','actions':[{'keys':[51,59],'hold_ms':1000}]}))
            d=Fake()
            with self.assertRaises(LabError):run_live(d,'CUSTOM_GAME_1',p,td)
            self.assertEqual(d.calls,[])
    def test_active_alias_mismatch_refused(self):
        d=Fake(); original=d.call
        d.call=lambda *a,**kw:'./xuser-OTHER' if 'readlink' in a else original(*a,**kw)
        with self.assertRaises(LabError):attest(d,'CUSTOM_GAME_1')
    def test_owned_session_collects_closes_and_analyzes(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td)/'route.json';p.write_text(json.dumps({'schema_version':1,'description':'mock','actions':[{'keys':[51,59],'hold_ms':1000}]}))
            d=Fake()
            def session(*a,**kw):return execute_session(*a,**kw,clock=lambda:d.now)
            with patch('benchmark.live.Collectors',Collector),patch('benchmark.live.capture',return_value='mock.png'),patch('benchmark.live.execute_session',side_effect=session):
                value=run_live(d,'CUSTOM_GAME_1',p,td,duration=2,confirmed=True,allow_close=True)
            self.assertTrue(value['result']['close_completed'])
            self.assertTrue(value['result']['configuration_unchanged'])
            self.assertFalse(d.alive)
            self.assertFalse(value['analysis']['regression_eligible'])
            self.assertFalse(value['analysis']['fex_profiling']['available'])
            self.assertTrue((Path(value['directory'])/'hashes.json').exists())
    def test_command_ledger_preserves_original_and_literal_outputs(self):
        with tempfile.TemporaryDirectory() as td:
            d=Fake();old=d.execute
            with command_ledger(d,Path(td)):
                r=d.execute(['/bin/echo','hello'],capture_output=True)
                self.assertEqual(r.returncode,0)
            self.assertIs(d.execute,old)
            record=json.loads((Path(td)/'commands.jsonl').read_text())
            self.assertEqual(record['stdout'],'hello\n')
            self.assertEqual(record['stderr'],'')
            self.assertEqual(record['exit_status'],0)
    def test_duplicate_launch_refused(self):
        with tempfile.TemporaryDirectory() as td:
            d=Fake()
            with self.assertRaises(LabError):launch_live(d,'CUSTOM_GAME_1',td)
            self.assertFalse(any(a[:3]==('shell','am','start') for a in d.calls))
    def test_launch_uses_normal_intent_without_overrides(self):
        with tempfile.TemporaryDirectory() as td:
            d=Fake();d.alive=False
            result=launch_live(d,'CUSTOM_GAME_1',td)
            commands=[a for a in d.calls if a[:3]==('shell','am','start')]
            self.assertEqual(len(commands),1)
            self.assertNotIn('container_config',commands[0])
            self.assertFalse(result['gameplay_confirmed'])

    def test_initial_screenshot_failure_still_collects_and_closes(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td)/'route.json';p.write_text(json.dumps({'schema_version':1,'description':'mock','actions':[{'keys':[51,59],'hold_ms':1000}]}))
            d=Fake()
            with patch('benchmark.live.Collectors',Collector),patch('benchmark.live.capture',side_effect=LabError('Screenshot unavailable')):
                value=run_live(d,'CUSTOM_GAME_1',p,td,duration=1,confirmed=True,allow_close=True)
            self.assertEqual(value['result']['status'],'failed')
            self.assertTrue(value['result']['close_completed'])
            self.assertFalse(d.alive)
    def test_replacement_guest_is_not_closed(self):
        class Replacement(Fake):
            swapped=False
            def call(self,*a,**kw):
                value=super().call(*a,**kw)
                if a[:3]==('shell','input','keycombination'):self.swapped=True
                if self.swapped and a[:2]==('shell','ps'):value=value.replace('10208 7 ','10208 8 ')
                return value
        with tempfile.TemporaryDirectory() as td:
            p=Path(td)/'route.json';p.write_text(json.dumps({'schema_version':1,'description':'mock','actions':[{'keys':[51,59],'hold_ms':1000}]}))
            d=Replacement()
            def session(*a,**kw):return execute_session(*a,**kw,clock=lambda:d.now)
            with patch('benchmark.live.Collectors',Collector),patch('benchmark.live.capture',return_value='mock.png'),patch('benchmark.live.execute_session',side_effect=session):
                value=run_live(d,'CUSTOM_GAME_1',p,td,duration=2,confirmed=True,allow_close=True)
            self.assertFalse(value['result']['close_completed'])
            self.assertTrue(d.alive)
            self.assertFalse(any(a[:3]==('shell','am','force-stop') for a in d.calls))
    def test_delayed_guest_retirement_polled(self):
        class Delayed(Fake):
            pending=False
            def call(self,*a,**kw):
                value=super().call(*a,**kw)
                if a[:3]==('shell','am','force-stop'):self.pending=True
                if a[:2]==('shell','ps') and self.pending:
                    self.pending=False;return 'UID PID NAME\n10208 7 [ff7remake_.exe]'
                return value
        with tempfile.TemporaryDirectory() as td:
            p=Path(td)/'route.json';p.write_text(json.dumps({'schema_version':1,'description':'mock','actions':[{'keys':[51,59],'hold_ms':1000}]}))
            d=Delayed()
            def session(*a,**kw):return execute_session(*a,**kw,clock=lambda:d.now)
            with patch('benchmark.live.Collectors',Collector),patch('benchmark.live.capture',return_value='mock.png'),patch('benchmark.live.execute_session',side_effect=session):
                value=run_live(d,'CUSTOM_GAME_1',p,td,duration=1,confirmed=True,allow_close=True)
            self.assertTrue(value['result']['close_completed'])

    def test_surface_layer_excludes_background_and_parent(self):
        layers=['Background for SurfaceView[app.gamenative/app.gamenative.MainActivity]@0#20185',
                'SurfaceView[app.gamenative/app.gamenative.MainActivity]@0#20183',
                'SurfaceView[app.gamenative/app.gamenative.MainActivity]@0(BLAST)#20184']
        self.assertEqual([x for x in layers if re.search(LIVE_LAYER_PATTERN,x)],[layers[-1]])
