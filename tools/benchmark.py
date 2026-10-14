#!/usr/bin/env python3
"""GameNative lab: pinned deployment, isolated guest control, telemetry and comparisons."""
import argparse
import json
import sys
import uuid
from benchmark.components import validate_package
from benchmark.device import Device, LabError, PACKAGE
from benchmark.orchestrator import execute
from benchmark.profiles import load_profile
from benchmark.compare import compare
from benchmark.backup import backup
from benchmark.run_profiles import load_run_profile
from benchmark.runner import run, trial
from benchmark.performance_compare import compare_performance
from benchmark.retrieve import fetch
from benchmark.recovery_probe import probe
from benchmark.control import command as control_command, status as session_status, export_route
from benchmark.workload_snapshot import snapshot
from benchmark.drivers import validate_driver
from benchmark.package_build import package_dxvk
from benchmark.build_set import resolve as resolve_build_set
from benchmark.build_dispatch import dispatch as dispatch_build


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Explicit ADB serial; never select a device implicitly")
    parser.add_argument("--results", default="benchmark-results")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("device-info")
    sub.add_parser("list-components")
    dispatch_parser = sub.add_parser("dispatch-build")
    dispatch_parser.add_argument("--manifest", required=True)
    dispatch_parser.add_argument("--output", required=True)
    dispatch_parser.add_argument("--execute", action="store_true", help="Actually submit the workflow; default is validation only")
    build_set = sub.add_parser("build-set")
    build_set.add_argument("--manifest", required=True)
    build_set.add_argument("--output", required=True)
    build_set.add_argument("--download", action="store_true")
    validate = sub.add_parser("validate-profile")
    validate.add_argument("profile")
    install = sub.add_parser("install")
    install.add_argument("--component", choices=["dxvk", "fex", "turnip", "proton"], required=True)
    install.add_argument("--package", required=True)
    install.add_argument("--version", required=True)
    install.add_argument("--sha256", required=True)
    driver_parser = sub.add_parser("install-driver")
    driver_parser.add_argument("--package", required=True)
    driver_parser.add_argument("--sha256", required=True)
    fetch_parser = sub.add_parser("fetch-component")
    fetch_parser.add_argument("--repo", required=True)
    fetch_parser.add_argument("--run-id", required=True, type=int)
    fetch_parser.add_argument("--artifact", required=True)
    fetch_parser.add_argument("--filename", required=True)
    fetch_parser.add_argument("--component", required=True, choices=["dxvk", "fex", "turnip", "proton"])
    fetch_parser.add_argument("--version", required=True)
    fetch_parser.add_argument("--sha256", required=True)
    fetch_parser.add_argument("--source-commit", required=True)
    fetch_parser.add_argument("--destination", required=True)
    pack_parser = sub.add_parser("package-dxvk")
    pack_parser.add_argument("--directory", required=True)
    pack_parser.add_argument("--version", required=True)
    pack_parser.add_argument("--version-code", required=True, type=int)
    pack_parser.add_argument("--output", required=True)
    config = sub.add_parser("verify-config")
    config.add_argument("--profile", required=True)
    sub.add_parser("recover")
    recovery_test = sub.add_parser("test-recovery")
    recovery_test.add_argument("--container", required=True)
    run_parser = sub.add_parser("run")
    run_parser.add_argument("--profile", required=True)
    run_parser.add_argument("--variant")
    run_parser.add_argument("--repeat", type=int)
    explore_parser = sub.add_parser("explore")
    explore_parser.add_argument("--profile", required=True)
    explore_parser.add_argument("--variant", required=True)
    status_parser = sub.add_parser("session-status")
    status_parser.add_argument("--run-id", required=True)
    control_parser = sub.add_parser("control")
    control_parser.add_argument("--run-id", required=True)
    control_parser.add_argument("--action", required=True, help="JSON key/tap/look/screenshot action")
    control_parser.add_argument("--output", help="New PNG path for screenshot action")
    route_parser = sub.add_parser("export-route")
    route_parser.add_argument("--run-directory", required=True)
    route_parser.add_argument("--output", required=True)
    snapshot_parser = sub.add_parser("snapshot-workload")
    snapshot_parser.add_argument("--source", required=True)
    snapshot_parser.add_argument("--output", required=True)
    backup_parser = sub.add_parser("backup")
    backup_parser.add_argument("--destination", required=True)
    prepare = sub.add_parser("prepare-container")
    prepare.add_argument("--source", required=True, help="Existing STEAM_ or CUSTOM_GAME_ container ID")
    prepare.add_argument("--container", required=True, help="New BENCHMARK_ sandbox ID")
    comparison = sub.add_parser("compare")
    comparison.add_argument("--baseline", required=True)
    comparison.add_argument("--candidate", required=True)
    comparison.add_argument("--component", choices=["dxvk", "fex", "turnip", "proton", "lsfg"], default="dxvk")
    comparison.add_argument("--baseline-variant")
    comparison.add_argument("--candidate-variant")
    args = parser.parse_args()
    try:
        if args.command == "dispatch-build":
            result = dispatch_build(args.manifest, args.output, args.execute)
        elif args.command == "build-set":
            result = resolve_build_set(args.manifest, args.output, args.download)
        elif args.command == "compare":
            from pathlib import Path
            root = Path(args.baseline)
            result = compare_performance(args.baseline, args.candidate, args.component,
                args.baseline_variant, args.candidate_variant) if (root / "campaign.json").exists() or (root / "metrics.json").exists() else compare(args.baseline, args.candidate)
        elif args.command == "package-dxvk":
            result = package_dxvk(args.directory, args.version, args.version_code, args.output)
        elif args.command == "export-route":
            result = export_route(args.run_directory, args.output)
        elif args.command == "fetch-component":
            result = fetch(args.repo, args.run_id, args.artifact, args.filename, args.component,
                           args.version, args.sha256, args.source_commit, args.destination)
        elif args.command == "validate-profile":
            version = json.loads(__import__("pathlib").Path(args.profile).read_text()).get("schema_version")
            result = load_run_profile(args.profile) if version == 2 else load_profile(args.profile)
        else:
            if not args.serial:
                raise LabError("--serial is required for all device operations")
            device = Device(args.serial)
            if args.command == "device-info":
                result = device.info()
            elif args.command == "backup":
                result = backup(device, args.destination)
            elif args.command == "run":
                result = run(device, load_run_profile(args.profile), args.results, args.variant, args.repeat)
            elif args.command == "explore":
                profile = load_run_profile(args.profile)
                if profile["workload"]["kind"] != "exploration" or args.variant not in profile["variants"]:
                    raise LabError("Explore requires an exploration profile and known variant")
                with device.lease():
                    result = trial(device, profile, args.variant, 0, "cold", uuid.uuid4().hex, args.results)
            elif args.command == "test-recovery":
                result = probe(device, args.container, args.results)
            elif args.command == "session-status":
                result = session_status(device, args.run_id)
            elif args.command == "control":
                result = control_command(device, args.run_id, json.loads(args.action), args.output)
            elif args.command == "snapshot-workload":
                result = snapshot(device, args.source, args.output)
            elif args.command == "install":
                package = validate_package(args.package, args.component, args.version, args.sha256)
                with device.lease():
                    device.assert_idle_debug()
                    staged = f"cache/lab-component-{uuid.uuid4().hex}.wcp"
                    try:
                        with open(package["path"], "rb") as stream:
                            device.call("shell", "-T", "run-as", PACKAGE, "sh", "-c", f"'cat > {staged}'", stream=stream, timeout=180)
                        result = execute(device, {"operation": "install", "package": staged, **package}, args.results)
                    finally:
                        device.call("shell", "run-as", PACKAGE, "rm", "-f", staged)
            elif args.command == "install-driver":
                package = validate_driver(args.package, args.sha256)
                with device.lease():
                    device.assert_idle_debug()
                    staged = f"cache/lab-driver-{uuid.uuid4().hex}.zip"
                    try:
                        with open(package["path"], "rb") as stream:
                            device.call("shell", "-T", "run-as", PACKAGE, "sh", "-c", f"'cat > {staged}'", stream=stream, timeout=180)
                        result = execute(device, {"operation": "install-driver", "package": staged, **package}, args.results)
                    finally:
                        device.call("shell", "run-as", PACKAGE, "rm", "-f", staged)
            elif args.command == "verify-config":
                profile = load_profile(args.profile)
                with device.lease():
                    result = execute(device, {"operation": "config-roundtrip", "container_id": profile["game"]["container_id"],
                                             "config": profile["config"]}, args.results, profile)
            elif args.command == "prepare-container":
                import re
                if not re.fullmatch(r"(?:STEAM|CUSTOM_GAME)_[0-9]+", args.source) or not re.fullmatch(r"BENCHMARK_[A-Za-z0-9_-]+", args.container):
                    raise LabError("Preparation requires a STEAM_/CUSTOM_GAME_ source and a new BENCHMARK_ ID")
                with device.lease():
                    result = execute(device, {"operation": "prepare", "source_container_id": args.source,
                                             "container_id": args.container}, args.results)
            else:
                with device.lease():
                    result = execute(device, {"operation": "recover" if args.command == "recover" else "list"}, args.results)
        print(json.dumps(result, indent=2, allow_nan=False))
        return 0
    except (LabError, OSError, ValueError, KeyError, TypeError) as exc:
        print(json.dumps({"status": "failed", "error": str(exc)}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
