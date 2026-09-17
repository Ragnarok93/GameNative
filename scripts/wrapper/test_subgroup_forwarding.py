#!/usr/bin/env python3
import argparse
import pathlib
import re
import struct
import sys

FORWARD_CALL = "pdevice->dispatch_table.GetPhysicalDeviceProperties2(\n      pdevice->dispatch_handle, pProperties);"
FIELDS = {
    "vk11": ("subgroupSupportedStages", "subgroupSupportedOperations"),
    "subgroup": ("supportedStages", "supportedOperations"),
}
CASES = {
    "vk11": "VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_PROPERTIES",
    "subgroup": "VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES",
}
FIXTURES = (
    ("turnip-a6xx-full", 128, 0x20, 0x3F, 0),
    ("missing-ballot", 64, 0x20, 0x03, 0),
    ("zero-mask", 32, 0x00, 0x00, 0),
)


def function_body(text: str) -> str:
    start = text.find("wrapper_GetPhysicalDeviceProperties2(")
    end = text.find("wrapper_GetPhysicalDeviceImageFormatProperties(", start)
    if start < 0 or end < 0:
        raise AssertionError("could not isolate wrapper_GetPhysicalDeviceProperties2")
    body = text[start:end]
    if FORWARD_CALL not in body:
        raise AssertionError("wrapper no longer forwards GetPhysicalDeviceProperties2 before post-processing")
    return body


def case_body(body: str, case_name: str) -> str:
    marker = f"case {case_name}:"
    start = body.find(marker)
    if start < 0:
        raise AssertionError(f"missing {case_name} case")
    next_case = body.find("\n      case ", start + len(marker))
    default = body.find("\n      default:", start + len(marker))
    candidates = [p for p in (next_case, default) if p >= 0]
    end = min(candidates) if candidates else len(body)
    return body[start:end]


def assignment_present(case: str, field: str) -> bool:
    return re.search(rf"(?:->|\.){re.escape(field)}\s*=", case) is not None


def modeled_wrapper_bytes(source_case: str, route: str, fixture) -> tuple[bytes, bytes]:
    _name, subgroup_size, stages, operations, quad = fixture
    before = struct.pack("<IIIII", 0x51A6F00D, subgroup_size, stages, operations, quad)
    out_stages, out_operations = stages, operations
    stage_field, operation_field = FIELDS[route]
    if assignment_present(source_case, stage_field):
        out_stages = 0
    if assignment_present(source_case, operation_field):
        out_operations = 0
    after = struct.pack("<IIIII", 0x51A6F00D, subgroup_size, out_stages, out_operations, quad)
    return before, after


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify that the GameNative Vulkan wrapper preserves driver subgroup properties.")
    parser.add_argument("source", type=pathlib.Path)
    args = parser.parse_args()

    text = args.source.read_text(encoding="utf-8")
    body = function_body(text)

    failures = []
    for route, case_name in CASES.items():
        case = case_body(body, case_name)
        stage_field, operation_field = FIELDS[route]

        for field in (stage_field, operation_field):
            if assignment_present(case, field):
                failures.append(f"{case_name} writes forwarded driver field {field}")

        for fixture in FIXTURES:
            before, after = modeled_wrapper_bytes(case, route, fixture)
            if before != after:
                failures.append(
                    f"{route}/{fixture[0]} subgroup forwarding changed driver bytes: "
                    f"{before.hex()} -> {after.hex()}"
                )

    if failures:
        print("SUBGROUP_FORWARDING_REGRESSION", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("subgroup forwarding contract preserved for full, missing-BALLOT, and zero-mask fake-driver fixtures")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
