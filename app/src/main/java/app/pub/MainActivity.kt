package app.pub

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pub.hardware.*
import app.pub.builder.BuilderModel
import app.pub.core.ContentReport
import app.pub.core.PhoneFilesContract
import app.pub.projects.SavedProject
import app.pub.ui.*

class MainActivity : ComponentActivity() {
    private val model: HostModel by viewModels()
    private val builder: BuilderModel by viewModels()
    private var openBuildSignal by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent.getBooleanExtra("open_builder",false)) openBuildSignal++
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { PubApp(model, builder, openBuildSignal) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("open_builder",false)) openBuildSignal++}
    override fun onStart() { super.onStart(); model.setVisible(true); builder.setVisible(true) }
    override fun onStop() {
        // A configuration change (rotation, resize, theme) recreates the Activity while the ViewModels and
        // the user's session continue; only a real stop ends hardware sessions and the foreground state.
        if (!isChangingConfigurations) { model.setVisible(false); builder.setVisible(false) }
        super.onStop()
    }
}

@Composable
private fun PubApp(model: HostModel, builder: BuilderModel, openBuildSignal:Int, openAndroidSettings: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(model.listening) {
        view.keepScreenOn = model.listening
        onDispose { view.keepScreenOn = false }
    }
    val colors=PubColors.scheme(model.accent)
    var destination by rememberSaveable { mutableStateOf("launcher") }
    var settingsReturn by rememberSaveable { mutableStateOf("launcher") }
    var historyReturn by rememberSaveable { mutableStateOf("builder") }
    var historySession by rememberSaveable { mutableStateOf<String?>(null) }
    var historyProject by rememberSaveable { mutableStateOf<String?>(null) }
    val context=LocalContext.current
    val density=LocalDensity.current
    // Read inset values only inside the size callback: reading them here would recompose the whole app
    // on every frame of the keyboard animation.
    val imeInsets=WindowInsets.ime
    val navigationInsets=WindowInsets.navigationBars
    var pendingBuildContext by rememberSaveable {mutableStateOf<String?>(null)}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingBuildContext?.let {requestContext -> pendingBuildContext=null;builder.generate(requestContext)}
    }
    val requestBuild:(String)->Unit = {requestContext ->
        val prefs=context.getSharedPreferences("build.notifications",android.content.Context.MODE_PRIVATE)
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("asked",false)) {
            prefs.edit().putBoolean("asked",true).apply();pendingBuildContext=requestContext
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else builder.generate(requestContext)
    }
    LaunchedEffect(openBuildSignal) {if(openBuildSignal>0) {model.closeProject();destination="builder"}}
    var deleteTarget by remember { mutableStateOf<SavedProject?>(null) }
    var reportTarget by remember { mutableStateOf<ReportTarget?>(null) }
    val closeProject = {
        // Closing discards the preview's photos, which an in-progress save is still copying.
        if (!model.savingPreview) {
            if (!model.preview) destination = "launcher"
            model.closeProject()
        }
    }
    // Asks the builder to bring its change box into view once it is shown again.
    var revealChanges by remember { mutableIntStateOf(0) }
    // A builder preview of the current draft returns to the builder's change box.
    val requestChanges: (() -> Unit)? = if (model.preview && destination == "builder") ({
        if (!model.savingPreview) { model.closeProject(); revealChanges++ }
    }) else null
    // A saved app can be changed from its own screen, unless the builder holds another app's build.
    val runningSaved = if (model.projectOpen && !model.preview) model.projects.find { it.id == model.projectId } else null
    val editRunning: (() -> Unit)? = runningSaved
        ?.takeIf { !builder.buildInProgress && (!builder.hasBuildResult || builder.editingProject?.id == it.id) }
        ?.let { project -> { model.closeProject(); builder.beginEdit(project); destination = "builder"; revealChanges++ } }
    val openSettings = { settingsReturn = destination; destination = "settings" }
    val back = {
        if (model.projectOpen) closeProject()
        else { destination = when (destination) { "help", "inventory" -> "settings"; "history" -> historyReturn; "settings" -> settingsReturn; else -> "launcher" } }
    }
    BackHandler(model.projectOpen || destination != "launcher", onBack = back)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::androidPermissionResult)
    LaunchedEffect(model.askAndroidPermission) {
        if (model.askAndroidPermission) { model.askAndroidPermission = false; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    var permissionEpoch by rememberSaveable { mutableStateOf(-1L) }
    val hardwarePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.hardwareAndroidResult(permissionEpoch) }
    LaunchedEffect(model.hardwareAndroidRequest) {
        model.hardwareAndroidRequest?.let { permissions ->
            permissionEpoch=model.currentHardwareEpoch
            model.hardwareAndroidRequest=null
            hardwarePermissions.launch(permissions)
        }
    }
    // The system file picker (capability files). Results go to the model's pending request, which survives this Activity
    // being recreated; launched marks it so a recreated Activity does not open the picker twice.
    val openFile = rememberLauncherForActivityResult(FilePickerContracts.OpenFile()) { model.filePickerResult(it?.let(::listOf)) }
    val openFiles = rememberLauncherForActivityResult(FilePickerContracts.OpenFiles()) { model.filePickerResult(it) }
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { model.filePickerResult(it?.let(::listOf)) }
    val createFile = rememberLauncherForActivityResult(FilePickerContracts.CreateFile()) { model.filePickerResult(it?.let(::listOf)) }
    LaunchedEffect(model.filePicker) {
        val pick = model.filePicker ?: return@LaunchedEffect
        if (pick.launched) return@LaunchedEffect
        pick.launched = true
        try {
            when (pick.mode) {
                "open" -> openFile.launch(pick.mimeTypes)
                "openMultiple" -> openFiles.launch(pick.mimeTypes)
                "folder" -> openFolder.launch(null)
                else -> createFile.launch(pick.createType to pick.name)
            }
        } catch (_: ActivityNotFoundException) { model.filePickerResult(null, failed = true) }
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.safeDrawingPadding().fillMaxSize()) {
                if (model.projectOpen) {
                    RunningAppScreen(model, closeProject, editRunning, requestChanges) {
                        model.savePreview { historyId, saved ->
                            builder.markSaved(historyId, saved.id, saved.bundle.name)
                            builder.beginEdit(saved)
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (destination != "launcher") {
                            TextButton(onClick = back, modifier = Modifier.testTag("back")) { Text("‹ Back") }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (destination == "launcher") {
                            LauncherBrand(Modifier.weight(1f).padding(end = 12.dp))
                        } else {
                            Text(when(destination) { "settings" -> "Settings"; "help" -> "Help & guide"; "inventory" -> "Hardware inventory"; "history" -> "Prompt history"; else -> if(builder.capturePending!=null) "Checking preview" else if (builder.editingProject != null) "Edit app" else "Create app" },
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }
                        if (destination != "settings" && destination != "help" && destination != "inventory" && destination != "history") OutlinedButton(onClick = openSettings, modifier = Modifier.testTag("settings")) { Text("Settings") }
                    }
                    if(destination=="launcher" && (builder.buildInProgress || builder.hasBuildResult)) {
                        TextButton(onClick={destination="builder"},modifier=Modifier.fillMaxWidth().testTag("resume-build")) {
                            Text(if(builder.buildInProgress) "Build in progress - view status" else "View build result")
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged {
                        val keyboardInset=(imeInsets.getBottom(density)-navigationInsets.getBottom(density)).coerceAtLeast(0)
                        model.updateBuilderViewport(it.width,it.height+keyboardInset)
                    }) {
                        when (destination) {
                            "launcher" -> LauncherScreen(model,
                                onEdit = { builder.beginEdit(it); destination = "builder" },
                                onDelete = { deleteTarget = it },
                                onReport = { reportTarget = ReportTarget.app(ContentReport.Subject.APP, it.bundle) }) { builder.beginCreate(); destination = "builder" }
                            "builder" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                BuilderScreen(builder, model, openSettings, { historyReturn = "builder"; historyProject = builder.editingProject?.id; historySession=builder.historySessionId; builder.refreshHistory(); destination = "history" }, onGenerate=requestBuild,
                                    revealChanges = revealChanges, onChangesRevealed = { revealChanges = 0 }) { model.previewProject(it, builder.editingProject, builder.draftHistoryId) }
                            }
                            "history" -> PromptHistoryScreen(builder, historyProject, historySession)
                            "inventory" -> HardwareInventoryScreen()
                            "help" -> HelpScreen(model)
                            "settings" -> SettingsScreen(model, builder, openAndroidSettings, { destination = "help" }, { model.previewProject(HardwareDemo.bundle()) }, { destination = "inventory" }) { deleteTarget = it }
                        }
                    }
                }
            }
            model.hardwarePrompt?.let { request -> AlertDialog(
                onDismissRequest={model.hardwareConsent(false)},
                title={Text("Allow ${model.projectName} to use ${HardwareInventory.labels[request.capability] ?: request.capability}?")},
                text={Text(if(request.capability!=PhoneFilesContract.CAPABILITY) "Access is limited to this app while PUB is open. You can change it in Settings → Access."
                    else if(model.preview) "You choose each file or folder in Android's file picker; this preview can use only what you pick, until you close it. Your files are never deleted when the preview closes."
                    else "You choose each file or folder in Android's file picker; the app can use only what you pick, and only while it is open in PUB. It keeps access to what you picked until you forget it in Settings → Access. Deleting the app never deletes your files.")},
                confirmButton={Column {
                    TextButton(onClick={model.hardwareConsent(true)}) {Text("Allow this session")}
                    if(!model.preview) TextButton(onClick={model.hardwareConsent(true,true)}) {Text("Always allow while open")}
                }}, dismissButton={TextButton(onClick={model.hardwareConsent(false)}){Text("Not now")}}
            ) }
            model.devicePicker?.let { picker -> AlertDialog(
                onDismissRequest={picker.complete(null)},title={Text("Choose a device")},
                text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
                    if(picker.choices.isEmpty()) Text("No compatible devices found. Check Android connections and try again.")
                    picker.choices.forEach { choice -> TextButton(onClick={picker.complete(choice.id)},modifier=Modifier.fillMaxWidth()){Text(choice.label)} }
                }},confirmButton={TextButton(onClick={picker.complete(null)}){Text("Cancel")}}
            ) }
            model.cameraRequest?.let { callback -> key(callback) { CameraCaptureScreen(model.cameraDirectory, { callback(it) }, { callback(null) }) } }
            model.nfcRequest?.let { callback -> key(callback) { NfcReadDialog({callback(it)},{callback(null)}) } }
            if (model.askPermission && model.projectOpen) AlertDialog(
                onDismissRequest = model::deny,
                title = { Text("Let ${model.projectName} use the microphone?") },
                text = { Text("Pitch is measured on your phone. No audio is saved or uploaded by this app. Access ends when you close it or leave PUB.") },
                confirmButton = { Column {
                    TextButton(onClick = model::allowSession) { Text("Allow this session") }
                    if (!model.preview) TextButton(onClick = model::allowProject) { Text("Always allow while open") }
                } },
                dismissButton = { TextButton(onClick = model::deny) { Text("Not now") } }
            )
            reportTarget?.let { ReportDialog(it) { reportTarget = null } }
            deleteTarget?.let { project -> AlertDialog(
                onDismissRequest = { deleteTarget = null }, title = { Text("Delete ${project.bundle.name}?") },
                text = { Text("This removes the app and its saved data from this phone. Files and folders you picked for it elsewhere on the phone are kept; only its access to them ends.") },
                confirmButton = { TextButton(onClick = { model.deleteProject(project); if (builder.editingProject?.id == project.id) builder.beginCreate(); deleteTarget = null }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
            ) }
        }
    }
}

/**
 * [edit] (a saved app) opens the builder to change it; [requestChanges] (a builder preview) returns to the builder's
 * change box. Both are absent where they do not apply.
 */
@Composable
private fun ColumnScope.RunningAppScreen(model: HostModel, close: () -> Unit, edit: (() -> Unit)?, requestChanges: (() -> Unit)?, save: () -> Unit) {
    // Pinned host controls stay outside the generated UI and cannot be hidden by an app.
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(model.projectName, style=MaterialTheme.typography.titleLarge, maxLines=1, overflow=TextOverflow.Ellipsis)
            if(model.preview) Text("Preview", color=MaterialTheme.colorScheme.primary, style=MaterialTheme.typography.labelMedium)
        }
        edit?.let { TextButton(onClick=it, modifier=Modifier.testTag("edit-app")) { Text("Edit") } }
        TextButton(onClick=close, enabled=!model.savingPreview, modifier=Modifier.testTag("close-app").semantics { contentDescription="Close app" }) { Text("✕  Close") }
    }
    HorizontalDivider()
    if (model.hardwareAccessEnabled) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
        val filesOnly = model.onlyFileAccessActive
        Text(if (filesOnly) "Phone file access enabled" else "Device access enabled",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
        TextButton(onClick=model::stopHardware){Text(if (filesOnly) "Stop access" else "Stop hardware")}
    }
    if (model.listening) Surface(color=MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(model.microphoneMessage, modifier=Modifier.weight(1f), style=MaterialTheme.typography.labelMedium)
            TextButton(onClick=model::stop) { Text("Stop") }
        }
    }
    if (model.preview) Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(if (model.previewEditTarget != null) "Save changes updates the app code. Your existing saved data, files and photos are kept; changes made in this preview are discarded." else "Try it before saving. Closing discards preview changes, including its files.",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp,Alignment.End)) {
            requestChanges?.let { OutlinedButton(onClick=it,enabled=!model.savingPreview,modifier=Modifier.testTag("request-changes")) { Text("Request changes") } }
            Button(onClick=save,enabled=model.ui!=null && model.failure==null && !model.savingPreview,modifier=Modifier.testTag("save-preview")) {
                Text(if (model.savingPreview) "Saving…" else if (model.previewEditTarget != null) "Save changes" else "Save locally")
            }
        }
    }
    key(model.projectId) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("running-app"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            val dictation = rememberAppDictation(blocked = model.globalBlocked)
            model.ui?.let { NativeRenderer(it,model::action,model::input,model::resolveImage,model::graphicAsset,dictation) } ?: Text(model.failure ?: "Opening app…")
            if(model.failure!=null) {
                Text(model.failure!!,color=MaterialTheme.colorScheme.error)
                Button(onClick=model::restart) { Text("Restart app") }
            }
        }
    }
}
