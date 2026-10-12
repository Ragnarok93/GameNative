import io
import json
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmark.backup import ROOTS, backup, verify_archive
from benchmark.device import Device, LabError
from test_lab import MockAdb


class BackupTests(unittest.TestCase):
    def fixture(self, path, unsafe=False):
        with tarfile.open(path, "w") as archive:
            for root in ROOTS:
                item = tarfile.TarInfo(root)
                item.type = tarfile.DIRTYPE
                archive.addfile(item)
            for name in ("databases/pluvia.db", "files/imagefs_shared/home/xuser-CUSTOM_GAME_1/.container"):
                item = tarfile.TarInfo(name)
                item.size = 3
                archive.addfile(item, io.BytesIO(b"abc"))
            link = tarfile.TarInfo("files/imagefs_shared/home/xuser")
            link.type, link.linkname = tarfile.SYMTYPE, "/external/saves"
            archive.addfile(link)
            if unsafe:
                item = tarfile.TarInfo("../escape")
                item.size = 1
                archive.addfile(item, io.BytesIO(b"x"))

    def test_verification_hashes_data_and_records_external_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "fixture.tar"
            self.fixture(path)
            result = verify_archive(path)
            self.assertEqual(result["file_count"], 2)
            self.assertIn("databases/pluvia.db", result["critical_file_sha256"])
            self.assertFalse(result["external_symlink_targets_copied"])
            self.assertEqual(result["symlinks_and_hardlinks"][0]["target"], "/external/saves")

    def test_incomplete_or_unsafe_archive_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "fixture.tar"
            self.fixture(path, unsafe=True)
            with self.assertRaises(LabError):
                verify_archive(path)
            path.write_bytes(b"incomplete")
            with self.assertRaises(LabError):
                verify_archive(path)

    def test_backup_transfer_completed_only_after_verification(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "fixture.tar"
            self.fixture(path)
            mock = MockAdb()
            def execute(argv, **kwargs):
                if "tar" in argv:
                    kwargs["stdout"].write(path.read_bytes())
                    return subprocess.CompletedProcess(argv, 0, b"", b"")
                return mock(argv, **kwargs)
            report = backup(Device("test-device", execute=execute), Path(tmp) / "backups")
            self.assertTrue(Path(report["archive"]).is_file())
            manifest = json.loads(Path(report["manifest"]).read_text())
            self.assertEqual(manifest["status"], "complete")
            self.assertEqual(manifest["archive_sha256"], report["archive_sha256"])
            self.assertEqual(Path(report["archive"]).stat().st_mode & 0o777, 0o600)
            self.assertFalse(any("instrument" in call for call in mock.calls))

    def test_active_app_or_failed_transfer_never_completes_backup(self):
        with tempfile.TemporaryDirectory() as tmp:
            mock = MockAdb(active=True)
            with self.assertRaises(LabError):
                backup(Device("test-device", execute=mock), tmp)
            self.assertFalse(list(Path(tmp).glob("*/app-data.tar*")))
        with tempfile.TemporaryDirectory() as tmp:
            mock = MockAdb()
            def fail(argv, **kwargs):
                if "tar" in argv:
                    kwargs["stdout"].write(b"partial")
                    return subprocess.CompletedProcess(argv, 1, b"", b"device disconnected")
                return mock(argv, **kwargs)
            with self.assertRaisesRegex(LabError, "disconnected"):
                backup(Device("test-device", execute=fail), tmp)
            self.assertTrue(list(Path(tmp).glob("*/app-data.tar.partial")))
            self.assertFalse(list(Path(tmp).glob("*/manifest.json")))


if __name__ == "__main__":
    unittest.main()
