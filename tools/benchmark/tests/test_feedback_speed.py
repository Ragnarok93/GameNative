import sys
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from benchmark.live import IncrementalFatalLog
from benchmark.ui_navigation import Navigator,bounds
from benchmark.device import LabError
class SpeedTests(unittest.TestCase):
    def test_incremental_read_and_split_fatal_marker(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'log';p.write_bytes(b'normal\n');reader=IncrementalFatalLog(p)
            self.assertFalse(reader.poll());self.assertFalse(reader.poll());self.assertEqual(reader.bytes_read,7)
            with p.open('ab') as f:f.write(b'VK_ERROR_DEVICE_')
            self.assertFalse(reader.poll())
            with p.open('ab') as f:f.write(b'LOST')
            self.assertTrue(reader.poll());self.assertEqual(reader.bytes_read,p.stat().st_size)
    def test_truncate_and_replace_scans_again(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'log';p.write_bytes(b'x'*100);r=IncrementalFatalLog(p);self.assertFalse(r.poll())
            p.write_bytes(b'Fatal signal');self.assertTrue(r.poll())
            q=Path(d)/'replacement';q.write_bytes(b'VK_ERROR_DEVICE_LOST');q.replace(p);self.assertTrue(r.poll())
    def test_hidden_wrong_package_and_ambiguous_nodes(self):
        tree=ET.fromstring('<hierarchy><node text="A" package="app.gamenative" enabled="true" bounds="[0,0][0,0]"/><node text="A" package="other" enabled="true" bounds="[1,1][9,9]"/><node text="A" package="app.gamenative" enabled="true" bounds="[2,2][8,8]"/></hierarchy>')
        self.assertEqual(len(Navigator.matches(tree,'A')),1)
        with self.assertRaises(LabError):bounds(tree[0])

    def test_horizontal_swipe_avoids_android_back_edges(self):
        class D:
            def __init__(self):self.calls=[]
            def call(self,*a,**kw):self.calls.append(a)
        tree=ET.fromstring('<hierarchy><node class="android.widget.HorizontalScrollView" scrollable="true" bounds="[0,200][1080,300]"/></hierarchy>')
        with tempfile.TemporaryDirectory() as p:
            d=D();n=Navigator(d,p,sleep=lambda _:None);n.scroll(tree,True)
            self.assertEqual(d.calls[0],('shell','input','swipe','930','250','150','250','350'))

    def test_detail_options_excludes_landscape_bottom_navigation(self):
        from unittest.mock import Mock
        tree=ET.fromstring('<hierarchy><node package="app.gamenative" enabled="true" clickable="true" content-desc="Uninstall" bounds="[900,600][960,660]"/><node package="app.gamenative" enabled="true" clickable="true" content-desc="Options" bounds="[700,600][760,660]"/><node package="app.gamenative" enabled="true" clickable="true" content-desc="Options" bounds="[700,940][760,1000]"/></hierarchy>')
        with tempfile.TemporaryDirectory() as p:
            d=Mock();n=Navigator(d,p,sleep=lambda _:None);n.dump=lambda:(tree,{c:tree for c in tree});n.click=Mock();n.editor('FF7')
            taps=[call.args for call in d.call.call_args_list if call.args[:3]==('shell','input','tap')]
            self.assertEqual(taps,[('shell','input','tap','730','630')])
