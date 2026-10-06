package com.winlator.widget

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XServerFrameRatingRetargetContractTest {
    private fun source(path: String): String {
        val candidates = listOf(
            File(path),
            File("../$path"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun trackedWindowChangeResetsOnlySamplingEpoch() {
        val controller = source(
            "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreenController.kt",
        )
        val screen = source(
            "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt",
        )

        assertTrue(
            "FrameRating retarget must clear inherited short-term timing/frame count",
            controller.contains("rating.resetSamplingEpoch()"),
        )
        assertTrue(
            "XServer window lifecycle listener must remain installed after screen decomposition",
            controller.contains("addOnWindowModificationListener(wmListener)"),
        )
        assertTrue(
            "XServerScreen must delegate window lifecycle ownership to its controller",
            screen.contains("installWindowModificationListener("),
        )
        assertFalse(
            "Window retarget must not erase accumulated session statistics",
            controller.contains("rating.reset()"),
        )
    }
}
