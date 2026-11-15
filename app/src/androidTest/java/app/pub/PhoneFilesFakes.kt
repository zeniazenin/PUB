package app.pub

import android.net.Uri
import android.provider.DocumentsContract.Document
import app.pub.core.PhoneFileGrants
import app.pub.hardware.PhoneDocument
import app.pub.hardware.PhoneDocuments
import app.pub.hardware.UriPermissions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * PUB's URI permissions without Android's grant system: [pickerGrant] stands for the temporary grant of a picker result
 * (lost on [reboot]) and take/release for persisted grants. Records every take and release.
 */
class FakeUriPermissions : UriPermissions {
    val temporary = HashMap<String, Int>()
    val persistedBits = HashMap<String, Int>()
    val taken = mutableListOf<Pair<String, Int>>()
    val released = mutableListOf<Pair<String, Int>>()

    fun pickerGrant(uri: Uri, flags: Int = PhoneFileGrants.READ or PhoneFileGrants.WRITE) { temporary[uri.toString()] = flags }
    /** Access removed outside PUB. */
    fun revoke(uri: Uri) { temporary.remove(uri.toString()); persistedBits.remove(uri.toString()) }
    /** Only persisted grants survive a restart of the phone. */
    fun reboot() { temporary.clear() }
    private fun bits(uri: String) = (temporary[uri] ?: 0) or (persistedBits[uri] ?: 0)

    override fun granted(uri: Uri, flags: Int) = bits(uri.toString()) and flags == flags
    override fun take(uri: Uri, flags: Int) {
        if (bits(uri.toString()) and flags != flags) throw SecurityException("Not granted")
        persistedBits[uri.toString()] = (persistedBits[uri.toString()] ?: 0) or flags
        taken += uri.toString() to flags
    }
    override fun release(uri: Uri, flags: Int) {
        released += uri.toString() to flags
        val left = (persistedBits[uri.toString()] ?: 0) and flags.inv()
        if (left == 0) persistedBits.remove(uri.toString()) else persistedBits[uri.toString()] = left
    }
    override fun persisted(): Map<String, Int> = persistedBits.toMap()
}

/**
 * An in-memory document provider with DocumentsContract flags and URIs (content://test.documents/document/<id> and
 * .../tree/<id>), so the files capability can be tested without the system picker. Renaming gives a document a new ID,
 * as some providers do. [failure] makes the next access throw.
 */
class FakeDocuments : PhoneDocuments {
    class Node(val id: String, var name: String, val folder: Boolean, var parent: String?, var data: ByteArray, var flags: Int, val mimeType: String, var modified: Long = 1_700_000_000_000L)

    val nodes = LinkedHashMap<String, Node>()
    var appendSupported = true
    var failure: (() -> Exception)? = null
    private var counter = 0

    fun documentUri(id: String): Uri = Uri.parse("content://$AUTHORITY/document/$id")
    fun treeUri(id: String): Uri = Uri.parse("content://$AUTHORITY/tree/$id")
    fun folder(parent: String?, name: String, flags: Int = FOLDER_FLAGS): String = add(Node(nextId(), name, true, parent, ByteArray(0), flags, Document.MIME_TYPE_DIR))
    fun file(parent: String?, name: String, text: String, flags: Int = FILE_FLAGS, mimeType: String = "text/plain"): String =
        add(Node(nextId(), name, false, parent, text.toByteArray(), flags, mimeType))
    fun text(id: String) = String(nodes.getValue(id).data)
    fun childrenOf(id: String) = nodes.values.filter { it.parent == id }
    fun named(parent: String, name: String) = childrenOf(parent).single { it.name == name }

    private fun nextId() = "doc${++counter}"
    private fun add(node: Node): String { nodes[node.id] = node; return node.id }
    private fun id(uri: Uri): String {
        val segments = uri.pathSegments
        require(uri.authority == AUTHORITY && segments.size == 2) { "Not a test document" }
        return segments[1]
    }
    private fun node(uri: Uri): Node {
        failure?.let { failure = null; throw it() }
        return nodes[id(uri)] ?: throw FileNotFoundException()
    }
    private fun document(node: Node) = PhoneDocument(documentUri(node.id), node.name, node.mimeType, if (node.folder) -1 else node.data.size.toLong(), node.modified, node.flags)

    override fun describe(document: Uri): PhoneDocument? {
        failure?.let { failure = null; throw it() }
        return nodes[id(document)]?.let(::document)
    }
    override fun root(tree: Uri): Uri = documentUri(id(tree))
    override fun children(tree: Uri, folder: Uri, limit: Int): List<PhoneDocument> = childrenOf(node(folder).id).take(limit + 1).map(::document)
    override fun create(folder: Uri, mimeType: String, name: String): Uri {
        val parent = node(folder)
        check(parent.folder)
        var actual = name
        var copy = 1
        while (childrenOf(parent.id).any { it.name == actual }) actual = "$name (${copy++})"
        val directory = mimeType == Document.MIME_TYPE_DIR
        return documentUri(add(Node(nextId(), actual, directory, parent.id, ByteArray(0), if (directory) FOLDER_FLAGS else FILE_FLAGS, mimeType)))
    }
    override fun delete(document: Uri) {
        val node = node(document)
        childrenOf(node.id).forEach { delete(documentUri(it.id)) }
        nodes.remove(node.id)
    }
    override fun rename(document: Uri, name: String): Uri {
        val node = node(document)
        nodes.remove(node.id)
        val renamed = Node(nextId(), name, node.folder, node.parent, node.data, node.flags, node.mimeType, node.modified + 1)
        add(renamed)
        nodes.values.filter { it.parent == node.id }.forEach { it.parent = renamed.id }
        return documentUri(renamed.id)
    }
    override fun read(document: Uri, offset: Long, buffer: ByteArray, length: Int): Int {
        val node = node(document)
        if (offset >= node.data.size) return 0
        val count = minOf(length.toLong(), node.data.size - offset).toInt()
        System.arraycopy(node.data, offset.toInt(), buffer, 0, count)
        return count
    }
    override fun write(document: Uri, data: ByteArray, append: Boolean) {
        val node = node(document)
        if (append && !appendSupported) throw UnsupportedOperationException("No append")
        node.data = if (append) node.data + data else data.copyOf()
        node.modified++
    }
    override fun openInput(document: Uri): InputStream = ByteArrayInputStream(node(document).data)
    override fun openOutput(document: Uri): OutputStream {
        val node = node(document)
        node.data = ByteArray(0)
        return object : ByteArrayOutputStream() { override fun close() { super.close(); node.data = toByteArray() } }
    }

    companion object {
        const val AUTHORITY = "test.documents"
        const val FILE_FLAGS = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        const val FOLDER_FLAGS = Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
    }
}
