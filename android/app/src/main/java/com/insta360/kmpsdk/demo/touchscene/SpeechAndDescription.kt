package com.insta360.kmpsdk.demo.touchscene

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.insta360.kmpsdk.demo.raw.Yuv420Frame

data class SceneDescription(
    val text: String,
    val timestampMs: Long,
    val source: String,
    val subject: String? = null,
    val position: String? = null,
    val approximateSize: String? = null,
    val framingRisk: String? = null,
    val clipped: Boolean? = null,
    val occlusion: String? = null,
    val backgroundContext: String? = null,
    val lighting: String? = null,
)

interface SceneDescriber {
    fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription
}

/**
 * Honest offline fallback: describes the measured main contour, position, size, clipping and
 * lighting. It does not claim semantic object identity and remains usable without Internet.
 */
class LocalContourSceneDescriber : SceneDescriber {
    override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription {
        if (map == null) return SceneDescription("暂时没有可用的画面描述", frame.sourceTimestampMs, "local-contour")
        val active = ArrayList<GridPoint>()
        for (y in 0 until map.height) for (x in 0 until map.width) {
            if (map.cellAt(x, y) != TactileCell.BACKGROUND) active += GridPoint(x, y)
        }
        if (active.isEmpty()) {
            return SceneDescription("画面中暂未检测到明显的主要轮廓。", frame.sourceTimestampMs, "local-contour")
        }
        val minX = active.minOf { it.x }
        val maxX = active.maxOf { it.x }
        val minY = active.minOf { it.y }
        val maxY = active.maxOf { it.y }
        val centerX = (minX + maxX) / 2f / map.width
        val centerY = (minY + maxY) / 2f / map.height
        val position = "${if (centerY < .34f) "上方" else if (centerY > .66f) "下方" else "中部"}${if (centerX < .34f) "偏左" else if (centerX > .66f) "偏右" else ""}"
        val ratio = (maxX - minX + 1) * (maxY - minY + 1).toFloat() / (map.width * map.height)
        val size = if (ratio < .15f) "较小" else if (ratio > .55f) "较大" else "中等"
        val clipped = minX == 0 || minY == 0 || maxX == map.width - 1 || maxY == map.height - 1
        val mean = frame.y.sumOf { it.toInt() and 0xff }.toDouble() / frame.y.size
        val light = if (mean < 55) "整体光线较暗。" else if (mean > 215) "整体光线较亮。" else null
        val risk = if (clipped) "轮廓接近画面边缘，可能有裁切。" else "轮廓未贴边。"
        return SceneDescription(
            "画面有一处${size}的主要轮廓，位于${position}。${risk}${light.orEmpty()}",
            frame.sourceTimestampMs,
            "local-contour",
            subject = "未识别类别的主要轮廓",
            position = position,
            approximateSize = size,
            framingRisk = risk,
            clipped = clipped,
            lighting = light,
        )
    }
}

/** Bundled on-device ML Kit model. No preview frame is uploaded to a server. */
class MlKitSceneDescriber(context: Context) : SceneDescriber, AutoCloseable {
    private val fallback = LocalContourSceneDescriber()
    private val labeler =
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(MIN_CONFIDENCE)
                .build(),
        )

    override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription {
        val fallbackResult = fallback.describe(frame, map)
        return runCatching {
            val bitmap = frame.toRgbBitmap(MAX_MODEL_WIDTH)
            val labels = try {
                Tasks.await(
                    labeler.process(InputImage.fromBitmap(bitmap, 0)),
                    MODEL_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            } finally {
                bitmap.recycle()
            }
            val names =
                SceneLabelVocabulary.topNames(
                    labels.sortedByDescending { it.confidence }.map { it.text },
                )
            if (names.isEmpty()) fallbackResult else {
                val semantic = if (names.size == 1) names.first() else names.joinToString("、")
                fallbackResult.copy(
                    text = "画面语义可能包含${semantic}。" + if (map == null) "" else fallbackResult.text,
                    source = "mlkit-image-labeling+local-contour",
                    subject = "可能包含${semantic}",
                )
            }
        }.getOrElse { fallbackResult }
    }

    override fun close() {
        labeler.close()
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.55f
        private const val MAX_MODEL_WIDTH = 320
        private const val MODEL_TIMEOUT_SECONDS = 2L
    }
}

private fun Yuv420Frame.toRgbBitmap(maxWidth: Int): Bitmap {
    val outputWidth = minOf(width, maxWidth)
    val outputHeight = (height.toLong() * outputWidth / width).toInt().coerceAtLeast(1)
    return toRgbDebugBitmap(outputWidth, outputHeight)
}

/** Uses the same sampling grid as the TouchMap processor, including its current aspect mapping. */
internal fun Yuv420Frame.toRgbDebugBitmap(outputWidth: Int, outputHeight: Int): Bitmap {
    require(outputWidth > 0 && outputHeight > 0)
    val chromaWidth = (width + 1) / 2
    val colors = IntArray(outputWidth * outputHeight)
    for (oy in 0 until outputHeight) {
        val sy = oy * height / outputHeight
        for (ox in 0 until outputWidth) {
            val sx = ox * width / outputWidth
            val yValue = (y[sy * width + sx].toInt() and 0xff) - 16
            val chromaIndex = (sy / 2) * chromaWidth + sx / 2
            val uValue = (u[chromaIndex].toInt() and 0xff) - 128
            val vValue = (v[chromaIndex].toInt() and 0xff) - 128
            val c = yValue.coerceAtLeast(0)
            val red = ((298 * c + 409 * vValue + 128) shr 8).coerceIn(0, 255)
            val green = ((298 * c - 100 * uValue - 208 * vValue + 128) shr 8).coerceIn(0, 255)
            val blue = ((298 * c + 516 * uValue + 128) shr 8).coerceIn(0, 255)
            colors[oy * outputWidth + ox] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return Bitmap.createBitmap(colors, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
}

class AndroidSpeechOutput(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit = {},
) : TextToSpeech.OnInitListener, AutoCloseable {
    private val lastText = AtomicReference<String?>(null)
    private val activeUtteranceId = AtomicReference<String?>(null)
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finishUtterance(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finishUtterance(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishUtterance(utteranceId)
            },
        )
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
    }

    fun canSpeak(): Boolean = ready

    fun isSpeaking(): Boolean = activeUtteranceId.get() != null

    fun speak(text: String) {
        lastText.set(text)
        if (!ready) return
        val utteranceId = UUID.randomUUID().toString()
        activeUtteranceId.set(utteranceId)
        onSpeakingChanged(true)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId) == TextToSpeech.ERROR) {
            finishUtterance(utteranceId)
        }
    }

    private fun finishUtterance(utteranceId: String?) {
        if (utteranceId != null && activeUtteranceId.compareAndSet(utteranceId, null)) {
            onSpeakingChanged(false)
        }
    }

    fun repeatLast(): Boolean {
        val text = lastText.get() ?: return false
        speak(text)
        return true
    }

    fun stop() {
        activeUtteranceId.set(null)
        tts.stop()
        onSpeakingChanged(false)
    }

    override fun close() {
        activeUtteranceId.set(null)
        tts.stop()
        tts.shutdown()
        onSpeakingChanged(false)
    }
}
