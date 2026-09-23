package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TactileAccessibilityNavigatorTest {
    @Test
    fun `coarse move stops at first semantic transition instead of skipping edge`() {
        val cells = ByteArray(8 * 4)
        cells[2 * 8 + 3] = TactileCell.BOUNDARY.code
        cells[2 * 8 + 2] = TactileCell.SUBJECT.code
        cells[2 * 8 + 1] = TactileCell.SUBJECT.code
        val map = TactileMap(1L, 1L, 8, 4, cells)
        val navigator = TactileAccessibilityNavigator(stride = 4)
        navigator.bind(map)
        assertEquals(GridPoint(4, 2), navigator.current()?.point)
        assertEquals(TactileCell.BOUNDARY, navigator.move(-1, 0)?.cell)
        assertEquals(GridPoint(3, 2), navigator.current()?.point)
        assertEquals(TactileCell.SUBJECT, navigator.move(-1, 0)?.cell)
        assertEquals(GridPoint(2, 2), navigator.current()?.point)
        assertEquals(TactileCell.BACKGROUND, navigator.move(-1, 0)?.cell)
        assertEquals(GridPoint(0, 2), navigator.current()?.point)
    }

    @Test
    fun `cursor remains frozen for same version and resets only for refreshed map`() {
        val navigator = TactileAccessibilityNavigator()
        assertNull(navigator.move(0, 1))
        val first = TactileMap(1L, 1L, 8, 4, ByteArray(32))
        navigator.bind(first)
        navigator.move(1, 0)
        assertTrue(navigator.current()!!.point.x > 4)
        navigator.bind(first.withVersion(1L))
        assertTrue(navigator.current()!!.point.x > 4)
        navigator.bind(first.withVersion(2L))
        assertEquals(GridPoint(4, 2), navigator.current()?.point)
        assertNull(navigator.select(GridPoint(-1, 2)))
    }
}
