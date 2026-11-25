package app.pub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pub.builder.BuilderController
import app.pub.builder.BuilderModel
import app.pub.builder.IconProposal
import app.pub.projects.SavedProject

/**
 * Redesign icon for a saved app, in four states: what it does and costs (nothing is sent until Design icon), designing
 * (with Cancel), the current and new icons side by side (Use new icon, Try again, Keep current), or why it failed.
 * [onUse] stores the proposal; [saving] and [saveError] report that.
 */
@Composable
fun IconRedesignDialog(project: SavedProject, builder: BuilderModel, saving: Boolean, saveError: String?,
                       onUse: (IconProposal) -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    var started by remember(project.id) { mutableStateOf(false) }
    val designing = builder.busy == BuilderController.DESIGNING_ICON
    val proposal = builder.iconProposal?.takeIf { it.projectId == project.id }
    val error = builder.iconDesignError ?: saveError
    val current = project.bundle.icon?.let { project.bundle.assets[it]?.asJsonObject }
    val close = { if (designing) builder.cancel(); builder.clearIconProposal(); onClose() }
    AlertDialog(
        onDismissRequest = { if (!saving) close() },
        modifier = Modifier.testTag("icon-redesign"),
        title = { Text("Redesign icon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    builder.account == null -> Text("Connect your ChatGPT account in Settings to design icons.", style = MaterialTheme.typography.bodyMedium)
                    designing -> {
                        Text("Designing a new icon for ${project.bundle.name}…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    }
                    proposal != null -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AppIconTile(project.bundle.name, current, size = 64.dp); Eyebrow("Current")
                            }
                            Icon(PubIcons.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.testTag("icon-proposal")) {
                                AppIconTile(project.bundle.name, proposal.icon, size = 64.dp); Eyebrow("New", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text("Only the icon changes; the app, its data and files stay as they are.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Text("PUB designs a new minimal icon from the app's name and description (and its saved research, if any). " +
                        "This sends them to ChatGPT and uses one request of your plan, or two when the first design needs simplifying. " +
                        "You choose whether to use it.", style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                builder.account == null -> PubButton(onClick = { close(); onSettings() }) { Text("Open Settings") }
                designing -> {}
                proposal != null -> PubButton(onClick = { onUse(proposal) }, enabled = !saving, modifier = Modifier.testTag("use-icon")) {
                    Text(if (saving) "Saving…" else "Use new icon")
                }
                else -> PubButton(onClick = { started = true; builder.redesignIcon(project) }, enabled = builder.busy == null && !builder.buildInProgress,
                    modifier = Modifier.testTag("design-icon")) { Text(if (started || error != null) "Try again" else "Design icon") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (proposal != null) PubTextButton(onClick = { builder.redesignIcon(project) }, enabled = !saving, modifier = Modifier.testTag("redesign-again")) { Text("Try again") }
                PubTextButton(onClick = close, enabled = !saving, modifier = Modifier.testTag("keep-icon")) {
                    Text(when { designing -> "Cancel"; proposal != null -> "Keep current"; else -> "Close" })
                }
            }
        }
    )
}
