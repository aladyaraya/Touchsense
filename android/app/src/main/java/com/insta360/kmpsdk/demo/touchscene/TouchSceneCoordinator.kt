package com.insta360.kmpsdk.demo.touchscene

import com.insta360.kmpsdk.demo.raw.Yuv420Frame
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class TouchSceneUpdate(
    val map: TactileMap? = null,
    val debugSnapshot: TactileDebugSnapshot? = null,
    val sessionState: TactileSessionState = TactileSessionState.LIVE,
)

/** The source image and analysis layers belong to the same TouchMap version. */
data class TactileDebugSnapshot(
    val source: Yuv420Frame,
    val result: TactileProcessingResult,
    val photoBinary: PhotoBinaryLayer,
) {
    val version: Long get() = result.map.version
}

class TouchSceneCoordinator(
    private val frameProcessor: TactileFrameProcessor = DefaultTactileFrameProcessor(),
    private val describer: SceneDescriber = LocalContourSceneDescriber(),
    private val descriptionTimeoutMs: Long = 3_000L,
    private val onUpdate: (TouchSceneUpdate) -> Unit,
) : AutoCloseable {
    init {
        require(descriptionTimeoutMs > 0)
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

    private val session = TactileSession()
    private val latestOfferedFrame = AtomicReference<Yuv420Frame?>()
    private val latestVisionFrame = AtomicReference<Yuv420Frame?>()
    private val latestDebugSnapshot = AtomicReference<TactileDebugSnapshot?>()
    @Volatile private var frozenDebugSnapshot: TactileDebugSnapshot? = null
    private val stillExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "touchscene-still") }
    private val stillGeneration = AtomicLong(0L)
    private val descriptionExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "touchscene-description") }
    private val descriptionTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "touchscene-description-timeout") }
    private val descriptionLock = Any()
    private var currentDescription: DescriptionRequest? = null
    private var cachedDescription: CachedDescription? = null
    private var descriptionClosed = false
    @Volatile private var rotationDegrees: Int = 0
    private val dispatcher =
        ParallelFrameDispatcher<Yuv420Frame>(
            visionConsumer = { latestVisionFrame.set(it) },
            edgeConsumer = { source ->
                val processed = frameProcessor.process(source)
                val result = processed.result
                val map = result.map
                latestDebugSnapshot.set(TactileDebugSnapshot(source, result, processed.photoBinary))
                session.updateLive(map)
                val activeMap = session.activeMap()
                onUpdate(
                    TouchSceneUpdate(
                        map = activeMap,
                        debugSnapshot = snapshotFor(activeMap),
                        sessionState = session.state,
                    ),
                )
            },
        )

    fun offer(frame: Yuv420Frame) {
        val rotated = frame.rotated(rotationDegrees)
        latestOfferedFrame.set(rotated)
        dispatcher.offer(rotated)
    }

    /** Analyze one requested frame, then publish the completed map as a frozen snapshot. */
    fun analyzeAndFreeze(frame: Yuv420Frame, rotationOverride: Int? = null) {
        val generation = stillGeneration.incrementAndGet()
        val rotated = frame.rotated(rotationOverride ?: rotationDegrees)
        latestOfferedFrame.set(rotated)
        stillExecutor.execute {
            val processed = frameProcessor.process(rotated)
            val result = processed.result
            if (generation != stillGeneration.get()) return@execute
            val snapshot = TactileDebugSnapshot(rotated, result, processed.photoBinary)
            latestVisionFrame.set(rotated)
            latestDebugSnapshot.set(snapshot)
            session.updateLive(result.map)
            val frozen = session.freeze() ?: return@execute
            frozenDebugSnapshot = snapshot.takeIf { it.version == frozen.version }
            onUpdate(TouchSceneUpdate(frozen, frozenDebugSnapshot, session.state))
        }
    }

    fun cancelPendingStill() {
        stillGeneration.incrementAndGet()
    }

    fun setRotationDegrees(value: Int) {
        val normalized = ((value % 360) + 360) % 360
        require(normalized in setOf(0, 90, 180, 270))
        if (rotationDegrees == normalized) return
        rotationDegrees = normalized
        if (session.markStale()) {
            val map = session.activeMap()
            onUpdate(TouchSceneUpdate(map, snapshotFor(map), session.state))
        }
    }

    fun freeze(): TactileMap? = session.freeze()?.also {
        frozenDebugSnapshot = latestDebugSnapshot.get()?.takeIf { snapshot -> snapshot.version == it.version }
        onUpdate(TouchSceneUpdate(it, frozenDebugSnapshot, session.state))
    }

    fun refresh(): TactileMap? = session.refresh()?.also {
        frozenDebugSnapshot = latestDebugSnapshot.get()?.takeIf { snapshot -> snapshot.version == it.version }
        onUpdate(TouchSceneUpdate(it, frozenDebugSnapshot, session.state))
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
        }
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
        val map = session.activeMap()
        onUpdate(TouchSceneUpdate(map, snapshotFor(map), session.state))
        return map
    }

    fun activeMapVersion(): Long? = session.activeMap()?.version

    fun currentSessionState(): TactileSessionState = session.currentState()

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
                true
            }
        }
        if (!shouldClose) return
        cancelPendingStill()
        dispatcher.close()
        stillExecutor.shutdownNow()
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
