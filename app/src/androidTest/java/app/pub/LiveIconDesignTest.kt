package app.pub

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.auth.ChatGptAuth
import app.pub.builder.IconDesigner
import app.pub.core.AppIcon
import app.pub.core.ModelSelection
import app.pub.core.ProjectBundle
import app.pub.ui.AppIconTile
import app.pub.ui.Eyebrow
import app.pub.ui.PubTheme
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Live: designs launcher icons for three app ideas with the connected ChatGPT account through the real icon workflow
 * (design, refine, at most one simplification each, so 3-6 requests), saves the raw answers and refined designs to
 * cache/live-icons/icons.json and renders them as PUB draws them. Runs only with -e pubLiveIcons true; uses plan usage.
 */
@RunWith(AndroidJUnit4::class)
class LiveIconDesignTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private val ideas = listOf(
        "Habit Tracker" to "Tick off daily habits and see the current streak for each one.",
        "Mortgage Calculator" to "Monthly payment, total interest and a year-by-year balance for a home loan.",
        "Bird Log" to "Record the birds you spot with the date, the place and a note.")

    @Test fun designsMinimalIconsWithTheConnectedAccount() {
        assumeTrue("Live icon design runs only with -e pubLiveIcons true", InstrumentationRegistry.getArguments().getString("pubLiveIcons") == "true")
        val auth = ChatGptAuth(context); auth.prepare(); assertNotNull("Connect ChatGPT in PUB first", auth.accountLabel())
        val preferred = context.getSharedPreferences("builder.preferences", Context.MODE_PRIVATE).getString("preferred_model", null)
        val model = ModelSelection.choose(auth.models().map { ModelSelection.Model(it.id, it.name) }, preferred)
        // -e pubLiveIconsOnly Bird designs only the ideas whose name starts with it, to retry a single icon without redoing the others.
        val only = InstrumentationRegistry.getArguments().getString("pubLiveIconsOnly")
        val ideas = ideas.filter { only == null || it.first.startsWith(only) }
        val dir = File(context.cacheDir, "live-icons").apply { deleteRecursively(); mkdirs() }
        var requests = 0
        val report = JsonObject().apply { addProperty("model", model) }
        val results = JsonArray()
        val icons = ideas.map { (name, description) ->
            val bundle = ProjectBundle.parse(JsonObject().apply {
                addProperty("name", name); addProperty("description", description); add("capabilities", JsonArray())
                addProperty("source", "function dispatch(e){return JSON.stringify({ui:{type:'text',text:'x'},effects:[]});}")
            }.toString())
            val answers = JsonArray()
            val icon = IconDesigner.designWith(bundle, null) { input ->
                requests++
                auth.generate(model, IconDesigner.CONTRACT, input).also { answers.add(it) }
            }
            results.add(JsonObject().apply {
                addProperty("name", name); add("answers", answers); add("icon", icon)
                addProperty("shapes", icon.getAsJsonArray("shapes").size()); addProperty("busyness", AppIcon.busyness(icon))
                addProperty("tooBusy", AppIcon.tooBusy(icon))
            })
            assertTrue("$name: not a pictogram", AppIcon.isPictogram(icon))
            name to icon
        }
        report.addProperty("requests", requests); report.add("icons", results)
        File(dir, "icons.json").writeText(report.toString())
        assertTrue("At most one simplification per icon: $requests requests", requests <= ideas.size * 2)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var host: HostModel
            scenario.onActivity { host = ViewModelProvider(it)[HostModel::class.java] }
            for ((label, font) in listOf("terminal" to "mono", "lcd" to "serif")) {
                scenario.onActivity { it.setContent { PubTheme(host.styleGuide.withFont(font)) { Sheet(icons) } } }
                compose.waitForIdle(); Thread.sleep(700)
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(dir, "icons-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Sheet(icons: List<Pair<String, JsonObject>>) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Eyebrow("Designed live")
                for (size in listOf(40.dp, 56.dp, 72.dp, 96.dp)) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    icons.forEach { (name, icon) -> AppIconTile(name, icon, size = size) }
                }
                icons.forEach { (name, _) -> Text(name, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
