package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchScenePerformanceMetricsTest {
    @Test
    fun `empty window is explicitly unavailable`() {
        val snapshot = TouchScenePerformanceMetrics().snapshot()
        assertEquals(0, snapshot.samples)
        assertNull(snapshot.mapFps)
        assertTrue(snapshot.traceCode().contains("fps=na"))
    }

    @Test
    fun `records fps and processing versus visible latency`() {
        val metrics = TouchScenePerformanceMetrics()
        metrics.record(100, 115, 120)
        metrics.record(200, 225, 240)
        metrics.record(300, 335, 360)
        val snapshot = metrics.snapshot()
        assertEquals(3, snapshot.samples)
        assertEquals(2 * 1_000.0 / 240.0, snapshot.mapFps!!, 0.001)
        assertEquals(35L, snapshot.processP95Ms)
        assertEquals(60L, snapshot.visibleP95Ms)
    }

    @Test
    fun `bounded window rejects invalid clock order`() {
        val metrics = TouchScenePerformanceMetrics(capacity = 2)
        metrics.record(100, 90, 120)
        metrics.record(100, 110, 120)
        metrics.record(100, 110, 120)
        metrics.record(200, 210, 220)
        metrics.record(300, 310, 320)
        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.samples)
        assertEquals(10L, snapshot.processP95Ms)
        assertEquals(20L, snapshot.visibleP95Ms)
        assertEquals(10.0, snapshot.mapFps!!, 0.001)
    }
}
