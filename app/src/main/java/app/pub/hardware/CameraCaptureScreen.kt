package app.pub.hardware

import android.view.Surface
import android.view.MotionEvent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ZoomState
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Observer
import com.google.gson.JsonObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Host-only camera UI. Caller must already authorize the capability, OS permission, and user action. */
@Composable
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
fun CameraCaptureScreen(directory: File, onResult: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestResult by rememberUpdatedState(onResult)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val session = remember(directory.absolutePath) { CaptureSession() }
    val main = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context, session) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    var provider by remember(session) { mutableStateOf<ProcessCameraProvider?>(null) }
    var availableLenses by remember(session) { mutableStateOf(emptyList<CameraLens>()) }
    var availableLogicalIds by remember(session) { mutableStateOf(emptySet<String>()) }
    var selectedKey by remember(session) { mutableStateOf("") }
    val lens = availableLenses.firstOrNull { it.key == selectedKey }
    var selectedSize by remember(selectedKey) { mutableStateOf<Size?>(null) }
    val photoPreferences = remember(context) { context.applicationContext.getSharedPreferences("camera-preferences", android.content.Context.MODE_PRIVATE) }
    var jpegQuality by remember { mutableIntStateOf(photoPreferences.getInt("jpeg-quality", 95).takeIf { it in listOf(70, 85, 95, 100) } ?: 95) }
    var camera by remember(session) { mutableStateOf<Camera?>(null) }
    var cameraReady by remember(session) { mutableStateOf(false) }
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
    var flashMode by remember(selectedKey) { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var manualExposure by remember(selectedKey) { mutableStateOf(false) }
    var manualFocus by remember(selectedKey) { mutableStateOf(false) }
    var controlMessage by remember(selectedKey) { mutableStateOf<String?>(null) }
    var capture by remember(session) { mutableStateOf<ImageCapture?>(null) }
    var busy by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }

    DisposableEffect(session) {
        val future = ProcessCameraProvider.getInstance(context.applicationContext)
        future.addListener({
            if (session.active.get()) {
                runCatching {
                    val cameraProvider = future.get()
                    val lenses = CameraCatalog.lenses(context.applicationContext)
                    availableLenses = lenses
                    availableLogicalIds = cameraProvider.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.toSet()
                    if (lenses.isEmpty()) error = "Android did not expose any usable cameras."
                    else selectedKey = (lenses.firstOrNull { it.facing == "back" && it.physicalId == null && it.logicalId in availableLogicalIds }
                        ?: lenses.first()).key
                    provider = cameraProvider
                }.onFailure { error = "Camera could not start. Close this screen and check camera access in Settings." }
            }
        }, main)
        onDispose {
            session.active.set(false)
            session.pending.getAndSet(null)?.delete()
            session.worker.shutdown()
        }
    }

    DisposableEffect(provider, owner, selectedKey, selectedSize, jpegQuality, session) {
        val cameraProvider = provider
        val previewBuilder = Preview.Builder()
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(jpegQuality)
            .setResolutionSelector(ResolutionSelector.Builder()
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .setResolutionStrategy(selectedSize?.let { ResolutionStrategy(it, ResolutionStrategy.FALLBACK_RULE_NONE) }
                    ?: ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .setResolutionFilter { sizes, _ ->
                    if (selectedSize == null) sizes.sortedByDescending { it.width.toLong() * it.height }
                    else sizes.filter { it == selectedSize }
                }.build())
        lens?.physicalId?.let { physical ->
            Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physical)
            Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physical)
        }
        val preview = previewBuilder.build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val imageCapture = captureBuilder.build()
        var bound: Camera? = null
        val stateObserver = Observer<CameraState> { state ->
            cameraReady = state.type == CameraState.Type.OPEN && state.error == null
            if (cameraReady) error = null
            if (state.error != null) error = "This lens or settings combination could not open (camera error ${state.error!!.code}). Choose another lens or a lower resolution."
        }
        cameraReady = false; error = null; controlMessage = null
        // A new binding is a new control session, even when CameraX reuses its Camera object.
        manualExposure = false; manualFocus = false; flashMode = ImageCapture.FLASH_MODE_OFF
        if (cameraProvider != null && lens != null && session.active.get()) {
            runCatching {
                check(lens.logicalId in availableLogicalIds)
                val selector = CameraSelector.Builder().addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == lens.logicalId }
                }.build()
                bound = cameraProvider.bindToLifecycle(owner, selector, preview, imageCapture)
                camera = bound
                bound!!.cameraInfo.cameraState.observe(owner, stateObserver)
                capture = imageCapture
            }.onFailure {
                capture = null; camera = null
                error = "This lens or resolution combination is unavailable. Try a lower resolution or the parent camera’s automatic lens."
            }
        }
        onDispose {
            bound?.cameraInfo?.cameraState?.removeObserver(stateObserver)
            capture = null; camera = null; cameraReady = false
            cameraProvider?.unbind(preview, imageCapture)
        }
    }

    LaunchedEffect(capture, flashMode, manualExposure) { capture?.flashMode = if (manualExposure) ImageCapture.FLASH_MODE_OFF else flashMode }
    DisposableEffect(camera, manualFocus, manualExposure, busy) {
        val active = camera
        val meteringAlive = AtomicBoolean(true)
        previewView.setOnTouchListener { _, touch ->
            if (touch.action == MotionEvent.ACTION_UP && active != null && !busy) {
                val flags = (if (manualFocus) 0 else FocusMeteringAction.FLAG_AF) or
                    (if (manualExposure) 0 else FocusMeteringAction.FLAG_AE) or FocusMeteringAction.FLAG_AWB
                val point = previewView.meteringPointFactory.createPoint(touch.x, touch.y)
                val action = FocusMeteringAction.Builder(point, flags).build()
                if (active.cameraInfo.isFocusMeteringSupported(action)) {
                    val future = active.cameraControl.startFocusAndMetering(action)
                    future.addListener({ if (session.active.get() && meteringAlive.get()) controlMessage = runCatching {
                        if (future.get().isFocusSuccessful) "Focus set" else "Metering updated; focus was not confirmed"
                    }.getOrDefault("Tap metering is unavailable with these settings") }, main)
                } else controlMessage = "Tap metering is not supported by this lens/settings combination"
                previewView.performClick()
            }
            true
        }
        onDispose { meteringAlive.set(false); previewView.setOnTouchListener(null) }
    }

    val close = {
        session.active.set(false)
        session.pending.getAndSet(null)?.delete()
        latestDismiss()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("camera-dialog")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Take a photo", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = close, modifier = Modifier.testTag("camera-close")) { Text("× Close") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    if (provider == null) CircularProgressIndicator()
                }
                CameraChoice("Lens", lens?.label ?: "Checking cameras…", availableLenses.map { it.key to it.label }, !busy,
                    "camera-lens-selector") { selectedKey = it }
                TextButton(onClick = { controlsOpen = !controlsOpen }, modifier = Modifier.testTag("camera-controls")) {
                    Text(if (controlsOpen) "Hide camera controls" else "Camera controls · ${availableLenses.size} lens routes")
                }
                if (lens != null) Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (controlsOpen) {
                    val sizes = listOf("auto" to "Highest available") + lens.sizes.map { "${it.width}x${it.height}" to "${it.width} × ${it.height}" }
                    CameraChoice("Capture resolution", selectedSize?.let { "${it.width} × ${it.height}" } ?: "Highest available", sizes, !busy, "camera-resolution") { key ->
                        selectedSize = lens.sizes.firstOrNull { "${it.width}x${it.height}" == key }
                    }
                    capture?.resolutionInfo?.resolution?.let { Text("Capture size: ${it.width} × ${it.height} · saved without resizing", style = MaterialTheme.typography.bodySmall) }
                    CameraChoice("JPEG quality", qualityLabel(jpegQuality), listOf(70, 85, 95, 100).map { it.toString() to qualityLabel(it) }, !busy, "camera-quality") { value ->
                        jpegQuality = value.toInt()
                        photoPreferences.edit().putInt("jpeg-quality", jpegQuality).apply()
                    }
                    Text("Quality changes compression, not pixel dimensions. Remembered for future captures. Storage: 20 photos / 1 GiB per app, up to 256 MiB each.", style = MaterialTheme.typography.bodySmall)
                    if (lens.flash) CameraChoice("Flash", flashLabel(flashMode), listOf(ImageCapture.FLASH_MODE_OFF, ImageCapture.FLASH_MODE_AUTO, ImageCapture.FLASH_MODE_ON).map { it.toString() to flashLabel(it) }, !busy && !manualExposure, "camera-flash") { flashMode = it.toInt() }
                    else Text("Flash unavailable on this lens", style = MaterialTheme.typography.bodySmall)
                    }
                    // Keep controls/effects composed when collapsed: hiding UI must not lose the
                    // values whose Camera2 overrides remain active. A new capture use case resets them.
                    key(capture) {
                        camera?.let { active -> CameraControls(active, lens, busy, controlsOpen, manualExposure, { manualExposure = it }, manualFocus, { manualFocus = it }) { controlMessage = it } }
                    }
                    if (controlsOpen) {
                    Text("Tap the preview for focus and metering. Hiding controls keeps your settings. Changing lens, resolution or quality resets manual exposure, focus, white balance and flash; closing the camera resets all controls.", style = MaterialTheme.typography.bodySmall)
                    Text("Only the controls shown above are adjustable here. OEM-only features and Samsung Pro modes are not exposed. Android extensions detected: ${lens.extensions.joinToString().ifBlank { "none" }} (not enabled in this dialog).", style = MaterialTheme.typography.bodySmall)
                    }
                }
                controlMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(
                    enabled = capture != null && cameraReady && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("camera-capture"),
                    onClick = {
                        val activeCapture = capture
                        if (activeCapture != null && session.active.get()) {
                            try {
                                val raw = ProjectMedia.beginCapture(directory)
                                session.pending.set(raw)
                                busy = true; error = null
                                activeCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                                val capturedLens = lens
                                val capturedQuality = jpegQuality
                                activeCapture.takePicture(ImageCapture.OutputFileOptions.Builder(raw).build(), main, object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                        if (!session.active.get()) { raw.delete(); return }
                                        runCatching { session.worker.execute {
                                            var completed: File? = null
                                            try {
                                                if (!session.active.get()) { raw.delete(); return@execute }
                                                val photo = ProjectMedia.finishCapture(directory, raw).apply {
                                                    addProperty("lensFacing", capturedLens?.facing ?: "unknown")
                                                    addProperty("lensLabel", capturedLens?.label ?: "Camera")
                                                    addProperty("jpegQuality", capturedQuality)
                                                }
                                                val saved = File(directory, photo["asset"].asString)
                                                completed = saved
                                                session.pending.set(saved)
                                                main.execute {
                                                    if (session.active.get()) {
                                                        session.pending.compareAndSet(saved, null)
                                                        busy = false
                                                        latestResult(photo)
                                                    } else saved.delete()
                                                }
                                            } catch (failure: Exception) {
                                                raw.delete(); completed?.delete()
                                                main.execute {
                                                    if (session.active.get()) {
                                                        busy = false
                                                        error = failure.message?.takeIf { failure is IllegalStateException } ?: "Could not save this photo. Please try again."
                                                    }
                                                }
                                            }
                                        } }.onFailure { raw.delete() }
                                    }
                                    override fun onError(exception: ImageCaptureException) {
                                        raw.delete(); session.pending.compareAndSet(raw, null)
                                        if (session.active.get()) { busy = false; error = "Could not capture this photo. Please try again." }
                                    }
                                })
                            } catch (failure: Exception) {
                                session.pending.getAndSet(null)?.delete()
                                busy = false
                                error = failure.message?.takeIf { failure is IllegalStateException } ?: "Camera capture is unavailable."
                            }
                        }
                    }
                ) { Text(if (busy) "Saving photo…" else "Capture photo") }
                Text("Saved only inside this app on your phone. No video is recorded or uploaded.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CameraChoice(title: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean, tag: String, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text("$title: $value")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            choices.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; choose(key) }) }
        }
    }
}

private fun qualityLabel(value: Int) = when (value) { 70 -> "Compact · 70"; 85 -> "Balanced · 85"; 100 -> "Maximum · 100"; else -> "High · 95" }

private fun flashLabel(mode: Int) = when (mode) { ImageCapture.FLASH_MODE_AUTO -> "Auto"; ImageCapture.FLASH_MODE_ON -> "On"; else -> "Off" }
private fun whiteBalanceLabel(mode: Int) = when (mode) {
    CaptureRequest.CONTROL_AWB_MODE_AUTO -> "Auto"
    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "Incandescent"
    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescent"
    CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Warm fluorescent"
    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "Daylight"
    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Cloudy"
    CaptureRequest.CONTROL_AWB_MODE_TWILIGHT -> "Twilight"
    CaptureRequest.CONTROL_AWB_MODE_SHADE -> "Shade"
    else -> "Mode $mode"
}

@Composable
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun CameraControls(camera: Camera, lens: CameraLens, busy: Boolean, visible: Boolean,
    manualExposure: Boolean, setManualExposure: (Boolean) -> Unit, manualFocus: Boolean, setManualFocus: (Boolean) -> Unit,
    report: (String?) -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val main = remember(context) { ContextCompat.getMainExecutor(context) }
    val alive = remember(camera, owner) { AtomicBoolean(true) }
    val latestReport by rememberUpdatedState(report)
    fun watch(future: com.google.common.util.concurrent.ListenableFuture<*>, failure: String) {
        future.addListener({ if (alive.get()) runCatching { future.get() }.onFailure { latestReport(failure) } }, main)
    }
    var zoomState by remember(camera) { mutableStateOf(camera.cameraInfo.zoomState.value) }
    var zoom by remember(camera) { mutableFloatStateOf(zoomState?.zoomRatio ?: 1f) }
    DisposableEffect(camera, owner) {
        val observer = Observer<ZoomState> { state -> zoomState = state; zoom = state.zoomRatio }
        camera.cameraInfo.zoomState.observe(owner, observer)
        onDispose { alive.set(false); camera.cameraInfo.zoomState.removeObserver(observer) }
    }
    val minimumZoom = zoomState?.minZoomRatio ?: 1f
    val maximumZoom = zoomState?.maxZoomRatio ?: 1f
    if (visible && maximumZoom > minimumZoom) {
        Text(String.format(Locale.US, "Zoom %.2f× (%.2f–%.2f×)", zoom, minimumZoom, maximumZoom))
        Slider(zoom.coerceIn(minimumZoom, maximumZoom), { zoom = it }, valueRange = minimumZoom..maximumZoom, enabled = !busy,
            onValueChangeFinished = { watch(camera.cameraControl.setZoomRatio(zoom), "Zoom is unavailable for this lens") }, modifier = Modifier.testTag("camera-zoom"))
    }
    val exposure = camera.cameraInfo.exposureState
    var compensation by remember(camera) { mutableFloatStateOf(exposure.exposureCompensationIndex.toFloat()) }
    if (visible && exposure.isExposureCompensationSupported && exposure.exposureCompensationRange.lower < exposure.exposureCompensationRange.upper) {
        Text(String.format(Locale.US, "Exposure compensation %+.2f EV", compensation.roundToInt() * exposure.exposureCompensationStep.toDouble()))
        Slider(compensation, { compensation = it.roundToInt().toFloat() },
            valueRange = exposure.exposureCompensationRange.lower.toFloat()..exposure.exposureCompensationRange.upper.toFloat(), enabled = !busy && !manualExposure,
            onValueChangeFinished = { watch(camera.cameraControl.setExposureCompensationIndex(compensation.roundToInt()), "Exposure compensation is unavailable with these settings") }, modifier = Modifier.testTag("camera-exposure"))
    }
    val c = lens.characteristics
    val isoRange = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
    val timeRange = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
    val focusMaximum = c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f
    var iso by remember(lens.key) { mutableFloatStateOf(100.coerceIn(isoRange?.lower ?: 100, isoRange?.upper ?: 100).toFloat()) }
    val lowTime = (timeRange?.lower ?: 1_000_000L).coerceAtLeast(1L).toDouble()
    val highTime = (timeRange?.upper ?: 100_000_000L).coerceAtLeast(lowTime.toLong()).toDouble()
    var logTime by remember(lens.key) { mutableFloatStateOf(ln(10_000_000.0.coerceIn(lowTime, highTime)).toFloat()) }
    var distance by remember(lens.key) { mutableFloatStateOf(0f) }
    var wb by remember(lens.key) { mutableIntStateOf(lens.whiteBalanceModes.firstOrNull { it == CaptureRequest.CONTROL_AWB_MODE_AUTO } ?: lens.whiteBalanceModes.firstOrNull() ?: CaptureRequest.CONTROL_AWB_MODE_AUTO) }
    var revision by remember(camera) { mutableIntStateOf(0) }

    LaunchedEffect(camera, manualExposure, manualFocus, wb, revision) {
        val options = CaptureRequestOptions.Builder()
        if (manualExposure && lens.manualExposure && isoRange != null && timeRange != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.roundToInt().coerceIn(isoRange.lower, isoRange.upper))
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp(logTime.toDouble()).toLong().coerceIn(timeRange.lower, timeRange.upper))
        }
        if (manualFocus && lens.manualFocus) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance.coerceIn(0f, focusMaximum))
        }
        if (wb in lens.whiteBalanceModes) options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wb)
        runCatching { watch(Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build()), "This manual-control combination was rejected. Return to automatic controls or select another lens.") }
            .onFailure { report("Manual controls are unavailable for this camera session") }
    }
    if (visible) {
    if (lens.manualExposure && isoRange != null && timeRange != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Manual ISO and shutter"); Switch(manualExposure, setManualExposure, enabled = !busy, modifier = Modifier.testTag("camera-manual-exposure"))
        }
        if (manualExposure) {
            Text("ISO ${iso.roundToInt()} (${isoRange.lower}–${isoRange.upper})")
            if (isoRange.lower < isoRange.upper) Slider(iso, { iso = it }, valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(), enabled = !busy, onValueChangeFinished = { revision++ })
            Text(String.format(Locale.US, "Shutter %.3f ms", exp(logTime.toDouble()) / 1_000_000))
            if (highTime > lowTime) Slider(logTime, { logTime = it }, valueRange = ln(lowTime).toFloat()..ln(highTime).toFloat(), enabled = !busy, onValueChangeFinished = { revision++ })
            Text("Flash and exposure compensation are disabled during manual exposure. Long shutter times need a steady phone.", style = MaterialTheme.typography.bodySmall)
        }
    } else Text("Manual exposure is not publicly controllable for this lens route", style = MaterialTheme.typography.bodySmall)
    if (lens.manualFocus) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Manual focus"); Switch(manualFocus, setManualFocus, enabled = !busy, modifier = Modifier.testTag("camera-manual-focus"))
        }
        if (manualFocus) {
            Text(if (distance == 0f) "Focus: infinity" else String.format(Locale.US, "Focus: %.2f diopters", distance))
            Slider(distance, { distance = it }, valueRange = 0f..focusMaximum, enabled = !busy, onValueChangeFinished = { revision++ })
        }
    } else Text("Manual focus unavailable (fixed-focus or unsupported physical controls)", style = MaterialTheme.typography.bodySmall)
    if (lens.whiteBalanceModes.isNotEmpty()) CameraChoice("White balance", whiteBalanceLabel(wb), lens.whiteBalanceModes.map { it.toString() to whiteBalanceLabel(it) }, !busy, "camera-white-balance") { wb = it.toInt() }
    }
}

private class CaptureSession {
    val active = AtomicBoolean(true)
    val pending = AtomicReference<File?>(null)
    val worker = Executors.newSingleThreadExecutor()
}
