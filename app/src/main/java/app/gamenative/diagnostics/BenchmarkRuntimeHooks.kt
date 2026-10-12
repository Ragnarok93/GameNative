package app.gamenative.diagnostics

import app.gamenative.BuildConfig
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xserver.Window

/** No endpoint: installed only by debug instrumentation in the target process. */
object BenchmarkRuntimeHooks {
    interface Session {
        val appId: String
        val container: Container
        fun checkActive()
        fun setup(started: Boolean)
        fun environment(env: EnvVars)
        fun content(window: Window)
        fun frame()
        fun renderer(sourceCap: Int, renderer: String)
        fun terminated(status: Int)
    }

    @Volatile private var session: Session? = null

    @Synchronized fun install(value: Session) {
        check(BuildConfig.DEBUG && value.container.id.startsWith("BENCHMARK_"))
        check(session == null) { "A benchmark already owns the runtime" }
        session = value
    }

    @Synchronized fun retire(value: Session) {
        check(session === value)
        session = null
    }

    fun container(appId: String): Container? =
        if (BuildConfig.DEBUG) session?.takeIf { it.appId == appId }?.container else null

    fun owns(containerId: String): Boolean = BuildConfig.DEBUG && session?.container?.id == containerId

    private fun owner(container: Container): Session? =
        if (BuildConfig.DEBUG) session?.takeIf { it.container.id == container.id } else null

    @JvmStatic fun environment(container: Container, env: EnvVars) { owner(container)?.environment(env) }
    fun checkActive(container: Container) { owner(container)?.checkActive() }
    fun setup(container: Container, started: Boolean) { owner(container)?.setup(started) }
    fun content(container: Container, window: Window) { owner(container)?.content(window) }
    fun frame(container: Container) { owner(container)?.frame() }
    fun renderer(container: Container, cap: Int, renderer: String) { owner(container)?.renderer(cap, renderer) }
    fun terminated(container: Container, status: Int) { owner(container)?.terminated(status) }
}
