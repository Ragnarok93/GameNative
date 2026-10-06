package com.winlator.xenvironment.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseAudioAssetContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun configuredPulseAudioAssetExistsAndXServerUsesSingleSourceOfTruth() {
        val component =
            repoFile("app/src/main/java/com/winlator/xenvironment/components/PulseAudioComponent.java")
                .readText()
        val xserver =
            repoFile("app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt")
                .readText()

        val match = Regex(
            """BUNDLED_ASSET_NAME\s*=\s*"([^"]+)"""",
        ).find(component) ?: error("PulseAudio bundled asset constant is missing")
        val assetName = match.groupValues[1]

        assertTrue(assetName.startsWith("pulseaudio-gamenative-"))
        assertTrue(assetName.endsWith(".tzst"))
        assertTrue(repoFile("app/src/main/assets/$assetName").isFile)
        assertTrue(xserver.contains("PulseAudioComponent.BUNDLED_ASSET_NAME"))
        assertFalse(xserver.contains("pulseaudio-gamenative-20260612.tzst"))
    }

    @Test
    fun pulseAudioRefusesIncompleteRuntimeInsteadOfStartingBrokenDaemon() {
        val component =
            repoFile("app/src/main/java/com/winlator/xenvironment/components/PulseAudioComponent.java")
                .readText()

        assertTrue(component.contains("module-native-protocol-unix.so"))
        assertTrue(component.contains("module-aaudio-sink.so"))
        assertTrue(component.contains("isRuntimeAvailable(context, enableAudioOutput)"))
        assertTrue(component.contains("PulseAudio runtime incomplete after extraction"))
    }
}
