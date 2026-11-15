package app.pub

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.core.PhoneFileGrants
import app.pub.core.PhoneFileGrants.READ
import app.pub.core.PhoneFileGrants.WRITE
import app.pub.core.PhoneFilesContract
import app.pub.hardware.FileAccess
import app.pub.hardware.FilePick
import app.pub.hardware.HardwareBroker
import app.pub.hardware.PhoneFileGrantStore
import app.pub.hardware.PhoneFiles
import app.pub.hardware.ProjectMedia
import app.pub.projects.AppFiles
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Files on the phone (capability files) against an in-memory document provider and URI permissions ([FakeDocuments],
 * [FakeUriPermissions]) in a sandboxed filesDir: the grant store (persist, reload, holder counts, release, limits,
 * previews, deletion, forgetting, startup reconciliation), every operation on file and folder handles, import and
 * export, edit-preview read-only rules, revocation, the broker's gesture and picker rules, and that no reply carries
 * a URI. The real system picker and real providers are not exercised.
 */
@RunWith(AndroidJUnit4::class)
class PhoneFilesDeviceTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var sandbox: File
    private lateinit var context: ContextWrapper
    private lateinit var permissions: FakeUriPermissions
    private lateinit var documents: FakeDocuments
    private lateinit var store: PhoneFileGrantStore
    private lateinit var appFiles: AppFiles
    private lateinit var phone: PhoneFiles
    private val app = UUID.randomUUID().toString()
    private val other = UUID.randomUUID().toString()
    private val preview = "preview-${UUID.randomUUID()}"
    private val replies = mutableListOf<String>()
    private val saved get() = PhoneFiles.Scope(app, files = AppFiles.Scope(app))
    private val grantRoot get() = File(sandbox, "app-grants")

    @Before fun setup() {
        sandbox = File(base.cacheDir, "phone-files-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(base) { override fun getFilesDir() = sandbox }
        permissions = FakeUriPermissions()
        documents = FakeDocuments()
        store = PhoneFileGrantStore(grantRoot, permissions)
        appFiles = AppFiles(context, ProjectMedia(context))
        phone = PhoneFiles(store, documents, permissions, appFiles)
    }
    @After fun cleanup() {
        // Scripts never see content URIs or provider names.
        replies.forEach { assertFalse(it, it.contains("content://") || it.contains(FakeDocuments.AUTHORITY)) }
        check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        sandbox.deleteRecursively()
    }

    private fun effect(operation: String, vararg args: Pair<String, Any>): JsonObject = JsonObject().apply {
        addProperty("type", "hardware.request"); addProperty("id", "t_$operation"); addProperty("capability", "files"); addProperty("operation", operation)
        add("args", JsonObject().apply {
            args.forEach { (key, value) ->
                when (value) {
                    is String -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    is JsonElement -> add(key, value)
                    else -> error("Unsupported argument")
                }
            }
        })
    }
    private fun call(scope: PhoneFiles.Scope, operation: String, vararg args: Pair<String, Any>): PhoneFiles.Reply =
        phone.execute(scope, effect(operation, *args)).also { replies += it.event + it.data }
    private fun ok(scope: PhoneFiles.Scope, operation: String, event: String, vararg args: Pair<String, Any>): JsonObject =
        call(scope, operation, *args).also { assertEquals(it.data.toString(), event, it.event) }.data
    private fun code(scope: PhoneFiles.Scope, operation: String, vararg args: Pair<String, Any>): String =
        call(scope, operation, *args).let { assertEquals(it.data.toString(), "error", it.event); it.data["code"].asString }
    /** What the system picker would answer: [uris] carry the picker's temporary grant. */
    private fun pick(scope: PhoneFiles.Scope, uris: List<Uri>, vararg args: Pair<String, Any>, flags: Int = READ or WRITE): PhoneFiles.Reply {
        uris.forEach { permissions.pickerGrant(it, flags) }
        val request = PhoneFilesContract.parse(effect(if (args.any { it.first == "from" }) "export" else "pick", *args))
        phone.prepare(scope, request)
        return phone.picked(scope, request, uris).also { replies += it.event + it.data }
    }
    private fun handle(reply: PhoneFiles.Reply, index: Int = 0): String {
        assertEquals(reply.data.toString(), PhoneFilesContract.PICKED, reply.event)
        return reply.data.getAsJsonArray("items")[index].asJsonObject["handle"].asString
    }
    private fun names(data: JsonObject) = data.getAsJsonArray("entries").map { it.asJsonObject["name"].asString }
    private fun utf8(scope: PhoneFiles.Scope, handle: String, path: String? = null): String {
        val text = StringBuilder()
        var offset = 0L
        while (true) {
            val args = mutableListOf<Pair<String, Any>>("handle" to handle, "encoding" to "utf8", "offset" to offset, "length" to 7)
            if (path != null) args += "path" to path
            val data = ok(scope, "read", "data", *args.toTypedArray())
            text.append(data["data"].asString)
            offset += data["bytesRead"].asLong
            if (data["eof"].asBoolean) return text.toString()
        }
    }

    @Test fun savedAppGrantsPersistAcrossReloadAndRestart() {
        val fileId = documents.file(null, "Report.pdf", "%PDF", mimeType = "application/pdf")
        val uri = documents.documentUri(fileId)
        val picked = pick(saved, listOf(uri), "mode" to "open", "write" to true)
        val item = picked.data.getAsJsonArray("items")[0].asJsonObject
        assertEquals(setOf("handle", "name", "kind", "mimeType", "size", "canRead", "canWrite"), item.keySet())
        assertEquals("Report.pdf", item["name"].asString); assertEquals("application/pdf", item["mimeType"].asString)
        assertTrue(item["canWrite"].asBoolean); assertFalse(picked.data["truncated"].asBoolean)
        assertEquals(listOf(uri.toString() to (READ or WRITE)), permissions.taken)
        val file = File(grantRoot, "$app.json")
        assertTrue(file.isFile)
        val stored = PhoneFileGrants.parse(file.readText())
        assertEquals(uri.toString(), stored.single().uri); assertEquals(item["handle"].asString, stored.single().handle)

        // A new process reads the same grants; after a restart only the persisted permission remains, and it suffices.
        permissions.reboot()
        store = PhoneFileGrantStore(grantRoot, permissions)
        phone = PhoneFiles(store, documents, permissions, appFiles)
        val grants = ok(saved, "grants", "grants")
        assertEquals(PhoneFilesContract.MAX_GRANTS, grants["max"].asInt)
        assertEquals(item["handle"].asString, grants.getAsJsonArray("items")[0].asJsonObject["handle"].asString)
        assertEquals("%PDF", utf8(saved, item["handle"].asString))

        // Picking the same document again keeps its handle.
        assertEquals(item["handle"].asString, handle(pick(saved, listOf(uri), "mode" to "open")))
        assertEquals(1, store.grants(app).size)
    }

    @Test fun readOnlyPicksTakeOnlyReadAccess() {
        val uri = documents.documentUri(documents.file(null, "a.txt", "x"))
        val handle = handle(pick(saved, listOf(uri), "mode" to "open"))
        assertEquals(listOf(uri.toString() to READ), permissions.taken)
        assertFalse(ok(saved, "grants", "grants").getAsJsonArray("items")[0].asJsonObject["canWrite"].asBoolean)
        assertEquals("read_only", code(saved, "write", "handle" to handle, "data" to "y", "encoding" to "utf8"))
        assertEquals("x", documents.text(documents.nodes.keys.single()))
        // A picker that granted only read access leaves the grant read-only even when write was asked for.
        val second = documents.documentUri(documents.file(null, "b.txt", "x"))
        val item = pick(saved, listOf(second), "mode" to "open", "write" to true, flags = READ).data.getAsJsonArray("items")[0].asJsonObject
        assertFalse(item["canWrite"].asBoolean)
    }

    @Test fun aDocumentIsReleasedOnlyByItsLastSavedHolder() {
        val uri = documents.documentUri(documents.file(null, "shared.txt", "both"))
        val mine = handle(pick(saved, listOf(uri), "mode" to "open", "write" to true))
        val theirs = handle(pick(PhoneFiles.Scope(other), listOf(uri), "mode" to "open"))
        permissions.reboot()
        assertEquals(READ or WRITE, permissions.persisted()[uri.toString()])
        assertEquals(1, store.persistedCount())

        // The writer leaves: the reader keeps read access, write is released.
        ok(saved, "release", "released", "handle" to mine)
        assertEquals(listOf(uri.toString() to WRITE), permissions.released)
        assertEquals(READ, permissions.persisted()[uri.toString()])
        assertFalse(File(grantRoot, "$app.json").exists())
        assertEquals("not_found", code(saved, "read", "handle" to mine, "encoding" to "utf8"))
        assertEquals("both", utf8(PhoneFiles.Scope(other), theirs))

        // The last holder is deleted: the permission goes, the document stays.
        store.forget(other)
        assertNull(permissions.persisted()[uri.toString()])
        assertEquals(0, store.persistedCount())
        assertEquals("both", documents.text(documents.nodes.keys.single()))
    }

    @Test fun previewGrantsAreSessionOnlyAndNeverPersisted() {
        val uri = documents.documentUri(documents.file(null, "draft.txt", "preview"))
        val scope = PhoneFiles.Scope(preview)
        val handle = handle(pick(scope, listOf(uri), "mode" to "open", "write" to true))
        assertTrue("A preview never takes a persisted permission", permissions.taken.isEmpty())
        assertTrue(grantRoot.list().orEmpty().isEmpty())
        ok(scope, "write", "written", "handle" to handle, "data" to "edited", "encoding" to "utf8")
        assertEquals("edited", utf8(scope, handle))
        // Closing the preview (or saving it as an app) forgets them; nothing was persisted to release.
        store.forget(preview)
        assertEquals("not_found", code(scope, "stat", "handle" to handle))
        assertTrue(permissions.released.isEmpty())
        assertTrue(PhoneFileGrantStore(grantRoot, permissions).grants(preview).isEmpty())
    }

    @Test fun editPreviewsReadTheSavedAppsGrantsButNeverChangeThem() {
        val folderId = documents.folder(null, "Recipes")
        documents.file(folderId, "Soup.txt", "water")
        val tree = documents.treeUri(folderId)
        val savedHandle = handle(pick(saved, listOf(tree), "mode" to "folder", "write" to true))
        val grantFile = File(grantRoot, "$app.json").readText()
        val edit = PhoneFiles.Scope(preview, app, AppFiles.Scope(preview, preview, app))

        val listed = ok(edit, "grants", "grants").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(listOf(savedHandle), listed.map { it["handle"].asString })
        assertFalse(listed.single()["canWrite"].asBoolean)
        assertEquals(listOf("Soup.txt"), names(ok(edit, "list", "entries", "handle" to savedHandle)))
        assertEquals("water", utf8(edit, savedHandle, "Soup.txt"))
        assertEquals("imported", call(edit, "import", "handle" to savedHandle, "path" to "Soup.txt", "to" to "soup.txt").event)
        for ((operation, args) in listOf(
            "write" to arrayOf<Pair<String, Any>>("path" to "Soup.txt", "data" to "x", "encoding" to "utf8"),
            "mkdir" to arrayOf<Pair<String, Any>>("path" to "New"),
            "delete" to arrayOf<Pair<String, Any>>("path" to "Soup.txt"),
            "rename" to arrayOf<Pair<String, Any>>("path" to "Soup.txt", "name" to "Stew.txt"),
            "export" to arrayOf<Pair<String, Any>>("from" to "soup.txt", "path" to "Copy.txt"),
            "release" to emptyArray<Pair<String, Any>>())) {
            assertEquals(operation, "read_only", code(edit, operation, "handle" to savedHandle, *args))
        }
        assertEquals("water", documents.text(documents.named(folderId, "Soup.txt").id))
        assertEquals(grantFile, File(grantRoot, "$app.json").readText())

        // What the edit preview picks itself is writable and session-only; it shares the limit with the saved grants.
        val own = handle(pick(edit, listOf(documents.documentUri(documents.file(null, "x.txt", "1"))), "mode" to "open", "write" to true))
        ok(edit, "write", "written", "handle" to own, "data" to "2", "encoding" to "utf8")
        assertEquals(2, ok(edit, "grants", "grants").getAsJsonArray("items").size())
        store.forget(preview)
        assertEquals(listOf(savedHandle), store.grants(app).map { it.handle })
    }

    @Test fun grantLimitsAreCheckedBeforeThePickerOpens() {
        repeat(PhoneFilesContract.MAX_GRANTS) { i -> pick(saved, listOf(documents.documentUri(documents.file(null, "f$i", "$i"))), "mode" to "open") }
        val request = PhoneFilesContract.parse(effect("pick", "mode" to "open"))
        val failure = assertThrows(PhoneFilesContract.Failure::class.java) { phone.prepare(saved, request) }
        assertEquals("grant_limit", failure.code)
        assertEquals(PhoneFilesContract.MAX_GRANTS, store.grants(app).size)
        // An openMultiple pick keeps only the room left and says so.
        val scope = PhoneFiles.Scope(other)
        repeat(PhoneFilesContract.MAX_GRANTS - 2) { i -> pick(scope, listOf(documents.documentUri(documents.file(null, "g$i", "$i"))), "mode" to "open") }
        val many = (1..5).map { documents.documentUri(documents.file(null, "m$it", "$it")) }
        val multiple = pick(scope, many, "mode" to "openMultiple", "max" to 5)
        assertEquals(2, multiple.data.getAsJsonArray("items").size()); assertTrue(multiple.data["truncated"].asBoolean)
    }

    @Test fun hostWideLimitStopsBeforeAndroidsPersistedGrantLimit() {
        val apps = (0 until PhoneFilesContract.MAX_HOST_GRANTS / PhoneFilesContract.MAX_GRANTS).map { UUID.randomUUID().toString() }
        for (owner in apps) repeat(PhoneFilesContract.MAX_GRANTS) { i ->
            val uri = documents.documentUri(documents.file(null, "$owner-$i", "x"))
            permissions.pickerGrant(uri)
            store.add(owner, uri, false, false, "x", "text/plain")
        }
        assertEquals(PhoneFilesContract.MAX_HOST_GRANTS, store.persistedCount())
        val request = PhoneFilesContract.parse(effect("pick", "mode" to "open"))
        assertEquals("grant_limit", assertThrows(PhoneFilesContract.Failure::class.java) { phone.prepare(saved, request) }.code)
        val uri = documents.documentUri(documents.file(null, "one more", "x"))
        permissions.pickerGrant(uri)
        assertEquals("grant_limit", assertThrows(PhoneFilesContract.Failure::class.java) { store.add(app, uri, false, false, "x", "text/plain") }.code)
        // Previews do not persist anything and are not limited by it.
        assertNotNull(store.add(preview, uri, false, false, "x", "text/plain"))
    }

    @Test fun deletingOrForgettingAnAppEndsAccessButKeepsTheDocuments() {
        val folderId = documents.folder(null, "Photos backup")
        documents.file(folderId, "a.jpg", "jpeg")
        val tree = documents.treeUri(folderId)
        pick(saved, listOf(tree), "mode" to "folder", "write" to true)
        pick(saved, listOf(documents.documentUri(documents.file(null, "b.txt", "b"))), "mode" to "open")
        val before = documents.nodes.keys.toSet()
        store.forget(app)
        assertFalse(File(grantRoot, "$app.json").exists())
        assertTrue(permissions.persisted().isEmpty())
        assertEquals(before, documents.nodes.keys.toSet())
        assertEquals(0, ok(saved, "grants", "grants").getAsJsonArray("items").size())
    }

    @Test fun startupRemovesDeletedAppsAndPermissionsNothingNeeds() {
        val kept = documents.documentUri(documents.file(null, "kept", "1"))
        val gone = documents.documentUri(documents.file(null, "gone", "2"))
        val orphan = documents.documentUri(documents.file(null, "orphan", "3"))
        pick(saved, listOf(kept), "mode" to "open")
        pick(PhoneFiles.Scope(other), listOf(gone), "mode" to "open", "write" to true)
        // A change cut short: a persisted permission no grant refers to, and a write bit nothing needs.
        permissions.pickerGrant(orphan); permissions.take(orphan, READ or WRITE); permissions.take(kept, READ or WRITE)
        val fresh = PhoneFileGrantStore(grantRoot, permissions)
        fresh.reconcile { it == app } // the other app's package no longer exists
        assertFalse(File(grantRoot, "$other.json").exists())
        assertEquals(mapOf(kept.toString() to READ), permissions.persisted())
        assertEquals(1, fresh.grants(app).size)
        assertTrue(fresh.grants(other).isEmpty())
    }

    @Test fun startupKeepsEveryPermissionWhileASavedAppsGrantFileIsUnreadable() {
        val held = documents.documentUri(documents.file(null, "held", "1"))
        pick(saved, listOf(held), "mode" to "open")
        // The saved app's grant file becomes unreadable: the documents it refers to are unknown at startup.
        File(grantRoot, "$app.json").writeText("{not json")
        val fresh = PhoneFileGrantStore(grantRoot, permissions)
        fresh.reconcile { it == app }
        assertEquals(mapOf(held.toString() to READ), permissions.persisted())
        assertTrue(File(grantRoot, "$app.json").exists())
    }
    @Test fun anUnreadableGrantFileCountsAsEmptyUntilReplaced() {
        check(grantRoot.mkdirs())
        val file = File(grantRoot, "$app.json")
        file.writeText("{\"version\":1,\"grants\":[{\"uri\":\"content://elsewhere\"}]}")
        assertTrue(store.grants(app).isEmpty())
        assertTrue(file.readText().contains("elsewhere"))
        pick(saved, listOf(documents.documentUri(documents.file(null, "new", "1"))), "mode" to "open")
        assertEquals(1, PhoneFileGrants.parse(file.readText()).size)
    }

    @Test fun folderOperationsWorkByRealNamesInsideThePickedFolderOnly() {
        val root = documents.folder(null, "Documents")
        documents.file(root, "Crème brûlée.md", "sugar")
        documents.file(documents.folder(null, "Elsewhere"), "secret.txt", "no")
        val handle = handle(pick(saved, listOf(documents.treeUri(root)), "mode" to "folder", "write" to true))
        assertEquals("folder", ok(saved, "stat", "entry", "handle" to handle).getAsJsonObject("entry")["kind"].asString)

        val written = ok(saved, "write", "written", "handle" to handle, "path" to "Notes/2024/Mom's list.txt", "data" to "eggs", "encoding" to "utf8")
        assertTrue(written["created"].asBoolean)
        assertEquals("Notes/2024/Mom's list.txt", written.getAsJsonObject("entry")["path"].asString)
        ok(saved, "write", "written", "handle" to handle, "path" to "Notes/2024/Mom's list.txt", "data" to ", milk", "encoding" to "utf8", "append" to true)
        assertEquals("eggs, milk", utf8(saved, handle, "Notes/2024/Mom's list.txt"))
        assertEquals("sugar", utf8(saved, handle, "Crème brûlée.md"))
        assertEquals(listOf("Crème brûlée.md", "Notes"), names(ok(saved, "list", "entries", "handle" to handle)))
        val entry = ok(saved, "list", "entries", "handle" to handle, "path" to "Notes/2024").getAsJsonArray("entries")[0].asJsonObject
        assertEquals(setOf("name", "path", "kind", "mimeType", "size", "modified", "canWrite"), entry.keySet())

        assertFalse(ok(saved, "mkdir", "created", "handle" to handle, "path" to "Notes")["created"].asBoolean)
        assertTrue(ok(saved, "mkdir", "created", "handle" to handle, "path" to "Archive/Old")["created"].asBoolean)
        assertEquals("exists", code(saved, "mkdir", "handle" to handle, "path" to "Crème brûlée.md"))
        val renamed = ok(saved, "rename", "renamed", "handle" to handle, "path" to "Notes/2024", "name" to "Twenty 24")
        assertEquals("Notes/Twenty 24", renamed.getAsJsonObject("entry")["path"].asString)
        assertEquals("eggs, milk", utf8(saved, handle, "Notes/Twenty 24/Mom's list.txt"))
        assertEquals("exists", code(saved, "rename", "handle" to handle, "path" to "Notes", "name" to "Archive"))

        assertEquals("not_found", code(saved, "read", "handle" to handle, "path" to "secret.txt", "encoding" to "utf8"))
        assertEquals("invalid_path", code(saved, "read", "handle" to handle, "path" to "../Elsewhere/secret.txt", "encoding" to "utf8"))
        assertEquals("is_directory", code(saved, "read", "handle" to handle, "path" to "Notes", "encoding" to "utf8"))
        assertEquals("not_directory", code(saved, "list", "handle" to handle, "path" to "Crème brûlée.md"))
        assertEquals("invalid_path", code(saved, "delete", "handle" to handle))
        assertEquals("not_empty", code(saved, "delete", "handle" to handle, "path" to "Notes"))
        ok(saved, "delete", "deleted", "handle" to handle, "path" to "Notes", "recursive" to true)
        assertEquals(listOf("Archive", "Crème brûlée.md"), names(ok(saved, "list", "entries", "handle" to handle)))
        assertEquals("no", documents.text(documents.nodes.values.single { it.name == "secret.txt" }.id))

        // Names a path cannot address are listed without a path; two items with one name are never guessed between.
        documents.file(root, "a/b", "cloud name")
        documents.file(root, "twin", "1"); documents.file(root, "twin", "2")
        val listed = ok(saved, "list", "entries", "handle" to handle).getAsJsonArray("entries").map { it.asJsonObject }
        assertFalse(listed.single { it["name"].asString == "a/b" }.has("path"))
        assertEquals("unavailable", code(saved, "read", "handle" to handle, "path" to "twin", "encoding" to "utf8"))
    }

    @Test fun fileHandlesReadWriteAppendAndDeleteTheirDocument() {
        val id = documents.file(null, "log.txt", "line one\n")
        val handle = handle(pick(saved, listOf(documents.documentUri(id)), "mode" to "open", "write" to true))
        assertEquals("log.txt", ok(saved, "stat", "entry", "handle" to handle).getAsJsonObject("entry")["name"].asString)
        assertEquals("invalid_arguments", code(saved, "stat", "handle" to handle, "path" to "x"))
        assertEquals("not_directory", code(saved, "list", "handle" to handle))
        assertEquals(18, ok(saved, "write", "written", "handle" to handle, "data" to "line two\n", "encoding" to "utf8", "append" to true)["size"].asLong)
        assertEquals("line one\nline two\n", utf8(saved, handle))
        ok(saved, "write", "written", "handle" to handle, "data" to "AQID", "encoding" to "base64")
        assertArrayEquals(byteArrayOf(1, 2, 3), documents.nodes.getValue(id).data)
        val binary = ok(saved, "read", "data", "handle" to handle, "encoding" to "base64", "offset" to 1)
        assertEquals("AgM=", binary["data"].asString); assertTrue(binary["eof"].asBoolean); assertEquals(3, binary["size"].asLong)
        documents.appendSupported = false
        assertEquals("unavailable", code(saved, "write", "handle" to handle, "data" to "x", "encoding" to "utf8", "append" to true))

        val deleted = ok(saved, "delete", "deleted", "handle" to handle)
        assertTrue(deleted["released"].asBoolean)
        assertFalse(documents.nodes.containsKey(id))
        assertTrue(store.grants(app).isEmpty())
        // Read-only documents and online-only documents are refused clearly.
        val fixed = handle(pick(saved, listOf(documents.documentUri(documents.file(null, "fixed", "f", flags = 0))), "mode" to "open", "write" to true))
        assertEquals("read_only", code(saved, "write", "handle" to fixed, "data" to "x", "encoding" to "utf8"))
        assertEquals("read_only", code(saved, "delete", "handle" to fixed))
        val online = handle(pick(saved, listOf(documents.documentUri(documents.file(null, "Doc", "", flags = Document.FLAG_VIRTUAL_DOCUMENT))), "mode" to "open"))
        assertEquals("unavailable", code(saved, "read", "handle" to online, "encoding" to "utf8"))
    }

    @Test fun importAndExportStreamBetweenPickedFilesAndThePrivateFolder() {
        val big = "0123456789".repeat(5_000) // larger than one read or write request
        val sourceId = documents.file(null, "big.txt", big)
        val source = handle(pick(saved, listOf(documents.documentUri(sourceId)), "mode" to "open"))
        val imported = ok(saved, "import", "imported", "handle" to source, "to" to "inbox/big.txt")
        assertEquals(big.length.toLong(), imported.getAsJsonObject("entry")["size"].asLong)
        assertEquals("exists", code(saved, "import", "handle" to source, "to" to "inbox/big.txt"))
        assertEquals("read_only", code(saved, "export", "from" to "inbox/big.txt", "handle" to source))

        val folder = documents.folder(null, "Backups")
        val target = handle(pick(saved, listOf(documents.treeUri(folder)), "mode" to "folder", "write" to true))
        val exported = ok(saved, "export", "exported", "from" to "inbox/big.txt", "handle" to target, "path" to "2024/big copy.txt")
        assertEquals(big.length.toLong(), exported["bytes"].asLong)
        assertEquals(big, documents.text(documents.nodes.values.single { it.name == "big copy.txt" }.id))
        assertEquals("exists", code(saved, "export", "from" to "inbox/big.txt", "handle" to target, "path" to "2024/big copy.txt"))
        ok(saved, "export", "exported", "from" to "inbox/big.txt", "handle" to target, "path" to "2024/big copy.txt", "overwrite" to true)
        assertEquals("not_found", code(saved, "export", "from" to "missing.txt", "handle" to target, "path" to "x.txt"))
        assertFalse(documents.childrenOf(folder).any { it.name == "x.txt" })

        // Save as: the user places a new document, PUB writes the private file into it and keeps the grant.
        val createdId = documents.file(folder, "Notes.txt", "")
        val saveAs = pick(saved, listOf(documents.documentUri(createdId)), "from" to "inbox/big.txt", "createName" to "Notes.txt")
        assertEquals(saveAs.data.toString(), PhoneFilesContract.EXPORTED, saveAs.event)
        assertEquals(big, documents.text(createdId))
        assertTrue(saveAs.data.getAsJsonObject("item")["canWrite"].asBoolean)
        // An export whose private file is missing fails before the picker opens.
        val missing = PhoneFilesContract.parse(effect("export", "from" to "missing.txt", "createName" to "x.txt"))
        assertEquals("not_found", assertThrows(PhoneFilesContract.Failure::class.java) { phone.prepare(saved, missing) }.code)
        // Without storage.files there is no private folder to import into.
        assertEquals("unavailable", code(PhoneFiles.Scope(app), "import", "handle" to source, "to" to "x.txt"))
    }

    @Test fun accessRevokedOutsidePubAnswersRevokedAndDropsTheGrant() {
        val uri = documents.documentUri(documents.file(null, "a.txt", "a"))
        val handle = handle(pick(saved, listOf(uri), "mode" to "open", "write" to true))
        // The provider refuses while PUB still holds access: an error, and the grant stays.
        documents.failure = { SecurityException("Provider says no") }
        assertEquals("unavailable", code(saved, "read", "handle" to handle, "encoding" to "utf8"))
        assertEquals(1, store.grants(app).size)
        // Access really ended (for example the document's app revoked it): revoked, and the grant is dropped.
        documents.failure = { permissions.revoke(uri); SecurityException("Revoked") }
        assertEquals("revoked", code(saved, "read", "handle" to handle, "encoding" to "utf8"))
        assertTrue(store.grants(app).isEmpty())
        assertEquals("not_found", code(saved, "read", "handle" to handle, "encoding" to "utf8"))

        // grants drops what no longer works; an edit preview reports revoked but leaves the saved app's grant alone.
        val second = documents.documentUri(documents.file(null, "b.txt", "b"))
        val kept = handle(pick(saved, listOf(second), "mode" to "open"))
        permissions.revoke(second)
        val edit = PhoneFiles.Scope(preview, app)
        assertEquals("revoked", code(edit, "read", "handle" to kept, "encoding" to "utf8"))
        assertEquals(1, store.grants(app).size)
        assertEquals(0, ok(saved, "grants", "grants").getAsJsonArray("items").size())
        assertTrue(store.grants(app).isEmpty())
    }

    @Test fun pickerAnswersAreBoundedAndNamesSanitized() {
        val ids = (1..25).map { documents.file(null, "file\n$it", "x") }
        val reply = pick(saved, ids.map { documents.documentUri(it) }, "mode" to "openMultiple")
        val items = reply.data.getAsJsonArray("items")
        assertEquals(PhoneFilesContract.MAX_PICK, items.size()); assertTrue(reply.data["truncated"].asBoolean)
        assertTrue(items.all { !it.asJsonObject["name"].asString.contains('\n') })
        assertTrue(PhoneFilesContract.fits(reply.data))
        val missing = pick(saved, listOf(documents.documentUri("doc-missing")), "mode" to "open")
        assertEquals("error", missing.event)
        assertEquals("not_found", missing.data["code"].asString)
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(message: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) { var done = false; main { done = predicate() }; if (done) return; Thread.sleep(20) }
        fail(message)
    }

    @Test fun theBrokerOpensThePickerOnlyFromAButtonAndANewSessionAnswersIt() {
        val prefs = base.getSharedPreferences("permissions", Context.MODE_PRIVATE)
        val keys = listOf("files.blocked", "files.default", "$app.files")
        val prior = keys.associateWith { prefs.all[it] }
        val output = mutableListOf<JsonObject>()
        val picks = mutableListOf<FilePick>()
        var prompts = 0
        val access = FileAccess(phone, saved) { picks += it; true }
        val brokers = mutableListOf<HardwareBroker>()
        fun broker() = HardwareBroker(base, app, false, setOf("files", "storage.files"), { true }, { if (it != null) prompts++ }, {}, {}, {}, {},
            { event, _ -> output += event; replies += event.toString() }, {}, access).also { brokers += it }
        fun codes() = output.filter { it["event"].asString == "error" }.map { it["data"].asJsonObject["code"].asString }
        try {
            lateinit var first: HardwareBroker
            main {
                prefs.edit().putBoolean("files.blocked", false).putString("files.default", "ASK").remove("$app.files").commit()
                first = broker()
                first.request(effect("pick", "mode" to "open"), false)
                first.request(effect("grants"), false)
                first.request(effect("pick", "mode" to "open"), true)
                first.consent(true, false)
            }
            await("two refusals without a tap") { codes() == listOf("gesture_required", "gesture_required") }
            assertEquals(1, prompts)
            await("picker requested") { picks.size == 1 }
            assertEquals("open", picks[0].mode)

            // Leaving PUB for the picker closed the asking session; a new session answers with its consent.
            val uri = documents.documentUri(documents.file(null, "Notes.txt", "hello"))
            permissions.pickerGrant(uri, READ)
            lateinit var second: HardwareBroker
            main { first.close(); output.clear(); second = broker(); second.completePick(picks[0], listOf(uri), false) }
            await("picked") { output.any { it["event"].asString == PhoneFilesContract.PICKED } }
            val handle = output.first { it["event"].asString == PhoneFilesContract.PICKED }["data"].asJsonObject.getAsJsonArray("items")[0].asJsonObject["handle"].asString

            // Consented in this session: reading works without a tap; deleting the user's document never does.
            main { output.clear(); second.request(effect("read", "handle" to handle, "encoding" to "utf8"), false); second.request(effect("delete", "handle" to handle), false) }
            await("read without a tap") { output.any { it["event"].asString == PhoneFilesContract.DATA } }
            await("delete refused") { codes() == listOf("gesture_required") }
            main { output.clear(); second.completePick(FilePick(effect("pick", "mode" to "open"), PhoneFilesContract.parse(effect("pick", "mode" to "open")), true), null, false) }
            await("cancelled") { output.any { it["event"].asString == PhoneFilesContract.CANCELLED } }

            // A saved app always allowed: a fresh session reopens its documents at init. A global block stops it.
            lateinit var third: HardwareBroker
            main { prefs.edit().putString("$app.files", "ALLOW").commit(); output.clear(); third = broker(); third.request(effect("grants"), false) }
            await("grants at init") { output.any { it["event"].asString == PhoneFilesContract.GRANTS } }
            main { prefs.edit().putBoolean("files.blocked", true).commit(); output.clear(); broker().request(effect("grants"), true) }
            await("blocked") { codes() == listOf("blocked") }
        } finally {
            main {
                brokers.forEach { it.close() }
                prefs.edit().apply { keys.forEach { key -> when (val old = prior[key]) { is Boolean -> putBoolean(key, old); is String -> putString(key, old); else -> remove(key) } } }.commit()
            }
        }
    }
}
