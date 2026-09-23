package com.insta360.kmpsdk.demo.touchscene

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
