#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    text = text.replace(old, new, 1)


def sub_once(pattern: str, repl: str, label: str, flags: int = 0) -> None:
    global text
    text, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{label}: expected one regex match, found {count}")


for dead_import in (
    "import android.os.Handler\n",
    "import android.os.Looper\n",
    "import android.view.Choreographer\n",
    "import android.view.WindowManager\n",
):
    text = text.replace(dead_import, "")

replace_once(
    '    // FPS limiter extras (owned by XServerScreen)\n'
    '    private const val EXTRA_FPS_LIMITER_ENABLED = "fpsLimiterEnabled"\n'
    '    private const val EXTRA_FPS_LIMITER_TARGET = "fpsLimiterTarget"\n\n',
    '',
    'remove LSFG-side FPS limiter extras',
)

text = re.sub(
    r'    private const val RUNTIME_VERSION =\n        "[^"]+"',
    '    private const val RUNTIME_VERSION =\n'
    '        "v1.3.6-android-arm64-v8a-fixed-governor-eca8f7a3-r1"',
    text,
    count=1,
)

sub_once(
    r'\n    /\*\*\n     \* Base fps cap for the layer.*?\n    fun fpsLimit\(container: Container\): Int \{.*?\n    \}\n',
    '\n',
    'remove obsolete LSFG fpsLimit bridge',
    re.S,
)

sub_once(
    r'    // ---- Vsync clock .*?\n    /\*\*\n     \* Read the fps',
    '    // ---- Runtime stats ----------------------------------------------------\n\n'
    '    private val statsReadExecutor by lazy {\n'
    '        Executors.newSingleThreadExecutor { r -> Thread(r, "lsfg-stats").apply { isDaemon = true } }\n'
    '    }\n\n'
    '    /**\n'
    '     * Read the fps',
    'remove dead Choreographer/vsync publisher',
    re.S,
)
text = text.replace('vsyncWriteExecutor', 'statsReadExecutor')

replace_once(
    '                fpsLimit = fpsLimit(container),\n'
    '                presentMode = presentMode(container),\n',
    '                fixedGovernor = frameGenActive,\n'
    '                displayRefreshHz = 0,\n'
    '                presentMode = presentMode(container),\n',
    'launch config fixed-governor fields',
)

replace_once(
    '        performanceMode: Boolean,\n'
    '        fpsLimit: Int,\n'
    '        presentMode: String,\n',
    '        performanceMode: Boolean,\n'
    '        fixedGovernor: Boolean,\n'
    '        displayRefreshHz: Int,\n'
    '        presentMode: String,\n',
    'config builder signature',
)

replace_once(
    '                appendLine("hdr_mode = false")\n'
    '                appendLine("fps_limit = ${fpsLimit.coerceAtLeast(0)}")\n'
    '                appendLine("experimental_present_mode = ${tomlString(presentMode)}")\n',
    '                appendLine("hdr_mode = false")\n'
    '                appendLine("fixed_governor = ${if (fixedGovernor && effectiveMultiplier >= 2) "true" else "false"}")\n'
    '                appendLine("display_refresh_hz = ${displayRefreshHz.coerceAtLeast(0)}")\n'
    '                appendLine("experimental_present_mode = ${tomlString(presentMode)}")\n',
    'config builder body',
)

text = text.replace(
    '     * A temporary fpsLimitOverride is deliberately not persisted in container\n'
    '     * extras, so adaptive caps can be applied without rewriting user settings.\n',
    '     * Source-frame limiting remains owned by XServerScreen. The LSFG config\n'
    '     * carries only fixed-mode generation policy.\n',
)

replace_once(
    '        flowScale: Float,\n'
    '        performanceMode: Boolean,\n'
    '        fpsLimitOverride: Int? = null,\n'
    '    ): Boolean {\n',
    '        flowScale: Float,\n'
    '        performanceMode: Boolean,\n'
    '    ): Boolean {\n',
    'runtime update signature',
)

replace_once(
    '            val effectiveFpsLimit = (fpsLimitOverride ?: fpsLimit(container)).coerceAtLeast(0)\n',
    '',
    'remove runtime fps override',
)

replace_once(
    '                performanceMode = performanceMode,\n'
    '                fpsLimit = effectiveFpsLimit,\n'
    '                presentMode = presentMode(container),\n',
    '                performanceMode = performanceMode,\n'
    '                fixedGovernor = frameGenActive,\n'
    '                displayRefreshHz = 0,\n'
    '                presentMode = presentMode(container),\n',
    'runtime config fixed-governor fields',
)

replace_once(
    '                    "Hot-reloaded conf.toml: enabled=%s, multiplier=%d, flowScale=%.2f, perf=%s, fpsLimit=%d",\n'
    '                    frameGenActive,\n'
    '                    multiplier,\n'
    '                    flowScale,\n'
    '                    performanceMode,\n'
    '                    effectiveFpsLimit,\n',
    '                    "Hot-reloaded conf.toml: enabled=%s, multiplier=%d, flowScale=%.2f, perf=%s, fixedGovernor=%s",\n'
    '                    frameGenActive,\n'
    '                    multiplier,\n'
    '                    flowScale,\n'
    '                    performanceMode,\n'
    '                    frameGenActive,\n',
    'runtime config log',
)

for obsolete in (
    'fpsLimit',
    'fps_limit',
    'fpsLimitOverride',
    'EXTRA_FPS_LIMITER_',
    'startVsyncClock',
    'stopVsyncClock',
    'vsync.txt',
    'Choreographer',
    'WindowManager',
):
    if obsolete in text:
        raise RuntimeError(f'vestigial LSFG integration token remains in manager: {obsolete}')

for required in (
    'fixed_governor',
    'display_refresh_hz',
    'statsReadExecutor',
    'fixedGovernor = frameGenActive',
):
    if required not in text:
        raise RuntimeError(f'missing fixed-governor integration token: {required}')

PATH.write_text(text, encoding="utf-8")
