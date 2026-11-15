package app.pub

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the installed saved tuner without replacing its source, storage, or persistent access rules. */
@RunWith(AndroidJUnit4::class)
class TunerStartRegressionTest {
    @Test fun savedTunerStartKeepsListeningAndReceivingSamples() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue("The real microphone test requires the existing Android grant",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var host: HostModel
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                host = ViewModelProvider(activity)[HostModel::class.java]
                host.openProject(host.editableProject("guitar-tuner"))
            }
            fun waitFor(label: String, condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var passed = false
                    scenario.onActivity { passed = condition() }
                    if (passed) return
                    Thread.sleep(80)
                }
                var state = ""
                scenario.onActivity { state = "listening=${host.listening}, rate=${host.sampleRate}, status=${host.microphoneMessage}, failure=${host.failure}, ask=${host.askPermission}, Android=${host.askAndroidPermission}" }
                fail("$label: $state")
            }
            try {
                waitFor("Saved tuner did not render") { host.ui != null || host.failure != null }
                scenario.onActivity { assertNull(host.failure); host.action("start") }
                waitFor("Start did not request access or begin capture") { host.askPermission || host.askAndroidPermission || host.listening || host.failure != null }
                scenario.onActivity {
                    assertNull(host.failure)
                    assertFalse("Android access was already granted", host.askAndroidPermission)
                    if (host.askPermission) host.allowSession()
                }
                waitFor("Saved tuner did not receive microphone samples") { host.listening && host.sampleRate > 0 }
                var initialFrames = 0L
                scenario.onActivity { initialFrames = host.pitchFrames }
                val end = SystemClock.elapsedRealtime() + 2_000
                while (SystemClock.elapsedRealtime() < end) {
                    scenario.onActivity {
                        assertTrue("Start must not immediately trigger microphone.stop", host.listening)
                        assertTrue(host.sampleRate > 0)
                        assertNull(host.failure)
                    }
                    Thread.sleep(80)
                }
                scenario.onActivity { assertTrue("New samples must continue arriving", host.pitchFrames > initialFrames) }
            } finally {
                scenario.onActivity { host.stop(); host.closeProject() }
            }
        }
    }
}
