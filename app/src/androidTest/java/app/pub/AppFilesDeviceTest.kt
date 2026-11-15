package app.pub

import android.content.ContextWrapper
import android.os.Looper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.core.FilesContract
import app.pub.hardware.ProjectMedia
import app.pub.projects.AppFiles
import app.pub.projects.ProjectStore
import com.google.gson.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Private app files in a sandboxed filesDir (never the installed app's own storage): every operation, quotas,
 * containment and links, the photos folder, preview save, edit-preview copies, deletion, startup cleanup, sessions and
 * the stream helpers.
 */
@RunWith(AndroidJUnit4::class)
class AppFilesDeviceTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var sandbox: File
    private lateinit var context: ContextWrapper
    private lateinit var media: ProjectMedia
    private lateinit var files: AppFiles
    private val app = UUID.randomUUID().toString()
    private val scope get() = AppFiles.Scope(app)
    private val links = mutableListOf<File>()

    @Before fun setup() {
        sandbox = File(base.cacheDir, "app-files-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(base) { override fun getFilesDir() = sandbox }
        media = ProjectMedia(context)
        files = AppFiles(context, media)
    }
    @After fun cleanup() {
        // Remove planted links first, so the recursive cleanup can never follow one.
        links.forEach { it.delete() }
        check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        sandbox.deleteRecursively()
    }

    private fun request(operation: String, vararg args: Pair<String, Any>): JsonObject = JsonObject().apply {
        addProperty("type", FilesContract.EFFECT); addProperty("id", "t_$operation"); addProperty("operation", operation)
        add("args", JsonObject().apply {
            args.forEach { (key, value) ->
                when (value) {
                    is String -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    else -> error("Unsupported argument")
                }
            }
        })
    }
    private fun call(operation: String, vararg args: Pair<String, Any>, within: AppFiles.Scope = scope, using: AppFiles = files): JsonObject =
        using.execute(within, request(operation, *args)).also {
            assertEquals("files", it["type"].asString); assertEquals("t_$operation", it["id"].asString); assertEquals(operation, it["operation"].asString)
        }
    private fun ok(operation: String, vararg args: Pair<String, Any>, within: AppFiles.Scope = scope, using: AppFiles = files): JsonObject =
        call(operation, *args, within = within, using = using).also { assertTrue(it.toString(), it["ok"].asBoolean) }
    private fun code(operation: String, vararg args: Pair<String, Any>, within: AppFiles.Scope = scope, using: AppFiles = files): String =
        call(operation, *args, within = within, using = using).let { assertFalse(it.toString(), it["ok"].asBoolean); it["code"].asString }
    private fun write(path: String, text: String, within: AppFiles.Scope = scope, append: Boolean = false, using: AppFiles = files) =
        ok("write", "path" to path, "data" to text, "encoding" to "utf8", "append" to append, within = within, using = using)
    private fun readAll(path: String, encoding: String = "utf8", within: AppFiles.Scope = scope, length: Int = FilesContract.MAX_CHUNK_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val reply = ok("read", "path" to path, "offset" to offset, "length" to length, "encoding" to encoding, within = within)
            val data = reply["data"].asString
            output.write(if (encoding == "base64") Base64.getDecoder().decode(data) else data.toByteArray(Charsets.UTF_8))
            assertEquals(offset, reply["offset"].asLong)
            assertTrue(reply.toString().length <= FilesContract.MAX_REPLY_CHARS)
            offset += reply["bytesRead"].asLong
            if (reply["eof"].asBoolean) break
            assertTrue(reply["bytesRead"].asLong > 0)
        }
        return output.toByteArray()
    }
    private fun text(path: String, within: AppFiles.Scope = scope) = readAll(path, within = within).toString(Charsets.UTF_8)
    private fun paths(path: String = "", recursive: Boolean = false, within: AppFiles.Scope = scope) =
        ok("list", "path" to path, "recursive" to recursive, within = within).getAsJsonArray("entries").map { it.asJsonObject["path"].asString }
    private fun folder(id: String = app) = File(sandbox.canonicalFile, "app-files/$id")
    private fun photo(projectId: String): String {
        val asset = "${UUID.randomUUID()}.jpg"
        File(media.directory(projectId), asset).writeBytes(Random.nextBytes(4096))
        return asset
    }
    private fun inode(file: File) = Os.lstat(file.path).st_ino
    private fun linkCount(file: File) = Os.lstat(file.path).st_nlink
    private fun hostLeftovers() = File(sandbox.canonicalFile, "app-files/.tmp").list().orEmpty().toList()

    @Test fun writeReadAppendAndChunkedRoundTrips() {
        assertEquals("directory", ok("stat", "path" to "").getAsJsonObject("entry")["type"].asString)
        assertEquals(listOf("photos"), paths())
        assertFalse(folder().exists())

        assertEquals(5, write("notes/a.txt", "Hello")["size"].asInt)
        assertTrue(File(folder(), "notes/a.txt").isFile)
        assertEquals(11, write("notes/a.txt", " world", append = true)["size"].asInt)
        assertEquals("Hello world", text("notes/a.txt"))
        val before = inode(File(folder(), "notes/a.txt"))
        write("notes/a.txt", "replaced")
        assertNotEquals("A write replaces the file instead of changing it in place", before, inode(File(folder(), "notes/a.txt")))
        assertEquals("replaced", text("notes/a.txt"))

        // Binary data in base64 chunks of any length.
        val binary = Random.nextBytes(FilesContract.MAX_CHUNK_BYTES)
        ok("write", "path" to "bin/data.bin", "data" to Base64.getEncoder().encodeToString(binary), "encoding" to "base64")
        assertArrayEquals(binary, readAll("bin/data.bin", "base64", length = 5_000))

        // Multi-byte text written in pieces and read back in whole characters, never split.
        val piece = "Grüße € 🎸 \"quoted\" <tag> \u0001\n".repeat(300)
        val pieceBytes = piece.toByteArray(Charsets.UTF_8)
        assertTrue(pieceBytes.size <= FilesContract.MAX_CHUNK_BYTES)
        write("long.txt", piece)
        repeat(3) { write("long.txt", piece, append = true) }
        assertEquals(piece.repeat(4), text("long.txt"))
        assertEquals(piece.repeat(4), readAll("long.txt", length = 1_001).toString(Charsets.UTF_8))
        assertEquals(pieceBytes.size * 4L, ok("stat", "path" to "long.txt").getAsJsonObject("entry")["size"].asLong)

        val end = ok("read", "path" to "notes/a.txt", "offset" to 100, "encoding" to "utf8")
        assertEquals("", end["data"].asString); assertTrue(end["eof"].asBoolean); assertEquals(0, end["bytesRead"].asInt)
        File(folder(), "binary.bin").writeBytes(byteArrayOf(0xFF.toByte(), 0x41))
        assertEquals("invalid_arguments", code("read", "path" to "binary.bin", "encoding" to "utf8"))
        assertEquals("not_found", code("write", "path" to "missing/x.txt", "data" to "x", "encoding" to "utf8", "createParents" to false))
        assertFalse(File(folder(), "missing").exists())
        assertEquals("is_directory", code("write", "path" to "notes", "data" to "x", "encoding" to "utf8"))
        assertEquals("not_directory", code("write", "path" to "notes/a.txt/x", "data" to "x", "encoding" to "utf8"))
        assertEquals("not_found", code("read", "path" to "nothing.txt", "encoding" to "utf8"))
        assertEquals("not_found", code("read", "path" to "notes/a.txt/x", "encoding" to "utf8"))
        assertEquals("is_directory", code("read", "path" to "notes", "encoding" to "utf8"))
        assertEquals("invalid_arguments", code("write", "path" to "x", "data" to "x".repeat(12_001), "encoding" to "utf8"))
        assertEquals(emptyList<String>(), hostLeftovers())
        assertFalse(folder().walk().any { it.name.startsWith(".") })
    }

    @Test fun listStatMkdirDeleteMoveAndCopy() {
        assertTrue(ok("mkdir", "path" to "a/b/c")["created"].asBoolean)
        assertFalse(ok("mkdir", "path" to "a/b/c")["created"].asBoolean)
        assertFalse(ok("mkdir", "path" to "photos")["created"].asBoolean)
        write("notes/a.txt", "A"); write("notes/b.txt", "BB"); write("a/b/c/deep.txt", "D")
        assertEquals("exists", code("mkdir", "path" to "notes/a.txt"))
        assertEquals("not_directory", code("mkdir", "path" to "notes/a.txt/sub"))
        assertEquals(listOf("a", "notes", "photos"), paths())
        assertEquals(listOf("a", "a/b", "a/b/c", "a/b/c/deep.txt", "notes", "notes/a.txt", "notes/b.txt", "photos"), paths(recursive = true))
        val entry = ok("stat", "path" to "notes/b.txt").getAsJsonObject("entry")
        assertEquals("b.txt", entry["name"].asString); assertEquals("file", entry["type"].asString); assertEquals(2, entry["size"].asInt)
        assertTrue(entry["modified"].asLong > 0)
        assertEquals("not_found", code("stat", "path" to "notes/c.txt"))
        assertEquals("not_directory", code("list", "path" to "notes/a.txt"))
        assertEquals("not_found", code("list", "path" to "none"))

        assertEquals("not_empty", code("delete", "path" to "a"))
        assertEquals(4, ok("delete", "path" to "a", "recursive" to true)["deleted"].asInt)
        assertFalse(File(folder(), "a").exists())
        assertEquals("not_found", code("delete", "path" to "a"))

        val moved = ok("move", "from" to "notes/a.txt", "to" to "archive/2026/a.txt").getAsJsonObject("entry")
        assertEquals("archive/2026/a.txt", moved["path"].asString)
        assertEquals("not_found", code("stat", "path" to "notes/a.txt"))
        assertEquals("A", text("archive/2026/a.txt"))
        assertEquals("exists", code("move", "from" to "notes/b.txt", "to" to "archive/2026/a.txt"))
        ok("move", "from" to "notes/b.txt", "to" to "archive/2026/a.txt", "overwrite" to true)
        assertEquals("BB", text("archive/2026/a.txt"))
        assertEquals("is_directory", code("move", "from" to "archive/2026/a.txt", "to" to "notes", "overwrite" to true))
        write("file.txt", "F")
        assertEquals("not_directory", code("move", "from" to "archive", "to" to "file.txt", "overwrite" to true))
        assertEquals("invalid_arguments", code("move", "from" to "archive", "to" to "archive/inside"))
        assertEquals("not_found", code("move", "from" to "ghost", "to" to "x"))

        ok("copy", "from" to "archive", "to" to "backup/archive")
        assertEquals(listOf("backup/archive", "backup/archive/2026", "backup/archive/2026/a.txt"), paths("backup", recursive = true))
        assertEquals("BB", text("backup/archive/2026/a.txt"))
        write("archive/2026/a.txt", "changed original")
        assertEquals("BB", text("backup/archive/2026/a.txt"))
        assertEquals("exists", code("copy", "from" to "file.txt", "to" to "backup/archive/2026/a.txt"))
        ok("copy", "from" to "file.txt", "to" to "backup/archive/2026/a.txt", "overwrite" to true)
        assertEquals("F", text("backup/archive/2026/a.txt"))
        assertEquals("is_directory", code("copy", "from" to "file.txt", "to" to "backup", "overwrite" to true))

        // A moved or copied folder's contents must still fit the depth limit.
        write("d1/d2/d3/d4/d5/d6/d7/f.txt", "deep")
        assertEquals("invalid_path", code("copy", "from" to "d1", "to" to "x/y/z"))
        assertEquals("invalid_path", code("move", "from" to "d1", "to" to "x/y/z"))
        ok("copy", "from" to "d1", "to" to "x")
        assertEquals("deep", text("x/d2/d3/d4/d5/d6/d7/f.txt"))

        val usage = ok("usage")
        val visible = folder().walk().filter { it != folder() }.toList()
        assertEquals(visible.size, usage["files"].asInt)
        assertEquals(visible.filter { it.isFile }.sumOf { it.length() }, usage["bytes"].asLong)
        assertEquals(FilesContract.QUOTA_BYTES, usage["quotaBytes"].asLong); assertEquals(FilesContract.QUOTA_ENTRIES, usage["quotaFiles"].asInt)

        // Listing pages: entries continue at nextOffset and none is skipped or repeated.
        repeat(520) { ok("mkdir", "path" to "many/d%03d".format(it)) }
        val first = ok("list", "path" to "many")
        assertTrue(first["truncated"].asBoolean); assertEquals(520, first["total"].asInt)
        val seen = first.getAsJsonArray("entries").map { it.asJsonObject["path"].asString }.toMutableList()
        var next = first["nextOffset"].asLong
        while (true) {
            val page = ok("list", "path" to "many", "offset" to next)
            seen += page.getAsJsonArray("entries").map { it.asJsonObject["path"].asString }
            if (!page["truncated"].asBoolean) break
            next = page["nextOffset"].asLong
        }
        assertEquals((0 until 520).map { "many/d%03d".format(it) }, seen)
        assertEquals(emptyList<String>(), hostLeftovers())
    }

    @Test fun quotasAreCheckedBeforeAnythingIsWritten() {
        val small = AppFiles(context, media, quotaBytes = 1_000, quotaEntries = 5)
        write("a.txt", "x".repeat(900), using = small)
        assertEquals("quota_exceeded", code("write", "path" to "b.txt", "data" to "y".repeat(200), "encoding" to "utf8", using = small))
        assertFalse(File(folder(), "b.txt").exists())
        assertEquals("quota_exceeded", code("write", "path" to "a.txt", "data" to "y".repeat(200), "encoding" to "utf8", "append" to true, using = small))
        assertEquals(900L, File(folder(), "a.txt").length())
        write("a.txt", "z".repeat(950), using = small) // Replacing counts only the difference.
        write("a.txt", "z".repeat(100), using = small)
        ok("mkdir", "path" to "m/n/o", using = small)
        ok("mkdir", "path" to "p", using = small)
        assertEquals("quota_exceeded", code("mkdir", "path" to "q", using = small))
        assertEquals("quota_exceeded", code("copy", "from" to "a.txt", "to" to "m/a.txt", using = small))
        assertEquals("quota_exceeded", code("write", "path" to "m/new.txt", "data" to "1", "encoding" to "utf8", using = small))
        assertFalse(File(folder(), "q").exists()); assertFalse(File(folder(), "m/a.txt").exists())
        val usage = ok("usage", using = small)
        assertEquals(5, usage["files"].asInt); assertEquals(100L, usage["bytes"].asLong)
        assertEquals(1_000L, usage["quotaBytes"].asLong); assertEquals(5, usage["quotaFiles"].asInt)
        ok("delete", "path" to "p", using = small)
        ok("mkdir", "path" to "q", using = small)
        // A move adds no entries, so it still works at the limit.
        ok("move", "from" to "q", "to" to "m/q", using = small)
        assertEquals(256L * 1024 * 1024, ok("usage")["quotaBytes"].asLong)
        assertEquals(5_000, ok("usage")["quotaFiles"].asInt)
    }

    @Test fun pathsCannotEscapeAndLinksAreNeverFollowed() {
        val outside = File(sandbox, "outside").apply { check(mkdir()) }
        val secret = File(outside, "secret.txt").apply { writeText("secret") }
        for (path in listOf("../x", "/etc/passwd", ".tmp/x", "a/../../x", "a//b", "./a", "photos/../x")) {
            assertEquals(path, "invalid_path", code("stat", "path" to path))
            assertEquals(path, "invalid_path", code("write", "path" to path, "data" to "x", "encoding" to "utf8"))
        }
        // Another app never sees this app's files.
        write("private.txt", "mine")
        assertEquals("not_found", code("stat", "path" to "private.txt", within = AppFiles.Scope(UUID.randomUUID().toString())))

        // Planted links are not listed and never followed by any operation, including recursive deletion.
        val link = File(folder(), "link").also { links += it }
        Os.symlink(outside.path, link.path)
        File(folder(), "dir").mkdir()
        val inner = File(folder(), "dir/link").also { links += it }
        Os.symlink(outside.path, inner.path)
        File(folder(), ".hidden").writeText("host only")
        assertEquals(listOf("dir", "photos", "private.txt"), paths())
        assertEquals(listOf("dir", "photos", "private.txt"), paths(recursive = true))
        assertEquals("unavailable", code("stat", "path" to "link"))
        assertEquals("unavailable", code("read", "path" to "link/secret.txt", "encoding" to "utf8"))
        assertEquals("unavailable", code("write", "path" to "link/new.txt", "data" to "x", "encoding" to "utf8"))
        assertEquals("unavailable", code("delete", "path" to "link", "recursive" to true))
        assertEquals("unavailable", code("copy", "from" to "link", "to" to "copied"))
        assertEquals(4L, ok("usage")["bytes"].asLong)
        ok("delete", "path" to "dir", "recursive" to true)
        assertEquals("secret", secret.readText()); assertFalse(File(outside, "new.txt").exists())
        assertEquals(listOf("secret.txt"), outside.list()!!.toList())

        // An app folder that is itself a link is refused.
        val linkedApp = UUID.randomUUID().toString()
        val linkedFolder = File(sandbox.canonicalFile, "app-files/$linkedApp").also { links += it }
        Os.symlink(outside.path, linkedFolder.path)
        assertEquals("unavailable", code("list", within = AppFiles.Scope(linkedApp)))
        assertEquals("unavailable", code("write", "path" to "x.txt", "data" to "x", "encoding" to "utf8", within = AppFiles.Scope(linkedApp)))
        assertEquals(listOf("secret.txt"), outside.list()!!.toList())
        assertThrows(IllegalArgumentException::class.java) { AppFiles.Scope("../$app") }
        assertThrows(IllegalArgumentException::class.java) { AppFiles.Scope("preview-not-a-uuid") }
    }

    @Test fun photosFolderListsCopiesAndDeletesPhotosThroughProjectMedia() {
        val first = photo(app); val second = photo(app)
        val photos = paths("photos")
        assertEquals(listOf(first, second).sorted().map { "photos/$it" }, photos)
        assertTrue(paths().contains("photos"))
        assertTrue(paths(recursive = true).containsAll(photos))
        val entry = ok("stat", "path" to "photos/$first").getAsJsonObject("entry")
        assertEquals(4096, entry["size"].asInt); assertEquals("file", entry["type"].asString); assertEquals(first, entry["name"].asString)
        assertEquals("directory", ok("stat", "path" to "photos").getAsJsonObject("entry")["type"].asString)
        assertEquals("not_found", code("stat", "path" to "photos/${UUID.randomUUID()}.jpg"))
        assertEquals("not_directory", code("list", "path" to "photos/$first"))
        assertArrayEquals(media.resolve(app, first)!!.readBytes(), readAll("photos/$first", "base64"))

        ok("copy", "from" to "photos/$first", "to" to "keep/first.jpg")
        assertArrayEquals(media.resolve(app, first)!!.readBytes(), readAll("keep/first.jpg", "base64"))
        ok("copy", "from" to "photos", "to" to "all-photos")
        assertEquals(listOf(first, second).sorted().map { "all-photos/$it" }, paths("all-photos"))
        assertEquals(3 * 4096L, ok("usage")["bytes"].asLong)
        assertEquals(2, ok("usage")["photos"].asInt)

        assertEquals("read_only", code("write", "path" to "photos/x.jpg", "data" to "x", "encoding" to "utf8"))
        assertEquals("read_only", code("mkdir", "path" to "photos/sub"))
        assertEquals("read_only", code("move", "from" to "photos/$first", "to" to "moved.jpg"))
        assertEquals("read_only", code("move", "from" to "keep/first.jpg", "to" to "photos/first.jpg"))
        assertEquals("read_only", code("copy", "from" to "keep/first.jpg", "to" to "photos/first.jpg"))
        assertEquals("read_only", code("delete", "path" to "photos", "recursive" to true))
        assertEquals(1, ok("delete", "path" to "photos/$first")["deleted"].asInt)
        assertNull(media.resolve(app, first))
        assertEquals(listOf("photos/$second"), paths("photos"))
        assertEquals(4096, readAll("keep/first.jpg", "base64").size) // The copy is independent of the photo.
        assertEquals("not_found", code("delete", "path" to "photos/$first"))

        // An edit preview lists its own captures and the saved app's photos; only its own can be deleted.
        val preview = "preview-${UUID.randomUUID()}"
        val capture = photo(preview)
        val editing = AppFiles.Scope(preview, preview, app)
        assertEquals(listOf(capture, second).sorted().map { "photos/$it" }, paths("photos", within = editing))
        assertEquals("read_only", code("delete", "path" to "photos/$second", within = editing))
        assertNotNull(media.resolve(app, second))
        ok("delete", "path" to "photos/$capture", within = editing)
        assertNull(media.resolve(preview, capture))
        ok("copy", "from" to "photos/$second", "to" to "copy.jpg", within = editing)
        assertEquals(listOf("photos/$second"), paths("photos"))
    }

    @Test fun savingANewAppMovesItsPreviewFilesAndClosingDiscardsThem() {
        val preview = "preview-${UUID.randomUUID()}"
        val previewScope = AppFiles.Scope(preview)
        write("notes/one.txt", "one", within = previewScope)
        ok("mkdir", "path" to "empty", within = previewScope)
        files.serial { files.publishPreview(preview, app) }
        assertFalse(folder(preview).exists())
        assertEquals(listOf("empty", "notes", "notes/one.txt", "photos"), paths(recursive = true))
        assertEquals("one", text("notes/one.txt"))
        assertEquals(3, ok("usage")["files"].asInt)

        // A preview without files publishes nothing; a non-empty destination is refused and the preview kept.
        val unused = "preview-${UUID.randomUUID()}"
        val other = UUID.randomUUID().toString()
        files.publishPreview(unused, other)
        assertFalse(folder(other).exists())
        val second = "preview-${UUID.randomUUID()}"
        write("draft.txt", "draft", within = AppFiles.Scope(second))
        assertThrows(IllegalStateException::class.java) { files.publishPreview(second, app) }
        assertEquals("draft", text("draft.txt", within = AppFiles.Scope(second)))

        // Closing an unsaved preview deletes its folder.
        files.serial { files.delete(second) }
        assertFalse(folder(second).exists())
        assertEquals("not_found", code("stat", "path" to "draft.txt", within = AppFiles.Scope(second)))
        assertEquals(emptyList<String>(), hostLeftovers())
    }

    @Test fun editPreviewChangesNeverTouchTheSavedFiles() {
        write("a.txt", "original A"); write("b.txt", "original B"); write("c.txt", "original C"); write("dir/d.txt", "original D")
        val binary = Random.nextBytes(9_000)
        ok("write", "path" to "big.bin", "data" to Base64.getEncoder().encodeToString(binary), "encoding" to "base64")
        val saved = { name: String -> File(folder(), name) }
        val preview = "preview-${UUID.randomUUID()}"
        val editing = AppFiles.Scope(preview, preview, app)
        files.serial { files.snapshot(app, preview) }
        // A real copy (Android does not let apps create hard links): nothing is shared with the saved files.
        assertEquals("original A", File(folder(preview), "a.txt").readText())
        assertNotEquals(inode(saved("a.txt")), inode(File(folder(preview), "a.txt")))
        assertEquals(1L, linkCount(saved("a.txt")))
        assertEquals(paths(recursive = true), paths(recursive = true, within = editing))

        write("a.txt", "changed A", within = editing)
        write("b.txt", " plus more", within = editing, append = true)
        ok("delete", "path" to "c.txt", within = editing)
        ok("move", "from" to "dir/d.txt", "to" to "moved.txt", within = editing)
        write("e.txt", "new E", within = editing)
        ok("mkdir", "path" to "f", within = editing)
        ok("write", "path" to "big.bin", "data" to "AAAA", "encoding" to "base64", "append" to true, within = editing)

        assertEquals("changed A", text("a.txt", within = editing))
        assertEquals("original B plus more", text("b.txt", within = editing))
        assertEquals("original D", text("moved.txt", within = editing))
        assertEquals("original A", saved("a.txt").readText())
        assertEquals("original B", saved("b.txt").readText())
        assertEquals("original C", saved("c.txt").readText())
        assertEquals("original D", saved("dir/d.txt").readText())
        assertArrayEquals(binary, saved("big.bin").readBytes())
        assertFalse(saved("e.txt").exists()); assertFalse(saved("f").exists())
        assertEquals(1L, linkCount(saved("a.txt"))); assertEquals(1L, linkCount(saved("b.txt"))); assertEquals(1L, linkCount(saved("big.bin")))

        // Save changes keeps the saved files; closing the preview discards its changes.
        files.delete(preview)
        assertEquals(listOf("a.txt", "b.txt", "big.bin", "c.txt", "dir", "dir/d.txt", "photos"), paths(recursive = true))
        assertEquals(1L, linkCount(saved("dir/d.txt")))

        // Appending to an unshared file extends it in place.
        val before = inode(saved("a.txt"))
        write("a.txt", "!", append = true)
        assertEquals(before, inode(saved("a.txt")))
        assertEquals("original A!", text("a.txt"))

        // An app without files gives an empty snapshot.
        val emptyApp = UUID.randomUUID().toString()
        val emptyPreview = "preview-${UUID.randomUUID()}"
        files.snapshot(emptyApp, emptyPreview)
        assertEquals(listOf("photos"), paths(within = AppFiles.Scope(emptyPreview, emptyPreview, emptyApp)))
        assertEquals(emptyList<String>(), hostLeftovers())
    }

    @Test fun deletingAnAppAndStartupCleanupRemoveOnlyWhatIsAbandoned() {
        write("kept.txt", "kept")
        val orphan = UUID.randomUUID().toString()
        write("orphan.txt", "orphan", within = AppFiles.Scope(orphan))
        val preview = "preview-${UUID.randomUUID()}"
        write("draft.txt", "draft", within = AppFiles.Scope(preview))
        val legacy = AppFiles.Scope(ProjectStore.LEGACY_TUNER_ID)
        write("tuning.json", "{}", within = legacy)
        val outside = File(sandbox, "outside").apply { check(mkdir()) }
        File(outside, "secret.txt").writeText("secret")
        val temporary = File(sandbox.canonicalFile, "app-files/.tmp")
        File(temporary, "${UUID.randomUUID()}.tmp").writeText("abandoned")
        val link = File(temporary, "link").also { links += it }
        Os.symlink(outside.path, link.path)
        val unrelated = File(sandbox.canonicalFile, "app-files/not-an-app").apply { check(mkdir()) }

        files.cleanupTemporary()
        assertTrue(folder(orphan).exists()) // Without the saved-app check only temporary work goes.
        assertFalse(folder(preview).exists())
        assertEquals(emptyList<String>(), temporary.list()!!.toList())
        assertEquals("secret", File(outside, "secret.txt").readText())

        files.cleanupTemporary { it == app || it == ProjectStore.LEGACY_TUNER_ID }
        assertFalse(folder(orphan).exists())
        assertEquals("kept", text("kept.txt"))
        assertEquals("{}", text("tuning.json", within = legacy))
        assertTrue(unrelated.isDirectory)

        files.serial { files.delete(app) }
        assertFalse(folder().exists())
        files.delete(app)
        assertEquals(listOf("photos"), paths())
        files.delete(ProjectStore.LEGACY_TUNER_ID)
        assertFalse(folder(ProjectStore.LEGACY_TUNER_ID).exists())
        assertThrows(IllegalArgumentException::class.java) { files.delete("../outside") }
    }

    @Test fun sessionsReplyOnTheMainThreadInOrderAndDropRepliesAfterClose() {
        write("a.txt", "A")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val replies = mutableListOf<JsonObject>()
        var offMain = 0
        val arrived = CountDownLatch(FilesContract.MAX_PENDING + 1)
        instrumentation.runOnMainSync {
            val session = files.open(scope) { reply ->
                if (Looper.myLooper() !== Looper.getMainLooper()) offMain++
                replies += reply; arrived.countDown()
            }
            // Within one main-thread turn no reply can arrive, so the request after MAX_PENDING waiting ones is busy.
            repeat(FilesContract.MAX_PENDING + 1) { index ->
                session.request(request("stat", "path" to "a.txt").apply { addProperty("id", "r$index") })
            }
        }
        assertTrue(arrived.await(10, TimeUnit.SECONDS))
        assertEquals(0, offMain)
        assertEquals(1, replies.count { it["code"]?.asString == "busy" })
        assertEquals((0 until FilesContract.MAX_PENDING).map { "r$it" }, replies.filter { it["ok"].asBoolean }.map { it["id"].asString })

        val late = mutableListOf<JsonObject>()
        instrumentation.runOnMainSync {
            val session = files.open(scope) { late += it }
            session.request(request("write", "path" to "late.txt", "data" to "x", "encoding" to "utf8"))
            session.request(request("stat", "path" to "a.txt"), busy = "Saving")
            session.close()
            assertTrue(session.closed)
        }
        files.serial { }
        instrumentation.runOnMainSync { }
        instrumentation.runOnMainSync { }
        assertEquals(emptyList<JsonObject>(), late)

        val busy = CountDownLatch(1)
        var answer: JsonObject? = null
        instrumentation.runOnMainSync { files.open(scope) { answer = it; busy.countDown() }.request(request("usage"), busy = "PUB is saving this app.") }
        assertTrue(busy.await(5, TimeUnit.SECONDS))
        assertEquals("busy", answer!!["code"].asString); assertEquals("PUB is saving this app.", answer!!["message"].asString)
    }

    @Test fun streamsImportAndExportWithTheSameRules() {
        val bytes = Random.nextBytes(50_000)
        val imported = files.importStream(scope, "in/doc.bin", ByteArrayInputStream(bytes))
        assertEquals("in/doc.bin", imported["path"].asString); assertEquals(50_000L, imported["size"].asLong)
        assertArrayEquals(bytes, ByteArrayOutputStream().also { assertEquals(50_000L, files.exportStream(scope, "in/doc.bin", it)) }.toByteArray())
        assertEquals("exists", assertThrows(FilesContract.Failure::class.java) { files.importStream(scope, "in/doc.bin", ByteArrayInputStream(bytes)) }.code)
        files.importStream(scope, "in/doc.bin", ByteArrayInputStream(byteArrayOf(1, 2, 3)), overwrite = true)
        assertArrayEquals(byteArrayOf(1, 2, 3), readAll("in/doc.bin", "base64"))
        assertEquals("read_only", assertThrows(FilesContract.Failure::class.java) { files.importStream(scope, "photos/x.jpg", ByteArrayInputStream(bytes)) }.code)
        assertEquals("invalid_path", assertThrows(FilesContract.Failure::class.java) { files.importStream(scope, "../x", ByteArrayInputStream(bytes)) }.code)
        assertEquals("is_directory", assertThrows(FilesContract.Failure::class.java) { files.exportStream(scope, "in", ByteArrayOutputStream()) }.code)
        val asset = photo(app)
        assertArrayEquals(media.resolve(app, asset)!!.readBytes(), ByteArrayOutputStream().also { files.exportStream(scope, "photos/$asset", it) }.toByteArray())
        val small = AppFiles(context, media, quotaBytes = 10_000)
        assertEquals("quota_exceeded", assertThrows(FilesContract.Failure::class.java) { small.importStream(scope, "big.bin", ByteArrayInputStream(bytes)) }.code)
        assertFalse(File(folder(), "big.bin").exists())
        assertEquals(emptyList<String>(), hostLeftovers())
    }

    /** Exports [path], running [change] once while the export streams (it holds no lock then). */
    private fun exportWhile(path: String, change: () -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        var pending = true
        val sink = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (pending) { pending = false; change() }
                output.write(b, off, len)
            }
        }
        val copied = files.exportStream(scope, path, sink)
        assertFalse("The change ran during the export", pending)
        return output.toByteArray().also { assertEquals(copied, it.size.toLong()) }
    }

    @Test fun anExportCopiesTheFileAsItWasWhenTheExportBegan() {
        // Larger than one stream buffer, so the change lands between reads.
        val bytes = Random.nextBytes(700_000)
        files.importStream(scope, "doc.bin", ByteArrayInputStream(bytes))
        val appended = bytes + "tail".toByteArray()
        assertArrayEquals(bytes, exportWhile("doc.bin") { write("doc.bin", "tail", append = true) })
        assertArrayEquals(appended, File(folder(), "doc.bin").readBytes())
        assertArrayEquals(appended, exportWhile("doc.bin") { write("doc.bin", "new") })
        assertEquals("new", File(folder(), "doc.bin").readText())
        assertArrayEquals("new".toByteArray(), exportWhile("doc.bin") { ok("delete", "path" to "doc.bin") })
        assertFalse(File(folder(), "doc.bin").exists())
        assertEquals(emptyList<String>(), hostLeftovers())
    }
}
