package app.pub

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pub.core.AppIcon
import app.pub.ui.AppIconTile
import app.pub.ui.Dictation
import app.pub.ui.NativeRenderer
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The host's UI building blocks for generated apps: launcher pictograms, multiline input with dictation, paragraphs. */
@RunWith(AndroidJUnit4::class)
class UiCapabilitiesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.runOnUiThread {
        ViewModelProvider(compose.activity)[HostModel::class.java].closeProject()
        compose.activity.setContent { MaterialTheme { content() } }
    }

    private val screen = JsonParser.parseString("""{"type":"column","children":[
        {"type":"text","style":"paragraph","text":"Reading text starts at the left edge, like any document."},
        {"type":"input","label":"Entry","action":"entry","value":"Dear diary","multiline":true,"voice":true},
        {"type":"input","label":"Amount","action":"amount","value":"","keyboard":"decimal"}]}""").asJsonObject

    @Test fun multilineInputsAreTallAndDictationAddsTextAsIfTyped() {
        val inputs = mutableListOf<Pair<String, String>>()
        var spoken: ((String) -> Unit)? = null
        var prompt: String? = null
        show { NativeRenderer(screen, {}, { action, value -> inputs += action to value }, dictation = Dictation { label, onText -> prompt = label; spoken = onText }) }
        compose.onNodeWithText("Reading text starts at the left edge, like any document.").assertIsDisplayed()
        val entry = compose.onNodeWithTag("input-entry").fetchSemanticsNode().size.height
        val amount = compose.onNodeWithTag("input-amount").fetchSemanticsNode().size.height
        assertTrue("A multiline field shows several lines ($entry vs $amount px)", entry > amount * 2)
        compose.onNodeWithTag("dictate-amount").assertDoesNotExist()
        compose.onNodeWithTag("dictate-entry").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("Entry", prompt); spoken!!.invoke("went for a walk") }
        compose.runOnIdle { assertEquals("entry" to "Dear diary went for a walk", inputs.last()) }
        compose.onNodeWithTag("input-entry").assertTextContains("Dear diary went for a walk")
    }

    @Test fun cardsRowsItemsAndButtonStylesRenderAndReactToTaps() {
        val actions = mutableListOf<String>()
        val ui = JsonParser.parseString("""{"type":"column","children":[
            {"type":"text","text":"Habits","style":"subtitle"},
            {"type":"card","action":"summary","label":"Today summary","children":[{"type":"text","text":"1 of 2 done"}]},
            {"type":"item","title":"Read","supporting":"Daily","trailing":"Done","action":"toggle-read","checked":true},
            {"type":"item","title":"Walk","action":"toggle-walk","checked":false},
            {"type":"row","children":[{"type":"button","label":"Cancel","action":"cancel","style":"text"},{"type":"button","label":"Save","action":"save"}]},
            {"type":"button","label":"Edit habit","action":"edit","style":"secondary"},
            {"type":"button","label":"Delete habit","action":"delete","style":"danger"}]}""").asJsonObject
        show { NativeRenderer(ui, { actions += it }) }
        compose.onNodeWithTag("item-toggle-read").assertIsOn().performClick()
        compose.onNodeWithTag("item-toggle-walk").assertIsOff()
        compose.onNodeWithTag("card-summary").assertContentDescriptionContains("Today summary").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Delete habit").performClick()
        compose.runOnIdle { assertEquals(listOf("toggle-read", "summary", "save", "delete"), actions) }
        // A row puts its buttons side by side at their natural width; standalone buttons span the column.
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithText("Delete habit").fetchSemanticsNode().boundsInRoot
        assertTrue("Same line", kotlin.math.abs(cancel.center.y - save.center.y) < 8f)
        assertTrue("Side by side", save.left >= cancel.right)
        assertTrue("Full width vs natural width", delete.width > save.width * 2)
    }

    @Test fun withoutDictationTheMicrophoneIsShownDisabled() {
        show { NativeRenderer(screen, {}, { _, _ -> }) }
        compose.onNodeWithTag("dictate-entry").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Voice input is unavailable").assertExists()
    }

    @Test fun launcherIconsArePictogramsNeverLetters() {
        val notebook = AppIcon.fromDesign("""{"shapes":[
            {"id":"cover","type":"rect","x":10,"y":7,"width":24,"height":34,"rx":3,"fill":"transparent","stroke":"primary"},
            {"id":"line","type":"line","x1":16,"y1":16,"x2":28,"y2":16,"stroke":"#FF0000"},
            {"id":"dot","type":"ellipse","cx":36,"cy":36,"rx":3,"ry":3,"fill":"#00FF00","stroke":"transparent"}]}""")
        show {
            Row {
                AppIconTile("Plain Journal", null)
                AppIconTile("Notes", notebook)
                AppIconTile("Tip Calculator", null)
            }
        }
        compose.onNodeWithText("PJ").assertDoesNotExist()
        compose.onNodeWithText("PJ", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Plain Journal icon").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tip Calculator icon").assertIsDisplayed()
        // The designed pictogram is drawn in the tile's one tint: the model's red and green never appear.
        val pixels = compose.onAllNodesWithTag("app-icon")[1].captureToImage().toPixelMap()
        var drawn = 0; var red = 0; var green = 0
        val background = pixels[2, 2]
        for (y in 0 until pixels.height step 2) for (x in 0 until pixels.width step 2) {
            val c = pixels[x, y]
            if (c != background) drawn++
            if (c.red > .8f && c.green < .3f && c.blue < .3f) red++
            if (c.green > .8f && c.red < .3f && c.blue < .3f) green++
        }
        assertTrue("The pictogram is drawn ($drawn pixels)", drawn > 50)
        assertEquals(0, red); assertEquals(0, green)
    }
}
