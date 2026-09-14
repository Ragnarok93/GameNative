#!/usr/bin/env python3
"""Apply the Candidate B6 Turnip/IR3 compute-shader profiling env hook.

This is intentionally a build-time diagnostic transform. It keeps the retained
Candidate B4 source tree unchanged while producing a B6 APK that asks IR3 to
print compute-shader disassembly before the guest Vulkan driver initializes.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

DEFAULT_SOURCE = Path("app/src/main/java/app/gamenative/utils/LsfgVkManager.kt")
MARKER = 'envVars.put("IR3_SHADER_DEBUG", "cs")'
NEEDLE = """        val processExecutable = targetExecutable(container)
        if (processExecutable == null) {
            Timber.tag(TAG).w(\"LSFG layer armed but target executable could not be resolved\")
            return false
        }

        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
"""
SYNTHETIC_NEEDLE = """        val processExecutable = targetExecutable(container)
        if (processExecutable == null) {
            return false
        }

        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
"""
INSERTION = """        val processExecutable = targetExecutable(container)
        if (processExecutable == null) {
{failure_body}        }

        // Candidate B6 profiling only: request IR3 disassembly for compute shaders.
        // This is applied after all LSFG activation gates so disabled/non-armed
        // launches retain their caller-provided driver environment unchanged.
        envVars.put(\"IR3_SHADER_DEBUG\", \"cs\")
        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
"""


def patch_source(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    marker_count = text.count(MARKER)
    if marker_count == 1:
        print(f"Candidate B6 IR3 profile already applied: {path}")
        return False
    if marker_count != 0:
        raise RuntimeError(f"unexpected B6 marker count {marker_count}: {path}")

    if text.count(NEEDLE) == 1:
        failure_body = (
            '            Timber.tag(TAG).w("LSFG layer armed but target executable could not be resolved")\n'
            "            return false\n"
        )
        replacement = INSERTION.format(failure_body=failure_body)
        text = text.replace(NEEDLE, replacement, 1)
    elif text.count(SYNTHETIC_NEEDLE) == 1:
        failure_body = "            return false\n"
        replacement = INSERTION.format(failure_body=failure_body)
        text = text.replace(SYNTHETIC_NEEDLE, replacement, 1)
    else:
        raise RuntimeError(
            "Candidate B6 source anchor mismatch; refusing to patch an unknown LsfgVkManager layout"
        )

    if text.count(MARKER) != 1:
        raise RuntimeError("Candidate B6 postcondition failed: IR3 compute debug env not inserted exactly once")

    path.write_text(text, encoding="utf-8")
    print(f"Candidate B6 IR3 compute profiling env applied: {path}")
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    args = parser.parse_args(argv)

    try:
        patch_source(args.source)
    except Exception as exc:  # fail closed on source drift
        print(f"candidate-b6-ir3-profile: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
