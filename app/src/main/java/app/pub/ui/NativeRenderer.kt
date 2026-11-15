package app.pub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
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
import com.google.gson.JsonObject
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NativeRenderer(node: JsonObject, onAction: (String) -> Unit, onInput: (String, String) -> Unit = { _, _ -> }, resolveImage: (String) -> File? = { null }, resolveGraphic: (String) -> JsonObject? = { null }) {
    when (node["type"].asString) {
        "column" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            node["children"].asJsonArray.forEach { NativeRenderer(it.asJsonObject, onAction, onInput, resolveImage, resolveGraphic) }
        }
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
            Text(node["text"].asString, textAlign = TextAlign.Center,
                style = when (style) {
                    "display" -> if (node["text"].asString.length <= 5)
                        MaterialTheme.typography.displayLarge.copy(fontSize = 76.sp, fontWeight = FontWeight.Light)
                    else MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Light)
                    "eyebrow" -> MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp)
                    "subtitle" -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.bodyMedium
                }, color = if (style == "eyebrow") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        "input" -> ScriptTextField(node["action"].asString, node["value"].asString, node["label"].asString, onInput)
        "button" -> Button(onClick = { onAction(node["action"].asString) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(node["label"].asString)
        }
        "choices" -> FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
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
 * The script's reply to each keystroke arrives asynchronously from the isolated runtime and may echo an
 * older value. Binding the field straight to the script value reverted characters and moved the cursor
 * while replies were in flight, and a script that did not echo made the field impossible to type into.
 * The field therefore owns its text; a script value is adopted only when it is not an echo of what the
 * user typed (for example, the script clears or reformats the field).
 */
@Composable
private fun ScriptTextField(action: String, scriptValue: String, label: String, onInput: (String, String) -> Unit) {
    var text by remember(action) { mutableStateOf(scriptValue) }
    val sent = remember(action) { ArrayDeque<String>() }
    LaunchedEffect(action, scriptValue) {
        val echo = sent.indexOf(scriptValue)
        if (echo >= 0) repeat(echo + 1) { sent.removeFirst() }
        else { sent.clear(); text = scriptValue }
    }
    OutlinedTextField(value = text, onValueChange = { value ->
        val next = value.take(2000)
        if (next != text) {
            text = next
            sent.addLast(next); if (sent.size > 64) sent.removeFirst()
            onInput(action, next)
        }
    }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}
