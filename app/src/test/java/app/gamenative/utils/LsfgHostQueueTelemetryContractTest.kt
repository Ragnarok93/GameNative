package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
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
        val modulePath = Path.of("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Path.of("app/src/main/java").resolve(relative)
    }
}
