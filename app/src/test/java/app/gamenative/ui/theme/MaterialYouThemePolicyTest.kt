package app.gamenative.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialYouThemePolicyTest {
    @Test
    fun materialYouRequiresAndroid12() {
        assertFalse(isMaterialYouSupported(30))
        assertTrue(isMaterialYouSupported(31))
        assertTrue(isMaterialYouSupported(36))
    }
}
