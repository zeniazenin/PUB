package app.pub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.pub.BuildConfig
import app.pub.core.ContentReport
import app.pub.core.ProjectBundle
import app.pub.core.ResearchBrief
import app.pub.reports.ReportSender
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a report is about, and how to build it from the user's reason, details and choice to include the content. */
class ReportTarget private constructor(
    val title: String,
    val includeLabel: String,
    private val build: (ContentReport.Reason, String, Boolean) -> JsonObject
) {
    fun report(reason: ContentReport.Reason, details: String, include: Boolean): JsonObject = build(reason, details, include)

    companion object {
        private val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        /** A build's draft ([ContentReport.Subject.BUILD]) or a saved app ([ContentReport.Subject.APP]). */
        fun app(subject: ContentReport.Subject, bundle: ProjectBundle) = ReportTarget(
            title = if (subject == ContentReport.Subject.BUILD) "Report this build" else "Report ${bundle.name}",
            includeLabel = "Include the app's name, description and code"
        ) { reason, details, include -> ContentReport.app(subject, reason, details, version, bundle.takeIf { include }) }

        fun research(brief: ResearchBrief) = ReportTarget(
            title = "Report this research",
            includeLabel = "Include the research summary and sources"
        ) { reason, details, include -> ContentReport.research(reason, details, version, brief.takeIf { include }) }
    }
}

/**
 * The in-app report form required by Google Play's AI-Generated Content policy: the user reports offensive or
 * otherwise problematic output to PUB's developer without leaving the app.
 */
@Composable
fun ReportDialog(target: ReportTarget, onDismiss: () -> Unit) {
    var reasonId by rememberSaveable(target) { mutableStateOf<String?>(null) }
    var details by rememberSaveable(target) { mutableStateOf("") }
    var include by rememberSaveable(target) { mutableStateOf(true) }
    var sending by remember(target) { mutableStateOf(false) }
    var sent by rememberSaveable(target) { mutableStateOf(false) }
    var failed by remember(target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reason = ContentReport.Reason.entries.find { it.id == reasonId }

    if (sent) {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("report-dialog"),
            title = { Text("Report sent") },
            text = { Text("Thank you. PUB's developer reviews every report.") },
            confirmButton = { PubTextButton(onClick = onDismiss, modifier = Modifier.testTag("report-done")) { Text("Done") } }
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        modifier = Modifier.testTag("report-dialog"),
        title = { Text(target.title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What's wrong? Reports go to PUB's developer, not to OpenAI.")
                Column(Modifier.selectableGroup()) {
                    ContentReport.Reason.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("report-reason-${option.id}")
                                .selectable(selected = option == reason, enabled = !sending, role = Role.RadioButton) { reasonId = option.id },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option == reason, onClick = null, enabled = !sending)
                            Text(option.label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(ContentReport.MAX_DETAILS) },
                    label = { Text("Details (optional)") },
                    supportingText = { Text("${details.length} / 2,000 characters") },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().testTag("report-details")
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("report-include")
                        .toggleable(value = include, enabled = !sending, role = Role.Checkbox) { include = it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = include, onCheckedChange = null, enabled = !sending)
                    Text(target.includeLabel, Modifier.padding(start = 12.dp))
                }
                Text("Reports never include your phone details, account, saved data, files or photos.",
                    style = MaterialTheme.typography.bodySmall)
                when {
                    !ReportSender.available -> Text("Reporting isn't set up in this build of PUB.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    failed -> Text("Couldn't send the report. Check your connection and try again.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    sending -> Text("Sending…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            PubTextButton(
                onClick = {
                    val chosen = reason ?: return@PubTextButton
                    sending = true; failed = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ReportSender.send(target.report(chosen, details, include)) }
                        sending = false
                        if (ok) sent = true else failed = true
                    }
                },
                enabled = reason != null && !sending && ReportSender.available,
                modifier = Modifier.testTag("report-send")
            ) { Text("Send report") }
        },
        dismissButton = { PubTextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") } }
    )
}
