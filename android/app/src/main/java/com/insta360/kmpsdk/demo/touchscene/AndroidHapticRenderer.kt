package com.insta360.kmpsdk.demo.touchscene

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Minimal binary haptics.
 * value == 1 → sustained low-duty loop; value == 0 → silence.
 */
class AndroidHapticRenderer(context: Context) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private var enabled = true
    private var activeValue = -1

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) cancel()
    }

    fun render(value: Int) {
        if (!enabled || !vibrator.hasVibrator()) return
        if (value == activeValue) return
        activeValue = value
        if (value == 1) startSustained() else vibrator.cancel()
    }

    fun renderDiscrete(value: Int) = render(value)

    fun playSuccess() = playWaveform(longArrayOf(0, 55, 45, 55), repeat = -1)

    fun playError() = playWaveform(longArrayOf(0, 180, 80, 180), repeat = -1)

    fun selfTest() {
        if (!enabled) return
        playWaveform(longArrayOf(0, 150, 180, 150), repeat = -1)
    }

    fun cancel() {
        vibrator.cancel()
        activeValue = -1
    }

    private fun startSustained() {
        vibrator.cancel()
        vibrator.vibrate(VibrationEffect.createWaveform(SUSTAINED_TIMINGS, SUSTAINED_AMPLITUDES, 0))
    }

    private fun playWaveform(timings: LongArray, repeat: Int) {
        if (!enabled || !vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(timings, repeat))
    }

    companion object {
        // 原来 20ms 开 / 160ms 关的占空比过低；改为更明显的满幅规律脉冲。
        internal val SUSTAINED_TIMINGS = longArrayOf(0L, 65L, 65L)
        internal val SUSTAINED_AMPLITUDES = intArrayOf(0, 255, 0)
    }
}
