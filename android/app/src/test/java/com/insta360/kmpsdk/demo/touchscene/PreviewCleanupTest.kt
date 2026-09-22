package com.insta360.kmpsdk.demo.touchscene

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class PreviewCleanupTest {
    @Test
    fun `failed SDK and logging calls do not skip later release steps`() {
        val attempted = mutableListOf<String>()
        val failed = mutableListOf<String>()

        runPreviewCleanup(
            { name, _ ->
                failed += name
                if (name == "listener") error("logger failure")
            },
            "listener" to { attempted += "listener"; error("unregister failed") },
            "posture" to { attempted += "posture" },
            "pipeline" to { attempted += "pipeline"; error("unbind failed") },
            "stream" to { attempted += "stream" },
        )

        assertEquals(listOf("listener", "posture", "pipeline", "stream"), attempted)
        assertEquals(listOf("listener", "pipeline"), failed)
    }

    @Test
    fun `failed SDK switch reports once and returns failure for recovery`() = runBlocking {
        val failures = mutableListOf<Throwable?>()
        val rejected = runPreviewSdkSwitch({ false }, failures::add)
        assertEquals(false, rejected)
        assertEquals(listOf(null), failures)

        val thrown = IllegalStateException("camera failed")
        val failed = runPreviewSdkSwitch({ throw thrown }, failures::add)
        assertEquals(false, failed)
        assertEquals(listOf(null, thrown), failures)
    }

    @Test
    fun `SDK switch cancellation propagates without false failure report`() {
        val failures = mutableListOf<Throwable?>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runPreviewSdkSwitch({ throw CancellationException("leaving page") }, failures::add)
            }
        }
        assertEquals(emptyList<Throwable?>(), failures)
    }
}
