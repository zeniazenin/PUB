package app.pub.builder

import android.content.Context
import android.util.AtomicFile
import app.pub.core.ProjectBundle
import app.pub.projects.SavedProject
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Durable builder state only: package assets are included; live app data, captures and credentials are not. */
data class BuildDraftSnapshot(
    val prompt: String,
    val sessionId: String,
    val editingProject: SavedProject?,
    val draft: ProjectBundle?,
    val draftHistoryId: String?,
    val draftRequest: String,
    val draftModel: String,
    val repairPass: Int,
    val message: String?,
    val visualReview: String?,
    val visualReviewError: String?,
    val running: Boolean
)

/** Safe error text for the controller; the underlying cause is never incorporated into the message. */
class BuildDraftStoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Atomic singleton-controller snapshot. No network, replay, auto-clear or recovery policy lives here.
 * A running snapshot is returned unchanged so its owner can mark it interrupted without retrying.
 * Reads/writes throw on corrupt or unsupported files; only explicit clear() removes those files.
 * All instances share a lock because AtomicFile itself does not provide concurrent-writer protection.
 */
class BuildDraftStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "builder-draft.json"))

    fun read(): BuildDraftSnapshot? = synchronized(lock) {
        guarded("The saved builder draft could not be read. Its file has been preserved.") { readLocked() }
    }
    fun write(snapshot: BuildDraftSnapshot) = synchronized(lock) {
        guarded("The builder draft could not be saved. The previous file has been preserved.") {
            val json = encode(snapshot)
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_SNAPSHOT_BYTES) { "Snapshot size limit" }
            // Validate the proposed state and the old file before beginning an atomic replacement.
            decode(json)
            readLocked()
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
    }
    fun clear() = synchronized(lock) {
        guarded("The saved builder draft could not be cleared.") {
            file.delete()
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() && !File(file.baseFile.path + ".new").exists())
        }
    }
    private fun readLocked(): BuildDraftSnapshot? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.openRead().use { input ->
            require(input.channel.size() <= MAX_SNAPSHOT_BYTES) { "Snapshot size limit" }
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= MAX_SNAPSHOT_BYTES) { "Snapshot size limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            val value = readJson(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT)
            require(value.isJsonObject)
            value.asJsonObject
        }
        return decode(root)
    }
    private fun encode(snapshot: BuildDraftSnapshot) = JsonObject().apply {
        addProperty("version", 1); addProperty("prompt", snapshot.prompt); addProperty("sessionId", snapshot.sessionId)
        add("editingProject", snapshot.editingProject?.let { project -> JsonObject().apply {
            addProperty("id", project.id); add("bundle", bundleJson(project.bundle))
        } } ?: JsonNull.INSTANCE)
        add("draft", snapshot.draft?.let(::bundleJson) ?: JsonNull.INSTANCE)
        addProperty("draftHistoryId", snapshot.draftHistoryId); addProperty("draftRequest", snapshot.draftRequest)
        addProperty("draftModel", snapshot.draftModel); addProperty("repairPass", snapshot.repairPass)
        addProperty("message", snapshot.message); addProperty("visualReview", snapshot.visualReview)
        addProperty("visualReviewError", snapshot.visualReviewError); addProperty("running", snapshot.running)
    }
    private fun bundleJson(bundle: ProjectBundle): JsonObject {
        val text = bundle.toJson()
        require(text.toByteArray(Charsets.UTF_8).size <= ProjectBundle.MAX_PACKAGE_BYTES)
        return JsonParser.parseString(text).asJsonObject
    }
    private fun decode(root: JsonObject): BuildDraftSnapshot {
        require(root.keySet() == FIELDS) { "Unexpected draft fields" }
        require(integer(root, "version") == 1) { "Unsupported draft version" }
        val session = string(root, "sessionId", 36).also { require(validUuid(it)) }
        val history = nullableString(root, "draftHistoryId", 36)?.also { require(validUuid(it)) }
        val pass = integer(root, "repairPass").also { require(it in 0..2) }
        val editing = root["editingProject"].let { value ->
            if (value.isJsonNull) null else {
                require(value.isJsonObject)
                val project = value.asJsonObject
                require(project.keySet() == setOf("id", "bundle"))
                val id = string(project, "id", 36).also { require(it == "guitar-tuner" || validUuid(it)) }
                SavedProject(id, decodeBundle(project["bundle"]))
            }
        }
        val draft = root["draft"].let { if (it.isJsonNull) null else decodeBundle(it) }
        require(root["running"].isJsonPrimitive && root["running"].asJsonPrimitive.isBoolean)
        return BuildDraftSnapshot(string(root, "prompt", 8000), session, editing, draft, history,
            string(root, "draftRequest", 8000), string(root, "draftModel", 200), pass,
            nullableString(root, "message", 1000), nullableString(root, "visualReview", 6000),
            nullableString(root, "visualReviewError", 6000), root["running"].asBoolean)
    }
    private fun decodeBundle(value: JsonElement): ProjectBundle {
        require(value.isJsonObject)
        val text = value.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= ProjectBundle.MAX_PACKAGE_BYTES)
        return ProjectBundle.parse(text)
    }
    private fun string(root: JsonObject, key: String, max: Int): String {
        val value = root[key]
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.also { require(it.length <= max) }
    }
    private fun nullableString(root: JsonObject, key: String, max: Int): String? =
        if (root[key].isJsonNull) null else string(root, key, max)
    private fun integer(root: JsonObject, key: String): Int {
        val value = root[key]
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.intValueExact()
    }
    /** Reject duplicate fields, non-JSON extensions and deep input before constructing a large tree. */
    private fun readJson(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 24) { "JSON nesting limit" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) { val key = reader.nextName(); require(!has(key)) { "Duplicate field" }; add(key, readJson(reader, depth + 1)) }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray(); while (reader.hasNext()) add(readJson(reader, depth + 1)); reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException("Unexpected JSON token")
        }
    }
    private inline fun <T> guarded(message: String, block: () -> T): T = try { block() }
        catch (error: BuildDraftStoreException) { throw error }
        catch (error: Exception) { throw BuildDraftStoreException(message, error) }
    companion object {
        const val MAX_SNAPSHOT_BYTES = 40 * 1024 * 1024
        private val FIELDS = setOf("version", "prompt", "sessionId", "editingProject", "draft", "draftHistoryId", "draftRequest", "draftModel", "repairPass", "message", "visualReview", "visualReviewError", "running")
        private val lock = Any()
        private fun validUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
