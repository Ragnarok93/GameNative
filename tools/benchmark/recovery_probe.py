"""Exercise actual app process death only inside an idle configuration sandbox."""
import re
from .device import LabError, PACKAGE
from .orchestrator import execute


def probe(device, container, results):
    if not re.fullmatch(r"BENCHMARK_[A-Za-z0-9_-]+", container):
        raise LabError("Recovery probes require a dedicated sandbox")
    with device.lease():
        before = execute(device, {"operation": "inspect", "container_id": container}, results)["result"]
        patch = {"showFPS": not before["config"].get("showFPS", False)}
        interrupted = False
        try:
            execute(device, {"operation": "config-roundtrip", "container_id": container,
                "config": patch, "fault": "process-death-after-write"}, results)
        except LabError:
            interrupted = True
        if not interrupted:
            raise LabError("Process-death injection unexpectedly completed")
        # Verify an actual persisted mutation, rather than treating any failure
        # as proof that the fault reached the journal/write checkpoint.
        changed = device.call("shell", "run-as", PACKAGE, "sha256sum", before["config_path"]).split()[0]
        if changed == before["config_sha256"]:
            raise LabError("Fault did not reach persisted mutation; inspect failed evidence")
        blocked = False
        try:
            execute(device, {"operation": "list"}, results)
        except LabError as exc:
            if "Pending recovery must complete first" not in str(exc):
                raise
            blocked = True
        if not blocked:
            raise LabError("Pending recovery did not block another operation")
        recovered = execute(device, {"operation": "recover"}, results)
        after = execute(device, {"operation": "inspect", "container_id": container}, results)["result"]
        if not recovered["result"]["recovered"] or before["config_sha256"] != after["config_sha256"]:
            raise LabError("Recovery did not restore the exact original bytes")
        return {"process_death_verified": True, "pending_operation_block_verified": True,
                "exact_restore_verified": True, "recovery_run_id": recovered["run_id"]}
