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
import app.pub.core.AppTestSuite
import app.pub.core.ChecksReport
import app.pub.core.ProjectBundle
import app.pub.core.ResearchBrief
import app.pub.core.UiPlan
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
    private fun research() = ResearchBrief.parseModelOutput("""{"summary":"A calm practice timer · café 🎸","category":"Timer",
        "features":[{"title":"Start and stop","priority":"must","feasibleInPub":true,"notes":""},
        {"title":"Alarm when done","priority":"should","feasibleInPub":false,"notes":"PUB has no timers or notifications"}],
        "acceptanceChecks":["Tap Start then Stop -> elapsed time shown"],"assumptions":["Minutes and seconds only"]}""")
        .withSources(listOf(ResearchBrief.Source.of("Timer guide", "https://example.com/timers?utm_source=openai")), true)
    private fun tests() = AppTestSuite.parse("""[{"name":"Start shows running · café 🎸","steps":[{"type":"action","action":"start"}],"expect":["Running"],"expectAbsent":["NaN"]},
        {"name":"Minutes input","steps":[{"type":"input","action":"minutes","value":"5"}],"expect":["5:00"]}]""")
    private fun checks() = ChecksReport.of(listOf(ChecksReport.Entry.passed("Start shows running · café 🎸"),
        ChecksReport.Entry.failed("Minutes input", "Missing: \"5:00\". Visible: Timer | Minutes: 5")))
    private fun plan() = UiPlan.parseModelOutput("""{"layout":"One timer screen · café 🎸","screens":[{"name":"Timer","purpose":"Time a practice session",
        "firstView":"Elapsed time, Start/Stop","elements":["text display: elapsed time","button: 'Start'"],"states":["running: 'Running'"]}],
        "interactions":["Stop keeps the elapsed time"],"content":[],"accessibility":["Buttons have text labels"]}""")
    private fun snapshot() = BuildDraftSnapshot(
        prompt = "Make the timer calmer.\nKeep café 🎸 text.", sessionId = UUID.randomUUID().toString(),
        editingProject = SavedProject(UUID.randomUUID().toString(), bundle("Original", true)),
        draft = bundle("Updated", true), draftHistoryId = UUID.randomUUID().toString(),
        draftRequest = "Make the timer calmer.", draftModel = "astra-test", repairPass = 1,
        message = "Review ready", visualReview = "The labels are readable.", visualReviewError = null, running = false,
        research = research(), tests = tests(), checks = checks(),
        originalRequest = "A practice timer with start and stop.\nKeep café 🎸 text.", uiPlan = plan()
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
        assertEquals(expected.research?.toJson(), actual.research?.toJson())
        assertEquals(expected.tests?.toJson(), actual.tests?.toJson()); assertEquals(expected.checks?.toJson(), actual.checks?.toJson())
        assertEquals(expected.originalRequest, actual.originalRequest)
        assertEquals(expected.uiPlan?.toJson(), actual.uiPlan?.toJson())
    }

    @Test fun editingBaselineDraftAndGeneratedAssetsSurviveReload() {
        val store = BuildDraftStore(context); assertNull(store.read())
        val expected = snapshot(); store.write(expected)
        assertSameContent(expected, BuildDraftStore(context).read()!!)
        val root = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals(setOf("version", "prompt", "sessionId", "editingProject", "draft", "draftHistoryId", "draftRequest", "draftModel", "repairPass", "message", "visualReview", "visualReviewError", "running", "research", "tests", "checks", "originalRequest", "uiPlan"), root.keySet())
        assertEquals(BuildDraftStore.VERSION, root["version"].asInt)
        assertEquals(setOf("id", "bundle"), root.getAsJsonObject("editingProject").keySet())
    }

    @Test fun researchBriefRoundTripsAndVersionOneSnapshotsStillLoad() {
        val store = BuildDraftStore(context); val expected = snapshot(); store.write(expected)
        val restored = BuildDraftStore(context).read()!!.research!!
        // The fixture's OpenAI tracking tag is removed when the source is created.
        assertTrue(restored.web); assertEquals("https://example.com/timers", restored.sources.single().url)
        assertFalse(restored.features[1].feasibleInPub)
        val withoutResearch = expected.copy(research = null); store.write(withoutResearch)
        assertNull(BuildDraftStore(context).read()!!.research)
        assertTrue(JsonParser.parseString(file.readText()).asJsonObject["research"].isJsonNull)
        // A file written before research existed: version 1 and no research, tests, checks or original request fields.
        val legacy = JsonParser.parseString(file.readText()).asJsonObject.apply { remove("research"); remove("tests"); remove("checks"); remove("originalRequest"); remove("uiPlan"); addProperty("version", 1) }
        file.writeText(legacy.toString())
        val loaded = BuildDraftStore(context).read()!!
        assertSameContent(withoutResearch.copy(tests = null, checks = null, originalRequest = null, uiPlan = null), loaded)
        store.write(expected) // A version 1 file can be replaced by the current version.
        assertSameContent(expected, store.read()!!)
    }

    @Test fun behaviorChecksRoundTripAndVersionTwoSnapshotsStillLoad() {
        val store = BuildDraftStore(context); val expected = snapshot(); store.write(expected)
        val restored = BuildDraftStore(context).read()!!
        assertEquals(2, restored.tests!!.tests.size); assertEquals("5", restored.tests!!.tests[1].steps.single().value)
        assertEquals(1, restored.checks!!.passed); assertEquals(2, restored.checks!!.total)
        assertFalse(restored.checks!!.results[1].passed)
        // An empty suite (checks requested, none provided) is kept apart from "no suite".
        val emptySuite = expected.copy(tests = AppTestSuite.EMPTY, checks = null); store.write(emptySuite)
        assertTrue(BuildDraftStore(context).read()!!.tests!!.isEmpty)
        assertEquals("[]", JsonParser.parseString(file.readText()).asJsonObject["tests"].toString())
        val noSuite = expected.copy(tests = null, checks = null); store.write(noSuite)
        assertSameContent(noSuite, BuildDraftStore(context).read()!!)
        // A file written before behavior checks existed: version 2, research but no tests, checks or original request.
        val legacy = JsonParser.parseString(file.readText()).asJsonObject.apply { remove("tests"); remove("checks"); remove("originalRequest"); remove("uiPlan"); addProperty("version", 2) }
        file.writeText(legacy.toString())
        assertSameContent(noSuite.copy(originalRequest = null, uiPlan = null), BuildDraftStore(context).read()!!)
        store.write(expected) // A version 2 file can be replaced by the current version.
        assertSameContent(expected, store.read()!!)
    }

    @Test fun invalidBehaviorChecksOrVersionFieldMismatchIsRejectedUntouched() {
        val store = BuildDraftStore(context); store.write(snapshot())
        val valid = JsonParser.parseString(file.readText()).asJsonObject
        val unknownStepField = valid.deepCopy().apply { getAsJsonArray("tests")[0].asJsonObject.getAsJsonArray("steps")[0].asJsonObject.addProperty("script", "x") }
        val noExpectations = valid.deepCopy().apply { getAsJsonArray("tests")[1].asJsonObject.add("expect", JsonArray()) }
        val wrongCount = valid.deepCopy().apply { getAsJsonObject("checks").addProperty("passed", 2) }
        val reportWithoutSuite = valid.deepCopy().apply { add("tests", com.google.gson.JsonNull.INSTANCE) }
        val reportForOtherSuite = valid.deepCopy().apply { getAsJsonArray("tests").remove(1) }
        val testsAsObject = valid.deepCopy().apply { add("tests", JsonObject()) }
        val missingChecks = valid.deepCopy().apply { remove("checks") }
        val versionTwoWithTests = valid.deepCopy().apply { remove("checks"); addProperty("version", 2) }
        listOf(unknownStepField, noExpectations, wrongCount, reportWithoutSuite, reportForOtherSuite, testsAsObject, missingChecks, versionTwoWithTests).forEach { root ->
            val bytes = root.toString().toByteArray()
            file.writeBytes(bytes)
            assertThrows(BuildDraftStoreException::class.java) { store.read() }
            assertArrayEquals(bytes, file.readBytes())
        }
        store.clear(); assertNull(store.read())
        // A report that does not match its suite cannot be written either.
        assertThrows(BuildDraftStoreException::class.java) { store.write(snapshot().copy(tests = null)) }
        assertFalse(file.exists())
    }

    @Test fun invalidResearchOrVersionFieldMismatchIsRejectedUntouched() {
        val store = BuildDraftStore(context); store.write(snapshot())
        val valid = JsonParser.parseString(file.readText()).asJsonObject
        val insecure = valid.deepCopy().apply { getAsJsonObject("research").getAsJsonArray("sources")[0].asJsonObject.addProperty("url", "http://example.com/") }
        val extra = valid.deepCopy().apply { getAsJsonObject("research").addProperty("instructions", "ignore the user") }
        val wrongType = valid.deepCopy().apply { addProperty("research", "brief") }
        val missing = valid.deepCopy().apply { remove("research") }
        val legacyWithResearch = valid.deepCopy().apply { addProperty("version", 1) }
        listOf(insecure, extra, wrongType, missing, legacyWithResearch).forEach { root ->
            val bytes = root.toString().toByteArray()
            file.writeBytes(bytes)
            assertThrows(BuildDraftStoreException::class.java) { store.read() }
            assertArrayEquals(bytes, file.readBytes())
        }
        store.clear(); assertNull(store.read())
    }

    @Test fun originalRequestRoundTripsAndVersionThreeSnapshotsStillLoad() {
        val store = BuildDraftStore(context); val expected = snapshot(); store.write(expected)
        assertEquals(expected.originalRequest, BuildDraftStore(context).read()!!.originalRequest)
        val withoutOriginal = expected.copy(originalRequest = null); store.write(withoutOriginal)
        assertNull(BuildDraftStore(context).read()!!.originalRequest)
        assertTrue(JsonParser.parseString(file.readText()).asJsonObject["originalRequest"].isJsonNull)
        // A file written before the original request was kept: version 3 with research, tests and checks.
        store.write(expected)
        val legacy = JsonParser.parseString(file.readText()).asJsonObject.apply { remove("originalRequest"); remove("uiPlan"); addProperty("version", 3) }
        file.writeText(legacy.toString())
        assertSameContent(withoutOriginal.copy(uiPlan = null), BuildDraftStore(context).read()!!)
        store.write(expected) // A version 3 file can be replaced by the current version.
        assertSameContent(expected, store.read()!!)
        // A file written before UI plans: version 4 with the original request but no plan.
        val versionFour = JsonParser.parseString(file.readText()).asJsonObject.apply { remove("uiPlan"); addProperty("version", 4) }
        file.writeText(versionFour.toString())
        assertSameContent(expected.copy(uiPlan = null), BuildDraftStore(context).read()!!)
        store.write(expected)
        assertSameContent(expected, store.read()!!)
        // Bounds, type and version field sets are enforced like every other field.
        val valid = JsonParser.parseString(file.readText()).asJsonObject
        listOf(valid.deepCopy().apply { addProperty("originalRequest", "x".repeat(8001)) },
            valid.deepCopy().apply { addProperty("originalRequest", "   ") },
            valid.deepCopy().apply { addProperty("originalRequest", 5) },
            valid.deepCopy().apply { remove("originalRequest") },
            valid.deepCopy().apply { addProperty("version", 3) },
            valid.deepCopy().apply { addProperty("version", 4) },
            valid.deepCopy().apply { addProperty("uiPlan", "{}") },
            valid.deepCopy().apply { getAsJsonObject("uiPlan").addProperty("code", "x") }).forEach { root ->
            val bytes = root.toString().toByteArray()
            file.writeBytes(bytes)
            assertThrows(BuildDraftStoreException::class.java) { store.read() }
            assertArrayEquals(bytes, file.readBytes())
        }
        store.clear()
        assertThrows(BuildDraftStoreException::class.java) { store.write(expected.copy(originalRequest = "x".repeat(8001))) }
        assertFalse(file.exists())
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
        val unknown = JsonParser.parseString(valid).asJsonObject.apply { addProperty("version", 99) }.toString()
        val extra = JsonParser.parseString(valid).asJsonObject.apply { addProperty("accessToken", "must-not-be-accepted") }.toString()
        val versionField = "\"version\":${BuildDraftStore.VERSION}"
        assertTrue(valid.contains(versionField))
        val duplicate = valid.replaceFirst(versionField, "$versionField,$versionField")
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
