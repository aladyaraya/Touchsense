package com.insta360.kmpsdk.demo.touchscene

import com.insta360.kmpsdk.demo.raw.Yuv420Frame

/** One-shot analysis of a single frame: the debug layers plus the haptic map. */
data class TactileFrameResult(
    val result: TactileProcessingResult,
    val photoBinary: PhotoBinaryLayer,
)

/**
 * Strategy that turns a camera frame into the tactile/debug layers.
 * Production injects an OpenCV implementation; JVM unit tests keep the pure-Kotlin default
 * because the OpenCV native library cannot load off-device.
 */
interface TactileFrameProcessor {
    fun process(source: Yuv420Frame): TactileFrameResult
}

/** Pure-Kotlin baseline: dependency-free Canny edges + brightness binary. */
class DefaultTactileFrameProcessor(
    private val canny: CannyTactileProcessor = CannyTactileProcessor(),
) : TactileFrameProcessor {
    override fun process(source: Yuv420Frame): TactileFrameResult {
        val gray = GrayFrame(source.width, source.height, source.receivedAtElapsedRealtimeMs, source.y)
        return TactileFrameResult(canny.processDetailed(gray), PhotoBinaryProcessor.process(source))
    }
}
