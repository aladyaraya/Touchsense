package com.insta360.kmpsdk.demo.touchscene

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation.fragment.NavHostFragment
import com.insta360.kmpsdk.demo.MainActivity
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.ui.capture.PreviewFragment
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the actual no-camera navigation and processing path on an emulator. */
@RunWith(AndroidJUnit4::class)
class LocalDemoFlowInstrumentedTest {
    @Test
    fun localDemoLoadsFreezesExploresAndDescribesWithoutCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val traceDirectory = context.getExternalFilesDir("touchscene-traces")
            ?: File(context.filesDir, "touchscene-traces")
        val existingTraces = traceDirectory.listFiles()?.map { it.name }?.toSet().orEmpty()
        val packageName = context.packageName
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += listOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(packageName, it) }

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            onMain {
                assertTrue(activity.findViewById<Button>(R.id.entry_touchscene_local_demo).performClick())
            }
            waitFor(15_000) {
                onMain { activity.findViewById<TactileMapView>(R.id.tactile_map_view)?.displayedMapVersion != null }
            }
            onMain {
                assertTrue(
                    activity.findViewById<TextView>(R.id.raw_frame_status).text.toString() ==
                        context.getString(R.string.touchscene_local_demo_active),
                )
                assertTrue(!activity.findViewById<Button>(R.id.capture_btn).isEnabled)
            }
            onMain {
                assertTrue(activity.findViewById<Button>(R.id.freeze_map_btn).performClick())
            }
            waitFor(5_000) {
                onMain {
                    activity.findViewById<TextView>(R.id.touchscene_status)?.text?.toString() ==
                        context.getString(R.string.touchscene_exploring_map, 1L)
                }
            }
            val genericMapDescription = context.getString(R.string.touchscene_map_content_description)
            onMain {
                assertTrue(activity.findViewById<Button>(R.id.map_move_left_btn).performClick())
            }
            val sampleDescription = onMain {
                activity.findViewById<TactileMapView>(R.id.tactile_map_view).contentDescription.toString()
            }
            assertNotEquals(genericMapDescription, sampleDescription)
            assertTrue(sampleDescription.isNotBlank())

            onMain {
                assertTrue(activity.findViewById<Button>(R.id.describe_scene_btn).performClick())
            }
            waitFor(8_000) {
                onMain {
                    val status = activity.findViewById<TextView>(R.id.touchscene_status)?.text?.toString()
                    status != null && status != context.getString(R.string.touchscene_exploring_map, 1L) &&
                        status != context.getString(R.string.touchscene_no_frame)
                }
            }
            val description = onMain { activity.findViewById<TextView>(R.id.touchscene_status)?.text?.toString() }
            assertNotNull(description)
            assertTrue(description!!.isNotBlank())

            // Voice capture must obey the same no-camera boundary as the disabled button.
            onMain {
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                val preview = navHost.childFragmentManager.primaryNavigationFragment as PreviewFragment
                val dispatch = PreviewFragment::class.java.getDeclaredMethod(
                    "dispatchVoiceCommand", VoiceCommand::class.java,
                ).apply { isAccessible = true }
                listOf(
                    VoiceCommand.TakePhoto,
                    VoiceCommand.StartRecording,
                    VoiceCommand.StopRecording,
                ).forEach { command ->
                    dispatch.invoke(preview, command)
                    assertTrue(
                        activity.findViewById<TextView>(R.id.touchscene_status).text.toString() ==
                            context.getString(R.string.touchscene_capture_requires_camera),
                    )
                    assertTrue(!activity.findViewById<Button>(R.id.capture_btn).isEnabled)
                }
            }
            onMain {
                assertTrue(!activity.findViewById<Button>(R.id.freeze_map_btn).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.return_live_btn).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.return_live_btn).performClick())
            }
            waitFor(5_000) {
                onMain {
                    activity.findViewById<Button>(R.id.freeze_map_btn)?.isEnabled == true &&
                        activity.findViewById<Button>(R.id.return_live_btn)?.isEnabled == false &&
                        activity.findViewById<Button>(R.id.refresh_map_btn)?.isEnabled == false &&
                        activity.findViewById<TactileMapView>(R.id.tactile_map_view)?.explorationEnabled == false
                }
            }
            onMain { assertTrue(activity.findViewById<Button>(R.id.freeze_map_btn).performClick()) }
            waitFor(5_000) {
                onMain { activity.findViewById<Button>(R.id.return_live_btn)?.isEnabled == true }
            }
        } finally {
            onMain { activity.finish() }
        }
        waitFor(10_000) {
            traceDirectory.listFiles()?.any {
                it.name !in existingTraces && it.name.endsWith(".jsonl") &&
                    runCatching { it.readText().contains("\"event\":\"map_window\"") }.getOrDefault(false)
            } == true
        }
        val newTrace = traceDirectory.listFiles()!!.first {
            it.name !in existingTraces && it.name.endsWith(".jsonl") &&
                it.readText().contains("\"event\":\"map_window\"")
        }.readText()
        assertTrue(newTrace.contains("\"stage\":\"performance\",\"event\":\"map_window\""))
        assertTrue(newTrace.contains("\"fallback\":\"local_demo\""))
        assertTrue(Regex("samples=[1-9][0-9]*").containsMatchIn(newTrace))
    }

    private fun <T> onMain(value: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = value() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for local demo state", condition())
    }
}
