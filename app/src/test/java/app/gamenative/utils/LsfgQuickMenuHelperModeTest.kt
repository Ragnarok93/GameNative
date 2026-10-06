package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgQuickMenuHelperModeTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun runtimePublicationSnapshotsFramegenAndFlowModesTogether() {
        val helper = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgQuickMenuHelper.kt",
        ).readText()
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt",
        ).readText()

        assertTrue(
            "Runtime publication must snapshot generation + Flow mode before debounce",
            helper.contains("RuntimeConfigSnapshot") &&
                helper.contains("generationMode = generationMode(container)") &&
                helper.contains("flowScaleMode = flowScaleMode(container)"),
        )
        assertTrue(
            "Published runtime config must use the captured generation mode",
            helper.contains("snapshot.generationMode"),
        )
        assertTrue(
            "Published runtime config must use the captured Flow mode",
            helper.contains("snapshot.flowScaleMode"),
        )
        assertTrue(
            "Native config writer must accept explicit framegen/Flow mode values instead of rereading mutable container state",
            manager.contains("adaptiveFramegen: Boolean") &&
                manager.contains("adaptiveFlowScale: Boolean"),
        )
    }

    @Test
    fun fixedModeSnapshotsUseDedicatedFixedMultiplierAndNativeMailboxRefresh() {
        val helper = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgQuickMenuHelper.kt",
        ).readText()
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt",
        ).readText()

        assertTrue(helper.contains("LsfgVkManager.fixedMultiplier(container)"))
        assertTrue(helper.contains("if (generationMode(container) == FrameGenerationMode.FIXED)"))
        assertTrue(manager.contains("else fixedMultiplier(container)"))
        assertTrue(helper.contains("Native timed presentation prefers Mailbox"))
        assertTrue(helper.contains("renderer.setVkPresentMode("))
        assertTrue(helper.contains("renderer.setLsfgFrameQueue("))
        assertTrue(helper.contains("frameQueueEnabled(container) &&"))
        assertTrue(helper.contains("LsfgVkManager.isArmed(container)"))
        assertTrue(helper.contains("sanitizeMultiplier(LsfgVkManager.multiplier(container)) >= 2"))
        assertTrue(!helper.contains("!LsfgVkManager.isNativeBackend(container) && frameQueueEnabled"))
    }

    @Test
    fun suspendedQuickMenuDoesNotTimeoutLsfgRuntimeHandoff() {
        val handoff = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgRuntimeHandoffController.kt",
        ).readText()

        assertTrue(
            "Runtime acknowledgement timeout must pause while the guest is suspended by Quick Menu",
            handoff.contains("isOverlayPaused()") &&
                handoff.contains("activePollingElapsedMs"),
        )
    }

    @Test
    fun disabledMultiplierIsSeparateFromPersistedGenerationMode() {
        assertEquals(0, LsfgQuickMenuHelper.sanitizeMultiplier(0))
        assertEquals(
            setOf(
                LsfgQuickMenuHelper.FrameGenerationMode.FIXED,
                LsfgQuickMenuHelper.FrameGenerationMode.ADAPTIVE,
            ),
            LsfgQuickMenuHelper.FrameGenerationMode.values().toSet(),
        )
    }

    @Test
    fun flowScaleModeAndPresetRemainIndependentFromFrameGenerationMode() {
        assertEquals(
            setOf(
                LsfgQuickMenuHelper.FlowScaleMode.FIXED,
                LsfgQuickMenuHelper.FlowScaleMode.ADAPTIVE,
            ),
            LsfgQuickMenuHelper.FlowScaleMode.values().toSet(),
        )
        assertEquals(
            setOf(
                LsfgQuickMenuHelper.AdaptiveFlowPreset.QUALITY,
                LsfgQuickMenuHelper.AdaptiveFlowPreset.BALANCED,
                LsfgQuickMenuHelper.AdaptiveFlowPreset.LOW,
                LsfgQuickMenuHelper.AdaptiveFlowPreset.AUTO,
            ),
            LsfgQuickMenuHelper.AdaptiveFlowPreset.values().toSet(),
        )
    }

    @Test
    fun frameQueueTargetsMapToBoundedPresenterDepths() {
        assertEquals(0, LsfgQuickMenuHelper.FrameQueueTarget.UNBUFFERED.depth)
        assertEquals(1, LsfgQuickMenuHelper.FrameQueueTarget.BALANCED.depth)
        assertEquals(2, LsfgQuickMenuHelper.FrameQueueTarget.SMOOTH.depth)
    }

    @Test
    fun supportedFixedMultipliersRemainInRange() {
        assertEquals(2, LsfgQuickMenuHelper.sanitizeMultiplier(2))
        assertEquals(4, LsfgQuickMenuHelper.sanitizeMultiplier(4))
        assertEquals(4, LsfgQuickMenuHelper.sanitizeMultiplier(5))
    }
}
