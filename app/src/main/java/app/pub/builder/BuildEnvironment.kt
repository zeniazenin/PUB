package app.pub.builder

import app.pub.BuildConfig
import app.pub.core.FilesContract
import app.pub.core.PhoneFilesContract
import app.pub.core.ScriptEngine
import app.pub.core.ProjectBundle
import app.pub.hardware.ProjectMedia
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Explicit target-platform brief, also sent on edits and automatic repairs. No private device data. */
object BuildEnvironment {
    fun metadata()=JsonObject().apply {
        addProperty("host","PUB")
        addProperty("hostVersion",BuildConfig.VERSION_NAME)
        addProperty("artifact","One local ProjectBundle JSON package; not an APK or website")
        addProperty("hostStack","Android Kotlin/Java 17, Jetpack Compose Material 3 native renderer, Android hardware brokers")
        addProperty("runtime","pub/1: Rhino 1.7.15.1 ES6 interpreter in an isolated Android service over Binder")
        addProperty("minimumAndroidApi",31);addProperty("hostTargetAndroidApi",35)
        addProperty("deployment","Saved privately in PUB on this phone; offline foreground execution")
        add("limits",JsonObject().apply {
            addProperty("sourceCharacters",ScriptEngine.MAX_SOURCE)
            addProperty("eventAndUiPacketCharacters",ScriptEngine.MAX_MESSAGE)
            addProperty("packageBytes",ProjectBundle.MAX_PACKAGE_BYTES)
            addProperty("executionBudgetMsPerLoadOrDispatch",150)
            addProperty("interpreterStackDepth",128)
            addProperty("uiNodes",60);addProperty("uiNestingDepth",8);addProperty("effectsPerDispatch",4)
            addProperty("projectStorageJsonCharacters",8192)
            addProperty("graphicsPerPackage",8);addProperty("generatedPngAssets",2);addProperty("visibleImageOrGraphicNodes",4)
            addProperty("vectorShapesPerAsset",128);addProperty("highlightedShapes",16)
            addProperty("savedPhotos",ProjectMedia.MAX_PHOTOS)
            addProperty("photoBytes",ProjectMedia.MAX_PHOTO_BYTES);addProperty("projectPhotoBytes",ProjectMedia.MAX_PROJECT_BYTES)
            addProperty("appFilesBytes",FilesContract.QUOTA_BYTES);addProperty("appFilesEntries",FilesContract.QUOTA_ENTRIES)
            addProperty("fileChunkBytes",FilesContract.MAX_CHUNK_BYTES);addProperty("fileListEntriesPerReply",FilesContract.MAX_LIST_ENTRIES)
            addProperty("filePathCharacters",FilesContract.MAX_PATH_CHARS);addProperty("filePathDepth",FilesContract.MAX_DEPTH)
            addProperty("fileNameCharacters",FilesContract.MAX_NAME_CHARS);addProperty("pendingFileRequests",FilesContract.MAX_PENDING)
            // Files the user picks on the phone (hardware capability files).
            addProperty("pickedFilesAndFoldersPerApp",PhoneFilesContract.MAX_GRANTS);addProperty("filesPerMultiplePick",PhoneFilesContract.MAX_PICK)
            addProperty("pickedFolderNameCharacters",PhoneFilesContract.MAX_NAME_CHARS);addProperty("pickedFolderPathDepth",PhoneFilesContract.MAX_DEPTH)
            addProperty("pickedFolderEntriesListed",PhoneFilesContract.MAX_FOLDER_CHILDREN);addProperty("pendingPhoneFileRequests",PhoneFilesContract.MAX_PENDING)
        })
        add("uiComponents",JsonArray().apply {listOf("column","card","row","text","item","button","input","choices","meter","image","graphic").forEach {add(it)}})
        add("microphonePitch",JsonObject().apply {
            addProperty("minimumHz",65);addProperty("maximumHz",400)
            addProperty("measurement","Host pitch estimates only, no raw audio or recording. Frequency range does not cover all bass/ukulele notes.")
        })
        add("buildVerification",JsonObject().apply {
            addProperty("cleanInitialViewportScreenshot",true);addProperty("automaticRepairAttempts",BuilderModel.MAX_VISUAL_REPAIRS)
            addProperty("savedDataAndHardwareAvailableDuringReview",false)
            addProperty("scope","Visible initial screen only; hardware, interactions, scrolling content and numerical correctness require separate testing")
        })
    }
    val CONTRACT="""
        BUILD ENVIRONMENT AND DELIVERY TARGET:
        You are building a mini-app inside PUB on the user's Android phone, not developing the PUB host itself.
        The host is Kotlin/Java Android with Jetpack Compose Material 3. Deliver ONLY one ProjectBundle JSON
        containing synchronous JavaScript source and optional native graphical assets. Do not output Kotlin,
        Java, an APK, Gradle project, web page, React, HTML/CSS, SVG, npm packages, Docker or shell commands.
        JavaScript executes offline in a separate Android isolated service using the Rhino ES6 interpreter.
        It is not a browser, Node.js, a WebView or a container. There is no DOM, network client, filesystem API
        (only the host's files.request effect on the app's private folder, and the files hardware capability for documents
        the user picks in Android's file picker), Java/Android bridge, library installation, imports, promises,
        async/await, timers, workers or background execution.
        All native behavior must use the exact declarative UI/event/effect contracts below. Unknown APIs and
        properties are not implemented. Hardware presence in the inventory does not imply a supported broker operation.
        Init and every event must synchronously return a complete UI packet quickly: load/dispatch budget150ms,
        interpreter stack128, source65536 characters, event/output32768 characters. Avoid unbounded work or recursion.
        UI is a vertically scrollable native surface with20dp padding. Column children are centered with12dp gaps.
        Buttons and inputs use available width; buttons are at least52dp high; choices wrap in rows. There are no
        arbitrary layout/style properties. Text uses the user's font scaling and native theme. Use short labels,
        clear hierarchy and compact content; put primary actions near the top. Graphics are aspect-fit native assets
        with a bounded height (graphic node up to340dp). Vector coordinates are asset-space units, not screen pixels.
        Respect the provided measured preview viewport when available; phone resolution includes system/host chrome.
        The host owns Back/Close/Save, safe insets, scrolling, permissions, microphone capture and image decoding.
        Saved app JSON state, private files and camera photos are local and scoped. Edits receive current code and asset
        definitions, not saved personal data, files or photos. Preserve storage keys, file paths and existing asset IDs where possible.
        Runtime microphone.pitch currently estimates65..400Hz; never promise reliable notes outside that band.
        Its status events report active recording or a terminal stop/denial/failure. Keep a Start action available
        after denial/stop; wait for status rather than assuming permission. No microphone effects during init.
        Visual review captures a clean initial UI with empty storage, no file replies and no hardware access, so it cannot validate
        listening, pitch accuracy, button behavior or persisted state. Never change runtime behavior merely to make
        an empty-data screenshot look successful. Repairs must preserve the original user requirements and capabilities.
        Match the exact supported contracts below; if a requirement cannot be met, state the limitation honestly.
    """.trimIndent()
}
