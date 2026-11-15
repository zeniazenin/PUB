package app.pub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.pub.HostModel
import app.pub.builder.BuilderModel
import app.pub.core.ProjectBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Content for the host's scrollable builder screen; account/model controls live in Settings. */
@Composable
fun BuilderScreen(
    builder: BuilderModel,
    host: HostModel,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onGenerate: (String) -> Unit = {builder.generate(it)},
    onPreview: (ProjectBundle) -> Unit
) {
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    // The generation context includes a hardware inventory scan; build it off the main thread, then start.
    val scope=rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    val startWithContext: ((String)->Unit)->Unit = { start ->
        focus.clearFocus();keyboard?.hide()
        if(!preparing) {
            preparing=true
            val editing=builder.editingProject?.id
            scope.launch {
                val context=withContext(Dispatchers.IO) { runCatching { host.generationContext(editing) } }
                preparing=false
                context.onSuccess(start)
            }
        }
    }
    Text(builder.editingProject?.let { "Edit ${it.bundle.name}" } ?: "Describe it. Make it yours.", style = MaterialTheme.typography.headlineSmall)
    Text(if (builder.editingProject != null) "Describe what to change. PUB uses the current app code as context. Preview before saving; your existing data and photos stay on your phone." else "Tell PUB what you need, try the result, and keep it on your phone.")

    TextButton(onClick=onHistory,modifier=Modifier.testTag("builder-history")) { Text("Prompt history") }

    if (builder.account == null) {
        Text("Connect your ChatGPT account in Settings to generate apps. You can write or dictate your idea now.")
        OutlinedButton(onClick = onSettings) { Text("Open Settings") }
    } else {
        val selectedName = builder.models.find { it.id == builder.selectedModel }?.name
        Text(
            if (selectedName != null) "Using $selectedName · ChatGPT plan"
            else "ChatGPT connected · waiting for available models",
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = onSettings) { Text("Change model in Settings") }
    }

    OutlinedTextField(
        value = builder.prompt,
        onValueChange = { builder.prompt = it.take(8000) },
        label = { Text(if (builder.editingProject != null) "What would you like to change?" else "What would you like to build?") },
        placeholder = { Text(if (builder.editingProject != null) "Add a reset button and make the result easier to read" else "A tip calculator with bill amount, tip percentage, and number of people") },
        supportingText = { Text("${builder.prompt.length} / 8,000 characters") },
        minLines = 4,
        maxLines = 10,
        modifier = Modifier.fillMaxWidth().testTag("app-instructions"),
        enabled = builder.busy == null && builder.capturePending == null && !builder.buildInProgress
    )
    VoicePromptButton(
        prompt = builder.prompt,
        onPromptChange = { builder.prompt = it },
        enabled = builder.busy == null && builder.capturePending == null && !builder.buildInProgress,
        globallyBlocked = host.globalBlocked
    )
    Button(
        onClick = { startWithContext(onGenerate) },
        enabled = builder.account != null && builder.selectedModel.isNotBlank() && !preparing &&
            builder.prompt.isNotBlank() && builder.busy == null && builder.capturePending == null && !builder.buildInProgress,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (builder.editingProject != null) "Generate changes" else "Generate app") }

    if (builder.imageToolsUnavailable) Text("Native illustrations and icons are available. PNG generation is unavailable with this ChatGPT connection.", style=MaterialTheme.typography.bodySmall)
    if(builder.buildInProgress) Text("You can switch apps. PUB will notify you when the build is ready.",style=MaterialTheme.typography.bodySmall)
    val notificationContext=androidx.compose.ui.platform.LocalContext.current
    if(android.os.Build.VERSION.SDK_INT>=33 && notificationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
        TextButton(onClick={notificationContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,notificationContext.packageName))}) {Text("Enable build notifications")}
    }
    BuilderStatus(builder)

    builder.visualReview?.let {
        Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Visual review",style=MaterialTheme.typography.titleMedium)
            Text(it)
        } }
    }
    builder.visualReviewError?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
    if(builder.hasBuildResult && builder.draft==null) TextButton(onClick=builder::clearDraft,enabled=builder.busy==null) {Text("Dismiss build result")}
    builder.draft?.let { draft ->
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(draft.name, style = MaterialTheme.typography.titleLarge)
                Text(draft.description)
                if (!draft.assets.isEmpty) Text("Graphics: ${draft.assets.size()} local assets")
                Text("Requested access: " + draft.capabilities.joinToString().ifBlank { "none" })
                Button(onClick = { onPreview(draft) }, enabled = builder.busy == null && builder.capturePending == null && !builder.buildInProgress) { Text("Preview app") }
                if(builder.visualReviewError!=null) TextButton(onClick={startWithContext(builder::retryVisualReview)},enabled=builder.busy==null && !preparing) {Text("Retry visual review")}
                TextButton(onClick = builder::clearDraft, enabled = builder.busy == null && builder.capturePending == null && !builder.buildInProgress) { Text("Discard draft") }
            }
        }
    }
}

/** Shared operation feedback for both the builder and account Settings. */
@Composable
fun BuilderStatus(builder: BuilderModel) {
    builder.busy?.let {
        Text(it)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        TextButton(onClick = builder::cancel) { Text("Cancel") }
    }
    if(builder.buildInProgress && builder.busy==null) {
        Text("Checking the preview in the background...")
        LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        TextButton(onClick=builder::cancel) {Text("Cancel")}
    }
    builder.message?.let { Text(it) }
}
