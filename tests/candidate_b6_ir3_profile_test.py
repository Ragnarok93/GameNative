#!/usr/bin/env python3
import pathlib
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = REPO / "scripts" / "apply-candidate-b6-ir3-profile.py"

SOURCE = '''private fun launchGuest() {
    val envVars = EnvVars()
    envVars.putAll(container.envVars)

    guestProgramLauncherComponent.envVars = envVars

    Timber.i("Env Vars (Final Guest): ${envVars.toString()}")
}
'''


def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        source = pathlib.Path(td) / "XServerScreen.kt"
        source.write_text(SOURCE, encoding="utf-8")

        subprocess.run(
            [sys.executable, str(SCRIPT), "--source", str(source)],
            check=True,
        )
        patched = source.read_text(encoding="utf-8")
        env_marker = 'envVars.put("IR3_SHADER_DEBUG", "cs")'
        log_marker = 'Timber.i("B6_IR3_PROFILE armed IR3_SHADER_DEBUG=cs")'
        handoff = "guestProgramLauncherComponent.envVars = envVars"

        assert patched.count(env_marker) == 1
        assert patched.count(log_marker) == 1
        assert patched.index(env_marker) < patched.index(handoff)
        assert patched.index(log_marker) < patched.index(handoff)

        # CI may invoke the build hook more than once; it must remain idempotent.
        subprocess.run(
            [sys.executable, str(SCRIPT), "--source", str(source)],
            check=True,
        )
        patched_twice = source.read_text(encoding="utf-8")
        assert patched_twice.count(env_marker) == 1
        assert patched_twice.count(log_marker) == 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
