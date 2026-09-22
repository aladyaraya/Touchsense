package com.insta360.kmpsdk.demo.touchscene

data class TactileSample(val point: GridPoint, val cell: TactileCell, val mapVersion: Long)

/** A coarse keyboard/screen-reader cursor that stops at the first semantic transition. */
class TactileAccessibilityNavigator(private val stride: Int = 4) {
    init { require(stride > 0) }

    private var map: TactileMap? = null
    private var point: GridPoint? = null

    fun bind(next: TactileMap?) {
        if (map?.version == next?.version) return
        map = next
        point = next?.let { GridPoint(it.width / 2, it.height / 2) }
    }

    fun select(next: GridPoint?): TactileSample? {
        val currentMap = map ?: return null
        if (next == null || next.x !in 0 until currentMap.width || next.y !in 0 until currentMap.height) return null
        point = next
        return current()
    }

    fun current(): TactileSample? {
        val currentMap = map ?: return null
        val cursor = point ?: return null
        return TactileSample(cursor, currentMap.cellAt(cursor.x, cursor.y), currentMap.version)
    }

    fun move(dx: Int, dy: Int): TactileSample? {
        require((dx == 0) != (dy == 0)) { "Move in exactly one axis" }
        require(dx in -1..1 && dy in -1..1)
        val currentMap = map ?: return null
        var cursor = point ?: GridPoint(currentMap.width / 2, currentMap.height / 2)
        val startingCell = currentMap.cellAt(cursor.x, cursor.y)
        repeat(stride) {
            val next = GridPoint(
                (cursor.x + dx).coerceIn(0, currentMap.width - 1),
                (cursor.y + dy).coerceIn(0, currentMap.height - 1),
            )
            if (next == cursor) return@repeat
            cursor = next
            if (currentMap.cellAt(cursor.x, cursor.y) != startingCell) {
                point = cursor
                return current()
            }
        }
        point = cursor
        return current()
    }
}
