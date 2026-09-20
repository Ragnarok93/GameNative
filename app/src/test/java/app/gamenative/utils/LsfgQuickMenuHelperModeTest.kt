package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LsfgQuickMenuHelperModeTest {
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
            ),
            LsfgQuickMenuHelper.AdaptiveFlowPreset.values().toSet(),
        )
    }

    @Test
    fun supportedFixedMultipliersRemainInRange() {
        assertEquals(2, LsfgQuickMenuHelper.sanitizeMultiplier(2))
        assertEquals(4, LsfgQuickMenuHelper.sanitizeMultiplier(4))
        assertEquals(4, LsfgQuickMenuHelper.sanitizeMultiplier(5))
    }
}
