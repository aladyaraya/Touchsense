package com.insta360.kmpsdk.demo.touchscene

enum class VoiceCaptureDispatchResult {
    SWITCH_FAILED,
    NOT_READY,
    DISPATCHED,
}

/** A failed or incomplete mode change must never fall through to capture. */
internal suspend fun dispatchVoiceCaptureAfterModeChange(
    switchModeIfNeeded: suspend () -> Boolean,
    isReady: () -> Boolean,
    capture: () -> Unit,
): VoiceCaptureDispatchResult {
    if (!switchModeIfNeeded()) return VoiceCaptureDispatchResult.SWITCH_FAILED
    if (!isReady()) return VoiceCaptureDispatchResult.NOT_READY
    capture()
    return VoiceCaptureDispatchResult.DISPATCHED
}
