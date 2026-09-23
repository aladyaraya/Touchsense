package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTouchSceneSourcesTest {
    private val camera = FakeCameraGateway()
    private val edge = FakeEdgePipeline()

    @Test
    fun `four deterministic camera fixtures traverse the production edge path`() {
        FakeContourScene.entries.forEachIndexed { index, scene ->
            val frame = camera.frame(scene, index.toLong() + 1)
            assertEquals(256, frame.width)
            assertEquals(192, frame.height)
            assertEquals(index.toLong() + 1, frame.sourceTimestampMs)
            val result = edge.process(frame)
            assertEquals(64, result.map.width)
            assertEquals(48, result.map.height)
            val cells = result.map.copyCells().toSet()
            if (scene == FakeContourScene.EMPTY) {
                assertFalse(result.subject.any { it })
                assertTrue(cells.all { it == TactileCell.BACKGROUND.code })
            } else {
                assertTrue("$scene must produce fill", cells.contains(TactileCell.SUBJECT.code))
                assertTrue("$scene must produce boundary", cells.contains(TactileCell.BOUNDARY.code))
            }
        }
    }

    @Test
    fun `fake vision phrases are explicitly marked as simulated`() {
        val frame = camera.frame(FakeContourScene.IRREGULAR, 17)
        val expected = mapOf(
            FakeVisionScene.PERSON to "前方有一个人",
            FakeVisionScene.TREE_AND_CAR to "画面里有一棵树和一辆车",
            FakeVisionScene.EMPTY to "当前没有识别到明显物体",
        )
        expected.forEach { (scene, phrase) ->
            val result = FakeSceneVisionEngine(scene).describe(frame, null)
            assertEquals(phrase, result.text)
            assertEquals("fake-vision", result.source)
            assertEquals(17L, result.timestampMs)
        }
    }

}
