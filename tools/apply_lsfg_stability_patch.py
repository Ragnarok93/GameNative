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
    '''    /** Whether LSFG is armed (enabled + Lossless.dll available in Steam dir) for this container. The DLL is copied into the container at launch time by ensureRuntimeInstalled(). */
    @JvmStatic
    fun isArmed(container: Container): Boolean =
        isSupported(container) &&
            parseBool(container.getExtra(EXTRA_ARMED, "false")) &&
            isDllAvailable()
''',
    '''    /**
     * Whether LSFG is armed for this container.
     *
     * This is a UI/runtime hot path. Never rescan Steam libraries here: launch
     * setup owns discovery/copying, so the container-local DLL is authoritative.
     */
    @JvmStatic
    fun isArmed(container: Container): Boolean =
        isSupported(container) &&
            parseBool(container.getExtra(EXTRA_ARMED, "false")) &&
            containerDllPath(container) != null
''',
)

replace_once(
    manager,
    '    private const val RUNTIME_VERSION = "v1.3.3-android-arm64-v8a-gamenative-implicit-r1"\n',
    '    private const val RUNTIME_VERSION = "v1.3.3-android-arm64-v8a-gamenative-targeted-r2"\n',
)

replace_once(
    manager,
    '''            val dllPath = containerDllPath(container)
            val savedMultiplier = multiplier(container)
            val frameGenActive = parseBool(container.getExtra(EXTRA_ARMED, "false")) &&
                dllPath != null && savedMultiplier >= 2
            val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)
            val configText = buildConfigToml(
                dllPath = dllPath,
                enabled = frameGenActive,
''',
    '''            val dllPath = containerDllPath(container)
            val processExecutable = targetExecutable(container)
            val savedMultiplier = multiplier(container)
            val frameGenActive = parseBool(container.getExtra(EXTRA_ARMED, "false")) &&
                dllPath != null && processExecutable != null && savedMultiplier >= 2
            val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)
            val configText = buildConfigToml(
                dllPath = dllPath,
                processExecutable = processExecutable,
                enabled = frameGenActive,
''',
)

replace_once(
    manager,
    '''        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
        // The manifest uses this same variable/value as its enable_environment
        // gate, so only GameNative-launched processes with LSFG armed activate it.
        envVars.put(ENV_PROCESS, PROCESS_EXE_IDENTIFIER)

        Timber.tag(TAG).i(
            "LSFG armed: dll=%s, multiplier=%d, flowScale=%.2f, perf=%s, discovery=implicit-home",
            dllPath,
            multiplier(container),
            flowScale(container),
            if (performanceMode(container)) "on" else "off",
        )
        return true
''',
    '''        envVars.put(ENV_CONFIG, configFile(container).absolutePath)

        // Do not set LSFG_PROCESS. It overrides process identity inside lsfg-vk
        // and is inherited by every Wine/Zink helper. With normal process identity,
        // unmatched helpers use the disabled global config and return before native
        // framegen/shader initialization; only the configured executable is enabled.
        val processExecutable = targetExecutable(container)
        Timber.tag(TAG).i(
            "LSFG armed: dll=%s, target=%s, multiplier=%d, flowScale=%.2f, perf=%s, discovery=implicit-home-targeted",
            dllPath,
            processExecutable ?: "unresolved",
            multiplier(container),
            flowScale(container),
            if (performanceMode(container)) "on" else "off",
        )
        return processExecutable != null
''',
)

replace_once(
    manager,
    '''    private fun buildConfigToml(
        dllPath: String?,
        enabled: Boolean,
''',
    '''    private fun buildConfigToml(
        dllPath: String?,
        processExecutable: String?,
        enabled: Boolean,
''',
)

replace_once(
    manager,
    '''        if (!dllPath.isNullOrBlank()) {
            val effectiveMultiplier = if (enabled) multiplier.coerceIn(2, 4) else 1
            appendLine("[[game]]")
            appendLine("exe = ${tomlString(PROCESS_EXE_IDENTIFIER)}")
''',
    '''        if (!dllPath.isNullOrBlank() && !processExecutable.isNullOrBlank()) {
            val effectiveMultiplier = if (enabled) multiplier.coerceIn(2, 4) else 1
            appendLine("[[game]]")
            appendLine("exe = ${tomlString(processExecutable)}")
''',
)

replace_once(
    manager,
    '''    private fun configFile(container: Container): File =
        File(container.rootDir, CONFIG_RELATIVE_PATH)

    // The layer rereads conf.toml on mtime change and must never observe a
''',
    '''    private fun configFile(container: Container): File =
        File(container.rootDir, CONFIG_RELATIVE_PATH)

    internal fun targetExecutable(container: Container): String? =
        container.executablePath
            .trim()
            .trim('"')
            .replace('\\\\', '/')
            .substringAfterLast('/')
            .trim()
            .takeIf { it.isNotEmpty() }

    // The layer rereads conf.toml on mtime change and must never observe a
''',
)

replace_once(
    manager,
    '''        return try {
            val frameGenActive = enabled && dllPath != null
            val configText = buildConfigToml(
                dllPath = dllPath,
                enabled = frameGenActive,
''',
    '''        return try {
            val processExecutable = targetExecutable(container)
            val frameGenActive = enabled && dllPath != null && processExecutable != null
            val configText = buildConfigToml(
                dllPath = dllPath,
                processExecutable = processExecutable,
                enabled = frameGenActive,
''',
)

text = Path(manager).read_text()
if text.count("buildConfigToml(") != 3:
    raise SystemExit("unexpected buildConfigToml count after patch")
if text.count("processExecutable = processExecutable") != 2:
    raise SystemExit("not every buildConfigToml call received processExecutable")

manifest = "app/src/main/assets/lsfg_vk/android_arm64_v8a/VkLayer_LS_frame_generation.json"
replace_once(
    manifest,
    '''    "enable_environment": {
      "LSFG_PROCESS": "gamenative-lsfg"
    },
''',
    "",
)

diag = "app/src/main/java/app/gamenative/utils/LsfgCompatibilityDiagnostics.kt"
replace_once(
    diag,
    '''        val config = File(root, CONFIG_RELATIVE)
        checks += configCheck(config, dll, armed)
''',
    '''        val config = File(root, CONFIG_RELATIVE)
        checks += configCheck(config, dll, armed, targetExecutable(container))
''',
)
replace_once(
    diag,
    '''                if (layer.optJSONObject("enable_environment")?.optString("LSFG_PROCESS") != PROCESS_ID) {
                    add("enable_environment.LSFG_PROCESS mismatch")
                }
''',
    '''                if (layer.has("enable_environment")) {
                    add("enable_environment must be absent; LSFG activation is config/process targeted")
                }
''',
)
replace_once(
    diag,
    '''    private fun configCheck(config: File, dll: File, armed: Boolean): Check {
''',
    '''    private fun configCheck(
        config: File,
        dll: File,
        armed: Boolean,
        expectedExecutable: String?,
    ): Check {
''',
)
replace_once(
    diag,
    '''                if (exe != PROCESS_ID) add("exe=$exe")
                if (armed && (multiplier == null || multiplier < 2)) add("multiplier=$multiplier while LSFG is armed")
''',
    '''                if (expectedExecutable == null) add("container executable is unresolved")
                else if (!exe.equals(expectedExecutable, ignoreCase = true)) {
                    add("exe=$exe expected=$expectedExecutable")
                }
                if (armed && (multiplier == null || multiplier < 2)) add("multiplier=$multiplier while LSFG is armed")
''',
)
replace_once(
    diag,
    '''    private fun layerLogCheck(logs: String): Check {
        if (logs.isBlank()) return Check("layer_log_evidence", Status.WARN, "no relevant logs captured")
        val found = logs.contains("lsfg-vk-framegen", ignoreCase = true) ||
            logs.contains(LAYER_NAME, ignoreCase = true) ||
            logs.contains("liblsfg-vk-layer.so", ignoreCase = true)
        return if (found) Check("layer_log_evidence", Status.PASS, "LSFG layer marker found in recent logs")
        else Check("layer_log_evidence", Status.WARN, "no LSFG layer marker in captured log window")
    }
''',
    '''    private fun layerLogCheck(logs: String): Check {
        if (logs.isBlank()) return Check("layer_log_evidence", Status.WARN, "no relevant logs captured")
        // Install/chmod/path messages mentioning the .so are not attachment proof.
        val found = logs.lineSequence().any { line ->
            line.contains("lsfg-vk:", ignoreCase = true) ||
                line.contains("lsfg-vk-framegen", ignoreCase = true)
        }
        return if (found) Check("layer_log_evidence", Status.PASS, "native LSFG layer output found in recent logs")
        else Check("layer_log_evidence", Status.WARN, "no native lsfg-vk runtime output in captured log window")
    }
''',
)
replace_once(
    diag,
    '''                "DEVICE_LOST", "VK_ERROR", "ExynosTools", "VortekXclipse",
                "vkCreateDevice", "DXVK", "Winlator_Renderer",
''',
    '''                "DEVICE_LOST", "VK_ERROR", "ExynosTools", "VortekXclipse",
                "vkCreateDevice", "DXVK", "Winlator_Renderer", "AndroidRuntime",
                "FATAL EXCEPTION", "Fatal signal", "SIGSEGV", "SIGABRT", "has died", "tombstone",
''',
)
replace_once(
    diag,
    '''    private fun MutableList<Check>.failed(id: String): Boolean =
''',
    '''    private fun targetExecutable(container: Container): String? =
        container.executablePath
            .trim()
            .trim('"')
            .replace('\\\\', '/')
            .substringAfterLast('/')
            .trim()
            .takeIf { it.isNotEmpty() }

    private fun MutableList<Check>.failed(id: String): Boolean =
''',
)

# Manager tests: no fake process identity; manifest is config-targeted.
t = Path("app/src/test/java/app/gamenative/utils/LsfgVkManagerTest.kt")
s = t.read_text()
s = s.replace(
    '        assertEquals("gamenative-lsfg", envVars["LSFG_PROCESS"])\n',
    '        assertFalse(envVars.has("LSFG_PROCESS"))\n',
)
s = s.replace(
    '''        assertEquals(
            "gamenative-lsfg",
            layer.getJSONObject("enable_environment").getString("LSFG_PROCESS"),
        )
''',
    '        assertFalse(layer.has("enable_environment"))\n',
)
s = s.replace(
    '        whenever(container.containerVariant).thenReturn(Container.BIONIC)\n',
    '        whenever(container.containerVariant).thenReturn(Container.BIONIC)\n        whenever(container.executablePath).thenReturn("bin/game.exe")\n',
    1,
)
t.write_text(s)

# Diagnostics fixtures must reflect real executable matching and native-only evidence.
t = Path("app/src/test/java/app/gamenative/utils/LsfgCompatibilityDiagnosticsTest.kt")
s = t.read_text()
s = s.replace(
    'logs = "LSFG armed VK_LAYER_LS_frame_generation liblsfg-vk-layer.so ExynosToolsShim"',
    'logs = "lsfg-vk: Loaded configuration for game.exe ExynosToolsShim"',
)
s = s.replace(
    'logs = "Vulkan loader: VK_LAYER_LS_frame_generation liblsfg-vk-layer.so"',
    'logs = "lsfg-vk: Loaded configuration for game.exe"',
)
s = s.replace(
    'VK_LAYER_LS_frame_generation liblsfg-vk-layer.so\n            lsfg-vk-framegen',
    'lsfg-vk: Loaded configuration for game.exe\n            lsfg-vk-framegen',
)
s = s.replace('                    "enable_environment":{"LSFG_PROCESS":"gamenative-lsfg"},\n', '')
s = s.replace('                exe = "gamenative-lsfg"\n', '                exe = "game.exe"\n')
s = s.replace(
    '        setContainerVariant(Container.BIONIC)\n        putExtra(LsfgVkManager.EXTRA_ARMED, armed)\n',
    '        setContainerVariant(Container.BIONIC)\n        setExecutablePath("bin/game.exe")\n        putExtra(LsfgVkManager.EXTRA_ARMED, armed)\n',
)
marker = '''    @Test
    fun missingLayerEvidence_identifiesDiscoveryBarrier() {
'''
extra = '''    @Test
    fun installPathMentionAlone_doesNotCountAsLayerAttachment() {
        writeHealthyRuntime(freshStats = false)

        val snapshot = inspect(
            logs = "LsfgVkManager: Installed liblsfg-vk-layer.so into container",
        )

        assertEquals(LsfgCompatibilityDiagnostics.Status.WARN, snapshot.check("layer_log_evidence")?.status)
        assertEquals("LAYER_DISCOVERY", snapshot.nextFocus)
    }

'''
if extra not in s:
    if marker not in s:
        raise SystemExit("diagnostic test insertion marker missing")
    s = s.replace(marker, extra + marker, 1)
t.write_text(s)

print("LSFG stability patch applied")
