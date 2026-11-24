package app.pub

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.builder.OffscreenPreview
import app.pub.core.ProjectBundle
import app.pub.runtime.RuntimeClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OffscreenPreviewDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun fixture(): ProjectBundle = ProjectBundle.parse(JsonObject().apply {
        addProperty("name", "Offscreen fixture"); addProperty("description", "Private native preview test")
        add("capabilities", JsonArray())
        addProperty("source", "function dispatch(raw){var e=JSON.parse(raw);return JSON.stringify({ui:{type:'column',children:[{type:'text',text:'Private preview'},{type:'graphic',asset:'diagram',description:'Blue rectangle'}]},effects:[]});}")
        add("assets", JsonParser.parseString("""{"diagram":{"type":"vector","width":240,"height":120,"shapes":[{"id":"rectangle","type":"rect","x":10,"y":10,"width":220,"height":100,"fill":"primary"}]}}""").asJsonObject)
    }.toString())
    private fun device() = JsonObject().apply {
        addProperty("accent", "blue")
        addProperty("screenWidthDp", 320); addProperty("screenHeightDp", 480)
        add("layoutContext", JsonObject().apply {
            addProperty("measuredBuilderViewportWidthDp", 320); addProperty("measuredBuilderViewportHeightDp", 480)
            addProperty("density", context.resources.displayMetrics.density)
            addProperty("fontScale", context.resources.configuration.fontScale)
            addProperty("layoutDirection", "ltr")
        })
    }
    private fun assertPng(bytes: ByteArray, diagnostic: String) {
        File(context.cacheDir, "offscreen-$diagnostic.png").writeBytes(bytes)
        assertTrue(bytes.size in 100..4 * 1024 * 1024)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertTrue(bitmap.width in 1..1280 && bitmap.height in 1..1280)
            assertEquals("Requested viewport aspect ratio", 320.0 / 480.0, bitmap.width.toDouble() / bitmap.height, .01)
            // A context from before style guides (accent only) is drawn in that accent's dark style guide.
            val background = app.pub.core.StyleColors.scheme(app.pub.core.StyleGuide.fromLegacyAccent("blue"), true).getValue("background")
            assertEquals("Blue style guide's dark background", background, bitmap.getPixel(0, 0))
            var accentPixels = 0
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                if (bitmap.getPixel(x, y) == 0xFF9EC8FF.toInt()) accentPixels++
            }
            assertTrue("Native vector and requested blue theme must render (accent samples=$accentPixels, size=${bitmap.width}x${bitmap.height}, diagnostic=offscreen-$diagnostic.png)", accentPixels > 100)
        } finally { bitmap.recycle() }
    }

    @Test fun capturesWithApplicationContextAndNoActivity() = runBlocking {
        val bytes = OffscreenPreview.capture(context, fixture(), device())
        assertPng(bytes, "application-context")
        File(context.cacheDir, "offscreen-preview-proof.png").writeBytes(bytes)
    }

    @Test fun capturesWhileHostActivityIsStopped() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            val bytes = runBlocking { OffscreenPreview.capture(context, fixture(), device()) }
            assertPng(bytes, "activity-stopped")
            assertEquals(Lifecycle.State.CREATED, scenario.state)
        }
    }

    @Test fun uniqueRuntimeProcessesRemainIndependentDuringOffscreenCapture() = runBlocking {
        withTimeout(30_000) {
            withContext(Dispatchers.Main.immediate) {
                val readyA = CompletableDeferred<Int>(); val readyB = CompletableDeferred<Int>()
                val packetsA = Channel<String>(Channel.UNLIMITED); val packetsB = Channel<String>(Channel.UNLIMITED)
                lateinit var first: RuntimeClient; lateinit var second: RuntimeClient
                fun init() = JsonObject().apply { addProperty("type", "init") }
                first = RuntimeClient(context, onReady = { readyA.complete(it); first.send(init()) },
                    onPacket = { packetsA.trySend(it.ui["text"].asString) },
                    onError = { readyA.completeExceptionally(IllegalStateException(it)); packetsA.close(IllegalStateException(it)) })
                second = RuntimeClient(context, onReady = { readyB.complete(it); second.send(init()) },
                    onPacket = { packetsB.trySend(it.ui["text"].asString) },
                    onError = { readyB.completeExceptionally(IllegalStateException(it)); packetsB.close(IllegalStateException(it)) })
                try {
                    first.start("var n=1;function dispatch(raw){if(JSON.parse(raw).type==='action')n++;return JSON.stringify({ui:{type:'text',text:'A'+n},effects:[]});}")
                    second.start("var n=10;function dispatch(raw){if(JSON.parse(raw).type==='action')n++;return JSON.stringify({ui:{type:'text',text:'B'+n},effects:[]});}")
                    val uidA = readyA.await(); val uidB = readyB.await()
                    assertNotEquals(Process.myUid(), uidA); assertNotEquals(Process.myUid(), uidB); assertNotEquals(uidA, uidB)
                    assertEquals("A1", packetsA.receive()); assertEquals("B10", packetsB.receive())
                    first.close()
                    assertPng(OffscreenPreview.capture(context, fixture(), device()), "concurrent-runtimes")
                    second.send(JsonObject().apply { addProperty("type", "action"); addProperty("action", "increment") })
                    assertEquals("Closing one runtime and rendering another must not reset or kill the active app", "B11", packetsB.receive())
                } finally { first.close(); second.close(); packetsA.close(); packetsB.close() }
            }
        }
    }

    @Test fun cancellationReleasesPrivateDisplay() = runBlocking {
        val displays = context.getSystemService(DisplayManager::class.java)
        fun count() = displays.displays.count { it.name == "PUB private build preview" }
        val baseline = count()
        val pending = launch { OffscreenPreview.capture(context, fixture(), device()) }
        delay(100)
        pending.cancelAndJoin()
        withTimeout(5_000) { while (count() != baseline) delay(50) }
        assertEquals(baseline, count())
    }

    /** Root runs this explicitly with the already-connected phone asleep; this test never wakes it. */
    @Test fun capturesWithPhysicalScreenOffWhenExplicitlyRequested() = runBlocking {
        assumeTrue("Explicit screen-off device check", InstrumentationRegistry.getArguments().getString("pubScreenOffPreviewTest") == "true")
        val power = context.getSystemService(PowerManager::class.java)
        assumeTrue("Phone must already be asleep", !power.isInteractive)
        val bytes = OffscreenPreview.capture(context, fixture(), device())
        assertPng(bytes, "screen-off")
        assertFalse("Private rendering must not wake the physical screen", power.isInteractive)
        File(context.cacheDir, "offscreen-screenoff-proof.png").writeBytes(bytes)
    }
}
