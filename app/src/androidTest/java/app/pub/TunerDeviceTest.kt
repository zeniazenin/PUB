package app.pub

import android.Manifest
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import app.pub.core.PermissionPolicy.Rule as PermissionRule

@RunWith(AndroidJUnit4::class)
class TunerDeviceTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test fun nativeScreenRendersAndRealMicrophoneStopsOnRevocationAndBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: HostModel
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model = ViewModelProvider(it)[HostModel::class.java]
                assertFalse(model.projectOpen)
                assertNull(model.isolatedUid)
                model.openProject(null)
            }
            fun waitUntil(label: String, condition: () -> Boolean) {
                val deadline = System.currentTimeMillis() + 15000
                while (System.currentTimeMillis() < deadline) {
                    var passed = false
                    scenario.onActivity { passed = condition() }
                    if (passed) return
                    Thread.sleep(100)
                }
                var detail = ""
                scenario.onActivity { detail = "status=${model.microphoneMessage}; runtime=${model.failure}; listening=${model.listening}; sampleRate=${model.sampleRate}; ask=${model.askPermission}; askAndroid=${model.askAndroidPermission}" }
                fail("Timed out: $label ($detail)")
            }
            waitUntil("isolated script rendered native UI") { model.ui != null || model.failure != null }
            scenario.onActivity {
                assertNull(model.failure)
                assertNotNull(model.isolatedUid)
                assertTrue(model.ui.toString().contains("GUITAR TUNER"))
                model.blockGlobally(false)
                model.changeAppRule(PermissionRule.ALLOW)
                model.action("start")
            }
            try {
                waitUntil("real microphone samples") { model.listening && model.sampleRate > 0 }
                var framesAtRevocation = 0L
                scenario.onActivity { model.blockGlobally(true); assertFalse(model.listening); framesAtRevocation = model.pitchFrames }
                Thread.sleep(350)
                scenario.onActivity {
                    assertFalse(model.listening)
                    assertEquals("Queued frames must not survive revocation", framesAtRevocation, model.pitchFrames)
                    model.blockGlobally(false); model.action("start")
                }
                waitUntil("microphone restart") { model.listening && model.pitchFrames > framesAtRevocation }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                assertFalse(model.listening)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { assertFalse("Must not auto-resume capture", model.listening) }
                scenario.onActivity {
                    model.changeGlobalDefault(PermissionRule.ASK)
                    model.changeAppRule(PermissionRule.ASK)
                    model.action("start")
                }
                waitUntil("session permission prompt") { model.askPermission }
                scenario.onActivity { model.allowSession() }
                waitUntil("session microphone capture") { model.listening && model.sampleRate > 0 }
                var framesAtClose = 0L
                scenario.onActivity {
                    model.closeProject()
                    framesAtClose = model.pitchFrames
                    assertFalse(model.projectOpen);assertFalse(model.listening)
                    assertNull(model.ui);assertNull(model.isolatedUid)
                }
                Thread.sleep(350)
                scenario.onActivity {
                    assertEquals("Closing must invalidate queued frames",framesAtClose,model.pitchFrames)
                    model.openProject(null)
                }
                waitUntil("reopened tuner") { model.ui != null }
                scenario.onActivity { model.action("start") }
                waitUntil("closed session grant must not survive reopen") { model.askPermission }
                scenario.onActivity { assertFalse(model.listening);model.deny();model.closeProject() }
            } finally {
                scenario.onActivity {
                    model.stop()
                    model.blockGlobally(false)
                    model.changeProjectRule("guitar-tuner",PermissionRule.INHERIT)
                    model.changeGlobalDefault(PermissionRule.ASK)
                }
            }
        }
    }
}
