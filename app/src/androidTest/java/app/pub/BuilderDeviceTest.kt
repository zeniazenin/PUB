package app.pub

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.auth.SecureSession
import app.pub.core.ProjectBundle
import app.pub.core.PermissionPolicy.Rule
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BuilderDeviceTest {
    private fun bundle(): ProjectBundle {
        val o=JsonObject().apply {
            addProperty("name","Builder test counter");addProperty("description","Test fixture")
            add("capabilities",com.google.gson.JsonArray().apply { add("storage.project") })
            addProperty("source","var n=0;function dispatch(raw){var e=JSON.parse(raw);if(e.type==='init')n=e.storage.n||0;if(e.type==='action')n++;return JSON.stringify({ui:{type:'column',children:[{type:'text',text:'Count '+n},{type:'button',label:'Add',action:'add'}]},effects:e.type==='action'?[{type:'storage.save',data:{n:n}}]:[]});}")
        }
        return ProjectBundle.parse(o.toString())
    }
    @Test fun previewSaveReopenAndProjectPermissionsRemainSeparate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: HostModel
            scenario.onActivity {
                model=ViewModelProvider(it)[HostModel::class.java]
                assertFalse(model.projectOpen)
                assertNull(model.ui)
                assertNull(model.isolatedUid)
                model.openProject(null)
            }
            fun waitFor(check: ()->Boolean) {
                val end=System.currentTimeMillis()+15000
                while(System.currentTimeMillis()<end) {
                    var ok=false;scenario.onActivity { ok=check() };if(ok)return;Thread.sleep(100)
                }
                fail("Runtime did not reach expected state")
            }
            waitFor { model.ui!=null }
            scenario.onActivity { model.changeAppRule(Rule.DENY);model.previewProject(bundle()) }
            waitFor { model.ui!=null || model.failure!=null }
            scenario.onActivity {
                assertNull(model.failure);assertTrue(model.preview);assertEquals(Rule.INHERIT,model.appRule)
                val permission=it.packageManager.checkPermission(android.Manifest.permission.INTERNET,"app.pub")
                assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,permission)
                assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,it.checkPermission(android.Manifest.permission.INTERNET,-1,model.isolatedUid!!))
                model.action("add")
            }
            waitFor { model.ui.toString().contains("Count 1") }
            scenario.onActivity { model.savePreview() }
            waitFor { model.ui!=null && !model.preview }
            val saved=model.projects.first { it.id==model.projectId }
            try {
                scenario.onActivity { assertTrue(model.ui.toString().contains("Count 1"));model.openProject(null) }
                waitFor { model.ui!=null }
                scenario.onActivity { assertEquals(Rule.DENY,model.appRule);model.openProject(saved) }
                waitFor { model.ui!=null }
                scenario.onActivity { assertEquals(Rule.INHERIT,model.appRule);assertTrue(model.ui.toString().contains("Count 1")) }
            } finally {
                scenario.onActivity {
                    model.deleteProject(saved)
                    assertFalse(model.projectOpen)
                    assertNull(model.ui)
                    assertNull(model.isolatedUid)
                    model.changeProjectRule("guitar-tuner", Rule.INHERIT)
                }
            }
        }
    }
    @Test fun launcherStaysIdleAndClosingDiscardsPreviewState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: HostModel
            scenario.onActivity {
                model=ViewModelProvider(it)[HostModel::class.java]
                assertFalse(model.projectOpen)
                assertNull(model.ui)
                assertNull(model.isolatedUid)
                model.restart()
                model.changeProjectRule("guitar-tuner", Rule.DENY)
                assertEquals(Rule.DENY, model.microphoneRuleFor("guitar-tuner"))
                assertFalse("Changing Settings must not run an app", model.projectOpen)
                assertNull(model.isolatedUid)
                assertThrows(IllegalArgumentException::class.java) { model.changeProjectRule("../other-app", Rule.ALLOW) }
                assertThrows(IllegalArgumentException::class.java) { model.microphoneRuleFor(java.util.UUID.randomUUID().toString()) }
                model.changeProjectRule("guitar-tuner", Rule.INHERIT)
                model.previewProject(bundle())
            }
            fun waitFor(check: () -> Boolean) {
                val deadline=System.currentTimeMillis()+15000
                while(System.currentTimeMillis()<deadline) {
                    var passed=false;scenario.onActivity { passed=check() };if(passed)return
                    Thread.sleep(100)
                }
                fail("Runtime did not reach expected state")
            }
            waitFor { model.ui!=null || model.failure!=null }
            scenario.onActivity { assertNull(model.failure);model.action("add") }
            waitFor { model.ui.toString().contains("Count 1") }
            scenario.onActivity {
                model.closeProject()
                assertFalse(model.projectOpen);assertFalse(model.preview);assertFalse(model.listening)
                assertNull(model.ui);assertNull(model.isolatedUid);assertNull(model.failure)
                assertFalse(model.askPermission);assertFalse(model.askAndroidPermission)
                model.restart()
            }
            Thread.sleep(250)
            scenario.onActivity {
                assertFalse("Late callbacks must not reopen a closed app",model.projectOpen)
                assertNull(model.ui);assertNull(model.isolatedUid)
                model.previewProject(bundle())
            }
            waitFor { model.ui!=null || model.failure!=null }
            scenario.onActivity {
                assertNull(model.failure)
                assertTrue("Closing a preview discards its unsaved data",model.ui.toString().contains("Count 0"))
                model.closeProject()
            }
        }
    }
    @Test fun sessionIsEncryptedAtRest() {
        // Instrumentation runs as the target UID. Use a separate file, never the user's connection.
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=SecureSession(context,"chatgpt.test-session")
        val marker="test-credential-not-real-"+java.util.UUID.randomUUID()
        try {
            vault.write(marker)
            assertEquals(marker,vault.read())
            val bytes=File(context.noBackupFilesDir,"chatgpt.test-session").readBytes()
            assertFalse(String(bytes,Charsets.UTF_8).contains(marker))
        } finally { vault.clear() }
        assertNull(vault.read())
    }
}
