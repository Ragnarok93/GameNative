package app.gamenative.utils

import com.winlator.container.Container
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LsfgAdaptiveOutputTargetStoreTest {
    @Test
    fun explicitOutputTargetUsesDedicatedContainerExtra() {
        val container = mock<Container>()
        whenever(container.getExtra(LsfgVkManager.EXTRA_ADAPTIVE_OUTPUT_TARGET, "0"))
            .thenReturn("120")

        assertEquals(120, LsfgVkManager.adaptiveOutputTarget(container))
    }

    @Test
    fun persistingOutputTargetDoesNotWriteFpsLimiterExtras() {
        val container = mock<Container>()

        assertEquals(90, LsfgVkManager.setAdaptiveOutputTarget(container, 90))
        verify(container).putExtra(LsfgVkManager.EXTRA_ADAPTIVE_OUTPUT_TARGET, "90")
        verify(container).saveData()
    }
}
