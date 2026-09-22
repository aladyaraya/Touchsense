package com.insta360.kmpsdk.demo.touchscene

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android View-level checks; still not a physical touch or vibrator acceptance test. */
@RunWith(AndroidJUnit4::class)
class TactileMapViewInstrumentedTest {
    @Test
    fun hundredViewTouchesAnnounceMappedCellsAndLetterboxDoesNotRepeatOldCell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val view = TactileMapView(context)
            view.layout(0, 0, 1_000, 1_000)
            view.submitMap(TactileMap(1L, 1L, cells = ByteArray(64 * 48)))
            view.explorationEnabled = true
            val background = context.getString(R.string.touchscene_cell_background)
            var checked = 0
            for (row in 0 until 10) for (column in 0 until 10) {
                val x = (column + 0.5f) * 100f
                val y = 125f + (row + 0.5f) * 75f
                tap(view, x, y)
                val gridX = ((column + 0.5f) * 64f / 10f).toInt()
                val gridY = ((row + 0.5f) * 48f / 10f).toInt()
                assertEquals(
                    context.getString(R.string.touchscene_cell_announcement, gridX + 1, gridY + 1, background),
                    view.contentDescription,
                )
                checked++
            }
            assertEquals(100, checked)
            tap(view, 500f, 100f)
            assertEquals(context.getString(R.string.touchscene_map_content_description), view.contentDescription)
        }
    }

    @Test
    fun directionControlStopsAtBoundaryAndResetsDescriptionOnRefresh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val cells = ByteArray(64 * 48)
            cells[24 * 64 + 31] = TactileCell.BOUNDARY.code
            val map = TactileMap(1L, 1L, cells = cells)
            val view = TactileMapView(context)
            assertFalse(view.moveAccessibilityCursor(-1, 0))
            view.submitMap(map)
            view.explorationEnabled = true
            assertTrue(view.moveAccessibilityCursor(-1, 0))
            assertEquals(
                context.getString(
                    R.string.touchscene_cell_announcement,
                    32,
                    25,
                    context.getString(R.string.touchscene_cell_boundary),
                ),
                view.contentDescription,
            )
            view.submitMap(map.withVersion(2L))
            assertEquals(context.getString(R.string.touchscene_map_content_description), view.contentDescription)
        }
    }

    @Test
    fun mapReplacementWaitsUntilFingerLifts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val view = TactileMapView(context)
            view.layout(0, 0, 640, 480)
            view.submitMap(TactileMap(1L, 1L, cells = ByteArray(64 * 48)))
            view.explorationEnabled = true
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 320f, 240f, 0)
            val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, 320f, 240f, 0)
            try {
                assertTrue(view.onTouchEvent(down))
                assertTrue(view.isFingerExploring)
                view.submitMap(TactileMap(2L, 2L, cells = ByteArray(64 * 48) { TactileCell.SUBJECT.code }))
                assertEquals(1L, view.displayedMapVersion)
                assertTrue(view.onTouchEvent(up))
                assertFalse(view.isFingerExploring)
                assertEquals(2L, view.displayedMapVersion)
                assertEquals(context.getString(R.string.touchscene_map_content_description), view.contentDescription)
                tap(view, 320f, 240f)
                assertEquals(
                    context.getString(R.string.touchscene_cell_announcement, 33, 25, context.getString(R.string.touchscene_cell_subject)),
                    view.contentDescription,
                )
            } finally {
                down.recycle()
                up.recycle()
            }
        }
    }

    @Test
    fun secondFingerStopsSamplingAndMapWaitsForLastFinger() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val view = TactileMapView(context)
            view.layout(0, 0, 640, 480)
            view.submitMap(TactileMap(1L, 1L, cells = ByteArray(64 * 48)))
            view.explorationEnabled = true
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
            val secondDown = twoPointerEvent(now, now + 1, MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            val firstUp = twoPointerEvent(now, now + 2, MotionEvent.ACTION_POINTER_UP)
            val remainingMove = remainingPointerEvent(now, now + 3, MotionEvent.ACTION_MOVE)
            val lastUp = remainingPointerEvent(now, now + 4, MotionEvent.ACTION_UP)
            try {
                assertTrue(view.onTouchEvent(down))
                view.submitMap(TactileMap(2L, 2L, cells = ByteArray(64 * 48)))
                assertTrue(view.onTouchEvent(secondDown))
                assertTrue(view.onTouchEvent(firstUp))
                assertTrue(view.onTouchEvent(remainingMove))
                assertTrue(view.isFingerExploring)
                assertEquals(1L, view.displayedMapVersion)
                assertTrue(view.onTouchEvent(lastUp))
                assertFalse(view.isFingerExploring)
                assertEquals(2L, view.displayedMapVersion)
                assertEquals(context.getString(R.string.touchscene_map_content_description), view.contentDescription)
            } finally {
                listOf(down, secondDown, firstUp, remainingMove, lastUp).forEach(MotionEvent::recycle)
            }
        }
    }

    private fun twoPointerEvent(downTime: Long, eventTime: Long, action: Int): MotionEvent {
        val properties = Array(2) { index ->
            MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply { x = 100f; y = 100f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 500f; y = 300f; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(downTime, eventTime, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun remainingPointerEvent(downTime: Long, eventTime: Long, action: Int): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = 500f; y = 300f; pressure = 1f; size = 1f })
        return MotionEvent.obtain(downTime, eventTime, action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun tap(view: TactileMapView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x, y, 0)
        try {
            assertTrue(view.onTouchEvent(down))
            assertTrue(view.onTouchEvent(up))
        } finally {
            down.recycle()
            up.recycle()
        }
    }
}
