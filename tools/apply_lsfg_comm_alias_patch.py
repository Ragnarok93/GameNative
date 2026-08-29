from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1))


manager = "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
replace_once(
    manager,
    '''        if (!dllPath.isNullOrBlank() && !processExecutable.isNullOrBlank()) {
            val effectiveMultiplier = if (enabled) multiplier.coerceIn(2, 4) else 1
            appendLine("[[game]]")
            appendLine("exe = ${tomlString(processExecutable)}")
            appendLine("multiplier = $effectiveMultiplier")
            appendLine("flow_scale = ${formatFlowScale(flowScale)}")
            appendLine("performance_mode = ${if (enabled && performanceMode) "true" else "false"}")
            appendLine("hdr_mode = false")
            appendLine("fps_limit = ${fpsLimit.coerceAtLeast(0)}")
            appendLine("experimental_present_mode = ${tomlString(if (enabled) presentMode else "fifo")}")
        }
''',
    '''        if (!dllPath.isNullOrBlank() && !processExecutable.isNullOrBlank()) {
            val effectiveMultiplier = if (enabled) multiplier.coerceIn(2, 4) else 1
            // Wine/Linux process names exposed through /proc/self/comm are limited
            // to TASK_COMM_LEN (16 bytes including NUL). Keep the full basename for
            // /proc/self/exe-style matching and add the 15-character comm alias when
            // needed so long Windows executable names still activate LSFG.
            val processNames = listOf(
                processExecutable,
                processExecutable.take(15),
            ).distinct()
            processNames.forEach { processName ->
                appendLine("[[game]]")
                appendLine("exe = ${tomlString(processName)}")
                appendLine("multiplier = $effectiveMultiplier")
                appendLine("flow_scale = ${formatFlowScale(flowScale)}")
                appendLine("performance_mode = ${if (enabled && performanceMode) "true" else "false"}")
                appendLine("hdr_mode = false")
                appendLine("fps_limit = ${fpsLimit.coerceAtLeast(0)}")
                appendLine("experimental_present_mode = ${tomlString(if (enabled) presentMode else "fifo")}")
            }
        }
''',
)

# Add a direct regression test using a concrete Container so writeConfig is
# exercised rather than only checking launch-env behavior.
test = Path("app/src/test/java/app/gamenative/utils/LsfgVkManagerTest.kt")
s = test.read_text()
marker = '''    @Test
    fun applyLaunchEnv_clearsOnlyLsfgEnvironmentWhenDisabled() {
'''
extra = '''    @Test
    fun writeConfig_addsLinuxCommAliasForLongExecutableNames() {
        File(rootDir, ".local/share/lsfg-vk/Lossless.dll").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val container = Container("lsfg-long-name").apply {
            setRootDir(rootDir)
            setContainerVariant(Container.BIONIC)
            setExecutablePath("bin/FFVIII_LAUNCHER.exe")
            putExtra(LsfgVkManager.EXTRA_ARMED, "true")
            putExtra(LsfgVkManager.EXTRA_MULTIPLIER, "2")
            putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, "0.80")
            putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, "true")
            putExtra(LsfgVkManager.EXTRA_PRESENT_MODE, "mailbox")
            putExtra("fpsLimiterEnabled", "false")
        }

        assertTrue(LsfgVkManager.writeConfig(container))
        val text = File(rootDir, ".config/lsfg-vk/conf.toml").readText()
        assertTrue(text.contains("exe = \\"FFVIII_LAUNCHER.exe\\""))
        assertTrue(text.contains("exe = \\"FFVIII_LAUNCHER\\""))
    }

'''
if extra not in s:
    if marker not in s:
        raise SystemExit("test insertion marker missing")
    s = s.replace(marker, extra + marker, 1)
test.write_text(s)

print("comm alias patch applied")
