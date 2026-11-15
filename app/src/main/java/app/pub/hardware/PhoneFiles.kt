package app.pub.hardware

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import app.pub.core.FilesContract
import app.pub.core.PhoneFileGrants.Grant
import app.pub.core.PhoneFileGrants.READ
import app.pub.core.PhoneFileGrants.WRITE
import app.pub.core.PhoneFilesContract
import app.pub.core.PhoneFilesContract.Failure
import app.pub.core.PhoneFilesContract.Request
import app.pub.projects.AppFiles
import app.pub.projects.ProjectStore
import com.google.gson.JsonObject
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One request to open the system file picker for the open app; the host Activity launches it. [launched] is set when
 * the picker opens, so a recreated Activity does not open it twice. [consented] records that the user allowed file
 * access in this session, which carries over to the answer even though leaving PUB for the picker ends the session.
 * [token] is the asking broker's native-screen lock.
 */
class FilePick(val request: JsonObject, val parsed: Request, val consented: Boolean, val token: Any = Any()) {
    /** open, openMultiple, folder or create; an export to a new document uses create. */
    val mode: String = parsed.mode ?: "create"
    val mimeTypes: Array<String> = parsed.mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()
    /** The suggested name of a new document. */
    val name: String = parsed.createName ?: parsed.name ?: ""
    val createType: String = parsed.mimeType ?: PhoneFiles.mimeTypeFor(name)
    var launched = false
}

/**
 * The files capability: documents and folders the user picks in Android's system picker (Storage Access Framework).
 * The broker ([HardwareBroker]) decides access and gestures; this class turns picks into grants ([PhoneFileGrantStore])
 * and performs file operations on them through [PhoneDocuments].
 *
 * - Scripts see only handles, names, sizes, types and relative paths inside picked folders: never content URIs,
 *   provider names, paths of the phone or provider errors (every message is fixed host text).
 * - Saved apps keep their grants; previews hold session grants. An edit preview also reads the saved app's grants,
 *   but cannot write, create, delete, rename or release through them.
 * - Access revoked outside PUB (a SecurityException without a current permission) answers revoked and drops the grant.
 * - Requests run in order on one background thread; [Session]s skip queued work once closed and drop late replies.
 */
class PhoneFiles(
    val store: PhoneFileGrantStore,
    private val documents: PhoneDocuments,
    private val permissions: UriPermissions,
    private val privateFiles: AppFiles?
) {
    /**
     * What one app session reaches: the grants of [owner] (a saved app ID, whose grants persist, or a preview-UUID,
     * whose grants last for the preview), read-only grants of the saved app [savedApp] for an edit preview, and the
     * app's private folder [files] for import and export (null when the app does not declare storage.files).
     */
    data class Scope(val owner: String, val savedApp: String? = null, val files: AppFiles.Scope? = null) {
        init {
            require(AppFiles.isFolderId(owner)) { "Invalid app identity" }
            require(savedApp == null || (AppFiles.isPreviewId(owner) && ProjectStore.isAppId(savedApp))) { "Invalid app identity" }
        }
    }

    class Reply(val event: String, val data: JsonObject)

    /** One broker session of an open app. [request], [prepare], [picked] and [close] are called on the main thread. */
    inner class Session internal constructor(val scope: Scope) {
        @Volatile var closed = false
            private set
        private var waiting = 0

        /** Performs a request that opens no picker; [deliver] gets its event and data on the main thread, never after [close]. */
        fun request(effect: JsonObject, deliver: (String, JsonObject) -> Unit) = submit(deliver, limited = true) { execute(scope, effect) }

        /** Checks a picker request before the picker opens; [ready] receives null, or the error data, on the main thread. */
        fun prepare(request: Request, ready: (JsonObject?) -> Unit) {
            if (closed) return
            executor.execute {
                if (closed) return@execute
                val failure = try { this@PhoneFiles.prepare(scope, request); null }
                    catch (failure: Failure) { PhoneFilesContract.error(failure) }
                    catch (_: Exception) { PhoneFilesContract.error("unavailable", "The file picker could not be opened.") }
                main.post { if (!closed) ready(failure) }
            }
        }

        /** Turns what the user picked into grants (and, for an export, writes the file); answered like [request]. */
        fun picked(request: Request, uris: List<Uri>, deliver: (String, JsonObject) -> Unit) = submit(deliver, limited = false) { this@PhoneFiles.picked(scope, request, uris) }

        fun close() { closed = true }

        private fun submit(deliver: (String, JsonObject) -> Unit, limited: Boolean, task: () -> Reply) {
            if (closed) return
            if (limited && waiting >= PhoneFilesContract.MAX_PENDING) {
                val busy = PhoneFilesContract.error("busy", "Too many file requests are waiting. Send more after their replies arrive.")
                main.post { if (!closed) deliver(PhoneFilesContract.ERROR, busy) }
                return
            }
            waiting++
            executor.execute {
                val reply = if (closed) null else task()
                main.post { waiting--; if (!closed && reply != null) deliver(reply.event, reply.data) }
            }
        }
    }

    fun open(scope: Scope): Session = Session(scope)

    /** Performs one request that opens no picker and returns its reply. Blocking: call it off the main thread. */
    fun execute(scope: Scope, effect: JsonObject): Reply = guarded {
        val request = PhoneFilesContract.parse(effect)
        if (request.opensPicker()) throw Failure("invalid_arguments", "Open the file picker from a button.")
        perform(scope, request)
    }

    /**
     * Before the picker opens: there must be room for another grant and, for an export, the private file must exist,
     * so the user never picks a place for nothing. Throws [Failure]. Blocking.
     */
    fun prepare(scope: Scope, request: Request) {
        if (room(scope) <= 0) throw grantLimit()
        if (!AppFiles.isPreviewId(scope.owner) && store.persistedCount() >= PhoneFilesContract.MAX_HOST_GRANTS) throw hostLimit()
        if (request.operation == "export") privateFile(privateFolder(scope), request.from)
    }

    /**
     * What the user picked for [request]: grants for at most the requested number (and the room left), or for an export
     * the new document with the private file written into it. Blocking.
     */
    fun picked(scope: Scope, request: Request, uris: List<Uri>): Reply = guarded {
        val unique = uris.distinctBy { it.toString() }
        val wanted = minOf(room(scope), if (request.mode == "openMultiple") request.max else 1)
        if (wanted <= 0) throw grantLimit()
        val granted = ArrayList<Pair<Grant, PhoneDocument>>()
        var failure: Failure? = null
        for (uri in unique.take(wanted)) {
            try { granted += grant(scope, request, uri) }
            catch (error: Failure) { failure = failure ?: error }
            catch (_: SecurityException) { failure = failure ?: Failure("revoked", "Android did not give PUB access to what was picked. Pick it again.") }
            catch (_: Exception) { failure = failure ?: Failure("unavailable", "What was picked could not be opened.") }
        }
        if (granted.isEmpty()) throw failure ?: Failure("unavailable", "What was picked could not be opened.")
        if (request.operation == "export") return@guarded exportPicked(scope, request, granted.single())
        Reply(PhoneFilesContract.PICKED, PhoneFilesContract.picked(granted.map { (grant, document) -> item(grant, document) }, unique.size > granted.size))
    }

    /** Queues [task] on the phone-files thread after every request queued so far, then posts [done] to the main thread. Failures are dropped. */
    fun later(task: () -> Unit, done: (() -> Unit)? = null) { executor.execute { runCatching(task); done?.let { main.post(it) } } }

    /** Queues the grant store's startup cleanup once per process, ahead of any request. */
    fun cleanupAtStartup(isSaved: (String) -> Boolean) { if (startupCleaned.compareAndSet(false, true)) later({ store.reconcile(isSaved) }) }

    /** A grant the session can use. [own]: the session's own (false: the saved app's, read-only in an edit preview). */
    private inner class Held(val grant: Grant, val own: Boolean) {
        val uri: Uri = Uri.parse(grant.uri)
        val writable = own && grant.write && permissions.granted(uri, READ or WRITE)
    }

    private inline fun guarded(block: () -> Reply): Reply {
        val reply = try { block() }
            catch (failure: Failure) { failed(failure) }
            catch (failure: FilesContract.Failure) { failed(Failure.of(failure)) }
            catch (_: FileNotFoundException) { failed(Failure("not_found", "The file or folder no longer exists.")) }
            catch (error: Exception) {
                failed(if (noSpace(error)) Failure("quota_exceeded", "The phone's storage is full.")
                    else Failure("unavailable", "The file request could not be completed. The app that stores the file may not support it."))
            }
        return if (PhoneFilesContract.fits(reply.data)) reply else failed(Failure("unavailable", "The reply would be too large."))
    }
    private fun failed(failure: Failure) = Reply(PhoneFilesContract.ERROR, PhoneFilesContract.error(failure))

    private fun perform(scope: Scope, r: Request): Reply = when (r.operation) {
        "grants" -> grants(scope)
        "release" -> release(scope, r)
        else -> {
            val held = held(scope, r.handle)
            using(scope, held) {
                when (r.operation) {
                    "stat" -> stat(held, r)
                    "list" -> list(held, r)
                    "read" -> read(held, r)
                    "write" -> write(held, r)
                    "mkdir" -> mkdir(held, r)
                    "delete" -> delete(scope, held, r)
                    "rename" -> rename(held, r)
                    "import" -> import(scope, held, r)
                    "export" -> export(scope, held, r)
                    else -> throw Failure("invalid_arguments", "Unsupported operation")
                }
            }
        }
    }

    private fun held(scope: Scope, handle: String): Held {
        store.grants(scope.owner).find { it.handle == handle }?.let { return Held(it, own = true) }
        scope.savedApp?.let { app -> store.grants(app).find { it.handle == handle }?.let { return Held(it, own = false) } }
        throw Failure("not_found", "No picked file or folder has this handle. Ask the user to pick it again.")
    }

    /** Runs [block] on a grant PUB can still use. Access revoked outside PUB answers revoked and drops the session's grant. */
    private inline fun <T> using(scope: Scope, held: Held, block: () -> T): T {
        if (!permissions.granted(held.uri, READ)) revoked(scope, held)
        try { return block() }
        catch (_: SecurityException) {
            if (!permissions.granted(held.uri, READ)) revoked(scope, held)
            // Still permitted: the provider refused this particular change.
            throw Failure(if (held.writable) "unavailable" else "read_only", "The app that stores this file refused the request.")
        }
    }

    private fun revoked(scope: Scope, held: Held): Nothing {
        // An edit preview never changes the saved app's grants; the saved app drops it on its next use.
        if (held.own) runCatching { store.remove(scope.owner, held.grant.handle) }
        throw Failure("revoked", "Access to this file or folder was removed outside PUB. Ask the user to pick it again.")
    }

    private fun requireWritable(held: Held) {
        if (!held.own) throw Failure("read_only", "An edit preview can only read the saved app's files. Pick the file again in this preview to change it.")
        if (!held.writable) throw Failure("read_only", "This was picked for reading only. Pick it again with write: true to change it.")
    }

    private fun grants(scope: Scope): Reply {
        val items = ArrayList<JsonObject>()
        for (grant in store.grants(scope.owner)) {
            val uri = Uri.parse(grant.uri)
            // Grants whose access was removed outside PUB are dropped here.
            if (!permissions.granted(uri, READ)) { runCatching { store.remove(scope.owner, grant.handle) }; continue }
            items += PhoneFilesContract.item(grant.handle, grant.name, grant.mimeType, -1, grant.folder, grant.write && permissions.granted(uri, READ or WRITE))
        }
        scope.savedApp?.let { app ->
            for (grant in store.grants(app)) {
                if (permissions.granted(Uri.parse(grant.uri), READ)) items += PhoneFilesContract.item(grant.handle, grant.name, grant.mimeType, -1, grant.folder, false)
            }
        }
        return Reply(PhoneFilesContract.GRANTS, PhoneFilesContract.grants(items))
    }

    private fun release(scope: Scope, r: Request): Reply {
        val held = held(scope, r.handle)
        if (!held.own) throw Failure("read_only", "An edit preview cannot release the saved app's files.")
        store.remove(scope.owner, r.handle)
        return Reply(PhoneFilesContract.RELEASED, JsonObject().apply { addProperty("handle", r.handle) })
    }

    private fun stat(held: Held, r: Request): Reply {
        val document = target(held, r.path)
        return Reply(PhoneFilesContract.ENTRY, JsonObject().apply { add("entry", entry(held, document, if (held.grant.folder) r.path else null)) })
    }

    private fun list(held: Held, r: Request): Reply {
        if (!held.grant.folder) throw Failure("not_directory", "This handle is a file; list works on picked folders.")
        val folder = target(held, r.path)
        if (!folder.folder) throw notDirectory(r.path)
        val children = documents.children(held.uri, folder.uri, PhoneFilesContract.MAX_FOLDER_CHILDREN)
        val sorted = children.take(PhoneFilesContract.MAX_FOLDER_CHILDREN)
            .sortedWith(compareBy<PhoneDocument, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name })
        val entries = sorted.map { entry(held, it, PhoneFilesContract.child(r.path, it.name)) }
        return Reply(PhoneFilesContract.ENTRIES, PhoneFilesContract.entries(entries, r.offset, children.size > PhoneFilesContract.MAX_FOLDER_CHILDREN))
    }

    private fun read(held: Held, r: Request): Reply {
        val document = target(held, r.path)
        if (document.folder) throw isDirectory(r.path)
        if (document.virtual) throw virtual()
        val buffer = ByteArray(r.length)
        val count = if (document.size >= 0 && r.offset >= document.size) 0 else documents.read(document.uri, r.offset, buffer, r.length)
        val end = count < r.length || (document.size >= 0 && r.offset + count >= document.size)
        return Reply(PhoneFilesContract.DATA, PhoneFilesContract.read(r, buffer, count, document.size, end))
    }

    private fun write(held: Held, r: Request): Reply {
        requireWritable(held)
        val data = JsonObject()
        if (!held.grant.folder) {
            val document = target(held, r.path)
            if (!document.writable) throw readOnlyDocument()
            writeTo(document, r.data, r.append)
            val size = if (!r.append) r.data.size.toLong() else if (document.size >= 0) document.size + r.data.size else -1
            if (size >= 0) data.addProperty("size", size)
        } else {
            if (r.path.isEmpty()) throw Failure("invalid_path", "'path' must name a file inside the picked folder.")
            val (parent, existing) = place(held, r.path)
            if (existing?.folder == true) throw isDirectory(r.path)
            if (existing != null && !existing.writable) throw readOnlyDocument()
            val name = PhoneFilesContract.name(r.path)
            val document = existing ?: createIn(parent, mimeTypeFor(name), name)
            writeTo(document, r.data, r.append && existing != null)
            val size = if (r.append && existing != null) (if (existing.size >= 0) existing.size + r.data.size else -1) else r.data.size.toLong()
            if (size >= 0) data.addProperty("size", size)
            data.addProperty("created", existing == null)
            val written = documents.describe(document.uri) ?: document
            data.add("entry", entry(held, written, PhoneFilesContract.child(PhoneFilesContract.parent(r.path), written.name)))
        }
        return Reply(PhoneFilesContract.WRITTEN, data)
    }

    private fun mkdir(held: Held, r: Request): Reply {
        if (!held.grant.folder) throw Failure("not_directory", "This handle is a file; folders can only be made inside a picked folder.")
        requireWritable(held)
        val (parent, existing) = place(held, r.path)
        if (existing != null && !existing.folder) throw Failure("exists", "A file named ${quote(PhoneFilesContract.name(r.path))} already exists.")
        val folder = existing ?: createIn(parent, PhoneFilesContract.FOLDER_MIME, PhoneFilesContract.name(r.path))
        return Reply(PhoneFilesContract.CREATED, JsonObject().apply {
            addProperty("created", existing == null)
            add("entry", entry(held, folder, PhoneFilesContract.child(PhoneFilesContract.parent(r.path), folder.name)))
        })
    }

    private fun delete(scope: Scope, held: Held, r: Request): Reply {
        requireWritable(held)
        val data = JsonObject()
        if (!held.grant.folder) {
            val document = target(held, r.path)
            if (!document.deletable) throw Failure("read_only", "The app that stores this file does not allow deleting it.")
            documents.delete(document.uri)
            // Nothing is left for the handle to name.
            runCatching { store.remove(scope.owner, held.grant.handle) }
            data.addProperty("handle", held.grant.handle); data.addProperty("released", true)
        } else {
            if (r.path.isEmpty()) throw Failure("invalid_path", "The picked folder itself cannot be deleted. Release its handle to stop using it.")
            val document = target(held, r.path)
            if (!document.deletable) throw Failure("read_only", "The app that stores this item does not allow deleting it.")
            if (document.folder && !r.recursive && documents.children(held.uri, document.uri, 0).isNotEmpty()) {
                throw Failure("not_empty", "${display(r.path)} is not empty. Delete it with recursive: true.")
            }
            documents.delete(document.uri)
            data.addProperty("path", r.path)
        }
        return Reply(PhoneFilesContract.DELETED, data)
    }

    private fun rename(held: Held, r: Request): Reply {
        if (!held.grant.folder) throw Failure("invalid_arguments", "Only files and folders inside a picked folder can be renamed.")
        requireWritable(held)
        val document = target(held, r.path)
        val parentPath = PhoneFilesContract.parent(r.path)
        val renamed = if (document.name == r.name) document else {
            if (!document.renamable) throw Failure("unavailable", "The app that stores this item does not support renaming.")
            if (childNamed(held, target(held, parentPath), r.name) != null) throw Failure("exists", "Something named ${quote(r.name)} already exists in this folder.")
            documents.describe(documents.rename(document.uri, r.name)) ?: throw Failure("unavailable", "The item could not be renamed.")
        }
        return Reply(PhoneFilesContract.RENAMED, JsonObject().apply { add("entry", entry(held, renamed, PhoneFilesContract.child(parentPath, renamed.name))) })
    }

    private fun import(scope: Scope, held: Held, r: Request): Reply {
        val folder = privateFolder(scope)
        val document = target(held, r.path)
        if (document.folder) throw isDirectory(r.path)
        if (document.virtual) throw virtual()
        val entry = documents.openInput(document.uri).use { input -> privateFiles!!.importStream(folder, r.to, input, r.overwrite) }
        return Reply(PhoneFilesContract.IMPORTED, JsonObject().apply { add("entry", entry) })
    }

    private fun export(scope: Scope, held: Held, r: Request): Reply {
        val folder = privateFolder(scope)
        requireWritable(held)
        privateFile(folder, r.from)
        val data = JsonObject()
        if (!held.grant.folder) {
            val document = target(held, r.path)
            if (!document.writable) throw readOnlyDocument()
            data.addProperty("bytes", exportInto(folder, r.from, document))
        } else {
            if (r.path.isEmpty()) throw Failure("invalid_path", "'path' must name a file inside the picked folder.")
            val (parent, existing) = place(held, r.path)
            if (existing != null) {
                if (existing.folder) throw isDirectory(r.path)
                if (!r.overwrite) throw Failure("exists", "${display(r.path)} already exists. Use overwrite: true to replace it.")
                if (!existing.writable) throw readOnlyDocument()
            }
            val name = PhoneFilesContract.name(r.path)
            val document = existing ?: createIn(parent, mimeTypeFor(name), name)
            val bytes = try { exportInto(folder, r.from, document) } catch (error: Exception) {
                // Remove only the document this export created.
                if (existing == null) runCatching { documents.delete(document.uri) }
                throw error
            }
            data.addProperty("bytes", bytes)
            val written = documents.describe(document.uri) ?: document
            data.add("entry", entry(held, written, PhoneFilesContract.child(PhoneFilesContract.parent(r.path), written.name)))
        }
        return Reply(PhoneFilesContract.EXPORTED, data)
    }

    /** Writes the private file into a document the user just placed with the picker; a failed export removes it if it is still empty. */
    private fun exportPicked(scope: Scope, r: Request, picked: Pair<Grant, PhoneDocument>): Reply {
        val (grant, document) = picked
        try {
            val folder = privateFolder(scope)
            if (!grant.write) throw Failure("read_only", "Android did not allow writing to the new document.")
            val bytes = exportInto(folder, r.from, document)
            val written = documents.describe(document.uri) ?: document
            return Reply(PhoneFilesContract.EXPORTED, JsonObject().apply { addProperty("bytes", bytes); add("item", item(grant, written)) })
        } catch (error: Exception) {
            // The user may also have chosen an existing document to replace: never delete one that has content.
            if (document.size == 0L) runCatching { documents.delete(document.uri) }
            runCatching { store.remove(scope.owner, grant.handle) }
            throw error
        }
    }

    private fun grant(scope: Scope, r: Request, uri: Uri): Pair<Grant, PhoneDocument> {
        val folder = r.operation == "pick" && r.mode == "folder"
        val document = documents.describe(if (folder) documents.root(uri) else uri) ?: throw Failure("not_found", "What was picked no longer exists.")
        if (document.folder != folder) throw Failure("unavailable", "What was picked could not be opened.")
        val write = r.write && permissions.granted(uri, READ or WRITE)
        val limit = PhoneFilesContract.MAX_GRANTS - (scope.savedApp?.let { store.grants(it).size } ?: 0)
        return store.add(scope.owner, uri, folder, write, document.name, document.mimeType, limit) to document
    }

    private fun item(grant: Grant, document: PhoneDocument) = PhoneFilesContract.item(grant.handle, grant.name, grant.mimeType,
        if (grant.folder) -1 else document.size, grant.folder, grant.write && (grant.folder || document.writable))

    private fun entry(held: Held, document: PhoneDocument, path: String?) = PhoneFilesContract.entry(document.name, path, document.folder,
        document.mimeType, document.size, document.modified, held.writable && (if (document.folder) document.creates else document.writable))

    /** The room an owner has for new grants; an edit preview shares the limit with the saved app's grants it sees. */
    private fun room(scope: Scope) = PhoneFilesContract.MAX_GRANTS - store.grants(scope.owner).size - (scope.savedApp?.let { store.grants(it).size } ?: 0)

    /** The document a request names: the picked file, or the picked folder or [path] inside it (found by name, level by level). */
    private fun target(held: Held, path: String): PhoneDocument {
        if (!held.grant.folder) {
            if (path.isNotEmpty()) throw Failure("invalid_arguments", "'path' is only used with folder handles.")
            return documents.describe(held.uri) ?: throw Failure("not_found", "The picked file no longer exists.")
        }
        var current = folderRoot(held)
        var walked = ""
        if (path.isEmpty()) return current
        for (name in path.split('/')) {
            if (!current.folder) throw notDirectory(walked)
            walked = if (walked.isEmpty()) name else "$walked/$name"
            current = childNamed(held, current, name) ?: throw Failure("not_found", "Nothing is stored at ${display(walked)}.")
        }
        return current
    }

    /** The folder that holds [path] in a folder grant (missing folders on the way are created) and what is at [path] now. */
    private fun place(held: Held, path: String): Pair<PhoneDocument, PhoneDocument?> {
        var parent = folderRoot(held)
        var walked = ""
        val names = path.split('/')
        for (name in names.dropLast(1)) {
            walked = if (walked.isEmpty()) name else "$walked/$name"
            val next = childNamed(held, parent, name) ?: createIn(parent, PhoneFilesContract.FOLDER_MIME, name)
            if (!next.folder) throw notDirectory(walked)
            parent = next
        }
        return parent to childNamed(held, parent, names.last())
    }

    private fun folderRoot(held: Held): PhoneDocument =
        documents.describe(documents.root(held.uri))?.takeIf { it.folder } ?: throw Failure("not_found", "The picked folder no longer exists.")

    private fun childNamed(held: Held, folder: PhoneDocument, name: String): PhoneDocument? {
        val matches = documents.children(held.uri, folder.uri, PhoneFilesContract.MAX_FOLDER_CHILDREN).filter { it.name == name }
        if (matches.size > 1) throw Failure("unavailable", "Several items in this folder are named ${quote(name)}.")
        return matches.firstOrNull()
    }

    private fun createIn(folder: PhoneDocument, mimeType: String, name: String): PhoneDocument {
        if (!folder.creates) throw Failure("read_only", "The app that stores this folder does not allow new files or folders in it.")
        return documents.describe(documents.create(folder.uri, mimeType, name)) ?: throw Failure("unavailable", "The new file or folder could not be created.")
    }

    private fun writeTo(document: PhoneDocument, data: ByteArray, append: Boolean) {
        try { documents.write(document.uri, data, append) }
        catch (error: SecurityException) { throw error }
        catch (error: Exception) {
            if (noSpace(error)) throw Failure("quota_exceeded", "The phone's storage is full.")
            throw Failure("unavailable", if (append) "The app that stores this file cannot add to its end. Write the whole file instead."
                else "The app that stores this file cannot replace its contents.")
        }
    }

    private fun exportInto(folder: AppFiles.Scope, from: String, document: PhoneDocument): Long =
        documents.openOutput(document.uri).use { output -> privateFiles!!.exportStream(folder, from, output) }

    private fun privateFolder(scope: Scope): AppFiles.Scope {
        val files = privateFiles
        val folder = scope.files
        if (files == null || folder == null) throw Failure("unavailable", "Import and export need the app's private folder (capability storage.files).")
        // Private-file work queued so far (such as an edit preview's snapshot) finishes first.
        files.serial { }
        return folder
    }

    /** The private file [path] of the export must be a file that exists. */
    private fun privateFile(folder: AppFiles.Scope, path: String) {
        val stat = JsonObject().apply {
            addProperty("type", FilesContract.EFFECT); addProperty("id", "export"); addProperty("operation", "stat")
            add("args", JsonObject().apply { addProperty("path", path) })
        }
        val reply = privateFiles!!.execute(folder, stat)
        if (!reply["ok"].asBoolean) throw Failure(reply["code"].asString, reply["message"].asString)
        if (reply.getAsJsonObject("entry")["type"].asString != "file") throw Failure("is_directory", "'$path' is a folder; export one file at a time.")
    }

    private fun grantLimit() = Failure("grant_limit", "This app already holds the most files and folders it can (${PhoneFilesContract.MAX_GRANTS}). Release one first.")
    private fun hostLimit() = Failure("grant_limit", "PUB already keeps access to ${PhoneFilesContract.MAX_HOST_GRANTS} files and folders for your apps. Forget file access of an app in Settings → Access first.")
    private fun readOnlyDocument() = Failure("read_only", "The app that stores this file does not allow changing it.")
    private fun virtual() = Failure("unavailable", "This is an online-only document (such as a Google Docs file) that cannot be read as a file.")
    private fun notDirectory(path: String) = Failure("not_directory", "${display(path)} is a file, not a folder.")
    private fun isDirectory(path: String) = Failure("is_directory", "${display(path)} is a folder.")
    private fun display(path: String) = if (path.isEmpty()) "The picked item" else quote(path)
    private fun quote(text: String) = "'" + (if (text.length > 80) text.take(80) + "…" else text) + "'"

    companion object {
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pub-phone-files").also { it.isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())
        private val startupCleaned = AtomicBoolean(false)
        @Volatile private var sharedStore: PhoneFileGrantStore? = null

        /** The process-wide grant store at filesDir/app-grants: one holder count for every host model in the process. */
        fun defaultStore(context: Context): PhoneFileGrantStore = sharedStore ?: synchronized(this) {
            sharedStore ?: PhoneFileGrantStore(File(context.filesDir, "app-grants"), AndroidUriPermissions(context)).also { sharedStore = it }
        }

        fun create(context: Context, privateFiles: AppFiles): PhoneFiles =
            PhoneFiles(defaultStore(context), AndroidDocuments(context), AndroidUriPermissions(context), privateFiles)

        /** The MIME type for a new document named [name], from its extension (application/octet-stream when unknown). */
        fun mimeTypeFor(name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val type = if (extension.isEmpty() || extension.length > 16) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            return PhoneFilesContract.mimeType(type, false)
        }

        private fun noSpace(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                if (current is ErrnoException && (current.errno == OsConstants.ENOSPC || current.errno == OsConstants.EDQUOT)) return true
                if (current.message?.let { "ENOSPC" in it || "No space left" in it } == true) return true
                current = current.cause
            }
            return false
        }
    }
}
