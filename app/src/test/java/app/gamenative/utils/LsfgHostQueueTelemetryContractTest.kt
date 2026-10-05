package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgHostQueueTelemetryContractTest {
    @Test
    fun queueTelemetrySeparatesSubmitAndPresentOperations() {
        val source = String(
            Files.readAllBytes(sourcePath("app/src/main/cpp/winlator/VulkanRendererContext.cpp")),
            Charsets.UTF_8,
        )

        assertTrue(source.contains("event=graphics-queue-submit"))
        assertTrue(source.contains("graphics_queue_submit_serial"))
        assertTrue(source.contains("graphics_queue_submit_ms"))
        assertTrue(source.contains("graphics_queue_blocked_ms"))
        assertTrue(source.contains("event=present-queue-op"))
        assertTrue(source.contains("present_queue_present_serial"))
        assertTrue(source.contains("present_queue_present_ms"))
        assertTrue(source.contains("present_queue_blocked_ms"))
        assertTrue(source.contains("render_complete_semaphore"))
        assertTrue(source.contains("present_queue_wait_semaphore_ms=-1.000"))
        assertTrue(source.contains("graphics_queue_idle_ms=-1.000"))
        assertTrue(source.contains("present_queue_idle_ms=-1.000"))
    }

    @Test
    fun queueTelemetryDoesNotIntroduceIdleWaitSynchronization() {
        val source = String(
            Files.readAllBytes(sourcePath("app/src/main/cpp/winlator/VulkanRendererContext.cpp")),
            Charsets.UTF_8,
        )

        assertFalse(source.contains("QueueWaitIdle(presentQueue"))
        assertFalse(source.contains("QueueWaitIdle(graphicsQueue"))
    }

    private fun sourcePath(relative: String): Path {
        val candidates = listOf(
            Paths.get("src/main/java").resolve(relative),
            Paths.get("app/src/main/java").resolve(relative),
            Paths.get(relative),
            Paths.get("app").resolve(relative),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("Unable to locate $relative from test working directory")
    }
}
