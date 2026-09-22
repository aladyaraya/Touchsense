package com.insta360.kmpsdk.demo.touchscene

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import com.insta360.kmpsdk.demo.raw.Yuv420Frame

class TouchSceneCoreTest {
    @Test
    fun `touch mapper respects center-fit letterbox and bounds`() {
        val rect = TouchMapper.centerFit(1_000, 1_000, 64, 48)
        assertNull(TouchMapper.map(500f, 50f, rect))
        assertEquals(GridPoint(0, 0), TouchMapper.map(rect.left, rect.top, rect))
        assertEquals(GridPoint(32, 24), TouchMapper.map(500f, 500f, rect))
        assertEquals(GridPoint(63, 47), TouchMapper.map(rect.right - 0.01f, rect.bottom - 0.01f, rect))
    }

    @Test
    fun `hundred center-fit software coordinates map deterministically`() {
        val content = TouchMapper.centerFit(1280, 900, 640, 360)
        assertEquals(90f, content.top)
        var checked = 0
        for (row in 0 until 10) for (column in 0 until 10) {
            val touchX = content.left + (column + 0.5f) * content.width / 10f
            val touchY = content.top + (row + 0.5f) * content.height / 10f
            val expected = GridPoint(((column + 0.5f) * 64 / 10).toInt(), ((row + 0.5f) * 48 / 10).toInt())
            assertEquals(expected, TouchMapper.map(touchX, touchY, content))
            checked++
        }
        assertEquals(100, checked)
        assertNull(TouchMapper.map(640f, 89f, content))
        assertNull(TouchMapper.map(640f, 811f, content))
    }

    @Test
    fun `frozen map never changes when live frames continue`() {
        val session = TactileSession()
        val first = mapOf(TactileCell.SUBJECT, 1)
        val second = mapOf(TactileCell.BOUNDARY, 2)
        session.updateLive(first)
        val frozen = session.freeze()
        session.updateLive(second)
        assertEquals(first.version, session.activeMap()?.version)
        assertEquals(TactileCell.SUBJECT, session.activeMap()?.cellAt(1, 1))
        assertTrue(session.markStale())
        assertEquals(TactileSessionState.STALE, session.state)
        assertEquals(second.version, session.refresh()?.version)
        assertEquals(TactileSessionState.EXPLORING, session.state)
        assertNotNull(frozen)
    }

    @Test
    fun `leaving exploration restores newest live map and permits another freeze`() {
        val session = TactileSession()
        session.updateLive(mapOf(TactileCell.SUBJECT, 1))
        assertEquals(1L, session.freeze()?.version)
        session.updateLive(mapOf(TactileCell.BOUNDARY, 2))
        assertEquals(1L, session.activeMap()?.version)
        session.leaveExploration()
        assertEquals(TactileSessionState.LIVE, session.currentState())
        assertEquals(2L, session.activeMap()?.version)
        assertEquals(2L, session.freeze()?.version)
        assertEquals(TactileSessionState.EXPLORING, session.currentState())
    }

    @Test
    fun `refresh requires a newer processed frame and preserves stale state otherwise`() {
        val session = TactileSession()
        val first = mapOf(TactileCell.SUBJECT, 1)
        assertNull(session.refresh())
        session.updateLive(first)
        session.freeze()
        assertTrue(session.markStale())
        assertNull(session.refresh())
        assertEquals(TactileSessionState.STALE, session.state)
        assertEquals(1L, session.activeMap()?.version)
        session.updateLive(mapOf(TactileCell.BOUNDARY, 1))
        assertNull(session.refresh())
        assertEquals(TactileSessionState.STALE, session.state)
        session.updateLive(mapOf(TactileCell.BOUNDARY, 2))
        assertEquals(2L, session.refresh()?.version)
        assertEquals(TactileSessionState.EXPLORING, session.state)
    }

    @Test
    fun `canny pipeline creates a 64 by 48 tactile subject with boundary`() {
        val width = 256
        val height = 192
        val gray = ByteArray(width * height) { 240.toByte() }
        for (y in 40 until 152) for (x in 70 until 190) gray[y * width + x] = 20
        val result = CannyTactileProcessor().process(GrayFrame(width, height, 10L, gray))
        assertEquals(64, result.width)
        assertEquals(48, result.height)
        val values = result.copyCells().toSet()
        assertTrue(values.contains(TactileCell.BACKGROUND.code))
        assertTrue(values.contains(TactileCell.SUBJECT.code))
        assertTrue(values.contains(TactileCell.BOUNDARY.code))
    }

    @Test
    fun `uniform frame does not invent a tactile subject`() {
        val frame = GrayFrame(256, 192, 11L, ByteArray(256 * 192) { 128.toByte() })
        val result = CannyTactileProcessor().processDetailed(frame)
        assertTrue(result.usedThresholdFallback)
        assertFalse(result.subject.any { it })
        assertFalse(result.boundary.any { it })
        assertTrue(result.map.copyCells().all { it == TactileCell.BACKGROUND.code })
    }

    @Test
    fun `threshold fallback still creates subject and tactile boundary`() {
        val width = 256
        val height = 192
        val gray = ByteArray(width * height) { 240.toByte() }
        for (y in 40 until 152) for (x in 70 until 190) gray[y * width + x] = 20
        val processor = CannyTactileProcessor(CannyConfig(lowThreshold = 100_000f, highThreshold = 200_000f))
        val result = processor.processDetailed(GrayFrame(width, height, 12L, gray))
        assertTrue(result.usedThresholdFallback)
        assertFalse(result.cannyEdges.any { it })
        assertTrue(result.subject.any { it })
        assertTrue(result.boundary.any { it })
        val cells = result.map.copyCells().toSet()
        assertTrue(cells.contains(TactileCell.SUBJECT.code))
        assertTrue(cells.contains(TactileCell.BOUNDARY.code))
    }

    @Test
    fun `fallback keeps the largest disconnected subject`() {
        val width = 256
        val height = 192
        val gray = ByteArray(width * height) { 240.toByte() }
        for (y in 20 until 40) for (x in 20 until 40) gray[y * width + x] = 20
        for (y in 60 until 150) for (x in 110 until 200) gray[y * width + x] = 20
        val result = CannyTactileProcessor(
            CannyConfig(lowThreshold = 100_000f, highThreshold = 200_000f),
        ).processDetailed(GrayFrame(width, height, 13L, gray))
        assertTrue(result.usedThresholdFallback)
        assertFalse(result.subject[30 * width + 30])
        assertTrue(result.subject[100 * width + 150])
    }

    @Test
    fun `circular fixture has center fill and outer background`() {
        val width = 256
        val height = 192
        val gray = ByteArray(width * height) { 240.toByte() }
        for (y in 0 until height) for (x in 0 until width) {
            val dx = x - 128
            val dy = y - 96
            if (dx * dx + dy * dy <= 55 * 55) gray[y * width + x] = 20
        }
        val result = CannyTactileProcessor().processDetailed(GrayFrame(width, height, 14L, gray))
        assertTrue(result.subject[96 * width + 128])
        assertFalse(result.subject[10 * width + 10])
        assertTrue(result.boundary.any { it })
    }

    @Test
    fun `connected person silhouette keeps head torso arms and legs`() {
        val width = 256
        val height = 192
        val gray = ByteArray(width * height) { 240.toByte() }
        fun darkRect(left: Int, top: Int, right: Int, bottom: Int) {
            for (y in top until bottom) for (x in left until right) gray[y * width + x] = 20
        }
        for (y in 28 until 76) for (x in 0 until width) {
            val dx = x - 128
            val dy = y - 52
            if (dx * dx + dy * dy <= 22 * 22) gray[y * width + x] = 20
        }
        darkRect(105, 71, 151, 143)
        darkRect(84, 81, 107, 94)
        darkRect(149, 81, 172, 94)
        darkRect(111, 142, 125, 180)
        darkRect(131, 142, 145, 180)

        val result = CannyTactileProcessor().processDetailed(GrayFrame(width, height, 17L, gray))
        assertTrue(result.subject[52 * width + 128])
        assertTrue(result.subject[110 * width + 128])
        assertTrue(result.subject[86 * width + 94])
        assertTrue(result.subject[86 * width + 162])
        assertTrue(result.subject[160 * width + 118])
        assertTrue(result.subject[160 * width + 138])
        assertFalse(result.subject[20 * width + 20])
        assertTrue(result.map.copyCells().contains(TactileCell.BOUNDARY.code))
    }

    @Test
    fun `wide source keeps aspect and silent letterbox rows`() {
        val width = 320
        val height = 180
        val gray = ByteArray(width * height) { 240.toByte() }
        for (y in 40 until 140) for (x in 110 until 210) gray[y * width + x] = 20
        val result = CannyTactileProcessor().processDetailed(GrayFrame(width, height, 15L, gray))
        assertEquals(256, result.width)
        assertEquals(144, result.height)
        val content = TouchMapper.centerFit(64, 48, result.width, result.height)
        assertEquals(6f, content.top)
        for (y in 0 until 6) for (x in 0 until 64) assertEquals(TactileCell.BACKGROUND, result.map.cellAt(x, y))
        for (y in 42 until 48) for (x in 0 until 64) assertEquals(TactileCell.BACKGROUND, result.map.cellAt(x, y))
        assertTrue(result.map.cellAt(32, 24) != TactileCell.BACKGROUND)
    }

    @Test
    fun `portrait source keeps aspect and silent letterbox columns`() {
        val width = 180
        val height = 320
        val gray = ByteArray(width * height) { 240.toByte() }
        for (y in 100 until 220) for (x in 55 until 125) gray[y * width + x] = 20
        val result = CannyTactileProcessor().processDetailed(GrayFrame(width, height, 16L, gray))
        assertEquals(108, result.width)
        assertEquals(192, result.height)
        for (x in 0 until 18) for (y in 0 until 48) assertEquals(TactileCell.BACKGROUND, result.map.cellAt(x, y))
        for (x in 46 until 64) for (y in 0 until 48) assertEquals(TactileCell.BACKGROUND, result.map.cellAt(x, y))
        assertTrue(result.map.cellAt(32, 24) != TactileCell.BACKGROUND)
    }

    @Test
    fun `debug layers remain paired with frozen touch map while live frames advance`() {
        val firstUpdate = CountDownLatch(1)
        val nextLiveFrame = CountDownLatch(1)
        val described = CountDownLatch(1)
        val descriptionText = AtomicReference<String>()
        val latest = AtomicReference<TouchSceneUpdate>()
        val describer = object : SceneDescriber {
            override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription =
                SceneDescription("${frame.sourceTimestampMs}:${map?.version}", frame.sourceTimestampMs, "test")
        }
        TouchSceneCoordinator(describer = describer) { update ->
            latest.set(update)
            if (update.map != null && update.sessionState == TactileSessionState.LIVE) firstUpdate.countDown()
            if (update.sessionState != TactileSessionState.LIVE &&
                update.debugSnapshot?.source?.sourceTimestampMs == 1L &&
                Thread.currentThread().name.contains("touchscene-edge")) nextLiveFrame.countDown()
        }.use { coordinator ->
            coordinator.offer(testYuvFrame(1L, 20))
            assertTrue(firstUpdate.await(3, TimeUnit.SECONDS))
            val frozen = coordinator.freeze()
            assertNotNull(frozen)
            assertEquals(frozen?.version, latest.get().debugSnapshot?.version)
            coordinator.offer(testYuvFrame(2L, 70))
            assertTrue(nextLiveFrame.await(3, TimeUnit.SECONDS))
            val update = latest.get()
            assertEquals(frozen?.version, update.map?.version)
            assertEquals(frozen?.version, update.debugSnapshot?.version)
            assertEquals(1L, update.debugSnapshot?.source?.sourceTimestampMs)
            assertEquals(TactileSessionState.EXPLORING, update.sessionState)
            coordinator.describeLatest { description ->
                descriptionText.set(description?.text)
                described.countDown()
            }
            assertTrue(described.await(1, TimeUnit.SECONDS))
            assertEquals("1:${frozen?.version}", descriptionText.get())
        }
    }

    @Test
    fun `local description exposes measured subject position size and risk fields`() {
        val description = LocalContourSceneDescriber().describe(
            testYuvFrame(20L, 30),
            mapOf(TactileCell.SUBJECT, 1),
        )
        assertEquals("未识别类别的主要轮廓", description.subject)
        assertNotNull(description.position)
        assertNotNull(description.approximateSize)
        assertNotNull(description.framingRisk)
        assertFalse(description.clipped ?: true)
        assertNull(description.occlusion)
        assertNull(description.backgroundContext)
        assertTrue(description.text.startsWith("画面有一处"))
    }

    @Test
    fun `frozen exploration describes its own frame instead of a recent live result`() {
        val frozenFrameReady = CountDownLatch(1)
        val described = CountDownLatch(1)
        val calls = AtomicInteger()
        val result = AtomicReference<SceneDescription?>()
        val describer = object : SceneDescriber {
            override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription =
                SceneDescription("frame-${frame.sourceTimestampMs}-${calls.incrementAndGet()}", frame.sourceTimestampMs, "test")
        }
        TouchSceneCoordinator(describer = describer) { update ->
            if (update.debugSnapshot?.source?.sourceTimestampMs == 100L) frozenFrameReady.countDown()
        }.use { coordinator ->
            coordinator.offer(testYuvFrame(0, 30))
            coordinator.offer(testYuvFrame(100, 30))
            assertTrue(frozenFrameReady.await(3, TimeUnit.SECONDS))
            assertNotNull(coordinator.freeze())
            coordinator.describeLatest { result.set(it); described.countDown() }
            assertTrue(described.await(3, TimeUnit.SECONDS))
            assertEquals("frame-100-1", result.get()?.text)
        }
    }

    @Test
    fun `manual query uses newer offered frame without pairing an older edge map`() {
        val edgeBlocked = CountDownLatch(1)
        val releaseEdge = CountDownLatch(1)
        val described = CountDownLatch(1)
        val result = AtomicReference<SceneDescription?>()
        val describer = object : SceneDescriber {
            override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription =
                SceneDescription("${frame.sourceTimestampMs}:${map?.version}", frame.sourceTimestampMs, "test")
        }
        TouchSceneCoordinator(describer = describer) { update ->
            if (update.debugSnapshot?.source?.sourceTimestampMs == 0L) {
                edgeBlocked.countDown()
                releaseEdge.await(3, TimeUnit.SECONDS)
            }
        }.use { coordinator ->
            try {
                coordinator.offer(testYuvFrame(0, 30))
                assertTrue(edgeBlocked.await(3, TimeUnit.SECONDS))
                coordinator.offer(testYuvFrame(2, 50))
                coordinator.describeLatest { result.set(it); described.countDown() }
                assertTrue(described.await(3, TimeUnit.SECONDS))
                assertEquals("2:null", result.get()?.text)
            } finally {
                releaseEdge.countDown()
            }
        }
    }

    @Test
    fun `new description request cancels old work and suppresses its result`() {
        val frameReady = CountDownLatch(1)
        val secondFrameReady = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val secondDelivered = CountDownLatch(1)
        val calls = AtomicInteger()
        val edgeUpdates = AtomicInteger()
        val firstCallbacks = AtomicInteger()
        val secondText = AtomicReference<String>()
        val describer = object : SceneDescriber {
            override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription {
                val call = calls.incrementAndGet()
                if (call == 1) {
                    firstStarted.countDown()
                    try { Thread.sleep(3_000) } catch (_: InterruptedException) { /* cancellation */ }
                }
                return SceneDescription("request-$call", frame.sourceTimestampMs, "test")
            }
        }
        TouchSceneCoordinator(describer = describer) { update ->
            if (update.map != null) {
                if (edgeUpdates.incrementAndGet() == 1) frameReady.countDown() else secondFrameReady.countDown()
            }
        }.use { coordinator ->
            coordinator.offer(testYuvFrame(21L, 30))
            assertTrue(frameReady.await(3, TimeUnit.SECONDS))
            coordinator.describeLatest { firstCallbacks.incrementAndGet() }
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            coordinator.offer(testYuvFrame(24L, 40))
            assertTrue(secondFrameReady.await(3, TimeUnit.SECONDS))
            coordinator.describeLatest { result ->
                secondText.set(result?.text)
                secondDelivered.countDown()
            }
            assertTrue(secondDelivered.await(1, TimeUnit.SECONDS))
            assertEquals("request-2", secondText.get())
            assertEquals(0, firstCallbacks.get())
        }
    }

    @Test
    fun `same-frame description requests share one inference`() {
        val frameReady = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val calls = AtomicInteger()
        val describer = object : SceneDescriber {
            override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription {
                calls.incrementAndGet()
                firstStarted.countDown()
                release.await(1, TimeUnit.SECONDS)
                return SceneDescription("shared", frame.sourceTimestampMs, "test")
            }
        }
        TouchSceneCoordinator(describer = describer) { update ->
            if (update.map != null) frameReady.countDown()
        }.use { coordinator ->
            coordinator.offer(testYuvFrame(25L, 30))
            assertTrue(frameReady.await(3, TimeUnit.SECONDS))
            coordinator.describeLatest { delivered.countDown() }
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            coordinator.describeLatest { delivered.countDown() }
            release.countDown()
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `completed description is reused immediately for the same frozen frame`() {
        val frameReady = CountDownLatch(1)
        val firstDelivered = CountDownLatch(1)
        val calls = AtomicInteger()
        val first = AtomicReference<SceneDescription?>()
        val describer = object : SceneDescriber {
            override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription =
                SceneDescription("analysis-${calls.incrementAndGet()}", frame.sourceTimestampMs, "test")
        }
        TouchSceneCoordinator(describer = describer) { update ->
            if (update.map != null && Thread.currentThread().name.contains("touchscene-edge")) frameReady.countDown()
        }.use { coordinator ->
            coordinator.offer(testYuvFrame(31L, 30))
            assertTrue(frameReady.await(3, TimeUnit.SECONDS))
            assertNotNull(coordinator.freeze())
            coordinator.describeLatest { first.set(it); firstDelivered.countDown() }
            assertTrue(firstDelivered.await(1, TimeUnit.SECONDS))
            var cached: SceneDescription? = null
            coordinator.describeLatest { cached = it }
            assertEquals("analysis-1", first.get()?.text)
            assertEquals(first.get(), cached)
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `new frame and failed analysis are never served from old description cache`() {
        val firstFrame = CountDownLatch(1)
        val secondFrame = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val calls = AtomicInteger()
        val failedResult = AtomicReference<SceneDescription?>()
        val result = AtomicReference<SceneDescription?>()
        val describer = object : SceneDescriber {
            override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription {
                if (calls.incrementAndGet() == 1) error("temporary model failure")
                return SceneDescription("frame-${frame.sourceTimestampMs}", frame.sourceTimestampMs, "test")
            }
        }
        TouchSceneCoordinator(describer = describer) { update ->
            if (Thread.currentThread().name.contains("touchscene-edge")) {
                when (update.debugSnapshot?.source?.sourceTimestampMs) {
                    41L -> firstFrame.countDown()
                    42L -> secondFrame.countDown()
                }
            }
        }.use { coordinator ->
            coordinator.offer(testYuvFrame(41L, 30))
            assertTrue(firstFrame.await(3, TimeUnit.SECONDS))
            coordinator.describeLatest { failedResult.set(it); failed.countDown() }
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            assertNull(failedResult.get())
            coordinator.describeLatest { result.set(it); delivered.countDown() }
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
            assertEquals("frame-41", result.get()?.text)
            coordinator.offer(testYuvFrame(42L, 50))
            assertTrue(secondFrame.await(3, TimeUnit.SECONDS))
            val newer = CountDownLatch(1)
            coordinator.describeLatest { result.set(it); newer.countDown() }
            assertTrue(newer.await(1, TimeUnit.SECONDS))
            assertEquals("frame-42", result.get()?.text)
            assertEquals(3, calls.get())
        }
    }

    @Test
    fun `description timeout delivers one failure without blocking edge updates`() {
        val frameReady = CountDownLatch(1)
        val edgeAdvanced = CountDownLatch(1)
        val timedOut = CountDownLatch(1)
        val workerInterrupted = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val delivered = AtomicReference<SceneDescription?>()
        val edgeUpdates = AtomicInteger()
        val describer = object : SceneDescriber {
            override fun describe(frame: Yuv420Frame, map: TactileMap?): SceneDescription {
                try { Thread.sleep(3_000) } catch (_: InterruptedException) { workerInterrupted.countDown() }
                return SceneDescription("late", frame.sourceTimestampMs, "test")
            }
        }
        TouchSceneCoordinator(describer = describer, descriptionTimeoutMs = 80L) { update ->
            if (update.map != null) {
                if (edgeUpdates.incrementAndGet() == 1) frameReady.countDown() else edgeAdvanced.countDown()
            }
        }.use { coordinator ->
            coordinator.offer(testYuvFrame(22L, 30))
            assertTrue(frameReady.await(3, TimeUnit.SECONDS))
            coordinator.describeLatest { result ->
                delivered.set(result)
                callbacks.incrementAndGet()
                timedOut.countDown()
            }
            assertTrue(timedOut.await(3, TimeUnit.SECONDS))
            assertNull(delivered.get())
            assertTrue(workerInterrupted.await(1, TimeUnit.SECONDS))
            coordinator.offer(testYuvFrame(23L, 40))
            assertTrue(edgeAdvanced.await(3, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
        }
    }

    @Test
    fun `parallel lanes both see a frame without blocking one another`() {
        val visionStarted = CountDownLatch(1)
        val releaseVision = CountDownLatch(1)
        val edgeDone = CountDownLatch(1)
        val frame = GrayFrame(2, 2, 1L, ByteArray(4))
        ParallelFrameDispatcher<GrayFrame>(
            visionConsumer = {
                visionStarted.countDown()
                releaseVision.await(2, TimeUnit.SECONDS)
            },
            edgeConsumer = { edgeDone.countDown() },
        ).use { dispatcher ->
            dispatcher.offer(frame)
            assertTrue(visionStarted.await(1, TimeUnit.SECONDS))
            assertTrue(edgeDone.await(1, TimeUnit.SECONDS))
            releaseVision.countDown()
        }
    }

    @Test
    fun `rejected vision scheduling during shutdown does not crash or block edge lane`() {
        val stoppedVisionExecutor = Executors.newSingleThreadExecutor()
        stoppedVisionExecutor.shutdown()
        val edgeDone = CountDownLatch(1)
        ParallelFrameDispatcher<GrayFrame>(
            visionConsumer = { throw AssertionError("A stopped vision lane must not run") },
            edgeConsumer = { edgeDone.countDown() },
            visionExecutor = stoppedVisionExecutor,
        ).use { dispatcher ->
            dispatcher.offer(GrayFrame(2, 2, 1L, ByteArray(4)))
            assertTrue(edgeDone.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `haptic sustained timings match minimal binary contract`() {
        assertArrayEquals(longArrayOf(0L, 20L, 160L), AndroidHapticRenderer.SUSTAINED_TIMINGS)
    }

    @Test
    fun `bailian request body follows openai compatible vision protocol`() {
        val body = JSONObject(RemoteAiSceneDescriber.buildRequestBody("qwen3-vl-flash", "QUJD"))
        assertEquals("qwen3-vl-flash", body.getString("model"))
        assertEquals(false, body.getBoolean("enable_thinking"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            content.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `bailian response parser reads description from json content`() {
        val body = """{"choices":[{"message":{"content":"{\"description\":\"主体居中，占比中等。\"}"}}]}"""
        assertEquals("主体居中，占比中等。", RemoteAiSceneDescriber.parseResponseText(body))
    }

    @Test
    fun `bailian response parser handles part array and plain text fallback`() {
        val parts =
            """{"choices":[{"message":{"content":[{"type":"text","text":"{\"description\":\"一位行人在右侧。\"}"}]}}]}"""
        assertEquals("一位行人在右侧。", RemoteAiSceneDescriber.parseResponseText(parts))
        val plain = """{"choices":[{"message":{"content":"画面里有一棵树。"}}]}"""
        assertEquals("画面里有一棵树。", RemoteAiSceneDescriber.parseResponseText(plain))
        assertNull(RemoteAiSceneDescriber.parseResponseText("""{"choices":[{"message":{"content":"  "}}]}"""))
        assertNull(RemoteAiSceneDescriber.parseResponseText("not json"))
    }

    @Test
    fun `voice parser accepts whitelist and rejects open text`() {
        assertEquals(VoiceCommand.TakePhoto, VoiceCommandParser.parse("拍一张！"))
        assertEquals(VoiceCommand.StartStream, VoiceCommandParser.parse("开始录像"))
        assertEquals(VoiceCommand.StopStream, VoiceCommandParser.parse("停止录像"))
        assertEquals(VoiceCommand.FreezeFrame, VoiceCommandParser.parse("冻结当前画面"))
        assertEquals(VoiceCommand.ReturnLive, VoiceCommandParser.parse("恢复视频流"))
        assertEquals(VoiceCommand.ReturnLive, VoiceCommandParser.parse("取消冻结"))
        assertEquals(VoiceCommand.ReturnLive, VoiceCommandParser.parse("返回实时"))
        assertTrue(VoiceCommandParser.parse("帮我找个人") is VoiceCommand.Unknown)
    }

    @Test
    fun `posture rotation rotates luminance and swaps dimensions`() {
        val frame =
            Yuv420Frame(
                width = 3,
                height = 2,
                sourceTimestampMs = 1,
                receivedAtElapsedRealtimeMs = 1,
                y = byteArrayOf(1, 2, 3, 4, 5, 6),
                u = byteArrayOf(10, 11),
                v = byteArrayOf(20, 21),
            )
        val rotated = frame.rotated(90)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        assertTrue(rotated.y.contentEquals(byteArrayOf(4, 1, 5, 2, 6, 3)))
    }

    @Test
    fun `photo binary threshold splits luminance around 63`() {
        val width = 128
        val height = 96
        val y = ByteArray(width * height) { idx ->
            val col = idx % width
            (if (col < width / 2) 20 else 200).toByte()
        }
        val frame = Yuv420Frame(
            width = width,
            height = height,
            sourceTimestampMs = 1L,
            receivedAtElapsedRealtimeMs = 1L,
            y = y,
            u = ByteArray((width / 2) * (height / 2)),
            v = ByteArray((width / 2) * (height / 2)),
        )
        val layer = PhotoBinaryProcessor.process(frame)
        assertEquals(PhotoBinaryProcessor.SHORT_EDGE, layer.width)
        assertEquals(0, layer.at(0, layer.height / 2))
        assertEquals(1, layer.at(layer.width - 1, layer.height / 2))
    }

    private fun mapOf(cell: TactileCell, version: Long): TactileMap {
        val cells = ByteArray(64 * 48)
        cells[65] = cell.code
        return TactileMap(version, version, cells = cells)
    }

    private fun testYuvFrame(timestamp: Long, subjectLuma: Int): Yuv420Frame {
        val width = 256
        val height = 192
        val y = ByteArray(width * height) { 240.toByte() }
        for (row in 40 until 152) for (column in 70 until 190) y[row * width + column] = subjectLuma.toByte()
        val chroma = ByteArray((width / 2) * (height / 2)) { 128.toByte() }
        return Yuv420Frame(width, height, timestamp, timestamp, y, chroma, chroma)
    }
}
