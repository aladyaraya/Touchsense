package com.insta360.kmpsdk.demo.touchscene

/** The four values used by the stage architecture and the Android explorer. */
enum class TactileCell(val code: Byte) {
    BACKGROUND(0),
    SUBJECT(1),
    BOUNDARY(2),
    KEY_POINT(3),
    ;

    companion object {
        fun fromCode(code: Byte): TactileCell = entries.firstOrNull { it.code == code } ?: BACKGROUND
    }
}

/**
 * Immutable tactile snapshot. The backing array is copied on input and never exposed directly.
 */
class TactileMap(
    val version: Long,
    val sourceTimestampMs: Long,
    val width: Int = WIDTH,
    val height: Int = HEIGHT,
    cells: ByteArray,
) {
    private val data = cells.copyOf()

    init {
        require(width > 0 && height > 0)
        require(data.size == width * height) {
            "Expected ${width * height} cells, got ${data.size}"
        }
    }

    fun cellAt(x: Int, y: Int): TactileCell {
        if (x !in 0 until width || y !in 0 until height) return TactileCell.BACKGROUND
        return TactileCell.fromCode(data[y * width + x])
    }

    fun copyCells(): ByteArray = data.copyOf()

    fun withVersion(newVersion: Long): TactileMap =
        TactileMap(newVersion, sourceTimestampMs, width, height, data)

    companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
    }
}

/** A luminance-only analysis frame copied out of ImageReader ownership. */
data class GrayFrame(
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val luminance: ByteArray,
) {
    init {
        require(width > 0 && height > 0)
        require(luminance.size == width * height)
    }
}

data class ContentRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class GridPoint(val x: Int, val y: Int)

object TouchMapper {
    fun map(
        touchX: Float,
        touchY: Float,
        content: ContentRect,
        gridWidth: Int = TactileMap.WIDTH,
        gridHeight: Int = TactileMap.HEIGHT,
    ): GridPoint? {
        if (content.width <= 0f || content.height <= 0f) return null
        if (touchX < content.left || touchX >= content.right || touchY < content.top || touchY >= content.bottom) {
            return null
        }
        val u = ((touchX - content.left) / content.width).coerceIn(0f, 0.999999f)
        val v = ((touchY - content.top) / content.height).coerceIn(0f, 0.999999f)
        return GridPoint((u * gridWidth).toInt(), (v * gridHeight).toInt())
    }

    /** Center-fit content rectangle used by both drawing and hit testing. */
    fun centerFit(viewWidth: Int, viewHeight: Int, contentWidth: Int, contentHeight: Int): ContentRect {
        if (viewWidth <= 0 || viewHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) {
            return ContentRect(0f, 0f, 0f, 0f)
        }
        val scale = minOf(viewWidth.toFloat() / contentWidth, viewHeight.toFloat() / contentHeight)
        val width = contentWidth * scale
        val height = contentHeight * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f
        return ContentRect(left, top, left + width, top + height)
    }
}
