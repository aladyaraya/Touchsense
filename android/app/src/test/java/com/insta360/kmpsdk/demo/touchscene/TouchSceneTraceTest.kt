package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class TouchSceneTraceTest {
    @Test
    fun `trace retains only the latest bounded metadata events`() {
        val now = AtomicLong(100L)
        val trace = TouchSceneTrace(
            sessionId = "test-session",
            capacity = 2,
            wallClockMs = { now.getAndIncrement() },
            elapsedMs = { now.get() },
        )
        trace.record("preview", "requested")
        trace.record("decoder", "first_frame", code = "256x192")
        trace.record("map", "frozen", mapVersion = 7L)
        val entries = trace.snapshot()
        assertEquals(2, entries.size)
        assertEquals("decoder", entries[0].stage)
        assertEquals("map", entries[1].stage)
        assertEquals(7L, entries[1].mapVersion)
        assertEquals(102L, entries[1].wallTimeMs)
        assertFalse(trace.jsonLines().contains("requested"))
    }

    @Test
    fun `trace produces escaped json lines without frame content`() {
        val trace = TouchSceneTrace(sessionId = "test", capacity = 1)
        val line = trace.record("description", "failed", code = "quote\"\\\n", fallback = "local_demo").toJsonLine()
        assertTrue(line.contains("\"stage\":\"description\""))
        assertTrue(line.contains("\"code\":\"quote\\\"\\\\\\n\""))
        assertTrue(line.contains("\"fallback\":\"local_demo\""))
        assertFalse(line.contains("image"))
    }
}
