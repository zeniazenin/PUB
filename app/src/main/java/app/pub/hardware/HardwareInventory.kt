package app.pub.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.nfc.NfcManager
import android.os.Build
import android.os.VibratorManager
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Public metadata only; never starts capture, scans, sensor listeners or permission requests.
 * inspect() is local-only. describe() has its own allowlist and UTF-8 budget: no accessory names.
 */
object HardwareInventory {
    const val DESCRIPTION_MAX_BYTES = 15 * 1024
    val labels = linkedMapOf("camera" to "Cameras", "bluetooth" to "Bluetooth devices", "location" to "Location (GPS)",
        "sensors" to "Motion & environment sensors", "audio" to "Audio & headphones", "haptics" to "Vibration",
        "torch" to "Flashlight", "battery" to "Battery status", "nfc" to "NFC tags", "usb" to "USB devices", "infrared" to "Infrared transmitter",
        // Not hardware, but brokered and ruled the same way: documents the user picks in the system file picker.
        "files" to "Files on this phone")
    private val sensorTypes = linkedMapOf(
        "accelerometer" to Sensor.TYPE_ACCELEROMETER, "accelerometer_uncalibrated" to Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        "gyroscope" to Sensor.TYPE_GYROSCOPE, "gyroscope_uncalibrated" to Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD, "magnetic_field_uncalibrated" to Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        "gravity" to Sensor.TYPE_GRAVITY, "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
        "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR, "game_rotation_vector" to Sensor.TYPE_GAME_ROTATION_VECTOR,
        "geomagnetic_rotation_vector" to Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, "hinge_angle" to Sensor.TYPE_HINGE_ANGLE,
        "light" to Sensor.TYPE_LIGHT, "proximity" to Sensor.TYPE_PROXIMITY, "pressure" to Sensor.TYPE_PRESSURE,
        "relative_humidity" to Sensor.TYPE_RELATIVE_HUMIDITY, "ambient_temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
        "step_counter" to Sensor.TYPE_STEP_COUNTER, "step_detector" to Sensor.TYPE_STEP_DETECTOR)
    private val featureMap = linkedMapOf(
        "bluetooth" to "bluetooth", "bluetoothLE" to "bluetooth_le", "gps" to "location.gps", "nfc" to "nfc",
        "usbHost" to "usb.host", "usbAccessory" to "usb.accessory", "infrared" to "consumerir",
        "audioOutput" to "audio.output", "microphone" to "microphone", "flash" to "camera.flash",
        "wifi" to "wifi", "wifiDirect" to "wifi.direct", "wifiAware" to "wifi.aware", "wifiRtt" to "wifi.rtt",
        "telephony" to "telephony", "ethernet" to "ethernet", "uwb" to "uwb", "fingerprint" to "fingerprint", "faceAuthentication" to "biometrics.face")

    private var cachedDescription: JsonObject? = null
    private var cachedAt = 0L
    @Synchronized fun describe(context: Context): JsonObject {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedDescription?.takeIf { now - cachedAt < 15_000 }?.let { return it.deepCopy() }
        return describeFresh(context).also { cachedDescription = it.deepCopy(); cachedAt = now }
    }
    private fun describeFresh(context: Context): JsonObject {
        val root = basics(context)
        root.addProperty("inventoryScope", "Public Android metadata; OEM/private hardware may be hidden. Detection does not imply PUB support or a user grant.")
        val sensorInventory = sensors(context)
        root.add("sensors", JsonArray().apply {
            sensorInventory.getAsJsonArray("items").map { it.asJsonObject }.filter { it["pubDefault"].asBoolean }
                .map { it["pubName"].asString }.distinct().forEach { add(it) }
        })
        root.addProperty("detectedSensorCount", sensorInventory.getAsJsonArray("items").size())
        root.add("staticSensorCount", sensorInventory["staticCount"]); root.add("dynamicSensorCount", sensorInventory["dynamicCount"])
        root.add("device", device(context))
        val camera = camera(context)
        root.add("cameras", obj {
            listOf("present", "cameraCount", "lensEndpointCount", "inventoryAvailable", "metadataTruncated", "capture", "maxSavedPhotosPerApp", "maxSavedBytesPerApp", "maxSavedBytesPerPhoto", "jpegQualityChoices", "preservesCaptureResolution").forEach { key -> camera[key]?.let { add(key, it) } }
            add("cameras", JsonArray().apply { camera.getAsJsonArray("cameras")?.take(24)?.forEach { entry -> add(obj {
                listOf("cameraId", "logicalCameraId", "physicalCameraId", "label", "facing", "flash", "autofocus", "logicalMultiCamera", "manualExposure", "manualFocus", "preservesCaptureResolution")
                    .forEach { key -> entry.asJsonObject[key]?.let { if (it.isJsonPrimitive) add(key, it) } }
                listOf("focalLengthsMm", "apertures", "jpegResolutions").forEach { key ->
                    entry.asJsonObject[key]?.takeIf { it.isJsonArray }?.asJsonArray?.let { values ->
                        add(key, JsonArray().apply { values.take(if (key == "jpegResolutions") 3 else 8).forEach { add(it) } })
                    }
                }
            }) } })
        })
        root.addProperty("metadataTruncated", (camera.getAsJsonArray("cameras")?.size() ?: 0) > 24)
        val allFeatures = features(context); root.addProperty("hardwareFeatureCount", allFeatures.size())
        val detected = JsonArray(); root.add("detectedSensors", detected)
        sensorInventory.getAsJsonArray("items").forEach { entry -> val sensor = entry.asJsonObject
            append(root, detected, obj {
                addProperty("name", sensor["name"].asString.take(80)); add("type", sensor["type"])
                add("pubSupported", sensor["pubSupported"]); add("dynamic", sensor["dynamic"])
            })
        }
        val listedFeatures = JsonArray(); root.add("hardwareFeatures", listedFeatures)
        allFeatures.forEach { append(root, listedFeatures, it) }
        val summaries = JsonArray(); root.add("displays", summaries)
        displays(context).forEach { entry -> val info = entry.asJsonObject
            append(root, summaries, obj { listOf("width", "height", "refreshHz", "hdrTypes", "modes").forEach { key -> info[key]?.let { add(key, it) } } })
        }
        if (size(root) > DESCRIPTION_MAX_BYTES) {
            root.add("cameras", obj { add("present", camera["present"]); add("cameraCount", camera["cameraCount"]); addProperty("detailsAvailableLocally", true) })
            root.addProperty("metadataTruncated", true)
        }
        check(size(root) <= DESCRIPTION_MAX_BYTES) { "Hardware description exceeded its budget" }
        return root
    }

    /** Full local metadata, including currently available endpoint labels; no unique IDs or readings. */
    fun inspect(context: Context): JsonObject {
        synchronized(this) { cachedDescription = null }
        return basics(context).apply {
        addProperty("scope", "Hardware exposed by Android public APIs. Hidden OEM/private interfaces cannot be enumerated. Detected hardware may not have a PUB adapter; access still needs a user grant.")
        add("cameras", camera(context)); add("sensorInventory", sensors(context)); add("hardwareFeatures", features(context))
        add("device", device(context)); add("displays", displays(context)); add("connections", connections(context))
        add("hapticDetails", section {
            val manager = context.getSystemService(VibratorManager::class.java)
            addProperty("vibratorCount", manager?.vibratorIds?.size ?: 0)
            addProperty("amplitudeControl", manager?.defaultVibrator?.hasAmplitudeControl() == true)
        })
        addProperty("privacy", "No readings, photos, audio, coordinates, serial numbers, MAC addresses or network names collected. Accessory labels stay local.")
        }
    }
    private fun basics(context: Context) = obj {
        featureMap.forEach { (key, suffix) -> addProperty(key, context.packageManager.hasSystemFeature("android.hardware.$suffix")) }
        addProperty("haptics", runCatching { context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.hasVibrator() == true }.getOrDefault(false))
        addProperty("battery", true)
    }
    private fun sensors(context: Context): JsonObject {
        val manager = context.getSystemService(SensorManager::class.java)
        val static = runCatching { manager?.getSensorList(Sensor.TYPE_ALL).orEmpty() }.getOrDefault(emptyList())
        val dynamic = runCatching { manager?.getDynamicSensorList(Sensor.TYPE_ALL).orEmpty() }.getOrDefault(emptyList())
        return obj {
            addProperty("staticCount", static.size); addProperty("dynamicCount", dynamic.size)
            addProperty("dynamicDiscoverySupported", manager?.isDynamicSensorDiscoverySupported == true)
            add("items", JsonArray().apply { (static.map { it to false } + dynamic.map { it to true }).forEach { (sensor, isDynamic) -> add(obj {
                addProperty("name", clean(sensor.name)); addProperty("vendor", clean(sensor.vendor))
                addProperty("type", sensor.type); addProperty("stringType", clean(sensor.stringType)); addProperty("version", sensor.version)
                addProperty("dynamic", isDynamic); addProperty("wakeUp", sensor.isWakeUpSensor); addProperty("reportingMode", sensor.reportingMode)
                addProperty("resolution", sensor.resolution); addProperty("maximumRange", sensor.maximumRange); addProperty("powerMilliAmp", sensor.power)
                addProperty("minDelayMicros", sensor.minDelay); addProperty("maxDelayMicros", sensor.maxDelay)
                val pubName = sensorTypes.entries.firstOrNull { it.value == sensor.type }?.key
                val pubDefault = pubName != null && runCatching { manager?.getDefaultSensor(sensor.type) == sensor }.getOrDefault(false)
                addProperty("pubSupported", pubName != null); addProperty("pubDefault", pubDefault)
                if (pubName != null) addProperty("pubName", pubName)
                addProperty("access", if (pubName == null) "Detected only; no PUB adapter" else if (!pubDefault) "Supported type; PUB selects the Android default instance" else "PUB sensor adapter; user grant required")
            }) } })
        }
    }
    private fun features(context: Context) = JsonArray().apply {
        context.packageManager.systemAvailableFeatures.filter { it.name?.startsWith("android.hardware.") == true }.sortedBy { it.name }.forEach {
            add(obj { addProperty("name", clean(it.name)); addProperty("version", it.version) })
        }
    }
    private fun camera(context: Context) = runCatching { ProjectMedia(context).inventory() }.getOrElse {
        obj { addProperty("inventoryAvailable", false); addProperty("present", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)); addProperty("cameraCount", 0) }
    }
    private fun device(context: Context) = section {
        addProperty("manufacturer", clean(Build.MANUFACTURER)); addProperty("model", clean(Build.MODEL))
        addProperty("android", Build.VERSION.RELEASE); addProperty("sdk", Build.VERSION.SDK_INT)
        add("cpuAbis", JsonArray().apply { Build.SUPPORTED_ABIS.forEach { add(clean(it)) } })
        addProperty("availableCpuCores", Runtime.getRuntime().availableProcessors())
        val activity = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo(); activity?.getMemoryInfo(memory)
        addProperty("totalMemoryBytes", memory.totalMem); addProperty("lowRamDevice", activity?.isLowRamDevice == true)
        addProperty("openGles", activity?.deviceConfigurationInfo?.glEsVersion ?: "Not reported")
        addProperty("gpuModel", "Not exposed by this metadata API")
    }
    private fun displays(context: Context) = JsonArray().apply {
        runCatching { context.getSystemService(DisplayManager::class.java)?.displays.orEmpty() }.getOrDefault(emptyArray()).forEach { display -> add(section {
            addProperty("name", clean(display.name)); addProperty("builtIn", display.displayId == android.view.Display.DEFAULT_DISPLAY)
            addProperty("width", display.mode.physicalWidth); addProperty("height", display.mode.physicalHeight)
            addProperty("refreshHz", display.refreshRate); addProperty("wideColorGamut", display.isWideColorGamut)
            add("hdrTypes", JsonArray().apply { display.hdrCapabilities.supportedHdrTypes.forEach { add(when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "Type $it" }) } })
            add("modes", JsonArray().apply { display.supportedModes.forEach { mode -> add(obj {
                addProperty("width", mode.physicalWidth); addProperty("height", mode.physicalHeight); addProperty("refreshHz", mode.refreshRate)
            }) } })
        }) }
    }
    @SuppressLint("MissingPermission") // Existing CONNECT grant checked before paired-device metadata.
    private fun connections(context: Context) = obj {
        add("audio", section {
            addProperty("scope", "Available endpoints, not an active recording or playback route")
            add("endpoints", JsonArray().apply { context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)?.forEach { endpoint -> add(obj {
                addProperty("name", clean(endpoint.productName?.toString())); addProperty("type", audioType(endpoint.type))
                addProperty("input", endpoint.isSource); addProperty("output", endpoint.isSink)
                add("sampleRates", JsonArray().apply { endpoint.sampleRates.forEach { add(it) } }); add("channelCounts", JsonArray().apply { endpoint.channelCounts.forEach { add(it) } })
            }) } })
        })
        add("bluetooth", section {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            addProperty("present", adapter != null)
            val granted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            addProperty("permissionGranted", granted); addProperty("scope", "Paired devices; paired does not mean connected")
            if (granted && adapter != null) {
                addProperty("enabled", adapter.isEnabled)
                add("pairedDevices", JsonArray().apply { adapter.bondedDevices.forEach { item -> add(obj {
                    addProperty("name", clean(item.name)); addProperty("transport", when (item.type) { 1 -> "Classic"; 2 -> "BLE"; 3 -> "Dual"; else -> "Unknown" })
                }) } })
            }
        })
        add("usb", section {
            val manager = context.getSystemService(UsbManager::class.java)
            add("attachedDevices", JsonArray().apply { manager?.deviceList?.values?.forEach { item -> add(obj {
                addProperty("product", clean(runCatching { item.productName }.getOrNull())); addProperty("manufacturer", clean(runCatching { item.manufacturerName }.getOrNull()))
                addProperty("vendorId", item.vendorId); addProperty("productId", item.productId); addProperty("deviceClass", item.deviceClass)
                addProperty("interfaceCount", item.interfaceCount); addProperty("permissionGranted", manager.hasPermission(item))
            }) } })
        })
        add("nfc", section {
            val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter
            addProperty("present", adapter != null); addProperty("enabled", adapter?.isEnabled == true)
        })
        add("infrared", section {
            val manager = context.getSystemService(ConsumerIrManager::class.java)
            val present = manager?.hasIrEmitter() == true; addProperty("present", present)
            if (present) add("carrierRangesHz", JsonArray().apply { manager?.carrierFrequencies?.forEach { range -> add(obj { addProperty("min", range.minFrequency); addProperty("max", range.maxFrequency) }) } })
        })
    }
    private fun audioType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"; AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"; AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"; AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth media"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call audio"; AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"; AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"; AudioDeviceInfo.TYPE_HDMI -> "HDMI"; else -> "Audio type $type"
    }
    private fun clean(value: String?) = value.orEmpty().replace(Regex("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}"), "[address omitted]")
        .filter { !it.isISOControl() }.take(160).ifBlank { "Not reported" }
    private fun size(value: JsonObject) = value.toString().toByteArray(Charsets.UTF_8).size
    private fun append(root: JsonObject, array: JsonArray, value: JsonElement) {
        array.add(value)
        if (size(root) > DESCRIPTION_MAX_BYTES - 256) { array.remove(array.size() - 1); root.addProperty("metadataTruncated", true) }
    }
    private inline fun obj(block: JsonObject.() -> Unit) = JsonObject().apply(block)
    private inline fun section(block: JsonObject.() -> Unit) = JsonObject().apply {
        try { block(); addProperty("inventoryAvailable", true) } catch (_: Exception) { addProperty("inventoryAvailable", false) }
    }
}
