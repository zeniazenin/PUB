package app.pub.hardware

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Size
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

/** Public, static Camera2 metadata. Camera IDs identify camera endpoints, not a handset or person. */
internal data class CameraLens(
    val logicalId: String,
    val physicalId: String?,
    val characteristics: CameraCharacteristics,
    val physicalRequestKeys: Set<String> = emptySet(),
    val extensions: List<Int> = emptyList()
) {
    val key get() = "$logicalId/${physicalId ?: "auto"}"
    val id get() = physicalId ?: logicalId
    val facing get() = when (characteristics[CameraCharacteristics.LENS_FACING]) {
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        else -> "external"
    }
    val focalLengths get() = characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.toList().orEmpty()
    val label get() = buildString {
        append(facing.replaceFirstChar { it.uppercase() })
        append(if (physicalId == null) " · camera $logicalId" else " · lens $physicalId via $logicalId")
        if (focalLengths.isNotEmpty()) append(" · " + focalLengths.joinToString("/") { String.format(Locale.US, "%.1f", it) } + " mm")
        if (physicalId == null && characteristics.physicalCameraIds.isNotEmpty()) append(" · automatic lens")
    }
    val sizes: List<Size> get() {
        val streams = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        return (streams?.getOutputSizes(ImageFormat.JPEG).orEmpty().toList() +
            streams?.getHighResolutionOutputSizes(ImageFormat.JPEG).orEmpty().toList())
            .distinct().sortedByDescending { it.width.toLong() * it.height }
    }
    val flash get() = characteristics[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
    private val requestKeys get() = characteristics.availableCaptureRequestKeys.orEmpty().map { it.name }.toSet()
    fun supports(vararg keys: CaptureRequest.Key<*>): Boolean = keys.all {
        it.name in requestKeys && (physicalId == null || it.name in physicalRequestKeys)
    }
    val manualExposure get() = characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true &&
        supports(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.SENSOR_SENSITIVITY, CaptureRequest.SENSOR_EXPOSURE_TIME) &&
        characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_AE_MODE_OFF) == true
    val manualFocus get() = (characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f) > 0 &&
        supports(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.LENS_FOCUS_DISTANCE) &&
        characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true
    val whiteBalanceModes get() = if (supports(CaptureRequest.CONTROL_AWB_MODE))
        characteristics[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]?.filter { it != CaptureRequest.CONTROL_AWB_MODE_OFF }.orEmpty() else emptyList()
}

internal object CameraCatalog {
    fun lenses(context: Context): List<CameraLens> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val lenses = mutableListOf<CameraLens>()
        manager.cameraIdList.forEach { logicalId ->
            runCatching {
                val info = manager.getCameraCharacteristics(logicalId)
                val extensions = runCatching { manager.getCameraExtensionCharacteristics(logicalId).supportedExtensions }.getOrDefault(emptyList())
                lenses.add(CameraLens(logicalId, null, info, extensions = extensions))
                val physicalKeys = info.availablePhysicalCameraRequestKeys.orEmpty().map { it.name }.toSet()
                info.physicalCameraIds.sorted().forEach { physicalId ->
                    runCatching { lenses.add(CameraLens(logicalId, physicalId, manager.getCameraCharacteristics(physicalId), physicalKeys)) }
                }
            }
        }
        return lenses
    }

    fun inventory(context: Context): JsonObject {
        val result = JsonObject()
        val all = runCatching { lenses(context) }
        val lenses = all.getOrDefault(emptyList())
        val reportedIds = runCatching { context.getSystemService(CameraManager::class.java)?.cameraIdList?.toList().orEmpty() }.getOrDefault(emptyList())
        result.addProperty("inventoryAvailable", all.isSuccess)
        result.addProperty("cameraCount", reportedIds.size)
        result.add("reportedCameraIds", strings(reportedIds.take(128)))
        result.add("unreadableCameraIds", strings(reportedIds.filter { id -> lenses.none { it.logicalId == id && it.physicalId == null } }))
        result.addProperty("lensEndpointCount", lenses.size)
        result.addProperty("metadataTruncated", lenses.size > 32)
        result.add("cameras", JsonArray().apply { lenses.take(32).forEach { add(describe(it)) } })
        result.addProperty("selection", "logical camera ID or physical lens through its logical parent; combinations are verified on opening")
        result.addProperty("capture", "still-photo")
        result.addProperty("extensionSupport", "Android-reported availability only; extensions are not activated by PUB")
        result.addProperty("vendorPrivateModes", false)
        // Hardware replies share a bounded Binder message. Preserve every endpoint identity even
        // when an unusually verbose HAL forces us to omit detailed public-setting lists.
        val entries = result.getAsJsonArray("cameras")
        var compactIndex = entries.size() - 1
        while (result.toString().toByteArray(Charsets.UTF_8).size > 23_000 && compactIndex >= 0) {
            val source = entries[compactIndex].asJsonObject
            entries.set(compactIndex--, JsonObject().apply {
                listOf("cameraId", "logicalCameraId", "physicalCameraId", "selectionKey", "label", "facing", "flash", "focalLengthsMm", "manualExposure", "manualFocus").forEach { field -> source[field]?.let { add(field, it) } }
                addProperty("settingsMetadataOmitted", true)
            })
            result.addProperty("metadataTruncated", true)
        }
        return result
    }

    fun describe(lens: CameraLens): JsonObject = JsonObject().apply {
        val c = lens.characteristics
        addProperty("cameraId", lens.id); addProperty("logicalCameraId", lens.logicalId)
        lens.physicalId?.let { addProperty("physicalCameraId", it) }
        addProperty("selectionKey", lens.key); addProperty("label", lens.label); addProperty("facing", lens.facing)
        addProperty("logicalMultiCamera", c.physicalCameraIds.isNotEmpty())
        add("physicalCameraIds", strings(c.physicalCameraIds.sorted()))
        add("focalLengthsMm", numbers(lens.focalLengths))
        add("apertures", numbers(c[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.toList().orEmpty()))
        addProperty("flash", lens.flash)
        addProperty("autofocus", c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.any { it != CaptureRequest.CONTROL_AF_MODE_OFF } == true)
        addProperty("manualExposure", lens.manualExposure); addProperty("manualFocus", lens.manualFocus)
        add("whiteBalanceModes", numbers(lens.whiteBalanceModes))
        addProperty("minimumFocusDistanceDiopters", c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f)
        c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.let { add("isoRange", numbers(listOf(it.lower, it.upper))) }
        c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.let { add("exposureTimeNsRange", numbers(listOf(it.lower, it.upper))) }
        c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]?.let { add("exposureCompensationIndices", numbers(listOf(it.lower, it.upper))) }
        c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.let { addProperty("exposureCompensationStepEV", it.toDouble()) }
        c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.let { add("zoomRatioRange", numbers(listOf(it.lower, it.upper))) }
        c[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]?.let { addProperty("maximumDigitalZoom", it) }
        c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]?.let { addProperty("hardwareLevel", it) }
        add("capabilities", numbers(c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toList().orEmpty()))
        add("focusModes", numbers(c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.toList().orEmpty()))
        add("autoExposureModes", numbers(c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.toList().orEmpty()))
        add("sceneModes", numbers(c[CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES]?.toList().orEmpty()))
        add("effects", numbers(c[CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS]?.toList().orEmpty()))
        add("opticalStabilizationModes", numbers(c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.toList().orEmpty()))
        add("noiseReductionModes", numbers(c[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]?.toList().orEmpty()))
        add("edgeModes", numbers(c[CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES]?.toList().orEmpty()))
        add("extensions", numbers(lens.extensions))
        val sizes = lens.sizes
        addProperty("jpegResolutionCount", sizes.size)
        addProperty("jpegResolutionsTruncated", sizes.size > 24)
        add("jpegResolutions", JsonArray().apply { sizes.take(24).forEach { add("${it.width}x${it.height}") } })
        val keys = c.availableCaptureRequestKeys.orEmpty().map { it.name }.filter { it.startsWith("android.") }.sorted()
        addProperty("publicRequestKeyCount", keys.size)
        addProperty("publicRequestKeysTruncated", keys.size > 128)
        add("publicRequestKeys", strings(keys.take(128)))
        add("physicalRequestKeys", strings(lens.physicalRequestKeys.sorted().take(128)))
        addProperty("preservesCaptureResolution", true)
        add("jpegQualityChoices", numbers(listOf(70, 85, 95, 100)))
        addProperty("runtimeControls", "lens, resolution, JPEG quality, zoom, flash, exposure compensation, tap metering, supported manual ISO/shutter/focus and white-balance presets")
    }

    private fun numbers(values: List<Number>) = JsonArray().apply { values.take(128).forEach { add(it) } }
    private fun strings(values: List<String>) = JsonArray().apply { values.take(128).forEach { add(it.take(160)) } }
}
