package app.pub.builder

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Submitted instructions, request metadata, optional advisory AI review and short research and behavior-check
 * summaries; never app code, research sources' content, context or credentials.
 */
data class PromptHistoryEntry(
    val id: String,
    val createdAtMillis: Long,
    val prompt: String,
    val model: String,
    val kind: String,
    val status: String,
    val projectId: String?,
    val title: String,
    val interrupted: Boolean = false,
    val sessionId: String? = null,
    val review: String? = null,
    val automatic: Boolean = false,
    /** How this request was researched, e.g. "Researched on the web · tip calculator · 6 sources", and the summary. */
    val research: String? = null,
    /** This request's behavior-check outcome, e.g. "5 of 6 behavior checks passed", and the failing checks. */
    val checks: String? = null
)

class PromptHistoryFullException : IllegalStateException("Prompt history storage is full. Existing app histories have been preserved.")

/** Atomic private journal, newest first; at most 200 requests per app/construction session.
 * The aggregate byte limit rejects new writes rather than silently deleting another app's history.
 * Corrupt/unknown files fail closed and are never overwritten.
 */
class PromptHistoryStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "prompt-history.json"))

    fun list(projectId: String? = null, sessionId: String? = null): List<PromptHistoryEntry> = synchronized(lock) {
        require(projectId == null || validProjectId(projectId))
        require(sessionId == null || validUuid(sessionId))
        read().filter { if (projectId != null) it.projectId == projectId else sessionId == null || it.sessionId == sessionId }
    }
    fun begin(prompt: String, model: String, projectId: String? = null, title: String = "New app", sessionId: String? = null, automatic: Boolean = false): PromptHistoryEntry = synchronized(lock) {
        require(prompt.isNotBlank() && prompt.length <= 8000) { "Instructions must contain 1 to 8000 characters" }
        require(model.isNotBlank() && model.length <= 200)
        require(projectId == null || validProjectId(projectId))
        require(sessionId == null || validUuid(sessionId))
        require(title.length <= 120)
        val old = read()
        // A construction session can become one saved app, never several unrelated apps.
        require(sessionId == null || old.none { it.sessionId == sessionId && it.projectId != null && it.projectId != projectId }) {
            "This construction session already belongs to another app"
        }
        val entry = PromptHistoryEntry(UUID.randomUUID().toString(), System.currentTimeMillis(), prompt, model,
            if (projectId == null) "create" else "edit", "building", projectId, title, sessionId = sessionId, automatic = automatic)
        write(retained(listOf(entry) + old))
        entry
    }
    fun update(id: String, status: String, title: String? = null): Boolean = synchronized(lock) {
        require(status in setOf("ready", "failed", "cancelled"))
        require(title == null || title.length <= 120)
        val old = read()
        val index = old.indexOfFirst { it.id == id }
        if (index < 0 || old[index].status == "saved") return@synchronized false
        val changed = old.toMutableList()
        changed[index] = changed[index].copy(status = status, title = title ?: changed[index].title, interrupted = false)
        write(changed)
        true
    }
    fun recordReview(id: String, text: String): Boolean = synchronized(lock) {
        require(text.isNotBlank() && text.length <= MAX_REVIEW_CHARS) { "Review must contain 1 to 6000 characters" }
        val old = read()
        if (old.none { it.id == id }) return@synchronized false
        write(old.map { if (it.id == id) it.copy(review = text) else it })
        true
    }
    /** Records how [id]'s build was researched; replaces an earlier note for the same request. */
    fun recordResearch(id: String, text: String): Boolean = recordDetail(id, text) { copy(research = text) }
    /** Records [id]'s latest behavior-check outcome; replaces an earlier one for the same request. */
    fun recordChecks(id: String, text: String): Boolean = recordDetail(id, text) { copy(checks = text) }
    private fun recordDetail(id: String, text: String, change: PromptHistoryEntry.() -> PromptHistoryEntry): Boolean = synchronized(lock) {
        require(text.isNotBlank() && text.length <= MAX_DETAIL_CHARS) { "History details must contain 1 to $MAX_DETAIL_CHARS characters" }
        val old = read()
        if (old.none { it.id == id }) return@synchronized false
        write(old.map { if (it.id == id) it.change() else it })
        true
    }
    fun markSaved(id: String, projectId: String, title: String): Boolean = synchronized(lock) {
        require(validProjectId(projectId)); require(title.length <= 120)
        val old = read()
        val index = old.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val entry = old[index]
        require(entry.status in setOf("ready", "saved")) { "Only a completed request can be saved" }
        require(entry.projectId == null || entry.projectId == projectId) { "Cannot attach an edit to a different app" }
        val lineage = old.filter { it.id == id || entry.sessionId != null && it.sessionId == entry.sessionId }
        require(lineage.all { it.projectId == null || it.projectId == projectId }) { "Cannot attach a session to a different app" }
        // One atomic write links failed/cancelled/earlier drafts too, preserving their actual outcomes.
        // Without an explicit session ID, old records are not retroactively guessed or grouped.
        val changed = old.map { request ->
            if (request.id == id || entry.sessionId != null && request.sessionId == entry.sessionId)
                request.copy(status = if (request.id == id) "saved" else request.status, projectId = projectId, title = title)
            else request
        }
        write(retained(changed))
        true
    }
    /** Called once on BuilderModel startup; a previous process cannot still own a pending generation. */
    fun recoverInterrupted(): List<PromptHistoryEntry> = synchronized(lock) {
        val old = read()
        if (old.none { it.status == "building" }) return@synchronized old
        val recovered = old.map { if (it.status == "building") it.copy(status = "cancelled", interrupted = true) else it }
        write(recovered)
        recovered
    }
    /**
     * Every mutation used to reread and revalidate the whole journal, and the controller reread it again
     * to refresh its list. The parsed list is now reused while the file is the version this process last
     * read or wrote; any other change to the file (or an AtomicFile backup) forces a full validated read.
     */
    private fun read(): List<PromptHistoryEntry> {
        val path = file.baseFile.path
        val signature = FileSignature.of(file.baseFile)
        cache[path]?.takeIf { signature != null && it.first == signature }?.let { return it.second }
        cache.remove(path)
        val entries = readFile()
        FileSignature.of(file.baseFile)?.let { cache[path] = it to entries }
        return entries
    }
    private fun readFile(): List<PromptHistoryEntry> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val text = file.openRead().use { input ->
            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                check(bytes.size() + count <= MAX_FILE_BYTES) { "Prompt history is too large" }
                bytes.write(buffer, 0, count)
            }
            bytes.toString(Charsets.UTF_8.name())
        }
        val root = JsonParser.parseString(text).asJsonObject
        check(root.keySet() == setOf("version", "requests") && root["version"].isJsonPrimitive &&
            root["version"].asJsonPrimitive.isNumber && root["version"].asBigDecimal.intValueExact() == 1) { "Unrecognized prompt history format" }
        val requests = root.getAsJsonArray("requests")
        check(requests.size() <= MAX_RECORDS)
        val entries = requests.map { item ->
            val entry = item.asJsonObject
            val requiredFields = setOf("id", "createdAtMillis", "prompt", "model", "kind", "status", "projectId", "title", "interrupted")
            // Optional fields were added over time; older journals simply lack them. Anything else is rejected.
            check(entry.keySet().containsAll(requiredFields) && (requiredFields + OPTIONAL_FIELDS).containsAll(entry.keySet()))
            fun string(key: String, max: Int): String {
                val value = entry[key]
                check(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                return value.asString.also { check(it.length <= max) }
            }
            val id = string("id", 36); check(validUuid(id))
            val prompt = string("prompt", 8000); check(prompt.isNotBlank())
            val model = string("model", 200); check(model.isNotBlank())
            val kind = string("kind", 10); check(kind in setOf("create", "edit"))
            val status = string("status", 20); check(status in STATUSES)
            val projectId = if (entry["projectId"].isJsonNull) null else string("projectId", 36).also { check(validProjectId(it)) }
            val sessionId = if (!entry.has("sessionId") || entry["sessionId"].isJsonNull) null else string("sessionId", 36).also { check(validUuid(it)) }
            val review = if (!entry.has("review") || entry["review"].isJsonNull) null else string("review", MAX_REVIEW_CHARS).also { check(it.isNotBlank()) }
            val automatic=entry["automatic"]?.let { check(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean } ?: false
            fun detail(key: String) = if (!entry.has(key) || entry[key].isJsonNull) null else string(key, MAX_DETAIL_CHARS).also { check(it.isNotBlank()) }
            val research = detail("research"); val checks = detail("checks")
            check(kind != "edit" || projectId != null)
            check(entry["createdAtMillis"].isJsonPrimitive && entry["createdAtMillis"].asJsonPrimitive.isNumber)
            val time = entry["createdAtMillis"].asBigDecimal.longValueExact(); check(time > 0)
            check(entry["interrupted"].isJsonPrimitive && entry["interrupted"].asJsonPrimitive.isBoolean)
            PromptHistoryEntry(id, time, prompt, model, kind, status, projectId, string("title", 120), entry["interrupted"].asBoolean, sessionId, review, automatic, research, checks)
        }
        check(entries.map { it.id }.distinct().size == entries.size)
        return entries // File order is authoritative when the phone's wall clock changes.
    }
    private fun retained(entries: List<PromptHistoryEntry>): List<PromptHistoryEntry> {
        val counts = mutableMapOf<String, Int>()
        return entries.filter { entry ->
            val identity = entry.projectId?.let { "app:$it" } ?: entry.sessionId?.let { "session:$it" } ?: "legacy:${entry.id}"
            val count = (counts[identity] ?: 0) + 1
            counts[identity] = count
            count <= MAX_ENTRIES
        }
    }
    private fun write(entries: List<PromptHistoryEntry>) {
        if (entries.size > MAX_RECORDS) throw PromptHistoryFullException()
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("requests", JsonArray().apply { entries.forEach { entry -> add(JsonObject().apply {
                addProperty("id", entry.id); addProperty("createdAtMillis", entry.createdAtMillis)
                addProperty("prompt", entry.prompt); addProperty("model", entry.model); addProperty("kind", entry.kind)
                addProperty("status", entry.status); addProperty("projectId", entry.projectId)
                addProperty("title", entry.title); addProperty("interrupted", entry.interrupted)
                entry.sessionId?.let { addProperty("sessionId", it) }
                entry.review?.let { addProperty("review", it) }
                if(entry.automatic) addProperty("automatic", true)
                entry.research?.let { addProperty("research", it) }
                entry.checks?.let { addProperty("checks", it) }
            }) } })
        }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw PromptHistoryFullException()
        val path = file.baseFile.path
        cache.remove(path)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        FileSignature.of(file.baseFile)?.let { cache[path] = it to entries.toList() }
    }
    companion object {
        const val MAX_ENTRIES = 200
        const val MAX_REVIEW_CHARS = 6000
        /** Bound for the research and behavior-check summaries. */
        const val MAX_DETAIL_CHARS = 1500
        private val OPTIONAL_FIELDS = setOf("sessionId", "review", "automatic", "research", "checks")
        private const val MAX_RECORDS = 50_000
        private const val MAX_FILE_BYTES = 10_500_000
        private val STATUSES = setOf("building", "ready", "failed", "cancelled", "saved")
        private val lock = Any()
        // Per file path, guarded by [lock]: the last validated entries and the file version they came from.
        private val cache = mutableMapOf<String, Pair<FileSignature, List<PromptHistoryEntry>>>()
        private fun validUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        private fun validProjectId(value: String) = value == "guitar-tuner" || validUuid(value)
    }
}
