package app.pub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.pub.core.UiPacket
import com.google.gson.JsonObject
import kotlin.math.abs

/**
 * A validated UI-packet node. Packets are never mutated after parsing and JsonObject equality is
 * structural, so marking this stable lets Compose skip every subtree that is unchanged between packets
 * (each script reply is a whole new tree; a tuner sends one per pitch frame).
 */
@Immutable
private class UiNode(val json: JsonObject) {
    override fun equals(other: Any?) = other is UiNode && other.json == json
    override fun hashCode() = json.hashCode()
}

/**
 * Host dictation for inputs with voice:true: Android's speech service turns speech into text, which the field adds as
 * if typed. The app only ever receives text. Null where dictation is unavailable (the visual review, or voice input
 * blocked in Settings); such inputs show a disabled microphone.
 */
fun interface Dictation { fun start(label: String, onText: (String) -> Unit) }

@Composable
fun NativeRenderer(node: JsonObject, onAction: (String) -> Unit, onInput: (String, String) -> Unit = { _, _ -> }, resolveImage: (String) -> File? = { null }, resolveGraphic: (String) -> JsonObject? = { null }, dictation: Dictation? = null) =
    CompositionLocalProvider(LocalDictation provides dictation) { RenderNode(UiNode(node), onAction, onInput, resolveImage, resolveGraphic) }

private val LocalDictation = staticCompositionLocalOf<Dictation?> { null }
/** True inside a row, where buttons take their natural width instead of the full width. */
private val LocalInRow = staticCompositionLocalOf { false }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenderNode(wrapped: UiNode, onAction: (String) -> Unit, onInput: (String, String) -> Unit, resolveImage: (String) -> File?, resolveGraphic: (String) -> JsonObject?) {
    val node = wrapped.json
    when (node["type"].asString) {
        // Content is start-aligned like any Material screen; display text and graphics are centered on their own.
        "column" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            node["children"].asJsonArray.forEach { RenderNode(UiNode(it.asJsonObject), onAction, onInput, resolveImage, resolveGraphic) }
        }
        "card" -> {
            val action = node["action"]?.asString
            val content: @Composable ColumnScope.() -> Unit = {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    node["children"].asJsonArray.forEach { RenderNode(UiNode(it.asJsonObject), onAction, onInput, resolveImage, resolveGraphic) }
                }
            }
            val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            if (action == null) Card(Modifier.fillMaxWidth(), colors = colors, content = content)
            else Card(onClick = { onAction(action) }, colors = colors, content = content,
                // A tappable card holds no controls of its own, so it reads as one button.
                modifier = Modifier.fillMaxWidth().testTag("card-$action").semantics(mergeDescendants = true) {
                    node["label"]?.asString?.let { contentDescription = it }
                })
        }
        "row" -> CompositionLocalProvider(LocalInRow provides true) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                node["children"].asJsonArray.forEach { RenderNode(UiNode(it.asJsonObject), onAction, onInput, resolveImage, resolveGraphic) }
            }
        }
        "item" -> ScriptListItem(node, onAction)
        "graphic" -> {
            val asset = resolveGraphic(node["asset"].asString)
            if (asset != null) NativeGraphic(asset, node["description"].asString,
                node.getAsJsonArray("highlight")?.map { it.asString }?.toSet() ?: emptySet(),
                Modifier.fillMaxWidth().heightIn(max=340.dp).testTag("graphic-${node["asset"].asString}"))
            else Text("Graphic unavailable", style=MaterialTheme.typography.bodySmall)
        }
        "image" -> {
            val file=resolveImage(node["asset"].asString)
            val bitmap by produceState<android.graphics.Bitmap?>(null,file?.absolutePath) {
                value=null
                value=withContext(Dispatchers.IO) {runCatching {file?.let {app.pub.hardware.ProjectMedia.decodePreview(it)}}.getOrNull()}
            }
            bitmap?.let { Image(it.asImageBitmap(),contentDescription=node["description"].asString,
                contentScale=ContentScale.Fit,modifier=Modifier.fillMaxWidth().heightIn(max=360.dp)) }
                ?: Text("Photo unavailable",style=MaterialTheme.typography.bodySmall)
        }
        "text" -> {
            val style = node["style"]?.asString ?: "body"
            // Text is start-aligned like the rest of the screen; a display headline (a result or a big number) is
            // centered. align overrides either. Inside a row, text takes its natural width.
            val center = when (node["align"]?.asString) { "center" -> true; "start" -> false; else -> style == "display" }
            val width = if (LocalInRow.current) Modifier else Modifier.fillMaxWidth()
            if (style == "paragraph") Text(node["text"].asString, textAlign = if (center) TextAlign.Center else TextAlign.Start,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = width)
            else Text(node["text"].asString, textAlign = if (center) TextAlign.Center else TextAlign.Start, modifier = width,
                style = when (style) {
                    "display" -> if (node["text"].asString.length <= 5)
                        MaterialTheme.typography.displayLarge.copy(fontSize = 76.sp, fontWeight = FontWeight.Light)
                    else MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Light)
                    "eyebrow" -> MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp)
                    "subtitle" -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.bodyMedium
                }, color = if (style == "eyebrow") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        "input" -> ScriptTextField(node["action"].asString, node["value"].asString, node["label"].asString,
            node["multiline"]?.asBoolean ?: false, node["keyboard"]?.asString ?: "text", node["voice"]?.asBoolean ?: false, onInput)
        "button" -> ScriptButton(node["label"].asString, node["style"]?.asString ?: "primary") { onAction(node["action"].asString) }
        "choices" -> FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            node["options"].asJsonArray.forEach { item ->
                val option = item.asJsonObject
                FilterChip(selected = option["selected"]?.asBoolean ?: false, onClick = { onAction(option["action"].asString) }, label = { Text(option["label"].asString) })
            }
        }
        "meter" -> {
            val value = node["value"].asFloat.coerceIn(-50f, 50f)
            val active = node["active"]?.asBoolean ?: false
            val accent = MaterialTheme.colorScheme.primary
            val quiet = MaterialTheme.colorScheme.outlineVariant
            val needle = if (abs(value) <= 5) accent else MaterialTheme.colorScheme.tertiary
            Column(Modifier.fillMaxWidth()) {
                Canvas(Modifier.fillMaxWidth().height(70.dp).semantics { contentDescription = if (active) "Tuning offset $value cents" else "Waiting for pitch" }) {
                    val left = 12.dp.toPx(); val right = size.width - left; val y = size.height / 2
                    drawLine(quiet, Offset(left, y), Offset(right, y), 3.dp.toPx(), StrokeCap.Round)
                    for (i in 0..10) {
                        val x = left + (right - left) * i / 10
                        val height = if (i == 5) 18.dp.toPx() else 7.dp.toPx()
                        drawLine(if (i == 5) accent else quiet, Offset(x, y - height), Offset(x, y + height), 2.dp.toPx())
                    }
                    if (active) {
                        val x = left + (right - left) * (value + 50) / 100
                        drawCircle(needle, 8.dp.toPx(), Offset(x, y))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("FLAT", style = MaterialTheme.typography.labelSmall)
                    Text("IN TUNE", color = accent, style = MaterialTheme.typography.labelSmall)
                    Text("SHARP", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * Buttons show their emphasis: primary (filled, the screen's main action), secondary (outlined), text (a quiet text
 * button) and danger (filled in the error colour, for destructive actions). Outside a row, primary, secondary and
 * danger buttons span the width; text buttons and buttons in a row take their natural width. An unknown style is
 * primary, as every button was before styles existed.
 */
@Composable
private fun ScriptButton(label: String, style: String, onClick: () -> Unit) {
    val modifier = (if (LocalInRow.current || style == "text") Modifier else Modifier.fillMaxWidth()).heightIn(min = 48.dp)
    when (style) {
        "secondary" -> OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
        // Shifted by its own padding outside a row, so its label lines up with the text above it.
        "text" -> TextButton(onClick = onClick, modifier = if (LocalInRow.current) modifier else modifier.offset(x = (-12).dp)) { Text(label) }
        "danger" -> Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text(label) }
        else -> Button(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

/**
 * One row of a list: a title with optional supporting text below and trailing text at the end. With an action the
 * whole row is tappable (checkable items show a checkbox that reflects checked; the app toggles it); selected rows
 * are tinted.
 */
@Composable
private fun ScriptListItem(node: JsonObject, onAction: (String) -> Unit) {
    val action = node["action"]?.asString
    val checked = node["checked"]?.asBoolean
    val selected = node["selected"]?.asBoolean ?: false
    val colors = MaterialTheme.colorScheme
    var modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
    if (action != null) modifier = modifier.testTag("item-$action").let {
        if (checked != null) it.toggleable(value = checked, role = Role.Checkbox, onValueChange = { onAction(action) })
        else it.clickable(role = Role.Button) { onAction(action) }
    }
    // Flush with the column's other content (Material's list item would add its own side padding inside a card).
    if (selected) modifier = modifier.background(colors.secondaryContainer)
    Row(modifier.heightIn(min = 56.dp).padding(horizontal = if (selected) 12.dp else 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        checked?.let {
            // The whole row is the touch target, so the box itself needs no extra margin.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) { Checkbox(checked = it, onCheckedChange = null) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(node["title"].asString, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            node["supporting"]?.asString?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        node["trailing"]?.asString?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
    }
}

/**
 * The script's reply to each keystroke arrives asynchronously from the isolated runtime and may echo an
 * older value. Binding the field straight to the script value reverted characters and moved the cursor
 * while replies were in flight, and a script that did not echo made the field impossible to type into.
 * The field therefore owns its text; a script value is adopted only when it is not an echo of what the
 * user typed (for example, the script clears or reformats the field).
 */
@Composable
private fun ScriptTextField(action: String, scriptValue: String, label: String, multiline: Boolean, keyboard: String, voice: Boolean,
                            onInput: (String, String) -> Unit) {
    var text by remember(action) { mutableStateOf(scriptValue) }
    val sent = remember(action) { ArrayDeque<String>() }
    val limit = if (multiline) UiPacket.MAX_MULTILINE_VALUE else UiPacket.MAX_INPUT_VALUE
    val currentInput by rememberUpdatedState(onInput)
    LaunchedEffect(action, scriptValue) {
        val echo = sent.indexOf(scriptValue)
        if (echo >= 0) repeat(echo + 1) { sent.removeFirst() }
        else { sent.clear(); text = scriptValue }
    }
    fun change(value: String) {
        // Never cut a UTF-16 surrogate pair at the limit.
        var end = minOf(value.length, limit)
        if (end in 1 until value.length && value[end - 1].isHighSurrogate()) end--
        val next = value.substring(0, end)
        if (next != text) {
            text = next
            sent.addLast(next); if (sent.size > 64) sent.removeFirst()
            currentInput(action, next)
        }
    }
    val dictation = LocalDictation.current
    OutlinedTextField(value = text, onValueChange = ::change, label = { Text(label) },
        minLines = if (multiline) 5 else 1, maxLines = if (multiline) 14 else Int.MAX_VALUE,
        keyboardOptions = KeyboardOptions(
            keyboardType = when (keyboard) {
                "number" -> KeyboardType.Number
                "decimal" -> KeyboardType.Decimal
                "phone" -> KeyboardType.Phone
                "email" -> KeyboardType.Email
                else -> KeyboardType.Text
            },
            capitalization = if (keyboard == "text" && multiline) KeyboardCapitalization.Sentences else KeyboardCapitalization.None),
        trailingIcon = if (!voice) null else { {
            IconButton(onClick = {
                // Dictated words are added to what is already there, as if typed.
                dictation?.start(label) { words -> change(text + (if (text.isEmpty() || text.last().isWhitespace()) "" else " ") + words) }
            }, enabled = dictation != null, modifier = Modifier.testTag("dictate-$action")) {
                MicrophoneGlyph(if (dictation != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    if (dictation != null) "Dictate $label" else "Voice input is unavailable")
            }
        } },
        modifier = Modifier.fillMaxWidth().testTag("input-$action"))
}

@Composable
private fun MicrophoneGlyph(tint: androidx.compose.ui.graphics.Color, description: String) {
    Canvas(Modifier.size(24.dp).semantics { contentDescription = description }) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        val unit = size.minDimension / 24f
        drawRoundRect(tint, Offset(9 * unit, 3 * unit), androidx.compose.ui.geometry.Size(6 * unit, 11 * unit),
            androidx.compose.ui.geometry.CornerRadius(3 * unit), style = stroke)
        drawArc(tint, 0f, 180f, false, Offset(5.5f * unit, 5.5f * unit), androidx.compose.ui.geometry.Size(13 * unit, 12.5f * unit), style = stroke)
        drawLine(tint, Offset(12 * unit, 18 * unit), Offset(12 * unit, 21 * unit), 2.dp.toPx(), StrokeCap.Round)
    }
}
