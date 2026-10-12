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
            elif args.command == "install":
                package = validate_package(args.package, args.component, args.version, args.sha256)
                with device.lease():
                    device.assert_idle_debug()
                    staged = f"cache/lab-component-{uuid.uuid4().hex}.wcp"
                    try:
                        with open(package["path"], "rb") as stream:
                            device.call("exec-out", "run-as", PACKAGE, "sh", "-c", f"'cat > {staged}'", stream=stream, timeout=180)
                        result = execute(device, {"operation": "install", "package": staged, **package}, args.results)
                    finally:
                        device.call("shell", "run-as", PACKAGE, "rm", "-f", staged)
            elif args.command == "verify-config":
                profile = load_profile(args.profile)
                with device.lease():
                    result = execute(device, {"operation": "config-roundtrip", "container_id": profile["game"]["container_id"],
                                             "config": profile["config"]}, args.results, profile)
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
