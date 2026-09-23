package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexVulkanPresenterContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun presenterOwnsDedicatedEglSurfaceAndGpuFenceHandoff() {
        val presenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()
        val native = repoFile(
            "app/src/main/cpp/apex/apex_vulkan_presenter.cpp",
        ).readText()
        val renderer = repoFile(
            "app/src/main/java/com/winlator/renderer/VulkanRenderer.java",
        ).readText()

        listOf(
            "HandlerThread",
            "Choreographer",
            "nativeCreatePresenter",
            "nativePresentSourceFrame",
            "nativePresentGeneratedFrame",
            "renderer.releaseApexFrame",
        ).forEach { token ->
            assertTrue("presenter is missing $token", presenter.contains(token))
        }

        listOf(
            "eglGetNativeClientBufferANDROID",
            "EGL_SYNC_NATIVE_FENCE_ANDROID",
            "EGL_SYNC_NATIVE_FENCE_FD_ANDROID",
            "eglWaitSyncKHR",
            "eglDupNativeFenceFDANDROID",
            "ApexEngine::getInstance().processFrame",
        ).forEach { token ->
            assertTrue("native presenter is missing $token", native.contains(token))
        }

        assertFalse(
            "normal acquire-fence handoff must stay GPU-side",
            native.contains("poll(") || native.contains("sync_wait("),
        )

        listOf(
            "ApexVulkanPresenter",
            "apexGameSurface",
            "apexGameSurfaceControl",
            "gamenative_apex_presenter",
        ).forEach { token ->
            assertTrue("VulkanRenderer presenter lifecycle is missing $token", renderer.contains(token))
        }
    }

    @Test
    fun generatedPulseDoesNotDependOnReleasedSourceAhb() {
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val native = repoFile("app/src/main/cpp/apex/apex_vulkan_presenter.cpp").readText()

        assertTrue(
            "generated pulses must be legal without retaining the source AHB texture",
            pipeline.contains("if (isNewRealFrame && inputTextureId == 0)"),
        )
        assertTrue(
            "generated presentation must invoke Apex with no source texture dependency",
            native.contains("processFrame(0, 0,"),
        )
        assertTrue(
            "source imports must be released after a release fence is exported",
            native.contains("destroyImportedSource"),
        )
    }
}
