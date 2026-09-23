package com.insta360.kmpsdk.demo.touchscene

import com.insta360.kmpsdk.demo.raw.Yuv420Frame

/** Explicitly synthetic fixtures. These never claim to be Ace frames or model inferences. */
enum class FakeContourScene { RECTANGLE, CIRCLE, IRREGULAR, EMPTY }

/** In-memory no-camera source feeding the same YUV coordinator entry point as decoded preview. */
class FakeCameraGateway {
    fun frame(scene: FakeContourScene, timestampMs: Long): Yuv420Frame {
        val width = 256
        val height = 192
        val y = ByteArray(width * height) { 226.toByte() }
        fun dark(x: Int, row: Int, value: Int = 42) {
            y[row * width + x] = value.toByte()
        }
        when (scene) {
            FakeContourScene.RECTANGLE ->
                for (row in 40 until 152) for (x in 70 until 190) dark(x, row)
            FakeContourScene.CIRCLE ->
                for (row in 0 until height) for (x in 0 until width) {
                    val dx = x - width / 2
                    val dy = row - height / 2
                    if (dx * dx + dy * dy <= 55 * 55) dark(x, row)
                }
            FakeContourScene.IRREGULAR -> {
                // Connected head, torso, arms and legs: a controlled person-like silhouette.
                for (row in 24 until 68) for (x in 108 until 148) {
                    val dx = x - 128
                    val dy = row - 46
                    if (dx * dx + dy * dy <= 21 * 21) dark(x, row, 35)
                }
                for (row in 66 until 158) for (x in 94 until 162) dark(x, row)
                for (row in 145 until 184) {
                    for (x in 98 until 122) dark(x, row)
                    for (x in 134 until 158) dark(x, row)
                }
            }
            FakeContourScene.EMPTY -> Unit
        }
        val chroma = ByteArray((width / 2) * (height / 2)) { 128.toByte() }
        return Yuv420Frame(width, height, timestampMs, timestampMs, y, chroma.copyOf(), chroma)
    }
}
