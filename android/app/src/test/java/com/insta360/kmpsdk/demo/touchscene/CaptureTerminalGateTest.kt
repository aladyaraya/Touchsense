package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaptureTerminalGateTest {
    @Test
    fun `error then late finish claims only the error`() {
        val gate = CaptureTerminalGate()
        gate.begin()
        assertTrue(gate.claim(active = true))
        assertFalse(gate.claim(active = true))
        gate.begin()
        assertTrue(gate.claim(active = true))
    }

    @Test
    fun `inactive or cancelled capture cannot claim success`() {
        val gate = CaptureTerminalGate()
        gate.begin()
        assertFalse(gate.claim(active = false))
        gate.invalidate()
        assertFalse(gate.claim(active = true))
    }

    @Test
    fun `concurrent terminal callbacks have exactly one winner`() {
        val gate = CaptureTerminalGate()
        gate.begin()
        val ready = CountDownLatch(12)
        val start = CountDownLatch(1)
        val claimed = AtomicInteger()
        val threads = List(12) {
            Thread {
                ready.countDown()
                start.await()
                if (gate.claim(active = true)) claimed.incrementAndGet()
            }.also { it.start() }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        threads.forEach { it.join(2_000) }
        assertTrue(claimed.get() == 1)
    }
}
