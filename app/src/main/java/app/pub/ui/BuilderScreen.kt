package app.pub.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.core.net.toUri
import app.pub.HostModel
import app.pub.builder.BuilderModel
import app.pub.core.AppSpec
import app.pub.core.AppTestSuite
import app.pub.core.ChecksReport
import app.pub.core.ContentReport
import app.pub.core.ProjectBundle
import app.pub.core.ResearchBrief
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
    var reporting by remember { mutableStateOf<ReportTarget?>(null) }
    reporting?.let { ReportDialog(it) { reporting = null } }
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

    // What the next edit builds on. With a draft, the draft's own research and checks are shown below instead.
    if (builder.editingProject != null && builder.draft == null) {
        builder.editingSpec?.let { Text(specNote(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("edit-spec-note")) }
            ?: builder.editingSpecNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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

    builder.researchBrief?.let { brief -> ResearchCard(brief, onReport = { reporting = ReportTarget.research(brief) }) }
    builder.researchNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    builder.uiPlan?.let { UiPlanCard(it) }
    builder.uiPlanNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppIconTile(draft.name, draft.icon?.let { draft.assets[it]?.asJsonObject }, size = 64.dp)
                    Text(draft.name, style = MaterialTheme.typography.titleLarge)
                }
                Text(draft.description)
                if (!draft.assets.isEmpty) Text("Graphics: ${draft.assets.size()} local assets")
                Text("Requested access: " + draft.capabilities.joinToString().ifBlank { "none" })
                BehaviorChecksSummary(builder.draftTests, builder.checksReport, builder.checksError,
                    running = builder.busy != null || builder.buildInProgress)
                Button(onClick = { onPreview(draft) }, enabled = builder.busy == null && builder.capturePending == null && !builder.buildInProgress) { Text("Preview app") }
                if(builder.visualReviewError!=null) TextButton(onClick={startWithContext(builder::retryVisualReview)},enabled=builder.busy==null && !preparing) {Text("Retry visual review")}
                TextButton(onClick = builder::clearDraft, enabled = builder.busy == null && builder.capturePending == null && !builder.buildInProgress) { Text("Discard draft") }
                TextButton(onClick = { reporting = ReportTarget.app(ContentReport.Subject.BUILD, draft) }, modifier = Modifier.testTag("report-build")) { Text("Report content") }
            }
        }
    }
}

/** One line for an edit that builds on the app's stored spec (its original request, research and checks). */
internal fun specNote(spec: AppSpec): String {
    val count = spec.testCount()
    val checks = "$count behavior ${if (count == 1) "check" else "checks"}"
    return when {
        spec.brief != null && count > 0 -> "Uses this app's saved research and $checks"
        spec.brief != null -> "Uses this app's saved research"
        count > 0 -> "Uses this app's $checks"
        else -> "Uses this app's original request"
    }
}

/** PUB's own sandboxed behavior checks for the draft. Failing checks are behind a toggle to keep the card compact. */
@Composable
private fun BehaviorChecksSummary(tests: AppTestSuite?, report: ChecksReport?, error: String?, running: Boolean) {
    var expanded by remember(report) { mutableStateOf(false) }
    when {
        report != null -> {
            Text("Behavior checks: ${report.passed} of ${report.total} passed", modifier = Modifier.testTag("behavior-checks"))
            val failures = report.failures()
            if (failures.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Show details") }
                if (expanded) failures.forEach { Text("• ${it.name}: ${it.detail}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        tests == null -> {}
        tests.isEmpty -> Text("No behavior checks were provided with this build.", style = MaterialTheme.typography.bodySmall)
        !running -> Text("Behavior checks haven't run on this draft yet.", style = MaterialTheme.typography.bodySmall)
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

private const val RESEARCH_PREVIEW_ITEMS = 6

/**
 * What research found and how it shaped the plan. Web results are always shown with their sources as
 * prominent links; only https links are shown, and they open outside PUB.
 */
@Composable
internal fun ResearchCard(brief: ResearchBrief, onReport: (() -> Unit)? = null) {
    var expanded by rememberSaveable(brief.summary) { mutableStateOf(false) }
    val included = brief.features.filter { it.feasibleInPub && it.priority != "could" }
    val impossible = brief.features.filter { !it.feasibleInPub }
    val sources = brief.sources.filter { it.url.startsWith("https://") }
    val longest = maxOf(included.size, impossible.size, brief.assumptions.size, sources.size)
    val limit = if (expanded) Int.MAX_VALUE else RESEARCH_PREVIEW_ITEMS
    Card(Modifier.fillMaxWidth().testTag("research-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Research", style = MaterialTheme.typography.titleMedium)
            Text(
                if (brief.web) "Researched on the web" else "From the model's knowledge — not checked against the web",
                style = MaterialTheme.typography.labelLarge,
                color = if (brief.web) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(brief.summary)
            ResearchList("Included features", included.map { if (it.priority == "must") it.title else "${it.title} (if it fits)" }, limit)
            ResearchList("Not possible in PUB", impossible.map { "${it.title} — ${it.notes}" }, limit)
            ResearchList("Assumptions", brief.assumptions, limit)
            if (sources.isNotEmpty()) {
                Text("Sources", style = MaterialTheme.typography.titleSmall)
                sources.take(limit).forEach { SourceLink(it) }
            }
            if (longest > RESEARCH_PREVIEW_ITEMS) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show all") }
            }
            onReport?.let { TextButton(onClick = it, modifier = Modifier.testTag("report-research")) { Text("Report research") } }
        }
    }
}

/** The build's UI plan: its structure and each screen's purpose; every element and state on request. */
@Composable
internal fun UiPlanCard(plan: app.pub.core.UiPlan) {
    var expanded by rememberSaveable(plan.layout) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("ui-plan-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("UI plan", style = MaterialTheme.typography.titleMedium)
            Text(plan.layout)
            plan.screens.forEach { screen ->
                Text(screen.name, style = MaterialTheme.typography.titleSmall)
                Text(screen.purpose, style = MaterialTheme.typography.bodyMedium)
                if (expanded) {
                    Text("First view: ${screen.firstView}", style = MaterialTheme.typography.bodySmall)
                    screen.elements.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    screen.states.forEach { Text("◦ $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (expanded) {
                ResearchList("Interactions", plan.interactions, Int.MAX_VALUE)
                ResearchList("Wording and formats", plan.content, Int.MAX_VALUE)
                ResearchList("Accessibility", plan.accessibility, Int.MAX_VALUE)
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show every screen in detail") }
        }
    }
}

@Composable
private fun ResearchList(title: String, items: List<String>, limit: Int) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall)
    items.take(limit).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    if (items.size > limit) Text("+${items.size - limit} more", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SourceLink(source: ResearchBrief.Source) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val hostColor = MaterialTheme.colorScheme.onSurfaceVariant
    val text = remember(source.url, source.title, linkColor, hostColor) {
        val host = source.url.toUri().host.orEmpty().removePrefix("www.")
        buildAnnotatedString {
            val link = LinkAnnotation.Url(source.url,
                TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline))) {
                openInBrowser(context, source.url)
            }
            withLink(link) { append(source.title) }
            if (host.isNotEmpty() && !source.title.equals(host, ignoreCase = true)) {
                withStyle(SpanStyle(color = hostColor)) { append("  ·  $host") }
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
}

/** Opens an https link in the user's browser app, never inside PUB; a missing browser is ignored. */
internal fun openInBrowser(context: Context, url: String) {
    if (!url.startsWith("https://")) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
