package app.gamenative.utils

import com.winlator.container.Container
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Contract note for the PowerManager -> LSFG callback boundary: source-cap
 * probes are allowed to reach [LsfgQuickMenuHelper.applyLiveFpsCap], but that
 * callback must not rewrite conf.toml or LSFG's independent output objective.
 * The behavioral manager tests cover the serialized output-target side.
 */
class LsfgQuickMenuHelperContractTest {
    @Test
    fun sourceCapCallbackDoesNotRequireAConfigFile() {
        val container = mock<Container>()
        whenever(container.getExtra(LsfgVkManager.EXTRA_ADAPTIVE_OUTPUT_TARGET, "0"))
            .thenReturn("120")

        // A previous implementation attempted a config hot-reload here, which
        // required conf.toml and coupled PowerManager's source cap to fps_limit.
        LsfgQuickMenuHelper.applyLiveFpsCap(container, 30)
    }
}
