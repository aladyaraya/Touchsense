package com.insta360.kmpsdk.demo.touchscene

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

data class CannyConfig(
    val analysisWidth: Int = 256,
    val analysisHeight: Int = 192,
    val blurRadius: Int = 2,
    val lowThreshold: Float = 50f,
    val highThreshold: Float = 150f,
    val closeRadius: Int = 1,
    val minimumComponentRatio: Float = 0.01f,
    val tactileBoundaryRadius: Int = 2,
) {
    init {
        require(analysisWidth > 0 && analysisHeight > 0)
        require(blurRadius in 0..4)
        require(lowThreshold >= 0 && highThreshold > lowThreshold)
        require(closeRadius in 0..4)
        require(minimumComponentRatio in 0f..1f)
        require(tactileBoundaryRadius in 1..4)
    }
}

/** Intermediate analysis layers for the in-app debug view and fixture tests. */
data class TactileProcessingResult(
    val width: Int,
    val height: Int,
    val gray: IntArray,
    val blurred: IntArray,
    val cannyEdges: BooleanArray,
    val closedEdges: BooleanArray,
    val subject: BooleanArray,
    val boundary: BooleanArray,
    val usedThresholdFallback: Boolean,
    val map: TactileMap,
)

/**
 * Dependency-free Canny MVP used on the Y plane from MediaCodec. It performs Gaussian blur,
 * Sobel gradient, non-maximum suppression, double-threshold hysteresis, morphological close,
 * largest-contour selection, interior fill, and 64x48 nearest-neighbour reduction.
 */
class CannyTactileProcessor(private val config: CannyConfig = CannyConfig()) {
    private val versions = AtomicLong(0L)

    fun process(frame: GrayFrame): TactileMap = processDetailed(frame).map

    fun processDetailed(frame: GrayFrame): TactileProcessingResult {
        // The configured dimensions are bounds, not a target aspect ratio. Analyze the
        // complete source frame without stretching a 16:9 or portrait camera image to 4:3.
        val scale = minOf(config.analysisWidth.toDouble() / frame.width, config.analysisHeight.toDouble() / frame.height)
        val width = (frame.width * scale).toInt().coerceIn(1, config.analysisWidth)
        val height = (frame.height * scale).toInt().coerceIn(1, config.analysisHeight)
        val gray = resize(frame.luminance, frame.width, frame.height, width, height)
        val blurred = gaussianBlur(gray, width, height, config.blurRadius)
        val edges = canny(blurred, width, height)
        val closed = close(edges, width, height, config.closeRadius)
        val mainBoundary = largestComponent(closed, width, height)
        var subject = fillInterior(mainBoundary, width, height)
        val interiorCount = subject.indices.count { subject[it] && !mainBoundary[it] }
        val boundaryCount = mainBoundary.count { it }
        val hasPlausibleInterior =
            interiorCount >= maxOf(16, boundaryCount / 2) && subject.count { it } < subject.size * 9 / 10
        if (!hasPlausibleInterior) {
            // Open contours do not enclose a subject. Fall back to a threshold component.
            subject = largestThresholdComponent(blurred, width, height)
        }
        val boundary = subjectBoundary(subject, width, height)
        return TactileProcessingResult(
            width,
            height,
            gray,
            blurred,
            edges,
            closed,
            subject,
            boundary,
            !hasPlausibleInterior,
            reduceToTouchMap(subject, boundary, width, height, frame.timestampMs),
        )
    }

    private fun subjectBoundary(subject: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(subject.size) { index ->
            if (!subject[index]) false else {
                val x = index % width
                val y = index / width
                x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                    !subject[index - 1] || !subject[index + 1] ||
                    !subject[index - width] || !subject[index + width]
            }
        }

    private fun canny(src: IntArray, width: Int, height: Int): BooleanArray {
        val magnitude = FloatArray(src.size)
        val direction = ByteArray(src.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val gx =
                    -src[i - width - 1] + src[i - width + 1] - 2 * src[i - 1] + 2 * src[i + 1] -
                        src[i + width - 1] + src[i + width + 1]
                val gy =
                    src[i - width - 1] + 2 * src[i - width] + src[i - width + 1] -
                        src[i + width - 1] - 2 * src[i + width] - src[i + width + 1]
                magnitude[i] = hypot(gx.toFloat(), gy.toFloat())
                var angle = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble()))
                if (angle < 0) angle += 180.0
                direction[i] = when {
                    angle < 22.5 || angle >= 157.5 -> 0
                    angle < 67.5 -> 1
                    angle < 112.5 -> 2
                    else -> 3
                }
            }
        }

        val suppressed = FloatArray(src.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val (a, b) = when (direction[i].toInt()) {
                    0 -> i - 1 to i + 1
                    1 -> i - width + 1 to i + width - 1
                    2 -> i - width to i + width
                    else -> i - width - 1 to i + width + 1
                }
                val m = magnitude[i]
                if (m >= magnitude[a] && m >= magnitude[b]) suppressed[i] = m
            }
        }

        val result = BooleanArray(src.size)
        val weak = BooleanArray(src.size)
        val queue = ArrayDeque<Int>()
        for (i in suppressed.indices) {
            when {
                suppressed[i] >= config.highThreshold -> {
                    result[i] = true
                    queue.add(i)
                }
                suppressed[i] >= config.lowThreshold -> weak[i] = true
            }
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % width
            val y = i / width
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val ni = ny * width + nx
                if (weak[ni] && !result[ni]) {
                    result[ni] = true
                    queue.add(ni)
                }
            }
        }
        return result
    }

    private fun gaussianBlur(src: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius == 0) return src.copyOf()
        val kernel = when (radius) {
            1 -> intArrayOf(1, 2, 1)
            2 -> intArrayOf(1, 4, 6, 4, 1)
            else -> IntArray(radius * 2 + 1) { index -> binomial(radius * 2, index) }
        }
        val sum = kernel.sum()
        val horizontal = IntArray(src.size)
        val output = IntArray(src.size)
        for (y in 0 until height) for (x in 0 until width) {
            var value = 0
            for (k in -radius..radius) value += src[y * width + (x + k).coerceIn(0, width - 1)] * kernel[k + radius]
            horizontal[y * width + x] = value / sum
        }
        for (y in 0 until height) for (x in 0 until width) {
            var value = 0
            for (k in -radius..radius) value += horizontal[(y + k).coerceIn(0, height - 1) * width + x] * kernel[k + radius]
            output[y * width + x] = value / sum
        }
        return output
    }

    private fun binomial(n: Int, k: Int): Int {
        var value = 1L
        for (i in 1..k) value = value * (n - i + 1) / i
        return value.toInt()
    }

    private fun close(src: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius == 0) return src.copyOf()
        return erode(dilate(src, width, height, radius), width, height, radius)
    }

    private fun dilate(src: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until height) for (x in 0 until width) {
            loop@ for (dy in -radius..radius) for (dx in -radius..radius) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until width && ny in 0 until height && src[ny * width + nx]) {
                    out[y * width + x] = true
                    break@loop
                }
            }
        }
        return out
    }

    private fun erode(src: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until height) for (x in 0 until width) {
            var keep = true
            loop@ for (dy in -radius..radius) for (dx in -radius..radius) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height || !src[ny * width + nx]) {
                    keep = false
                    break@loop
                }
            }
            out[y * width + x] = keep
        }
        return out
    }

    private fun largestComponent(src: BooleanArray, width: Int, height: Int): BooleanArray {
        val visited = BooleanArray(src.size)
        var best = IntArray(0)
        val minimum = (src.size * config.minimumComponentRatio).toInt().coerceAtLeast(1)
        for (start in src.indices) {
            if (!src[start] || visited[start]) continue
            val component = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            visited[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                component.add(i)
                val x = i % width
                val y = i / width
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val ni = ny * width + nx
                    if (src[ni] && !visited[ni]) {
                        visited[ni] = true
                        queue.add(ni)
                    }
                }
            }
            if (component.size >= minimum && component.size > best.size) best = component.toIntArray()
        }
        return BooleanArray(src.size).also { out -> best.forEach { out[it] = true } }
    }

    private fun fillInterior(boundary: BooleanArray, width: Int, height: Int): BooleanArray {
        if (boundary.none { it }) return BooleanArray(boundary.size)
        val outside = BooleanArray(boundary.size)
        val queue = ArrayDeque<Int>()
        fun seed(i: Int) {
            if (!boundary[i] && !outside[i]) {
                outside[i] = true
                queue.add(i)
            }
        }
        for (x in 0 until width) {
            seed(x)
            seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            seed(y * width + width - 1)
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % width
            val y = i / width
            val neighbours = intArrayOf(x - 1, y, x + 1, y, x, y - 1, x, y + 1)
            for (n in neighbours.indices step 2) {
                val nx = neighbours[n]
                val ny = neighbours[n + 1]
                if (nx !in 0 until width || ny !in 0 until height) continue
                val ni = ny * width + nx
                if (!boundary[ni] && !outside[ni]) {
                    outside[ni] = true
                    queue.add(ni)
                }
            }
        }
        return BooleanArray(boundary.size) { i -> boundary[i] || !outside[i] }
    }

    private fun largestThresholdComponent(src: IntArray, width: Int, height: Int): BooleanArray {
        val threshold = otsu(src)
        val dark = BooleanArray(src.size) { src[it] <= threshold }
        val light = BooleanArray(src.size) { src[it] > threshold }
        if (dark.none { it } || light.none { it }) return BooleanArray(src.size)
        val darkLargest = largestAreaComponent(dark, width, height)
        val lightLargest = largestAreaComponent(light, width, height)
        // Prefer a non-border component; otherwise select the smaller plausible foreground region.
        val darkScore = componentScore(darkLargest, width, height)
        val lightScore = componentScore(lightLargest, width, height)
        return if (darkScore >= lightScore) darkLargest else lightLargest
    }

    private fun largestAreaComponent(src: BooleanArray, width: Int, height: Int): BooleanArray {
        return largestComponent(src, width, height)
    }

    private fun componentScore(mask: BooleanArray, width: Int, height: Int): Int {
        var count = 0
        var border = 0
        mask.forEachIndexed { i, value ->
            if (!value) return@forEachIndexed
            count++
            val x = i % width
            val y = i / width
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border++
        }
        if (count == 0) return Int.MIN_VALUE
        // A closed foreground that does not touch the image border must beat its
        // usually much larger connected background. Area alone reverses that choice.
        val enclosedBonus = if (border == 0) mask.size else 0
        return enclosedBonus + count - border * 8 - abs(count - mask.size / 3) / 4
    }

    private fun otsu(src: IntArray): Int {
        val histogram = IntArray(256)
        src.forEach { histogram[it.coerceIn(0, 255)]++ }
        val total = src.size
        var sum = 0L
        for (i in histogram.indices) sum += i.toLong() * histogram[i]
        var backgroundWeight = 0
        var backgroundSum = 0L
        var bestVariance = -1.0
        var best = 127
        for (threshold in 0..254) {
            backgroundWeight += histogram[threshold]
            if (backgroundWeight == 0) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) break
            backgroundSum += threshold.toLong() * histogram[threshold]
            val backgroundMean = backgroundSum.toDouble() / backgroundWeight
            val foregroundMean = (sum - backgroundSum).toDouble() / foregroundWeight
            val variance = backgroundWeight.toDouble() * foregroundWeight * (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (variance > bestVariance) {
                bestVariance = variance
                best = threshold
            }
        }
        return best
    }

    private fun reduceToTouchMap(subject: BooleanArray, boundary: BooleanArray, width: Int, height: Int, timestampMs: Long): TactileMap {
        val cells = ByteArray(TactileMap.WIDTH * TactileMap.HEIGHT)
        val eligible = BooleanArray(cells.size)
        val content = TouchMapper.centerFit(TactileMap.WIDTH, TactileMap.HEIGHT, width, height)
        for (gy in 0 until TactileMap.HEIGHT) for (gx in 0 until TactileMap.WIDTH) {
            val index = gy * TactileMap.WIDTH + gx
            val point = TouchMapper.map(gx + 0.5f, gy + 0.5f, content, width, height) ?: continue
            eligible[index] = true
            if (subject[point.y * width + point.x]) cells[index] = TactileCell.SUBJECT.code
        }
        val reducedBoundary = BooleanArray(cells.size)
        for (gy in 0 until TactileMap.HEIGHT) for (gx in 0 until TactileMap.WIDTH) {
            val index = gy * TactileMap.WIDTH + gx
            if (!eligible[index]) continue
            val x0 = floor((gx - content.left) * width / content.width).toInt().coerceIn(0, width - 1)
            val x1 = ceil((gx + 1 - content.left) * width / content.width).toInt().coerceIn(x0 + 1, width)
            val y0 = floor((gy - content.top) * height / content.height).toInt().coerceIn(0, height - 1)
            val y1 = ceil((gy + 1 - content.top) * height / content.height).toInt().coerceIn(y0 + 1, height)
            var found = false
            loop@ for (y in y0 until y1) for (x in x0 until x1) if (boundary[y * width + x]) {
                found = true
                break@loop
            }
            reducedBoundary[index] = found
        }
        val thickBoundary = dilate(reducedBoundary, TactileMap.WIDTH, TactileMap.HEIGHT, config.tactileBoundaryRadius)
        thickBoundary.forEachIndexed { index, value -> if (value && eligible[index]) cells[index] = TactileCell.BOUNDARY.code }

        return TactileMap(versions.incrementAndGet(), timestampMs, cells = cells)
    }

    private fun resize(src: ByteArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): IntArray {
        val out = IntArray(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val sy = (y * srcHeight / dstHeight).coerceAtMost(srcHeight - 1)
            for (x in 0 until dstWidth) {
                val sx = (x * srcWidth / dstWidth).coerceAtMost(srcWidth - 1)
                out[y * dstWidth + x] = src[sy * srcWidth + sx].toInt() and 0xff
            }
        }
        return out
    }
}
