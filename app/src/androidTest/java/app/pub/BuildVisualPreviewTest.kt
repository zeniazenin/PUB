package app.pub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pub.core.ProjectBundle
import app.pub.ui.BuildVisualPreview
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BuildVisualPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun bundle(source: String, assets: JsonObject = JsonObject()): ProjectBundle = ProjectBundle.parse(JsonObject().apply {
        addProperty("name", "Preview proof"); addProperty("description", "Controlled visual review fixture")
        addProperty("source", source)
        add("capabilities", JsonArray().apply { add("microphone.pitch"); add("storage.project"); add("camera") })
        add("assets", assets)
    }.toString())

    private data class Capture(val bytes: AtomicReference<ByteArray?> = AtomicReference(null), val failure: AtomicReference<String?> = AtomicReference(null), val callbacks: AtomicInteger = AtomicInteger())
    private fun show(bundle: ProjectBundle): Capture {
        val result = Capture()
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)[HostModel::class.java].closeProject()
            compose.activity.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize()) {
                        // A host-only bar makes leaking the surrounding app into a screenshot visible.
                        Text("PRIVATE HOST HEADER", Modifier.fillMaxWidth().height(40.dp).background(Color.Red))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            BuildVisualPreview(bundle, JsonObject().apply { addProperty("reviewMarker", "hardware-only-fixture") },
                                onCaptured = { result.bytes.set(it); result.callbacks.incrementAndGet() },
                                onFailure = { result.failure.set(it); result.callbacks.incrementAndGet() })
                        }
                    }
                }
            }
        }
        return result
    }
    private fun awaitCapture(result: Capture): ByteArray {
        compose.waitUntil(15_000) { result.callbacks.get() > 0 }
        assertNull(result.failure.get())
        assertEquals("Exactly one terminal callback", 1, result.callbacks.get())
        val bytes = checkNotNull(result.bytes.get())
        assertTrue(bytes.size in 100..4 * 1024 * 1024)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertTrue(bitmap.width in 1..1280 && bitmap.height in 1..1280)
        assertNotEquals("The host header must not be captured", android.graphics.Color.RED, bitmap.getPixel(bitmap.width / 2, 0))
        bitmap.recycle()
        return bytes
    }

    @Test fun vectorViewportCapturesCleanPngAndSavesVisionFixture() {
        val asset = JsonParser.parseString("""{"type":"vector","width":400,"height":180,"shapes":[
            {"id":"body","type":"ellipse","cx":280,"cy":90,"rx":90,"ry":75,"fill":"#B67C42","stroke":"outline","strokeWidth":3},
            {"id":"neck","type":"rect","x":40,"y":64,"width":240,"height":52,"fill":"#553B28"},
            {"id":"hole","type":"ellipse","cx":280,"cy":90,"rx":24,"ry":24,"fill":"#161616"},
            {"id":"string0","type":"line","x1":40,"y1":70,"x2":330,"y2":70,"stroke":"outline","strokeWidth":2},
            {"id":"string1","type":"line","x1":40,"y1":78,"x2":330,"y2":78,"stroke":"outline"},
            {"id":"string2","type":"line","x1":40,"y1":86,"x2":330,"y2":86,"stroke":"outline"},
            {"id":"string3","type":"line","x1":40,"y1":94,"x2":330,"y2":94,"stroke":"outline"},
            {"id":"string4","type":"line","x1":40,"y1":102,"x2":330,"y2":102,"stroke":"outline"},
            {"id":"string5","type":"line","x1":40,"y1":110,"x2":330,"y2":110,"stroke":"outline"}
        ]}""").asJsonObject
        val source = """function dispatch(raw){return JSON.stringify({ui:{type:'column',children:[
            {type:'text',text:'Guitar overview',style:'display'},
            {type:'graphic',asset:'guitar',description:'Guitar strings, low E selected',highlight:['string0']},
            {type:'text',text:'Selected string: Low E'},
            {type:'button',label:'Start tuning',action:'start'}]},effects:[]});}"""
        val project = bundle(source, JsonObject().apply { add("guitar", asset) })
        val bytes = awaitCapture(show(project))
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        var brownPixels = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
            val color = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(color) > 140 && android.graphics.Color.green(color) in 70..150 && android.graphics.Color.blue(color) < 100) brownPixels++
        }
        assertTrue("The rendered guitar must be present in the screenshot", brownPixels > 100)
        bitmap.recycle()
        File(compose.activity.cacheDir, "build-visual-proof.png").writeBytes(bytes)
        File(compose.activity.cacheDir, "build-visual-proof.json").writeText(project.toJson())
        File(compose.activity.cacheDir, "build-visual-proof-prompt.txt").writeText("Build a guitar tuner with a clear guitar diagram, highlight the selected string, and a Start tuning button.")
    }

    @Test fun initializationEffectsAreIgnoredAndPrivateStateIsNotProvided() {
        val preferences = compose.activity.getSharedPreferences("visual.preview.private", Context.MODE_PRIVATE)
        preferences.edit().putString("data", "PRIVATE_SENTINEL").commit()
        try {
            val source = """var count=0;function dispatch(raw){count++;var e=JSON.parse(raw);
                var safe=e.type==='init'&&Object.keys(e.storage).length===0&&Object.keys(e.settings).length===0&&e.device.reviewMarker==='hardware-only-fixture';
                return JSON.stringify({ui:{type:'column',children:[{type:'text',text:safe?'EMPTY PRIVATE STATE':'PRIVATE STATE LEAK'},
                {type:'text',text:'Dispatch count '+count},{type:'button',label:'Do not execute',action:'unsafe'}]},effects:[
                {type:'microphone.start'},{type:'storage.save',data:{sentinel:'OVERWRITTEN'}},
                {type:'hardware.request',id:'camera-init',capability:'camera',operation:'capture',args:{}}]});}"""
            val capture = show(bundle(source))
            awaitCapture(capture)
            compose.onNodeWithText("EMPTY PRIVATE STATE").assertIsDisplayed()
            compose.onNodeWithText("Do not execute").performClick()
            compose.onNodeWithText("Dispatch count 1").assertIsDisplayed()
            assertEquals("PRIVATE_SENTINEL", preferences.getString("data", null))
            compose.runOnIdle {
                val host = ViewModelProvider(compose.activity)[HostModel::class.java]
                assertFalse(host.listening); assertFalse(host.projectOpen)
                assertFalse(host.askPermission); assertFalse(host.askAndroidPermission)
                assertEquals(1, capture.callbacks.get())
            }
        } finally { preferences.edit().clear().commit() }
    }

    @Test fun rasterIsDecodedBeforeCaptureAndMissingRasterFailsOnce() {
        // Image preserves its intrinsic pixel size; use enough pixels to survive viewport downsampling.
        val red = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(230, 20, 40)) }
        val encoded = ByteArrayOutputStream().also { red.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        red.recycle()
        val asset = JsonObject().apply { addProperty("type", "image"); addProperty("prompt", "A plain red square"); addProperty("data", Base64.encodeToString(encoded, Base64.NO_WRAP)) }
        val source = "function dispatch(raw){return JSON.stringify({ui:{type:'graphic',asset:'sample',description:'Red square'},effects:[]});}"
        val bytes = awaitCapture(show(bundle(source, JsonObject().apply { add("sample", asset) })))
        File(compose.activity.cacheDir, "build-visual-raster-proof.png").writeBytes(bytes)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        var redPixels = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
            val color = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(color) > 200 && android.graphics.Color.green(color) < 50 && android.graphics.Color.blue(color) < 70) redPixels++
        }
        assertTrue("Capture must wait for decoded image pixels (sampled red pixels=$redPixels, PNG=${bitmap.width}x${bitmap.height})", redPixels > 100)
        bitmap.recycle()
        asset.remove("data")
        val failure = show(bundle(source, JsonObject().apply { add("sample", asset) }))
        compose.waitUntil(15_000) { failure.callbacks.get() > 0 }
        assertNull(failure.bytes.get()); assertNotNull(failure.failure.get()); assertEquals(1, failure.callbacks.get())
    }
}
