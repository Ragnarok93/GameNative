import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from benchmark.guest_cpu import parse_stat,summarize_cpu

def event(cpu,ns,pid='7',start=123,hz='100'):
    f=['0']*22;f[0]='R';f[11]=str(cpu);f[19]=str(start)
    return {'event':'guest_cpu_counter','pid':pid,'stat':pid+' (guest ) name) '+' '.join(f),
            'clock_ticks':hz,'host_monotonic_ns':ns}
class GuestCpuTests(unittest.TestCase):
    def test_multicore_cpu_not_clamped(self):
        r=summarize_cpu([event(100,1000000000),event(500,2000000000)])
        self.assertEqual(r['one_core_percent'],400)
    def test_pid_reuse_rejected(self):
        r=summarize_cpu([event(100,1),event(200,1000000001,start=124)])
        self.assertFalse(r['available'])
    def test_counter_reset_rejected(self):
        self.assertFalse(summarize_cpu([event(200,1),event(100,1000000001)])['available'])
    def test_invalid_clock_and_short_samples_unavailable(self):
        self.assertFalse(summarize_cpu([event(100,1),event(200,2,hz='0')])['available'])
        self.assertFalse(summarize_cpu([event(100,1)])['available'])
        with self.assertRaises(ValueError):parse_stat('bad')