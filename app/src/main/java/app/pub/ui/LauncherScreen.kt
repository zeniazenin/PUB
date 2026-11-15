package app.pub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.pub.projects.SavedProject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pub.HostModel
import app.pub.R

@Composable
fun LauncherBrand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The adaptive-icon artwork includes transparent safety margins. Enlarge it within
        // this clipped slot so the visible mark matches the header's intended logo size.
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.pub_icon_art), contentDescription = "PUB",
                modifier = Modifier.requiredSize(90.dp))
        }
        Text("Built for you.\nKept on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
    }
}

@Composable
fun LauncherScreen(host: HostModel, onEdit: (SavedProject) -> Unit, onDelete: (SavedProject) -> Unit, onCreate: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your apps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            host.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("launcher"),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            if (host.tunerVisible) item(key = "guitar-tuner") {
                val tuner = host.editableProject("guitar-tuner")
                LauncherTile("guitar-tuner", tuner.bundle.name, tuner = true, graphic = tuner.bundle.icon?.let { tuner.bundle.assets[it]?.asJsonObject }, onEdit = { onEdit(tuner) }, onDelete = { onDelete(tuner) }) { host.openProject(null) }
            }
            items(host.projects, key = { it.id }) { project ->
                LauncherTile(project.id, project.bundle.name, graphic = project.bundle.icon?.let { project.bundle.assets[it]?.asJsonObject }, onEdit = { onEdit(project) }, onDelete = { onDelete(project) }) { host.openProject(project) }
            }
        }
        Button(
            onClick = onCreate,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth().heightIn(min = 56.dp).testTag("create-app")
        ) { Text("+ Create app", style = MaterialTheme.typography.titleMedium) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherTile(id: String, name: String, tuner: Boolean = false, graphic: com.google.gson.JsonObject? = null, onEdit: () -> Unit, onDelete: () -> Unit, onOpen: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val palette = listOf(
        colors.primary,
        lerp(colors.primary, colors.tertiary, .4f),
        lerp(colors.primary, colors.onSurface, .28f),
        lerp(colors.primary, colors.tertiary, .75f)
    )
    val tint = palette[(id.hashCode() ushr 1) % palette.size]
    val iconBackground = lerp(colors.surface, tint, .15f)
    val calculator = name.contains("calc", ignoreCase = true) || name.contains("tip", ignoreCase = true)
    val words = name.trim().split(Regex("\\s+")).map { word -> word.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
    val monogram = if (words.size > 1) "${words.first().first()}${words.last().first()}" else words.firstOrNull()?.take(2) ?: "AP"
    Box {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .combinedClickable(role = Role.Button, onClickLabel = "Open $name", onLongClickLabel = "Edit or delete $name", onLongClick = { menuOpen = true }, onClick = onOpen)
            .testTag("app-$id").padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)).background(iconBackground), contentAlignment = Alignment.Center) {
            when {
                graphic != null -> NativeGraphic(graphic, "$name icon", modifier=Modifier.size(68.dp), maxDecodeSize=256)
                tuner -> TunerGlyph(tint, iconBackground)
                calculator -> CalculatorGlyph(tint)
                else -> Text(monogram.uppercase(), color = tint, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            }
        }
        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(text = { Text("Edit app") }, modifier = Modifier.testTag("edit-app-$id"), onClick = { menuOpen = false; onEdit() })
        DropdownMenuItem(text = { Text("Delete app") }, modifier = Modifier.testTag("delete-app-$id"), onClick = { menuOpen = false; onDelete() })
    }
    }
}

@Composable
private fun TunerGlyph(tint: Color, background: Color) {
    Canvas(Modifier.size(42.dp)) {
        val stroke = 2.5.dp.toPx()
        listOf(.22f to .34f, .5f to .68f, .78f to .43f).forEach { (x, knob) ->
            val center = Offset(size.width * x, size.height * knob)
            drawLine(tint, Offset(size.width * x, size.height * .12f), Offset(size.width * x, size.height * .88f), stroke, StrokeCap.Round)
            drawCircle(background, 4.5.dp.toPx(), center)
            drawCircle(tint, 4.5.dp.toPx(), center, style = Stroke(stroke))
        }
    }
}

@Composable
private fun CalculatorGlyph(tint: Color) {
    Canvas(Modifier.size(44.dp)) {
        val stroke = 2.2.dp.toPx()
        drawRoundRect(tint, Offset(size.width * .18f, size.height * .06f), Size(size.width * .64f, size.height * .88f), CornerRadius(5.dp.toPx()), style = Stroke(stroke))
        drawRoundRect(tint, Offset(size.width * .29f, size.height * .19f), Size(size.width * .42f, size.height * .16f), CornerRadius(1.5.dp.toPx()), style = Stroke(stroke))
        for (row in 0..2) for (column in 0..1) {
            drawCircle(tint, 1.8.dp.toPx(), Offset(size.width * (.35f + column * .3f), size.height * (.49f + row * .15f)))
        }
    }
}
