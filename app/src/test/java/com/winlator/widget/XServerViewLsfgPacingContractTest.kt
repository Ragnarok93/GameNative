package com.winlator.widget

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XServerViewLsfgPacingContractTest {
    @Test
    fun rendererSourceLimit_neverTransfersToNativeReadiness() {
        val source = String(
            Files.readAllBytes(sourcePath("com/winlator/widget/XServerView.java")),
            Charsets.UTF_8,
        )

        assertFalse(
            "Native generation readiness is telemetry, not authority over GameNative source pacing",
            source.contains("LsfgRuntimeGate.isGenerationReady()"),
        )
        assertFalse(source.contains("nativeReady ? 0 : localFrameRateLimit"))
        assertTrue(source.contains("transitionLsfgFramePacing"))
        assertTrue(source.contains("applyEffectiveFrameRateLimit(this.localFrameRateLimit)"))
    }

    @Test
    fun renderHotPath_doesNotReevaluateLsfgPacingOwnership() {
        val source = String(
            Files.readAllBytes(sourcePath("com/winlator/widget/XServerView.java")),
            Charsets.UTF_8,
        )
        val requestRender = source.substringAfter("public void requestRender()")
            .substringBeforeLast("}")

        assertFalse(requestRender.contains("refreshLsfgFramePacing"))
        assertFalse(requestRender.contains("LsfgRuntimeGate"))
    }

    @Test
    fun presentHotPath_doesNotTransitionOrRefreshSourcePacing() {
        val source = String(
            Files.readAllBytes(
                sourcePath("com/winlator/xserver/extensions/PresentExtension.java"),
            ),
            Charsets.UTF_8,
        )
        val presentPixmap = source.substringAfter("private void presentPixmap(")
            .substringBefore("private void selectInput(")

        assertFalse(presentPixmap.contains("refreshLsfgPacingState()"))
        assertFalse(presentPixmap.contains("transitionLsfgFramePacing"))
        assertFalse(source.contains("pending idle superseded and dropped"))
    }

    private fun sourcePath(relative: String): Path {
        val modulePath = Paths.get("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Paths.get("app/src/main/java").resolve(relative)
    }
}
