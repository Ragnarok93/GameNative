package app.gamenative.utils

import android.app.Application
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.metrics.MetricsSnapshot
import app.gamenative.powercontrol.metrics.CpuUsageSource
import com.winlator.container.Container
import com.winlator.renderer.VulkanRenderer
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(VulkanRenderer::class, isInAndroidSdk = false)
class LsfgAuditRendererShadow {
    companion object {
        @JvmStatic @Implementation fun __staticInitializer__() {}
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [LsfgAuditRendererShadow::class])
class LsfgNativeActivationInteractionAuditTest {
    private lateinit var root: File
    private lateinit var renderer: VulkanRenderer
    private lateinit var container: Container
    private var wsi = 100L
    private var confirmed = 100L
    private var source = 200L
    private var accepted = 300L
    private var opportunities = 200L
    private var initialized = true
    private val originals = mutableMapOf<java.lang.reflect.Field, Any?>()
    private val atomicOriginals = mutableMapOf<AtomicLong, Long>()
    private var originalPacing = PowerManager.frameGenerationPacing

    private fun put(name: String, value: Any?) {
        val field = LsfgVkManager::class.java.getDeclaredField(name).apply { isAccessible = true }
        field.set(LsfgVkManager, value)
    }
    private fun atomic(name: String): AtomicLong =
        LsfgVkManager::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(LsfgVkManager) as AtomicLong
    private fun snapshot(target: Int = 120, revision: Long = 10, generation: Long = 20) =
        LsfgVkManager.NativeRuntimeConfigSnapshot(revision, generation, "native", true,
            if (target > 0) "adaptive" else "fixed", 4, target, "fixed", "quality", 0.8f,
            true, "mailbox", true, 1, 120f)
    private fun commit(snapshot: LsfgVkManager.NativeRuntimeConfigSnapshot): Boolean =
        LsfgVkManager::class.java.declaredMethods.single { it.name == "commitNativeApply" }
            .apply { isAccessible = true }
            .invoke(LsfgVkManager, renderer, container, snapshot, 30L, initialized, false, null) as Boolean

    @Before fun setup() {
        for (field in LsfgVkManager::class.java.declaredFields) {
            field.isAccessible = true
            if (field.type == AtomicLong::class.java) {
                val value = field.get(LsfgVkManager) as AtomicLong
                atomicOriginals[value] = value.get()
            } else if (!Modifier.isFinal(field.modifiers)) originals[field] = field.get(LsfgVkManager)
        }
        originalPacing = PowerManager.frameGenerationPacing
        root = Files.createTempDirectory("native-health-audit").toFile()
        container = mock()
        whenever(container.rootDir).thenReturn(root)
        whenever(container.getExtra(LsfgVkManager.EXTRA_BACKEND, "legacy")).thenReturn("native")
        renderer = mock()
        whenever(renderer.isFrameGenerationSupported()).thenAnswer { initialized }
        whenever(renderer.hasNativeSurface()).thenReturn(true)
        whenever(renderer.getGeneratedPresentedFrameCount()).thenAnswer { wsi }
        whenever(renderer.getGeneratedDisplayConfirmedFrameCount()).thenAnswer { confirmed }
        whenever(renderer.getSourceFrameCount()).thenAnswer { source }
        whenever(renderer.getPresentedFrameCount()).thenAnswer { accepted }
        whenever(renderer.getGenerationOpportunityCount()).thenAnswer { opportunities }
        whenever(renderer.isDisplayConfirmationAvailable()).thenReturn(true)
        whenever(renderer.getNativeConfirmedOutputFps()).thenReturn(120f)
        put("nativeRendererRef", WeakReference(renderer)); put("nativeRendererContainer", container)
        put("nativeStateRenderer", null); put("runtimeMeasurementPolicy", null)
        put("nativeActivationOwnerGeneration", -1L)
        atomic("nativeConfigRevision").set(10); atomic("backendRequestSerial").set(20)
        atomic("nativeApplyGeneration").set(30)
        put("latestNativeSnapshot", snapshot())
        assertTrue(commit(snapshot()))
        LsfgVkManager.readRuntimeState(container)
    }

    @After fun cleanup() {
        for ((field, value) in originals) field.set(LsfgVkManager, value)
        for ((value, saved) in atomicOriginals) value.set(saved)
        PowerManager.setFrameGenerationPacing(originalPacing.active, originalPacing.sourceSampleStride,
            originalPacing.multiplier, originalPacing.adaptiveTargetFps, originalPacing.displayRefreshFps)
        root.deleteRecursively()
    }

    private fun window(input: Long, sourceAccepted: Long = input, generated: Long = 0,
        demand: Long = generated): LsfgVkManager.RuntimeState {
        source += input; accepted += sourceAccepted + generated; wsi += generated
        confirmed += generated; opportunities += demand
        put("nativeStateSampleNs", System.nanoTime() - 501_000_000L)
        return LsfgVkManager.readRuntimeState(container)
    }

    private fun restartWithoutGeneratedHistory() {
        wsi = 0L; confirmed = 0L
        put("nativeActivationOwnerGeneration", -1L)
        assertTrue(commit(snapshot()))
        LsfgVkManager.readRuntimeState(container)
    }

    @Test fun historicalWsiDoesNotActivateNewOwner() {
        assertFalse(LsfgVkManager.readRuntimeState(container).generationReady)
    }
    @Test fun freshGenerationActivatesCurrentOwner() {
        assertTrue(window(15, generated = 45).generationReady)
    }
    @Test fun sourceAlreadyAtTargetRequiresTwoUniqueDeliveryWindows() {
        restartWithoutGeneratedHistory()
        assertFalse(window(60).generationReady)
        val state = window(60)
        assertTrue(state.generationReady)
        assertTrue(state.sourceOnly)
        assertEquals(LsfgVkManager.NativeHealthState.SOURCE_CADENCE_SUFFICIENT, state.nativeHealthState)
    }
    @Test fun collapsedAdaptiveCapBelowTargetCannotPretendDemandIsSatisfied() {
        restartWithoutGeneratedHistory()
        repeat(8) {
            val state = window(15)
            assertFalse(state.generationReady)
            assertNotEquals(LsfgVkManager.NativeHealthState.GENERATION_STALLED, state.nativeHealthState)
        }
    }
    @Test fun backlogReceiptsCannotInflateUniqueSourceCadence() {
        restartWithoutGeneratedHistory()
        repeat(3) { assertFalse(window(57, sourceAccepted = 60).generationReady) }
    }
    @Test fun fixedModeNeverUsesSourceOnlyDemandProof() {
        put("latestNativeSnapshot", snapshot(target = 0))
        assertTrue(commit(snapshot(target = 0)))
        LsfgVkManager.readRuntimeState(container)
        repeat(3) { assertFalse(window(60).generationReady) }
    }
    @Test fun resumedGenerationDemandClearsSourceOnlyReadiness() {
        restartWithoutGeneratedHistory()
        window(60); assertTrue(window(60).generationReady)
        assertFalse(window(15, demand = 45).generationReady)
    }
    @Test fun newDemandImmediatelyInvalidatesSourceOnlyReadiness() {
        restartWithoutGeneratedHistory()
        window(60); assertTrue(window(60).generationReady)
        opportunities++
        assertFalse(LsfgVkManager.readRuntimeState(container).generationReady)
    }
    @Test fun actualUndeliveredGenerationDemandBecomesStalled() {
        var state = window(15, demand = 45)
        repeat(5) { state = window(15, demand = 45) }
        assertEquals(LsfgVkManager.NativeHealthState.GENERATION_STALLED, state.nativeHealthState)
    }
    @Test fun staleCommitCannotPublishFlagsOrPacingForNewRequest() {
        atomic("nativeConfigRevision").set(11)
        val pacing = PowerManager.frameGenerationPacing
        assertFalse(commit(snapshot()))
        assertSame(pacing, PowerManager.frameGenerationPacing)
        assertFalse(LsfgVkManager.readRuntimeState(container).generationReady)
    }
    @Test fun legacyFixedPressureUsesFreshActualSourceCadence() {
        val legacy = snapshot(target = 0).copy(backend = "legacy")
        put("latestNativeSnapshot", legacy)
        assertTrue(commit(legacy))
        Thread.sleep(2L) // Cross the millisecond commit freshness floor.
        File(root, ".config/lsfg-vk/stats.txt").apply {
            parentFile!!.mkdirs()
            writeText("source_frames_total=100\nsource_fps=30\nconfirmed_output_valid=1\nfps=120")
        }
        val sample = MetricsSnapshot(System.currentTimeMillis(), 7.5f, 133f, 140f, 160f,
            10, 20, 50f, CpuUsageSource.PROC_STAT, 80f, 55, 54, 2)
        assertTrue(LsfgVkManager.publishRuntimePressure(root, sample))
        val pressure = File(root, ".config/lsfg-vk/runtime-pressure.txt").readText()
        assertTrue(pressure.contains("source_fps=30.00"))
        assertTrue(pressure.contains("frame_time_p95_ms=-1.00"))
        assertTrue(pressure.contains("slow_frame_ratio=-1.0000"))
        assertEquals(4, PowerManager.frameGenerationPacing.sourceSampleStride)
    }

    @Test fun oldLegacyStatsCannotFeedNewMeasurementContract() {
        val stats = File(root, ".config/lsfg-vk/stats.txt").apply {
            parentFile!!.mkdirs()
            writeText("source_frames_total=100\nsource_fps=30\nconfirmed_output_valid=1\nfps=120")
            assertTrue(setLastModified(System.currentTimeMillis() - 20L))
        }
        val legacy = snapshot(target = 0).copy(backend = "legacy")
        put("latestNativeSnapshot", legacy)
        assertTrue(commit(legacy))
        assertNull(LsfgVkManager.readFreshLegacySourceFps(root))
        assertNull(LsfgVkManager.readFreshOutputFps(root))
        assertTrue(stats.isFile)
    }

}
