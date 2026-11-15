package app.pub.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pub.core.ProjectBundle
import app.pub.core.UiPacket
import app.pub.runtime.RuntimeClient
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/** NativeGraphic reports completion only while inside a review preview, never to the script. */
internal val LocalBuildGraphicStatus = staticCompositionLocalOf<((JsonObject, Boolean) -> Unit)?> { null }

/** A fresh, effects-disabled app surface. Its layer contains no builder controls or saved project data. */
@Composable
fun BuildVisualPreview(
    bundle: ProjectBundle,
    device: JsonObject,
    onCaptured: (ByteArray) -> Unit,
    onFailure: (String) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val captured by rememberUpdatedState(onCaptured)
    val failed by rememberUpdatedState(onFailure)
    val session = remember(bundle) { VisualPreviewSession(context, bundle, device.deepCopy(), { captured(it) }, { failed(it) }) }
    val rasterStatus = remember(session) { { asset: JsonObject, success: Boolean -> session.rasterFinished(asset, success) } }
    val layer = rememberGraphicsLayer()
    val compositionReady = remember(session) { CompletableDeferred<Unit>() }
    val drawReady = remember(session) { CompletableDeferred<Unit>() }
    val drawCompletionPosted = remember(session) { AtomicBoolean(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var captureDrawRequested by remember(session) { mutableStateOf(false) }
    val contentReady = session.readyToCapture
    SideEffect { if (contentReady) compositionReady.complete(Unit) }
    DisposableEffect(session) {
        session.start()
        onDispose { session.close() }
    }
    LaunchedEffect(session) {
        snapshotFlow { session.finished || session.readyToCapture }.first { it }
        if (session.finished) return@LaunchedEffect
        try {
            // Frame-clock progress alone is not proof of an Android draw, especially on a private
            // virtual display. First wait for ready content to be applied, then explicitly request
            // and acknowledge a new recording of the actual viewport layer.
            compositionReady.await()
            repeat(2) { withFrameNanos { } }
            captureDrawRequested = true
            drawReady.await()
            if (session.finished) return@LaunchedEffect
            val bitmap = layer.toImageBitmap().asAndroidBitmap()
            val bytes = withContext(Dispatchers.IO) { boundedPreviewPng(bitmap) }
            session.succeed(bytes)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { session.fail("The app preview image could not be captured.") }
    }
    val surface = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().testTag("build-visual-preview")
        .pointerInput(session) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
        .clipToBounds()
        .drawWithContent {
            val requested = captureDrawRequested // Observed here so the request invalidates drawing.
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            if (requested && size.width > 0 && size.height > 0 && drawCompletionPosted.compareAndSet(false, true)) {
                // Resume capture after the complete Android draw traversal, not reentrantly inside it.
                main.post { drawReady.complete(Unit) }
            }
        }
        .background(surface)) {
        CompositionLocalProvider(LocalBuildGraphicStatus provides rasterStatus) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                session.ui?.let { node ->
                    NativeRenderer(node, onAction = {}, onInput = { _, _ -> }, resolveImage = { null },
                        resolveGraphic = { id -> bundle.assets.get(id)?.takeIf { it.isJsonObject }?.asJsonObject })
                }
            }
        }
    }
}

private class VisualPreviewSession(
    private val context: Context,
    private val bundle: ProjectBundle,
    private val device: JsonObject,
    private val onCaptured: (ByteArray) -> Unit,
    private val onFailure: (String) -> Unit
) {
    var ui by mutableStateOf<JsonObject?>(null); private set
    var finished by mutableStateOf(false); private set
    private var rasterRevision by mutableIntStateOf(0)
    private val expectedRasters = IdentityHashMap<JsonObject, Boolean>()
    private var runtime: RuntimeClient? = null
    private val main = Handler(Looper.getMainLooper())
    // This bounds native runtime/image/draw work, not animation time. Compose test clocks and
    // private frame clocks may advance independently of Android rendering and real elapsed time.
    private val deadline = Runnable { fail("The app preview did not finish within 10 seconds.") }
    val readyToCapture: Boolean get() {
        @Suppress("UNUSED_VARIABLE") val revision = rasterRevision
        return ui != null && expectedRasters.values.all { it }
    }
    fun start() {
        if (finished || runtime != null) return
        main.postDelayed(deadline, 10_000)
        val client = RuntimeClient(context, onReady = {
            if (!finished) runtime?.send(JsonObject().apply {
                addProperty("type", "init")
                add("device", device.deepCopy())
                add("settings", JsonObject())
                add("storage", JsonObject())
            })
        }, onPacket = ::receive, onError = { fail("Preview runtime: $it") })
        runtime = client
        runCatching { client.start(bundle.source) }.onFailure { fail("The isolated preview runtime could not start.") }
    }
    private fun receive(packet: UiPacket) {
        if (finished || ui != null) return
        // Deliberately discard packet.effects. This client has no broker, permission hooks, storage,
        // action dispatch, hardware event loop, or persisted-image resolver.
        try {
            fun visit(node: JsonObject) {
                if (node["type"].asString == "graphic") {
                    val id = node["asset"].asString
                    val asset = bundle.assets.get(id)?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: error("The preview references a missing graphic.")
                    if (asset["type"].asString == "image") {
                        check(asset.has("data")) { "A preview image has not finished generating." }
                        expectedRasters[asset] = false
                    }
                }
                node.getAsJsonArray("children")?.forEach { visit(it.asJsonObject) }
            }
            visit(packet.ui)
            ui = packet.ui.deepCopy()
        } catch (error: Exception) { fail(error.message ?: "The preview contains an unavailable graphic.") }
    }
    fun rasterFinished(asset: JsonObject, success: Boolean) {
        if (finished || !expectedRasters.containsKey(asset)) return
        if (!success) { fail("A generated image could not be decoded for visual review."); return }
        if (expectedRasters[asset] != true) { expectedRasters[asset] = true; rasterRevision++ }
    }
    fun succeed(bytes: ByteArray) {
        if (finished) return
        close()
        onCaptured(bytes)
    }
    fun fail(message: String) {
        if (finished) return
        close()
        onFailure(message)
    }
    fun close() {
        if (finished) return
        finished = true
        main.removeCallbacks(deadline)
        runtime?.close(); runtime = null
    }
}

/** Capture stays PNG, <=1280 pixels on either side, <=4 MiB, entirely in memory. */
private fun boundedPreviewPng(source: Bitmap): ByteArray {
    var working = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
    try {
        val longest = max(working.width, working.height)
        if (longest > 1280) {
            val scale = 1280f / longest
            val scaled = Bitmap.createScaledBitmap(working, max(1, (working.width * scale).roundToInt()), max(1, (working.height * scale).roundToInt()), true)
            if (scaled !== working) { working.recycle(); working = scaled }
        }
        repeat(6) {
            val output = ByteArrayOutputStream()
            check(working.compress(Bitmap.CompressFormat.PNG, 100, output))
            if (output.size() <= 4 * 1024 * 1024) return output.toByteArray()
            val scaled = Bitmap.createScaledBitmap(working, max(1, (working.width * .8f).roundToInt()), max(1, (working.height * .8f).roundToInt()), true)
            if (scaled !== working) { working.recycle(); working = scaled }
        }
        error("The preview image exceeds its size limit.")
    } finally { working.recycle() }
}
