package app.pub

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.builder.BuilderBackend
import app.pub.builder.BuilderController
import app.pub.builder.BuilderControllers
import app.pub.builder.EditBasis
import app.pub.builder.GeneratedApp
import app.pub.core.AppIcon
import app.pub.core.AppTestSuite
import app.pub.core.ProjectBundle
import app.pub.core.ResearchBrief
import app.pub.core.StyleGuide
import app.pub.core.UiPlan
import app.pub.core.VisualReviewDecision
import app.pub.ui.AppIconTile
import app.pub.ui.BuilderScreen
import app.pub.ui.LauncherScreen
import app.pub.builder.BuilderModel
import app.pub.ui.Eyebrow
import app.pub.ui.PubButton
import app.pub.ui.PubIcons
import app.pub.ui.PubOutlinedButton
import app.pub.ui.PubTextButton
import app.pub.ui.PubTheme
import com.google.gson.JsonParser
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Not a check: renders PUB's screens through every builder step with offline fixtures and saves full-screen PNGs to
 * cache/design-shots, for reviewing the design on a real phone. It runs only when asked for with the instrumentation
 * argument pubDesignShots=true (the shots include the phone's saved apps and the connected account's address); the phone
 * must be unlocked. No saved data is changed.
 */
@RunWith(AndroidJUnit4::class)
class DesignScreenshots {
    @get:Rule val compose = createEmptyComposeRule()
    @org.junit.Before fun onlyWhenAsked() {
        org.junit.Assume.assumeTrue("Design screenshots run only with -e pubDesignShots true",
            InstrumentationRegistry.getArguments().getString("pubDesignShots") == "true")
    }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dir get() = File(app.cacheDir, "design-shots").apply { mkdirs() }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(label: String, timeout: Long = 20000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { var done = false; main { done = check() }; if (done) return; Thread.sleep(50) }
        fail(label)
    }
    private fun shot(name: String) {
        compose.waitForIdle(); Thread.sleep(700)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Backend : BuilderBackend {
        override val initialModel = "fixture-model"
        val gate = CountDownLatch(1)
        override fun generate(model: String, request: String, context: String, existing: ProjectBundle?) = bundle()
        override fun generateApp(model: String, request: String, context: String, existing: ProjectBundle?, basis: EditBasis?): GeneratedApp {
            check(gate.await(60, TimeUnit.SECONDS)) { "Fixture was not released" }
            return GeneratedApp(bundle(), TESTS)
        }
        override fun research(model: String, request: String, existing: ProjectBundle?, stored: ResearchBrief?) = BRIEF
        override fun plan(model: String, request: String, existing: ProjectBundle?, brief: ResearchBrief?, stored: UiPlan?) = PLAN
        override fun assess(model: String, request: String, bundle: ProjectBundle, png: ByteArray) =
            VisualReviewDecision.parse("""{"status":"pass","summary":"The plant list, the Water button and the next watering dates are clear and readable.","issues":[]}""")

        companion object {
            const val ICON = """{"type":"vector","width":48,"height":48,"shapes":[
                {"id":"body","type":"path","commands":[["M",12,21],["L",31,21],["L",29,38],["L",14,38],["Z"]],"fill":"transparent","stroke":"primary"},
                {"id":"spout","type":"line","x1":31,"y1":25,"x2":40,"y2":17,"fill":"transparent","stroke":"primary"},
                {"id":"handle","type":"path","commands":[["M",15,21],["C",15,13,28,13,28,21]],"fill":"transparent","stroke":"primary"},
                {"id":"drop","type":"ellipse","cx":41,"cy":23,"rx":1.6,"ry":1.6,"fill":"primary","stroke":"transparent"}]}"""
            fun bundle() = ProjectBundle.parse("""{"name":"Plant Care","description":"Log when you water each plant and see which ones are due today. Watering intervals are per plant.",
                "capabilities":["storage.project"],"assets":{"app-icon":$ICON},"icon":"app-icon",
                "source":"var n=0;function dispatch(raw){var e=JSON.parse(raw);if(e.type==='action')n++;return JSON.stringify({ui:{type:'column',children:[{type:'text',text:'Due today',style:'eyebrow'},{type:'item',title:'Monstera',supporting:'Every 7 days',trailing:'Today'},{type:'button',label:'Water',action:'water'},{type:'text',text:'Watered '+n}]},effects:[]});}"}""")
            val TESTS: AppTestSuite = AppTestSuite.parse("""[{"name":"Shows plants due today","steps":[{"type":"action","action":"water"}],"expect":["Monstera"]},
                {"name":"Water counts up","steps":[{"type":"action","action":"water"}],"expect":["Watered 1"]}]""")
            val BRIEF: ResearchBrief = ResearchBrief.parseModelOutput("""{"summary":"Plant care apps centre on a list of plants with a next watering date; people want one tap to log watering and no accounts.","category":"Plant care",
                "features":[{"title":"Plant list with next watering","priority":"must","feasibleInPub":true,"notes":""},
                {"title":"One-tap watering log","priority":"must","feasibleInPub":true,"notes":""},
                {"title":"Push reminders","priority":"should","feasibleInPub":false,"notes":"PUB apps cannot send notifications"}],
                "acceptanceChecks":["Tap Water -> the plant's next date moves on"],"assumptions":["Intervals in whole days"]}""")
                .withSources(listOf(ResearchBrief.Source.of("Watering schedules", "https://example.com/watering"), ResearchBrief.Source.of("Houseplant care", "https://example.org/care")), true)
            val PLAN: UiPlan = UiPlan.parseModelOutput("""{"layout":"A list of plants due first, then the rest","screens":[{"name":"Plants","purpose":"See what needs water and log it",
                "firstView":"Due today, then all plants","elements":["eyebrow: 'Due today'","item per plant"],"states":["empty: 'No plants yet'"]},
                {"name":"Plant","purpose":"Edit one plant's name and interval","firstView":"Name and interval inputs","elements":["input: name"],"states":[]}],
                "interactions":["Water logs today"],"content":["Dates as 'Today', 'Tomorrow', 'Mon 3'"],"accessibility":["Every button has a text label"]}""")
        }
    }

    @Test fun builderSteps() {
        val directory = File(app.cacheDir, "design-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val backend = Backend()
        lateinit var controller: BuilderController
        var previous: BuilderController? = null
        main { controller = BuilderController(app, backend, context, false); previous = BuilderControllers.replaceForTest(controller) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { _ ->
                shot("01-launcher")
                compose.onNodeWithTag("create-app").performClick()
                shot("02-describe-empty")
                main { controller.prompt = "A plant care log: my plants with how often each needs water, a Water button that logs today, and which ones are due today." }
                shot("03-describe")
                main { controller.generate("{}") }
                waitFor("Build did not reach its build phase") { controller.busy == BuilderController.BUILDING }
                shot("04-building")
                backend.gate.countDown()
                waitFor("Draft not committed", 60000) { controller.busy == null && controller.capturePending != null }
                main { controller.reviewCapture(controller.capturePending!!.token, ByteArray(256)) }
                waitFor("Review did not finish") { controller.busy == null && controller.visualReview != null }
                shot("05-review")
                compose.onNodeWithTag("change-box").performScrollTo()
                shot("06-review-adjust")
                compose.onNodeWithTag("fold-research").performScrollTo().performClick()
                shot("07-review-details")
                compose.onNodeWithTag("continue-publish").performClick()
                shot("08-publish")
                compose.onNodeWithTag("back").performClick()
                compose.onNodeWithTag("settings").performClick()
                shot("09-settings")
                compose.onNodeWithTag("style-guide").performScrollTo().performClick()
                shot("10-style")
            }
        } finally {
            backend.gate.countDown()
            main { controller.close(); BuilderControllers.replaceForTest(previous) }
            directory.deleteRecursively()
        }
    }

    /** The launcher and the builder's steps on a narrow screen (340 dp) at a large font scale (1.4). */
    @Test fun narrowScreenLargeText() {
        val directory = File(app.cacheDir, "design-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val backend = Backend().apply { gate.countDown() }
        lateinit var controller: BuilderController
        var previous: BuilderController? = null
        main { controller = BuilderController(app, backend, context, false); previous = BuilderControllers.replaceForTest(controller) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var host: HostModel
                lateinit var builder: BuilderModel
                scenario.onActivity { host = ViewModelProvider(it)[HostModel::class.java]; builder = ViewModelProvider(it)[BuilderModel::class.java] }
                val guide = host.styleGuide
                var screen by androidx.compose.runtime.mutableStateOf("launcher")
                scenario.onActivity { activity -> activity.setContent {
                    PubTheme(guide) {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                            androidx.compose.ui.unit.Density(density.density, fontScale = 1.4f)) {
                            // requiredWidth: a Surface passes its full size down as a minimum, which would widen a plain width().
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Surface(Modifier.safeDrawingPadding().requiredWidth(340.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.background) {
                                    if (screen == "launcher") LauncherScreen(host, {}, {}, {}) {}
                                    else BuilderScreen(builder, host, {}, {}) {}
                                }
                            }
                        }
                    }
                } }
                shot("narrow-1-launcher")
                main { controller.beginCreate(); controller.prompt = "A plant care log with a Water button and the plants due today."; screen = "builder" }
                shot("narrow-2-describe")
                main { controller.generate("{}") }
                waitFor("Draft not committed", 60000) { controller.busy == null && controller.capturePending != null }
                main { controller.reviewCapture(controller.capturePending!!.token, ByteArray(256)) }
                waitFor("Review did not finish") { controller.busy == null && controller.visualReview != null }
                shot("narrow-3-review")
                compose.onNodeWithTag("change-box").performScrollTo()
                shot("narrow-4-adjust")
                compose.onNodeWithTag("continue-publish").performClick()
                shot("narrow-5-publish")
            }
        } finally {
            main { controller.close(); BuilderControllers.replaceForTest(previous) }
            directory.deleteRecursively()
        }
    }

    /** Each type pairing in the phone's own style guide colours: headings, text, labels, buttons and icon tiles. */
    @Test fun typeSpecimens() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var guide: StyleGuide
            scenario.onActivity { guide = ViewModelProvider(it)[HostModel::class.java].styleGuide }
            for ((font, mode) in listOf("sans" to "dark", "serif" to "dark", "mono" to "dark", "sans" to "light")) {
                scenario.onActivity { it.setContent { PubTheme(guide.withFont(font).withMode(mode)) { Specimen(font) } } }
                shot("spec-$font-$mode")
            }
        }
    }

    /** Launcher icons at three sizes, dark and light: the phone's saved apps, the designer's style examples and the built-ins. */
    @Test fun iconSheet() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var host: HostModel
            scenario.onActivity { host = ViewModelProvider(it)[HostModel::class.java] }
            runCatching { compose.waitUntil(10000) { host.projects.isNotEmpty() } }
            val guide = host.styleGuide
            val saved = host.projects.map { project -> project.bundle.name to project.bundle.icon?.let { project.bundle.assets[it]?.asJsonObject } }
            val examples: List<Pair<String, com.google.gson.JsonObject?>> = AppIcon.STYLE_EXAMPLES.toSortedMap().map { (name, json) -> name to AppIcon.fromDesign(json) }
            val builtIns: List<Pair<String, com.google.gson.JsonObject?>> = listOf("Tip Calculator", "Guitar tuner", "Notes").map { name -> name to null }
            for (mode in listOf("dark", "light")) {
                scenario.onActivity { it.setContent { PubTheme(guide.withMode(mode)) { IconSheet(listOf("Saved apps" to saved, "Style examples" to examples, "Built in" to builtIns)) } } }
                shot("icons-$mode")
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun IconSheet(groups: List<Pair<String, List<Pair<String, com.google.gson.JsonObject?>>>>) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                groups.forEach { (title, icons) ->
                    Eyebrow(title)
                    for (size in listOf(40.dp, 56.dp, 72.dp)) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        icons.forEach { (name, icon) -> AppIconTile(name, icon, size = size) }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Specimen(font: String) {
        val icon = JsonParser.parseString(Backend.ICON).asJsonObject
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Eyebrow("Type pairing · $font")
                Text("What should we build?", style = MaterialTheme.typography.headlineLarge)
                Text("Say what it's for, what you put in and what it should show. PUB researches similar apps, plans the screens and tests the result.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("128.40", style = MaterialTheme.typography.displayLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIconTile("Plant Care", icon, size = 48.dp)
                    AppIconTile("Tip Calculator", null, size = 56.dp)
                    AppIconTile("Guitar tuner", null, size = 64.dp)
                    AppIconTile("Notes", null, size = 76.dp)
                }
                OutlinedTextField("A plant care log", {}, label = { Text("What would you like to build?") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PubOutlinedButton({}, Modifier.weight(1f)) { Icon(PubIcons.Play, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Try it") }
                    PubButton({}, Modifier.weight(1.4f)) { Text("Continue") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(true, {}, label = { Text("Pixel") }); FilterChip(false, {}, label = { Text("LCD") })
                    PubTextButton({}) { Text("Prompt history") }
                }
                Text("CHECKS 2/2 · VISUAL PASSED · ACCESS NONE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("✓ done – 3 × €12,50 · Привет · Ελληνικά · 日本語 · ✕", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
