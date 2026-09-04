package app.gamenative.powercontrol

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFramePacingCoordinatorTest {
    private fun applyThreadCoordinator() = SourceFramePacingCoordinator(
        isApplyThread = { true },
        postToApplyThread = {},
        applyPendingOnApplyThread = {},
    )

    @Test
    fun sameResolvedCapAndTarget_isIdempotent() {
        val coordinator = applyThreadCoordinator()

        assertTrue(coordinator.shouldApply(30, targetToken = 1))
        assertFalse(coordinator.shouldApply(30, targetToken = 1))
    }

    @Test
    fun changedResolvedCap_emitsExactlyOneNewUpdate() {
        val coordinator = applyThreadCoordinator()

        assertTrue(coordinator.shouldApply(30, targetToken = 1))
        assertTrue(coordinator.shouldApply(24, targetToken = 1))
        assertFalse(coordinator.shouldApply(24, targetToken = 1))
    }

    @Test
    fun replacementRenderer_reappliesUnchangedCapOnce() {
        val coordinator = applyThreadCoordinator()

        assertTrue(coordinator.shouldApply(30, targetToken = 1))
        assertTrue(coordinator.shouldApply(30, targetToken = 2))
        assertFalse(coordinator.shouldApply(30, targetToken = 2))
    }

    @Test
    fun explicitLifecycleInvalidation_reappliesUnchangedCapOnce() {
        val coordinator = applyThreadCoordinator()

        assertTrue(coordinator.shouldApply(30, targetToken = 1))
        coordinator.invalidate()
        assertTrue(coordinator.shouldApply(30, targetToken = 1))
        assertFalse(coordinator.shouldApply(30, targetToken = 1))
    }

    @Test
    fun newerApplyThreadCap_invalidatesOlderQueuedBackgroundCap() {
        var onApplyThread = false
        val queued = ArrayDeque<() -> Unit>()
        val forwarded = mutableListOf<Int>()
        lateinit var coordinator: SourceFramePacingCoordinator

        coordinator = SourceFramePacingCoordinator(
            isApplyThread = { onApplyThread },
            postToApplyThread = { queued.add(it) },
            applyPendingOnApplyThread = { forwarded.add(it) },
        )

        assertFalse(coordinator.shouldApply(30, targetToken = 1))
        assertEquals(1, queued.size)

        onApplyThread = true
        assertTrue(coordinator.shouldApply(60, targetToken = 1))
        queued.removeFirst().invoke()

        assertTrue(forwarded.isEmpty())
        assertFalse(coordinator.shouldApply(60, targetToken = 1))
    }

    @Test
    fun newestBackgroundCap_isTheOnlyQueuedRevisionPromoted() {
        var onApplyThread = false
        val queued = ArrayDeque<() -> Unit>()
        val applied = mutableListOf<Int>()
        lateinit var coordinator: SourceFramePacingCoordinator

        coordinator = SourceFramePacingCoordinator(
            isApplyThread = { onApplyThread },
            postToApplyThread = { queued.add(it) },
            applyPendingOnApplyThread = { limit ->
                onApplyThread = true
                if (coordinator.shouldApply(limit, targetToken = 1)) {
                    applied.add(limit)
                }
                onApplyThread = false
            },
        )

        assertFalse(coordinator.shouldApply(30, targetToken = 1))
        assertFalse(coordinator.shouldApply(45, targetToken = 1))
        assertEquals(2, queued.size)

        queued.removeFirst().invoke()
        queued.removeFirst().invoke()

        assertEquals(listOf(45), applied)
    }

    @Test
    fun powerManager_doesNotLetLsfgCallbackConsumeSourceCapAuthority() {
        val root = if (Files.isDirectory(Paths.get("src/main/java"))) {
            Paths.get("src/main/java")
        } else {
            Paths.get("app/src/main/java")
        }
        val source = String(
            Files.readAllBytes(root.resolve("app/gamenative/powercontrol/PowerManager.kt")),
            Charsets.UTF_8,
        )

        assertFalse(source.contains("fpsCapApplier?.let { if (it(limitFps)) return true }"))
        assertTrue(source.contains("sourceFramePacingCoordinator.shouldApply"))
        assertTrue(source.contains("xServerView.setFrameRateLimit(resolvedLimit)"))
        assertTrue(source.contains("ShmFramePacer.setFrameRateLimit(resolvedLimit)"))
    }
}
