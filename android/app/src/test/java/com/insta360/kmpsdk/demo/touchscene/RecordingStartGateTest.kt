package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStartGateTest {
    @Test
    fun `working and timer callbacks confirm one recording start`() {
        val gate = RecordingStartGate()
        gate.begin()
        assertTrue(gate.claim(active = true, stopping = false))
        assertFalse(gate.claim(active = true, stopping = false))
    }

    @Test
    fun `starting or late callbacks do not claim inactive or stopping operation`() {
        val gate = RecordingStartGate()
        gate.begin()
        assertFalse(gate.claim(active = false, stopping = false))
        assertFalse(gate.claim(active = true, stopping = true))
        gate.invalidate()
        assertFalse(gate.claim(active = true, stopping = false))
    }

    @Test
    fun `new recording gets a new one-shot confirmation`() {
        val gate = RecordingStartGate()
        gate.begin()
        assertTrue(gate.claim(active = true, stopping = false))
        gate.begin()
        assertTrue(gate.claim(active = true, stopping = false))
        assertFalse(gate.claim(active = true, stopping = false))
    }
}
