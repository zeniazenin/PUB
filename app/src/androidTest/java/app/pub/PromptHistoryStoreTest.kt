package app.pub

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.builder.PromptHistoryStore
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PromptHistoryStoreTest {
    private lateinit var directory: File
    private lateinit var context: ContextWrapper
    @Before fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.cacheDir, "history-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
    }
    @After fun cleanup() { check(directory.deleteRecursively()) }

    @Test fun fullPromptOutcomesAndAppLinkSurviveReloadWithoutOtherPayloads() {
        val store = PromptHistoryStore(context)
        val prompt = "Build a quiet timer.\nKeep my chosen wording: café 🎸.\n" + "x".repeat(7900)
        val created = store.begin(prompt, "astra-test-model")
        assertEquals("building", store.list().single().status)
        assertTrue(store.update(created.id, "ready", "Quiet timer"))
        val appId = UUID.randomUUID().toString()
        assertTrue(store.markSaved(created.id, appId, "Quiet timer"))
        val loaded = PromptHistoryStore(context).list(appId).single()
        assertEquals(prompt, loaded.prompt); assertEquals(created.createdAtMillis, loaded.createdAtMillis)
        assertEquals("saved", loaded.status); assertEquals("create", loaded.kind); assertEquals(appId, loaded.projectId)
        val edit = store.begin("Add a pause button", "astra-test-model", appId, "Quiet timer")
        store.update(edit.id, "failed")
        assertEquals(listOf("edit", "create"), store.list(appId).map { it.kind })
        val encoded = JsonParser.parseString(File(directory, "prompt-history.json").readText()).asJsonObject
        assertEquals(setOf("version", "requests"), encoded.keySet())
        assertEquals(setOf("id", "createdAtMillis", "prompt", "model", "kind", "status", "projectId", "title", "interrupted"), encoded.getAsJsonArray("requests")[0].asJsonObject.keySet())
        assertFalse(store.update(created.id, "cancelled")) // A saved result cannot be regressed by stale cleanup.
    }

    @Test fun interruptedRecoveryChangesOnlyPendingRequestsAndNeverRetries() {
        val store = PromptHistoryStore(context)
        val ready = store.begin("Ready instruction", "model"); store.update(ready.id, "ready", "Ready app")
        val pending = store.begin("Pending instruction", "model")
        val recovered = PromptHistoryStore(context).recoverInterrupted()
        assertEquals("cancelled", recovered.first { it.id == pending.id }.status)
        assertTrue(recovered.first { it.id == pending.id }.interrupted)
        assertEquals("ready", recovered.first { it.id == ready.id }.status)
        assertEquals(recovered, PromptHistoryStore(context).recoverInterrupted())
    }

    @Test fun latestTwoHundredAreKeptAndIndependentInstancesDoNotOverwriteEachOther() {
        val first = PromptHistoryStore(context); val second = PromptHistoryStore(context)
        val session = UUID.randomUUID().toString()
        val otherApp = UUID.randomUUID().toString()
        val other = first.begin("Another app's instruction", "model", otherApp, "Other app")
        repeat(203) { index -> (if (index % 2 == 0) first else second).begin("Instruction $index", "model", sessionId = session) }
        val listed = first.list(sessionId = session)
        assertEquals(PromptHistoryStore.MAX_ENTRIES, listed.size)
        assertEquals("Instruction 202", listed.first().prompt)
        assertEquals("Instruction 3", listed.last().prompt)
        assertEquals(200, listed.map { it.id }.distinct().size)
        assertEquals(other, first.list(otherApp).single())
        assertEquals(201, first.list().size)
    }

    @Test fun savingLinksEveryOutcomeFromOnlyThisConstructionSession() {
        val store = PromptHistoryStore(context)
        val session = UUID.randomUUID().toString(); val otherSession = UUID.randomUUID().toString()
        val failed = store.begin("First attempt", "model", sessionId = session); store.update(failed.id, "failed")
        val cancelled = store.begin("Second attempt", "model", sessionId = session); store.update(cancelled.id, "cancelled")
        val ready = store.begin("Earlier preview", "model", sessionId = session); store.update(ready.id, "ready")
        val unrelated = store.begin("Different app", "model", sessionId = otherSession)
        val chosen = store.begin("Final preview", "model", sessionId = session); store.update(chosen.id, "ready")
        val review = "The contrast is good. Increase the caption size."
        assertTrue(store.recordReview(chosen.id, review))
        val appId = UUID.randomUUID().toString()
        assertTrue(store.markSaved(chosen.id, appId, "Saved app"))
        val reloaded = PromptHistoryStore(context)
        val lineage = reloaded.list(appId)
        assertEquals(listOf(chosen.id, ready.id, cancelled.id, failed.id), lineage.map { it.id })
        assertEquals(listOf("saved", "ready", "cancelled", "failed"), lineage.map { it.status })
        assertTrue(lineage.all { it.sessionId == session && it.projectId == appId && it.title == "Saved app" })
        assertEquals(review, lineage.first().review)
        assertEquals(lineage, reloaded.list(sessionId = session))
        assertEquals(unrelated, reloaded.list(sessionId = otherSession).single())
        val edit = reloaded.begin("Later edit", "model", appId, "Saved app", UUID.randomUUID().toString())
        assertEquals(5, reloaded.list(appId, session).size) // App scope includes edits from later sessions.
        assertEquals(edit.id, reloaded.list(appId).first().id)
    }

    @Test fun legacyRecordsRemainReadableAndAreNeverGuessedIntoNewLineage() {
        val store = PromptHistoryStore(context)
        val legacy = store.begin("Legacy ready", "model"); store.update(legacy.id, "ready")
        val legacyFailure = store.begin("Legacy failure", "model"); store.update(legacyFailure.id, "failed")
        val file = File(directory, "prompt-history.json")
        val encoded = JsonParser.parseString(file.readText()).asJsonObject
        assertTrue(encoded.getAsJsonArray("requests").all { !it.asJsonObject.has("sessionId") && !it.asJsonObject.has("review") })
        val appId = UUID.randomUUID().toString()
        PromptHistoryStore(context).markSaved(legacy.id, appId, "Legacy app")
        assertEquals(listOf(legacy.id), store.list(appId).map { it.id })
        assertNull(store.list().first { it.id == legacyFailure.id }.projectId)
        val session = UUID.randomUUID().toString()
        store.begin("New lineage", "model", sessionId = session)
        assertEquals(listOf("New lineage"), store.list(sessionId = session).map { it.prompt })
        assertEquals(3, store.list().size)
    }

    @Test fun sessionsCannotBeReassignedAndReviewsAreBoundedToTheirExactRequest() {
        val store = PromptHistoryStore(context)
        val session = UUID.randomUUID().toString()
        val first = store.begin("First", "model", sessionId = session); store.update(first.id, "ready")
        val second = store.begin("Second", "model", sessionId = session); store.update(second.id, "ready")
        val appId = UUID.randomUUID().toString(); store.markSaved(second.id, appId, "One app")
        val review = "r".repeat(PromptHistoryStore.MAX_REVIEW_CHARS)
        assertTrue(store.recordReview(first.id, review))
        assertNull(store.list(appId).first { it.id == second.id }.review)
        assertEquals(review, PromptHistoryStore(context).list(appId).first { it.id == first.id }.review)
        val file = File(directory, "prompt-history.json"); val bytes = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.recordReview(first.id, "x".repeat(6001)) }
        assertThrows(IllegalArgumentException::class.java) { store.begin("Cross app", "model", UUID.randomUUID().toString(), sessionId = session) }
        assertThrows(IllegalArgumentException::class.java) { store.markSaved(first.id, UUID.randomUUID().toString(), "Other app") }
        assertThrows(IllegalArgumentException::class.java) { store.begin("Invalid session", "model", sessionId = "../../other") }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun researchAndCheckSummariesAreOptionalBoundedAndSurviveSaving() {
        val store = PromptHistoryStore(context)
        val session = UUID.randomUUID().toString()
        val build = store.begin("Tip calculator", "model", sessionId = session); store.update(build.id, "ready", "Tips")
        val repair = store.begin("Automatic behavior repair 1 of 2.", "model", sessionId = session, automatic = true); store.update(repair.id, "ready", "Tips")
        val research = "Researched on the web · tip calculator · 6 sources\nA tip calculator for splitting a bill."
        assertTrue(store.recordResearch(build.id, research))
        assertTrue(store.recordChecks(build.id, "5 of 6 behavior checks passed\nFailed: Empty bill"))
        assertTrue(store.recordChecks(repair.id, "6 of 6 behavior checks passed"))
        assertTrue(store.recordChecks(build.id, "4 of 6 behavior checks passed")) // A later run replaces the earlier one.
        assertFalse(store.recordResearch(UUID.randomUUID().toString(), research))
        val appId = UUID.randomUUID().toString()
        assertTrue(store.markSaved(repair.id, appId, "Tips"))
        val loaded = PromptHistoryStore(context).list(appId)
        assertEquals(research, loaded.first { it.id == build.id }.research)
        assertEquals("4 of 6 behavior checks passed", loaded.first { it.id == build.id }.checks)
        assertNull(loaded.first { it.id == repair.id }.research)
        assertEquals("6 of 6 behavior checks passed", loaded.first { it.id == repair.id }.checks)
        val file = File(directory, "prompt-history.json")
        val requests = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("requests")
        assertTrue(requests.first { it.asJsonObject["id"].asString == build.id }.asJsonObject.keySet().containsAll(setOf("research", "checks")))
        // Bounds are checked before anything is written.
        val bytes = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.recordResearch(build.id, "r".repeat(PromptHistoryStore.MAX_DETAIL_CHARS + 1)) }
        assertThrows(IllegalArgumentException::class.java) { store.recordChecks(build.id, "  ") }
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(store.recordChecks(build.id, "c".repeat(PromptHistoryStore.MAX_DETAIL_CHARS)))
        // Stored values are validated like every other field, and unknown fields are still rejected.
        val valid = JsonParser.parseString(file.readText()).asJsonObject
        listOf<(com.google.gson.JsonObject) -> Unit>(
            { it.addProperty("research", "x".repeat(PromptHistoryStore.MAX_DETAIL_CHARS + 1)) },
            { it.addProperty("checks", " ") },
            { it.addProperty("checks", 5) },
            { it.addProperty("researchSources", "https://example.com") }
        ).forEach { change ->
            val root = valid.deepCopy(); change(root.getAsJsonArray("requests")[0].asJsonObject)
            file.writeText(root.toString())
            assertThrows(Exception::class.java) { PromptHistoryStore(context).list() }
        }
    }

    @Test fun invalidRequestsAndUnreadableHistoryNeverOverwriteExistingRecords() {
        val store = PromptHistoryStore(context)
        val entry = store.begin("Keep this instruction", "model")
        val file = File(directory, "prompt-history.json")
        val original = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.begin("x".repeat(8001), "model") }
        assertThrows(IllegalArgumentException::class.java) { store.begin("Edit", "model", "../../elsewhere") }
        assertThrows(IllegalArgumentException::class.java) { store.markSaved(entry.id, UUID.randomUUID().toString(), "Not ready") }
        assertArrayEquals(original, file.readBytes())
        val corrupt = "{broken existing history".toByteArray()
        file.writeBytes(corrupt)
        assertThrows(Exception::class.java) { store.begin("Must not replace history", "model") }
        assertArrayEquals(corrupt, file.readBytes())
        assertThrows(Exception::class.java) { store.recoverInterrupted() }
        assertArrayEquals(corrupt, file.readBytes())
    }
}
