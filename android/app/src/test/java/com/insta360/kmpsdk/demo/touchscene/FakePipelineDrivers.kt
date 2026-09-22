package com.insta360.kmpsdk.demo.touchscene

import com.insta360.kmpsdk.demo.raw.Yuv420Frame

/** Controlled pixel translation for video-difference stability tests, not SDK posture data. */
class FakeCameraMotionProvider {
    fun shifted(frame: Yuv420Frame, dx: Int, dy: Int, timestampMs: Long): Yuv420Frame {
        val shifted = ByteArray(frame.y.size) { 226.toByte() }
        for (row in 0 until frame.height) for (x in 0 until frame.width) {
            val sourceX = x - dx
            val sourceY = row - dy
            if (sourceX in 0 until frame.width && sourceY in 0 until frame.height) {
                shifted[row * frame.width + x] = frame.y[sourceY * frame.width + sourceX]
            }
        }
        return frame.copy(sourceTimestampMs = timestampMs, receivedAtElapsedRealtimeMs = timestampMs, y = shifted)
    }
}

enum class FakeVisionScene { PERSON, TREE_AND_CAR, EMPTY }

/** Fixed Chinese speech fixtures, excluded from the application APK. */
class FakeSceneVisionEngine(private val scene: FakeVisionScene) : SceneDescriber {
    override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription =
        SceneDescription(
            text = when (scene) {
                FakeVisionScene.PERSON -> "前方有一个人"
                FakeVisionScene.TREE_AND_CAR -> "画面里有一棵树和一辆车"
                FakeVisionScene.EMPTY -> "当前没有识别到明显物体"
            },
            timestampMs = frame.sourceTimestampMs,
            source = "fake-vision",
        )
}

/** Deterministic fixture driver for the production Canny edge processor. */
class FakeEdgePipeline(private val processor: CannyTactileProcessor = CannyTactileProcessor()) {
    fun process(frame: Yuv420Frame): TactileProcessingResult =
        processor.processDetailed(GrayFrame(frame.width, frame.height, frame.receivedAtElapsedRealtimeMs, frame.y))
}
