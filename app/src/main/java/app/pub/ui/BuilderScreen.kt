package app.pub.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.pub.HostModel
import app.pub.builder.BuilderModel
import app.pub.builder.VisualReview
import app.pub.core.AppSpec
import app.pub.core.AppTestSuite
import app.pub.core.ChecksReport
import app.pub.core.ContentReport
import app.pub.core.ProjectBundle
import app.pub.core.ResearchBrief
import app.pub.hardware.HardwareInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The builder's three steps, in the order a user goes through them. */
enum class BuildStep(val label: String) { DESCRIBE("Describe"), REVIEW("Review"), PUBLISH("Publish") }

/** The phases of a build, as BuilderController reports them in its busy text. */
internal val BUILD_PHASES = listOf("Research", "Plan screens", "Build", "Test behavior", "Check the screen")

/**
 * Which phase of [BUILD_PHASES] a build is in, from the controller's busy text (the same labels the build notification
 * reads); null when no build is running. [capturing] is a draft waiting for its screenshot.
 */
internal fun buildPhase(busy: String?, buildInProgress: Boolean, capturing: Boolean): Int? {
    val text = busy?.lowercase()
    return when {
        text == null -> if (buildInProgress || capturing) 4 else null
        "research" in text -> 0
        "planning" in text -> 1
        "building your app" in text -> 2
        "testing" in text || "fixing app behavior" in text -> 3
        "checking screen" in text || "fixing visual" in text || "background review" in text || "rendering" in text -> 4
        buildInProgress || capturing -> 4
        else -> null
    }
}

/**
 * The builder in three steps:
 * 1. Describe: what to build (or, for a saved app, what to change), typed or spoken. While the first build runs, its
 *    progress replaces the description.
 * 2. Review: the draft, how its checks went, Try it, and Adjust (changes by prompt or voice, which build on the draft).
 *    How it was built (research, screen plan, checks, visual review) is folded away below.
 * 3. Publish: what saving does, then Save. Saving opens the app once, as Save in a preview does.
 * The step follows the builder's state, so returning from a notification lands on the right one; only going on from
 * Review to Publish is the user's choice, and it resets when the draft changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BuilderScreen(
    builder: BuilderModel,
    host: HostModel,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onGenerate: (String) -> Unit = {builder.generate(it)},
    /** Incremented to bring the change box into view, for example after Request changes in a preview. */
    revealChanges: Int = 0,
    onChangesRevealed: () -> Unit = {},
    onPublish: (ProjectBundle) -> Unit = {},
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
    val draft = builder.draft
    val phase = buildPhase(builder.busy, builder.buildInProgress, builder.capturePending != null)
    val building = phase != null
    val idle = builder.busy == null && builder.capturePending == null && !builder.buildInProgress
    var publishing by rememberSaveable(builder.draftHistoryId) { mutableStateOf(false) }
    val step = when { draft == null -> BuildStep.DESCRIBE; publishing && !building -> BuildStep.PUBLISH; else -> BuildStep.REVIEW }
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    val changeBox = remember { BringIntoViewRequester() }
    LaunchedEffect(revealChanges) {
        if (revealChanges > 0) {
            withFrameNanos {} // Once the screen has been laid out.
            changeBox.bringIntoView()
            onChangesRevealed()
        }
    }
    val canBuild = builder.account != null && builder.selectedModel.isNotBlank() && !preparing && builder.prompt.isNotBlank() && idle

    Column(Modifier.fillMaxSize()) {
        StepRail(step, building, editing = builder.editingProject != null, Modifier.padding(horizontal = 20.dp)) { target ->
            if (target == BuildStep.REVIEW && step == BuildStep.PUBLISH) publishing = false
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)) {
            when (step) {
                BuildStep.DESCRIBE -> DescribeStep(builder, host, phase, onSettings, onHistory, canBuild, changeBox)
                BuildStep.REVIEW -> ReviewStep(builder, host, draft!!, phase, canBuild, preparing, changeBox, onHistory, onSettings,
                    onApply = { startWithContext(onGenerate) },
                    onRetryReview = { startWithContext(builder::retryVisualReview) },
                    onReport = { reporting = it })
                BuildStep.PUBLISH -> PublishStep(builder, host, draft!!)
            }
        }
        ActionBar {
            when (step) {
                BuildStep.DESCRIBE -> if (building) PubOutlinedButton(onClick = builder::cancel, modifier = Modifier.fillMaxWidth().testTag("cancel-build")) { Text("Cancel build") }
                    else PubButton(onClick = { startWithContext(onGenerate) }, enabled = canBuild, modifier = Modifier.fillMaxWidth().testTag("generate")) {
                        Icon(PubIcons.Spark, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                        Text(if (builder.editingProject != null) "Build changes" else "Build app")
                    }
                BuildStep.REVIEW -> if (building) PubOutlinedButton(onClick = builder::cancel, modifier = Modifier.fillMaxWidth().testTag("cancel-build")) { Text("Cancel") }
                    else {
                        PubOutlinedButton(onClick = { onPreview(draft!!) }, enabled = idle, modifier = Modifier.weight(1f).testTag("try-app")) {
                            Icon(PubIcons.Play, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Try it")
                        }
                        PubButton(onClick = { publishing = true }, enabled = idle, modifier = Modifier.weight(1.4f).testTag("continue-publish")) {
                            Text("Continue"); Spacer(Modifier.width(8.dp)); Icon(PubIcons.ChevronRight, null, Modifier.size(16.dp))
                        }
                    }
                BuildStep.PUBLISH -> {
                    PubTextButton(onClick = { publishing = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                    PubButton(onClick = { onPublish(draft!!) }, enabled = idle, modifier = Modifier.weight(1f).testTag("publish")) {
                        Icon(PubIcons.Publish, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                        Text(if (builder.editingProject != null) "Save changes" else "Add to Your apps")
                    }
                }
            }
        }
    }
}

/** The three steps as a numbered rail: done steps are checked, the current one is lit, later ones are quiet. */
@Composable
private fun StepRail(current: BuildStep, building: Boolean, editing: Boolean, modifier: Modifier = Modifier, onSelect: (BuildStep) -> Unit) {
    val colors = MaterialTheme.colorScheme
    // When three labels do not fit (a narrow screen or large text), only the current step keeps its label.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    val compact = maxWidth / LocalDensity.current.fontScale < 340.dp
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp).testTag("step-rail"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BuildStep.entries.forEachIndexed { index, step ->
            val done = step.ordinal < current.ordinal
            val active = step == current
            val label = if (step == BuildStep.PUBLISH && editing) "Save" else step.label
            Row(Modifier.clip(MaterialTheme.shapes.extraSmall).then(if (done) Modifier.clickable { onSelect(step) } else Modifier)
                .semantics { stateDescription = when { active -> "Current step"; done -> "Done"; else -> "Next" } },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(when { active -> colors.primary; done -> colors.primaryContainer; else -> colors.surfaceContainerHigh }),
                    contentAlignment = Alignment.Center) {
                    if (done) Icon(PubIcons.Check, null, Modifier.size(12.dp), tint = colors.onPrimaryContainer)
                    else Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = if (active) colors.onPrimary else colors.onSurfaceVariant)
                }
                if (active || !compact) Text((if (active && building) "$label…" else label).uppercase(), style = MaterialTheme.typography.labelMedium,
                    color = if (active) colors.onSurface else colors.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
            if (index < BuildStep.entries.size - 1) HorizontalDivider(Modifier.weight(1f), color = if (done) colors.primary.copy(alpha = .5f) else colors.outlineVariant)
        }
    }
    }
}

/** A label column's width that grows with the font scale, so labels such as "ACCESS" never break mid-word. */
@Composable
private fun labelColumn(base: androidx.compose.ui.unit.Dp) = base * LocalDensity.current.fontScale.coerceAtLeast(1f)

/** The step's main actions, pinned under the content above a hairline. */
@Composable
private fun ActionBar(content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Step 1: what to build or change. While the first build runs, its progress takes the description's place. */
@Composable
private fun ColumnScope.DescribeStep(builder: BuilderModel, host: HostModel, phase: Int?, onSettings: () -> Unit, onHistory: () -> Unit, canBuild: Boolean,
                                     changeBox: BringIntoViewRequester) {
    val editing = builder.editingProject
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(if (editing != null) "Edit · ${editing.bundle.name}" else "New app")
        Text(when { phase != null -> "Building it now"; editing != null -> "What should change?"; else -> "What should we build?" },
            style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text(when {
            phase != null -> "Research, a screen plan, the build, behavior checks and a visual check. You can leave PUB; it notifies you when the draft is ready."
            editing != null -> "Describe the change. PUB builds on the app's code, saved research and checks; its data and photos stay on your phone."
            else -> "Say what it's for, what you put in and what it should show. PUB researches similar apps, plans the screens and tests the result."
        }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (phase != null) {
        BuildProgress(builder, phase)
        Notice(builder.prompt.takeIf { it.isNotBlank() }?.let { "“${it.trim()}”" }, quiet = true)
        return
    }
    BusyLine(builder)
    builder.message?.let { Notice(it) }
    builder.visualReviewError?.takeIf { builder.hasBuildResult }?.let { Notice(it, quiet = true) }
    if (editing != null) {
        builder.editingSpec?.let { Text(specNote(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("edit-spec-note")) }
            ?: builder.editingSpecNote?.let { Notice(it, quiet = true) }
    }
    PromptBox(builder, host, label = if (editing != null) "What would you like to change?" else "What would you like to build?",
        placeholder = if (editing != null) "Add a reset button and make the result easier to read" else "A tip calculator with bill amount, tip percentage and number of people",
        voiceLabel = if (editing != null) "Speak a change" else "Speak your idea", minLines = 6, modifier = Modifier.bringIntoViewRequester(changeBox))
    if(builder.hasBuildResult) PubTextButton(onClick=builder::clearDraft,enabled=builder.busy==null) {Text("Dismiss build result")}
    BuilderMeta(builder, onSettings, onHistory)
}

/**
 * The description or change, with voice dictation and a character count. Dictation only adds text; nothing is built
 * until the user taps the build button.
 */
@Composable
private fun PromptBox(builder: BuilderModel, host: HostModel, label: String, placeholder: String, voiceLabel: String, minLines: Int,
                      modifier: Modifier = Modifier, addedMessage: String = "Voice text added. Review or edit it before building.") {
    val idle = builder.busy == null && builder.capturePending == null && !builder.buildInProgress
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = builder.prompt,
            onValueChange = { builder.prompt = it.take(8000) },
            label = { Text(label) },
            placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f)) },
            textStyle = MaterialTheme.typography.bodyLarge,
            minLines = minLines,
            maxLines = 12,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth().testTag("app-instructions"),
            enabled = idle
        )
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoicePromptButton(
                prompt = builder.prompt,
                onPromptChange = { builder.prompt = it },
                enabled = idle,
                globallyBlocked = host.globalBlocked,
                label = voiceLabel,
                speechPrompt = if (voiceLabel == "Speak your idea") "Describe the app you want to build" else "Say what to change",
                addedMessage = addedMessage,
                modifier = Modifier.weight(1f)
            )
            Text("${builder.prompt.length} / 8,000", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp))
        }
    }
}

/** The model the next build uses, where to change it, and Prompt history. */
@Composable
private fun BuilderMeta(builder: BuilderModel, onSettings: () -> Unit, onHistory: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider(color = colors.outlineVariant)
        if (builder.account == null) {
            MetaRow("ChatGPT", "Not connected", "Connect", onSettings)
        } else {
            val selectedName = builder.models.find { it.id == builder.selectedModel }?.name
            MetaRow("Model", selectedName ?: "Waiting for available models", "Change", onSettings)
        }
        HorizontalDivider(color = colors.outlineVariant)
        MetaRow("History", "Every request for this app", "Open", onHistory, Modifier.testTag("builder-history"))
        HorizontalDivider(color = colors.outlineVariant)
    }
}

@Composable
private fun MetaRow(label: String, value: String, action: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClickLabel = "$action $label", onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow(label, Modifier.width(labelColumn(64.dp)))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** What the builder is doing when it is busy with something other than a build: restoring, loading models, saving. */
@Composable
private fun BusyLine(builder: BuilderModel) {
    val busy = builder.busy ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("builder-busy")) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
        Text(busy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A short status line on a tinted strip; quiet ones are plain secondary text. */
@Composable
private fun Notice(text: String?, quiet: Boolean = false) {
    if (text == null) return
    val colors = MaterialTheme.colorScheme
    if (quiet) Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    else Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.secondaryContainer.copy(alpha = .55f)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(colors.primary))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSecondaryContainer)
    }
}

/** The running build as its five phases: done ones checked, the current one lit, with what research and planning found. */
@Composable
private fun BuildProgress(builder: BuilderModel, phase: Int) {
    val colors = MaterialTheme.colorScheme
    PubCard(Modifier.fillMaxWidth().testTag("build-progress")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.primary, trackColor = colors.outlineVariant)
            BUILD_PHASES.forEachIndexed { index, name ->
                val done = index < phase; val active = index == phase
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(when { done -> colors.primaryContainer; active -> colors.primary; else -> Color.Transparent }),
                        contentAlignment = Alignment.Center) {
                        if (done) Icon(PubIcons.Check, null, Modifier.size(11.dp), tint = colors.onPrimaryContainer)
                        else if (!active) Box(Modifier.size(6.dp).clip(CircleShape).background(colors.outline))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = if (done || active) colors.onSurface else colors.onSurfaceVariant)
                        val detail = when {
                            active -> builder.busy ?: "Checking the preview in the background…"
                            index == 0 && done -> builder.researchBrief?.let { researchLine(it) } ?: builder.researchNote
                            index == 1 && done -> builder.uiPlan?.let { "${it.screens.size} ${if (it.screens.size == 1) "screen" else "screens"} planned" } ?: builder.uiPlanNote
                            else -> null
                        }
                        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
    if (builder.buildInProgress) {
        val context = LocalContext.current
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            PubTextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Enable build notifications") }
        }
    }
}

private fun researchLine(brief: ResearchBrief): String {
    val sources = brief.sources.count { it.url.startsWith("https://") }
    return listOfNotNull(if (brief.web) "On the web" else "From the model's knowledge",
        if (sources > 0) "$sources ${if (sources == 1) "source" else "sources"}" else null, brief.category.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/**
 * Step 2: the draft, how it checked out, Adjust (changes that build on the draft) and, folded away, how it was built.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.ReviewStep(builder: BuilderModel, host: HostModel, draft: ProjectBundle, phase: Int?, canBuild: Boolean, preparing: Boolean,
                                   changeBox: BringIntoViewRequester, onHistory: () -> Unit, onSettings: () -> Unit,
                                   onApply: () -> Unit, onRetryReview: () -> Unit, onReport: (ReportTarget) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val editing = builder.editingProject
    // The draft.
    Column(Modifier.fillMaxWidth().testTag("draft-card"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Eyebrow(if (phase != null) "Updating the draft" else if (editing != null) "Changes ready" else "Draft ready")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIconTile(draft.name, draft.icon?.let { draft.assets[it]?.asJsonObject }, size = 60.dp)
            Text(draft.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Text(draft.description, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        DraftFacts(builder, draft)
        builder.message?.takeIf { phase == null && !it.startsWith("Visual check passed") }?.let { Notice(it) }
        builder.checksError?.let { Notice(it) }
        builder.visualReviewError?.let { error ->
            Notice(error)
            if (phase == null) PubTextButton(onClick = onRetryReview, enabled = builder.busy == null && !preparing) { Text("Retry visual review") }
        }
    }
    // Adjust: a change by prompt or voice builds a new version of this draft.
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(changeBox).testTag("change-box"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHead("Adjust", "Request changes")
        if (phase == null) BusyLine(builder)
        if (phase != null) {
            BuildProgress(builder, phase)
            Notice(builder.prompt.takeIf { it.isNotBlank() }?.let { "“${it.trim()}”" }, quiet = true)
        } else {
            PromptBox(builder, host, label = "What should change?", placeholder = "Make the total bigger and add a reset button",
                voiceLabel = "Speak a change", minLines = 3, addedMessage = "Voice text added. Review it, then tap Apply changes.")
            QuickFixes(builder.checksReport, builder.visualReview, builder.prompt) { builder.prompt = clipPrompt(joinPrompt(builder.prompt, it)) }
            PubButton(onClick = onApply, enabled = canBuild, modifier = Modifier.fillMaxWidth().testTag("apply-changes"),
                colors = ButtonDefaults.buttonColors(containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer)) {
                Icon(PubIcons.Spark, null, Modifier.size(16.dp)); Spacer(Modifier.width(10.dp)); Text("Apply changes")
            }
            Text("Builds a new version from this draft. If the build fails or you cancel it, this draft is kept.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        RequestTrail(builder.draftRequests, startsWithIdea = editing == null)
    }
    // How it was built, folded away.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SectionHead("Details", "How it was built")
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = colors.outlineVariant)
        builder.researchBrief?.let { brief ->
            Fold("Research", researchLine(brief), "fold-research") { ResearchCard(brief, onReport = { onReport(ReportTarget.research(brief)) }) }
        } ?: builder.researchNote?.let { Fold("Research", it, "fold-research") { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        builder.uiPlan?.let { plan ->
            Fold("Screen plan", "${plan.screens.size} ${if (plan.screens.size == 1) "screen" else "screens"}", "fold-plan") { UiPlanCard(plan) }
        } ?: builder.uiPlanNote?.let { Fold("Screen plan", it, "fold-plan") { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        val checks = builder.checksReport
        if (checks != null || builder.draftTests != null) {
            Fold("Behavior checks", checks?.let { "${it.passed} of ${it.total} passed" } ?: "Not run", "fold-checks") {
                BehaviorChecksSummary(builder.draftTests, checks, builder.checksError, running = phase != null)
            }
        }
        builder.visualReview?.let { review ->
            Fold("Visual review", if (review.lineSequence().firstOrNull()?.contains(VisualReview.FIXES_NEEDED) == true) "Fixes suggested" else "No fixes needed", "fold-visual") {
                Text(review, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    // Footer.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PubTextButton(onClick = onHistory, modifier = Modifier.testTag("builder-history")) { Text("Prompt history") }
        PubTextButton(onClick = builder::clearDraft, enabled = builder.busy == null && builder.capturePending == null && !builder.buildInProgress) { Text("Discard draft") }
        PubTextButton(onClick = { onReport(ReportTarget.app(ContentReport.Subject.BUILD, draft)) }, modifier = Modifier.testTag("report-build")) { Text("Report content") }
        if (builder.account == null) PubTextButton(onClick = onSettings) { Text("Connect ChatGPT") }
    }
}

/** The access an app asks the user for, by name. Its own saved data and private files are not access to anything else. */
internal fun accessLabels(capabilities: Collection<String>): List<String> =
    capabilities.filterNot { it.startsWith("storage.") }.map { HardwareInventory.labels[it] ?: it }

/** Checks, visual check and requested access side by side, separated by hairlines. */
@Composable
private fun DraftFacts(builder: BuilderModel, draft: ProjectBundle) {
    val colors = MaterialTheme.colorScheme
    val checks = builder.checksReport
    val review = builder.visualReview
    val facts = listOf(
        "Checks" to (checks?.let { "${it.passed}/${it.total}" } ?: if (builder.draftTests?.isEmpty == true) "None" else "–"),
        "Visual" to when {
            builder.visualReviewError != null -> "Not run"
            review == null -> "–"
            review.lineSequence().firstOrNull()?.contains(VisualReview.FIXES_NEEDED) == true -> "Fixes"
            else -> "Passed"
        },
        "Access" to (accessLabels(draft.capabilities).size.takeIf { it > 0 }?.let { "$it" } ?: "None"))
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerLow).padding(vertical = 12.dp)) {
        facts.forEachIndexed { index, (label, value) ->
            if (index > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(colors.outlineVariant))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(label)
                Text(value, style = MaterialTheme.typography.titleLarge, modifier = if (label == "Checks") Modifier.testTag("behavior-checks") else Modifier)
            }
        }
    }
    accessLabels(draft.capabilities).takeIf { it.isNotEmpty() }?.let { Text("Asks for: " + it.joinToString(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
}

@Composable
private fun SectionHead(eyebrow: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow(eyebrow)
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    }
}

/** A folded detail: a row with a one-line summary that opens to its content. */
@Composable
private fun Fold(title: String, summary: String?, tag: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = if (open) "Hide $title" else "Show $title") { open = !open }
            .padding(vertical = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Icon(if (open) PubIcons.ChevronDown else PubIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
        AnimatedVisibility(open) { Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

/** Step 3: what saving does, before the user saves. */
@Composable
private fun ColumnScope.PublishStep(builder: BuilderModel, host: HostModel, draft: ProjectBundle) {
    val colors = MaterialTheme.colorScheme
    val editing = builder.editingProject
    Column(Modifier.fillMaxWidth().testTag("publish-step"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppIconTile(draft.name, draft.icon?.let { draft.assets[it]?.asJsonObject }, size = 76.dp)
        Text(draft.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(if (editing != null) "Save these changes to ${editing.bundle.name}" else "Add it to Your apps", style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    val checks = builder.checksReport
    val rows = listOfNotNull(
        "Where" to "Your apps, on this phone only. Nothing is published online.",
        "Data" to (if (editing != null) "Keeps the app's saved data, files and photos. What you did while trying a preview is not kept."
            else "Starts empty and stays on this phone."),
        "Access" to (accessLabels(draft.capabilities).takeIf { it.isNotEmpty() }?.let { it.joinToString() + ". It asks before first use; change it in Settings → Access." }
            ?: "Needs no device access."),
        "Style" to (if (editing != null && !host.usesStyleGuide(editing.id)) "Drawn in PUB's default style, as you set for this app."
            else "Drawn in your style guide. Long-press the app to turn that off for it."),
        (checks?.let { "Checks" to "${it.passed} of ${it.total} behavior checks passed." }),
        "Next" to "PUB opens the app once it is saved.")
    PubCard(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Eyebrow(label, Modifier.width(labelColumn(60.dp)).padding(top = 2.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * One-tap starting points for the next change when the draft's own checks found something: the behavior checks that
 * still fail, or the issues the latest visual review still sees. Each adds editable text to the change box; nothing
 * is sent until Apply changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFixes(report: ChecksReport?, review: String?, prompt: String, onAdd: (String) -> Unit) {
    val checks = report?.failures()?.takeIf { it.isNotEmpty() }?.let(::failingChecksRequest)
    val screen = review?.let(::visualIssuesRequest)
    if (checks == null && screen == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("Quick fixes")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            checks?.let { SuggestionChip(onClick = { onAdd(it) }, label = { Text("Fix failing checks") }, enabled = it !in prompt, modifier = Modifier.testTag("fix-checks")) }
            screen?.let { SuggestionChip(onClick = { onAdd(it) }, label = { Text("Fix visual issues") }, enabled = it !in prompt, modifier = Modifier.testTag("fix-visual")) }
        }
    }
}

private const val QUICK_FIX_CHARS = 2000

/**
 * A change request naming the draft's failing behavior checks and why they failed. A change returns an updated suite,
 * so it asks for the app to be fixed and the checks to stay, as automatic behavior repairs do.
 */
internal fun failingChecksRequest(failures: List<ChecksReport.Entry>): String =
    ChecksReport.clip("Fix the app so these behavior checks pass. Keep the checks themselves unchanged:\n" + failures.joinToString("\n") { "- ${it.name}: ${it.detail}" }, QUICK_FIX_CHARS)

/** A change request with the latest visual review's findings; null unless that review asked for fixes. */
internal fun visualIssuesRequest(review: String): String? {
    if (review.lineSequence().firstOrNull()?.contains(VisualReview.FIXES_NEEDED) != true) return null
    val findings = review.substringAfter("\n\n").substringBefore("\n\n" + VisualReview.SCOPE_NOTE).trim()
    return if (findings.isEmpty()) null else ChecksReport.clip("Fix these issues from the visual review:\n$findings", QUICK_FIX_CHARS)
}

private const val TRAIL_PREVIEW = 4
private const val TRAIL_LINES = 3

/** What the user asked for to get the current draft, so the cleared change box never hides it. */
@Composable
private fun RequestTrail(requests: List<String>, startsWithIdea: Boolean) {
    if (requests.isEmpty()) return
    var expanded by rememberSaveable(requests.first()) { mutableStateOf(false) }
    val hidden = if (expanded) 0 else (requests.size - TRAIL_PREVIEW).coerceAtLeast(0)
    val labelStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
    Column(Modifier.fillMaxWidth().testTag("request-trail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("What you asked for")
        if (hidden > 0) Text("$hidden earlier ${if (hidden == 1) "change" else "changes"}", style = MaterialTheme.typography.bodySmall)
        requests.drop(hidden).forEachIndexed { offset, request ->
            val index = hidden + offset
            val label = if (startsWithIdea && index == 0) "Idea" else "Change ${if (startsWithIdea) index else index + 1}"
            Text(buildAnnotatedString { withStyle(labelStyle) { append(label) }; append("  ·  "); append(request) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else TRAIL_LINES, overflow = TextOverflow.Ellipsis)
        }
        val long = requests.any { it.length > 160 || it.count { c -> c == '\n' } >= TRAIL_LINES }
        if (requests.size > TRAIL_PREVIEW || long) PubTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show all") }
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
                PubTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Show details") }
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
internal fun ResearchCard(brief: ResearchBrief, onReport: (() -> Unit)? = null, framed: Boolean = false) {
    var expanded by rememberSaveable(brief.summary) { mutableStateOf(false) }
    val included = brief.features.filter { it.feasibleInPub && it.priority != "could" }
    val impossible = brief.features.filter { !it.feasibleInPub }
    val sources = brief.sources.filter { it.url.startsWith("https://") }
    val longest = maxOf(included.size, impossible.size, brief.assumptions.size, sources.size)
    val limit = if (expanded) Int.MAX_VALUE else RESEARCH_PREVIEW_ITEMS
    Framed(framed, "research-card") {
        Column(Modifier.padding(if (framed) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (framed) Text("Research", style = MaterialTheme.typography.titleMedium)
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
                Eyebrow("Sources", Modifier.padding(top = 6.dp))
                sources.take(limit).forEach { SourceLink(it) }
            }
            if (longest > RESEARCH_PREVIEW_ITEMS) {
                PubTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show all") }
            }
            onReport?.let { PubTextButton(onClick = it, modifier = Modifier.testTag("report-research")) { Text("Report research") } }
        }
    }
}

/** The build's UI plan: its structure and each screen's purpose; every element and state on request. */
@Composable
internal fun UiPlanCard(plan: app.pub.core.UiPlan, framed: Boolean = false) {
    var expanded by rememberSaveable(plan.layout) { mutableStateOf(false) }
    Framed(framed, "ui-plan-card") {
        Column(Modifier.padding(if (framed) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (framed) Text("UI plan", style = MaterialTheme.typography.titleMedium)
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
            PubTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show every screen in detail") }
        }
    }
}

/** [content] in a card when [framed], else as it is; [tag] marks it either way. */
@Composable
private fun Framed(framed: Boolean, tag: String, content: @Composable () -> Unit) {
    if (framed) PubCard(Modifier.fillMaxWidth().testTag(tag)) { content() }
    else Box(Modifier.fillMaxWidth().testTag(tag)) { content() }
}

@Composable
private fun ResearchList(title: String, items: List<String>, limit: Int) {
    if (items.isEmpty()) return
    Eyebrow(title, Modifier.padding(top = 6.dp))
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
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
}

/** Opens an https link in the user's browser app, never inside PUB; a missing browser is ignored. */
internal fun openInBrowser(context: Context, url: String) {
    if (!url.startsWith("https://")) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Account operation feedback for Settings: what is running (with Cancel) and the outcome of signing in or out. A build's
 * own messages belong to the builder, so they are not repeated here while it holds a draft or a result.
 */
@Composable
fun BuilderStatus(builder: BuilderModel) {
    val colors = MaterialTheme.colorScheme
    val busy = builder.busy ?: if (builder.buildInProgress) "Building in the background…" else null
    busy?.let {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.primary, trackColor = colors.outlineVariant)
            PubTextButton(onClick = builder::cancel) { Text("Cancel") }
        }
    }
    builder.message?.takeIf { builder.draft == null && !builder.hasBuildResult && !builder.buildInProgress }
        ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
}
