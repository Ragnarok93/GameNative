package com.winlator.xserver.extensions

import android.view.Choreographer
import app.gamenative.utils.LsfgRuntimeGate
import com.winlator.renderer.VulkanRenderer
import com.winlator.xserver.Pixmap
import com.winlator.xserver.Window
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PresentExtensionPacingTransitionTest {
    @Test
    fun transitionFramePacing_keepsLocalLimitUntilNativeLsfgIsReady() {
        val root = Files.createTempDirectory("lsfg-not-ready")
        LsfgRuntimeGate.configure(root.toFile())
        val extension = PresentExtension()
        val timingsField = PresentExtension::class.java
            .getDeclaredField("windowTimings")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val timings = timingsField.get(extension) as ConcurrentHashMap<Int, Any>
        val timingClass = PresentExtension::class.java.declaredClasses
            .first { it.simpleName == "WindowTiming" }
        val timing = timingClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        timings[7] = timing

        val transition = PresentExtension::class.java.methods.firstOrNull {
            it.name == "transitionFramePacing" && it.parameterTypes.contentEquals(
                arrayOf(Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            )
        }
        assertNotNull("Present pacing needs an explicit LSFG ownership transition", transition)
        transition!!.invoke(extension, true, 60)

        assertTrue(timings.isEmpty())
        assertEquals(60, privateField(extension, "frameRateLimit"))
        assertEquals(false, privateField(extension, "eagerIdleRelease"))
    }

    @Test
    fun transitionFramePacing_handsOwnershipToFreshNativeReadyContext() {
        val root = Files.createTempDirectory("lsfg-ready")
        val stats = root.resolve(".config/lsfg-vk/stats.txt")
        Files.createDirectories(stats.parent)
        Files.write(stats, "active=1\ngeneration_ready=1\n".toByteArray(Charsets.UTF_8))
        LsfgRuntimeGate.configure(root.toFile())

        val extension = PresentExtension()
        extension.transitionFramePacing(true, 60)

        assertEquals(0, privateField(extension, "frameRateLimit"))
        assertEquals(true, privateField(extension, "eagerIdleRelease"))
    }

    @Test
    fun transitionFramePacing_rejectsStaleNativeReadyState() {
        val root = Files.createTempDirectory("lsfg-stale")
        val stats = root.resolve(".config/lsfg-vk/stats.txt")
        Files.createDirectories(stats.parent)
        Files.write(stats, "active=1\ngeneration_ready=1\n".toByteArray(Charsets.UTF_8))
        Files.setLastModifiedTime(
            stats,
            FileTime.fromMillis(System.currentTimeMillis() - 10_000L),
        )
        LsfgRuntimeGate.configure(root.toFile())

        val extension = PresentExtension()
        extension.transitionFramePacing(true, 60)

        assertEquals(60, privateField(extension, "frameRateLimit"))
        assertEquals(false, privateField(extension, "eagerIdleRelease"))
    }

    @Test
    fun scheduledPresent_releasesSupersededPixmapEvenWhenLsfgIsOff() {
        val extension = PresentExtension()
        val sync = mock(SyncExtension::class.java)
        setPrivateField(extension, "syncExtension", sync)

        val window = Window(7, null, 0, 0, 1, 1, null)
        val firstPixmap = mock(Pixmap::class.java)
        val superseded = PresentExtension.PendingIdle(window, firstPixmap, 1, 101, 0, 0)

        extension.releaseSupersededIdle(superseded)

        verify(sync).setTriggered(101)
    }

    @Test
    fun scheduledPresent_usesCpuDeadlineEvenWhenChoreographerIsAvailable() {
        val extension = PresentExtension()
        try {
            val choreographerField = PresentExtension::class.java.declaredFields
                .firstOrNull { it.name == "choreographer" }
            if (choreographerField != null) {
                choreographerField.isAccessible = true
                choreographerField.set(extension, Choreographer.getInstance())
                PresentExtension::class.java.getDeclaredField("choreographerChecked")
                    .apply { isAccessible = true }
                    .setBoolean(extension, true)
            }

            val window = Window(7, null, 0, 0, 1, 1, null)
            val pixmap = mock(Pixmap::class.java)
            val schedule = PresentExtension::class.java.getDeclaredMethod(
                "scheduleIdleNotify",
                Window::class.java,
                Pixmap::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                VulkanRenderer::class.java,
            ).apply { isAccessible = true }
            schedule.invoke(extension, window, pixmap, 1, 101, 1, null)

            @Suppress("UNCHECKED_CAST")
            val cpuPending = privateField(extension, "cpuPendingIdles") as ConcurrentHashMap<Int, Any>
            @Suppress("UNCHECKED_CAST")
            val displayPending = privateField(extension, "pendingIdles") as ConcurrentHashMap<Int, Any>

            assertTrue("Present limiter must use monotonic CPU deadlines", cpuPending.containsKey(7))
            assertTrue("Present limiter must not quantize deadlines to Choreographer", displayPending.isEmpty())
        } finally {
            extension.close()
        }
    }

    @Test
    fun unmapWindow_releasesCpuIdleAndClearsDeadlineState() {
        val extension = PresentExtension()
        val sync = mock(SyncExtension::class.java)
        setPrivateField(extension, "syncExtension", sync)

        val window = Window(7, null, 0, 0, 1, 1, null)
        val pixmap = mock(Pixmap::class.java)
        val pending = PresentExtension.PendingIdle(window, pixmap, 1, 201, Long.MAX_VALUE, 0)

        @Suppress("UNCHECKED_CAST")
        val cpuPending = privateField(extension, "cpuPendingIdles") as ConcurrentHashMap<Int, PresentExtension.PendingIdle>
        @Suppress("UNCHECKED_CAST")
        val cpuQueue = privateField(extension, "cpuQueue") as PriorityBlockingQueue<PresentExtension.PendingIdle>
        @Suppress("UNCHECKED_CAST")
        val timings = privateField(extension, "windowTimings") as ConcurrentHashMap<Int, Any>
        val timingClass = PresentExtension::class.java.declaredClasses
            .first { it.simpleName == "WindowTiming" }
        val timing = timingClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        cpuPending[7] = pending
        cpuQueue.offer(pending)
        timings[7] = timing

        extension.onUnmapWindow(window)

        assertFalse(cpuPending.containsKey(7))
        assertTrue(cpuQueue.none { it.window.id == 7 })
        assertFalse(timings.containsKey(7))
        verify(sync).setTriggered(201)
    }

    @Test
    fun freeWindow_releasesLegacyPendingIdleAndClearsDeadlineState() {
        val extension = PresentExtension()
        val sync = mock(SyncExtension::class.java)
        setPrivateField(extension, "syncExtension", sync)

        val window = Window(9, null, 0, 0, 1, 1, null)
        val pixmap = mock(Pixmap::class.java)
        val pending = PresentExtension.PendingIdle(window, pixmap, 1, 301, Long.MAX_VALUE, 0)

        @Suppress("UNCHECKED_CAST")
        val displayPending = privateField(extension, "pendingIdles") as ConcurrentHashMap<Int, PresentExtension.PendingIdle>
        @Suppress("UNCHECKED_CAST")
        val timings = privateField(extension, "windowTimings") as ConcurrentHashMap<Int, Any>
        val timingClass = PresentExtension::class.java.declaredClasses
            .first { it.simpleName == "WindowTiming" }
        val timing = timingClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        displayPending[9] = pending
        timings[9] = timing

        extension.onFreeResource(window)

        assertFalse(displayPending.containsKey(9))
        assertFalse(timings.containsKey(9))
        verify(sync).setTriggered(301)
    }

    private fun privateField(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun setPrivateField(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }
}
