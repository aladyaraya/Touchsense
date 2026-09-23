package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnGateTest {
    @Test
    fun `tts interruption cancels and resumes only the unfinished turn`() {
        val gate = VoiceTurnGate()
        gate.beginListening()
        assertTrue(gate.speechStarted())
        assertFalse(gate.recognitionFinished())
        assertFalse(gate.speechStarted())
        assertTrue(gate.speechFinished())
        assertFalse(gate.speechFinished())
        assertTrue(gate.recognitionFinished())
    }

    @Test
    fun `spoken command reply never starts background listening`() {
        val gate = VoiceTurnGate()
        gate.beginListening()
        assertTrue(gate.recognitionFinished())
        assertFalse(gate.speechStarted())
        assertFalse(gate.speechFinished())
    }

    @Test
    fun `new push to talk and lifecycle reset discard old resume request`() {
        val gate = VoiceTurnGate()
        gate.beginListening()
        assertTrue(gate.speechStarted())
        gate.beginListening()
        assertFalse(gate.speechFinished())
        gate.reset()
        assertFalse(gate.recognitionFinished())
        assertFalse(gate.speechFinished())
    }
}
