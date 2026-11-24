package app.pub

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pub.builder.BuilderModel
import app.pub.builder.Generator
import app.pub.core.ProjectBundle
import app.pub.core.PermissionPolicy.Rule
import app.pub.hardware.ProjectMedia
import app.pub.projects.SavedProject
import app.pub.projects.ProjectStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule as TestRule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LauncherEditingTest {
    @get:TestRule val compose = createAndroidComposeRule<MainActivity>()
    private val host get() = ViewModelProvider(compose.activity)[HostModel::class.java]
    private fun bundle(label: String = "Original") = ProjectBundle.parse(JsonObject().apply {
        addProperty("name", "Launcher edit test")
        addProperty("description", "Temporary edit test")
        add("capabilities", JsonArray().apply { add("storage.project") })
        addProperty("source", """
            var n=0;
            function dispatch(s){var e=JSON.parse(s),fx=[];
            if(e.type==='init')n=e.storage.n||0;
            if(e.type==='action'){n++;fx=[{type:'storage.save',data:{n:n}}];}
            return JSON.stringify({ui:{type:'column',children:[{type:'text',text:'$label '+n}]},effects:fx});}
        """.trimIndent())
    }.toString())
    private fun ready() { compose.waitUntil(15000) { host.ui != null || host.failure != null }; compose.runOnIdle { assertNull(host.failure) } }
    /** Saving runs off the main thread; wait until the saved app has reopened. */
    private fun save() { compose.runOnIdle { host.savePreview() }; compose.waitUntil(15000) { !host.savingPreview && (!host.preview || host.failure != null) }; ready() }
    private fun fixture(): SavedProject {
        compose.runOnIdle { host.previewProject(bundle()) };ready()
        compose.runOnIdle { host.action("add") }
        compose.waitUntil(10000) { host.ui.toString().contains("Original 1") }
        save()
        lateinit var project: SavedProject
        compose.runOnIdle { project=host.projects.first { it.id==host.projectId };host.closeProject() }
        return project
    }
    @Test fun longPressEditsWithCodeContextAndDeletionRequiresConfirmation() {
        val builder=ViewModelProvider(compose.activity)[BuilderModel::class.java]
        compose.waitUntil(30000) { builder.busy == null }
        org.junit.Assume.assumeFalse("PUB holds a build result on this phone, which Edit app opens instead; save or discard it first",
            builder.hasBuildResult || builder.buildInProgress)
        val project=fixture()
        try {
            compose.onNodeWithTag("app-${project.id}").performTouchInput { longClick() }
            compose.onNodeWithText("Edit app").assertIsDisplayed()
            compose.onNodeWithText("Delete app").assertIsDisplayed()
            compose.onNodeWithTag("edit-app-${project.id}").performClick()
            compose.onNodeWithText("What would you like to change?").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(project.id,builder.editingProject!!.id)
                assertEquals(project.bundle.source,builder.editingProject!!.bundle.source)
                assertFalse(host.projectOpen);assertNull(host.isolatedUid)
                val input=Generator.buildInput("Add a reset button", "{}", builder.editingProject!!.bundle)
                assertTrue(input.contains(project.bundle.toJson())); assertTrue(input.endsWith("Add a reset button"))
            }
            compose.onNodeWithTag("back").performClick()
            compose.onNodeWithTag("app-${project.id}").performTouchInput { longClick() }
            compose.onNodeWithTag("delete-app-${project.id}").performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("app-${project.id}").assertExists()
            compose.onNodeWithTag("app-${project.id}").performTouchInput { longClick() }
            compose.onNodeWithTag("delete-app-${project.id}").performClick()
            compose.onNodeWithText("Delete", substring=false).performClick()
            compose.onNodeWithTag("app-${project.id}").assertDoesNotExist()
            compose.runOnIdle { assertNull(ProjectStore(compose.activity).read(project.id));assertNull(builder.editingProject) }
        } finally { compose.runOnIdle { if(host.projects.any {it.id==project.id}) host.deleteProject(project) } }
    }
    @Test fun editPreviewIsIsolatedAndSaveUpdatesSameAppWithoutChangingSavedDataOrPhotos() {
        val project=fixture()
        val media=ProjectMedia(compose.activity)
        val asset="${UUID.randomUUID()}.jpg"
        val originalPhoto=File(media.directory(project.id),asset).apply { writeBytes(byteArrayOf(1,2,3)) }
        try {
            compose.runOnIdle { host.changeProjectRule(project.id,Rule.DENY);host.previewProject(bundle("Edited"),project) };ready()
            compose.runOnIdle { assertTrue(host.ui.toString().contains("Edited 1"));assertEquals(originalPhoto,host.resolveImage(asset));host.action("add") }
            compose.waitUntil(10000) { host.ui.toString().contains("Edited 2") }
            compose.runOnIdle { host.closeProject() }
            assertEquals(project.bundle.source,ProjectStore(compose.activity).read(project.id)!!.bundle.source)
            compose.runOnIdle { host.previewProject(bundle("Edited"),project) };ready()
            compose.runOnIdle { host.action("add") }
            compose.waitUntil(10000) { host.ui.toString().contains("Edited 2") }
            save()
            compose.runOnIdle {
                assertEquals(project.id,host.projectId);assertFalse(host.preview)
                assertTrue(host.ui.toString().contains("Edited 1"))
                assertEquals(Rule.DENY,host.microphoneRuleFor(project.id))
                assertEquals(1,host.projects.count {it.id==project.id})
                assertEquals(bundle("Edited").source,ProjectStore(compose.activity).read(project.id)!!.bundle.source)
            }
            assertArrayEquals(byteArrayOf(1,2,3),originalPhoto.readBytes())
        } finally { compose.runOnIdle { host.deleteProject(project) } }
    }
}
