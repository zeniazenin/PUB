package app.pub.ui

import androidx.compose.foundation.Image
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
private fun LauncherTile(id: String, name: String, graphic: com.google.gson.JsonObject? = null, onEdit: () -> Unit, onDelete: () -> Unit, onOpen: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .combinedClickable(role = Role.Button, onClickLabel = "Open $name", onLongClickLabel = "Edit or delete $name", onLongClick = { menuOpen = true }, onClick = onOpen)
            .testTag("app-$id").padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIconTile(name, graphic)
        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(text = { Text("Edit app") }, modifier = Modifier.testTag("edit-app-$id"), onClick = { menuOpen = false; onEdit() })
        DropdownMenuItem(text = { Text("Delete app") }, modifier = Modifier.testTag("delete-app-$id"), onClick = { menuOpen = false; onDelete() })
    }
    }
}

