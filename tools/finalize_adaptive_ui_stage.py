#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
NATIVE_SHA = "f7158459cc689c05c5fbfa721301d9a8586537f0"


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)

# Quick Menu: use the exact same +/- adjustment row pattern as the frame limiter.
quick_rel = "app/src/main/java/app/gamenative/ui/component/QuickMenu.kt"
quick = read(quick_rel)
quick = quick.replace("import androidx.compose.material3.Slider\n", "")
start_marker = '''        } else {\n            QuickMenuSectionHeader(\n                title = stringResource(R.string.lsfg_adaptive_target),'''
end_marker = '''        }\n\n        AnimatedVisibility(\n            visible = frameGenerationEnabled,'''
start = quick.find(start_marker)
if start < 0:
    raise RuntimeError("adaptive slider block start not found")
end = quick.find(end_marker, start)
if end < 0:
    raise RuntimeError("adaptive slider block end not found")
replacement = '''        } else {\n            QuickMenuAdjustmentRow(\n                title = stringResource(R.string.lsfg_adaptive_target),\n                subtitle = stringResource(R.string.lsfg_adaptive_target_desc),\n                valueText = stringResource(R.string.lsfg_adaptive_target_value, adaptiveTargetFps),\n                progress = adaptiveTargetFpsProgress(adaptiveTargetFps),\n                onDecrease = {\n                    val next = previousAdaptiveTargetFps(adaptiveTargetFps)\n                    if (next != adaptiveTargetFps) {\n                        adaptiveTargetFps = next\n                        container?.let { app.gamenative.utils.LsfgQuickMenuHelper.setAdaptiveTargetFps(it, next) }\n                    }\n                },\n                onIncrease = {\n                    val next = nextAdaptiveTargetFps(adaptiveTargetFps)\n                    if (next != adaptiveTargetFps) {\n                        adaptiveTargetFps = next\n                        container?.let { app.gamenative.utils.LsfgQuickMenuHelper.setAdaptiveTargetFps(it, next) }\n                    }\n                },\n                accentColor = accentColor,\n            )\n'''
quick = quick[:start] + replacement + quick[end + len("        }\n"):]
if "Slider(" in quick or "30f..144f" in quick or "steps = 113" in quick:
    raise RuntimeError("old Adaptive Slider implementation still present")
write(quick_rel, quick)

# Manager: 30-120 range, 5-fps canonicalization, and exact native runtime marker.
manager_rel = "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
manager = read(manager_rel)
manager = replace_once(
    manager,
    '    const val MAX_ADAPTIVE_TARGET_FPS = 144\n',
    '    const val MAX_ADAPTIVE_TARGET_FPS = 120\n    const val ADAPTIVE_TARGET_FPS_STEP = 5\n',
    "adaptive max/step",
)
manager = re.sub(
    r'private const val RUNTIME_VERSION =\s*\n\s*"[^"]+"',
    'private const val RUNTIME_VERSION =\n        "gamenative-adaptive-f7158459-r1"',
    manager,
    count=1,
)
old_reader = '''    fun adaptiveTargetFps(container: Container): Int =\n        (container.getExtra(EXTRA_ADAPTIVE_TARGET_FPS, DEFAULT_ADAPTIVE_TARGET_FPS.toString())\n            .toIntOrNull() ?: DEFAULT_ADAPTIVE_TARGET_FPS)\n            .coerceIn(MIN_ADAPTIVE_TARGET_FPS, MAX_ADAPTIVE_TARGET_FPS)\n'''
new_reader = '''    fun sanitizeAdaptiveTargetFps(targetFps: Int): Int {\n        val clamped = targetFps.coerceIn(MIN_ADAPTIVE_TARGET_FPS, MAX_ADAPTIVE_TARGET_FPS)\n        val offset = clamped - MIN_ADAPTIVE_TARGET_FPS\n        return MIN_ADAPTIVE_TARGET_FPS + (offset / ADAPTIVE_TARGET_FPS_STEP) * ADAPTIVE_TARGET_FPS_STEP\n    }\n\n    fun adaptiveTargetFps(container: Container): Int =\n        sanitizeAdaptiveTargetFps(\n            container.getExtra(EXTRA_ADAPTIVE_TARGET_FPS, DEFAULT_ADAPTIVE_TARGET_FPS.toString())\n                .toIntOrNull() ?: DEFAULT_ADAPTIVE_TARGET_FPS,\n        )\n'''
manager = replace_once(manager, old_reader, new_reader, "adaptive target reader")
if "MAX_ADAPTIVE_TARGET_FPS = 144" in manager:
    raise RuntimeError("144-fps Adaptive max remains")
write(manager_rel, manager)

# Helper: persist canonical 5-fps values and publish a single runtime config update when Adaptive is live.
helper_rel = "app/src/main/java/app/gamenative/utils/LsfgQuickMenuHelper.kt"
helper = read(helper_rel)
old_setter = '''    fun setAdaptiveTargetFps(container: Container, targetFps: Int) {\n        container.putExtra(\n            LsfgVkManager.EXTRA_ADAPTIVE_TARGET_FPS,\n            targetFps.coerceIn(LsfgVkManager.MIN_ADAPTIVE_TARGET_FPS, LsfgVkManager.MAX_ADAPTIVE_TARGET_FPS).toString(),\n        )\n        container.saveData()\n    }\n'''
new_setter = '''    fun setAdaptiveTargetFps(container: Container, targetFps: Int) {\n        val sanitized = LsfgVkManager.sanitizeAdaptiveTargetFps(targetFps)\n        container.putExtra(LsfgVkManager.EXTRA_ADAPTIVE_TARGET_FPS, sanitized.toString())\n        container.saveData()\n        if (generationMode(container) == FrameGenerationMode.ADAPTIVE &&\n            sanitizeMultiplier(LsfgVkManager.multiplier(container)) >= 2\n        ) {\n            publishRuntimeConfig(container, readSettings(container))\n        }\n    }\n'''
helper = replace_once(helper, old_setter, new_setter, "adaptive target setter")
write(helper_rel, helper)

# Validator workflow must require the exact green native cadence-fix revision.
workflow_rel = ".github/workflows/experimental-adaptive-legacydebug.yml"
workflow = read(workflow_rel)
workflow = workflow.replace(
    "expected_native=b77b8e81f260f3bcace6000d011c79b2c222b9ac",
    f"expected_native={NATIVE_SHA}",
)
if NATIVE_SHA not in workflow:
    raise RuntimeError("validator does not reference exact native SHA")
write(workflow_rel, workflow)

# Update the gitlink without checking the private submodule out manually.
submodule = ROOT / "app/src/main/cpp/lsfg-vk-android"
if not submodule.exists():
    raise RuntimeError("lsfg-vk-android submodule path missing")

print("Adaptive UI/controller integration patch prepared")
