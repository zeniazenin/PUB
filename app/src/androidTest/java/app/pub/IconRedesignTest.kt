package app.pub

import android.app.Application
import android.content.ContextWrapper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.builder.BuilderBackend
import app.pub.builder.BuilderController
import app.pub.builder.BuilderControllers
import app.pub.core.AppIcon
import app.pub.core.ProjectBundle
import app.pub.core.ResearchBrief
import app.pub.core.VisualReviewDecision
import app.pub.projects.SavedProject
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Redesign icon on a saved app with an offline designer: design, use the new icon, and keep the current one. */
@RunWith(AndroidJUnit4::class)
class IconRedesignTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class Backend : BuilderBackend {
        override val initialModel = "fixture-model"
        val designs = AtomicInteger()
        override fun generate(model: String, request: String, context: String, existing: ProjectBundle?) = throw IllegalStateException("No builds here")
        override fun assess(model: String, request: String, bundle: ProjectBundle, png: ByteArray) = VisualReviewDecision.parse("""{"status":"pass","summary":"x","issues":[]}""")
        override fun designIcon(model: String, bundle: ProjectBundle, brief: ResearchBrief?): JsonObject {
            designs.incrementAndGet()
            return AppIcon.fromDesign(AppIcon.STYLE_EXAMPLES.getValue("a journal"))
        }
    }

    private fun bundle() = ProjectBundle.parse(JsonObject().apply {
        addProperty("name", "Icon redesign test"); addProperty("description", "A notes fixture")
        add("capabilities", JsonArray())
        addProperty("source", "function dispatch(e){return JSON.stringify({ui:{type:'text',text:'Notes'},effects:[]});}")
    }.toString())

    @Test fun redesignedIconIsUsedOrKept() {
        val directory = File(app.cacheDir, "icon-redesign-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val backend = Backend()
        lateinit var controller: BuilderController
        var previous: BuilderController? = null
        main { controller = BuilderController(app, backend, context, false); previous = BuilderControllers.replaceForTest(controller) }
        var saved: SavedProject? = null
        lateinit var host: HostModel
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { host = ViewModelProvider(it)[HostModel::class.java] }
                runCatching { compose.waitUntil(10000) { controller.account != null } }
                assumeTrue("Redesign icon needs a connected ChatGPT account on this phone", controller.account != null)
                // A saved app without an icon of its own.
                compose.runOnIdle { host.previewProject(bundle()) }
                compose.waitUntil(15000) { host.ui != null || host.failure != null }
                compose.runOnIdle { host.savePreview() }
                compose.waitUntil(15000) { !host.savingPreview && !host.preview && host.ui != null }
                compose.runOnIdle { saved = host.projects.first { it.id == host.projectId }; host.closeProject() }
                val id = saved!!.id
                assertNull(saved!!.bundle.icon)

                // Design and use: the stored package gets the new icon, and nothing else changes.
                compose.onNodeWithTag("launcher").performScrollToNode(hasTestTag("app-$id"))
                compose.onNodeWithTag("app-$id").performTouchInput { longClick() }
                compose.onNodeWithTag("redesign-icon-$id").performClick()
                compose.onNodeWithTag("icon-redesign").assertIsDisplayed()
                assertEquals("Nothing is sent before Design icon", 0, backend.designs.get())
                compose.onNodeWithTag("design-icon").performClick()
                compose.waitUntil(10000) { controller.iconProposal != null }
                compose.onNodeWithTag("icon-proposal").assertIsDisplayed()
                // With -e pubDesignShots true, a screenshot of the side-by-side step for design review.
                if (InstrumentationRegistry.getArguments().getString("pubDesignShots") == "true") {
                    compose.waitForIdle(); Thread.sleep(600)
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(File(app.cacheDir, "design-shots").apply { mkdirs() }, "icon-redesign.png").outputStream()
                            .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                compose.onNodeWithTag("use-icon").performClick()
                compose.waitUntil(10000) { host.projects.first { it.id == id }.bundle.icon != null }
                val updated = host.projects.first { it.id == id }.bundle
                assertEquals(AppIcon.fromDesign(AppIcon.STYLE_EXAMPLES.getValue("a journal")), AppIcon.pictogram(updated))
                assertEquals(saved!!.bundle.source, updated.source)
                compose.runOnIdle { assertNull(controller.iconProposal) }
                compose.onNodeWithTag("icon-redesign").assertDoesNotExist()

                // Design again and keep the current icon: the package is unchanged.
                val before = updated.toJson()
                compose.onNodeWithTag("app-$id").performTouchInput { longClick() }
                compose.onNodeWithTag("redesign-icon-$id").performClick()
                compose.onNodeWithTag("design-icon").performClick()
                compose.waitUntil(10000) { controller.iconProposal != null }
                compose.onNodeWithTag("keep-icon").performClick()
                compose.runOnIdle { assertNull(controller.iconProposal); assertEquals(before, host.projects.first { it.id == id }.bundle.toJson()) }
                assertEquals(2, backend.designs.get())
            }
        } finally {
            main { saved?.let { project -> runCatching { host.deleteProject(project) } }; controller.close(); BuilderControllers.replaceForTest(previous) }
            directory.deleteRecursively()
        }
    }
}
