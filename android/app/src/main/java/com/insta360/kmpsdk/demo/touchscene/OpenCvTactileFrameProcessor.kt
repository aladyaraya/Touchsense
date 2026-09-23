package com.insta360.kmpsdk.demo.touchscene

import com.insta360.kmpsdk.demo.raw.Yuv420Frame
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * OpenCV 版的单帧处理器，替换原先两种零依赖算法：
 * - EDGE 图层（原 Canny）→ [OpenCvImageProcessor.processEdge]：双边滤波 + CLAHE + 自适应阈值 + 轮廓描边。
 * - BINARY 图层（原亮度二值）→ [OpenCvImageProcessor.processOutline]：GrabCut 前景分割 + HSV 阴影过滤 + 最大连通域填充。
 *
 * 触觉图（三态）由新算法输出驱动：SUBJECT = 轮廓填充，BOUNDARY = 轮廓边界（加粗）。
 *
 * 整个 App 的图像处理是「冻结单帧后跑一次」，因此 GrabCut 的开销可以接受（与手机相机版一致）。
 * 若 OpenCV 原生库加载失败，则回退到纯 Kotlin 的 [DefaultTactileFrameProcessor]，避免崩溃。
 */
class OpenCvTactileFrameProcessor(
    private val openCvLongEdge: Int = 512,
    private val boundaryRadius: Int = 2,
) : TactileFrameProcessor {

    private val versions = AtomicLong(0L)
    private val fallback = DefaultTactileFrameProcessor()

    override fun process(source: Yuv420Frame): TactileFrameResult =
        runCatching { processWithOpenCv(source) }
            .getOrElse { error ->
                Timber.w(error, "OpenCV tactile processing failed; falling back to Canny")
                fallback.process(source)
            }

    private fun processWithOpenCv(source: Yuv420Frame): TactileFrameResult {
        val scale = minOf(1.0, openCvLongEdge.toDouble() / max(source.width, source.height))
        val workWidth = max(1, (source.width * scale).roundToInt())
        val workHeight = max(1, (source.height * scale).roundToInt())
        val rgba = source.toRgbDebugBitmap(workWidth, workHeight)

        val edgeBitmap = OpenCvImageProcessor.processEdge(rgba)
        val outlineBitmap = OpenCvImageProcessor.processOutline(rgba)

        val width = outlineBitmap.width
        val height = outlineBitmap.height
        val size = width * height

        val sourcePixels = IntArray(size)
        val edgePixels = IntArray(size)
        val outlinePixels = IntArray(size)
        rgba.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        edgeBitmap.getPixels(edgePixels, 0, edgeBitmap.width, 0, 0, edgeBitmap.width, edgeBitmap.height)
        outlineBitmap.getPixels(outlinePixels, 0, width, 0, 0, width, height)

        val gray = IntArray(size)
        val cannyEdges = BooleanArray(size)
        val subject = BooleanArray(size)
        for (i in 0 until size) {
            gray[i] = luminance(sourcePixels[i])
            cannyEdges[i] = luminance(edgePixels[i]) > 127
            subject[i] = luminance(outlinePixels[i]) > 127
        }

        val boundary = subjectBoundary(subject, width, height)
        val map = reduceToTouchMap(subject, boundary, width, height, source.receivedAtElapsedRealtimeMs)
        val result = TactileProcessingResult(
            width = width,
            height = height,
            gray = gray,
            blurred = gray.copyOf(),
            cannyEdges = cannyEdges,
            closedEdges = cannyEdges.copyOf(),
            subject = subject,
            boundary = boundary,
            usedThresholdFallback = false,
            map = map,
        )
        val photoBinary = reduceToPhotoBinary(outlinePixels, width, height)

        edgeBitmap.recycle()
        outlineBitmap.recycle()
        rgba.recycle()
        return TactileFrameResult(result, photoBinary)
    }

    private fun subjectBoundary(subject: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(subject.size) { index ->
            if (!subject[index]) {
                false
            } else {
                val x = index % width
                val y = index / width
                x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                    !subject[index - 1] || !subject[index + 1] ||
                    !subject[index - width] || !subject[index + width]
            }
        }

    private fun dilate(src: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius == 0) return src.copyOf()
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

    /** Mirror of CannyTactileProcessor.reduceToTouchMap: 64x48 center-fit, SUBJECT fill + thickened BOUNDARY. */
    private fun reduceToTouchMap(
        subject: BooleanArray,
        boundary: BooleanArray,
        width: Int,
        height: Int,
        timestampMs: Long,
    ): TactileMap {
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
        val thickBoundary = dilate(reducedBoundary, TactileMap.WIDTH, TactileMap.HEIGHT, boundaryRadius)
        thickBoundary.forEachIndexed { index, value ->
            if (value && eligible[index]) cells[index] = TactileCell.BOUNDARY.code
        }
        return TactileMap(versions.incrementAndGet(), timestampMs, cells = cells)
    }

    /** Reduce the outline silhouette to the short-edge-64 binary grid (same convention as PhotoBinaryProcessor). */
    private fun reduceToPhotoBinary(outlinePixels: IntArray, width: Int, height: Int): PhotoBinaryLayer {
        val gridWidth =
            if (width >= height) PhotoBinaryProcessor.SHORT_EDGE
            else max(1, (PhotoBinaryProcessor.SHORT_EDGE.toFloat() * width / height).roundToInt())
        val gridHeight =
            if (height >= width) PhotoBinaryProcessor.SHORT_EDGE
            else max(1, (PhotoBinaryProcessor.SHORT_EDGE.toFloat() * height / width).roundToInt())
        val cells = ByteArray(gridWidth * gridHeight)
        for (gy in 0 until gridHeight) {
            val y0 = gy * height / gridHeight
            val y1 = max(y0 + 1, (gy + 1) * height / gridHeight)
            for (gx in 0 until gridWidth) {
                val x0 = gx * width / gridWidth
                val x1 = max(x0 + 1, (gx + 1) * width / gridWidth)
                var sum = 0L
                var count = 0
                for (sy in y0 until y1) {
                    val row = sy * width
                    for (sx in x0 until x1) {
                        sum += luminance(outlinePixels[row + sx])
                        count++
                    }
                }
                cells[gy * gridWidth + gx] =
                    if (sum / count >= PhotoBinaryProcessor.THRESHOLD) 1.toByte() else 0.toByte()
            }
        }
        return PhotoBinaryLayer(gridWidth, gridHeight, cells)
    }

    private fun luminance(argb: Int): Int =
        ((argb shr 16 and 0xff) * 299 + (argb shr 8 and 0xff) * 587 + (argb and 0xff) * 114) / 1000
}
