package app.pub

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.core.HardwareContract
import app.pub.core.ProjectBundle
import app.pub.hardware.ProjectMedia
import app.pub.projects.AppSpecStore
import app.pub.projects.ProjectStore
import app.pub.projects.SavedProject
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.fail
import org.junit.rules.ExternalResource

/**
 * A microphone tuner app for device tests. PUB no longer ships a tuner, so its code lives in the test APK's assets
 * (fixtures/tuner, also checked by core's TunerProjectTest). It is saved as an ordinary app with a fresh UUID and
 * removed with all its data afterwards; it never touches the user's own apps, including one saved under the legacy
 * tuner ID.
 */
object TunerFixture {
    const val NAME = "Guitar Tuner"
    private val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun bundle(): ProjectBundle {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("fixtures/tuner/$name").bufferedReader().use { it.readText() }
        return ProjectBundle.parse(JsonObject().apply {
            addProperty("name", NAME); addProperty("description", "Device test fixture: offline guitar tuner")
            addProperty("source", read("main.js"))
            add("assets", JsonParser.parseString(read("graphics.json")).asJsonObject)
            addProperty("icon", "guitar")
            add("capabilities", JsonArray().apply { add("microphone.pitch"); add("storage.project") })
        }.toString())
    }

    fun install(): SavedProject = ProjectStore(target).save(bundle())

    /** Removes the fixture's package, spec, photos, saved data and access rules. */
    fun remove(id: String) {
        require(id != ProjectStore.LEGACY_TUNER_ID) { "Fixtures never use the legacy ID" }
        val context = target
        ProjectStore(context).delete(id)
        AppSpecStore(context).delete(id)
        ProjectMedia(context).delete(id)
        context.deleteSharedPreferences("project.$id")
        context.getSharedPreferences("permissions", Context.MODE_PRIVATE).edit().apply {
            remove("$id.microphone"); HardwareContract.OPERATIONS.keys.forEach { remove("$id.$it") }
        }.commit()
    }

    /** Waits (off the main thread) until the host's background library load lists [id], so it can be opened. */
    fun awaitListed(host: HostModel, id: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var listed = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { listed = host.projects.any { it.id == id } }
            if (listed) return
            Thread.sleep(50)
        }
        fail("The library never listed the fixture app")
    }
}

/**
 * Installs [TunerFixture] before the test (give it a lower rule order than an Activity rule, so the launcher's first
 * library load already lists it) and removes it afterwards.
 */
class TunerFixtureRule : ExternalResource() {
    lateinit var project: SavedProject
        private set
    val id: String get() = project.id
    override fun before() { project = TunerFixture.install() }
    override fun after() { if (::project.isInitialized) TunerFixture.remove(project.id) }
}
