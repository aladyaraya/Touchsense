package com.insta360.kmpsdk.demo.touchscene

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCaptureDispatchTest {
    @Test
    fun `rejected mode switch never starts capture`() = runBlocking {
        var captures = 0
        val result = dispatchVoiceCaptureAfterModeChange(
            switchModeIfNeeded = { false },
            isReady = { error("state must not be consulted after switch failure") },
            capture = { captures++ },
        )
        assertEquals(VoiceCaptureDispatchResult.SWITCH_FAILED, result)
        assertEquals(0, captures)
    }

    @Test
    fun `incomplete mode transition never starts capture`() = runBlocking {
        var captures = 0
        val result = dispatchVoiceCaptureAfterModeChange(
            switchModeIfNeeded = { true },
            isReady = { false },
            capture = { captures++ },
        )
        assertEquals(VoiceCaptureDispatchResult.NOT_READY, result)
        assertEquals(0, captures)
    }

    @Test
    fun `ready mode dispatches capture exactly once`() = runBlocking {
        var captures = 0
        val result = dispatchVoiceCaptureAfterModeChange(
            switchModeIfNeeded = { true },
            isReady = { true },
            capture = { captures++ },
        )
        assertEquals(VoiceCaptureDispatchResult.DISPATCHED, result)
        assertEquals(1, captures)
    }
}
