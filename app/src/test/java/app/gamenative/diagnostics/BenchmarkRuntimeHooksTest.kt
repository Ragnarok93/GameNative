package app.gamenative.diagnostics

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xserver.Window
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BenchmarkRuntimeHooksTest {
    private class Fake : BenchmarkRuntimeHooks.Session {
        override val appId = "CUSTOM_GAME_1"
        override val container = Container("BENCHMARK_TEST")
        var frames = 0
        override fun checkActive() {}
        override fun setup(started: Boolean) {}
        override fun environment(env: EnvVars) {}
        override fun content(window: Window) {}
        override fun frame() { frames++ }
        override fun renderer(sourceCap: Int, renderer: String) {}
        override fun terminated(status: Int) {}
        override fun controls(server: com.winlator.xserver.XServer, stop: () -> Unit) {}
    }

    @Test fun ownershipDoesNotRedirectOrSampleUnrelatedGames() {
        val owner = Fake()
        BenchmarkRuntimeHooks.install(owner)
        try {
            assertSame(owner.container, BenchmarkRuntimeHooks.container("CUSTOM_GAME_1"))
            assertNull(BenchmarkRuntimeHooks.container("CUSTOM_GAME_2"))
            BenchmarkRuntimeHooks.frame(Container("CUSTOM_GAME_1"))
            assertEquals(0, owner.frames)
            BenchmarkRuntimeHooks.frame(owner.container)
            assertEquals(1, owner.frames)
            assertThrows(IllegalStateException::class.java) { BenchmarkRuntimeHooks.install(Fake()) }
        } finally { BenchmarkRuntimeHooks.retire(owner) }
        assertFalse(BenchmarkRuntimeHooks.owns("BENCHMARK_TEST"))
    }
}
