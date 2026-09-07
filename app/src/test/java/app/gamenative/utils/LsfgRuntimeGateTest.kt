package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgRuntimeGateTest {
    @Test
    fun readyState_isPublishedAsynchronouslyWithoutBlockingCaller() {
        val root = Files.createTempDirectory("lsfg-gate-ready")
        val stats = root.resolve(".config/lsfg-vk/stats.txt")
        Files.createDirectories(stats.parent)
        Files.write(stats, "active=1\ngeneration_ready=1\n".toByteArray(Charsets.UTF_8))

        LsfgRuntimeGate.configure(root.toFile())

        // The render-path caller only observes the fail-closed cache. The
        // filesystem read happens on the gate's background poll thread.
        assertFalse(LsfgRuntimeGate.isGenerationReady())
        assertTrue(awaitReadyState(true))
    }

    @Test
    fun readyState_failsClosedForInactiveOrStaleStats() {
        val inactiveRoot = Files.createTempDirectory("lsfg-gate-inactive")
        val inactive = inactiveRoot.resolve(".config/lsfg-vk/stats.txt")
        Files.createDirectories(inactive.parent)
        Files.write(inactive, "active=0\ngeneration_ready=0\n".toByteArray(Charsets.UTF_8))
        LsfgRuntimeGate.configure(inactiveRoot.toFile())
        assertFalse(LsfgRuntimeGate.isGenerationReady())

        val staleRoot = Files.createTempDirectory("lsfg-gate-stale")
        val stale = staleRoot.resolve(".config/lsfg-vk/stats.txt")
        Files.createDirectories(stale.parent)
        Files.write(stale, "active=1\ngeneration_ready=1\n".toByteArray(Charsets.UTF_8))
        Files.setLastModifiedTime(
            stale,
            FileTime.fromMillis(System.currentTimeMillis() - 10_000L),
        )
        LsfgRuntimeGate.configure(staleRoot.toFile())
        assertFalse(LsfgRuntimeGate.isGenerationReady())
    }

    private fun awaitReadyState(expected: Boolean, timeoutMs: Long = 1_000L): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var observed = LsfgRuntimeGate.isGenerationReady()
        while (observed != expected && System.nanoTime() < deadline) {
            Thread.sleep(10L)
            observed = LsfgRuntimeGate.isGenerationReady()
        }
        return observed == expected
    }
}
