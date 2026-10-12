#!/usr/bin/env python3
"""Phase 1: validate, install pinned content, and verify reversible configuration."""
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Explicit ADB serial; never select a device implicitly")
    parser.add_argument("--results", default="benchmark-results")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("device-info")
    sub.add_parser("list-components")
    validate = sub.add_parser("validate-profile")
    validate.add_argument("profile")
    install = sub.add_parser("install")
    install.add_argument("--component", choices=["dxvk", "fex", "turnip", "proton"], required=True)
    install.add_argument("--package", required=True)
    install.add_argument("--version", required=True)
    install.add_argument("--sha256", required=True)
    config = sub.add_parser("verify-config")
    config.add_argument("--profile", required=True)
    sub.add_parser("recover")
    backup_parser = sub.add_parser("backup")
    backup_parser.add_argument("--destination", required=True)
    prepare = sub.add_parser("prepare-container")
    prepare.add_argument("--source", required=True, help="Existing STEAM_ or CUSTOM_GAME_ container ID")
    prepare.add_argument("--container", required=True, help="New BENCHMARK_ sandbox ID")
    comparison = sub.add_parser("compare")
    comparison.add_argument("--baseline", required=True)
    comparison.add_argument("--candidate", required=True)
    args = parser.parse_args()
    try:
        if args.command == "compare":
            result = compare(args.baseline, args.candidate)
        elif args.command == "validate-profile":
            result = load_profile(args.profile)
        else:
            if not args.serial:
                raise LabError("--serial is required for all device operations")
            device = Device(args.serial)
            if args.command == "device-info":
                result = device.info()
            elif args.command == "backup":
                result = backup(device, args.destination)
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
