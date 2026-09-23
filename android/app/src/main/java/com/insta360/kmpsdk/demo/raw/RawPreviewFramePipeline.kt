package com.insta360.kmpsdk.demo.raw

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.arashivision.sdk.camera.api.preview.PreviewStreamFrame
import com.arashivision.sdk.camera.core.model.option.VideoEncode
import timber.log.Timber
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ace SDK 编码预览流 -> Android YUV420 分析帧。
 *
 * 生命周期：
 * 1. 在收到 onParamsChanged + 视频编码类型后调用 [configure]；
 * 2. 把 onStreamDataNotify 的每个分片交给 [offer]；
 * 3. 页面停止预览时调用 [close]。
 */
class RawPreviewFramePipeline(
    private val analysisIntervalMs: Long = 100L,
    private val onFrame: (Yuv420Frame) -> Unit,
    private val onNeedsKeyFrame: () -> Unit,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()

    @Volatile
    private var decoder: SurfaceYuvDecoder? = null

    @Volatile
    private var configuredFormat: DecoderFormat? = null

    private val assembler =
        EncodedAccessUnitAssembler(
            acceptedType = null,
            onAccessUnit = { accessUnit ->
                decoder?.offer(accessUnit)
            },
            onMalformedUnit = { message ->
                onError(IllegalStateException(message))
                onNeedsKeyFrame()
            },
        )

    fun configure(
        width: Int,
        height: Int,
        encode: VideoEncode,
    ) {
        if (closed.get() || width <= 0 || height <= 0) return
        val requested = DecoderFormat(width, height, encode)
        synchronized(lock) {
            if (closed.get() || configuredFormat == requested) return
            assembler.reset()
            decoder?.close()
            decoder = null
            configuredFormat = null
            try {
                decoder =
                    SurfaceYuvDecoder(
                        format = requested,
                        analysisIntervalMs = analysisIntervalMs,
                        onFrame = onFrame,
                        onQueueOverflow = onNeedsKeyFrame,
                        onError = onError,
                    ).also { it.start() }
                configuredFormat = requested
            } catch (t: Throwable) {
                onError(t)
                return
            }
        }
        if (configuredFormat != requested) return
        Timber.i(
            "Raw preview decoder configured: %dx%d %s",
            width,
            height,
            requested.mime,
        )
        onNeedsKeyFrame()
    }

    fun offer(frame: PreviewStreamFrame) {
        if (closed.get() || decoder == null) return
        assembler.offer(frame)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            assembler.reset()
            decoder?.close()
            decoder = null
            configuredFormat = null
        }
    }
}

private data class DecoderFormat(
    val width: Int,
    val height: Int,
    val encode: VideoEncode,
) {
    val mime: String
        get() =
            if (encode == VideoEncode.ENCODE_H265) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }
}

/**
 * 常规机型使用 MediaCodec + ImageReader；已确认 ImageReader JNI 崩溃的机型
 * 改用 MediaCodec 的缓冲区输出，直接从解码后的输出帧复制 YUV。
 * 编码队列有上限；溢出时清空旧帧并请求相机补发关键帧，避免内存持续增长。
 */
private class SurfaceYuvDecoder(
    private val format: DecoderFormat,
    private val analysisIntervalMs: Long,
    private val onFrame: (Yuv420Frame) -> Unit,
    private val onQueueOverflow: () -> Unit,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val inputQueue = ArrayBlockingQueue<EncodedAccessUnit>(MAX_ENCODED_QUEUE)
    private val useBufferOutput = Build.MANUFACTURER.equals("HONOR", ignoreCase = true) &&
        Build.MODEL == "PTP-AN10"
    private lateinit var imageThread: HandlerThread
    private lateinit var imageReader: ImageReader
    private lateinit var codec: MediaCodec
    private lateinit var codecThread: Thread

    @Volatile
    private var lastAnalysisAtMs = Long.MIN_VALUE

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            if (!useBufferOutput) {
                imageThread = HandlerThread("ace-yuv-images").also { it.start() }
                imageReader =
                    ImageReader.newInstance(
                        format.width,
                        format.height,
                        ImageFormat.YUV_420_888,
                        MAX_IMAGES,
                    ).also { reader ->
                        reader.setOnImageAvailableListener(
                            { source -> consumeLatestImage(source) },
                            Handler(imageThread.looper),
                        )
                    }
            }
            codec = MediaCodec.createDecoderByType(format.mime)
            if (useBufferOutput) Timber.i("Using decoder buffer output %s on %s", codec.name, Build.MODEL)
            val mediaFormat =
                MediaFormat.createVideoFormat(format.mime, format.width, format.height).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ACCESS_UNIT_BYTES)
                    if (useBufferOutput) {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                        )
                    }
                }
            codec.configure(mediaFormat, if (useBufferOutput) null else imageReader.surface, null, 0)
            codec.start()

            codecThread =
                Thread(::decodeLoop, "ace-preview-decoder").also {
                    it.isDaemon = true
                    it.start()
                }
        } catch (t: Throwable) {
            running.set(false)
            releaseResources()
            throw t
        }
    }

    fun offer(accessUnit: EncodedAccessUnit) {
        if (!running.get()) return
        if (inputQueue.offer(accessUnit)) return

        // 处理速度异常时不允许形成无界延迟。清空依赖链后必须请求新的 I 帧。
        inputQueue.clear()
        inputQueue.offer(accessUnit)
        onQueueOverflow()
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val accessUnit = inputQueue.poll(10, TimeUnit.MILLISECONDS)
                if (accessUnit != null) {
                    queueInput(accessUnit)
                }
                drainOutput(info)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            if (running.get()) onError(t)
        }
    }

    private fun queueInput(accessUnit: EncodedAccessUnit) {
        while (running.get()) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) {
                drainOutput(MediaCodec.BufferInfo())
                continue
            }
            val input = codec.getInputBuffer(index) ?: return
            input.clear()
            if (accessUnit.data.size > input.remaining()) {
                onError(
                    IllegalArgumentException(
                        "Encoded frame ${accessUnit.data.size} exceeds codec input ${input.remaining()}",
                    ),
                )
                onQueueOverflow()
                return
            }
            input.put(accessUnit.data)
            codec.queueInputBuffer(
                index,
                0,
                accessUnit.data.size,
                accessUnit.timestampMs * 1_000L,
                0,
            )
            return
        }
    }

    private fun drainOutput(info: MediaCodec.BufferInfo) {
        while (running.get()) {
            when (val index = codec.dequeueOutputBuffer(info, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    Timber.d("Raw decoder output format: %s", codec.outputFormat)
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    if (useBufferOutput) {
                        try {
                            val now = SystemClock.elapsedRealtime()
                            if (info.size > 0 &&
                                (lastAnalysisAtMs == Long.MIN_VALUE || now - lastAnalysisAtMs >= analysisIntervalMs)
                            ) {
                                codec.getOutputImage(index)?.let { image ->
                                    try {
                                        lastAnalysisAtMs = now
                                        onFrame(image.toCompactYuv420(now))
                                    } finally {
                                        image.close()
                                    }
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    } else {
                        codec.releaseOutputBuffer(index, true)
                    }
                }
            }
        }
    }

    private fun consumeLatestImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (lastAnalysisAtMs != Long.MIN_VALUE && now - lastAnalysisAtMs < analysisIntervalMs) {
                return
            }
            lastAnalysisAtMs = now
            onFrame(image.toCompactYuv420(now))
        } catch (t: Throwable) {
            if (running.get()) onError(t)
        } finally {
            image.close()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        inputQueue.clear()
        if (::codecThread.isInitialized) {
            codecThread.interrupt()
            runCatching { codecThread.join(1_000L) }
        }
        releaseResources()
    }

    private fun releaseResources() {
        if (::codec.isInitialized) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        if (::imageReader.isInitialized) {
            runCatching { imageReader.setOnImageAvailableListener(null, null) }
            runCatching { imageReader.close() }
        }
        if (::imageThread.isInitialized) {
            runCatching { imageThread.quitSafely() }
            runCatching { imageThread.join(1_000L) }
        }
    }

    companion object {
        private const val MAX_IMAGES = 3
        private const val MAX_ENCODED_QUEUE = 60
        private const val MAX_ACCESS_UNIT_BYTES = 8 * 1024 * 1024
        private const val INPUT_TIMEOUT_US = 10_000L
    }
}

private fun Image.toCompactYuv420(receivedAtMs: Long): Yuv420Frame {
    require(format == ImageFormat.YUV_420_888) { "Expected YUV_420_888, got $format" }
    require(planes.size >= 3) { "Expected 3 YUV planes, got ${planes.size}" }

    val crop = cropRect
    val outputWidth = crop.width()
    val outputHeight = crop.height()
    val chromaWidth = (outputWidth + 1) / 2
    val chromaHeight = (outputHeight + 1) / 2

    return Yuv420Frame(
        width = outputWidth,
        height = outputHeight,
        sourceTimestampMs = if (timestamp > 0L) timestamp / 1_000_000L else 0L,
        receivedAtElapsedRealtimeMs = receivedAtMs,
        y = copyPlane(planes[0], crop.left, crop.top, outputWidth, outputHeight),
        u =
            copyPlane(
                planes[1],
                crop.left / 2,
                crop.top / 2,
                chromaWidth,
                chromaHeight,
            ),
        v =
            copyPlane(
                planes[2],
                crop.left / 2,
                crop.top / 2,
                chromaWidth,
                chromaHeight,
            ),
    )
}

private fun copyPlane(
    plane: Image.Plane,
    cropLeft: Int,
    cropTop: Int,
    width: Int,
    height: Int,
): ByteArray {
    val source = plane.buffer.duplicate()
    val output = ByteArray(width * height)
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val base = source.position()
    val limit = source.limit()

    var target = 0
    for (row in 0 until height) {
        val rowStart = base + (cropTop + row) * rowStride + cropLeft * pixelStride
        for (column in 0 until width) {
            val sourceIndex = rowStart + column * pixelStride
            require(sourceIndex < limit) {
                "YUV plane index $sourceIndex exceeds buffer limit $limit"
            }
            output[target++] = source.get(sourceIndex)
        }
    }
    return output
}
