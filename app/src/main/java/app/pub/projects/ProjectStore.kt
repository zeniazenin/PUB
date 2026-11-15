package app.pub.projects

import android.content.Context
import android.util.AtomicFile
import app.pub.core.ProjectBundle
import java.io.File
import java.util.UUID

data class SavedProject(val id: String, val bundle: ProjectBundle)
/** The local app library: one atomic package file per saved app at filesDir/library/<id>.json. */
class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }
    fun list(): List<SavedProject> = root.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
        runCatching { read(it.nameWithoutExtension) }.getOrNull()
    }.sortedBy { it.bundle.name.lowercase() }
    /** True when the library holds no package file at all, readable or not (an AtomicFile backup counts). */
    fun isEmpty(): Boolean = root.listFiles().orEmpty().none { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
    /** True when a package file exists for [id], readable or not (an AtomicFile backup counts). */
    fun contains(id: String): Boolean {
        require(isAppId(id))
        return File(root, "$id.json").exists() || File(root, "$id.json.bak").exists()
    }
    /** Saves a new app. New apps always get a fresh UUID; [LEGACY_TUNER_ID] is never assigned. */
    fun save(bundle: ProjectBundle): SavedProject {
        val id = UUID.randomUUID().toString()
        return write(id, bundle)
    }
    fun read(id: String): SavedProject? {
        require(isAppId(id))
        val file = AtomicFile(File(root, "$id.json"))
        if (!file.baseFile.exists() && !File(root, "$id.json.bak").exists()) return null
        val json = file.openRead().use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) { val count = input.read(chunk); if (count < 0) break; require(buffer.size() + count <= ProjectBundle.MAX_PACKAGE_BYTES); buffer.write(chunk, 0, count) }
            val bytes = buffer.toByteArray()
            require(bytes.size <= ProjectBundle.MAX_PACKAGE_BYTES)
            bytes.toString(Charsets.UTF_8)
        }
        return SavedProject(id, ProjectBundle.parse(json))
    }
    /** Replaces an existing app's package under the same ID; never creates an app. */
    fun update(id: String, bundle: ProjectBundle): SavedProject {
        require(isAppId(id))
        check(read(id) != null) { "App was deleted" }
        return write(id, bundle)
    }
    private fun write(id: String, bundle: ProjectBundle): SavedProject {
        bundle.requireReadyAssets()
        val file = AtomicFile(File(root, "$id.json"))
        val stream = file.startWrite()
        try { stream.write(bundle.toJson().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        return SavedProject(id, bundle)
    }
    fun delete(id: String) {
        require(isAppId(id))
        AtomicFile(File(root, "$id.json")).delete()
        check(!File(root, "$id.json").exists()) { "Could not delete project" }
    }

    companion object {
        /**
         * Legacy ID of an app saved from the former built-in tuner. Builds that bundled the tuner stored an edited
         * copy at library/guitar-tuner.json, with its data, photos, permission rules, spec and prompt history under
         * this ID. Current builds ship no tuner and never assign or create this ID (new apps always get UUIDs); it
         * stays valid only so such an existing saved package keeps working like any other app. No data is migrated.
         */
        const val LEGACY_TUNER_ID = "guitar-tuner"

        /** Host-assigned app IDs only: a canonical UUID or [LEGACY_TUNER_ID], so an ID can never become another path. */
        fun isAppId(id: String): Boolean =
            id == LEGACY_TUNER_ID || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
