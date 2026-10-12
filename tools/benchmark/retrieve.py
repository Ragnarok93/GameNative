"""Retrieve an authenticated GitHub build and source archive with pinned identity."""
import json
import re
import subprocess
import tempfile
from pathlib import Path
from .components import validate_package, sha256
from .device import LabError


def fetch(repo, run_id, artifact, filename, component, version, expected_hash, commit, destination):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) or not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise LabError("Explicit repository and full source commit required")
    if type(run_id) is not int or run_id <= 0 or Path(filename).name != filename or not filename.endswith(".wcp"):
        raise LabError("Invalid workflow run or WCP filename")
    def gh(*args, **kwargs):
        result = subprocess.run(["gh", *args], stderr=subprocess.PIPE, timeout=300, check=False, **kwargs)
        if result.returncode:
            raise LabError("GitHub retrieval failed: " + result.stderr.decode(errors="replace"))
        return result
    identity = json.loads(gh("run", "view", str(run_id), "--repo", repo, "--json", "headSha,conclusion,status", stdout=subprocess.PIPE).stdout)
    if identity != {"headSha": commit, "conclusion": "success", "status": "completed"}:
        raise LabError("Workflow did not succeed at the pinned source commit")
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / filename
    source_target = destination / (filename + ".source.tar.gz")
    manifest_target = destination / (filename + ".provenance.json")
    if any(path.exists() for path in (target, source_target, manifest_target)):
        raise LabError("Refusing to overwrite retrieved evidence")
    with tempfile.TemporaryDirectory() as tmp:
        gh("run", "download", str(run_id), "--repo", repo, "--name", artifact, "--dir", tmp, stdout=subprocess.PIPE)
        package = validate_package(Path(tmp) / filename, component, version, expected_hash)
        source = Path(tmp) / "source.tar.gz"
        with source.open("wb") as stream:
            gh("api", f"repos/{repo}/tarball/{commit}", stdout=stream)
        record = {"repository": repo, "workflow_run_id": run_id, "source_commit": commit,
            "source_sha256": sha256(source), "package_sha256": package["sha256"], "version": version,
            "provenance_scope": "Successful workflow head and downloaded source archive; inspect build manifest for transitive dependencies"}
        import shutil
        shutil.copyfile(package["path"], target)
        shutil.copyfile(source, source_target)
        manifest_target.write_text(json.dumps(record, indent=2) + "\n")
    return {"package": str(target), "manifest": str(manifest_target), **record}
