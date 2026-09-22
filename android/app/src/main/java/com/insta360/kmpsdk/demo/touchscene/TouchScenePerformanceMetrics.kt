package com.insta360.kmpsdk.demo.touchscene

import java.util.ArrayDeque
import kotlin.math.ceil

/** Recent end-to-end timing at the Android preview boundary; no image data is retained. */
class TouchScenePerformanceMetrics(private val capacity: Int = 64) {
    init { require(capacity > 1) }

    data class Snapshot(
        val samples: Int,
        val mapFps: Double?,
        val processP95Ms: Long?,
        val visibleP95Ms: Long?,
    ) {
        fun traceCode(): String =
            "samples=$samples,fps=${mapFps?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "na"}," +
                "process_p95_ms=${processP95Ms ?: "na"},visible_p95_ms=${visibleP95Ms ?: "na"}"
    }

    private data class Sample(val visibleAtMs: Long, val processMs: Long, val visibleMs: Long)
    private val recent = ArrayDeque<Sample>()

    @Synchronized
    fun record(receivedAtMs: Long, processedAtMs: Long, visibleAtMs: Long) {
        if (receivedAtMs < 0 || processedAtMs < receivedAtMs || visibleAtMs < processedAtMs) return
        if (recent.isNotEmpty() && visibleAtMs <= recent.last.visibleAtMs) return
        if (recent.size == capacity) recent.removeFirst()
        recent.addLast(Sample(visibleAtMs, processedAtMs - receivedAtMs, visibleAtMs - receivedAtMs))
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val samples = recent.toList()
        val spanMs = if (samples.size > 1) samples.last().visibleAtMs - samples.first().visibleAtMs else 0L
        val fps = if (spanMs > 0) (samples.size - 1) * 1_000.0 / spanMs else null
        return Snapshot(samples.size, fps, percentile95(samples.map { it.processMs }), percentile95(samples.map { it.visibleMs }))
    }

    private fun percentile95(values: List<Long>): Long? =
        values.sorted().takeIf { it.isNotEmpty() }?.let { it[ceil(it.size * 0.95).toInt() - 1] }
}
