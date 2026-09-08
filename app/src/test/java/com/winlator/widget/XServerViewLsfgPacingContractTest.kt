package com.winlator.widget

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XServerViewLsfgPacingContractTest {
    @Test
    fun sourceKeepsUpstreamSimpleFrameRateLimit() {
        val source = String(
            Files.readAllBytes(sourcePath("com/winlator/widget/XServerView.java")),
            Charsets.UTF_8,
        )
        assertTrue(source.contains("this.frameRateLimit = Math.max(0, frameRateLimit);"))
        assertTrue(source.contains("vkRenderer.setFpsLimit(this.frameRateLimit);"))
        assertFalse(source.contains("LsfgRuntimeGate"))
        assertFalse(source.contains("localFrameRateLimit"))
        assertFalse(source.contains("lsfgPacingRequested"))
        assertFalse(source.contains("transitionLsfgFramePacing"))
        assertFalse(source.contains("refreshLsfgFramePacing"))
    }

    @Test
    fun requestRenderDoesNotConsultLsfgReadiness() {
        val source = String(
            Files.readAllBytes(
                sourcePath("com/winlator/widget/XServerView.java"),
            ),
            Charsets.UTF_8,
        )
        val requestRender = source.substringAfter("public void requestRender()")
            .substringBeforeLast("}")
        assertTrue(requestRender.contains("vkRenderer.queueSceneUpdate();"))
        assertFalse(requestRender.contains("LsfgRuntimeGate"))
        assertFalse(requestRender.contains("refreshLsfgFramePacing"))
    }

    private fun sourcePath(relative: String): Path {
        val modulePath = Paths.get("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Paths.get("app/src/main/java").resolve(relative)
    }
}
