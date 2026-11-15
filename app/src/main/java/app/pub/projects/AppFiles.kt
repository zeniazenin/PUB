package app.pub.projects

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.pub.core.FilesContract
import app.pub.core.FilesContract.Failure
import app.pub.core.FilesContract.Request
import app.pub.hardware.ProjectMedia
import com.google.gson.JsonObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Each app's private file folder (capability storage.files) at filesDir/app-files/<id>/, where <id> is a saved app ID
 * ([ProjectStore.isAppId]) or preview-UUID, created on first write, plus a read-mostly photos/ view of the app's camera
 * photos kept by [ProjectMedia] (listed and deleted through it, never moved). Scripts reach it only through
 * files.request effects ([FilesContract]); the host resolves every path and the isolated runtime gets no file access.
 *
 * - Containment: script paths are validated names below the folder; every resolved location must equal its canonical
 *   form, so a link is never followed (the host never creates one), and tree walks never descend into links.
 * - Names starting with '.' are the host's: temporary files live in app-files/.tmp and are published by rename.
 * - Host changes never rewrite a file's existing bytes: a write replaces the file (temporary file, fsync, rename) and an
 *   append only adds bytes at its end. A reader that opened a file under the lock can therefore keep reading its first
 *   bytes after releasing it ([exportStream]). Copies and edit-preview snapshots are real copies, because Android does
 *   not let apps create hard links.
 * - Every operation holds one process-wide lock. [Session]s run requests in order on one dedicated background thread
 *   and post replies to the main thread; a closed session skips its queued requests and drops late replies.
 */
class AppFiles(
    context: Context,
    private val media: ProjectMedia = ProjectMedia(context),
    private val quotaBytes: Long = FilesContract.QUOTA_BYTES,
    private val quotaEntries: Int = FilesContract.QUOTA_ENTRIES
) {
    // Construction and every containment check use the same canonical parent (as ProjectMedia does).
    private val storageParent = context.filesDir.canonicalFile
    private val storage: StorageManager? = context.getSystemService(StorageManager::class.java)
    private val root = File(storageParent, "app-files").apply { check(mkdirs() || isDirectory) }
    private val temporary = File(root, ".tmp").apply { check(mkdirs() || isDirectory) }

    /**
     * What one app session reaches: the folder [folder] (an app ID or preview-UUID) and the photos of the media project
     * [photos], which photos/ lists and may delete. An edit preview also lists the saved app's photos [savedPhotos],
     * read-only, because its image nodes can show them.
     */
    data class Scope(val folder: String, val photos: String = folder, val savedPhotos: String? = null) {
        init { require(isFolderId(folder) && isFolderId(photos) && (savedPhotos == null || ProjectStore.isAppId(savedPhotos))) { "Invalid app identity" } }
    }

    /**
     * One runtime session of an open app; open a new one whenever the runtime restarts. [request] and [close] are
     * called on the main thread, and [deliver] receives each reply there, never after [close].
     */
    inner class Session internal constructor(val scope: Scope, private val deliver: (JsonObject) -> Unit) {
        @Volatile var closed = false
            private set
        private var waiting = 0
        /**
         * Performs a files.request effect (its envelope already validated with the UI packet) in the background.
         * With [busy], or when [FilesContract.MAX_PENDING] requests already wait, it is answered with code busy.
         */
        fun request(effect: JsonObject, busy: String? = null) {
            if (closed) return
            FilesContract.validate(effect)
            val id = effect["id"].asString
            val operation = effect["operation"].asString
            if (busy != null || waiting >= FilesContract.MAX_PENDING) {
                val reply = FilesContract.failure(id, operation, "busy", busy ?: "Too many file requests are waiting. Send more after their replies arrive.")
                main.post { if (!closed) deliver(reply) }
                return
            }
            waiting++
            executor.execute {
                val reply = if (closed) null else runCatching { this@AppFiles.execute(scope, effect) }
                    .getOrElse { FilesContract.failure(id, operation, "unavailable", "The app's files could not be accessed.") }
                main.post { waiting--; if (!closed && reply != null) deliver(reply) }
            }
        }
        fun close() { closed = true }
    }

    fun open(scope: Scope, deliver: (JsonObject) -> Unit): Session = Session(scope, deliver)

    /**
     * Performs one files.request effect for [scope] and returns its reply event. Blocking: call it off the main thread.
     * The envelope must be valid ([FilesContract.validate]); argument, path and storage problems become error replies.
     */
    fun execute(scope: Scope, effect: JsonObject): JsonObject {
        val request = try { FilesContract.parse(effect) } catch (failure: Failure) {
            return FilesContract.failure(effect["id"].asString, effect["operation"].asString, failure.code, failure.message)
        }
        val reply = synchronized(lock) {
            try { perform(scope, request) }
            catch (failure: Failure) { FilesContract.failure(request, failure) }
            catch (error: Exception) {
                // An interrupted change may have done part of its work; count the folder again next time.
                runCatching { usageCache.remove(folder(scope.folder).path) }
                if (noSpace(error)) FilesContract.failure(request.id, request.operation, "quota_exceeded", "The phone's storage is full.")
                else FilesContract.failure(request.id, request.operation, "unavailable", "The app's files could not be accessed.")
            }
        }
        return if (reply.toString().length <= FilesContract.MAX_REPLY_CHARS) reply
        else FilesContract.failure(request.id, request.operation, "unavailable", "The reply would be too large.")
    }

    /**
     * Starts the edit preview [previewId] with a copy of the saved app [appId]'s folder, so the saved originals never
     * change. It takes time and space in proportion to the app's files (photos are not copied) and is refused when it
     * would leave less than [MIN_FREE_BYTES] free. The snapshot appears whole or not at all; after a failure the
     * preview's requests answer unavailable (saying when storage is full) until [delete]. Blocking.
     */
    fun snapshot(appId: String, previewId: String): Unit = synchronized(lock) {
        require(ProjectStore.isAppId(appId) && isPreviewId(previewId)) { "Invalid app identity" }
        val source = folder(appId)
        val target = folder(previewId)
        try {
            check(kind(target) == Kind.MISSING) { "Preview folder already exists" }
            if (kind(source) != Kind.DIRECTORY) return@synchronized
            if (!reserveSpace(usage(source).bytes + MIN_FREE_BYTES)) throw StorageFull()
            val staged = stagingFile()
            try {
                check(staged.mkdir()) { "Could not prepare preview files" }
                copyTree(source, "", staged)
                Os.rename(staged.path, target.path)
            } finally { if (kind(staged) != Kind.MISSING) deleteTree(staged) }
        } catch (error: Exception) {
            unavailable[target.path] = if (error is StorageFull || noSpace(error)) SNAPSHOT_STORAGE_FULL else UNAVAILABLE
            throw error
        } finally { usageCache.remove(target.path) }
    }

    /**
     * Moves a saved new app's preview files [previewId] into its folder [appId]: one rename, else a staged copy
     * published by rename, so all files arrive or none. Blocking; run it through [serial] so it
     * follows the preview's queued requests.
     */
    fun publishPreview(previewId: String, appId: String): Unit = synchronized(lock) {
        require(isPreviewId(previewId) && ProjectStore.isAppId(appId)) { "Invalid app identity" }
        val source = folder(previewId)
        val target = folder(appId)
        check(!unavailable.containsKey(source.path)) { "Preview files are unavailable" }
        usageCache.remove(source.path); usageCache.remove(target.path)
        when (kind(source)) {
            Kind.MISSING -> return@synchronized
            Kind.DIRECTORY -> {}
            else -> error("Invalid preview folder")
        }
        when (kind(target)) {
            Kind.MISSING -> {}
            Kind.DIRECTORY -> check(target.list().isNullOrEmpty() && target.delete()) { "Saved app folder is not empty" }
            else -> error("Invalid app folder")
        }
        try { Os.rename(source.path, target.path); return@synchronized } catch (_: ErrnoException) {}
        val staged = stagingFile()
        try {
            check(staged.mkdir()) { "Could not prepare app files" }
            copyTree(source, "", staged)
            Os.rename(staged.path, target.path)
        } finally { if (kind(staged) != Kind.MISSING) deleteTree(staged) }
    }

    /** Deletes the folder of an app or preview with everything in it, never following links. Blocking. */
    fun delete(id: String): Unit = synchronized(lock) {
        val folder = folder(id)
        usageCache.remove(folder.path); unavailable.remove(folder.path)
        if (kind(folder) != Kind.MISSING) deleteTree(folder)
    }

    /**
     * Removes abandoned host work: everything in .tmp (a temporary file exists only while an operation holds the lock),
     * every preview folder and, given [isSaved], the folders of apps whose package no longer exists (a deletion cut
     * short). Never follows links. Blocking; the host queues it once per process with [cleanupAtStartup].
     */
    fun cleanupTemporary(isSaved: ((String) -> Boolean)? = null): Unit = synchronized(lock) {
        check(root.canonicalFile == root) { "Invalid file storage root" }
        if (kind(temporary) == Kind.DIRECTORY) temporary.list().orEmpty().forEach { runCatching { deleteTree(File(temporary, it)) } }
        root.list().orEmpty().forEach { name ->
            runCatching {
                val entry = File(root, name)
                if (entry.canonicalFile != entry || kind(entry) != Kind.DIRECTORY) return@runCatching
                if (isPreviewId(name) || (ProjectStore.isAppId(name) && isSaved != null && !isSaved(name))) {
                    usageCache.remove(entry.path); unavailable.remove(entry.path)
                    deleteTree(entry)
                }
            }
        }
    }

    /** Queues [cleanupTemporary] once per process, ahead of any session's requests. Call when the host starts. */
    fun cleanupAtStartup(isSaved: (String) -> Boolean) { if (startupCleaned.compareAndSet(false, true)) later { cleanupTemporary(isSaved) } }

    /** Queues [task] on the files thread after every request queued so far. Failures are dropped. */
    fun later(task: () -> Unit) { executor.execute { runCatching(task) } }

    /** Runs [task] on the files thread after every request queued so far and waits for it. Call off the main thread. */
    fun <T> serial(task: () -> T): T {
        if (Thread.currentThread() === worker) return task()
        try { return executor.submit(Callable { task() }).get() } catch (error: ExecutionException) { throw error.cause ?: error }
    }

    /**
     * Streams [input] into the script path [path] of [scope] (not photos/) and returns its entry: written to a host
     * temporary file without holding the lock, then checked against the quotas and published by rename. An existing
     * file is replaced only with [overwrite]. Throws [Failure] with the usual codes. Blocking; for host features that
     * bring files in from elsewhere on the phone.
     */
    fun importStream(scope: Scope, path: String, input: InputStream, overwrite: Boolean = false): JsonObject {
        val target = FilesContract.normalize(path)
        if (target.isEmpty()) throw Failure("invalid_path", "A file name is required")
        if (FilesContract.inPhotos(target)) throw readOnly()
        val folder = folder(scope.folder)
        val (limit, staged) = synchronized(lock) {
            checkAvailable(folder)
            val file = locate(folder, target)
            val replaceable = if (overwrite && kind(file) == Kind.FILE) file.length() else 0L
            (quotaBytes - usage(folder).bytes + replaceable) to stagingFile()
        }
        try {
            var size = 0L
            FileOutputStream(staged).use { output ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > limit) throw Failure("quota_exceeded", "The file does not fit this app's ${quotaText()} of storage.")
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            return synchronized(lock) {
                val file = locate(folder, target)
                val parents = missingParents(folder, FilesContract.parent(target), true)
                val existing = if (parents.isEmpty()) kind(file) else Kind.MISSING
                when (existing) {
                    Kind.DIRECTORY -> throw isDirectory(target)
                    Kind.OTHER -> throw unavailableFolder()
                    Kind.FILE -> if (!overwrite) throw Failure("exists", "${display(target)} already exists")
                    Kind.MISSING -> {}
                }
                val old = if (existing == Kind.FILE) file.length() else 0L
                reserve(usage(folder), size - old, parents.size + if (existing == Kind.FILE) 0 else 1)
                create(folder, parents)
                Os.rename(staged.path, file.path)
                usageCache.remove(folder.path)
                entryOf(target, file)
            }
        } finally { staged.delete() }
    }

    /**
     * Copies the file at the script path [path] of [scope] (photos/ included) to [output] and returns the bytes copied.
     * The file is opened and its size taken under the lock, then exactly that many bytes are streamed without it: host
     * changes never rewrite existing bytes (a write or delete leaves the open file intact, an append adds bytes past
     * the end), so the copy is the file as it was when the export began. Throws [Failure] with the usual codes. Blocking.
     */
    fun exportStream(scope: Scope, path: String, output: OutputStream): Long {
        val normalized = FilesContract.normalize(path)
        val (input, size) = synchronized(lock) {
            val folder = folder(scope.folder)
            checkAvailable(folder)
            val found = source(scope, folder, normalized)
            if (found.directory) throw isDirectory(normalized)
            val stream = FileInputStream(found.file!!)
            try { stream to stream.channel.size() } catch (error: Exception) { stream.close(); throw error }
        }
        input.use { stream ->
            val buffer = ByteArray(BUFFER)
            var left = size
            while (left > 0) {
                val count = stream.read(buffer, 0, minOf(left, BUFFER.toLong()).toInt())
                if (count < 0) throw IOException("The file ended early")
                output.write(buffer, 0, count)
                left -= count
            }
            return size
        }
    }

    private enum class Kind { MISSING, FILE, DIRECTORY, OTHER }
    private class Usage(var bytes: Long, var entries: Int)
    /** Bytes and entries of a tree, and the depth and length of its longest path relative to the tree. */
    private class Measure(var bytes: Long = 0, var entries: Int = 0, var depth: Int = 0, var length: Int = 0)
    private class Child(val file: File, val path: String, val kind: Kind)
    private class Photo(val file: File, val writable: Boolean) {
        val entry: JsonObject get() = FilesContract.entry("${FilesContract.PHOTOS}/${file.name}", false, file.length(), file.lastModified())
    }
    /** An existing file or folder a request reads: in the folder (the folder itself even before it exists), photos/ or a photo. */
    private class Source(val file: File?, val directory: Boolean, val photo: Photo? = null)
    private class Destination(val target: File, val parents: List<File>, val replaced: Boolean, val replacedBytes: Long)

    private fun perform(scope: Scope, r: Request): JsonObject {
        val folder = folder(scope.folder)
        checkAvailable(folder)
        return when (r.operation) {
            "list" -> list(scope, folder, r)
            "stat" -> FilesContract.ok(r).apply { add("entry", stat(scope, folder, r.path) ?: throw notFound(r.path)) }
            "read" -> read(scope, folder, r)
            "write" -> write(folder, r)
            "mkdir" -> mkdir(folder, r)
            "delete" -> delete(scope, folder, r)
            "move" -> move(folder, r)
            "copy" -> copy(scope, folder, r)
            else -> usage(scope, folder, r)
        }
    }

    private fun list(scope: Scope, folder: File, r: Request): JsonObject {
        val entries = ArrayList<JsonObject>()
        if (FilesContract.inPhotos(r.path)) {
            if (r.path != FilesContract.PHOTOS) throw if (stat(scope, folder, r.path) != null) notDirectory(r.path) else notFound(r.path)
            photos(scope).values.mapTo(entries) { it.entry }
        } else {
            val directory = locate(folder, r.path)
            when (kind(directory)) {
                Kind.DIRECTORY -> collect(directory, r.path, r.recursive, entries)
                Kind.FILE -> throw notDirectory(r.path)
                else -> if (r.path.isNotEmpty()) throw notFound(r.path) // The folder itself is created on first write.
            }
            if (r.path.isEmpty()) {
                val photos = photos(scope)
                entries.add(photosEntry(photos))
                if (r.recursive) photos.values.mapTo(entries) { it.entry }
            }
        }
        entries.sortWith(compareBy(FilesContract.PATH_ORDER) { it["path"].asString })
        return FilesContract.listReply(r, entries)
    }

    private fun stat(scope: Scope, folder: File, path: String): JsonObject? {
        if (path == FilesContract.PHOTOS) return photosEntry(photos(scope))
        if (FilesContract.inPhotos(path)) {
            return if (FilesContract.parent(path) == FilesContract.PHOTOS) photos(scope)[FilesContract.name(path)]?.entry else null
        }
        val file = locate(folder, path)
        return when (kind(file)) {
            Kind.DIRECTORY, Kind.FILE -> entryOf(path, file)
            Kind.MISSING -> if (path.isEmpty()) FilesContract.entry("", true, 0, 0) else null
            Kind.OTHER -> null
        }
    }

    private fun read(scope: Scope, folder: File, r: Request): JsonObject {
        val source = source(scope, folder, r.path)
        if (source.directory) throw isDirectory(r.path)
        val file = source.file!!
        val size = file.length()
        val count = if (r.offset >= size) 0 else minOf(r.length.toLong(), size - r.offset).toInt()
        val bytes = ByteArray(count)
        if (count > 0) RandomAccessFile(file, "r").use { it.seek(r.offset); it.readFully(bytes) }
        return FilesContract.readReply(r, bytes, count, size)
    }

    private fun write(folder: File, r: Request): JsonObject {
        if (FilesContract.inPhotos(r.path)) throw readOnly()
        val target = locate(folder, r.path)
        val parents = missingParents(folder, FilesContract.parent(r.path), r.createParents)
        val existing = if (parents.isEmpty()) kind(target) else Kind.MISSING
        if (existing == Kind.DIRECTORY) throw isDirectory(r.path)
        if (existing == Kind.OTHER) throw unavailableFolder()
        val old = if (existing == Kind.FILE) target.length() else 0L
        val size = if (r.append) old + r.data.size else r.data.size.toLong()
        val entries = parents.size + if (existing == Kind.FILE) 0 else 1
        val used = usage(folder)
        reserve(used, size - old, entries)
        create(folder, parents)
        if (r.append && existing == Kind.FILE) append(target, r.data) else replace(target, r.data)
        used.bytes += size - old; used.entries += entries
        return FilesContract.ok(r).apply { addProperty("size", target.length()) }
    }

    private fun mkdir(folder: File, r: Request): JsonObject {
        if (r.path == FilesContract.PHOTOS) return FilesContract.ok(r).apply { addProperty("created", false) }
        if (FilesContract.inPhotos(r.path)) throw readOnly()
        val target = locate(folder, r.path)
        val parents = missingParents(folder, FilesContract.parent(r.path), true)
        when (if (parents.isEmpty()) kind(target) else Kind.MISSING) {
            Kind.DIRECTORY -> return FilesContract.ok(r).apply { addProperty("created", false) }
            Kind.FILE, Kind.OTHER -> throw Failure("exists", "A file named ${display(r.path)} already exists")
            Kind.MISSING -> {}
        }
        val used = usage(folder)
        reserve(used, 0, parents.size + 1)
        create(folder, parents + target)
        used.entries += parents.size + 1
        return FilesContract.ok(r).apply { addProperty("created", true) }
    }

    private fun delete(scope: Scope, folder: File, r: Request): JsonObject {
        if (r.path == FilesContract.PHOTOS) throw readOnly("The photos folder cannot be deleted. Delete single photos instead.")
        val source = source(scope, folder, r.path)
        val removed = if (source.photo != null) {
            if (!source.photo.writable) throw readOnly("Photos of the saved app are read-only in an edit preview.")
            if (!media.deletePhoto(scope.photos, source.photo.file.name)) throw notFound(r.path)
            1
        } else if (source.directory) {
            val directory = source.file!!
            if (!r.recursive && !directory.list().isNullOrEmpty()) throw Failure("not_empty", "${display(r.path)} is not empty. Delete it with recursive: true.")
            usageCache.remove(folder.path)
            deleteTree(directory)
        } else {
            val file = source.file!!
            val size = file.length()
            check(file.delete()) { "Could not delete the file" }
            usageCache[folder.path]?.let { it.bytes -= size; it.entries -= 1 }
            1
        }
        return FilesContract.ok(r).apply { addProperty("deleted", removed) }
    }

    private fun move(folder: File, r: Request): JsonObject {
        if (FilesContract.inPhotos(r.from) || FilesContract.inPhotos(r.to)) throw readOnly("Photos cannot be moved. Copy them instead.")
        val source = locate(folder, r.from)
        val sourceKind = kind(source)
        if (sourceKind != Kind.FILE && sourceKind != Kind.DIRECTORY) throw notFound(r.from)
        if (sourceKind == Kind.DIRECTORY) checkFits(measure(source, r.from), r.to)
        val destination = destination(folder, r, sourceKind == Kind.DIRECTORY)
        reserve(usage(folder), 0, destination.parents.size - if (destination.replaced) 1 else 0)
        create(folder, destination.parents)
        usageCache.remove(folder.path)
        Os.rename(source.path, destination.target.path)
        return FilesContract.ok(r).apply { add("entry", entryOf(r.to, destination.target)) }
    }

    private fun copy(scope: Scope, folder: File, r: Request): JsonObject {
        if (FilesContract.inPhotos(r.to)) throw readOnly("Nothing can be copied into the photos folder.")
        val source = source(scope, folder, r.from)
        val photos = if (r.from == FilesContract.PHOTOS) photos(scope).values.map { it.file } else null
        val tree = when {
            photos != null -> Measure(photos.sumOf { it.length() }, photos.size, 1, photos.maxOfOrNull { it.name.length } ?: 0)
            source.directory -> measure(source.file!!, r.from)
            else -> Measure(source.file!!.length())
        }
        if (source.directory) checkFits(tree, r.to)
        val destination = destination(folder, r, source.directory)
        reserve(usage(folder), tree.bytes - destination.replacedBytes,
            tree.entries + 1 + destination.parents.size - if (destination.replaced) 1 else 0)
        create(folder, destination.parents)
        usageCache.remove(folder.path)
        val staged = stagingFile()
        try {
            when {
                photos != null -> { check(staged.mkdir()); photos.forEach { copyFile(it, File(staged, it.name)) } }
                source.directory -> { check(staged.mkdir()); copyTree(source.file!!, r.from, staged) }
                else -> copyFile(source.file!!, staged)
            }
            Os.rename(staged.path, destination.target.path)
        } finally { if (kind(staged) != Kind.MISSING) deleteTree(staged) }
        return FilesContract.ok(r).apply { add("entry", entryOf(r.to, destination.target)) }
    }

    private fun usage(scope: Scope, folder: File, r: Request): JsonObject {
        val used = usage(folder)
        val photos = photos(scope).values
        return FilesContract.ok(r).apply {
            addProperty("bytes", used.bytes); addProperty("files", used.entries)
            addProperty("quotaBytes", quotaBytes); addProperty("quotaFiles", quotaEntries)
            addProperty("photos", photos.size); addProperty("photoBytes", photos.sumOf { it.file.length() })
        }
    }

    /** Where a move or copy lands; an existing target is replaced only when both are files and [Request.overwrite] is set. */
    private fun destination(folder: File, r: Request, directory: Boolean): Destination {
        val target = locate(folder, r.to)
        val parents = missingParents(folder, FilesContract.parent(r.to), true)
        val existing = if (parents.isEmpty()) kind(target) else Kind.MISSING
        if (existing != Kind.MISSING) {
            if (!r.overwrite) throw Failure("exists", "${display(r.to)} already exists. Use overwrite: true to replace a file.")
            if (existing == Kind.DIRECTORY) throw Failure("is_directory", "${display(r.to)} is a folder; folders are never overwritten.")
            if (existing != Kind.FILE) throw unavailableFolder()
            if (directory) throw Failure("not_directory", "A folder cannot replace the file ${display(r.to)}.")
        }
        val replaced = existing == Kind.FILE
        return Destination(target, parents, replaced, if (replaced) target.length() else 0L)
    }

    /** A moved or copied folder's contents must still satisfy the path limits at their new place. */
    private fun checkFits(tree: Measure, to: String) {
        if (tree.entries > 0 && (depth(to) + tree.depth > FilesContract.MAX_DEPTH || to.length + 1 + tree.length > FilesContract.MAX_PATH_CHARS)) {
            throw Failure("invalid_path", "The folder's contents would be deeper than ${FilesContract.MAX_DEPTH} levels or longer than ${FilesContract.MAX_PATH_CHARS} characters at ${display(to)}.")
        }
    }

    /**
     * The folders still missing on the way to [path] (outermost first), so a change can count and create them. A file
     * in the way is not_directory; with [create] false a missing folder is not_found.
     */
    private fun missingParents(folder: File, path: String, create: Boolean): List<File> {
        if (path.isEmpty()) return emptyList()
        val missing = ArrayList<File>()
        var current = ""
        for (name in path.split('/')) {
            current = if (current.isEmpty()) name else "$current/$name"
            val directory = locate(folder, current)
            when (if (missing.isEmpty()) kind(directory) else Kind.MISSING) {
                Kind.DIRECTORY -> {}
                Kind.MISSING -> { if (!create) throw notFound(current); missing.add(directory) }
                else -> throw notDirectory(current)
            }
        }
        return missing
    }

    /** Creates the app's folder if needed, then [directories] in order. */
    private fun create(folder: File, directories: List<File>) {
        if (kind(folder) == Kind.MISSING) check(folder.mkdir() || kind(folder) == Kind.DIRECTORY) { "Could not create the app folder" }
        directories.forEach { check(it.mkdir()) { "Could not create a folder" } }
    }

    private fun replace(target: File, data: ByteArray) {
        val staged = stagingFile()
        try {
            FileOutputStream(staged).use { it.write(data); it.fd.sync() }
            Os.rename(staged.path, target.path)
        } finally { staged.delete() }
    }

    /** Adds [data] at the end of [target] in place; its existing bytes never change. No file is shared, so this is safe. */
    private fun append(target: File, data: ByteArray) {
        FileOutputStream(target, true).use { it.write(data); it.fd.sync() }
    }

    /** Copies a file's data and modification time (fsynced, so a copy published by rename is complete). */
    private fun copyFile(source: File, target: File) {
        FileOutputStream(target).use { output ->
            source.inputStream().use { it.copyTo(output, BUFFER) }
            output.fd.sync()
        }
        target.setLastModified(source.lastModified())
    }

    /** Recreates the script-visible tree under [source] (at script path [path]) inside [target] with [copyFile]. */
    private fun copyTree(source: File, path: String, target: File) {
        for (child in children(source, path)) {
            val copy = File(target, child.file.name)
            if (child.kind == Kind.FILE) copyFile(child.file, copy)
            else {
                check(copy.mkdir()) { "Could not copy a folder" }
                if (depth(child.path) < FilesContract.MAX_DEPTH) copyTree(child.file, child.path, copy)
                copy.setLastModified(child.file.lastModified())
            }
        }
        target.setLastModified(source.lastModified())
    }

    /** Deletes a tree without following links (a link itself is removed, never its target). Returns the entries removed. */
    private fun deleteTree(target: File): Int {
        var removed = 0
        if (kind(target) == Kind.DIRECTORY) target.list().orEmpty().forEach { removed += deleteTree(File(target, it)) }
        if (!target.delete() && kind(target) != Kind.MISSING) throw IOException("Could not delete ${target.name}")
        return removed + 1
    }

    /** Script-visible files and folders in [directory] at script path [path]: host names, invalid names and links are skipped. */
    private fun children(directory: File, path: String): List<Child> = directory.list().orEmpty().sorted().mapNotNull { name ->
        if (!FilesContract.validName(name) || (path.isEmpty() && name == FilesContract.PHOTOS)) return@mapNotNull null
        val file = File(directory, name)
        val kind = kind(file)
        if (kind == Kind.FILE || kind == Kind.DIRECTORY) Child(file, if (path.isEmpty()) name else "$path/$name", kind) else null
    }

    private fun collect(directory: File, path: String, recursive: Boolean, into: MutableList<JsonObject>) {
        for (child in children(directory, path)) {
            if (into.size >= WALK_LIMIT) return
            into.add(entryOf(child.path, child.file))
            if (recursive && child.kind == Kind.DIRECTORY && depth(child.path) < FilesContract.MAX_DEPTH) collect(child.file, child.path, true, into)
        }
    }

    private fun measure(directory: File, path: String, relative: String = "", into: Measure = Measure()): Measure {
        for (child in children(directory, path)) {
            val inside = if (relative.isEmpty()) child.file.name else "$relative/${child.file.name}"
            into.entries++
            into.depth = max(into.depth, depth(inside))
            into.length = max(into.length, inside.length)
            if (child.kind == Kind.FILE) into.bytes += child.file.length()
            else if (depth(child.path) < FilesContract.MAX_DEPTH) measure(child.file, child.path, inside, into)
        }
        return into
    }

    /** The folder's usage, counted once and then kept up to date by every change (all under [lock]). */
    private fun usage(folder: File): Usage = usageCache.getOrPut(folder.path) {
        if (kind(folder) != Kind.DIRECTORY) Usage(0, 0) else measure(folder, "").let { Usage(it.bytes, it.entries) }
    }

    /** Quotas are checked before anything is written; changes that do not grow the folder are always allowed. */
    private fun reserve(used: Usage, bytes: Long, entries: Int) {
        if (bytes > 0 && used.bytes + bytes > quotaBytes) throw Failure("quota_exceeded", "This app's files would exceed ${quotaText()}. Delete files first.")
        if (entries > 0 && used.entries + entries > quotaEntries) throw Failure("quota_exceeded", "This app's folder would exceed $quotaEntries files and folders.")
    }

    private fun photos(scope: Scope): Map<String, Photo> {
        val photos = LinkedHashMap<String, Photo>()
        media.photos(scope.photos).forEach { photos[it.name] = Photo(it, true) }
        scope.savedPhotos?.let { saved -> media.photos(saved).forEach { photos.putIfAbsent(it.name, Photo(it, false)) } }
        return photos
    }
    private fun photosEntry(photos: Map<String, Photo>) =
        FilesContract.entry(FilesContract.PHOTOS, true, 0, photos.values.maxOfOrNull { it.file.lastModified() } ?: 0)

    private fun source(scope: Scope, folder: File, path: String): Source {
        if (path == FilesContract.PHOTOS) return Source(null, true)
        if (FilesContract.inPhotos(path)) {
            val photo = if (FilesContract.parent(path) == FilesContract.PHOTOS) photos(scope)[FilesContract.name(path)] else null
            return Source(photo?.file ?: throw notFound(path), false, photo)
        }
        val file = locate(folder, path)
        return when (kind(file)) {
            Kind.FILE -> Source(file, false)
            Kind.DIRECTORY -> Source(file, true)
            else -> if (path.isEmpty()) Source(file, true) else throw notFound(path)
        }
    }

    private fun entryOf(path: String, file: File): JsonObject =
        FilesContract.entry(path, kind(file) == Kind.DIRECTORY, file.length(), file.lastModified())

    private fun folder(id: String): File {
        require(isFolderId(id)) { "Invalid app identity" }
        return File(root, id).also { check(it.canonicalFile.parentFile == root) { "Invalid app folder" } }
    }

    /** The location of a script path in [folder]. It must be its own canonical form, so no link can redirect it. */
    private fun locate(folder: File, path: String): File {
        val file = if (path.isEmpty()) folder else File(folder, path)
        if (file.canonicalPath != file.path) throw Failure("unavailable", "This file location is not allowed.")
        return file
    }

    /** Never follows a link. A path below a file is [Kind.MISSING]. */
    private fun kind(file: File): Kind = try {
        val mode = Os.lstat(file.path).st_mode
        when {
            OsConstants.S_ISDIR(mode) -> Kind.DIRECTORY
            OsConstants.S_ISREG(mode) -> Kind.FILE
            else -> Kind.OTHER
        }
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ENOTDIR) Kind.MISSING else throw IOException(error)
    }

    /**
     * Whether [bytes] can be written to app storage, counting cached data Android can clear (which it then clears).
     * Falls back to the plain free space when the storage service cannot tell.
     */
    private fun reserveSpace(bytes: Long): Boolean {
        val manager = storage ?: return storageParent.usableSpace >= bytes
        return try {
            val volume = manager.getUuidForPath(storageParent)
            if (manager.getAllocatableBytes(volume) < bytes) false else { manager.allocateBytes(volume, bytes); true }
        } catch (_: IOException) { storageParent.usableSpace >= bytes }
    }

    private fun stagingFile(): File {
        check(kind(temporary) == Kind.DIRECTORY || (temporary.mkdirs() && kind(temporary) == Kind.DIRECTORY)) { "Temporary file storage is unavailable" }
        return File(temporary, "${UUID.randomUUID()}.tmp")
    }

    private fun quotaText() = if (quotaBytes % MIB == 0L) "${quotaBytes / MIB} MiB" else "$quotaBytes bytes"
    private fun display(path: String) = if (path.isEmpty()) "the app's folder" else "'$path'"
    private fun notFound(path: String) = Failure("not_found", "Nothing is stored at ${display(path)}.")
    private fun notDirectory(path: String) = Failure("not_directory", "${display(path)} is a file, not a folder.")
    private fun isDirectory(path: String) = Failure("is_directory", "${display(path)} is a folder.")
    private fun readOnly(message: String = "The photos folder is read-only. Copy a photo to another folder to keep a changeable copy.") = Failure("read_only", message)
    private fun unavailableFolder() = Failure("unavailable", UNAVAILABLE)
    /** A folder whose preparation failed answers unavailable, with the reason recorded then. */
    private fun checkAvailable(folder: File) { unavailable[folder.path]?.let { throw Failure("unavailable", it) } }
    private class StorageFull : IOException("Not enough free storage for the preview's copy of the app's files")

    companion object {
        private const val BUFFER = 256 * 1024
        private const val MIB = 1024L * 1024
        /** Free space an edit-preview snapshot must leave on the phone. */
        const val MIN_FREE_BYTES = 64 * MIB
        private const val UNAVAILABLE = "This app's files are unavailable. Close the app and open it again."
        private const val SNAPSHOT_STORAGE_FULL = "The phone's storage is too full to copy this app's files for the preview. Free some space, then open the preview again."
        /** Bounds one listing walk; a folder holds at most the entry quota. */
        private const val WALK_LIMIT = 2 * FilesContract.QUOTA_ENTRIES
        private val lock = Any()
        @Volatile private var worker: Thread? = null
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pub-app-files").also { it.isDaemon = true; worker = it } }
        private val main = Handler(Looper.getMainLooper())
        private val startupCleaned = AtomicBoolean(false)
        /** Usage per folder path, kept current by every change under [lock] and dropped whenever a change is uncertain. */
        private val usageCache = HashMap<String, Usage>()
        /** Folder paths whose preparation failed, with the reason: their requests answer unavailable until the folder is deleted. */
        private val unavailable = HashMap<String, String>()
        private fun isUuid(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        fun isPreviewId(id: String) = id.startsWith("preview-") && isUuid(id.removePrefix("preview-"))
        /** Saved app IDs (including the legacy tuner ID) and preview-UUID identities. */
        fun isFolderId(id: String) = ProjectStore.isAppId(id) || isPreviewId(id)
        private fun depth(path: String) = if (path.isEmpty()) 0 else path.count { it == '/' } + 1
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
