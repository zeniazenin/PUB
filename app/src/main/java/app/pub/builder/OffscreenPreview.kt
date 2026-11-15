package app.pub.builder

import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.pub.core.ProjectBundle
import app.pub.ui.BuildVisualPreview
import app.pub.ui.PubColors
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Only PUB-generated content is rendered on this app-owned private display; nothing is mirrored. */
object OffscreenPreview {
    suspend fun capture(context: Context, bundle: ProjectBundle, device: JsonObject): ByteArray =
        withContext(Dispatchers.Main.immediate) {
            withTimeout(30_000) { captureOnMain(context.applicationContext, bundle, device.deepCopy()) }
        }

    private suspend fun captureOnMain(context: Context, bundle: ProjectBundle, device: JsonObject): ByteArray {
        val spec = viewport(context, device)
        val displayManager = checkNotNull(context.getSystemService(DisplayManager::class.java)) { "Private preview displays are unavailable." }
        val main = Handler(Looper.getMainLooper())
        val result = CompletableDeferred<ByteArray>()
        val owners = PreviewOwners()
        val frameClock = BroadcastFrameClock()
        val renderingJob = SupervisorJob(currentCoroutineContext()[Job])
        val renderingScope = CoroutineScope(Dispatchers.Main.immediate + renderingJob + frameClock)
        val recomposer = Recomposer(renderingScope.coroutineContext)
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var presentation: Presentation? = null
        var view: ComposeView? = null
        var disposing = false
        try {
            reader = ImageReader.newInstance(spec.widthPx, spec.heightPx, PixelFormat.RGBA_8888, 2).apply {
                // Drain buffers so rendering never blocks behind a full consumer queue. Captured PNG
                // comes from BuildVisualPreview's own graphics layer, never from display pixels.
                setOnImageAvailableListener({ source -> runCatching { source.acquireLatestImage()?.close() } }, main)
            }
            display = checkNotNull(displayManager.createVirtualDisplay(
                "PUB private build preview", spec.widthPx, spec.heightPx, spec.densityDpi, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                object : VirtualDisplay.Callback() {
                    override fun onStopped() {
                        if (!disposing) result.completeExceptionally(IllegalStateException("The private preview display stopped."))
                    }
                }, main
            )) { "Android could not create a private preview display." }
            presentation = Presentation(context, display.display, android.R.style.Theme_Material_NoActionBar).apply {
                setCancelable(false)
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                    addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setDimAmount(0f)
                }
                setOnDismissListener {
                    if (!disposing && !result.isCompleted) result.completeExceptionally(IllegalStateException("The private preview window closed."))
                }
            }
            val configuration = Configuration(presentation.context.resources.configuration).apply {
                densityDpi = spec.densityDpi
                fontScale = spec.fontScale
                screenWidthDp = (spec.widthPx / spec.density).roundToInt()
                screenHeightDp = (spec.heightPx / spec.density).roundToInt()
            }
            val displayContext = presentation.context.createConfigurationContext(configuration)
            view = ComposeView(displayContext).apply {
                setViewTreeLifecycleOwner(owners)
                setViewTreeSavedStateRegistryOwner(owners)
                setViewTreeViewModelStoreOwner(owners)
                setParentCompositionContext(recomposer)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owners.lifecycle))
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(spec.density, spec.fontScale),
                        LocalLayoutDirection provides spec.direction) {
                        MaterialTheme(colorScheme = PubColors.scheme(device.text("accent") ?: "moss")) {
                            BuildVisualPreview(bundle, device,
                                onCaptured = { result.complete(it) },
                                onFailure = { result.completeExceptionally(IllegalStateException(it)) })
                        }
                    }
                }
            }
            presentation.window?.decorView?.apply {
                setViewTreeLifecycleOwner(owners)
                setViewTreeSavedStateRegistryOwner(owners)
                setViewTreeViewModelStoreOwner(owners)
            }
            presentation.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            renderingScope.launch {
                try { recomposer.runRecomposeAndApplyChanges() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { result.completeExceptionally(failure) }
            }
            presentation.show()
            presentation.window?.setLayout(spec.widthPx, spec.heightPx)
            owners.resume()
            // This private composition must not inherit the stopped Activity's lifecycle/frame clock.
            // Android still owns window drawing on the virtual display; no foreground screen is woken.
            renderingScope.launch {
                while (isActive && !result.isCompleted) {
                    frameClock.sendFrame(System.nanoTime())
                    view?.invalidate()
                    delay(16)
                }
            }
            return result.await()
        } finally {
            disposing = true
            // disposeComposition executes BuildVisualPreview's RuntimeClient cleanup before the
            // display is released, including when cancellation or a deadline interrupts capture.
            runCatching { view?.disposeComposition() }
            runCatching { owners.destroy() }
            recomposer.cancel()
            renderingScope.cancel()
            runCatching { presentation?.dismiss() }
            runCatching { display?.release() }
            runCatching { reader?.setOnImageAvailableListener(null, null) }
            runCatching { reader?.close() }
            result.cancel()
        }
    }

    private data class Viewport(val widthPx: Int, val heightPx: Int, val density: Float, val fontScale: Float, val direction: LayoutDirection) {
        val densityDpi: Int get() = (density * 160).roundToInt().coerceAtLeast(1)
    }
    private fun viewport(context: Context, device: JsonObject): Viewport {
        val metrics = context.resources.displayMetrics
        val config = context.resources.configuration
        val layout = device.get("layoutContext")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        var density = layout.number("density", .25, 8.0)?.toFloat() ?: metrics.density
        val fontScale = (layout.number("fontScale", .5, 5.0) ?: device.number("fontScale", .5, 5.0))?.toFloat() ?: config.fontScale
        val widthDp = layout.number("measuredBuilderViewportWidthDp", 1.0, 8192.0)
            ?: device.number("screenWidthDp", 1.0, 8192.0) ?: max(1, config.screenWidthDp).toDouble()
        val heightDp = layout.number("measuredBuilderViewportHeightDp", 1.0, 8192.0)
            ?: device.number("screenHeightDp", 1.0, 8192.0) ?: max(1, config.screenHeightDp).toDouble()
        var width = max(1, (widthDp * density).roundToInt())
        var height = max(1, (heightDp * density).roundToInt())
        // Preserve logical dp/font layout while bounding backing buffers on very large displays.
        val scale = min(1.0, min(3072.0 / max(width, height), sqrt(4_000_000.0 / (width.toLong() * height))))
        if (scale < 1) {
            width = max(1, (width * scale).roundToInt()); height = max(1, (height * scale).roundToInt())
            density *= scale.toFloat()
        }
        val direction = when (layout.text("layoutDirection")) {
            "rtl" -> LayoutDirection.Rtl
            "ltr" -> LayoutDirection.Ltr
            else -> if (config.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
        }
        return Viewport(width, height, density, fontScale, direction)
    }
    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.number(name: String, min: Double, max: Double): Double? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() && it in min..max }

    private class PreviewOwners : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        init {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
    }
}
