package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgSettingsDownloadContractTest {
    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }

    @Test
    fun graphicsSettingsEnableStartsDownloadWithoutRequiringDialogRestart() {
        val source = File(
            repoRoot,
            "app/src/main/java/app/gamenative/ui/component/dialog/GraphicsTab.kt",
        ).readText()
        val helper = source.substringAfter("fun requestLsfgEnable()")
            .substringBefore("SettingsGroup")

        assertTrue(helper.contains("LsfgVkManager.isDllAvailable()"))
        assertTrue(helper.contains("LsfgVkManager.ownsLosslessScaling()"))
        assertTrue(helper.contains("state.launchSteamAppDownload("))
        assertTrue(helper.contains("state.config.value = state.config.value.copy(lsfgEnabled = downloaded)"))
        assertTrue(
            "The no-entitlement branch must keep LSFG disabled instead of persisting an armed setting",
            helper.contains("state.config.value = state.config.value.copy(lsfgEnabled = false)"),
        )

        val unavailableBranch = source.substringAfter("// State 3: User doesn't own Lossless Scaling")
            .substringBefore("}\n        }\n    }")
        assertTrue(
            "The visible unavailable state must re-check ownership on click so late Steam library loading can start the download without an app restart",
            unavailableBranch.contains("requestLsfgEnable()"),
        )
    }
}
