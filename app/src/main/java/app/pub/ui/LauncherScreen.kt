package app.pub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.pub.projects.SavedProject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pub.HostModel
import app.pub.R

/** PUB's mark and wordmark, with its promise in small Geist Mono. */
@Composable
fun LauncherBrand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // The pixel mark (the launcher icon's foreground) on its own dark plate, in light and dark themes alike.
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(colorResource(R.color.icon_background)),
            contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.pub_mark), contentDescription = "PUB", modifier = Modifier.size(26.dp))
        }
        Column {
            Text("PUB", style = MaterialTheme.typography.titleLarge)
            Eyebrow("Built for you · kept on your phone")
        }
    }
}

/**
 * Your apps: a grid sized from the screen's width (about 88 dp per column, so four on a typical phone), with tiles and
 * glyphs proportional to their column, and New app pinned below.
 */
@Composable
fun LauncherScreen(host: HostModel, onEdit: (SavedProject) -> Unit, onDelete: (SavedProject) -> Unit, onReport: (SavedProject) -> Unit,
                   onRedesignIcon: ((SavedProject) -> Unit)? = null, canRedesignIcon: (SavedProject) -> Boolean = { true }, onCreate: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val side = 20.dp
            val columns = ((maxWidth - side * 2) / 88.dp).toInt().coerceIn(3, 6)
            val cell = (maxWidth - side * 2 - 8.dp * (columns - 1)) / columns
            val tile = (cell * .7f).coerceIn(48.dp, 64.dp)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().testTag("launcher"),
                contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Your apps", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                        Eyebrow(when (val count = host.projects.size) { 0 -> "No apps yet"; 1 -> "1 app on this phone"; else -> "$count apps on this phone" })
                        host.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                items(host.projects, key = { it.id }) { project ->
                    LauncherTile(project.id, project.bundle.name, graphic = project.bundle.icon?.let { project.bundle.assets[it]?.asJsonObject }, tile = tile,
                        followsStyle = host.usesStyleGuide(project.id), onFollowStyle = { host.changeUsesStyleGuide(project.id, it) },
                        onEdit = { onEdit(project) }, onDelete = { onDelete(project) }, onReport = { onReport(project) },
                        onRedesignIcon = onRedesignIcon?.takeIf { canRedesignIcon(project) }?.let { redesign -> { redesign(project) } }) { host.openProject(project) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PubButton(
            onClick = onCreate,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().heightIn(min = 52.dp).testTag("create-app")
        ) {
            Icon(PubIcons.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("New app")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherTile(id: String, name: String, graphic: com.google.gson.JsonObject? = null, tile: Dp, followsStyle: Boolean, onFollowStyle: (Boolean) -> Unit,
                         onEdit: () -> Unit, onDelete: () -> Unit, onReport: () -> Unit, onRedesignIcon: (() -> Unit)?, onOpen: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    Column(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .combinedClickable(role = Role.Button, onClickLabel = "Open $name", onLongClickLabel = "Edit, style, delete or report $name", onLongClick = { menuOpen = true }, onClick = onOpen)
            .testTag("app-$id").padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppIconTile(name, graphic, size = tile)
        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp))
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(text = { Text("Edit app") }, leadingIcon = { Icon(PubIcons.Edit, null, Modifier.size(18.dp)) },
            modifier = Modifier.testTag("edit-app-$id"), onClick = { menuOpen = false; onEdit() })
        // Redesign icon is absent while it cannot run (a build is running, or this app has an unsaved edit draft).
        onRedesignIcon?.let { redesign ->
            DropdownMenuItem(text = { Text("Redesign icon") }, leadingIcon = { Icon(PubIcons.Spark, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("redesign-icon-$id"), onClick = { menuOpen = false; redesign() })
        }
        DropdownMenuItem(text = { Text("Use style guide") }, trailingIcon = { if (followsStyle) Icon(PubIcons.Check, null, Modifier.size(18.dp)) },
            modifier = Modifier.testTag("style-app-menu-$id").semantics { stateDescription = if (followsStyle) "On" else "Off" },
            onClick = { menuOpen = false; onFollowStyle(!followsStyle) })
        DropdownMenuItem(text = { Text("Delete app") }, modifier = Modifier.testTag("delete-app-$id"), onClick = { menuOpen = false; onDelete() })
        DropdownMenuItem(text = { Text("Report app") }, modifier = Modifier.testTag("report-app-$id"), onClick = { menuOpen = false; onReport() })
    }
    }
}
