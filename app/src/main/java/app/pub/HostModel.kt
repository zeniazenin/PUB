package app.pub

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.pub.core.StyleGuide
import app.pub.core.PermissionPolicy.Rule
import app.pub.hardware.*
import app.pub.core.FilesContract
import app.pub.core.HardwareContract
import app.pub.core.PhoneFilesContract
import app.pub.core.UiPacket
import app.pub.core.ProjectBundle
import app.pub.projects.AppFiles
import app.pub.projects.AppSpecStore
import app.pub.projects.ProjectStore
import app.pub.projects.SampleApps
import app.pub.projects.SavedProject
import app.pub.permissions.PermissionBroker
import app.pub.runtime.RuntimeClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class HostModel(application: Application) : AndroidViewModel(application) {
    @SuppressLint("StaticFieldLeak") // Application context only; never retains an Activity.
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("host", Context.MODE_PRIVATE)
    private val permissionPrefs = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)
    private val store = ProjectStore(context)
    private val specs = AppSpecStore(context)
    private val projectPrefs get() = context.getSharedPreferences("project.$projectId", Context.MODE_PRIVATE)
    // Loaded off the main thread: listing parses (and validates) every saved package, including image data.
    var projects by mutableStateOf(emptyList<SavedProject>())
        private set
    private var projectsEpoch = 0L
    /** Rereads the library in the background; only the most recently requested result is published. */
    private fun refreshProjects() {
        val epoch = ++projectsEpoch
        viewModelScope.launch {
            val loaded = runCatching { withContext(Dispatchers.IO) { listLibrary() } }.getOrNull() ?: return@launch
            if (epoch == projectsEpoch) { projects = loaded; syncPolicies() }
        }
    }
    /**
     * Every library listing, off the main thread. A new install's sample app is saved first (once per install, see
     * [SampleApps]), so the first listing already includes it; a seeding failure is skipped and never blocks it.
     */
    private fun listLibrary(): List<SavedProject> {
        SampleApps.seedIfNeeded(context, store)
        return store.list()
    }
    var previewHistoryId: String? = null; private set
    fun graphicAsset(id: String): JsonObject? = selectedBundle?.assets?.get(id)?.asJsonObject
    var previewEditTarget by mutableStateOf<SavedProject?>(null); private set
    /** Reads the current stored package from disk (no cache); safe to call off the main thread. */
    fun editableProject(id: String): SavedProject = checkNotNull(store.read(id)) { "App is no longer available" }
    var projectOpen by mutableStateOf(false)
        private set
    var projectId by mutableStateOf("")
        private set
    var projectName by mutableStateOf("")
        private set
    var preview by mutableStateOf(false)
        private set
    var projectDescription by mutableStateOf("")
        private set
    private var selectedBundle: ProjectBundle? = null
    private var previewData = JsonObject()
    private var hardwareIntent = false
    private val media = ProjectMedia(context)
    // Private files of the open app (storage.files). Like hardware, a session belongs to one runtime start and epoch.
    private val files = AppFiles(context, media)
    private var filesSession: AppFiles.Session? = null
    private var filesEpoch = 0L
    /** Ends the open app's file session: its queued requests are skipped and late replies dropped. */
    private fun stopFiles() { filesEpoch++; filesSession?.close(); filesSession = null }
    private fun currentFilesSession(): AppFiles.Session = filesSession ?: run {
        val epoch = filesEpoch
        // An edit preview has its own snapshot folder and photos, and sees the saved app's photos read-only.
        val scope = AppFiles.Scope(projectId, projectId, if (preview) previewEditTarget?.id else null)
        files.open(scope) { reply -> if (filesEpoch == epoch) runtime?.send(reply) }.also { filesSession = it }
    }
    // Files on the phone (capability files): documents the user picks in the system picker, through the hardware broker.
    // Saved apps keep their grants; previews hold session grants, and an edit preview reads the saved app's read-only.
    private val phoneFiles = PhoneFiles.create(context, files)
    /** The system file picker the open app asked for; MainActivity launches it and answers [filePickerResult]. */
    var filePicker by mutableStateOf<FilePick?>(null); private set
    // The app session (files epoch) that asked: only it gets the answer.
    private var filePickerSession = -1L
    /** Changes whenever an app's file grants may have changed (for Settings → Access). */
    var fileGrantsRevision by mutableStateOf(0); private set
    private fun phoneFileScope(): PhoneFiles.Scope {
        val saved = if (preview) previewEditTarget?.id else null
        return PhoneFiles.Scope(projectId, saved, if (FilesContract.CAPABILITY in capabilities) AppFiles.Scope(projectId, projectId, saved) else null)
    }
    /**
     * The system file picker's answer from MainActivity: [uris] is null or empty when the user cancelled, and [failed]
     * says no picker could open. It reaches only the app session that asked, if file access is still allowed.
     */
    fun filePickerResult(uris: List<android.net.Uri>?, failed: Boolean = false) {
        val pick = filePicker ?: return
        filePicker = null
        if (!projectOpen || !visible || filePickerSession != filesEpoch) return
        hardwareBroker().completePick(pick, uris, failed)
        fileGrantsRevision++
    }
    /** Files and folders the saved app [id] can open (Settings → Access). Reads the grant store off the main thread. */
    suspend fun fileGrantCount(id: String): Int { requireProjectId(id); return withContext(Dispatchers.IO) { phoneFiles.store.grants(id).size } }
    /**
     * Settings → Access → Forget file access: releases every file and folder the saved app [id] was given and deletes its
     * grant file. The documents themselves are never touched; the app can ask the user to pick them again.
     */
    fun forgetFileAccess(id: String) {
        requireProjectId(id)
        if (projectId == id || previewEditTarget?.id == id) stopHardware()
        phoneFiles.later({ phoneFiles.store.forget(id) }) { fileGrantsRevision++ }
    }
    private var hardware: HardwareBroker? = null
    var hardwarePrompt by mutableStateOf<HardwarePrompt?>(null); private set
    var hardwareAndroidRequest by mutableStateOf<Array<String>?>(null)
    var devicePicker by mutableStateOf<DevicePicker?>(null); private set
    var cameraRequest by mutableStateOf<((JsonObject?) -> Unit)?>(null); private set
    var nfcRequest by mutableStateOf<((JsonObject?) -> Unit)?>(null); private set
    var hardwareRevision by mutableStateOf(0); private set
    private var hardwareEpoch = 0L
    val hardwareAccessEnabled: Boolean get() { hardwareRevision;return hardware?.activeCapabilities?.isNotEmpty()==true }
    val cameraDirectory get() = media.directory(projectId)
    fun resolveImage(asset: String) = if(projectOpen) media.resolve(projectId, asset) ?: previewEditTarget?.let { media.resolve(it.id, asset) } else null
    fun hardwareConsent(allow: Boolean, always: Boolean = false) { hardware?.consent(allow,always) }
    val currentHardwareEpoch get() = hardwareEpoch
    fun hardwareAndroidResult(epoch: Long) { if(epoch==hardwareEpoch) hardware?.androidResult() }
    fun hardwareBlocked(cap: String): Boolean { hardwareRevision; return permissionPrefs.getBoolean("$cap.blocked",false) }
    fun hardwareDefault(cap: String): Rule { hardwareRevision; return readHardwareRule("$cap.default",Rule.ASK) }
    fun hardwareRule(id: String,cap: String): Rule { hardwareRevision; requireProjectId(id);return readHardwareRule("$id.$cap",Rule.INHERIT) }
    private fun readHardwareRule(key: String,fallback: Rule) = runCatching {Rule.valueOf(permissionPrefs.getString(key,fallback.name)!!)}.getOrDefault(fallback)
    fun changeHardwareBlock(cap: String,value: Boolean) { require(cap in HardwareContract.OPERATIONS);permissionPrefs.edit().putBoolean("$cap.blocked",value).apply();stopHardware();hardwareRevision++ }
    fun changeHardwareDefault(cap: String,value: Rule) { require(cap in HardwareContract.OPERATIONS && value in setOf(Rule.ASK,Rule.ALLOW));permissionPrefs.edit().putString("$cap.default",value.name).apply();stopHardware();hardwareRevision++ }
    fun changeHardwareRule(id: String,cap: String,value: Rule) { requireProjectId(id);require(cap in HardwareContract.OPERATIONS);permissionPrefs.edit().putString("$id.$cap",value.name).apply();stopHardware();hardwareRevision++ }
    fun stopHardware() = endHardware(keepFilePicker = false)
    /**
     * Ends hardware sessions and invalidates their pending results. With [keepFilePicker], a system file picker that is
     * already open survives: PUB was left for it, and its answer still belongs to the open app's request.
     */
    private fun endHardware(keepFilePicker: Boolean) {
        if (!keepFilePicker || filePicker?.launched != true) filePicker = null
        hardwareEpoch++;hardware?.close();hardware=null;hardwarePrompt=null;hardwareAndroidRequest=null;devicePicker=null;cameraRequest=null;nfcRequest=null;hardwareRevision++;broker.stop();runtime?.discard(HARDWARE_STREAM);runtime?.send(event("hardwareStopped"))
    }
    /** True while the only active access is to files on the phone (the host shows "Phone file access" instead of devices). */
    val onlyFileAccessActive: Boolean get() { hardwareRevision; return hardware?.activeCapabilities == setOf(PhoneFilesContract.CAPABILITY) }
    private fun hardwareBroker(): HardwareBroker {
        if(hardware?.isClosed==true) {hardware=null;hardwareEpoch++}
        return hardware ?: run {
            val epoch=hardwareEpoch
            val fileAccess=FileAccess(phoneFiles,phoneFileScope()) { pick ->
                if(hardwareEpoch==epoch && filePicker==null) { filePicker=pick; filePickerSession=filesEpoch; true } else false
            }
            HardwareBroker(context,projectId,preview,capabilities,{ visible && projectOpen && hardwareEpoch==epoch },
                {if(hardwareEpoch==epoch) hardwarePrompt=it},{if(hardwareEpoch==epoch) hardwareAndroidRequest=it},{if(hardwareEpoch==epoch) devicePicker=it},
                {callback->if(hardwareEpoch==epoch) cameraRequest=callback},{callback->if(hardwareEpoch==epoch) nfcRequest=callback},
                // Stream data (sensors, location, notifications) keeps only its newest value per stream while the
                // script is busy, so concurrent streams all get delivered and none can exhaust the event queue.
                {packet,disposable->if(hardwareEpoch==epoch) { if(packet["event"]?.asString=="audio.routeLost") broker.stop("Audio route disconnected")
                    runtime?.send(packet,key=if(disposable) "$HARDWARE_STREAM${packet["id"].asString}:${packet["event"].asString}" else null) }},
                {if(hardware?.isClosed==true) {broker.stop("Hardware access ended");runtime?.discard(HARDWARE_STREAM);runtime?.send(event("hardwareStopped"))};hardwareRevision++},
                fileAccess).also {hardware=it}
        }
    }
    var ui by mutableStateOf<JsonObject?>(null)
        private set
    var runtimeStatus by mutableStateOf("Choose an app to open")
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var isolatedUid by mutableStateOf<Int?>(null)
        private set
    var listening by mutableStateOf(false)
        private set
    var microphoneMessage by mutableStateOf("Microphone off")
        private set
    var askPermission by mutableStateOf(false)
    var askAndroidPermission by mutableStateOf(false)
    private val stylePrefs = context.getSharedPreferences("style", Context.MODE_PRIVATE)
    /**
     * The user's style guide for PUB and every app. Before style guides an install had only an accent, which becomes
     * its guide until the user changes it.
     */
    var styleGuide by mutableStateOf(stylePrefs.getString("guide", null)?.let { runCatching { StyleGuide.parse(it) }.getOrNull() }
        ?: StyleGuide.fromLegacyAccent(prefs.getString("accent", null)))
        private set
    /** Saved apps the user set not to follow the style guide; they use PUB's default style. */
    private var styleOptOuts by mutableStateOf(stylePrefs.getStringSet("optOut", null)?.toSet() ?: emptySet())
    var globalBlocked by mutableStateOf(false)
        private set
    var globalDefault by mutableStateOf(Rule.ASK)
        private set
    var appRule by mutableStateOf(Rule.INHERIT)
        private set
    var sampleRate by mutableStateOf(0)
        private set
    var pitchFrames by mutableStateOf(0L)
        private set
    var savingPreview by mutableStateOf(false)
        private set
    private var capabilities = emptySet<String>()
    private var projectMicrophoneRules by mutableStateOf<Map<String, Rule>>(emptyMap())
    private var pendingAndroidProjectId: String? = null
    private var runtime: RuntimeClient? = null
    private var visible = false
    private val broker = PermissionBroker(context,
        projectId = { projectId },
        prepareInput = { ready, error ->
            val session=hardware
            if(session==null) ready(null) else session.prepareMicrophone(ready,error)
        },
        declared = { "microphone.pitch" in capabilities },
        requestUser = { askPermission = true },
        requestAndroid = { pendingAndroidProjectId = projectId; askAndroidPermission = true },
        status = { active, message ->
            if (active && !listening) sampleRate = 0
            listening = active
            microphoneMessage = message
            runtime?.send(event("status").apply { addProperty("listening", active); addProperty("message", message) })
        },
        pitch = { result, rate ->
            sampleRate = rate
            pitchFrames++
            runtime?.send(event("pitch").apply {
                addProperty("hz", result.hz); addProperty("confidence", result.confidence); addProperty("rms", result.rms)
            }, disposable = true)
        })

    init { runCatching {media.cleanupTemporary()};files.cleanupAtStartup { store.contains(it) };phoneFiles.cleanupAtStartup { store.contains(it) };syncPolicies();refreshProjects() }
    fun openProject(project: SavedProject) {
        requireProjectId(project.id)
        closeProject()
        selectedBundle = project.bundle
        projectId = project.id
        projectName = project.bundle.name
        projectDescription = project.bundle.description
        preview = false; previewData = JsonObject()
        projectOpen = true
        broker.setForeground(visible)
        syncPolicies(); restart()
    }
    fun closeProject() {
        stopHardware()
        stopFiles()
        if(preview && projectId.isNotBlank()) {
            media.delete(projectId)
            // Queued after the preview's own requests; a saved new app's files were already moved out.
            val previewId = projectId
            files.later { files.delete(previewId) }
            // Preview file grants are session-only, also when the preview was just saved as an app.
            phoneFiles.later({ phoneFiles.store.forget(previewId) })
        }
        runtime?.close(); runtime = null
        capabilities = emptySet()
        broker.projectChanged()
        broker.setForeground(false)
        projectOpen = false
        selectedBundle = null
        projectId = ""; projectName = ""; projectDescription = ""
        preview = false; previewData = JsonObject(); previewEditTarget = null; previewHistoryId = null
        hardwareIntent = false
        pendingAndroidProjectId = null
        askPermission = false; askAndroidPermission = false
        ui = null; failure = null; isolatedUid = null
        sampleRate = 0
        runtimeStatus = "Choose an app to open"
        microphoneMessage = "Microphone off"
        syncPolicies()
    }
    fun previewProject(bundle: ProjectBundle, editing: SavedProject? = null, historyId: String? = null) {
        bundle.requireReadyAssets()
        closeProject()
        selectedBundle = bundle; projectId = "preview-${java.util.UUID.randomUUID()}"
        projectName = bundle.name; projectDescription = bundle.description
        preview = true; previewEditTarget = editing; previewHistoryId = historyId
        previewData = if (editing == null) JsonObject() else runCatching {
            JsonParser.parseString(context.getSharedPreferences("project.${editing.id}", Context.MODE_PRIVATE).getString("data", "{}")).asJsonObject
        }.getOrDefault(JsonObject())
        // An edit preview starts from a copy of the saved app's files, queued before any of its requests.
        if (editing != null) { val previewId = projectId; files.later { files.snapshot(editing.id, previewId) } }
        projectOpen = true
        broker.setForeground(visible)
        syncPolicies(); restart()
    }
    /**
     * Saves the open preview. Writing the package, its data, a photo set (up to 1 GiB) and the preview's
     * files runs off the main thread; the saved app then reopens and [onSaved] receives the preview's history
     * ID and the saved project. [savingPreview] is true meanwhile, so the preview cannot be saved twice or
     * closed, and its new file requests are answered busy. An edit keeps the saved app's data, photos and
     * files; the edit preview's changes to them are discarded.
     */
    fun savePreview(onSaved: (historyId: String?, saved: SavedProject) -> Unit = { _, _ -> }) {
        if (!preview || ui == null || failure != null || savingPreview) return
        val bundle = selectedBundle ?: return
        val target = previewEditTarget; val previewId = projectId; val historyId = previewHistoryId
        val data = previewData.toString()
        savingPreview = true
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val saved = if (target != null) {
                    check(editableProject(target.id).bundle.toJson() == target.bundle.toJson()) { "App changed since editing began" }
                    store.update(target.id, bundle)
                } else {
                    val created = store.save(bundle)
                    try {
                        check(context.getSharedPreferences("project.${created.id}", Context.MODE_PRIVATE).edit().putString("data",data).commit())
                        media.copyPreview(previewId,created.id)
                        // Last, after the preview's queued file requests: all its files move to the new app or none do.
                        files.serial { files.publishPreview(previewId,created.id) }
                    } catch(e: Exception) {
                        runCatching {store.delete(created.id)}
                        context.deleteSharedPreferences("project.${created.id}")
                        runCatching {media.delete(created.id)}
                        runCatching {files.serial { files.delete(created.id) }}
                        throw e
                    }
                    created
                }
                saved to listLibrary()
            } }
            savingPreview = false
            result.onSuccess { (saved, library) ->
                projectsEpoch++; projects = library // Newer than any library read still in flight.
                if (preview && projectId == previewId) openProject(saved) else syncPolicies()
                onSaved(historyId, saved)
            }.onFailure { if (preview && projectId == previewId) failure = "Could not save project locally" }
        }
    }
    fun deleteProject(project: SavedProject) {
        runCatching {
            requireProjectId(project.id)
            if (projectId == project.id) closeProject()
            store.delete(project.id)
            // The app's stored research and checks go with it.
            specs.delete(project.id)
            media.delete(project.id)
            // Queued after any request still running for it; startup cleanup removes the folder if this is cut short.
            files.later { files.delete(project.id) }
            // Its access to files on the phone ends (permissions no other app holds are released); the documents stay.
            phoneFiles.later({ phoneFiles.store.forget(project.id) }) { fileGrantsRevision++ }
            permissionPrefs.edit().apply { HardwareContract.OPERATIONS.keys.forEach {remove("${project.id}.$it")} }.apply()
            if (project.id in styleOptOuts) { styleOptOuts = styleOptOuts - project.id; stylePrefs.edit().putStringSet("optOut", styleOptOuts.toSet()).apply() }
            context.deleteSharedPreferences("project.${project.id}")
            context.getSharedPreferences("permissions", Context.MODE_PRIVATE).edit().remove("${project.id}.microphone").apply()
            // Update the launcher at once; the background reread also covers a library load still in flight.
            projects = projects.filter { it.id != project.id }
            refreshProjects()
            syncPolicies()
        }.onFailure { failure = "Could not delete project" }
    }
    fun restart() {
        if (!projectOpen) return
        stopHardware()
        stopFiles()
        runtime?.close()
        runtime = null
        broker.projectChanged()
        failure = null
        ui = null
        isolatedUid = null
        askPermission = false
        askAndroidPermission = false
        pendingAndroidProjectId = null
        hardwareIntent = false
        runtimeStatus = "Starting isolated runtime…"
        try {
            val bundle = checkNotNull(selectedBundle) { "App is no longer available" }
            capabilities = bundle.capabilities
            val sourceText = bundle.source
            val client = RuntimeClient(context, onReady = { uid ->
                isolatedUid = uid
                runtimeStatus = "Isolated runtime connected"
                runtime?.send(event("init").apply {
                    // Legacy dropD/reference preferences, still read at init by apps saved from the former built-in tuner.
                    add("settings", JsonObject().apply { val settings = previewEditTarget?.let { context.getSharedPreferences("project.${it.id}", Context.MODE_PRIVATE) } ?: projectPrefs; addProperty("dropD", settings.getBoolean("dropD", false)); addProperty("reference", settings.getInt("reference", 440)) })
                    add("device", deviceContext(openAppStyle))
                    add("storage", if (preview) previewData.deepCopy() else runCatching { JsonParser.parseString(projectPrefs.getString("data", "{}")).asJsonObject }.getOrDefault(JsonObject()))
                })
            }, onPacket = ::receive, onError = { message ->
                runtime = null
                stopHardware()
                broker.stop("Runtime stopped")
                failure = message
                runtimeStatus = "Runtime stopped"
            })
            runtime = client
            client.start(sourceText)
        } catch (e: Exception) { failure = e.message; runtimeStatus = "Project could not start" }
    }
    private fun receive(packet: UiPacket) {
        // Scripts often return an identical screen (a tuner while nothing is playing); JsonObject equality
        // is structural, so skip the state write and the recomposition it would trigger.
        if (packet.ui != ui) ui = packet.ui
        hardwareIntent = runtime?.responseIsUserAction == true
        for (item in packet.effects) {
            val effect = item.asJsonObject
            when (effect["type"].asString) {
                "hardware.request" -> {
                    if(effect["capability"].asString=="audio" && effect["operation"].asString in setOf("route","routeReset")) broker.stop("Changing audio route")
                    hardwareBroker().request(effect,hardwareIntent)
                }
                "microphone.start" -> { if (hardwareIntent) { hardwareIntent = false; broker.start() } }
                "microphone.stop" -> broker.stop()
                "storage.save" -> {
                    require("storage.project" in capabilities) { "Storage capability was not declared" }
                    val data = effect.getAsJsonObject("data")
                    require(data.toString().length <= 8192) { "Project storage limit exceeded" }
                    if (preview) previewData = data.deepCopy()
                    else projectPrefs.edit().putString("data", data.toString()).apply()
                }
                // No gesture or prompt: the folder is the app's own. The reply arrives later as a files event.
                FilesContract.EFFECT -> {
                    require(FilesContract.CAPABILITY in capabilities) { "Files capability was not declared" }
                    currentFilesSession().request(effect, busy = if (savingPreview) "PUB is saving this app. Try again after it reopens." else null)
                }
            }
        }
    }
    fun action(value: String) { if (!projectOpen) return; hardwareIntent = true; runtime?.send(event("action").apply { addProperty("action", value) }) }
    // Each keystroke carries the field's full text, so only the newest pending value per field is needed.
    fun input(action: String, value: String) { runtime?.send(event("input").apply { addProperty("action", action); addProperty("value", value.take(app.pub.core.UiPacket.MAX_MULTILINE_VALUE)) }, key = "input:$action") }
    fun setVisible(value: Boolean) {
        val returning = value && !visible
        visible = value
        broker.setForeground(value && projectOpen)
        if (!value) { endHardware(keepFilePicker = true);askPermission = false; askAndroidPermission = false }
        // An app back on screen can refresh what depends on the time (such as today's date) without a button.
        if (returning && projectOpen) runtime?.send(event("resume"), key = "resume")
    }
    fun stop() = broker.stop()
    fun allowSession() { val requested = askPermission; askPermission = false; if (requested && visible && projectOpen) broker.allowOnce() }
    fun allowProject() { val requested = askPermission; askPermission = false; if (requested && visible && projectOpen) { if(preview) broker.allowOnce() else broker.allowApp() }; syncPolicies() }
    fun deny() { askPermission = false; broker.denied() }
    fun androidPermissionResult(granted: Boolean) {
        val requestedProject = pendingAndroidProjectId
        pendingAndroidProjectId = null
        if (projectOpen && visible && requestedProject == projectId) broker.androidResult(granted)
    }
    fun blockGlobally(value: Boolean) { broker.globalBlocked = value; syncPolicies() }
    fun changeGlobalDefault(value: Rule) { broker.globalDefault = value; syncPolicies() }
    fun changeAppRule(value: Rule) { if(projectOpen && !preview) changeProjectRule(projectId, value) }
    fun microphoneRuleFor(id: String): Rule {
        requireProjectId(id)
        return projectMicrophoneRules[id] ?: Rule.INHERIT
    }
    fun changeProjectRule(id: String, value: Rule) {
        requireProjectId(id)
        if (projectOpen && projectId == id) broker.appRule = value
        else permissionPrefs.edit().putString("$id.microphone", value.name).apply()
        syncPolicies()
    }
    private fun requireProjectId(id: String) {
        // The legacy tuner ID is valid only for an existing saved package, like any UUID.
        require(ProjectStore.isAppId(id) && projects.any { it.id == id }) { "Unknown project" }
    }
    fun changeStyleGuide(value: StyleGuide) {
        styleGuide = value
        stylePrefs.edit().putString("guide", value.toJson()).apply()
    }
    /** Whether the saved app [id] follows the style guide (the default) instead of PUB's default style. */
    fun usesStyleGuide(id: String): Boolean = id !in styleOptOuts
    fun changeUsesStyleGuide(id: String, value: Boolean) {
        requireProjectId(id)
        styleOptOuts = if (value) styleOptOuts - id else styleOptOuts + id
        stylePrefs.edit().putStringSet("optOut", styleOptOuts.toSet()).apply()
    }
    /** The style app [id] is drawn and built in: the style guide, or PUB's default style when the app does not follow it. A new app (null) follows it. */
    fun styleFor(id: String?): StyleGuide = if (id == null || usesStyleGuide(id)) styleGuide else StyleGuide.DEFAULT
    /** The open app's style; a preview of an edit follows its saved app's choice, other previews the style guide. */
    val openAppStyle: StyleGuide get() = styleFor(if (preview) previewEditTarget?.id else projectId.takeIf { projectOpen && it.isNotBlank() })
    /** Whether the phone's dark theme is on, for guides that follow it. */
    private fun systemDark() = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private fun syncPolicies() {
        globalBlocked = broker.globalBlocked; globalDefault = broker.globalDefault
        appRule = if (projectOpen) broker.appRule else Rule.INHERIT
        projectMicrophoneRules = projects.map { it.id }.associateWith { id ->
            runCatching { Rule.valueOf(permissionPrefs.getString("$id.microphone", Rule.INHERIT.name)!!) }.getOrDefault(Rule.INHERIT)
        }
    }
    private var builderViewportWidthPx=0
    private var builderViewportHeightPx=0
    fun updateBuilderViewport(width:Int,height:Int) {
        builderViewportWidthPx=width.coerceAtLeast(0);builderViewportHeightPx=height.coerceAtLeast(0)
    }
    fun generationContext(editingId:String?=null): String = deviceContext(styleFor(editingId)).apply {
        add("buildEnvironment",app.pub.builder.BuildEnvironment.metadata())
        val metrics=context.resources.displayMetrics
        add("layoutContext",JsonObject().apply {
            addProperty("density",metrics.density)
            addProperty("orientation",if(context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
            addProperty("fontScale",context.resources.configuration.fontScale)
            addProperty("layoutDirection",if(context.resources.configuration.layoutDirection==android.view.View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr")
            if(builderViewportWidthPx>0 && builderViewportHeightPx>0) {
                addProperty("measuredBuilderViewportWidthDp",builderViewportWidthPx/metrics.density)
                addProperty("measuredBuilderViewportHeightDp",builderViewportHeightPx/metrics.density)
                addProperty("viewportNote","Measured builder viewport excluding host header/system insets and correcting for keyboard height. Running app host controls can change height.")
            }
            addProperty("contentPaddingDp",20);addProperty("columnSpacingDp",12)
        })
        add("permissionContext",JsonObject().apply {
            addProperty("foregroundOnly",true)
            addProperty("hardwarePresenceIsNotPermission",true)
            addProperty("microphoneGlobalDefault",globalDefault.name)
            if(editingId!=null) {
                requireProjectId(editingId)
                addProperty("microphoneAppRule",microphoneRuleFor(editingId).name)
                add("appHardwareRules",JsonObject().apply {HardwareContract.OPERATIONS.keys.forEach { cap ->
                    addProperty(cap,permissionPrefs.getString("$editingId.$cap",Rule.INHERIT.name))
                }})
            }
        })
        remove("hostUid"); remove("cloudConnection")
        addProperty("cameraPresent", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        addProperty("gpsPresent", context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))
        addProperty("cameraRuntimeAvailable", true); addProperty("gpsRuntimeAvailable", true)
        add("hardwareGloballyBlocked", com.google.gson.JsonArray().apply {HardwareContract.OPERATIONS.keys.filter {hardwareBlocked(it)}.forEach {add(it)}})
        addProperty("locale", context.resources.configuration.locales[0].toLanguageTag())
        addProperty("supportedCapabilities", HardwareContract.CAPABILITIES.sorted().joinToString(", "))
    }.toString()
    /** [style] is the style the app is drawn in; its colours, corners and font, never any text, go to the app and to builds. */
    private fun deviceContext(style: StyleGuide) = JsonObject().apply {
        addProperty("schemaVersion", 1)
        add("hardware", HardwareInventory.describe(context))
        addProperty("manufacturer", Build.MANUFACTURER)
        addProperty("model", Build.MODEL)
        addProperty("androidVersion", Build.VERSION.RELEASE)
        addProperty("apiLevel", Build.VERSION.SDK_INT)
        addProperty("runtime", "pub/1")
        addProperty("javascriptEngine", "Rhino interpreter")
        addProperty("microphonePresent", context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
        addProperty("microphoneGloballyBlocked", globalBlocked)
        addProperty("fontScale", context.resources.configuration.fontScale)
        addProperty("screenWidthDp", context.resources.configuration.screenWidthDp)
        addProperty("screenHeightDp", context.resources.configuration.screenHeightDp)
        addProperty("uiTheme", if (style.dark(systemDark())) "dark" else "light")
        add("styleGuide", style.toJsonObject())
        addProperty("hostUid", Process.myUid())
        addProperty("cloudConnection", false)
    }
    private fun event(type: String) = JsonObject().apply { addProperty("type", type) }
    override fun onCleared() { hardware?.close();stopFiles();runtime?.close(); broker.close(); super.onCleared() }
    private companion object { const val HARDWARE_STREAM = "hardware:" }
}
