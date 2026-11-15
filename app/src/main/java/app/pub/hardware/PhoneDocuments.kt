package app.pub.hardware

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.pub.core.PhoneFileGrants
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One document or folder as its provider describes it. Host-only: URIs and provider IDs never reach scripts. */
data class PhoneDocument(val uri: Uri, val name: String, val mimeType: String, val size: Long, val modified: Long, val flags: Int) {
    val folder get() = mimeType == Document.MIME_TYPE_DIR
    val writable get() = flags and Document.FLAG_SUPPORTS_WRITE != 0
    val creates get() = folder && flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0
    val deletable get() = flags and Document.FLAG_SUPPORTS_DELETE != 0
    val renamable get() = flags and Document.FLAG_SUPPORTS_RENAME != 0
    /** An online-only document (for example a Google Docs file) that has no byte stream. */
    val virtual get() = flags and Document.FLAG_VIRTUAL_DOCUMENT != 0
}

/**
 * Document provider access for the files capability, behind an interface so [PhoneFiles] can be tested without the
 * system picker or a real provider. Failures are the provider's exceptions: SecurityException (access was revoked),
 * FileNotFoundException (the document is gone) and others (the provider does not support the operation).
 */
interface PhoneDocuments {
    /** The document [document] names, or null when it no longer exists. */
    fun describe(document: Uri): PhoneDocument?
    /** The document URI of the folder a folder grant ([tree], from the folder picker) names. */
    fun root(tree: Uri): Uri
    /** The children of [folder], a document inside [tree]: at most [limit] + 1, so callers can tell when there are more. */
    fun children(tree: Uri, folder: Uri, limit: Int): List<PhoneDocument>
    /** Creates a document named [name] in [folder] and returns its URI. The provider may adjust the name. */
    fun create(folder: Uri, mimeType: String, name: String): Uri
    fun delete(document: Uri)
    /** Renames [document] and returns its URI, which can change. */
    fun rename(document: Uri, name: String): Uri
    /** Reads up to [length] bytes at [offset] into [buffer] and returns how many; fewer only at the end of the document. */
    fun read(document: Uri, offset: Long, buffer: ByteArray, length: Int): Int
    /** Replaces the contents with [data] (truncate, then write) or appends it; providers that cannot do so throw. */
    fun write(document: Uri, data: ByteArray, append: Boolean)
    fun openInput(document: Uri): InputStream
    /** Opens [document] to replace its contents (truncated first). */
    fun openOutput(document: Uri): OutputStream
}

/** PUB's own URI permissions: temporary grants from the picker and the persisted grants kept for saved apps. */
interface UriPermissions {
    /** True when PUB currently holds every bit of [flags] ([PhoneFileGrants.READ], [PhoneFileGrants.WRITE]) for [uri]. */
    fun granted(uri: Uri, flags: Int): Boolean
    /** Keeps [flags] for [uri] across restarts. Throws SecurityException when the picker did not grant them. */
    fun take(uri: Uri, flags: Int)
    /** Gives up persisted [flags] for [uri]; never fails. */
    fun release(uri: Uri, flags: Int)
    /** Every persisted URI with its bits. */
    fun persisted(): Map<String, Int>
}

/** [UriPermissions] of this app process through the ContentResolver. */
class AndroidUriPermissions(context: Context) : UriPermissions {
    private val app = context.applicationContext
    override fun granted(uri: Uri, flags: Int): Boolean =
        (flags and PhoneFileGrants.READ == 0 || check(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) &&
            (flags and PhoneFileGrants.WRITE == 0 || check(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    private fun check(uri: Uri, mode: Int) = app.checkUriPermission(uri, Process.myPid(), Process.myUid(), mode) == PackageManager.PERMISSION_GRANTED
    override fun take(uri: Uri, flags: Int) {
        if (flags and PhoneFileGrants.READ != 0) app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (flags and PhoneFileGrants.WRITE != 0) app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    override fun release(uri: Uri, flags: Int) {
        // Release write before read; a bit that is not persisted (or a provider that is gone) is not an error here.
        if (flags and PhoneFileGrants.WRITE != 0) runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        if (flags and PhoneFileGrants.READ != 0) runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    override fun persisted(): Map<String, Int> = app.contentResolver.persistedUriPermissions.associate {
        it.uri.toString() to ((if (it.isReadPermission) PhoneFileGrants.READ else 0) or (if (it.isWritePermission) PhoneFileGrants.WRITE else 0))
    }
}

/** [PhoneDocuments] through DocumentsContract and the ContentResolver. */
class AndroidDocuments(context: Context) : PhoneDocuments {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun describe(document: Uri): PhoneDocument? =
        (resolver.query(document, COLUMNS, null, null, null) ?: throw IOException("No answer")).use { cursor ->
            if (cursor.moveToFirst()) row(document, cursor) else null
        }

    override fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    override fun children(tree: Uri, folder: Uri, limit: Int): List<PhoneDocument> {
        val listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        val children = ArrayList<PhoneDocument>()
        (resolver.query(listing, COLUMNS, null, null, null) ?: throw IOException("No answer")).use { cursor ->
            val id = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            if (id < 0) throw IOException("No document IDs")
            while (children.size <= limit && cursor.moveToNext()) {
                val documentId = cursor.getString(id) ?: continue
                children += row(DocumentsContract.buildDocumentUriUsingTree(tree, documentId), cursor)
            }
        }
        return children
    }

    override fun create(folder: Uri, mimeType: String, name: String): Uri =
        DocumentsContract.createDocument(resolver, folder, mimeType, name) ?: throw IOException("Not created")

    override fun delete(document: Uri) { if (!DocumentsContract.deleteDocument(resolver, document)) throw IOException("Not deleted") }

    override fun rename(document: Uri, name: String): Uri = DocumentsContract.renameDocument(resolver, document, name) ?: throw IOException("Not renamed")

    override fun read(document: Uri, offset: Long, buffer: ByteArray, length: Int): Int {
        val descriptor = resolver.openFileDescriptor(document, "r") ?: throw FileNotFoundException()
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            // A real file seeks; a pipe (for example a document streamed from the cloud) is read up to the offset.
            val seeked = try { input.channel.position(offset); true } catch (_: IOException) { false }
            if (!seeked) discard(input, offset)
            var total = 0
            while (total < length) {
                val count = input.read(buffer, total, length - total)
                if (count < 0) break
                total += count
            }
            return total
        }
    }

    override fun write(document: Uri, data: ByteArray, append: Boolean) {
        (resolver.openOutputStream(document, if (append) "wa" else "wt") ?: throw FileNotFoundException()).use { output ->
            output.write(data)
            output.flush()
            (output as? FileOutputStream)?.let { runCatching { it.fd.sync() } }
        }
    }

    override fun openInput(document: Uri): InputStream = resolver.openInputStream(document) ?: throw FileNotFoundException()

    override fun openOutput(document: Uri): OutputStream = resolver.openOutputStream(document, "wt") ?: throw FileNotFoundException()

    /** Reads and drops [count] bytes of a stream that cannot seek; bounded so a request cannot stream gigabytes to skip. */
    private fun discard(input: InputStream, count: Long) {
        if (count > MAX_DISCARD) throw UnsupportedOperationException("Cannot seek")
        val scratch = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val read = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (read < 0) return
            left -= read
        }
    }

    private fun row(uri: Uri, cursor: Cursor): PhoneDocument {
        fun index(column: String) = cursor.getColumnIndex(column)
        fun text(column: String) = index(column).let { if (it >= 0 && !cursor.isNull(it)) cursor.getString(it) else null }
        fun number(column: String) = index(column).let { if (it >= 0 && !cursor.isNull(it)) cursor.getLong(it) else -1L }
        return PhoneDocument(uri, text(Document.COLUMN_DISPLAY_NAME).orEmpty(), text(Document.COLUMN_MIME_TYPE).orEmpty(),
            number(Document.COLUMN_SIZE), number(Document.COLUMN_LAST_MODIFIED), number(Document.COLUMN_FLAGS).coerceAtLeast(0).toInt())
    }

    private companion object {
        /** Bytes a read may skip in a document that cannot seek; import such a document instead. */
        const val MAX_DISCARD = 64L * 1024 * 1024
        val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}
