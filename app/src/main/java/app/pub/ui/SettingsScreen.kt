package app.pub.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pub.BuildConfig
import app.pub.HostModel
import app.pub.builder.BuilderModel
import app.pub.core.PermissionPolicy.Rule
import app.pub.projects.SavedProject

@OptIn(ExperimentalLayoutApi::class)
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

    Column(
        Modifier.fillMaxSize().testTag("settings-screen").verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth().testTag("help-guide")) {
            Text("Help & guide")
        }
        OutlinedButton(onClick = { openInBrowser(context, BuildConfig.PRIVACY_POLICY_URL) }, modifier = Modifier.fillMaxWidth().testTag("privacy-policy")) {
            Text("Privacy policy")
        }
        OutlinedButton(onClick = onInventory, modifier = Modifier.fillMaxWidth().testTag("hardware-inventory")) {Text("Hardware inventory")}
        OutlinedButton(onClick = onHardwareCheck, modifier = Modifier.fillMaxWidth().testTag("hardware-check")) {Text("Hardware check")}
        Text("ChatGPT account", style = MaterialTheme.typography.titleLarge)
        if (builder.account == null) {
            Text("Connect your ChatGPT account to create apps using eligible plan usage. Your saved apps run locally.")
            Button(onClick = builder::connect, enabled = builder.busy == null && !builder.buildInProgress, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with ChatGPT")
            }
        } else {
            Text("Connected · ${builder.account.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = builder::connect, enabled = builder.busy == null && !builder.buildInProgress) { Text("Reconnect") }
                TextButton(onClick = builder::disconnect, enabled = builder.busy == null && !builder.buildInProgress) { Text("Disconnect") }
            }
            Text("Model for building apps", style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(
                    onClick = { modelsOpen = true },
                    enabled = builder.models.isNotEmpty() && builder.busy == null && !builder.buildInProgress,
                    modifier = Modifier.fillMaxWidth().testTag("model-selector")
                ) {
                    Text(if (modelName == null) "Choose a model" else if (builder.automaticModelSelection) "Automatic · $modelName" else modelName)
                }
                DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Automatic · prefer Astra") },
                        trailingIcon = { if (builder.automaticModelSelection) Text("✓") },
                        onClick = { builder.useAutomaticModel(); modelsOpen = false },
                        modifier = Modifier.testTag("automatic-model")
                    )
                    HorizontalDivider()
                    builder.models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.name) },
                            trailingIcon = { if (!builder.automaticModelSelection && builder.selectedModel == model.id) Text("✓") },
                            onClick = { builder.selectModel(model.id); modelsOpen = false }
                        )
                    }
                }
            }
            Text(builder.modelSelectionNote, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = builder::refreshModels, enabled = builder.busy == null && !builder.buildInProgress) { Text("Refresh available models") }
            Text("Using ChatGPT plan", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }
            }) { Text("Manage ChatGPT usage") }
        }
        BuilderStatus(builder)

        HorizontalDivider()
        Text("Style", style = MaterialTheme.typography.titleLarge)
        Text("One style guide for PUB and all your apps: light or dark, colours, corners and font. You can turn it off for any app; that app then uses PUB's default style.")
        val optedOut = host.projects.count { !host.usesStyleGuide(it.id) }
        if (optedOut > 0) Text(if (optedOut == 1) "1 app uses PUB's default style instead." else "$optedOut apps use PUB's default style instead.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onStyle, modifier = Modifier.fillMaxWidth().testTag("style-guide")) { Text("Edit style guide") }

        HorizontalDivider()
        HardwareAccessSettings(host,onAndroidSettings)

        if (host.projects.isNotEmpty()) {
            HorizontalDivider()
            Text("Saved apps", style = MaterialTheme.typography.titleLarge)
            host.projects.forEach { project ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(project.bundle.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onDelete(project) }) { Text("Delete") }
                }
            }
        }
        host.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    }
}
