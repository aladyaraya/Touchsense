package com.insta360.kmpsdk.demo.touchscene

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

data class HapticPatternConfig(
    val fillOnMs: Long = 20L,
    val fillOffMs: Long = 160L,
    val edgeOnMs: Long = 150L,
    val keyPointOnMs: Long = 50L,
    val edgeCooldownMs: Long = 180L,
) {
    // The detailed MVP plan and supplied architecture diagram define a long
    // boundary pulse and a double keypoint pulse. The older route's boundary
    // double-tick suggestion is superseded for this build.
    internal fun edgeTimings(): LongArray = longArrayOf(0, edgeOnMs)
    internal fun keyPointTimings(): LongArray = longArrayOf(0, keyPointOnMs, keyPointOnMs, keyPointOnMs)
}

internal enum class HapticAction { CANCEL, FILL, EDGE, KEY_POINT }

internal class HapticTransitionGate(private val edgeCooldownMs: Long) {
    private var previousPoint: GridPoint? = null
    private var previousCell = TactileCell.BACKGROUND
    private var edgeCooldownUntil = 0L

    fun transition(point: GridPoint?, cell: TactileCell, nowMs: Long): HapticAction? {
        if (point == previousPoint && cell == previousCell) return null
        previousPoint = point
        if (cell == previousCell) return null
        previousCell = cell
        return when (cell) {
            TactileCell.BACKGROUND -> HapticAction.CANCEL
            TactileCell.SUBJECT -> HapticAction.FILL
            TactileCell.KEY_POINT -> HapticAction.KEY_POINT
            TactileCell.BOUNDARY -> {
                // A suppressed edge tick must still stop a repeating fill from the prior cell.
                if (nowMs < edgeCooldownUntil) HapticAction.CANCEL else {
                    edgeCooldownUntil = nowMs + edgeCooldownMs
                    HapticAction.EDGE
                }
            }
        }
    }

    fun reset() {
        previousPoint = null
        previousCell = TactileCell.BACKGROUND
        edgeCooldownUntil = 0L
    }
}

/** Rhythm-first renderer. Amplitude is intentionally not used as a required semantic channel. */
class AndroidHapticRenderer(
    context: Context,
    private val config: HapticPatternConfig = HapticPatternConfig(),
) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private var enabled = true
    private val transitionGate = HapticTransitionGate(config.edgeCooldownMs)

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) cancel()
    }

    fun render(point: GridPoint?, cell: TactileCell, nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!enabled || !vibrator.hasVibrator()) return
        when (transitionGate.transition(point, cell, nowMs)) {
            null -> Unit
            HapticAction.CANCEL -> vibrator.cancel()
            HapticAction.FILL -> playFill()
            HapticAction.EDGE ->
                playWaveform(config.edgeTimings(), -1)
            HapticAction.KEY_POINT ->
                playWaveform(config.keyPointTimings(), -1)
        }
    }

    /** Button-driven exploration must never leave the repeating fill running after a tap. */
    fun renderDiscrete(cell: TactileCell) {
        cancel()
        when (cell) {
            TactileCell.BACKGROUND -> Unit
            TactileCell.SUBJECT -> playWaveform(longArrayOf(0, config.fillOnMs), -1)
            TactileCell.BOUNDARY ->
                playWaveform(config.edgeTimings(), -1)
            TactileCell.KEY_POINT ->
                playWaveform(config.keyPointTimings(), -1)
        }
    }

    fun playReady() = playWaveform(longArrayOf(0, 40, 60, 90), -1)

    fun playSuccess() = playWaveform(longArrayOf(0, 55, 45, 55), -1)

    fun playError() = playWaveform(longArrayOf(0, 180, 80, 180), -1)

    fun selfTest() {
        if (!enabled) return
        playWaveform(longArrayOf(0, config.fillOnMs, config.fillOffMs, config.edgeOnMs, 180,
            config.keyPointOnMs, config.keyPointOnMs, config.keyPointOnMs), -1)
    }

    fun cancel() {
        vibrator.cancel()
        transitionGate.reset()
    }

    private fun playFill() {
        playWaveform(longArrayOf(0, config.fillOnMs, config.fillOffMs), 1)
    }

    private fun playWaveform(timings: LongArray, repeat: Int) {
        if (!enabled || !vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(timings, repeat))
    }
}
