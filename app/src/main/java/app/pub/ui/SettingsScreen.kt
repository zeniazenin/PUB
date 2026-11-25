package app.pub.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pub.BuildConfig
import app.pub.HostModel
import app.pub.builder.BuilderModel
import app.pub.projects.SavedProject

/**
 * Settings as grouped sections of list rows, in the order people need them: the ChatGPT account and the model for
 * building, the style guide, access rules, saved apps, then help and tools.
 */
@Composable
fun SettingsScreen(
    host: HostModel,
    builder: BuilderModel,
    onAndroidSettings: () -> Unit,
    onHelp: () -> Unit,
    onHardwareCheck: () -> Unit,
    onInventory: () -> Unit,
    onStyle: () -> Unit,
    onDelete: (SavedProject) -> Unit
) {
    val context = LocalContext.current
    var modelsOpen by remember { mutableStateOf(false) }
    val modelName = builder.models.find { it.id == builder.selectedModel }?.name
    val colors = MaterialTheme.colorScheme
    val free = builder.busy == null && !builder.buildInProgress

    Column(
        Modifier.fillMaxSize().testTag("settings-screen").verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        PubSection("Account", "ChatGPT account") {
            if (builder.account == null) {
                Text("Connect your ChatGPT account to create apps using eligible plan usage. Your saved apps run locally.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                PubButton(onClick = builder::connect, enabled = free, modifier = Modifier.fillMaxWidth()) { Text("Continue with ChatGPT") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Eyebrow("Connected", color = colors.primary)
                        Text(builder.account.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PubTextButton(onClick = builder::connect, enabled = free) { Text("Reconnect") }
                    PubTextButton(onClick = builder::disconnect, enabled = free) { Text("Disconnect") }
                }
                HorizontalDivider(color = colors.outlineVariant)
                Box {
                    PubListRow("Model for building apps",
                        if (modelName == null) "Choose a model" else if (builder.automaticModelSelection) "Automatic · $modelName" else modelName,
                        Modifier.testTag("model-selector"), enabled = builder.models.isNotEmpty() && free) { modelsOpen = true }
                    DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Automatic · prefer Astra") },
                            trailingIcon = { if (builder.automaticModelSelection) Icon(PubIcons.Check, null, Modifier.size(18.dp)) },
                            onClick = { builder.useAutomaticModel(); modelsOpen = false },
                            modifier = Modifier.testTag("automatic-model")
                        )
                        HorizontalDivider()
                        builder.models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.name) },
                                trailingIcon = { if (!builder.automaticModelSelection && builder.selectedModel == model.id) Icon(PubIcons.Check, null, Modifier.size(18.dp)) },
                                onClick = { builder.selectModel(model.id); modelsOpen = false }
                            )
                        }
                    }
                }
                Text(builder.modelSelectionNote, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                HorizontalDivider(color = colors.outlineVariant)
                PubListRow("Refresh available models", null, enabled = free) { builder.refreshModels() }
                HorizontalDivider(color = colors.outlineVariant)
                PubListRow("Manage ChatGPT usage", "Building uses your ChatGPT plan") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://chatgpt.com/settings/usage".toUri())) }
                }
                if (builder.imageToolsUnavailable) Text("Native illustrations and icons are available. PNG generation is unavailable with this ChatGPT connection.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            BuilderStatus(builder)
        }

        PubSection("Style", "Style guide") {
            Text("One style guide for PUB and all your apps: light or dark, colours, type pairing and corners. You can turn it off for any app; that app then uses PUB's default style.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            val optedOut = host.projects.count { !host.usesStyleGuide(it.id) }
            HorizontalDivider(color = colors.outlineVariant)
            PubListRow("Edit style guide", if (optedOut == 0) "Every app follows it" else if (optedOut == 1) "1 app uses PUB's default style instead" else "$optedOut apps use PUB's default style instead",
                Modifier.testTag("style-guide")) { onStyle() }
            HorizontalDivider(color = colors.outlineVariant)
        }

        // Its rows emit straight into this column; keep them at the sections' inner spacing.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { HardwareAccessSettings(host,onAndroidSettings) }

        if (host.projects.isNotEmpty()) {
            PubSection("Library", "Saved apps") {
                HorizontalDivider(color = colors.outlineVariant)
                host.projects.forEach { project ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(project.bundle.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        PubTextButton(onClick = { onDelete(project) }, colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) { Text("Delete") }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
        }

        PubSection("About", "Help and tools") {
            HorizontalDivider(color = colors.outlineVariant)
            PubListRow("Help & guide", "How building, privacy and hardware work", Modifier.testTag("help-guide")) { onHelp() }
            HorizontalDivider(color = colors.outlineVariant)
            PubListRow("Privacy policy", "Opens in your browser", Modifier.testTag("privacy-policy")) { openInBrowser(context, BuildConfig.PRIVACY_POLICY_URL) }
            HorizontalDivider(color = colors.outlineVariant)
            PubListRow("Hardware inventory", "Cameras, sensors and accessories Android reports", Modifier.testTag("hardware-inventory")) { onInventory() }
            HorizontalDivider(color = colors.outlineVariant)
            PubListRow("Hardware check", "Try PUB's hardware access on this phone", Modifier.testTag("hardware-check")) { onHardwareCheck() }
            HorizontalDivider(color = colors.outlineVariant)
        }
        host.failure?.let { Text(it, color = colors.error) }
    }
}
