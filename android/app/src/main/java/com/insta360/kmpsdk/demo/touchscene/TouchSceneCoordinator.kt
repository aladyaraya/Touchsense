package com.insta360.kmpsdk.demo.touchscene

import com.insta360.kmpsdk.demo.raw.Yuv420Frame
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

data class TouchSceneUpdate(
    val map: TactileMap? = null,
    val debugSnapshot: TactileDebugSnapshot? = null,
    val sessionState: TactileSessionState = TactileSessionState.LIVE,
    val stabilityState: StabilityState = StabilityState.MOVING,
    val ready: Boolean = false,
)

/** The source image and analysis layers belong to the same TouchMap version. */
data class TactileDebugSnapshot(
    val source: Yuv420Frame,
    val result: TactileProcessingResult,
) {
    val version: Long get() = result.map.version
}

class TouchSceneCoordinator(
    private val processor: CannyTactileProcessor = CannyTactileProcessor(),
    private val describer: SceneDescriber = LocalContourSceneDescriber(),
    private val descriptionTimeoutMs: Long = 3_000L,
    private val automaticDescriptionIntervalMs: Long = 5_000L,
    private val onAutomaticDescription: ((SceneDescription) -> Unit)? = null,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onUpdate: (TouchSceneUpdate) -> Unit,
) : AutoCloseable {
    init {
        require(descriptionTimeoutMs > 0)
        require(automaticDescriptionIntervalMs > 0)
    }

    private class DescriptionRequest(
        val frameTimestampMs: Long,
        val mapVersion: Long?,
        onResult: (SceneDescription?) -> Unit,
    ) {
        val callbacks = mutableListOf(onResult)
        var worker: Future<*>? = null
        var timeout: ScheduledFuture<*>? = null
        var finished = false
    }

    private data class CachedDescription(
        val frameTimestampMs: Long,
        val mapVersion: Long?,
        val result: SceneDescription,
    )

    private data class RecentAutomaticDescription(
        val result: SceneDescription,
        val analyzedFrameAtMs: Long,
        val motionGeneration: Long,
    )

    private val session = TactileSession()
    private val stability = VideoStabilityEngine()
    private val latestOfferedFrame = AtomicReference<Yuv420Frame?>()
    private val latestVisionFrame = AtomicReference<Yuv420Frame?>()
    private val latestDebugSnapshot = AtomicReference<TactileDebugSnapshot?>()
    @Volatile private var frozenDebugSnapshot: TactileDebugSnapshot? = null
    private val descriptionExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "touchscene-description") }
    private val descriptionTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "touchscene-description-timeout") }
    private val descriptionLock = Any()
    private var currentDescription: DescriptionRequest? = null
    private var cachedDescription: CachedDescription? = null
    private var recentAutomaticDescription: RecentAutomaticDescription? = null
    private var descriptionClosed = false
    private var lastAutomaticStartedAt = Long.MIN_VALUE
    private var automaticToken = 0L
    private var automaticRunning = false
    private var automaticWorker: Future<*>? = null
    private var automaticTimeout: ScheduledFuture<*>? = null
    private val motionGeneration = AtomicLong()
    @Volatile private var rotationDegrees: Int = 0
    private val dispatcher =
        ParallelFrameDispatcher<Yuv420Frame>(
            visionConsumer = {
                latestVisionFrame.set(it)
                scheduleAutomaticDescription(it)
            },
            edgeConsumer = { source ->
                val frame = GrayFrame(source.width, source.height, source.receivedAtElapsedRealtimeMs, source.y)
                val result = processor.processDetailed(frame)
                val map = result.map
                latestDebugSnapshot.set(TactileDebugSnapshot(source, result))
                session.updateLive(map)
                val stabilityUpdate = stability.update(frame, frame.timestampMs)
                if (stabilityUpdate.motionDetected) motionGeneration.incrementAndGet()
                if (stabilityUpdate.state == StabilityState.MOVING) session.markStale()
                val activeMap = session.activeMap()
                onUpdate(
                    TouchSceneUpdate(
                        map = activeMap,
                        debugSnapshot = snapshotFor(activeMap),
                        sessionState = session.state,
                        stabilityState = stabilityUpdate.state,
                        ready = stabilityUpdate.becameReady,
                    ),
                )
            },
        )

    fun offer(frame: Yuv420Frame) {
        val rotated = frame.rotated(rotationDegrees)
        latestOfferedFrame.set(rotated)
        dispatcher.offer(rotated)
    }

    fun setRotationDegrees(value: Int) {
        val normalized = ((value % 360) + 360) % 360
        require(normalized in setOf(0, 90, 180, 270))
        if (rotationDegrees == normalized) return
        rotationDegrees = normalized
        if (session.markStale()) {
            val map = session.activeMap()
            onUpdate(TouchSceneUpdate(map, snapshotFor(map), session.state, stability.state))
        }
    }

    fun freeze(): TactileMap? = session.freeze()?.also {
        frozenDebugSnapshot = latestDebugSnapshot.get()?.takeIf { snapshot -> snapshot.version == it.version }
        onUpdate(TouchSceneUpdate(it, frozenDebugSnapshot, session.state, stability.state))
    }

    fun refresh(): TactileMap? = session.refresh()?.also {
        frozenDebugSnapshot = latestDebugSnapshot.get()?.takeIf { snapshot -> snapshot.version == it.version }
        onUpdate(TouchSceneUpdate(it, frozenDebugSnapshot, session.state, stability.state))
    }

    fun describeLatest(onResult: (SceneDescription?) -> Unit) {
        val activeMap = session.activeMap()
        val live = session.currentState() == TactileSessionState.LIVE
        val snapshot = if (live) latestDebugSnapshot.get()?.takeIf { it.version == activeMap?.version }
            else frozenDebugSnapshot?.takeIf { it.version == activeMap?.version }
        val newestFrame = if (live) latestOfferedFrame.get() ?: latestVisionFrame.get() else null
        val useNewerFrame = live && newestFrame != null &&
            (snapshot == null || newestFrame.receivedAtElapsedRealtimeMs > snapshot.source.receivedAtElapsedRealtimeMs)
        val frame = if (useNewerFrame) newestFrame else snapshot?.source
        val map = if (useNewerFrame) null else activeMap
        var cachedResult: SceneDescription? = null
        synchronized(descriptionLock) {
            if (descriptionClosed) return
            cancelAutomaticLocked()
            if (frame != null) {
                val active = currentDescription
                if (active != null && active.frameTimestampMs == frame.sourceTimestampMs && active.mapVersion == map?.version) {
                    active.callbacks += onResult
                    return
                }
            }
            cancelDescriptionLocked()
            if (frame != null) {
                cachedDescription?.takeIf {
                    it.frameTimestampMs == frame.sourceTimestampMs && it.mapVersion == map?.version
                }?.also {
                    cachedResult = it.result
                    return@synchronized
                }
                if (live && !useNewerFrame && stability.state == StabilityState.STABLE) {
                    recentAutomaticDescription?.takeIf {
                        val age = clockMs() - it.analyzedFrameAtMs
                        it.motionGeneration == motionGeneration.get() && age in 0..automaticDescriptionIntervalMs
                    }?.also {
                        cachedResult = it.result
                        return@synchronized
                    }
                }
                val request = DescriptionRequest(frame.sourceTimestampMs, map?.version, onResult)
                currentDescription = request
                request.worker = descriptionExecutor.submit {
                    finishDescription(request, runCatching { describer.describe(frame, map) }.getOrNull())
                }
                request.timeout = descriptionTimer.schedule({
                    // Claim the timeout before interrupting the worker: an interruption-aware
                    // describer can return immediately and must not win with a late result.
                    finishDescription(request, null, cancelWorker = true)
                }, descriptionTimeoutMs, TimeUnit.MILLISECONDS)
                return
            }
        }
        onResult(cachedResult)
    }

    fun cancelDescription() {
        synchronized(descriptionLock) {
            cancelDescriptionLocked()
            cancelAutomaticLocked()
            recentAutomaticDescription = null
        }
    }

    fun isCurrentAutomaticDescription(description: SceneDescription): Boolean = synchronized(descriptionLock) {
        val cached = recentAutomaticDescription ?: return@synchronized false
        val age = clockMs() - cached.analyzedFrameAtMs
        cached.result === description && cached.motionGeneration == motionGeneration.get() &&
            age in 0..automaticDescriptionIntervalMs && session.currentState() == TactileSessionState.LIVE
    }

    private fun scheduleAutomaticDescription(frame: Yuv420Frame) {
        if (onAutomaticDescription == null) return
        synchronized(descriptionLock) {
            if (descriptionClosed || currentDescription != null || automaticRunning ||
                session.currentState() != TactileSessionState.LIVE) return
            val nowMs = frame.receivedAtElapsedRealtimeMs
            if (lastAutomaticStartedAt != Long.MIN_VALUE && nowMs >= lastAutomaticStartedAt &&
                nowMs - lastAutomaticStartedAt < automaticDescriptionIntervalMs) return
            lastAutomaticStartedAt = nowMs
            automaticRunning = true
            val token = ++automaticToken
            val generation = motionGeneration.get()
            val analyzedFrameAtMs = clockMs()
            automaticWorker = descriptionExecutor.submit {
                val map = latestDebugSnapshot.get()
                    ?.takeIf {
                        it.source.sourceTimestampMs == frame.sourceTimestampMs &&
                            it.source.receivedAtElapsedRealtimeMs == frame.receivedAtElapsedRealtimeMs
                    }
                    ?.result?.map
                finishAutomaticDescription(token, generation, analyzedFrameAtMs,
                    runCatching { describer.describe(frame, map) }.getOrNull())
            }
            automaticTimeout = descriptionTimer.schedule({
                finishAutomaticDescription(token, generation, analyzedFrameAtMs, null, cancelWorker = true)
            }, descriptionTimeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun finishAutomaticDescription(
        token: Long,
        generation: Long,
        analyzedFrameAtMs: Long,
        result: SceneDescription?,
        cancelWorker: Boolean = false,
    ) {
        val callback = synchronized(descriptionLock) {
            if (descriptionClosed || !automaticRunning || token != automaticToken) null else {
                automaticRunning = false
                automaticTimeout?.cancel(false)
                automaticTimeout = null
                if (cancelWorker) automaticWorker?.cancel(true)
                automaticWorker = null
                val usefulResult = result?.takeIf {
                    it.text != "暂时没有可用的画面描述" && generation == motionGeneration.get()
                }
                if (usefulResult != null) {
                    recentAutomaticDescription = RecentAutomaticDescription(usefulResult, analyzedFrameAtMs, generation)
                }
                if (usefulResult != null) onAutomaticDescription else null
            }
        }
        if (callback != null && result != null) callback(result)
    }

    private fun cancelAutomaticLocked() {
        if (!automaticRunning) return
        automaticRunning = false
        automaticToken++
        automaticWorker?.cancel(true)
        automaticTimeout?.cancel(false)
        automaticWorker = null
        automaticTimeout = null
    }

    private fun cancelDescriptionLocked() {
        currentDescription?.also { request ->
            request.finished = true
            request.worker?.cancel(true)
            request.timeout?.cancel(false)
        }
        currentDescription = null
    }

    private fun finishDescription(
        request: DescriptionRequest,
        result: SceneDescription?,
        cancelWorker: Boolean = false,
    ) {
        val callbacks = synchronized(descriptionLock) {
            if (descriptionClosed || currentDescription !== request || request.finished) emptyList() else {
                request.finished = true
                request.timeout?.cancel(false)
                currentDescription = null
                if (result != null) cachedDescription = CachedDescription(request.frameTimestampMs, request.mapVersion, result)
                request.callbacks.toList()
            }
        }
        if (cancelWorker && callbacks.isNotEmpty()) request.worker?.cancel(true)
        callbacks.forEach { it(result) }
    }

    fun leaveExploration(): TactileMap? {
        if (session.currentState() == TactileSessionState.LIVE) return session.activeMap()
        session.leaveExploration()
        frozenDebugSnapshot = null
        stability.rearmReady()
        val map = session.activeMap()
        onUpdate(TouchSceneUpdate(map, snapshotFor(map), session.state, stability.state))
        return map
    }

    fun activeMapVersion(): Long? = session.activeMap()?.version

    private fun snapshotFor(map: TactileMap?): TactileDebugSnapshot? {
        if (map == null) return null
        val candidate = if (session.state == TactileSessionState.LIVE) latestDebugSnapshot.get() else frozenDebugSnapshot
        return candidate?.takeIf { it.version == map.version }
    }

    override fun close() {
        val shouldClose = synchronized(descriptionLock) {
            if (descriptionClosed) false else {
                descriptionClosed = true
                cancelDescriptionLocked()
                cancelAutomaticLocked()
                true
            }
        }
        if (!shouldClose) return
        dispatcher.close()
        descriptionExecutor.shutdownNow()
        descriptionTimer.shutdownNow()
        Thread({
            descriptionExecutor.awaitTermination(descriptionTimeoutMs, TimeUnit.MILLISECONDS)
            (describer as? AutoCloseable)?.close()
        }, "touchscene-description-cleanup").apply { isDaemon = true }.start()
    }
}

internal fun Yuv420Frame.rotated(degrees: Int): Yuv420Frame {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return this
    require(normalized in setOf(90, 180, 270))
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val (rotatedY, outputWidth, outputHeight) = rotatePlane(y, width, height, normalized)
    val (rotatedU, _, _) = rotatePlane(u, chromaWidth, chromaHeight, normalized)
    val (rotatedV, _, _) = rotatePlane(v, chromaWidth, chromaHeight, normalized)
    return copy(width = outputWidth, height = outputHeight, y = rotatedY, u = rotatedU, v = rotatedV)
}

private fun rotatePlane(source: ByteArray, width: Int, height: Int, degrees: Int): Triple<ByteArray, Int, Int> {
    val outputWidth = if (degrees == 90 || degrees == 270) height else width
    val outputHeight = if (degrees == 90 || degrees == 270) width else height
    val output = ByteArray(source.size)
    for (y in 0 until outputHeight) for (x in 0 until outputWidth) {
        val (sourceX, sourceY) =
            when (degrees) {
                90 -> y to (height - 1 - x)
                180 -> (width - 1 - x) to (height - 1 - y)
                270 -> (width - 1 - y) to x
                else -> x to y
            }
        output[y * outputWidth + x] = source[sourceY * width + sourceX]
    }
    return Triple(output, outputWidth, outputHeight)
}
