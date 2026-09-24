package app.gamenative.framegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexPresentationTelemetryTest {
    @Test
    fun sourceArrivalsAndPresentedOutputsAreIndependent() {
        val start = 1_000_000_000L
        ApexPresentationTelemetry.beginSession(start)

        ApexPresentationTelemetry.recordSourceArrival(start + 10_000_000L)
        ApexPresentationTelemetry.recordSourceArrival(start + 20_000_000L)
        ApexPresentationTelemetry.record(
            ApexPresentationTelemetry.OUTPUT_GENERATED,
            swapSucceeded = true,
            nowNanos = start + 25_000_000L,
        )
        ApexPresentationTelemetry.record(
            ApexPresentationTelemetry.OUTPUT_SOURCE,
            swapSucceeded = true,
            nowNanos = start + 30_000_000L,
        )

        val snapshot = ApexPresentationTelemetry.snapshot(start + 40_000_000L)
        assertTrue(snapshot.active)
        assertEquals(2L, snapshot.sourceArrivals)
        assertEquals(1L, snapshot.sourcePresented)
        assertEquals(1L, snapshot.generatedPresented)
        assertEquals(2L, snapshot.outputPresented)
        assertTrue(snapshot.sourceInputFps > snapshot.sourceFps)

        ApexPresentationTelemetry.endSession(start + 50_000_000L)
        assertFalse(ApexPresentationTelemetry.snapshot(start + 60_000_000L).active)
    }
}
