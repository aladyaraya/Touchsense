package com.insta360.kmpsdk.demo.raw

import com.arashivision.sdk.camera.api.preview.PreviewStreamFrame
import com.arashivision.sdk.camera.api.preview.PreviewStreamType
import java.io.ByteArrayOutputStream

/**
 * 将 SDK 按相同 timestamp 分批回调的视频数据拼成一个完整编码帧。
 *
 * Ace 单镜头预览通常使用 [PreviewStreamType.VIDEO]。如果目标机型只发送 VIDEO_L
 * 或 VIDEO_R，可在构造时显式切换 [acceptedType]，禁止把左右流拼进同一个解码器。
 */
internal class EncodedAccessUnitAssembler(
    private val acceptedType: PreviewStreamType = PreviewStreamType.VIDEO,
    private val maxAccessUnitBytes: Int = 8 * 1024 * 1024,
    private val onAccessUnit: (EncodedAccessUnit) -> Unit,
    private val onMalformedUnit: (String) -> Unit = {},
) {
    init { require(maxAccessUnitBytes > 0) }

    private var currentTimestamp: Long? = null
    private var discardedTimestamp: Long? = null
    private val buffer = ByteArrayOutputStream(256 * 1024)

    @Synchronized
    fun offer(frame: PreviewStreamFrame) {
        if (!frame.type.isVideo || frame.type != acceptedType || frame.data.isEmpty()) return

        if (discardedTimestamp == frame.timestamp) return
        discardedTimestamp = null

        val timestamp = currentTimestamp
        if (timestamp != null && timestamp != frame.timestamp) {
            flushLocked(timestamp)
        }
        if (currentTimestamp == null) currentTimestamp = frame.timestamp

        if (frame.data.size > maxAccessUnitBytes - buffer.size()) {
            resetLocked()
            discardedTimestamp = frame.timestamp
            onMalformedUnit(
                "Encoded access unit exceeded $maxAccessUnitBytes bytes at timestamp ${frame.timestamp}",
            )
            return
        }
        buffer.write(frame.data)
    }

    @Synchronized
    fun flush() {
        currentTimestamp?.let(::flushLocked)
    }

    @Synchronized
    fun reset() {
        resetLocked()
        discardedTimestamp = null
    }

    private fun flushLocked(timestamp: Long) {
        if (buffer.size() > 0) {
            onAccessUnit(
                EncodedAccessUnit(
                    data = buffer.toByteArray(),
                    timestampMs = timestamp,
                ),
            )
        }
        resetLocked()
    }

    private fun resetLocked() {
        buffer.reset()
        currentTimestamp = null
    }
}

internal data class EncodedAccessUnit(
    val data: ByteArray,
    val timestampMs: Long,
)
