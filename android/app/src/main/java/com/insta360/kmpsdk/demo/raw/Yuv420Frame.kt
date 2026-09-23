package com.insta360.kmpsdk.demo.raw

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 从 Ace 预览码流解码得到的紧凑 YUV420 图像。
 *
 * 三个平面均已去除 Android Image 的 rowStride / pixelStride 填充：
 * - [y] 长度为 width * height；
 * - [u] / [v] 长度为 ceil(width / 2) * ceil(height / 2)。
 *
 * 数组发布后按只读数据使用。这样下游图像处理无需持有或关闭 ImageReader 的 Image。
 */
data class Yuv420Frame(
    val width: Int,
    val height: Int,
    val sourceTimestampMs: Long,
    val receivedAtElapsedRealtimeMs: Long,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
)

/**
 * 业务层读取相机最新原始图像的最小交接点。
 *
 * 这里故意只保留一帧，避免图像处理变慢时堆积几十张 1080p YUV 图像。
 */
object LatestCameraFrameStore {
    private val latest = AtomicReference<Yuv420Frame?>(null)
    private val publishedCount = AtomicLong(0L)

    fun publish(frame: Yuv420Frame) {
        latest.set(frame)
        publishedCount.incrementAndGet()
    }

    /** 返回当前只读快照；调用方不得修改内部 ByteArray。 */
    fun snapshot(): Yuv420Frame? = latest.get()

    fun count(): Long = publishedCount.get()

    fun clear() {
        latest.set(null)
    }
}
