package app.pub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pub.builder.BuilderModel
import app.pub.builder.PromptHistoryEntry
import java.text.DateFormat
import java.util.Date

/** Review-only history; it never resubmits instructions or opens a saved app. */
@Composable
fun PromptHistoryScreen(builder: BuilderModel, projectId: String? = null, sessionId: String? = null) {
    require(!projectId.isNullOrBlank() || !sessionId.isNullOrBlank()) { "Prompt history requires an app or construction session" }
    LaunchedEffect(builder) { builder.refreshHistory() }
    val entries = builder.history.filter { if (projectId != null) it.projectId == projectId else it.sessionId == sessionId }
    val appTitle = entries.firstOrNull()?.title
        ?: builder.editingProject?.takeIf { it.id == projectId }?.bundle?.name
        ?: if (projectId == null) "This new app" else "This app"
    LazyColumn(Modifier.fillMaxSize().testTag("prompt-history-screen"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Prompt history", style = MaterialTheme.typography.headlineSmall)
            builder.historyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("For $appTitle", style = MaterialTheme.typography.titleMedium)
            Text("${entries.size} ${if (entries.size == 1) "request" else "requests"} · newest first", style = MaterialTheme.typography.labelMedium)
        }
        if (entries.isEmpty()) item {
            Text(if (builder.historyError != null) "History is unavailable." else "No recorded requests for this app yet.")
        }
        items(entries, key = { it.id }) { entry -> HistoryRequest(entry) }
    }
}

@Composable
private fun HistoryRequest(entry: PromptHistoryEntry) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val timestamp = remember(entry.createdAtMillis) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.createdAtMillis)) }
    OutlinedCard(Modifier.fillMaxWidth().testTag("history-${entry.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            Text("${if(entry.automatic) "Automatic visual repair" else if (entry.kind == "edit") "Edit" else "Create"} · ${statusLabel(entry)}", style = MaterialTheme.typography.labelLarge)
            Text(timestamp, style = MaterialTheme.typography.bodySmall)
            if (!expanded) Text(entry.prompt.replace('\n', ' ').take(180) + if (entry.prompt.length > 180) "…" else "", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide request" else "Review full request") }
            if (expanded) {
                Text("Submitted instructions", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(entry.prompt) }
                Text("Model: ${entry.model}", style = MaterialTheme.typography.bodySmall)
                Text("Request: ${entry.id}", style = MaterialTheme.typography.bodySmall)
                entry.review?.let { review ->
                    Text("AI screen review", style = MaterialTheme.typography.titleSmall)
                    Text("Advisory feedback for this request; review it before making changes.", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(review) }
                }
                if (entry.interrupted) Text("PUB closed before this request completed. No retry was sent automatically.", style = MaterialTheme.typography.bodySmall)
                if (entry.status == "ready") Text("The build completed. A ready record does not mean the app was saved or that a preview is still available.", style = MaterialTheme.typography.bodySmall)
                if (entry.projectId != null) Text("Linked to this app's history. The record remains if the app is later deleted.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
private fun statusLabel(entry: PromptHistoryEntry) = when (entry.status) {
    "building" -> "Building"
    "ready" -> "Ready to preview"
    "failed" -> "Failed"
    "cancelled" -> if (entry.interrupted) "Interrupted" else "Cancelled"
    "saved" -> "Saved"
    else -> entry.status
}
