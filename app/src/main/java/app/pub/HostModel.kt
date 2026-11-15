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
import app.pub.core.PermissionPolicy.Rule
import app.pub.hardware.*
import app.pub.core.HardwareContract
import app.pub.core.UiPacket
import app.pub.core.ProjectBundle
import app.pub.projects.ProjectStore
import app.pub.projects.SavedProject
import app.pub.permissions.PermissionBroker
import app.pub.runtime.RuntimeClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class HostModel(application: Application) : AndroidViewModel(application) {
    @SuppressLint("StaticFieldLeak") // Application context only; never retains an Activity.
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("host", Context.MODE_PRIVATE)
    private val permissionPrefs = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)
    private val store = ProjectStore(context)
    private val projectPrefs get() = context.getSharedPreferences("project.$projectId", Context.MODE_PRIVATE)
    var projects by mutableStateOf(store.list().filter { it.id != "guitar-tuner" })
        private set
    var tunerVisible by mutableStateOf(!prefs.getBoolean("tuner.deleted", false)); private set
    var previewHistoryId: String? = null; private set
    fun graphicAsset(id: String): JsonObject? = selectedBundle?.assets?.get(id)?.asJsonObject
    var previewEditTarget by mutableStateOf<SavedProject?>(null); private set
    private var originalTuner: SavedProject? = null
    fun editableProject(id: String): SavedProject {
        store.read(id)?.let { return it }
        check(id == "guitar-tuner" && tunerVisible) { "App is no longer available" }
        originalTuner?.let { return it }
        return SavedProject(id, ProjectBundle.parse(JsonObject().apply {
            addProperty("name", "Guitar Tuner"); addProperty("description", "Offline guitar tuner with standard and Drop D tuning")
            addProperty("source", context.assets.open("projects/guitar-tuner/main.js").bufferedReader().use { it.readText() })
            add("assets", JsonParser.parseString(context.assets.open("projects/guitar-tuner/graphics.json").bufferedReader().use { it.readText() }).asJsonObject)
            addProperty("icon", "guitar")
            add("capabilities", com.google.gson.JsonArray().apply { add("microphone.pitch"); add("storage.project") })
        }.toString())).also { originalTuner = it }
    }
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
    fun changeHardwareBlock(cap: String,value: Boolean) { require(cap in HardwareContract.OPERATIONS);permissionPrefs.edit().putBoolean("$cap.blocked",value).apply();stopHardware();hardwareRevision++;updateContext() }
    fun changeHardwareDefault(cap: String,value: Rule) { require(cap in HardwareContract.OPERATIONS && value in setOf(Rule.ASK,Rule.ALLOW));permissionPrefs.edit().putString("$cap.default",value.name).apply();stopHardware();hardwareRevision++ }
    fun changeHardwareRule(id: String,cap: String,value: Rule) { requireProjectId(id);require(cap in HardwareContract.OPERATIONS);permissionPrefs.edit().putString("$id.$cap",value.name).apply();stopHardware();hardwareRevision++ }
    fun stopHardware() { hardwareEpoch++;hardware?.close();hardware=null;hardwarePrompt=null;hardwareAndroidRequest=null;devicePicker=null;cameraRequest=null;nfcRequest=null;hardwareRevision++;broker.stop();runtime?.send(event("hardwareStopped")) }
    private fun hardwareBroker(): HardwareBroker {
        if(hardware?.isClosed==true) {hardware=null;hardwareEpoch++}
        return hardware ?: run {
            val epoch=hardwareEpoch
            HardwareBroker(context,projectId,preview,capabilities,{ visible && projectOpen && hardwareEpoch==epoch },
                {if(hardwareEpoch==epoch) hardwarePrompt=it},{if(hardwareEpoch==epoch) hardwareAndroidRequest=it},{if(hardwareEpoch==epoch) devicePicker=it},
                {callback->if(hardwareEpoch==epoch) cameraRequest=callback},{callback->if(hardwareEpoch==epoch) nfcRequest=callback},
                {packet,disposable->if(hardwareEpoch==epoch) { if(packet["event"]?.asString=="audio.routeLost") broker.stop("Audio route disconnected");runtime?.send(packet,disposable) }},
                {if(hardware?.isClosed==true) {broker.stop("Hardware access ended");runtime?.send(event("hardwareStopped"))};hardwareRevision++;updateContext()}).also {hardware=it}
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
    var accent by mutableStateOf(prefs.getString("accent", "moss") ?: "moss")
        private set
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
    var contextJson by mutableStateOf("")
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

    init { runCatching {media.cleanupTemporary()};syncPolicies() }
    fun openProject(project: SavedProject?) {
        if (project != null) requireProjectId(project.id)
        closeProject()
        selectedBundle = project?.bundle ?: editableProject("guitar-tuner").bundle
        projectId = project?.id ?: "guitar-tuner"
        projectName = selectedBundle?.name ?: "Guitar Tuner"
        projectDescription = project?.bundle?.description ?: ""
        preview = false; previewData = JsonObject()
        projectOpen = true
        broker.setForeground(visible)
        syncPolicies(); restart()
    }
    fun closeProject() {
        stopHardware()
        if(preview && projectId.isNotBlank()) media.delete(projectId)
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
        projectOpen = true
        broker.setForeground(visible)
        syncPolicies(); restart()
    }
    fun savePreview() {
        if (!preview || ui == null || failure != null) return
        runCatching {
            val target = previewEditTarget
            if (target != null) {
                check(editableProject(target.id).bundle.toJson() == target.bundle.toJson()) { "App changed since editing began" }
                val updated = store.update(target.id, selectedBundle!!)
                projects = store.list().filter { it.id != "guitar-tuner" }
                openProject(updated)
                return@runCatching
            }
            val saved = store.save(selectedBundle!!)
            try {
                check(context.getSharedPreferences("project.${saved.id}", Context.MODE_PRIVATE).edit().putString("data",previewData.toString()).commit())
                media.copyPreview(projectId,saved.id)
            } catch(e: Exception) {
                runCatching {store.delete(saved.id)}
                context.deleteSharedPreferences("project.${saved.id}")
                runCatching {media.delete(saved.id)}
                throw e
            }
            projects = store.list().filter { it.id != "guitar-tuner" }; openProject(saved)
        }.onFailure { failure = "Could not save project locally" }
    }
    fun deleteProject(project: SavedProject) {
        runCatching {
            requireProjectId(project.id)
            if (projectId == project.id) closeProject()
            if (project.id == "guitar-tuner") {
                check(prefs.edit().putBoolean("tuner.deleted", true).commit())
                tunerVisible = false
            }
            store.delete(project.id)
            media.delete(project.id)
            permissionPrefs.edit().apply { HardwareContract.OPERATIONS.keys.forEach {remove("${project.id}.$it")} }.apply()
            context.deleteSharedPreferences("project.${project.id}")
            context.getSharedPreferences("permissions", Context.MODE_PRIVATE).edit().remove("${project.id}.microphone").apply()
            projects = store.list().filter { it.id != "guitar-tuner" }
            syncPolicies()
        }.onFailure { failure = "Could not delete project" }
    }
    fun restart() {
        if (!projectOpen) return
        stopHardware()
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
            val sourceText: String
            if (selectedBundle != null) {
                capabilities = selectedBundle!!.capabilities
                sourceText = selectedBundle!!.source
            } else {
            val directory = File(context.filesDir, "projects/guitar-tuner").apply { mkdirs() }
            for (name in listOf("manifest.json", "main.js")) {
                val file = File(directory, name)
                if (!file.exists()) context.assets.open("projects/guitar-tuner/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            val manifestFile = File(directory, "manifest.json")
            require(manifestFile.length() <= 8192) { "Manifest too large" }
            val manifest = JsonParser.parseString(manifestFile.readText()).asJsonObject
            require(manifest["runtime"].asString == "pub/1" && manifest["entrypoint"].asString == "main.js") { "Unsupported project runtime" }
            capabilities = manifest["capabilities"].asJsonArray.map { it.asString }.toSet()
            require(capabilities.all { it in HardwareContract.CAPABILITIES }) { "Project requires unsupported capabilities" }
            val source = File(directory, "main.js")
            require(source.length() <= 65536) { "Script too large" }
            sourceText = source.readText()
            }
            val client = RuntimeClient(context, onReady = { uid ->
                isolatedUid = uid
                runtimeStatus = "Isolated runtime connected"
                runtime?.send(event("init").apply {
                    add("settings", JsonObject().apply { val settings = previewEditTarget?.let { context.getSharedPreferences("project.${it.id}", Context.MODE_PRIVATE) } ?: projectPrefs; addProperty("dropD", settings.getBoolean("dropD", false)); addProperty("reference", settings.getInt("reference", 440)) })
                    add("device", deviceContext())
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
            updateContext()
        } catch (e: Exception) { failure = e.message; runtimeStatus = "Project could not start" }
    }
    private fun receive(packet: UiPacket) {
        ui = packet.ui
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
                "settings.save" -> {
                    require(projectId == "guitar-tuner") { "Use storage.save for generated projects" }
                    require("storage.project" in capabilities) { "Storage capability was not declared" }
                    val reference = effect["reference"].asInt
                    require(reference in 430..450)
                    projectPrefs.edit().putBoolean("dropD", effect["dropD"].asBoolean).putInt("reference", reference).apply()
                }
                "storage.save" -> {
                    require("storage.project" in capabilities) { "Storage capability was not declared" }
                    val data = effect.getAsJsonObject("data")
                    require(data.toString().length <= 8192) { "Project storage limit exceeded" }
                    if (preview) previewData = data.deepCopy()
                    else projectPrefs.edit().putString("data", data.toString()).apply()
                }
            }
        }
    }
    fun action(value: String) { if (!projectOpen) return; hardwareIntent = true; runtime?.send(event("action").apply { addProperty("action", value) }) }
    fun input(action: String, value: String) { runtime?.send(event("input").apply { addProperty("action", action); addProperty("value", value.take(2000)) }) }
    fun setVisible(value: Boolean) {
        visible = value
        broker.setForeground(value && projectOpen)
        if (!value) { stopHardware();askPermission = false; askAndroidPermission = false }
    }
    fun stop() = broker.stop()
    fun allowSession() { val requested = askPermission; askPermission = false; if (requested && visible && projectOpen) broker.allowOnce() }
    fun allowProject() { val requested = askPermission; askPermission = false; if (requested && visible && projectOpen) { if(preview) broker.allowOnce() else broker.allowApp() }; syncPolicies() }
    fun deny() { askPermission = false; broker.denied() }
    fun androidPermissionResult(granted: Boolean) {
        val requestedProject = pendingAndroidProjectId
        pendingAndroidProjectId = null
        if (projectOpen && visible && requestedProject == projectId) broker.androidResult(granted)
        updateContext()
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
        require(id == "guitar-tuner" || (runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false) && projects.any { it.id == id })) { "Unknown project" }
    }
    fun changeAccent(value: String) {
        require(value in setOf("moss", "blue", "violet"))
        accent = value
        prefs.edit().putString("accent", value).apply()
        updateContext()
    }
    private fun syncPolicies() {
        globalBlocked = broker.globalBlocked; globalDefault = broker.globalDefault
        appRule = if (projectOpen) broker.appRule else Rule.INHERIT
        projectMicrophoneRules = (listOf("guitar-tuner") + projects.map { it.id }).associateWith { id ->
            runCatching { Rule.valueOf(permissionPrefs.getString("$id.microphone", Rule.INHERIT.name)!!) }.getOrDefault(Rule.INHERIT)
        }
        updateContext()
    }
    private fun updateContext() { contextJson = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(deviceContext()) }
    private var builderViewportWidthPx=0
    private var builderViewportHeightPx=0
    fun updateBuilderViewport(width:Int,height:Int) {
        builderViewportWidthPx=width.coerceAtLeast(0);builderViewportHeightPx=height.coerceAtLeast(0)
    }
    fun generationContext(editingId:String?=null): String = deviceContext().apply {
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
        addProperty("uiTheme", "dark"); addProperty("locale", context.resources.configuration.locales[0].toLanguageTag())
        addProperty("supportedCapabilities", HardwareContract.CAPABILITIES.sorted().joinToString(", "))
    }.toString()
    private fun deviceContext() = JsonObject().apply {
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
        addProperty("accent", accent)
        addProperty("hostUid", Process.myUid())
        addProperty("cloudConnection", false)
    }
    private fun event(type: String) = JsonObject().apply { addProperty("type", type) }
    override fun onCleared() { hardware?.close();runtime?.close(); broker.close(); super.onCleared() }
}
