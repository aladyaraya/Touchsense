package com.insta360.kmpsdk.demo.touchscene

import com.insta360.kmpsdk.demo.raw.Yuv420Frame

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
