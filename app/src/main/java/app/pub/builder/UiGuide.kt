package app.pub.builder

import app.pub.core.UiPacket
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.roundToInt

/**
 * PUB's native UI vocabulary and how much room it takes on screen, shared by the UI planner ([UiPlanner]) and the
 * builder ([Generator]) so the plan only uses what the builder can build. Sizes describe how NativeRenderer draws the
 * components in a running app.
 */
object UiGuide {
    val COMPONENTS = """
        UI COMPONENTS (no others; graphical assets are supported). The screen is ONE vertically scrolling column inside PUB's full-screen app frame, whose title bar already shows the app's name: never repeat the app name as a heading inside the app. The host owns all typography, colours, spacing, elevation and touch targets and honours the user's font scale and theme. Content is start-aligned like any Material screen; display headlines are centered. There are no tabs, menus, dialogs, bottom bars, floating buttons, gestures, timers or animation: an app shows its screens one at a time by replacing the column's contents when the user taps.
        {type:'column',children:[nodes]} (root should be column)
        {type:'card',children:[nodes],action?:'id',label?:'up to 80 chars'} a raised surface that groups related content: one record in a list, a result with its explanation, a settings group. With action the whole card is one tappable button (it may then hold only text, images, graphics, meters and rows of text); label is its spoken name. Do not nest cards.
        {type:'row',children:[1-${UiPacket.MAX_ROW_CHILDREN} text or button nodes]} puts short texts or small buttons side by side, wrapping onto a new line when they do not fit: a label with its value, a pair of actions such as Cancel and Save, a set of small filters, or a compact series such as the last 7 days as one short text per day ('Mon ✓', 'Tue –'). Buttons in a row take their natural width.
        {type:'text',text:'up to ${UiPacket.MAX_TEXT} chars; paragraph up to ${UiPacket.MAX_PARAGRAPH}',style:'body'|'subtitle'|'eyebrow'|'display'|'paragraph',align?:'start'|'center'} display is a large centered headline (5 characters or fewer become a very large number, as for a result); subtitle a section heading; eyebrow a small accent label above a heading; body a short line; paragraph reading text for notes, entries, messages and explanations.
        {type:'item',title:'up to ${UiPacket.MAX_ITEM_TITLE} chars',supporting?:'up to ${UiPacket.MAX_ITEM_SUPPORTING}',trailing?:'up to ${UiPacket.MAX_ITEM_TRAILING}',action?:'id',checked?:boolean,selected?:boolean} one compact list row (56-72 dp): title, an optional supporting line below it and short trailing text at the end (a count, a status, an amount). With action the row is tappable (to open it or to toggle it); checked:true|false shows a checkbox for to-dos, habits and checklists (tapping sends the action and the app flips the state); selected tints the row. Use items for lists instead of stacks of text and buttons: each item is one node.
        {type:'button',label:'up to 80 chars',action:'up to 80 chars',style?:'primary'|'secondary'|'text'|'danger'} primary is a full-width filled button for the screen's main action (one per screen, or one per card); secondary a full-width outlined button for other actions; text a quiet text button for low-emphasis actions such as Back, Cancel, Show more or About; danger a full-width filled button in the error colour, only for destructive actions such as Delete. Without style a button is primary.
        {type:'input',label:'up to 80 chars',action:'up to 80 chars',value:'up to ${UiPacket.MAX_INPUT_VALUE} chars; multiline up to ${UiPacket.MAX_MULTILINE_VALUE}',multiline?:false,keyboard?:'text'|'number'|'decimal'|'phone'|'email',voice?:false}
        Choose the input that fits the text: multiline:true for anything longer than a short line (notes, journal entries, messages, descriptions, lists to paste); it shows several lines and Enter adds a new line, so keep one field for the whole text instead of splitting it. keyboard:'decimal' (or 'number' for whole numbers) for numeric fields. voice:true adds a microphone that dictates into the field through Android's speech service: the app only receives the text as input events, needs no capability or permission, and the user can still type. Offer voice for free text people may prefer to speak (notes, journals, messages, descriptions), not for numbers, codes or short labels.
        {type:'choices',options:[{label:'up to 40 chars',action:'up to 80 chars',selected:boolean}]} max 8 options, shown as wrapping chips: the control for picking one of a few options, filters or a unit, and for switching between an app's sections.
        {type:'meter',value:number,active:boolean} guitar cents display, value -50..50.
        {type:'image',asset:'UUID.jpg',description:'up to 200 chars'} scoped captured photo, see hardware guide.
        {type:'graphic',asset:'asset-key',description:'accessible description',highlight:['shape-id']} native vector or generated image. See graphical asset contract.
        UI max 60 nodes, nesting 8, at most 4 image/graphic nodes, packet max 32768 chars.
        Events from the host include resume: {type:'resume'} when the app comes back on screen after the user left it, so refresh anything that depends on the current date or time then (no Refresh button is needed for that).
    """.trimIndent()

    /** Approximate rendered sizes, so a plan can budget what fits on one screen. */
    val LAYOUT = """
        SCREEN SPACE (dp at font scale 1; text heights grow with the font scale; the column has ${PADDING_DP} dp side padding and about 12-16 dp between elements):
        display headline about 40 dp per line, or 90 dp for a 1-5 character number; subtitle 24 dp per line; eyebrow 16 dp; body 20 dp per line; paragraph 24 dp per line.
        primary, secondary and danger buttons 48-52 dp; text button 40 dp; single-line input 64 dp; multiline input about 150 dp (5 lines), growing to 14 lines; choices 32 dp per row of chips (about 3-4 short chips per row on a phone).
        item 56 dp with a title only, 72 dp with a supporting line; card adds 16 dp of padding on every side plus 8 dp between its contents; row is as tall as its tallest element.
        meter about 100 dp; graphic 120-340 dp tall depending on its aspect ratio; image up to 360 dp.
        While someone types, the keyboard covers roughly the lower 40-45% of the screen, so a field's label, the field and its main action should sit close together.
    """.trimIndent()

    private const val PADDING_DP = 20

    /**
     * The phone's real space for the app, from the generation context: the measured app viewport when known, else the
     * screen, with the font scale and an estimate of what fits. Null fields are left out.
     */
    fun screenBudget(context: String): JsonObject {
        val device = runCatching { JsonParser.parseString(context).asJsonObject }.getOrDefault(JsonObject())
        val layout = device.getAsJsonObject("layoutContext") ?: JsonObject()
        fun number(o: JsonObject, key: String) = o.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() && it > 0 }
        val width = number(layout, "measuredBuilderViewportWidthDp") ?: number(device, "screenWidthDp") ?: 360.0
        val height = number(layout, "measuredBuilderViewportHeightDp") ?: number(device, "screenHeightDp")?.let { it - 120 } ?: 640.0
        val fontScale = number(layout, "fontScale") ?: number(device, "fontScale") ?: 1.0
        val contentWidth = (width - 2 * PADDING_DP).coerceAtLeast(120.0)
        return JsonObject().apply {
            addProperty("appViewportWidthDp", width.roundToInt())
            addProperty("appViewportHeightDp", height.roundToInt())
            addProperty("contentWidthDp", contentWidth.roundToInt())
            addProperty("fontScale", (fontScale * 100).roundToInt() / 100.0)
            // bodyMedium (14 sp) averages about 7 dp per character.
            addProperty("approxBodyCharactersPerLine", (contentWidth / (7.0 * fontScale)).roundToInt())
            addProperty("approxFirstViewHeightDp", height.roundToInt())
            addProperty("approxVisibleHeightWhileTypingDp", (height * .55).roundToInt())
            layout.get("orientation")?.takeIf { it.isJsonPrimitive }?.let { addProperty("orientation", it.asString) }
        }
    }
}
