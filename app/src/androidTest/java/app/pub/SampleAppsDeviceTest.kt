package app.pub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.core.ProjectBundle
import app.pub.projects.ProjectStore
import app.pub.projects.SampleApps
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * First-launch seeding of the sample Tip Calculator, in a fresh sandbox library and private preferences (never the
 * installed app's own library or flag), plus the legacy tuner ID's storage rules.
 */
@RunWith(AndroidJUnit4::class)
class SampleAppsDeviceTest {
    private val base: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var namespace: String
    private lateinit var context: ContextWrapper
    @Before fun setup() {
        directory = File(base.cacheDir, "samples-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        namespace = "samples-test-${UUID.randomUUID()}"
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("$namespace.$name", mode)
        }
    }
    @After fun cleanup() {
        base.deleteSharedPreferences("$namespace.host")
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile); check(directory.deleteRecursively())
    }

    private val seededFlag get() = context.getSharedPreferences("host", Context.MODE_PRIVATE).getBoolean(SampleApps.SEEDED_KEY, false)
    private fun bundle(name: String) = ProjectBundle.parse(JsonObject().apply {
        addProperty("name", name); addProperty("description", "Test fixture")
        addProperty("source", "function dispatch(e){return JSON.stringify({ui:{type:'text',text:'$name'},effects:[]});}")
        add("capabilities", JsonArray())
    }.toString())

    @Test fun decisionSeedsOnlyANeverSeededEmptyLibrary() {
        assertTrue(SampleApps.shouldSeed(alreadySeeded = false, libraryEmpty = true))
        assertFalse(SampleApps.shouldSeed(alreadySeeded = false, libraryEmpty = false))
        assertFalse(SampleApps.shouldSeed(alreadySeeded = true, libraryEmpty = true))
        assertFalse(SampleApps.shouldSeed(alreadySeeded = true, libraryEmpty = false))
    }

    @Test fun freshInstallSeedsTheTipCalculatorOnceAsAnOrdinaryApp() {
        val store = ProjectStore(context)
        assertFalse(seededFlag)
        val sample = checkNotNull(SampleApps.seedIfNeeded(context, store))
        assertEquals(sample.id, UUID.fromString(sample.id).toString())
        assertEquals("Tip Calculator", sample.bundle.name)
        val packaged = base.assets.open(SampleApps.ASSET).bufferedReader().use { ProjectBundle.parse(it.readText()) }
        assertEquals(packaged.toJson(), store.read(sample.id)!!.bundle.toJson())
        assertEquals(listOf(sample.id), store.list().map { it.id })
        assertTrue(seededFlag)

        // Later launches never add another copy, and a deleted sample never returns.
        assertNull(SampleApps.seedIfNeeded(context, ProjectStore(context)))
        assertEquals(1, store.list().size)
        store.delete(sample.id)
        assertNull(SampleApps.seedIfNeeded(context, ProjectStore(context)))
        assertTrue(store.list().isEmpty())
    }

    @Test fun existingAppsNeverGetTheSampleEvenAfterTheyAreDeleted() {
        val store = ProjectStore(context)
        val existing = store.save(bundle("My app"))
        assertNull(SampleApps.seedIfNeeded(context, store))
        assertEquals(listOf(existing.id), store.list().map { it.id })
        assertTrue("The decision is recorded although nothing was seeded", seededFlag)
        store.delete(existing.id)
        assertNull(SampleApps.seedIfNeeded(context, store))
        assertTrue(store.list().isEmpty())
    }

    @Test fun legacyTunerAndUnreadablePackagesCountAsExistingApps() {
        val library = File(directory, "library").apply { check(mkdirs()) }
        File(library, "${ProjectStore.LEGACY_TUNER_ID}.json").writeText(bundle("String Tuner").toJson())
        File(library, "${UUID.randomUUID()}.json").writeText("{not a package")
        val store = ProjectStore(context)
        assertNull(SampleApps.seedIfNeeded(context, store))
        assertTrue(seededFlag)
        assertEquals(listOf(ProjectStore.LEGACY_TUNER_ID), store.list().map { it.id })
    }

    @Test fun failureLeavesTheFlagUnsetAndTheNextLaunchRetries() {
        // A library path that cannot hold packages makes the save fail; seeding must not throw.
        val blocked = File(directory, "library").apply { writeText("not a directory") }
        assertNull(SampleApps.seedIfNeeded(context, ProjectStore(context)))
        assertFalse(seededFlag)
        check(blocked.delete())
        val store = ProjectStore(context)
        val sample = checkNotNull(SampleApps.seedIfNeeded(context, store))
        assertEquals(listOf(sample.id), store.list().map { it.id })
        assertTrue(seededFlag)
    }

    @Test fun legacyTunerIdIsValidOnlyForAnExistingPackage() {
        val store = ProjectStore(context)
        assertTrue(ProjectStore.isAppId(ProjectStore.LEGACY_TUNER_ID))
        assertFalse(ProjectStore.isAppId("GUITAR-TUNER")); assertFalse(ProjectStore.isAppId("../library/x"))
        assertNull(store.read(ProjectStore.LEGACY_TUNER_ID))
        assertThrows("Updating never creates the legacy app", IllegalStateException::class.java) { store.update(ProjectStore.LEGACY_TUNER_ID, bundle("Tuner")) }
        assertNull(store.read(ProjectStore.LEGACY_TUNER_ID))
        assertNotEquals(ProjectStore.LEGACY_TUNER_ID, store.save(bundle("New app")).id)

        // An app saved from the former built-in tuner keeps working: listed, readable and updatable in place.
        File(directory, "library/${ProjectStore.LEGACY_TUNER_ID}.json").writeText(bundle("String Tuner").toJson())
        assertTrue(store.list().any { it.id == ProjectStore.LEGACY_TUNER_ID && it.bundle.name == "String Tuner" })
        store.update(ProjectStore.LEGACY_TUNER_ID, bundle("String Tuner 2"))
        assertEquals("String Tuner 2", store.read(ProjectStore.LEGACY_TUNER_ID)!!.bundle.name)
        store.delete(ProjectStore.LEGACY_TUNER_ID)
        assertNull(store.read(ProjectStore.LEGACY_TUNER_ID))
    }
}
