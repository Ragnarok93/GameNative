import unittest
from unittest.mock import patch
from benchmark.device import Device


class TransportTests(unittest.TestCase):
    def test_native_adb_override_and_explicit_priority(self):
        with patch.dict('os.environ', {'GAMENATIVE_ADB': '/mnt/c/platform-tools/adb.exe'}):
            self.assertEqual(Device('SERIAL').adb, '/mnt/c/platform-tools/adb.exe')
            self.assertEqual(Device('SERIAL', adb='chosen-adb').adb, 'chosen-adb')
