package app.pub

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.core.AppSpec
import app.pub.core.AppTestSuite
import app.pub.core.ResearchBrief
import app.pub.projects.AppSpecStore
import app.pub.projects.AppSpecStoreException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppSpecStoreTest {
    private lateinit var directory: File
    private lateinit var context: ContextWrapper
    @Before fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.cacheDir, "spec-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
    }
    @After fun cleanup() { check(directory.deleteRecursively()) }

    private fun spec() = AppSpec.of("A tip calculator · café 🎸",
        ResearchBrief.parseModelOutput("""{"summary":"Split a bill","category":"Calculator","features":[{"title":"Bill input","priority":"must","feasibleInPub":true,"notes":""}]}""")
            .withSources(listOf(ResearchBrief.Source.of("Guide", "https://example.com/tips")), true),
        AppTestSuite.parse("""[{"name":"Tip","steps":[{"type":"input","action":"bill","value":"100"}],"expect":["Total: 115.00"]}]"""),
        1_731_600_000_000L)

    @Test fun specsRoundTripPerAppAndAreDeletedWithoutLeftovers() {
        val store = AppSpecStore(context)
        val app = UUID.randomUUID().toString()
        assertNull(store.read(app)); assertNull(store.read("guitar-tuner"))
        store.write(app, spec())
        assertEquals(spec().toJson(), AppSpecStore(context).read(app)!!.toJson())
        assertEquals(setOf("$app.json"), File(directory, "app-specs").list()!!.toSet())
        assertNull(store.read("guitar-tuner")) // One file per app; another app's spec is never returned.
        val updated = spec().withBuild(null, AppTestSuite.EMPTY, 1_731_700_000_000L)
        store.write(app, updated)
        assertEquals(updated.toJson(), store.read(app)!!.toJson())
        store.write("guitar-tuner", spec())
        store.delete(app); store.delete(app) // Idempotent.
        assertNull(store.read(app))
        assertEquals(setOf("guitar-tuner.json"), File(directory, "app-specs").list()!!.toSet())
    }

    @Test fun onlyHostAssignedIdsBecomePaths() {
        val store = AppSpecStore(context)
        for (id in listOf("../library/x", "guitar-tuner/../x", "", "GUITAR-TUNER", UUID.randomUUID().toString().uppercase())) {
            assertThrows(IllegalArgumentException::class.java) { store.write(id, spec()) }
            assertThrows(IllegalArgumentException::class.java) { store.read(id) }
            assertThrows(IllegalArgumentException::class.java) { store.delete(id) }
        }
        assertFalse(File(directory, "library").exists())
    }

    @Test fun corruptOrUnknownSpecsFailClosedAndStayUntouchedUntilReplaced() {
        val store = AppSpecStore(context)
        val app = UUID.randomUUID().toString()
        store.write(app, spec())
        val file = File(directory, "app-specs/$app.json")
        val valid = file.readText()
        val invalid = listOf("{broken".toByteArray(), valid.replace("\"version\":1", "\"version\":2").toByteArray(),
            valid.replaceFirst("{", "{\"photos\":[],").toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))
        invalid.forEach { bytes ->
            file.writeBytes(bytes)
            val error = assertThrows(AppSpecStoreException::class.java) { store.read(app) }
            assertFalse(error.message!!.contains("café"))
            assertArrayEquals(bytes, file.readBytes())
        }
        // The next saved build supersedes an unreadable spec.
        store.write(app, spec())
        assertEquals(spec().toJson(), store.read(app)!!.toJson())
    }
}
