#!/usr/bin/env python3
"""Apply Candidate B6 Turnip/IR3 compute-shader profiling at the real guest handoff.

B6 is diagnostic-only. The retained B4 source tree remains unchanged in Git; CI
patches XServerScreen.kt immediately before its final EnvVars object is assigned
to GuestProgramLauncherComponent. This guarantees Turnip sees the same variable
that the launch log and ProcessHelper environment will show on-device.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

DEFAULT_SOURCE = Path(
    "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt"
)
ENV_MARKER = 'envVars.put("IR3_SHADER_DEBUG", "cs")'
LOG_MARKER = 'Timber.i("B6_IR3_PROFILE armed IR3_SHADER_DEBUG=cs")'
ANCHOR = "    guestProgramLauncherComponent.envVars = envVars\n"
REPLACEMENT = """    // Candidate B6 profiling only: enable Turnip/IR3 compute-shader disassembly
    // on the exact final EnvVars object handed to the guest process.
    envVars.put(\"IR3_SHADER_DEBUG\", \"cs\")
    Timber.i(\"B6_IR3_PROFILE armed IR3_SHADER_DEBUG=cs\")
    guestProgramLauncherComponent.envVars = envVars
"""


def patch_source(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    env_count = text.count(ENV_MARKER)
    log_count = text.count(LOG_MARKER)

    if env_count == 1 and log_count == 1:
        print(f"Candidate B6 final-handoff IR3 profile already applied: {path}")
        return False
    if env_count != 0 or log_count != 0:
        raise RuntimeError(
            f"partial/unexpected B6 marker state env={env_count} log={log_count}: {path}"
        )

    anchor_count = text.count(ANCHOR)
    if anchor_count != 1:
        raise RuntimeError(
            f"Candidate B6 final guest handoff: expected 1 anchor, found {anchor_count}; "
            "refusing to patch an unknown XServerScreen layout"
        )

    text = text.replace(ANCHOR, REPLACEMENT, 1)
    if text.count(ENV_MARKER) != 1 or text.count(LOG_MARKER) != 1:
        raise RuntimeError("Candidate B6 postcondition failed")
    if text.index(ENV_MARKER) > text.index("guestProgramLauncherComponent.envVars = envVars"):
        raise RuntimeError("Candidate B6 env was not inserted before the guest handoff")

    path.write_text(text, encoding="utf-8")
    print(f"Candidate B6 IR3 final guest handoff applied: {path}")
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    args = parser.parse_args(argv)
    try:
        patch_source(args.source)
    except Exception as exc:
        print(f"candidate-b6-ir3-profile: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
