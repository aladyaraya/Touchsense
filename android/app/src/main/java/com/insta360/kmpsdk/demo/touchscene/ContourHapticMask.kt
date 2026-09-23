package com.insta360.kmpsdk.demo.touchscene

import kotlin.math.ceil
import kotlin.math.min

/** Builds the one contour band shared by rendering and finger hit-testing. */
internal object ContourHapticMask {
    fun radiusForDisplay(
        analysisWidth: Int,
        analysisHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        density: Float,
        halfWidthDp: Float = 10f,
    ): Int {
        if (analysisWidth <= 0 || analysisHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1
        val displayScale = min(viewWidth.toFloat() / analysisWidth, viewHeight.toFloat() / analysisHeight)
        if (displayScale <= 0f) return 1
        val requested = ceil(halfWidthDp * density / displayScale).toInt().coerceAtLeast(1)
        return requested.coerceAtMost((min(analysisWidth, analysisHeight) / 10).coerceAtLeast(1))
    }

    /** Circular dilation produces a stable finger-sized band without square corner artifacts. */
    fun build(boundary: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        require(width > 0 && height > 0 && boundary.size == width * height && radius >= 0)
        if (radius == 0) return boundary.copyOf()
        val offsets = ArrayList<Pair<Int, Int>>()
        val squaredRadius = radius * radius
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx * dx + dy * dy <= squaredRadius) offsets += dx to dy
        }
        val output = BooleanArray(boundary.size)
        boundary.forEachIndexed { index, isBoundary ->
            if (!isBoundary) return@forEachIndexed
            val x = index % width
            val y = index / width
            offsets.forEach { (dx, dy) ->
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until width && ny in 0 until height) output[ny * width + nx] = true
            }
        }
        return output
    }
}
