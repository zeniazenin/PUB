package app.pub.projects

import android.content.Context
import android.util.AtomicFile
import app.pub.core.AppSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Safe error text for callers; the underlying cause and file content are never incorporated into the message. */
class AppSpecStoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * One private, atomic spec file per saved app at filesDir/app-specs/<id>.json: the request the app was first
 * built from plus its latest saved build's research brief and behavior checks ([AppSpec]). Never app packages,
 * saved data, photos, device context or credentials.
 *
 * Fail-closed: [read] returns null when an app has no spec and throws [AppSpecStoreException] when its file is
 * corrupt, unsupported or oversized; such a file is left untouched (callers then build without it). [write]
 * validates the spec before replacing the file, so the next saved build supersedes an unreadable spec. All
 * instances share a lock because AtomicFile itself does not provide concurrent-writer protection.
 */
class AppSpecStore(context: Context) {
    private val root = File(context.filesDir, "app-specs")

    fun read(id: String): AppSpec? = synchronized(lock) {
        val file = fileFor(id)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
        guarded("This app's saved research and checks could not be read. The file has been kept.") {
            val bytes = file.openRead().use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= MAX_FILE_BYTES) { "Spec size limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            AppSpec.parse(text)
        }
    }

    fun write(id: String, spec: AppSpec) = synchronized(lock) {
        val file = fileFor(id)
        guarded("This app's research and checks could not be saved. The previous file has been kept.") {
            val json = spec.toJson()
            AppSpec.parse(json) // Never write what could not be read back.
            val bytes = json.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_FILE_BYTES) { "Spec size limit" }
            check(root.isDirectory || root.mkdirs()) { "Spec directory unavailable" }
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
    }

    /** Removes the app's spec (and any AtomicFile leftovers); a missing spec is not an error. */
    fun delete(id: String) = synchronized(lock) {
        val file = fileFor(id)
        guarded("This app's saved research and checks could not be deleted.") {
            file.delete()
            File(file.baseFile.path + ".new").delete()
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() && !File(file.baseFile.path + ".new").exists())
        }
    }

    /** Host-assigned app IDs only (the bundled tuner or a canonical UUID), so an ID can never become another path. */
    private fun fileFor(id: String): AtomicFile {
        require(id == "guitar-tuner" || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Unknown app" }
        return AtomicFile(File(root, "$id.json"))
    }

    private inline fun <T> guarded(message: String, block: () -> T): T = try { block() }
        catch (error: AppSpecStoreException) { throw error }
        catch (error: Exception) { throw AppSpecStoreException(message, error) }

    companion object {
        /** UTF-8 can take up to three bytes per stored character. */
        const val MAX_FILE_BYTES = 3 * AppSpec.MAX_JSON_CHARS
        private val lock = Any()
    }
}
