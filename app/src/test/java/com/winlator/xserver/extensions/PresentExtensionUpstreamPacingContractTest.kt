package com.winlator.xserver.extensions

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentExtensionUpstreamPacingContractTest {
    @Test
    fun presentExtensionKeepsUpstreamLimitContract() {
        val source = String(
            Files.readAllBytes(sourcePath("com/winlator/xserver/extensions/PresentExtension.java")),
            Charsets.UTF_8,
        )

        assertTrue(source.contains("public void setFrameRateLimit(int limit)"))
        assertTrue(source.contains("this.frameRateLimit = Math.max(0, limit);"))
        assertFalse(source.contains("transitionFramePacing"))
        assertFalse(source.contains("refreshLsfgPacingState"))
        assertFalse(source.contains("LsfgRuntimeGate"))
        assertFalse(source.contains("generation_ready"))
        assertFalse(source.contains("cpuPendingIdles"))
        assertFalse(source.contains("resetWindowPacing"))
        assertFalse(source.contains("OnWindowModificationListener"))
        assertFalse(source.contains("OnResourceLifecycleListener"))
    }

    @Test
    fun presentPathDoesNotReevaluateLsfgReadinessPerPresent() {
        val source = String(
            Files.readAllBytes(sourcePath("com/winlator/xserver/extensions/PresentExtension.java")),
            Charsets.UTF_8,
        )
        val presentPixmap = source.substringAfter("private void presentPixmap")
            .substringBefore("private void selectInput")

        assertTrue(presentPixmap.contains("final int targetFps = this.frameRateLimit;"))
        assertFalse(presentPixmap.contains("LsfgRuntimeGate"))
        assertFalse(presentPixmap.contains("transitionLsfgFramePacing"))
        assertFalse(presentPixmap.contains("refreshLsfgPacingState"))
    }

    private fun sourcePath(relative: String): Path {
        val modulePath = Paths.get("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Paths.get("app/src/main/java").resolve(relative)
    }
}
