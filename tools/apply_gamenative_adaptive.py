#!/usr/bin/env python3
from pathlib import Path
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
NATIVE_SHA = os.environ.get("LSFG_SHA", "").strip()
if not re.fullmatch(r"[0-9a-f]{40}", NATIVE_SHA):
    raise SystemExit("LSFG_SHA must be an exact 40-character commit SHA")


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one literal match, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, repl: str, label: str, flags=re.S) -> str:
    new, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one regex match, found {count}")
    return new

manager_rel = "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
manager = read(manager_rel)
for dead_import in (
    "import android.os.Handler\n",
    "import android.os.Looper\n",
    "import android.view.Choreographer\n",
    "import android.view.WindowManager\n",
):
    manager = manager.replace(dead_import, "")
manager = regex_once(
    manager,
    r'private const val RUNTIME_VERSION =\s*\n\s*"[^"]+"',
    'private const val RUNTIME_VERSION =\n        "v1.3.6-android-arm64-v8a-adaptive-' + NATIVE_SHA + '"',
    "runtime version",
)
manager = replace_once(
    manager,
    '''    const val EXTRA_PRESENT_MODE = "lsfgPresentMode"\n\n    // GameNative owns real/source-frame pacing. conf.toml must stay\n    // uncapped; Adaptive output targeting is supplied only by the separate\n    // gamenative-adaptive.toml overlay consumed by the native layer.\n    private const val LSFG_CONF_FPS_LIMIT = 0\n''',
    '''    const val EXTRA_PRESENT_MODE = "lsfgPresentMode"\n    const val EXTRA_FRAMEGEN_MODE = "lsfgFramegenMode"\n    const val EXTRA_FIXED_MULTIPLIER = "lsfgFixedMultiplier"\n    const val EXTRA_ADAPTIVE_TARGET_FPS = "lsfgAdaptiveTargetFps"\n\n    const val MODE_FIXED = "fixed"\n    const val MODE_ADAPTIVE = "adaptive"\n    const val MIN_ADAPTIVE_TARGET_FPS = 30\n    const val MAX_ADAPTIVE_TARGET_FPS = 144\n    const val DEFAULT_ADAPTIVE_TARGET_FPS = 60\n''',
    "manager mode extras",
)
manager = replace_once(
    manager,
    '''    private const val STATS_RELATIVE_PATH = ".config/lsfg-vk/stats.txt"\n    private const val STATS_FRESHNESS_MS = 2000L\n''',
    '''    private const val STATS_RELATIVE_PATH = ".config/lsfg-vk/stats.txt"\n    private const val STATS_FRESHNESS_MS = 2000L\n    private val statsReadExecutor by lazy {\n        Executors.newSingleThreadExecutor { r -> Thread(r, "lsfg-stats").apply { isDaemon = true } }\n    }\n''',
    "stats executor",
)
manager = replace_once(
    manager,
    '''    fun multiplier(container: Container): Int {\n        val raw = container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2\n        return if (raw == 0) 0 else raw.coerceIn(2, 4)\n    }\n''',
    '''    fun multiplier(container: Container): Int {\n        val raw = container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2\n        return if (raw == 0) 0 else raw.coerceIn(2, 4)\n    }\n\n    fun generationMode(container: Container): String =\n        container.getExtra(EXTRA_FRAMEGEN_MODE, MODE_FIXED)\n            .lowercase(Locale.US)\n            .takeIf { it == MODE_FIXED || it == MODE_ADAPTIVE }\n            ?: MODE_FIXED\n\n    fun fixedMultiplier(container: Container): Int =\n        (container.getExtra(EXTRA_FIXED_MULTIPLIER, "2").toIntOrNull() ?: 2).coerceIn(2, 4)\n\n    fun adaptiveTargetFps(container: Container): Int =\n        (container.getExtra(EXTRA_ADAPTIVE_TARGET_FPS, DEFAULT_ADAPTIVE_TARGET_FPS.toString())\n            .toIntOrNull() ?: DEFAULT_ADAPTIVE_TARGET_FPS)\n            .coerceIn(MIN_ADAPTIVE_TARGET_FPS, MAX_ADAPTIVE_TARGET_FPS)\n''',
    "manager mode readers",
)
manager = regex_once(
    manager,
    r'''\n    // ---- Vsync clock -+.*?(?=\n    /\*\*\n     \* Read the fps the layer actually presented)''',
    "\n",
    "remove vsync publisher",
)
manager = manager.replace("vsyncWriteExecutor.execute", "statsReadExecutor.execute")
old_write = '''            val savedMultiplier = multiplier(container)\n            val frameGenActive = frameGenerationActive(container) && processExecutable != null\n            val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)\n            val configText = buildConfigToml(\n                dllPath = dllPath,\n                processExecutable = processExecutable,\n                enabled = frameGenActive,\n                multiplier = if (frameGenActive) savedMultiplier else 1,\n                flowScale = flowScale(container),\n                performanceMode = performanceMode(container),\n                fpsLimit = LSFG_CONF_FPS_LIMIT,\n                presentMode = presentMode(container),\n            )'''
new_write = '''            val savedMultiplier = multiplier(container)\n            val frameGenActive = frameGenerationActive(container) && processExecutable != null\n            val adaptive = frameGenActive && generationMode(container) == MODE_ADAPTIVE\n            val runtimeMultiplier = if (adaptive) 4 else savedMultiplier\n            val adaptiveTarget = if (adaptive) adaptiveTargetFps(container) else 0\n            val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)\n            val configText = buildConfigToml(\n                dllPath = dllPath,\n                processExecutable = processExecutable,\n                enabled = frameGenActive,\n                multiplier = if (frameGenActive) runtimeMultiplier else 1,\n                flowScale = flowScale(container),\n                performanceMode = performanceMode(container),\n                adaptiveFramegen = adaptive,\n                fpsLimit = adaptiveTarget,\n                presentMode = presentMode(container),\n            )'''
manager = replace_once(manager, old_write, new_write, "launch config")
manager = replace_once(
    manager,
    '''        performanceMode: Boolean,\n        fpsLimit: Int,\n        presentMode: String,\n''',
    '''        performanceMode: Boolean,\n        adaptiveFramegen: Boolean,\n        fpsLimit: Int,\n        presentMode: String,\n''',
    "build config signature",
)
manager = replace_once(
    manager,
    '''                appendLine("hdr_mode = false")\n                appendLine("fps_limit = ${fpsLimit.coerceAtLeast(0)}")\n''',
    '''                appendLine("hdr_mode = false")\n                appendLine("adaptive_framegen = ${if (adaptiveFramegen) "true" else "false"}")\n                appendLine("fps_limit = ${fpsLimit.coerceAtLeast(0)}")\n''',
    "adaptive config lines",
)
manager = regex_once(
    manager,
    r'''\n    /\*\*\n     \* Request one deliberate native-context reload.*?(?=\n    /\*\*\n     \* Update conf\.toml while the container is running\.)''',
    "\n",
    "remove overlay reload helper",
)
manager = manager.replace(
    "     * GameNative's real/source FPS limiter is intentionally not forwarded here;\n"
    "     * Adaptive output targeting lives exclusively in gamenative-adaptive.toml.\n",
    "     * GameNative's real/source FPS limiter is intentionally not forwarded here.\n"
    "     * In Adaptive mode fps_limit is the requested output target only.\n",
)
old_runtime = '''            val frameGenActive = enabled && multiplier >= 2 &&\n                dllPath != null && processExecutable != null\n            val effectiveFpsLimit = LSFG_CONF_FPS_LIMIT\n            val configText = buildConfigToml(\n                dllPath = dllPath,\n                processExecutable = processExecutable,\n                enabled = frameGenActive,\n                multiplier = if (frameGenActive) multiplier.coerceIn(2, 4) else 1,\n                flowScale = flowScale.coerceIn(0.25f, 1.0f),\n                performanceMode = performanceMode,\n                fpsLimit = effectiveFpsLimit,\n                presentMode = presentMode(container),\n            )'''
new_runtime = '''            val frameGenActive = enabled && multiplier >= 2 &&\n                dllPath != null && processExecutable != null\n            val adaptive = frameGenActive && generationMode(container) == MODE_ADAPTIVE\n            val effectiveMultiplier = if (adaptive) 4 else multiplier.coerceIn(2, 4)\n            val effectiveFpsLimit = if (adaptive) adaptiveTargetFps(container) else 0\n            val configText = buildConfigToml(\n                dllPath = dllPath,\n                processExecutable = processExecutable,\n                enabled = frameGenActive,\n                multiplier = if (frameGenActive) effectiveMultiplier else 1,\n                flowScale = flowScale.coerceIn(0.25f, 1.0f),\n                performanceMode = performanceMode,\n                adaptiveFramegen = adaptive,\n                fpsLimit = effectiveFpsLimit,\n                presentMode = presentMode(container),\n            )'''
manager = replace_once(manager, old_runtime, new_runtime, "runtime config")
manager = manager.replace(
    '"Hot-reloaded conf.toml: enabled=%s, multiplier=%d, flowScale=%.2f, perf=%s, fpsLimit=%d",',
    '"Hot-reloaded conf.toml: enabled=%s, adaptive=%s, multiplier=%d, flowScale=%.2f, perf=%s, fpsLimit=%d",',
)
manager = manager.replace(
    '''                    frameGenActive,\n                    multiplier,\n                    flowScale,\n                    performanceMode,\n                    effectiveFpsLimit,\n''',
    '''                    frameGenActive,\n                    adaptive,\n                    effectiveMultiplier,\n                    flowScale,\n                    performanceMode,\n                    effectiveFpsLimit,\n''',
)
for forbidden in ("gamenative-adaptive.toml", "LSFG_CONF_FPS_LIMIT", "startVsyncClock", "stopVsyncClock", "vsync.txt"):
    if forbidden in manager:
        raise RuntimeError(f"manager vestige remains: {forbidden}")
write(manager_rel, manager)

helper_rel = "app/src/main/java/app/gamenative/utils/LsfgQuickMenuHelper.kt"
helper = r'''package app.gamenative.utils

import com.winlator.container.Container
import java.util.Locale

/** Quick Menu LSFG state persistence and runtime publication. */
object LsfgQuickMenuHelper {
    enum class FrameGenerationMode { FIXED, ADAPTIVE }

    data class Settings(
        val multiplier: Int,
        val flowScale: Float,
        val performanceMode: Boolean,
    )

    fun isAvailable(container: Container): Boolean {
        LsfgRuntimeGate.configure(container.rootDir)
        return LsfgVkManager.isAvailable(container)
    }

    fun readSettings(container: Container): Settings = Settings(
        multiplier = LsfgVkManager.multiplier(container),
        flowScale = LsfgVkManager.flowScale(container),
        performanceMode = LsfgVkManager.performanceMode(container),
    )

    fun generationMode(container: Container): FrameGenerationMode =
        if (LsfgVkManager.generationMode(container) == LsfgVkManager.MODE_ADAPTIVE) {
            FrameGenerationMode.ADAPTIVE
        } else FrameGenerationMode.FIXED

    fun fixedMultiplier(container: Container): Int = LsfgVkManager.fixedMultiplier(container)
    fun adaptiveTargetFps(container: Container): Int = LsfgVkManager.adaptiveTargetFps(container)

    fun setGenerationMode(container: Container, mode: FrameGenerationMode) {
        container.putExtra(
            LsfgVkManager.EXTRA_FRAMEGEN_MODE,
            if (mode == FrameGenerationMode.ADAPTIVE) LsfgVkManager.MODE_ADAPTIVE else LsfgVkManager.MODE_FIXED,
        )
        container.saveData()
    }

    fun setFixedMultiplier(container: Container, multiplier: Int) {
        container.putExtra(LsfgVkManager.EXTRA_FIXED_MULTIPLIER, multiplier.coerceIn(2, 4).toString())
        container.saveData()
    }

    fun setAdaptiveTargetFps(container: Container, targetFps: Int) {
        container.putExtra(
            LsfgVkManager.EXTRA_ADAPTIVE_TARGET_FPS,
            targetFps.coerceIn(LsfgVkManager.MIN_ADAPTIVE_TARGET_FPS, LsfgVkManager.MAX_ADAPTIVE_TARGET_FPS).toString(),
        )
        container.saveData()
    }

    fun presentMode(container: Container): String = LsfgVkManager.presentMode(container)

    fun applyPresentMode(container: Container, mode: String) {
        val sanitized = mode.takeIf { it == "mailbox" || it == "fifo" } ?: "mailbox"
        container.putExtra(LsfgVkManager.EXTRA_PRESENT_MODE, sanitized)
        container.saveData()
        publishRuntimeConfig(container, readSettings(container))
    }

    fun sanitizeMultiplier(multiplier: Int): Int = if (multiplier < 2) 0 else multiplier.coerceIn(2, 4)
    fun sanitizeFlowScale(flowScale: Float): Float = flowScale.coerceIn(0.25f, 1.0f)

    fun applySettings(container: Container, settings: Settings) {
        val multiplier = sanitizeMultiplier(settings.multiplier)
        val flowScale = sanitizeFlowScale(settings.flowScale)
        container.putExtra(LsfgVkManager.EXTRA_MULTIPLIER, multiplier.toString())
        container.putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale))
        container.putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, settings.performanceMode.toString())
        container.saveData()
        publishRuntimeConfig(container, settings.copy(multiplier = multiplier, flowScale = flowScale))
    }

    private fun publishRuntimeConfig(container: Container, settings: Settings) {
        val enabled = sanitizeMultiplier(settings.multiplier) >= 2
        val adaptive = enabled && generationMode(container) == FrameGenerationMode.ADAPTIVE
        val effectiveMultiplier = when {
            !enabled -> 2
            adaptive -> 4
            else -> sanitizeMultiplier(settings.multiplier).coerceIn(2, 4)
        }
        LsfgVkManager.updateConfigAtRuntime(
            container,
            enabled,
            effectiveMultiplier,
            sanitizeFlowScale(settings.flowScale),
            settings.performanceMode,
        )
    }
}
'''
write(helper_rel, helper)

quick_rel = "app/src/main/java/app/gamenative/ui/component/QuickMenu.kt"
quick = read(quick_rel)
if "import androidx.compose.material3.Slider\n" not in quick:
    quick = replace_once(
        quick,
        "import androidx.compose.material3.LinearProgressIndicator\n",
        "import androidx.compose.material3.LinearProgressIndicator\nimport androidx.compose.material3.Slider\n",
        "slider import",
    )
new_lsfg_tab = r'''@Composable
private fun LsfgQuickMenuTab(
    container: com.winlator.container.Container?,
    multiplier: Int,
    flowScale: Float,
    performanceMode: Boolean,
    runtimeStatus: String,
    onMultiplierChanged: (Int) -> Unit,
    onFlowScaleChanged: (Float) -> Unit,
    onPerformanceModeChanged: (Boolean) -> Unit,
    presentMode: String,
    onPresentModeChanged: (String) -> Unit,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    val initialMode = remember(container?.id) {
        container?.let { app.gamenative.utils.LsfgQuickMenuHelper.generationMode(it) }
            ?: app.gamenative.utils.LsfgQuickMenuHelper.FrameGenerationMode.FIXED
    }
    var frameGenerationEnabled by remember(container?.id) { mutableStateOf(multiplier >= 2) }
    var mode by remember(container?.id) { mutableStateOf(initialMode) }
    var fixedMultiplier by remember(container?.id) {
        mutableIntStateOf(container?.let { app.gamenative.utils.LsfgQuickMenuHelper.fixedMultiplier(it) } ?: 2)
    }
    var adaptiveTargetFps by remember(container?.id) {
        mutableIntStateOf(container?.let { app.gamenative.utils.LsfgQuickMenuHelper.adaptiveTargetFps(it) } ?: 60)
    }

    LaunchedEffect(multiplier) { frameGenerationEnabled = multiplier >= 2 }

    fun runtimeMultiplier(): Int =
        if (mode == app.gamenative.utils.LsfgQuickMenuHelper.FrameGenerationMode.ADAPTIVE) 4 else fixedMultiplier

    Column(
        modifier = modifier.verticalScroll(scrollState).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickMenuToggleRow(
            title = stringResource(R.string.lsfg_frame_generation),
            subtitle = runtimeStatus.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.lsfg_frame_generation_desc),
            enabled = frameGenerationEnabled,
            onToggle = {
                val next = !frameGenerationEnabled
                frameGenerationEnabled = next
                onMultiplierChanged(if (next) runtimeMultiplier() else 0)
            },
            accentColor = accentColor,
            focusRequester = focusRequester,
        )

        Spacer(modifier = Modifier.height(4.dp))
        QuickMenuSectionHeader(title = stringResource(R.string.lsfg_mode))
        Row(modifier = Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                app.gamenative.utils.LsfgQuickMenuHelper.FrameGenerationMode.FIXED to R.string.lsfg_mode_fixed,
                app.gamenative.utils.LsfgQuickMenuHelper.FrameGenerationMode.ADAPTIVE to R.string.lsfg_mode_adaptive,
            ).forEach { (candidate, label) ->
                QuickMenuChoiceChip(
                    text = stringResource(label),
                    selected = mode == candidate,
                    accentColor = accentColor,
                    onClick = {
                        mode = candidate
                        container?.let { app.gamenative.utils.LsfgQuickMenuHelper.setGenerationMode(it, candidate) }
                        if (frameGenerationEnabled) onMultiplierChanged(runtimeMultiplier())
                    },
                    modifier = Modifier.width(96.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        if (mode == app.gamenative.utils.LsfgQuickMenuHelper.FrameGenerationMode.FIXED) {
            QuickMenuSectionHeader(title = stringResource(R.string.lsfg_fixed_multiplier))
            Row(modifier = Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 3, 4).forEach { value ->
                    QuickMenuChoiceChip(
                        text = "${value}x",
                        selected = fixedMultiplier == value,
                        accentColor = accentColor,
                        onClick = {
                            fixedMultiplier = value
                            container?.let { app.gamenative.utils.LsfgQuickMenuHelper.setFixedMultiplier(it, value) }
                            if (frameGenerationEnabled) onMultiplierChanged(value)
                        },
                        modifier = Modifier.width(56.dp),
                    )
                }
            }
        } else {
            QuickMenuSectionHeader(
                title = stringResource(R.string.lsfg_adaptive_target),
                subtitle = stringResource(R.string.lsfg_adaptive_target_desc),
            )
            Text(
                text = stringResource(R.string.lsfg_adaptive_target_value, adaptiveTargetFps),
                style = MaterialTheme.typography.labelLarge,
                color = accentColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Slider(
                value = adaptiveTargetFps.toFloat(),
                onValueChange = { adaptiveTargetFps = it.roundToInt().coerceIn(30, 144) },
                onValueChangeFinished = {
                    container?.let { app.gamenative.utils.LsfgQuickMenuHelper.setAdaptiveTargetFps(it, adaptiveTargetFps) }
                    if (frameGenerationEnabled) onMultiplierChanged(4)
                },
                valueRange = 30f..144f,
                steps = 113,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        AnimatedVisibility(
            visible = frameGenerationEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(modifier = Modifier.height(4.dp))
                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.lsfg_flow_scale),
                    subtitle = stringResource(R.string.lsfg_flow_scale_desc),
                    valueText = String.format(java.util.Locale.US, "%.2f", flowScale),
                    progress = (flowScale - 0.25f) / 0.75f,
                    onDecrease = {
                        val next = (flowScale - 0.05f).coerceIn(0.25f, 1.0f)
                        onFlowScaleChanged(String.format(java.util.Locale.US, "%.2f", next).toFloat())
                    },
                    onIncrease = {
                        val next = (flowScale + 0.05f).coerceIn(0.25f, 1.0f)
                        onFlowScaleChanged(String.format(java.util.Locale.US, "%.2f", next).toFloat())
                    },
                    accentColor = accentColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                QuickMenuToggleRow(
                    title = stringResource(R.string.lsfg_performance_mode),
                    subtitle = stringResource(R.string.lsfg_performance_mode_desc),
                    enabled = performanceMode,
                    onToggle = { onPerformanceModeChanged(!performanceMode) },
                    accentColor = accentColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                QuickMenuSectionHeader(
                    title = stringResource(R.string.lsfg_present_mode),
                    subtitle = stringResource(R.string.lsfg_present_mode_desc),
                )
                Row(modifier = Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mailbox" to "Mailbox", "fifo" to "FIFO").forEach { (value, label) ->
                        QuickMenuChoiceChip(
                            text = label,
                            selected = presentMode == value,
                            accentColor = accentColor,
                            onClick = { onPresentModeChanged(value) },
                            modifier = Modifier.width(96.dp),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

'''
quick = regex_once(
    quick,
    r'@Composable\nprivate fun LsfgQuickMenuTab\(.*?(?=@Composable\nprivate fun ImmersiveQuickMenuTab\()',
    new_lsfg_tab,
    "LSFG Quick Menu tab",
)
write(quick_rel, quick)

strings_rel = "app/src/main/res/values/strings_lsfg_adaptive.xml"
write(strings_rel, '''<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string name="lsfg_frame_generation">Frame Generation</string>\n    <string name="lsfg_frame_generation_desc">Generate intermediate frames while keeping the LSFG layer resident.</string>\n    <string name="lsfg_mode">Mode</string>\n    <string name="lsfg_mode_fixed">Fixed</string>\n    <string name="lsfg_mode_adaptive">Adaptive</string>\n    <string name="lsfg_fixed_multiplier">Fixed multiplier</string>\n    <string name="lsfg_adaptive_target">Target frame rate</string>\n    <string name="lsfg_adaptive_target_desc">Adaptive uses up to 4x generation only as needed to approach this output target.</string>\n    <string name="lsfg_adaptive_target_value">%1$d FPS</string>\n</resources>\n''')

xserver_rel = "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt"
xserver = read(xserver_rel)
xserver = xserver.replace("            LsfgVkManager.stopVsyncClock()\n", "")
xserver = regex_once(
    xserver,
    r'''\n    DisposableEffect\(container, lsfgRuntimeMode\) \{\n        if \(lsfgRuntimeMode == LsfgRuntimeMode\.TURNING_ON \|\|\n            lsfgRuntimeMode == LsfgRuntimeMode\.GENERATING\n        \) \{\n            LsfgVkManager\.startVsyncClock\(context, container\)\n        \} else \{\n            LsfgVkManager\.stopVsyncClock\(\)\n        \}\n        onDispose \{\n            LsfgVkManager\.stopVsyncClock\(\)\n        \}\n    \}\n''',
    "\n",
    "remove XServer vsync lifecycle",
)
if "startVsyncClock" in xserver or "stopVsyncClock" in xserver:
    raise RuntimeError("XServer still references removed vsync publisher")
write(xserver_rel, xserver)

for rel in (
    ".github/workflows/apply-lsfg-mode-cleanup.yml",
    ".github/workflows/experimental-adaptive-legacydebug.yml",
):
    path = ROOT / rel
    if path.exists():
        path.unlink()

submodule = ROOT / "app/src/main/cpp/lsfg-vk-android"
subprocess.run(["git", "update-index", "--cacheinfo", f"160000,{NATIVE_SHA},app/src/main/cpp/lsfg-vk-android"], cwd=ROOT, check=True)
subprocess.run(["git", "fetch", "origin", NATIVE_SHA], cwd=submodule, check=True)
subprocess.run(["git", "checkout", "--detach", NATIVE_SHA], cwd=submodule, check=True)

all_product = "\n".join(read(rel) for rel in (manager_rel, helper_rel, quick_rel, xserver_rel))
for forbidden in ("gamenative-adaptive.toml", "target_output_fps", "startVsyncClock", "stopVsyncClock", "vsync.txt"):
    if forbidden in all_product:
        raise RuntimeError(f"vestigial path remains: {forbidden}")
for required in (
    "adaptive_framegen =",
    "fps_limit =",
    "lsfg_frame_generation",
    "FrameGenerationMode.ADAPTIVE",
    "valueRange = 30f..144f",
    "onValueChangeFinished",
):
    if required not in all_product:
        raise RuntimeError(f"required integration contract missing: {required}")
subprocess.run(["git", "diff", "--check"], cwd=ROOT, check=True)
print("GameNative Adaptive integration transformation complete")
