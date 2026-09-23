package com.insta360.kmpsdk.demo.touchscene

import java.util.concurrent.CancellationException

/** Always attempt every preview release step, even if an SDK call or logger fails. */
internal fun runPreviewCleanup(
    onFailure: (String, Throwable) -> Unit,
    vararg steps: Pair<String, () -> Unit>,
) {
    for ((name, action) in steps) {
        runCatching(action).onFailure { error ->
            runCatching { onFailure(name, error) }
        }
    }
}

/** A failed mode/lens switch is reported once; coroutine cancellation is never swallowed. */
internal suspend fun runPreviewSdkSwitch(
    switch: suspend () -> Boolean,
    onFailure: (Throwable?) -> Unit,
): Boolean {
    val result = try {
        switch()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        runCatching { onFailure(error) }
        return false
    }
    if (!result) runCatching { onFailure(null) }
    return result
}
