package app.pub.projects

import android.content.Context
import android.util.AtomicFile
import app.pub.core.ProjectBundle
import java.io.File
import java.util.UUID

data class SavedProject(val id: String, val bundle: ProjectBundle)
class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }
    fun list(): List<SavedProject> = root.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
        runCatching { read(it.nameWithoutExtension) }.getOrNull()
    }.sortedBy { it.bundle.name.lowercase() }
    fun save(bundle: ProjectBundle): SavedProject {
        val id = UUID.randomUUID().toString()
        return write(id, bundle)
    }
    fun read(id: String): SavedProject? {
        require(id == "guitar-tuner" || UUID.fromString(id).toString() == id)
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
    fun update(id: String, bundle: ProjectBundle): SavedProject {
        require(id == "guitar-tuner" || UUID.fromString(id).toString() == id)
        check(id == "guitar-tuner" || read(id) != null) { "App was deleted" }
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
        require(id == "guitar-tuner" || UUID.fromString(id).toString() == id)
        AtomicFile(File(root, "$id.json")).delete()
        check(!File(root, "$id.json").exists()) { "Could not delete project" }
    }
}
