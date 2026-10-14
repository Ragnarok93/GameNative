import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmark.navigation import navigate, execute_session
from benchmark.device import LabError

class NavigationTests(unittest.TestCase):
    def test_deadline_clips_last_hold(self):
        class Device:
            now = 0
            holds = []
            def call(self, *args, **kwargs):
                hold = int(args[4]); self.holds.append(hold); self.now += hold/1000
        d=Device()
        result=navigate(d, [([51,59],10000)], lambda: True,
                        duration_s=25, clock=lambda:d.now)
        self.assertEqual(d.holds,[10000,10000,5000])
        self.assertEqual(result['elapsed_s'],25)
        self.assertFalse(result['regression_eligible'])
    def test_slow_observation_consumes_budget(self):
        class Device:
            now=0
            def call(self,*args,**kwargs): raise AssertionError('Input after deadline')
        d=Device()
        def observe(): d.now+=301; return True
        result=navigate(d,[([51,59],1000)],observe,clock=lambda:d.now)
        self.assertEqual(result['actions'],0)
    def test_rejects_observation_before_input(self):
        class Device:
            def call(self,*args,**kwargs): raise AssertionError('Unexpected input')
        self.assertEqual(navigate(Device(),[([51,59],1000)],lambda:False)['reason'],
                         'observation_rejected')
    def test_invalid_route_has_no_inputs(self):
        for duration in (True,float('nan'),301,0):
            with self.assertRaises(LabError):
                navigate(None,[([51,59],1000)],lambda:True,duration_s=duration)
        with self.assertRaises(LabError):
            navigate(None,[([66,59],1000)],lambda:True)


class SessionTests(unittest.TestCase):
    def test_collect_before_close_after_observation_rejection(self):
        order=[]
        result=execute_session(None,[([51,59],1000)],lambda:False,
            lambda:order.append("collect"),lambda:order.append("close"))
        self.assertEqual(order,["collect","close"])
        self.assertTrue(result["close_completed"])
        self.assertEqual(result["status"],"aborted")
        self.assertEqual(result["navigation"]["reason"],"observation_rejected")
    def test_input_and_collection_failure_still_close(self):
        order=[]
        class Device:
            def call(self,*args,**kwargs): raise RuntimeError("ADB disconnected")
        def collect(): order.append("collect"); raise RuntimeError("Logs unavailable")
        result=execute_session(Device(),[([51,59],1000)],lambda:True,
            collect,lambda:order.append("close"))
        self.assertEqual(order,["collect","close"])
        self.assertEqual([e["stage"] for e in result["errors"]],
                         ["navigation","collection"])
        self.assertEqual(result["status"],"failed")
    def test_close_failure_not_reported_as_closed(self):
        def close(): raise RuntimeError("Process still running")
        result=execute_session(None,[([51,59],1000)],lambda:False,
                               lambda:{"fex":"unavailable"},close)
        self.assertFalse(result["close_completed"])
        self.assertEqual(result["errors"][0]["stage"],"close")


class StationaryTests(unittest.TestCase):
    def test_static_sample_never_sends_input(self):
        class Device:
            now=0
            def call(self,*a,**kw):raise AssertionError('Stationary input')
        d=Device()
        def sleep(s):d.now+=s
        r=navigate(d,[],lambda:True,duration_s=3,clock=lambda:d.now,stationary=True,sleeper=sleep)
        self.assertEqual(r['actions'],0)
        self.assertEqual(r['elapsed_s'],3)
