package app.gamenative.powercontrol

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFramePacingCoordinatorTest {
    @Test
    fun sameResolvedCapAndTarget_isIdempotent() {
        val coordinator = SourceFramePacingCoordinator()

        assertTrue(coordinator.shouldApply(30, targetToken = 1))
        assertFalse(coordinator.shouldApply(30, targetToken = 1))
    }

    @Test
    fun changedResolvedCap_emitsExactlyOneNewUpdate() {
        val coordinator = SourceFramePacingCoordinator()

        assertTrue(coordinator.shouldApply(30, targetToken = 1))
        assertTrue(coordinator.shouldApply(24, targetToken = 1))
        assertFalse(coordinator.shouldApply(24, targetToken = 1))
    }

    @Test
    fun replacementRenderer_reappliesUnchangedCapOnce() {
        val coordinator = SourceFramePacingCoordinator()

        assertTrue(coordinator.shouldApply(30, targetToken = 1))
        assertTrue(coordinator.shouldApply(30, targetToken = 2))
        assertFalse(coordinator.shouldApply(30, targetToken = 2))
    }

    @Test
    fun explicitLifecycleInvalidation_reappliesUnchangedCapOnce() {
        val coordinator = SourceFramePacingCoordinator()

        assertTrue(coordinator.shouldApply(30, targetToken = 1))
        coordinator.invalidate()
        assertTrue(coordinator.shouldApply(30, targetToken = 1))
        assertFalse(coordinator.shouldApply(30, targetToken = 1))
    }

    @Test
    fun powerManager_doesNotLetLsfgCallbackConsumeSourceCapAuthority() {
        val root = if (Files.isDirectory(Paths.get("src/main/java"))) {
            Paths.get("src/main/java")
        } else {
            Paths.get("app/src/main/java")
        }
        val source = Files.readString(root.resolve("app/gamenative/powercontrol/PowerManager.kt"))

        assertFalse(source.contains("fpsCapApplier?.let { if (it(limitFps)) return true }"))
        assertTrue(source.contains("sourceFramePacingCoordinator.shouldApply"))
        assertTrue(source.contains("xServerView.setFrameRateLimit(resolvedLimit)"))
        assertTrue(source.contains("ShmFramePacer.setFrameRateLimit(resolvedLimit)"))
    }
}
