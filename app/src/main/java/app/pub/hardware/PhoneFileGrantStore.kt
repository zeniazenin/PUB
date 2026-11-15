package app.pub.hardware

import android.net.Uri
import android.util.AtomicFile
import app.pub.core.PhoneFileGrants
import app.pub.core.PhoneFileGrants.Grant
import app.pub.core.PhoneFilesContract
import app.pub.core.PhoneFilesContract.Failure
import app.pub.projects.AppFiles
import app.pub.projects.ProjectStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom

/**
 * The phone files each app holds (capability files). PUB owns all of its persisted URI permissions here.
 *
 * - Saved apps (owners that are app IDs): one strict, atomic file per app at app-grants/<app id>.json ([PhoneFileGrants]),
 *   and Android's persisted permission for every document in it. [PhoneFileGrants.Holds] counts the apps holding each
 *   URI, so a permission is released only when no saved app needs it any more.
 * - Previews (preview-UUID owners): session grants in memory only. They rely on the picker's temporary grant, never take
 *   or release a persisted permission, and end with [forget] when the preview closes.
 * - A grant file that cannot be read counts as empty and is left untouched until the app's next change replaces it.
 * - [reconcile] (once at startup) removes the grants of deleted apps and releases persisted permissions no grant refers to,
 *   except while a saved app's grant file is unreadable: its documents are unknown, so nothing is released then.
 *
 * Users' documents are never deleted here. Every method is blocking and thread-safe; the host calls them from the
 * phone-files thread.
 */
class PhoneFileGrantStore(private val root: File, private val permissions: UriPermissions) {
    private val lock = Any()
    private val random = SecureRandom()
    private val saved = HashMap<String, List<Grant>>()
    private val unreadable = HashSet<String>()
    private val sessions = HashMap<String, List<Grant>>()
    private val holds = PhoneFileGrants.Holds()
    private var loaded = false

    /** The grants [owner] (a saved app ID or a preview-UUID) holds, in the order they were added. */
    fun grants(owner: String): List<Grant> = synchronized(lock) { load(); current(owner) }

    /** Distinct documents PUB keeps persisted access to for all saved apps. */
    fun persistedCount(): Int = synchronized(lock) { load(); holds.size() }

    /**
     * Adds a picked document for [owner] and returns its grant; picking the same document again keeps its handle (and
     * gains write access when it is newly granted). For a saved app the persisted permission is taken first, then the
     * grant file is written; a failure leaves no new grant or permission behind. [limit] caps the owner's grants (lower
     * for an edit preview, which also sees the saved app's). Throws [Failure] grant_limit or unavailable.
     */
    fun add(owner: String, uri: Uri, folder: Boolean, write: Boolean, name: String, mimeType: String,
            limit: Int = PhoneFilesContract.MAX_GRANTS, now: Long = System.currentTimeMillis()): Grant = synchronized(lock) {
        load()
        val key = uri.toString()
        if (!PhoneFileGrants.validUri(key)) throw Failure("unavailable", "This location cannot be used. Pick another file or folder.")
        val current = current(owner)
        val existing = current.find { it.uri == key }
        if (existing == null && current.size >= limit) throw Failure("grant_limit", "This app already has access to ${current.size} files and folders, the most it can hold. Release one first.")
        var grant = existing?.withWrite(existing.write || write)
            ?: PhoneFileGrants.grant(newHandle(current), key, folder, write, name, mimeType, now)
        if (AppFiles.isPreviewId(owner)) {
            sessions[owner] = replaced(current, grant)
            return@synchronized grant
        }
        require(ProjectStore.isAppId(owner)) { "Invalid app identity" }
        if (!holds.contains(key) && holds.size() >= PhoneFilesContract.MAX_HOST_GRANTS) {
            throw Failure("grant_limit", "PUB already keeps access to ${PhoneFilesContract.MAX_HOST_GRANTS} files and folders for your apps. Forget file access of an app in Settings → Access first.")
        }
        try { permissions.take(uri, grant.flags()) } catch (_: SecurityException) {
            // Android kept only read access (or none): keep what it allows.
            if (!grant.write) throw Failure("unavailable", "Android did not let PUB keep access to this file. Pick it again.")
            grant = grant.withWrite(false)
            try { permissions.take(uri, grant.flags()) } catch (_: SecurityException) { throw Failure("unavailable", "Android did not let PUB keep access to this file. Pick it again.") }
        }
        val updated = replaced(current, grant)
        try { write(owner, updated) } catch (error: Exception) {
            permissions.release(uri, holds.excess(key, grant.flags()))
            throw error
        }
        saved[owner] = updated; unreadable.remove(owner)
        holds.hold(owner, key, grant.flags()).let { if (it != 0) permissions.release(uri, it) }
        grant
    }

    /** Removes one grant of [owner] (releasing its permission when no saved app needs it); null when there is none. */
    fun remove(owner: String, handle: String): Grant? = synchronized(lock) {
        load()
        val current = current(owner)
        val grant = current.find { it.handle == handle } ?: return@synchronized null
        val updated = current - grant
        if (AppFiles.isPreviewId(owner)) { sessions[owner] = updated; return@synchronized grant }
        write(owner, updated)
        saved[owner] = updated
        release(owner, grant.uri)
        grant
    }

    /**
     * Removes every grant of [owner] and its grant file: when an app is deleted, its preview closes or the user forgets
     * its file access. Releases each permission no other saved app holds. The documents themselves stay untouched.
     */
    fun forget(owner: String): Unit = synchronized(lock) {
        load()
        if (AppFiles.isPreviewId(owner)) { sessions.remove(owner); return@synchronized }
        require(ProjectStore.isAppId(owner)) { "Invalid app identity" }
        delete(owner)
        unreadable.remove(owner)
        saved.remove(owner).orEmpty().forEach { release(owner, it.uri) }
    }

    /**
     * Startup cleanup, before any request: forgets the grants of apps whose package no longer exists ([isSaved] false; a
     * deletion cut short) and releases persisted permissions, or their write bit, that no saved app's grant needs (a
     * change cut short, or a grant file that could not be read).
     */
    fun reconcile(isSaved: (String) -> Boolean): Unit = synchronized(lock) {
        load()
        (saved.keys + unreadable).toList().forEach { id -> if (!isSaved(id)) runCatching { forget(id) } }
        // A saved app's unreadable grant file may still refer to persisted permissions. Releasing "unreferenced"
        // permissions now could permanently remove access the user granted, so skip that until every file reads.
        if (unreadable.any(isSaved)) return@synchronized
        runCatching { permissions.persisted() }.getOrDefault(emptyMap()).forEach { (uri, bits) ->
            val excess = holds.excess(uri, bits)
            if (excess != 0) runCatching { permissions.release(Uri.parse(uri), excess) }
        }
    }

    private fun current(owner: String): List<Grant> = when {
        AppFiles.isPreviewId(owner) -> sessions[owner].orEmpty()
        ProjectStore.isAppId(owner) -> saved[owner].orEmpty()
        else -> throw IllegalArgumentException("Invalid app identity")
    }

    private fun replaced(current: List<Grant>, grant: Grant) =
        if (current.any { it.handle == grant.handle }) current.map { if (it.handle == grant.handle) grant else it } else current + grant

    private fun release(owner: String, uri: String) {
        val bits = holds.release(owner, uri)
        if (bits != 0) permissions.release(Uri.parse(uri), bits)
    }

    private fun newHandle(current: List<Grant>): String {
        while (true) {
            val handle = PhoneFilesContract.newHandle(random)
            if (current.none { it.handle == handle }) return handle
        }
    }

    /** Reads every saved app's grant file once and counts their holds. */
    private fun load() {
        if (loaded) return
        val ids = root.list().orEmpty().mapNotNull { name ->
            name.removeSuffix(".bak").removeSuffix(".new").removeSuffix(".json").takeIf { name.contains(".json") && ProjectStore.isAppId(it) }
        }.toSet()
        for (id in ids) {
            val grants = try { read(id) } catch (_: Exception) { unreadable.add(id); continue }
            if (grants.isEmpty()) continue
            saved[id] = grants
            grants.forEach { holds.hold(id, it.uri, it.flags()) }
        }
        loaded = true
    }

    private fun file(id: String): AtomicFile {
        require(ProjectStore.isAppId(id)) { "Invalid app identity" }
        return AtomicFile(File(root, "$id.json"))
    }

    private fun read(id: String): List<Grant> {
        val file = file(id)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= MAX_FILE_BYTES) { "Grant file size limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return PhoneFileGrants.parse(text)
    }

    /** Replaces the grant file atomically (an app without grants has none). Never writes what could not be read back. */
    private fun write(id: String, grants: List<Grant>) {
        if (grants.isEmpty()) { delete(id); return }
        val json = PhoneFileGrants.toJson(grants)
        PhoneFileGrants.parse(json)
        val file = file(id)
        check(root.isDirectory || root.mkdirs()) { "Grant directory unavailable" }
        val stream = file.startWrite()
        try { stream.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    private fun delete(id: String) {
        val file = file(id)
        file.delete()
        File(file.baseFile.path + ".new").delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) { "Could not delete the grant file" }
    }

    private companion object {
        /** UTF-8 takes at most three bytes per stored character. */
        const val MAX_FILE_BYTES = 3 * PhoneFileGrants.MAX_JSON_CHARS
    }
}
