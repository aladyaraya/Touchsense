package com.insta360.kmpsdk.demo.touchscene

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Two independent latest-frame workers: a slow vision lane can never block the tactile lane. */
class ParallelFrameDispatcher<T : Any>(
    private val visionConsumer: (T) -> Unit,
    private val edgeConsumer: (T) -> Unit,
    private val visionExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "touchscene-vision") },
    private val edgeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "touchscene-edge") },
) : AutoCloseable {
    private val open = AtomicBoolean(true)
    private val visionLatest = AtomicReference<T?>()
    private val edgeLatest = AtomicReference<T?>()
    private val visionRunning = AtomicBoolean(false)
    private val edgeRunning = AtomicBoolean(false)

    fun offer(frame: T) {
        if (!open.get()) return
        visionLatest.set(frame)
        edgeLatest.set(frame)
        schedule(visionLatest, visionRunning, visionExecutor, visionConsumer)
        schedule(edgeLatest, edgeRunning, edgeExecutor, edgeConsumer)
    }

    private fun schedule(
        latest: AtomicReference<T?>,
        running: AtomicBoolean,
        executor: java.util.concurrent.ExecutorService,
        consumer: (T) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    while (open.get()) {
                        val next = latest.getAndSet(null) ?: break
                        consumer(next)
                    }
                } finally {
                    running.set(false)
                    if (open.get() && latest.get() != null) schedule(latest, running, executor, consumer)
                }
            }
        } catch (_: RejectedExecutionException) {
            // close() may shut the pool down between offer() and execute().
            running.set(false)
            latest.set(null)
        }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        visionLatest.set(null)
        edgeLatest.set(null)
        visionExecutor.shutdownNow()
        edgeExecutor.shutdownNow()
    }
}

enum class StabilityState { MOVING, STABILIZING, STABLE }

data class StabilityConfig(
    val sampleWidth: Int = 32,
    val sampleHeight: Int = 24,
    val meanDifferenceThreshold: Float = 4.5f,
    val stableDurationMs: Long = 600L,
    val readyCooldownMs: Long = 2_000L,
)

data class StabilityUpdate(
    val state: StabilityState,
    val becameReady: Boolean,
    val motionDetected: Boolean = false,
)

/** Video-motion fallback. Thresholds are configurable and must be calibrated on the target phone. */
class VideoStabilityEngine(private val config: StabilityConfig = StabilityConfig()) {
    private var previous: IntArray? = null
    private var stableSince: Long? = null
    private var lastReadyAt = Long.MIN_VALUE
    private var readyArmed = true
    @Volatile var state: StabilityState = StabilityState.MOVING
        private set

    @Synchronized
    fun update(frame: GrayFrame, nowMs: Long = frame.timestampMs): StabilityUpdate {
        val sample = downsample(frame)
        val old = previous
        previous = sample
        if (old == null) return StabilityUpdate(state, false)
        var total = 0L
        for (i in sample.indices) total += abs(sample[i] - old[i])
        val difference = total.toFloat() / sample.size
        if (difference > config.meanDifferenceThreshold) {
            stableSince = null
            readyArmed = true
            state = StabilityState.MOVING
            return StabilityUpdate(state, false, motionDetected = true)
        }
        val since = stableSince ?: nowMs.also { stableSince = it }
        state = if (nowMs - since >= config.stableDurationMs) StabilityState.STABLE else StabilityState.STABILIZING
        val ready = state == StabilityState.STABLE && readyArmed &&
            (lastReadyAt == Long.MIN_VALUE || nowMs - lastReadyAt >= config.readyCooldownMs)
        if (ready) {
            lastReadyAt = nowMs
            readyArmed = false
        }
        return StabilityUpdate(state, ready)
    }

    @Synchronized
    fun rearmReady() {
        readyArmed = true
    }

    @Synchronized
    fun reset() {
        previous = null
        stableSince = null
        lastReadyAt = Long.MIN_VALUE
        readyArmed = true
        state = StabilityState.MOVING
    }

    private fun downsample(frame: GrayFrame): IntArray {
        val out = IntArray(config.sampleWidth * config.sampleHeight)
        for (y in 0 until config.sampleHeight) for (x in 0 until config.sampleWidth) {
            val sx = x * frame.width / config.sampleWidth
            val sy = y * frame.height / config.sampleHeight
            out[y * config.sampleWidth + x] = frame.luminance[sy * frame.width + sx].toInt() and 0xff
        }
        return out
    }
}
