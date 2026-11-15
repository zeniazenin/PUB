package app.pub

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.util.AtomicFile
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.builder.BuildDraftSnapshot
import app.pub.builder.BuildDraftStore
import app.pub.builder.BuildDraftStoreException
import app.pub.core.ProjectBundle
import app.pub.projects.SavedProject
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BuildDraftStoreTest {
    private lateinit var directory: File
    private lateinit var context: ContextWrapper
    private val file get() = File(directory, "builder-draft.json")
    @Before fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.cacheDir, "draft-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
    }
    @After fun cleanup() { check(directory.deleteRecursively()) }
    private fun bundle(name: String, withAsset: Boolean = false): ProjectBundle {
        val json = JsonObject().apply {
            addProperty("name", name); addProperty("description", "Draft store fixture")
            addProperty("source", "function dispatch(e){return JSON.stringify({ui:{type:'text',text:'Ready'},effects:[]});}")
            add("capabilities", JsonArray())
        }
        if (withAsset) {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val output = ByteArrayOutputStream()
            try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) } finally { bitmap.recycle() }
            json.add("assets", JsonObject().apply { add("icon", JsonObject().apply {
                addProperty("type", "image"); addProperty("prompt", "Small icon fixture")
                addProperty("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
            }) })
            json.addProperty("icon", "icon")
        }
        return ProjectBundle.parse(json.toString())
    }
    private fun snapshot() = BuildDraftSnapshot(
        prompt = "Make the timer calmer.\nKeep café 🎸 text.", sessionId = UUID.randomUUID().toString(),
        editingProject = SavedProject(UUID.randomUUID().toString(), bundle("Original", true)),
        draft = bundle("Updated", true), draftHistoryId = UUID.randomUUID().toString(),
        draftRequest = "Make the timer calmer.", draftModel = "astra-test", repairPass = 1,
        message = "Review ready", visualReview = "The labels are readable.", visualReviewError = null, running = false
    )
    private fun assertSameContent(expected: BuildDraftSnapshot, actual: BuildDraftSnapshot) {
        assertEquals(expected.prompt, actual.prompt); assertEquals(expected.sessionId, actual.sessionId)
        assertEquals(expected.editingProject?.id, actual.editingProject?.id)
        assertEquals(expected.editingProject?.bundle?.toJson(), actual.editingProject?.bundle?.toJson())
        assertEquals(expected.draft?.toJson(), actual.draft?.toJson()); assertEquals(expected.draftHistoryId, actual.draftHistoryId)
        assertEquals(expected.draftRequest, actual.draftRequest); assertEquals(expected.draftModel, actual.draftModel)
        assertEquals(expected.repairPass, actual.repairPass); assertEquals(expected.message, actual.message)
        assertEquals(expected.visualReview, actual.visualReview); assertEquals(expected.visualReviewError, actual.visualReviewError)
        assertEquals(expected.running, actual.running)
    }

    @Test fun editingBaselineDraftAndGeneratedAssetsSurviveReload() {
        val store = BuildDraftStore(context); assertNull(store.read())
        val expected = snapshot(); store.write(expected)
        assertSameContent(expected, BuildDraftStore(context).read()!!)
        val root = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals(setOf("version", "prompt", "sessionId", "editingProject", "draft", "draftHistoryId", "draftRequest", "draftModel", "repairPass", "message", "visualReview", "visualReviewError", "running"), root.keySet())
        assertEquals(setOf("id", "bundle"), root.getAsJsonObject("editingProject").keySet())
    }

    @Test fun partialCreateAndTunerIdentityRoundTripAndClear() {
        val store = BuildDraftStore(context)
        val empty = snapshot().copy(prompt = "", editingProject = null, draft = null, draftHistoryId = null,
            draftRequest = "", draftModel = "", repairPass = 0, message = null, visualReview = null)
        store.write(empty); assertSameContent(empty, store.read()!!)
        val tuner = empty.copy(editingProject = SavedProject("guitar-tuner", bundle("Tuner")))
        store.write(tuner); assertSameContent(tuner, store.read()!!)
        store.clear(); assertNull(BuildDraftStore(context).read()); assertFalse(file.exists())
        store.clear() // Explicit clearing is idempotent, including when no snapshot exists.
    }

    @Test fun runningStateAndLastDraftSurviveAnInterruptedAtomicWrite() {
        val store = BuildDraftStore(context)
        val running = snapshot().copy(running = true, message = "Repairing preview…", repairPass = 2)
        store.write(running)
        // Closing startWrite without finishWrite simulates death during the next commit.
        AtomicFile(file).startWrite().use { it.write("{unfinished".toByteArray()) }
        val restored = BuildDraftStore(context).read()!!
        assertSameContent(running, restored)
        assertTrue(restored.running) // The controller owns interruption policy; the store never retries.
        assertNotNull(restored.draft)
    }

    @Test fun corruptUnknownDuplicateAndNonUtf8FilesStayUntouchedUntilExplicitClear() {
        val store = BuildDraftStore(context); val expected = snapshot(); store.write(expected)
        val valid = file.readText()
        val unknown = JsonParser.parseString(valid).asJsonObject.apply { addProperty("version", 2) }.toString()
        val extra = JsonParser.parseString(valid).asJsonObject.apply { addProperty("accessToken", "must-not-be-accepted") }.toString()
        val duplicate = valid.replaceFirst("\"version\":1", "\"version\":1,\"version\":1")
        val invalid = listOf("{unfinished".toByteArray(), unknown.toByteArray(), extra.toByteArray(), duplicate.toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))
        invalid.forEach { bytes ->
            file.writeBytes(bytes)
            assertThrows(BuildDraftStoreException::class.java) { store.read() }
            assertThrows(BuildDraftStoreException::class.java) { store.write(expected) }
            assertArrayEquals(bytes, file.readBytes())
        }
        store.clear(); assertNull(store.read())
    }

    @Test fun invalidBoundsAndIdentitiesCannotReplaceThePreviousSnapshot() {
        val store = BuildDraftStore(context); val valid = snapshot(); store.write(valid)
        val bytes = file.readBytes()
        val invalid = listOf(
            valid.copy(prompt = "x".repeat(8001)), valid.copy(draftRequest = "x".repeat(8001)),
            valid.copy(draftModel = "x".repeat(201)), valid.copy(message = "x".repeat(1001)),
            valid.copy(visualReview = "x".repeat(6001)), valid.copy(visualReviewError = "x".repeat(6001)),
            valid.copy(repairPass = -1), valid.copy(repairPass = 3), valid.copy(sessionId = "../../other"),
            valid.copy(draftHistoryId = "guitar-tuner"), valid.copy(editingProject = SavedProject("../other", bundle("Other")))
        )
        invalid.forEach { rejected ->
            assertThrows(BuildDraftStoreException::class.java) { store.write(rejected) }
            assertArrayEquals(bytes, file.readBytes())
        }
        val boundary = valid.copy(prompt = "p".repeat(8000), draftRequest = "r".repeat(8000), draftModel = "m".repeat(200),
            repairPass = 2, message = "m".repeat(1000), visualReview = "v".repeat(6000), visualReviewError = "e".repeat(6000))
        store.write(boundary); assertSameContent(boundary, store.read()!!)
    }

    @Test fun oversizedSnapshotIsRejectedBeforeReadingItsPayload() {
        val store = BuildDraftStore(context)
        RandomAccessFile(file, "rw").use { it.setLength(BuildDraftStore.MAX_SNAPSHOT_BYTES.toLong() + 1) }
        assertThrows(BuildDraftStoreException::class.java) { store.read() }
        assertThrows(BuildDraftStoreException::class.java) { store.write(snapshot()) }
        assertEquals(BuildDraftStore.MAX_SNAPSHOT_BYTES.toLong() + 1, file.length())
    }
}
