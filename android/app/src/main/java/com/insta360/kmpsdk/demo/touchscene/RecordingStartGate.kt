package com.insta360.kmpsdk.demo.touchscene

import java.util.concurrent.atomic.AtomicBoolean

/** Emits at most one recording-start cue for a pending video capture. */
internal class RecordingStartGate {
    private val pending = AtomicBoolean(false)

    fun begin() {
        pending.set(true)
    }

    fun invalidate() {
        pending.set(false)
    }

    fun claim(active: Boolean, stopping: Boolean): Boolean =
        active && !stopping && pending.compareAndSet(true, false)
}
