package com.insta360.kmpsdk.demo.raw

import com.arashivision.sdk.camera.api.preview.PreviewStreamFrame
import com.arashivision.sdk.camera.api.preview.PreviewStreamType
import java.io.ByteArrayOutputStream

/**
 * 将 SDK 按相同 timestamp 分批回调的视频数据拼成一个完整编码帧。
 *
 * 默认仅接收 VIDEO；分析预览可传 null，在每次配置后选定首先收到的视频流类型，
 * 避免只发送 VIDEO_L/VIDEO_R 的机型没有分析帧，同时不混合左右流。
 */
internal class EncodedAccessUnitAssembler(
    private val acceptedType: PreviewStreamType? = PreviewStreamType.VIDEO,
    private val maxAccessUnitBytes: Int = 8 * 1024 * 1024,
    private val onAccessUnit: (EncodedAccessUnit) -> Unit,
    private val onMalformedUnit: (String) -> Unit = {},
) {
    init { require(maxAccessUnitBytes > 0) }

    private var currentTimestamp: Long? = null
    private var discardedTimestamp: Long? = null
    private var selectedType: PreviewStreamType? = acceptedType
    private val buffer = ByteArrayOutputStream(256 * 1024)

    @Synchronized
    fun offer(frame: PreviewStreamFrame) {
        if (!frame.type.isVideo || frame.data.isEmpty()) return
        if (selectedType == null) selectedType = frame.type
        if (frame.type != selectedType) return

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
        selectedType = acceptedType
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
