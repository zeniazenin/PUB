package app.pub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pub.HostModel
import app.pub.core.StyleColors
import app.pub.core.StyleGuide
import com.google.gson.JsonParser

/**
 * Settings → Style guide: the one look PUB applies to its own screens and every app, and which apps follow it. Every
 * change applies at once, also to saved apps, since the host draws them; an app set not to follow the guide uses PUB's
 * default style ([StyleGuide.DEFAULT]). The screen itself is drawn in the guide, and a sample app shows the result.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StyleGuideScreen(host: HostModel) {
    val guide = host.styleGuide
    val change: (StyleGuide) -> Unit = host::changeStyleGuide
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("style-guide-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PUB and every app you build follow this style guide: PUB draws their screens, graphics and icons in it. Changes apply right away, also to saved apps. You can turn it off for an app; that app then uses PUB's default style.")
        StylePreview()

        Section("Mode", "Apps built before style guides were designed for dark mode, and some use fixed colours in their graphics or pictures. If one is hard to read in light mode, turn the style guide off for that app below.")
        Chips(listOf("dark" to "Dark", "light" to "Light", "system" to "Match phone"), guide.mode, "style-mode") { change(guide.withMode(it)) }

        Section("Main colour", "Buttons, highlights, icons and graphics. PUB adjusts it for light or dark when needed, so text on it stays readable.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PRESETS.forEach { (name, hex) -> Swatch(name, hex, guide.primary == hex) { change(guide.withPrimary(hex)) } }
        }
        ColorField("Custom main colour", guide.primary, "style-primary") { change(guide.withPrimary(it)) }

        Section("Second colour", "Selected rows, filters and secondary graphic parts.")
        OptionalColor(guide.secondary, MaterialTheme.colorScheme.secondary, "style-secondary") { change(guide.withSecondary(it)) }

        Section("Third colour", "Contrasting accents, such as a meter's needle or a chart's third series.")
        OptionalColor(guide.tertiary, MaterialTheme.colorScheme.tertiary, "style-tertiary") { change(guide.withTertiary(it)) }

        Section("Corners")
        Chips(listOf("sharp" to "Sharp", "rounded" to "Rounded", "round" to "Round"), guide.corners, "style-corners") { change(guide.withCorners(it)) }

        Section("Font")
        Chips(listOf("sans" to "Sans", "serif" to "Serif", "mono" to "Mono"), guide.font, "style-font",
            labelStyle = { TextStyle(fontFamily = when (it) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> FontFamily.SansSerif }) }) {
            change(guide.withFont(it))
        }

        TextButton(onClick = { change(StyleGuide.DEFAULT) }, enabled = guide != StyleGuide.DEFAULT, modifier = Modifier.testTag("style-reset")) {
            Text("Reset to PUB's default style")
        }

        if (host.projects.isNotEmpty()) {
            HorizontalDivider()
            Section("Apps that follow it", "Apps you turn off here use PUB's default style. New apps follow the style guide.")
            host.projects.forEach { project ->
                val follows = host.usesStyleGuide(project.id)
                Row(Modifier.fillMaxWidth().toggleable(value = follows, role = Role.Switch) { host.changeUsesStyleGuide(project.id, it) }
                    .padding(vertical = 4.dp).testTag("style-app-${project.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(project.bundle.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Switch(checked = follows, onCheckedChange = null)
                }
            }
        }
    }
}

/** A sample app screen in the current style: text styles, a theme-coloured graphic, chips, a card with list rows, a field and buttons. */
@Composable
private fun StylePreview() {
    val sample = remember { JsonParser.parseString(SAMPLE).asJsonObject }
    val chart = remember { JsonParser.parseString(SAMPLE_CHART).asJsonObject }
    Surface(color = MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("style-preview").semantics { contentDescription = "Sample app in your style" }) {
        Column(Modifier.padding(20.dp)) {
            NativeRenderer(sample, onAction = {}, resolveGraphic = { if (it == "sample-chart") chart else null })
        }
    }
}

@Composable
private fun Section(title: String, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, tag: String, labelStyle: (String) -> TextStyle? = { null }, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) },
                label = { labelStyle(value)?.let { Text(label, style = it) } ?: Text(label) },
                modifier = Modifier.testTag("$tag-$value"))
        }
    }
}

@Composable
private fun Swatch(name: String, hex: String, selected: Boolean, onClick: () -> Unit) {
    val argb = StyleColors.argb(hex)
    val mark = if (StyleColors.contrast(argb, 0xFF000000.toInt()) >= StyleColors.contrast(argb, 0xFFFFFFFF.toInt())) Color.Black else Color.White
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(argb))
        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
        .selectable(selected, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = name }.testTag("style-color-${name.lowercase()}"),
        contentAlignment = Alignment.Center) {
        if (selected) Text("✓", color = mark, style = MaterialTheme.typography.titleMedium)
    }
}

/** Automatic (derived from the main colour) or a custom colour; [shown] is the colour in use. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionalColor(value: String?, shown: Color, tag: String, onChange: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(shown)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).align(Alignment.CenterVertically))
        FilterChip(selected = value == null, onClick = { onChange(null) }, label = { Text("Automatic") }, modifier = Modifier.testTag("$tag-auto"))
    }
    ColorField("Custom colour", value, tag) { onChange(it) }
}

/** A "#RRGGBB" field; a complete valid colour applies at once, anything else waits. */
@Composable
private fun ColorField(label: String, value: String?, tag: String, onColor: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value ?: "") }
    val candidate = text.trim().let { if (it.startsWith("#")) it else "#$it" }
    val invalid = text.isNotBlank() && !StyleGuide.validColor(candidate)
    OutlinedTextField(value = text, singleLine = true, label = { Text(label) }, isError = invalid,
        onValueChange = { typed ->
            text = typed.take(7)
            val next = text.trim().let { if (it.startsWith("#")) it else "#$it" }
            if (StyleGuide.validColor(next) && !next.equals(value, ignoreCase = true)) onColor(next)
        },
        supportingText = { Text(if (invalid) "Use # and six hex digits, for example #1A73E8" else "Six hex digits, for example #1A73E8") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag("$tag-hex"))
}

private val PRESETS = listOf(
    "Moss" to StyleGuide.DEFAULT.primary, "Blue" to "#9EC8FF", "Violet" to "#D0BCFF", "Teal" to "#7FD6C2",
    "Amber" to "#F2C063", "Coral" to "#F59B83", "Rose" to "#F4A6C6", "Slate" to "#B4C0CF")

private const val SAMPLE = """{"type":"column","children":[
    {"type":"text","text":"This week","style":"eyebrow"},
    {"type":"text","text":"42","style":"display"},
    {"type":"text","text":"Glasses of water","align":"center"},
    {"type":"graphic","asset":"sample-chart","description":"Bar chart of glasses of water per day"},
    {"type":"choices","options":[{"label":"Week","action":"week","selected":true},{"label":"Month","action":"month","selected":false}]},
    {"type":"card","children":[{"type":"text","text":"Today","style":"subtitle"},
        {"type":"item","title":"Morning glass","supporting":"7:30","trailing":"Done","action":"morning","checked":true},
        {"type":"item","title":"Afternoon glass","supporting":"Not yet","action":"afternoon","checked":false}]},
    {"type":"input","label":"Note","action":"note","value":""},
    {"type":"row","children":[{"type":"button","label":"Cancel","action":"cancel","style":"text"},
        {"type":"button","label":"Details","action":"details","style":"secondary"},{"type":"button","label":"Save","action":"save"}]}]}"""

private const val SAMPLE_CHART = """{"type":"vector","width":240,"height":100,"shapes":[
    {"id":"panel","type":"rect","x":0,"y":0,"width":240,"height":100,"rx":14,"fill":"surfaceContainerHighest","stroke":"transparent"},
    {"id":"mon","type":"rect","x":24,"y":50,"width":28,"height":34,"rx":6,"fill":"primary"},
    {"id":"tue","type":"rect","x":66,"y":30,"width":28,"height":54,"rx":6,"fill":"primaryContainer"},
    {"id":"wed","type":"rect","x":108,"y":18,"width":28,"height":66,"rx":6,"fill":"secondary"},
    {"id":"thu","type":"rect","x":150,"y":40,"width":28,"height":44,"rx":6,"fill":"tertiary"},
    {"id":"fri","type":"rect","x":192,"y":60,"width":28,"height":24,"rx":6,"fill":"tertiaryContainer"},
    {"id":"base","type":"line","x1":16,"y1":86,"x2":224,"y2":86,"stroke":"outline","strokeWidth":2}]}"""
