package app.pub

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.builder.BuildDraftSnapshot
import app.pub.builder.BuildDraftStore
import app.pub.builder.BuilderBackend
import app.pub.builder.BuilderController
import app.pub.builder.BuilderControllers
import app.pub.core.ProjectBundle
import app.pub.core.VisualReviewDecision
import app.pub.projects.SavedProject
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Follow-up changes to a draft with offline fixtures: each change builds on the current draft, the change box is cleared
 * for the next one while the draft lists what was asked for, and previews and saved apps lead back to the change box.
 */
@RunWith(AndroidJUnit4::class)
class FollowUpChangesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(label: String, timeout: Long = 20000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { var done = false; main { done = check() }; if (done) return; Thread.sleep(50) }
        fail(label)
    }

    private class Backend : BuilderBackend {
        override val initialModel = "fixture-model"
        /** The request and the name of the package it changed (null for a new app), per generation. */
        val calls = CopyOnWriteArrayList<Pair<String, String?>>()
        @Volatile var failNext = false
        override fun generate(model: String, request: String, context: String, existing: ProjectBundle?): ProjectBundle {
            if (failNext) { failNext = false; throw IllegalStateException("Fixture build failed") }
            calls.add(request to existing?.name)
            return bundle("Follow-up counter ${calls.size}")
        }
        override fun assess(model: String, request: String, bundle: ProjectBundle, png: ByteArray) =
            VisualReviewDecision.parse("""{"status":"pass","summary":"Readable","issues":[]}""")
        companion object {
            fun bundle(name: String) = ProjectBundle.parse(JsonObject().apply {
                addProperty("name", name); addProperty("description", "Follow-up fixture")
                add("capabilities", JsonArray())
                addProperty("source", "function dispatch(e){return JSON.stringify({ui:{type:'column',children:[{type:'text',text:'$name'}]},effects:[]});}")
            }.toString())
        }
    }

    /** Builds [request] and settles its draft without the visual review, which needs a capture these tests skip. */
    private fun build(controller: BuilderController, request: String) {
        main { controller.prompt = request; controller.generate("{}") }
        waitFor("Build did not commit a draft") { controller.busy == null && controller.capturePending != null }
        main { controller.captureFailed("Not captured in this test") }
    }

    private fun withController(block: (BuilderController, Backend) -> Unit) {
        val directory = File(app.cacheDir, "follow-up-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val backend = Backend()
        lateinit var controller: BuilderController
        var previous: BuilderController? = null
        main { controller = BuilderController(app, backend, context, false); previous = BuilderControllers.replaceForTest(controller) }
        try { block(controller, backend) }
        finally { main { controller.close(); BuilderControllers.replaceForTest(previous) }; directory.deleteRecursively() }
    }

    @Test fun changesBuildOnTheDraftAndKeepItsRequests() = withController { controller, backend ->
        build(controller, "A counter")
        main {
            assertEquals("The box is ready for the next change", "", controller.prompt)
            assertEquals(listOf("A counter"), controller.draftRequests)
        }
        build(controller, "Make the count bigger")
        main {
            assertEquals(listOf("A counter" to null, "Make the count bigger" to "Follow-up counter 1"), backend.calls.toList())
            assertEquals("Follow-up counter 2", controller.draft!!.name)
            assertEquals(listOf("A counter", "Make the count bigger"), controller.draftRequests)
            assertEquals("", controller.prompt)
        }
        // A failed change keeps its text for a retry, and the draft and its requests as they were.
        backend.failNext = true
        main { controller.prompt = "Add a reset button"; controller.generate("{}") }
        waitFor("Failed build did not finish") { controller.busy == null }
        main {
            assertEquals("Add a reset button", controller.prompt)
            assertEquals("Follow-up counter 2", controller.draft!!.name)
            assertEquals(listOf("A counter", "Make the count bigger"), controller.draftRequests)
            controller.clearDraft()
            assertTrue(controller.draftRequests.isEmpty())
        }
        // Without a draft the next build is a new app again, with its own requests.
        build(controller, "A timer")
        main { assertEquals("A timer" to null, backend.calls.last()); assertEquals(listOf("A timer"), controller.draftRequests) }
    }

    /** A draft saved before requests were kept (empty list) and before the box was cleared on commit. */
    @Test fun olderDraftRestoresWithAnEmptyBoxAndItsOriginalRequest() {
        val directory = File(app.cacheDir, "follow-up-restore-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        BuildDraftStore(context).write(BuildDraftSnapshot(prompt = "Make the count bigger", sessionId = UUID.randomUUID().toString(),
            editingProject = null, draft = Backend.bundle("Follow-up counter 2"), draftHistoryId = UUID.randomUUID().toString(),
            draftRequest = "Make the count bigger", draftModel = "fixture-model", repairPass = 0, message = null, visualReview = null,
            visualReviewError = null, running = false, originalRequest = "A counter"))
        lateinit var restored: BuilderController
        main { restored = BuilderController(app, Backend(), context, true) }
        try {
            waitFor("Draft not restored") { restored.busy == null }
            main {
                assertEquals("Follow-up counter 2", restored.draft!!.name)
                assertEquals("The request that built the draft is not a pending change", "", restored.prompt)
                assertEquals(listOf("A counter"), restored.draftRequests)
            }
        } finally { main { restored.close() }; directory.deleteRecursively() }
    }

    @Test fun previewAndSavedAppLeadBackToTheChangeBox() = withController { controller, _ ->
        build(controller, "A counter")
        build(controller, "Make the count bigger")
        val intent = Intent(app, MainActivity::class.java).putExtra("open_builder", true)
        var saved: SavedProject? = null
        lateinit var host: HostModel
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { host = ViewModelProvider(it)[HostModel::class.java] }
            try {
                compose.onNodeWithText("Try it, then fine-tune").assertIsDisplayed()
                compose.onNodeWithTag("draft-card").assertIsDisplayed()
                compose.onNodeWithText("What should change?").assertExists()
                compose.onNodeWithText("Speak a change").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Apply changes").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("request-trail").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("A counter", substring = true).assertExists()
                compose.onNodeWithText("Make the count bigger", substring = true).assertExists()
                compose.onNodeWithTag("app-instructions").performTextInput("Add a reset button")
                compose.runOnIdle { assertEquals("Add a reset button", controller.prompt) }

                // Request changes closes the preview of the draft and returns to the change box, keeping its text.
                compose.onNodeWithText("Preview app").performScrollTo().performClick()
                compose.waitUntil(15000) { host.ui != null || host.failure != null }
                compose.runOnIdle { assertNull(host.failure); assertTrue(host.preview) }
                compose.onNodeWithTag("request-changes").assertIsDisplayed().performClick()
                compose.waitUntil(5000) { !host.projectOpen }
                compose.onNodeWithTag("change-box").assertIsDisplayed()
                compose.runOnIdle { assertEquals("Add a reset button", controller.prompt); assertNotNull(controller.draft) }

                // A saved app's Edit returns to the builder to change that app.
                compose.onNodeWithText("Preview app").performScrollTo().performClick()
                compose.waitUntil(15000) { host.ui != null || host.failure != null }
                compose.onNodeWithTag("save-preview").performClick()
                compose.waitUntil(15000) { host.projectOpen && !host.preview && host.ui != null }
                compose.runOnIdle { saved = host.projects.first { it.id == host.projectId } }
                compose.onNodeWithTag("request-changes").assertDoesNotExist()
                compose.onNodeWithTag("edit-app").assertIsDisplayed().performClick()
                compose.waitUntil(5000) { !host.projectOpen }
                compose.runOnIdle { assertEquals(saved!!.id, controller.editingProject?.id); assertNull(controller.draft) }
                compose.onNodeWithText("Edit Follow-up counter 2").assertIsDisplayed()
                compose.onNodeWithText("What would you like to change?").assertExists()
                compose.onNodeWithText("Speak a change").performScrollTo().assertIsDisplayed()
            } finally {
                compose.runOnIdle { host.closeProject(); saved?.let { host.deleteProject(it) } }
            }
        }
    }
}
