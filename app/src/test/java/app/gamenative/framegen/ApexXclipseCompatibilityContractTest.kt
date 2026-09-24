package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexXclipseCompatibilityContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun xclipseUsesCapabilitySelectedFp32PrivateStorage() {
        val profile = repoFile("app/src/main/cpp/apex/apex_gpu_profile.cpp").readText()
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val presenter = repoFile("app/src/main/cpp/apex/apex_vulkan_presenter.cpp").readText()

        assertTrue(profile.contains("MotionStorage::Rgba32f"))
        assertTrue(profile.contains("capabilities.rgba32fImageStore"))
        assertTrue(profile.contains("capabilities.r32fImageStore"))

        assertTrue(engine.contains("setGpuProfile"))
        assertTrue(engine.contains("MotionStorage mMotionStorage"))

        assertTrue(pipeline.contains("GL_RGBA32F"))
        assertTrue(pipeline.contains("rgba32f"))
        assertTrue(pipeline.contains("motionStorageFormat()"))
        assertTrue(pipeline.contains("precisionShaderSource"))

        assertTrue(presenter.contains("probeImageStoreFormat(GL_R32F"))
        assertTrue(presenter.contains("probeImageStoreFormat(GL_RGBA32F"))
        assertTrue(presenter.contains("GpuProfile::XclipseCompatibility"))
        assertTrue(presenter.contains("setGpuProfile("))
        assertTrue(presenter.contains("decision.profile"))
        assertTrue(presenter.contains("decision.motionStorage"))
        assertFalse(
            "Xclipse compatibility must not CPU-wait the Vulkan acquire fence",
            presenter.contains("sync_wait(") || presenter.contains("poll("),
        )
    }

    @Test
    fun xclipseCompatibilityKeepsAhbTransportOnly() {
        val presenter = repoFile("app/src/main/cpp/apex/apex_vulkan_presenter.cpp").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        assertTrue(presenter.contains("eglGetNativeClientBufferANDROID"))
        assertTrue(presenter.contains("EGL_SYNC_NATIVE_FENCE_ANDROID"))
        assertTrue(presenter.contains("eglDupNativeFenceFDANDROID"))

        assertFalse(
            "The imported AHB must remain a sampled transport source, not Apex compute storage",
            pipeline.contains("AHardwareBuffer"),
        )
    }
}
