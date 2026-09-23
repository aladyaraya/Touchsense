package com.insta360.kmpsdk.demo.touchscene

import java.util.concurrent.atomic.AtomicBoolean

/** Allows at most one terminal callback for the currently active capture. */
internal class CaptureTerminalGate {
    private val consumed = AtomicBoolean(false)

    fun begin() {
        consumed.set(false)
    }

    fun invalidate() {
        consumed.set(true)
    }

    fun claim(active: Boolean): Boolean = active && consumed.compareAndSet(false, true)
}
