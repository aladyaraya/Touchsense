package com.insta360.kmpsdk.demo.touchscene

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.insta360.kmpsdk.demo.R
import kotlin.math.ceil
import kotlin.math.floor

enum class TactileDebugMode { ORIGINAL, GRAYSCALE, BINARY, EDGE, TOUCH_MAP;
    fun next(): TactileDebugMode = entries[(ordinal + 1) % entries.size]
}

class TactileMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var tactileMap: TactileMap? = null
    private var debugSnapshot: TactileDebugSnapshot? = null
    private var debugBitmap: Bitmap? = null
    private var pendingMap: TactileMap? = null
    private var hasPendingMap = false
    private var pendingDebugSnapshot: TactileDebugSnapshot? = null
    private var hasPendingDebugSnapshot = false
    private val accessibilityNavigator = TactileAccessibilityNavigator()
    private var multiTouchSuppressed = false
    var isFingerExploring: Boolean = false
        private set
    val displayedMapVersion: Long?
        get() = tactileMap?.version
    var debugMode: TactileDebugMode = TactileDebugMode.TOUCH_MAP
        private set
    var hapticRenderer: AndroidHapticRenderer? = null
    var onExplorationStarted: ((Long) -> Unit)? = null
    var explorationEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                hapticRenderer?.cancel()
                finishFingerExploration()
            }
        }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.touchscene_map_content_description)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun submitMap(map: TactileMap?) {
        if (isFingerExploring) {
            pendingMap = map
            hasPendingMap = true
            return
        }
        if (tactileMap?.version != map?.version) {
            contentDescription = context.getString(R.string.touchscene_map_content_description)
        }
        tactileMap = map
        accessibilityNavigator.bind(map)
        invalidate()
    }

    /** Native buttons expose map exploration even when TalkBack intercepts touch gestures. */
    fun moveAccessibilityCursor(dx: Int, dy: Int): Boolean {
        if (!explorationEnabled) return false
        val sample = accessibilityNavigator.move(dx, dy) ?: return false
        hapticRenderer?.renderDiscrete(sample.cell)
        announceSample(sample)
        return true
    }

    private fun announceSample(sample: TactileSample) {
        val label = when (sample.cell) {
            TactileCell.BACKGROUND -> R.string.touchscene_cell_background
            TactileCell.SUBJECT -> R.string.touchscene_cell_subject
            TactileCell.BOUNDARY -> R.string.touchscene_cell_boundary
            TactileCell.KEY_POINT -> R.string.touchscene_cell_keypoint
        }
        val announcement = context.getString(
            R.string.touchscene_cell_announcement,
            sample.point.x + 1,
            sample.point.y + 1,
            context.getString(label),
        )
        contentDescription = announcement
        announceForAccessibility(announcement)
    }

    fun submitDebugSnapshot(snapshot: TactileDebugSnapshot?) {
        if (isFingerExploring) {
            pendingDebugSnapshot = snapshot
            hasPendingDebugSnapshot = true
            return
        }
        debugSnapshot = snapshot?.takeIf { it.version == tactileMap?.version }
        rebuildDebugBitmap()
    }

    private fun finishFingerExploration() {
        if (!isFingerExploring) return
        isFingerExploring = false
        if (hasPendingMap) {
            val next = pendingMap
            pendingMap = null
            hasPendingMap = false
            submitMap(next)
        }
        if (hasPendingDebugSnapshot) {
            val next = pendingDebugSnapshot
            pendingDebugSnapshot = null
            hasPendingDebugSnapshot = false
            submitDebugSnapshot(next)
        }
    }

    fun cycleDebugMode(): TactileDebugMode {
        debugMode = debugMode.next()
        rebuildDebugBitmap()
        return debugMode
    }

    private fun rebuildDebugBitmap() {
        debugBitmap?.recycle()
        debugBitmap = null
        val snapshot = debugSnapshot ?: run { invalidate(); return }
        if (debugMode == TactileDebugMode.TOUCH_MAP) {
            invalidate()
            return
        }
        val result = snapshot.result
        debugBitmap = if (debugMode == TactileDebugMode.ORIGINAL) {
            snapshot.source.toRgbDebugBitmap(result.width, result.height)
        } else {
            val pixels = IntArray(result.width * result.height) { i ->
                val level = when (debugMode) {
                    TactileDebugMode.GRAYSCALE -> result.gray[i]
                    TactileDebugMode.BINARY -> if (result.subject[i]) 255 else 0
                    TactileDebugMode.EDGE -> if (result.cannyEdges[i]) 255 else 0
                    else -> 0
                }
                Color.rgb(level, level, level)
            }
            Bitmap.createBitmap(pixels, result.width, result.height, Bitmap.Config.ARGB_8888)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(14, 18, 30))
        val map = tactileMap ?: return
        val bitmap = debugBitmap
        if (bitmap != null && debugMode != TactileDebugMode.TOUCH_MAP) {
            val image = TouchMapper.centerFit(width, height, bitmap.width, bitmap.height)
            paint.color = Color.WHITE
            canvas.drawBitmap(bitmap, null, RectF(image.left, image.top, image.right, image.bottom), paint)
            return
        }
        val content = TouchMapper.centerFit(width, height, map.width, map.height)
        val cellWidth = content.width / map.width
        val cellHeight = content.height / map.height
        for (y in 0 until map.height) for (x in 0 until map.width) {
            paint.color = when (map.cellAt(x, y)) {
                TactileCell.BACKGROUND -> Color.rgb(18, 23, 38)
                TactileCell.SUBJECT -> Color.rgb(248, 166, 52)
                TactileCell.BOUNDARY -> Color.WHITE
                TactileCell.KEY_POINT -> Color.rgb(213, 74, 142)
            }
            canvas.drawRect(
                floor(content.left + x * cellWidth),
                floor(content.top + y * cellHeight),
                ceil(content.left + (x + 1) * cellWidth),
                ceil(content.top + (y + 1) * cellHeight),
                paint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!explorationEnabled) return super.onTouchEvent(event)
        val map = tactileMap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    multiTouchSuppressed = false
                    isFingerExploring = true
                    onExplorationStarted?.invoke(map.version)
                }
                if (event.pointerCount > 1) suppressMultiTouch()
                if (multiTouchSuppressed) return true
                val content = TouchMapper.centerFit(width, height, map.width, map.height)
                val point = TouchMapper.map(event.x, event.y, content, map.width, map.height)
                accessibilityNavigator.select(point)
                hapticRenderer?.render(point, point?.let { map.cellAt(it.x, it.y) } ?: TactileCell.BACKGROUND)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                suppressMultiTouch()
                return true
            }
            MotionEvent.ACTION_UP -> {
                hapticRenderer?.cancel()
                if (multiTouchSuppressed) {
                    contentDescription = context.getString(R.string.touchscene_map_content_description)
                    multiTouchSuppressed = false
                    finishFingerExploration()
                    return true
                }
                val content = TouchMapper.centerFit(width, height, map.width, map.height)
                val point = TouchMapper.map(event.x, event.y, content, map.width, map.height)
                if (point != null) {
                    accessibilityNavigator.select(point)
                    performClick()
                } else {
                    contentDescription = context.getString(R.string.touchscene_map_content_description)
                    super.performClick()
                }
                finishFingerExploration()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hapticRenderer?.cancel()
                multiTouchSuppressed = false
                finishFingerExploration()
                return true
            }
        }
        return true
    }

    private fun suppressMultiTouch() {
        if (multiTouchSuppressed) return
        multiTouchSuppressed = true
        hapticRenderer?.cancel()
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (explorationEnabled) accessibilityNavigator.current()?.let(::announceSample)
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rebuildDebugBitmap()
    }

    override fun onDetachedFromWindow() {
        hapticRenderer?.cancel()
        finishFingerExploration()
        debugBitmap?.recycle()
        debugBitmap = null
        super.onDetachedFromWindow()
    }
}
