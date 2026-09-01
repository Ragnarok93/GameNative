#!/usr/bin/env python3
from pathlib import Path

MANAGER = Path("app/src/main/java/app/gamenative/utils/LsfgVkManager.kt")
HELPER = Path("app/src/main/java/app/gamenative/utils/LsfgQuickMenuHelper.kt")
SCREEN = Path("app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"ERROR: {label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def patch_manager() -> None:
    text = MANAGER.read_text(encoding="utf-8")
    if "sourceFpsLimitOverride" in text:
        return

    text = replace_once(
        text,
        '    const val EXTRA_ADAPTIVE_OUTPUT_TARGET = "lsfgAdaptiveOutputTarget"\n',
        '    const val EXTRA_ADAPTIVE_OUTPUT_TARGET = "lsfgAdaptiveOutputTarget"\n'
        '    private const val EXTRA_SOURCE_FPS_LIMITER_ENABLED = "fpsLimiterEnabled"\n'
        '    private const val EXTRA_SOURCE_FPS_LIMITER_TARGET = "fpsLimiterTarget"\n',
        "source limiter extra keys",
    )
    text = replace_once(
        text,
        '    fun fpsLimit(container: Container): Int = adaptiveOutputTarget(container)\n',
        '    fun fpsLimit(container: Container): Int = adaptiveOutputTarget(container)\n\n'
        '    /** Real/source Vulkan present ceiling, independent from Adaptive output FPS. */\n'
        '    fun sourceFpsLimit(container: Container): Int {\n'
        '        if (!parseBool(container.getExtra(EXTRA_SOURCE_FPS_LIMITER_ENABLED, "false"))) return 0\n'
        '        return container.getExtra(EXTRA_SOURCE_FPS_LIMITER_TARGET, "0")\n'
        '            .toIntOrNull()\n'
        '            ?.coerceAtLeast(0)\n'
        '            ?: 0\n'
        '    }\n',
        "source limiter reader",
    )
    text = replace_once(
        text,
        '                appendLine("fps_limit = ${fpsLimit.coerceAtLeast(0)}")\n'
        '                appendLine("experimental_present_mode = ${tomlString(if (enabled) presentMode else "fifo")}")\n',
        '                appendLine("fps_limit = ${fpsLimit.coerceAtLeast(0)}")\n'
        '                appendLine("source_fps_limit = 0")\n'
        '                appendLine("experimental_present_mode = ${tomlString(if (enabled) presentMode else "fifo")}")\n',
        "source limiter TOML field",
    )
    text = replace_once(
        text,
        '                fpsLimit = outputTarget,\n'
        '                presentMode = presentMode(container),\n'
        '            )\n'
        '            writeConfigAtomic(configFile, configText)\n',
        '                fpsLimit = outputTarget,\n'
        '                presentMode = presentMode(container),\n'
        '            ).replace(\n'
        '                "source_fps_limit = 0",\n'
        '                "source_fps_limit = ${sourceFpsLimit(container)}",\n'
        '            )\n'
        '            writeConfigAtomic(configFile, configText)\n',
        "launch source limiter serialization",
    )
    text = replace_once(
        text,
        '        adaptiveFrameGen: Boolean,\n'
        '        fpsLimitOverride: Int? = null,\n'
        '    ): Boolean {\n',
        '        adaptiveFrameGen: Boolean,\n'
        '        fpsLimitOverride: Int? = null,\n'
        '        sourceFpsLimitOverride: Int? = null,\n'
        '    ): Boolean {\n',
        "runtime source limiter override",
    )
    text = replace_once(
        text,
        '            val effectiveOutputTarget =\n'
        '                (fpsLimitOverride ?: adaptiveOutputTarget(container)).coerceAtLeast(0)\n'
        '            val adaptiveEffective =\n',
        '            val effectiveOutputTarget =\n'
        '                (fpsLimitOverride ?: adaptiveOutputTarget(container)).coerceAtLeast(0)\n'
        '            val effectiveSourceFpsLimit =\n'
        '                (sourceFpsLimitOverride ?: sourceFpsLimit(container)).coerceAtLeast(0)\n'
        '            val adaptiveEffective =\n',
        "runtime effective source limiter",
    )
    text = replace_once(
        text,
        '                fpsLimit = effectiveOutputTarget,\n'
        '                presentMode = presentMode(container),\n'
        '            )\n\n'
        '            val ok = writeConfigAtomic(configFile, configText)\n',
        '                fpsLimit = effectiveOutputTarget,\n'
        '                presentMode = presentMode(container),\n'
        '            ).replace(\n'
        '                "source_fps_limit = 0",\n'
        '                "source_fps_limit = $effectiveSourceFpsLimit",\n'
        '            )\n\n'
        '            val ok = writeConfigAtomic(configFile, configText)\n',
        "runtime source limiter serialization",
    )
    MANAGER.write_text(text, encoding="utf-8")


def patch_helper() -> None:
    text = HELPER.read_text(encoding="utf-8")
    if "sourceFpsLimitOverride = sourceLimit" in text:
        return
    old = '''    fun applyLiveFpsCap(container: Container, capFps: Int) {
        Timber.tag(TAG).d(
            "Source FPS cap changed to %d; preserving LSFG output target %d",
            capFps.coerceAtLeast(0),
            LsfgVkManager.adaptiveOutputTarget(container),
        )
    }
'''
    new = '''    fun applyLiveFpsCap(container: Container, capFps: Int) {
        applyExecutor.execute {
            val sourceLimit = capFps.coerceAtLeast(0)
            val settings = readSettings(container)
            val applied = LsfgVkManager.updateConfigAtRuntime(
                container = container,
                enabled = settings.multiplier >= 2,
                multiplier = if (settings.multiplier >= 2) settings.multiplier else 2,
                flowScale = settings.flowScale,
                performanceMode = settings.performanceMode,
                adaptiveFrameGen = settings.adaptiveFrameGen,
                fpsLimitOverride = null,
                sourceFpsLimitOverride = sourceLimit,
            )
            Timber.tag(TAG).d(
                "Source FPS cap hot-reload: source=%d output=%d applied=%s",
                sourceLimit,
                LsfgVkManager.adaptiveOutputTarget(container),
                applied,
            )
        }
    }
'''
    HELPER.write_text(replace_once(text, old, new, "live source cap bridge"), encoding="utf-8")


def patch_screen() -> None:
    text = SCREEN.read_text(encoding="utf-8")
    if "LsfgQuickMenuHelper.applyLiveFpsCap(container, limit)" in text:
        return
    old = (
        '        ShmFramePacer.setFrameRateLimit(limit)\n'
        '        PowerManager.targetFps = limit\n'
        '        // keeps frame stats in base units while generated frames tick the ring\n'
    )
    new = (
        '        ShmFramePacer.setFrameRateLimit(limit)\n'
        '        PowerManager.targetFps = limit\n'
        '        if (lsfgActive) {\n'
        '            LsfgQuickMenuHelper.applyLiveFpsCap(container, limit)\n'
        '        }\n'
        '        // keeps frame stats in base units while generated frames tick the ring\n'
    )
    SCREEN.write_text(replace_once(text, old, new, "XServer native source cap handoff"), encoding="utf-8")


patch_manager()
patch_helper()
patch_screen()

manager_text = MANAGER.read_text(encoding="utf-8")
helper_text = HELPER.read_text(encoding="utf-8")
screen_text = SCREEN.read_text(encoding="utf-8")
assert "source_fps_limit = 0" in manager_text
assert "sourceFpsLimitOverride" in manager_text
assert "sourceFpsLimitOverride = sourceLimit" in helper_text
assert "LsfgQuickMenuHelper.applyLiveFpsCap(container, limit)" in screen_text
