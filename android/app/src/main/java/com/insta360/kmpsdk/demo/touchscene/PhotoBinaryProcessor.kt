package com.insta360.kmpsdk.demo.touchscene

import com.insta360.kmpsdk.demo.raw.Yuv420Frame
import kotlin.math.max
import kotlin.math.roundToInt

/** Short-edge 64, area-averaged luminance, threshold 63. Ported from the phone-camera reference. */
data class PhotoBinaryLayer(
    val width: Int,
    val height: Int,
    val cells: ByteArray,
) {
    fun at(x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) cells[y * width + x].toInt() else 0
}

object PhotoBinaryProcessor {
    const val SHORT_EDGE = 64
    const val THRESHOLD = 63

    fun process(frame: Yuv420Frame): PhotoBinaryLayer {
        val sourceWidth = frame.width
        val sourceHeight = frame.height
        val width =
            if (sourceWidth >= sourceHeight) SHORT_EDGE
            else max(1, (SHORT_EDGE.toFloat() * sourceWidth / sourceHeight).roundToInt())
        val height =
            if (sourceHeight >= sourceWidth) SHORT_EDGE
            else max(1, (SHORT_EDGE.toFloat() * sourceHeight / sourceWidth).roundToInt())
        val cells = ByteArray(width * height)
        val y = frame.y
        for (gy in 0 until height) {
            val y0 = gy * sourceHeight / height
            val y1 = max(y0 + 1, (gy + 1) * sourceHeight / height)
            for (gx in 0 until width) {
                val x0 = gx * sourceWidth / width
                val x1 = max(x0 + 1, (gx + 1) * sourceWidth / width)
                var sum = 0L
                var count = 0
                for (sy in y0 until y1) {
                    val row = sy * sourceWidth
                    for (sx in x0 until x1) {
                        sum += (y[row + sx].toInt() and 0xff).toLong()
                        count++
                    }
                }
                cells[gy * width + gx] = if (sum / count >= THRESHOLD) 1.toByte() else 0.toByte()
            }
        }
        return PhotoBinaryLayer(width, height, cells)
    }
}
