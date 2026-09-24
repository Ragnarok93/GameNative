package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexPresentationHandoffContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun disablingApexExplicitlyHidesLatchedLayerAfterNormalPresentationIsConfirmed() {
        val renderer = repoFile(
            "app/src/main/java/com/winlator/renderer/VulkanRenderer.java",
        ).readText()

        val methodStart = renderer.indexOf("public boolean setApexFrameTargetEnabled(boolean enabled)")
        val pollStart = renderer.indexOf("public ApexFrame pollApexFrame()", methodStart)
        assertTrue(methodStart >= 0 && pollStart > methodStart)
        val method = renderer.substring(methodStart, pollStart)

        listOf(
            "retireApexPresenter()",
            "nativeGetNormalPresentSerial",
            "awaitNormalPresentationBeforeApexRelease",
            "hideApexPresenterLayerAfterNormalPresentation",
        ).forEach { token ->
            assertTrue("transactional Apex disable is missing $token", method.contains(token))
        }

        val retire = method.indexOf("retireApexPresenter()")
        val nativeDisable = method.indexOf("nativeDisableApexTarget", retire)
        val awaitNormal = method.indexOf("awaitNormalPresentationBeforeApexRelease", nativeDisable)

        assertTrue(retire >= 0 && nativeDisable > retire)
        assertTrue(awaitNormal > nativeDisable)
        assertFalse(
            "Apex layer must not be destroyed synchronously before the normal path presents",
            method.substring(retire, awaitNormal).contains("releaseApexPresenterLayer()"),
        )

        val hideStart = renderer.indexOf(
            "private void hideApexPresenterLayerAfterNormalPresentation",
            methodStart,
        )
        val pollMethod = renderer.indexOf("public ApexFrame pollApexFrame()", hideStart)
        assertTrue(hideStart > awaitNormal && pollMethod > hideStart)
        val hideMethod = renderer.substring(hideStart, pollMethod)

        assertTrue(
            "normal presentation acknowledgement must explicitly hide the opaque Apex child layer",
            hideMethod.contains("setVisibility(layer, false)"),
        )
        assertTrue(
            "Apex SurfaceControl handles must be released only after the hide transaction has crossed a frame boundary",
            hideMethod.contains("postOnAnimation") &&
                hideMethod.contains("releaseApexPresenterLayer()"),
        )
    }

    @Test
    fun normalPresentationSerialAdvancesForSwapchainAndScanout() {
        val header = repoFile("app/src/main/cpp/winlator/VulkanRendererContext.h").readText()
        val context = repoFile("app/src/main/cpp/winlator/VulkanRendererContext.cpp").readText()
        val scanout = repoFile("app/src/main/cpp/winlator/VulkanRendererScanout.cpp").readText()
        val jni = repoFile("app/src/main/cpp/winlator/vulkan_jni.cpp").readText()

        assertTrue(header.contains("normalPresentSerial"))
        assertTrue(context.contains("normalPresentSerial.fetch_add"))
        assertTrue(context.contains("QueuePresentKHR"))
        assertTrue(scanout.contains("normalPresentSerial.fetch_add"))
        assertTrue(scanout.contains("ST_APPLY"))
        assertTrue(jni.contains("nativeGetNormalPresentSerial"))
    }
}
