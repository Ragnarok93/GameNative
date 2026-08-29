package app.gamenative.utils

import com.winlator.container.Container
import java.io.File

/**
 * Read-only LSFG compatibility diagnostics.
 *
 * This object intentionally does not mutate container state, launch environment,
 * manifests, or configuration. It exists to classify where LSFG stops working.
 */
object LsfgCompatibilityDiagnostics {
    enum class Status { PASS, WARN, FAIL, SKIP, INFO }

    data class Check(
        val id: String,
        val status: Status,
        val detail: String,
    )

    data class ContainerSnapshot(
        val containerId: String,
        val checks: List<Check>,
        val nextFocus: String,
    ) {
        fun check(id: String): Check? = checks.firstOrNull { it.id == id }
    }

    internal fun inspectContainer(
        container: Container,
        activeHome: File?,
        nowMs: Long,
        logs: String,
    ): ContainerSnapshot = ContainerSnapshot(
        containerId = container.id,
        checks = emptyList(),
        nextFocus = "DIAGNOSTICS_NOT_IMPLEMENTED",
    )
}
