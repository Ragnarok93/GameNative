#!/usr/bin/env python3
import pathlib
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = REPO / "scripts" / "apply-candidate-b6-ir3-profile.py"

SOURCE = '''object LsfgVkManager {
    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean {
        if (!isSupported(container) || !isFrameGenerationRequested(container)) {
            return false
        }
        val processExecutable = targetExecutable(container)
        if (processExecutable == null) {
            return false
        }

        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
        return true
    }
}
'''


def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        source = pathlib.Path(td) / "LsfgVkManager.kt"
        source.write_text(SOURCE, encoding="utf-8")
        subprocess.run(
            [sys.executable, str(SCRIPT), "--source", str(source)],
            check=True,
        )
        patched = source.read_text(encoding="utf-8")
        assert 'envVars.put("IR3_SHADER_DEBUG", "cs")' in patched
        assert patched.index('envVars.put("IR3_SHADER_DEBUG", "cs")') > patched.index('processExecutable == null')
        assert patched.index('envVars.put("IR3_SHADER_DEBUG", "cs")') < patched.index('envVars.put(ENV_CONFIG')

        # The build hook may be invoked more than once; it must be idempotent.
        subprocess.run(
            [sys.executable, str(SCRIPT), "--source", str(source)],
            check=True,
        )
        patched_twice = source.read_text(encoding="utf-8")
        assert patched_twice.count('envVars.put("IR3_SHADER_DEBUG", "cs")') == 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
