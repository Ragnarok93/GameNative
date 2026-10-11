package app.gamenative.utils

import android.app.Application
import com.winlator.container.Container
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LsfgSettingsStabilityAuditTest {
    @Test fun disabledFixedSettingsDoNotRestoreSavedMultiplier() {
        val container = mock<Container>()
        whenever(container.getExtra(LsfgVkManager.EXTRA_MULTIPLIER, "2")).thenReturn("0")
        whenever(container.getExtra(LsfgVkManager.EXTRA_FIXED_MULTIPLIER, "2")).thenReturn("4")
        whenever(container.getExtra(LsfgVkManager.EXTRA_FRAMEGEN_MODE, "fixed")).thenReturn("fixed")
        whenever(container.getExtra(LsfgVkManager.EXTRA_FLOW_SCALE, "0.80")).thenReturn("0.80")
        whenever(container.getExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, "true")).thenReturn("true")
        assertEquals(0, LsfgQuickMenuHelper.readSettings(container).multiplier)
    }

    @Test fun nonFiniteFlowSettingsBecomeUsableDefault() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0.80f, LsfgQuickMenuHelper.sanitizeFlowScale(value), 0f)
            val container = mock<Container>()
            whenever(container.getExtra(LsfgVkManager.EXTRA_FLOW_SCALE, "0.80")).thenReturn(value.toString())
            assertEquals(0.80f, LsfgVkManager.flowScale(container), 0f)
        }
    }

    @Test fun supersededBackendOwnerCannotAllocateNewRevision() {
        val container = mock<Container>()
        assertNull(LsfgVkManager.reserveRuntimeRequestForOwner(container,
            LsfgVkManager.currentBackendGeneration() - 1))
    }
}
