package app.gamenative.framegen

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import androidx.annotation.Keep
import com.winlator.renderer.VulkanRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Display-clock-driven GLES presenter for Vulkan-composited Apex source frames.
 *
 * The Vulkan renderer owns the AHardwareBuffer ring. This class only consumes a
 * ready slot, waits on its acquire fence in the GLES command stream, runs Apex,
 * exports a GLES release fence, and returns that fence to Vulkan before the slot
 * can be reused.
 */
@Keep
class ApexVulkanPresenter(
    private val renderer: VulkanRenderer,
    private val surface: Surface,
) : AutoCloseable {
    private val thread = HandlerThread("ApexVulkanPresenter", Process.THREAD_PRIORITY_DISPLAY)
    @Volatile private var running = false
    @Volatile private var nativeHandle = 0L
    private var handler: Handler? = null
    private var choreographer: Choreographer? = null
    private var hasSourceHistory = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val handle = nativeHandle
            if (handle == 0L) {
                failPresenter()
                return
            }

            val frame = renderer.pollApexFrame()
            if (frame != null) {
                val releaseFenceFd = nativePresentSourceFrame(
                    handle,
                    frame.hardwareBufferPtr,
                    frame.acquireFenceFd,
                    frame.width,
                    frame.height,
                )
                renderer.releaseApexFrame(frame, releaseFenceFd)
                hasSourceHistory = true
            } else if (hasSourceHistory) {
                nativePresentGeneratedFrame(handle)
            }

            if (running) choreographer?.postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        thread.start()
        val localHandler = Handler(thread.looper)
        handler = localHandler
        localHandler.post {
            if (!running) return@post
            val handle = nativeCreatePresenter(surface)
            if (handle == 0L) {
                failPresenter()
                return@post
            }
            nativeHandle = handle
            choreographer = Choreographer.getInstance()
            choreographer?.postFrameCallback(frameCallback)
        }
    }

    private fun failPresenter() {
        running = false
        renderer.onApexPresenterFailure()
    }

    fun stop() {
        if (!running && nativeHandle == 0L) {
            if (thread.isAlive) thread.quitSafely()
            return
        }
        running = false
        val latch = CountDownLatch(1)
        val localHandler = handler
        if (localHandler == null) {
            latch.countDown()
        } else {
            localHandler.post {
                choreographer?.removeFrameCallback(frameCallback)
                choreographer = null
                val handle = nativeHandle
                nativeHandle = 0L
                if (handle != 0L) nativeDestroyPresenter(handle)
                hasSourceHistory = false
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
        handler = null
    }

    override fun close() = stop()

    companion object {
        init {
            System.loadLibrary("gamenative_apex")
        }

        @JvmStatic private external fun nativeCreatePresenter(surface: Surface): Long
        @JvmStatic private external fun nativeDestroyPresenter(handle: Long)

        @JvmStatic
        private external fun nativePresentSourceFrame(
            handle: Long,
            hardwareBufferPtr: Long,
            acquireFenceFd: Int,
            width: Int,
            height: Int,
        ): Int

        @JvmStatic
        private external fun nativePresentGeneratedFrame(handle: Long): Boolean
    }
}
