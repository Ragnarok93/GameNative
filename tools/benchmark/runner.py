import copy
import json
import queue
import subprocess
import threading
import time
import uuid
import sys
import shlex
from pathlib import Path
from .device import LabError, PACKAGE
from .telemetry import Telemetry
from .collectors import Collectors, thermal_gate
from .metrics import summarize
from .run_profiles import validate_run_profile
from .trace import Trace
from .diagnostics import collect as collect_diagnostics


def trial(device, profile, variant_name, trial_index, cache_mode, cache_token, results):
    variant = copy.deepcopy(profile["variants"][variant_name])
    run_id = uuid.uuid4().hex
    telemetry = Telemetry(results, run_id)
    print(json.dumps({"event": "session_created", "run_id": run_id, "results": str(telemetry.directory)}), file=sys.stderr, flush=True)
    workload = profile["workload"]
    request = {"protocol_version": 1, "run_id": run_id, "operation": "run", **profile["game"],
        "config": variant["config"], "runtime_expectations": variant["runtime_expectations"],
        "artifacts": [artifact for pin in variant["pins"].values() for artifact in pin["artifacts"]],
        "runtime_artifacts": [artifact for pin in variant["pins"].values() for artifact in pin["runtime_artifacts"]],
        "cache_mode": cache_mode, "cache_token": cache_token, "workload": workload}
    telemetry.artifact("request.json", request)
    telemetry.artifact("profile.json", profile)
    telemetry.artifact("trial.json", {"variant": variant_name, "trial_index": trial_index, "cache_mode": cache_mode,
        "cache_token": cache_token, "workload_kind": workload["kind"]})
    telemetry.event("VALIDATING")
    collector = Collectors(device, telemetry, profile["telemetry"])
    trace = Trace(device, telemetry)
    uid_log = None
    log_file = None
    thread = None
    mailbox = queue.Queue()
    last_phase = None
    root = f"files/benchmark-lab/{run_id}"
    try:
        info = device.info()
        telemetry.artifact("device.json", info)
        for key, value in profile["device"].items():
            if info[key] != value:
                raise LabError(f"Device {key} does not match profile")
        device.assert_idle_debug()
        thermal_gate(device, profile["telemetry"]["thermal_max_status"])
        if profile["telemetry"]["perfetto"]:
            trace.start(workload["startup_timeout_ms"] + workload["warmup_ms"] + workload["measure_ms"] + 180000)
        uid = device.call("shell", "run-as", PACKAGE, "id", "-u")
        log_file = (telemetry.directory / "logcat.txt").open("wb")
        uid_log = subprocess.Popen([device.adb, "-s", device.serial, "logcat", "--uid=" + uid,
                                    "-T", "1", "-v", "epoch"], stdout=log_file, stderr=subprocess.STDOUT)
        timeout = (workload["startup_timeout_ms"] + workload["warmup_ms"] + workload["measure_ms"]) / 1000 + 240
        def invoke():
            try:
                mailbox.put((True, device.bridge(request, timeout=timeout)))
            except BaseException as exc:
                mailbox.put((False, exc))
        thread = threading.Thread(target=invoke, daemon=True)
        thread.start()
        while thread.is_alive():
            try:
                status = json.loads(device.call("shell", "run-as", PACKAGE, "cat", root + "/status.json", timeout=10))
                if status.get("run_id") != run_id:
                    raise LabError("Device status belongs to another run")
                phase = status.get("phase")
                if phase != last_phase:
                    telemetry.event("device_phase", **status)
                    last_phase = phase
                if phase == "MEASURING":
                    sample = collector.sample()
                    if sample.get("thermal_status", 0) > profile["telemetry"]["thermal_max_status"]:
                        raise LabError("Thermal gate exceeded during measurement")
            except LabError as exc:
                # Before the activity creates its status file there is no state
                # to poll. Errors after a state exists must never be swallowed.
                if last_phase is not None:
                    raise
                telemetry.event("awaiting_device_status", detail=str(exc))
            time.sleep(profile["telemetry"]["sample_interval_ms"] / 1000)
        success, result = mailbox.get_nowait()
        if not success:
            raise result
        telemetry.artifact("result.json", result)
        execution = result["execution"]
        submitted = json.loads(device.call("shell", "run-as", PACKAGE, "cat", root + "/renderer-frames.json"))
        telemetry.artifact("renderer-frames.json", submitted)
        for name in ("events.jsonl", "lsfg-diagnostics.txt", "status.json"):
            text = device.call("shell", "run-as", PACKAGE, "cat", root + "/" + name, timeout=30)
            (telemetry.directory / ("device-" + name)).write_text(text + "\n")
        if workload["kind"] == "exploration":
            try:
                text = device.call("shell", "run-as", PACKAGE, "cat", root + "/interactive-input.jsonl")
                (telemetry.directory / "device-interactive-input.jsonl").write_text(text + "\n")
            except LabError:
                (telemetry.directory / "device-interactive-input.jsonl").write_text("")
        dxvk_files = device.call("shell", "run-as", PACKAGE, "find", root + "/dxvk", "-type", "f").splitlines()
        for remote in dxvk_files:
            name = Path(remote).name
            if not remote.startswith(root + "/dxvk/") or '/' in name or not name.endswith(".log"):
                raise LabError("Invalid DXVK log artifact path")
            output = telemetry.directory / "dxvk" / name
            output.parent.mkdir(exist_ok=True)
            output.write_text(device.call("shell", "run-as", PACKAGE, "cat", shlex.quote(remote), timeout=30))
        collector.finish()
        metrics = summarize(execution, collector.presented, submitted)
        metrics["eligible_for_regression"] = workload["kind"] != "exploration" and all(
            pin["source_kind"] != "installed_manifest" for pin in variant["pins"].values())
        metrics["provenance_kinds"] = {name: pin["source_kind"] for name, pin in variant["pins"].items()}
        if collector.coverage_gaps:
            metrics["display_cadence"] = {"available": False, "reason": "SurfaceFlinger ring coverage gaps detected; analyze Perfetto instead"}
        telemetry.artifact("metrics.json", metrics)
        device.assert_idle_debug()
        trace.finish()
        telemetry.event("COMPLETE", configuration_restored=result["restored"], measured_performance=metrics["display_cadence"]["available"])
        return {"run_id": run_id, "results": str(telemetry.directory), "metrics": metrics}
    except BaseException as exc:
        if thread and thread.is_alive():
            # This private cancellation marker is polled by the owned workload.
            try:
                device.call("shell", "-T", "run-as", PACKAGE, "tee", root + "/cancel", data=b"cancel")
            except LabError as cancel_error:
                telemetry.event("cancellation_failed", error=str(cancel_error))
            thread.join(timeout=180)
        collector.finish()
        telemetry.event("FAILED", error=str(exc), runtime_retired=not (thread and thread.is_alive()),
                        recovery="Inspect device processes and pending journal; run recover while idle before another mutation")
        raise
    finally:
        if uid_log:
            uid_log.terminate()
            try:
                uid_log.wait(timeout=5)
            except subprocess.TimeoutExpired:
                uid_log.kill(); uid_log.wait()
        if log_file:
            log_file.close()
        try:
            collect_diagnostics(device, telemetry, run_id)
        except (LabError, OSError) as exc:
            telemetry.event("diagnostic_collection_failed", error=str(exc))
        try:
            trace.finish()
        except (LabError, OSError, subprocess.TimeoutExpired) as exc:
            telemetry.event("trace_collection_failed", error=str(exc))


def run(device, profile, results, variant=None, repeat=None):
    profile = copy.deepcopy(validate_run_profile(profile))
    names = [variant] if variant else list(profile["variants"])
    if any(name not in profile["variants"] for name in names):
        raise LabError("Unknown variant")
    count = repeat if repeat is not None else profile["workload"]["trials"]
    if type(count) is not int or not 1 <= count <= 10:
        raise LabError("Repeat must be between 1 and 10")
    campaign = Telemetry(results, uuid.uuid4().hex)
    campaign.artifact("profile.json", profile)
    runs = []
    try:
        with device.lease():
            # Rotate variant order by trial to reduce fixed thermal/order bias.
            for index in range(count):
                order = names[index % len(names):] + names[:index % len(names)]
                for name in order:
                    token = uuid.uuid4().hex
                    for mode in ("cold", "warm"):
                        completed = trial(device, profile, name, index, mode, token, results)
                        runs.append(dict(completed, variant=name, cache_mode=mode, trial_index=index))
                        campaign.event("trial_complete", child_run_id=completed["run_id"], variant=name,
                                       cache_mode=mode, trial_index=index, results=completed["results"])
        report = {"schema_version": 2, "status": "complete", "campaign_id": campaign.run_id,
                  "runs": runs, "stable_promotion": False}
        campaign.artifact("campaign.json", report)
        return dict(report, results=str(campaign.directory))
    except BaseException as exc:
        campaign.artifact("campaign.json", {"schema_version": 2, "status": "failed", "runs": runs, "error": str(exc)})
        campaign.event("FAILED", error=str(exc))
        raise
