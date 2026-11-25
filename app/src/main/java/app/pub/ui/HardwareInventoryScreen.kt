package app.pub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pub.hardware.HardwareInventory
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local metadata page. Accessory names are not part of the builder's generation context. */
@Composable
fun HardwareInventoryScreen() {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        loading = true; error = null
        val result = withContext(Dispatchers.IO) { runCatching { HardwareInventory.inspect(context) } }
        result.onSuccess { data = it }.onFailure { error = "Could not read the hardware inventory. Try refreshing." }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize().testTag("hardware-inventory-screen"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Hardware inventory", style = MaterialTheme.typography.headlineSmall)
            Text("Everything Android makes visible through its public hardware APIs. Hidden manufacturer interfaces may not be discoverable.", style = MaterialTheme.typography.bodyMedium)
            PubOutlinedButton(onClick = { refresh++ }, enabled = !loading, modifier = Modifier.testTag("refresh-hardware-inventory")) { Text("Refresh inventory") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        data?.let { inventory ->
            val cameras = inventory.obj("cameras")
            val sensorInventory = inventory.obj("sensorInventory")
            val connections = inventory.obj("connections")
            item {
                Text("Detection and access are separate", style = MaterialTheme.typography.titleMedium)
                Text("A detected component may not have a PUB adapter. Supported features still require your global and per-app permission. This page opens no camera, microphone or sensor session.")
            }
            item {
                InventorySection("Cameras", "${cameras.value("cameraCount")} logical cameras · ${cameras.value("lensEndpointCount", cameras.array("cameras").size().toString())} lens endpoints", initiallyOpen = true) {
                    if (cameras.value("inventoryAvailable") == "false") Text("Camera metadata is unavailable with the current Android access.")
                    if (cameras.array("cameras").isEmpty) Text("No camera metadata reported.")
                    cameras.array("cameras").forEachIndexed { index, entry ->
                        val camera = entry.asJsonObject
                        MetadataRow(camera.value("label", "Camera ${index + 1} · ${camera.value("facing")}"),
                            "Flash ${yes(camera, "flash")} · autofocus ${yes(camera, "autofocus")} · manual exposure ${yes(camera, "manualExposure")}", camera)
                    }
                    if (cameras.value("metadataTruncated") == "true") Text("Some verbose metadata is omitted. The camera capture dialog shows the available controls.")
                    Text("Use a camera app's capture dialog to choose a lens and its supported settings.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                InventorySection("Sensors", "${sensorInventory.value("staticCount")} static · ${sensorInventory.value("dynamicCount")} dynamic") {
                    Text("Includes standard, wake-up and manufacturer sensors. PUB currently uses the Android default instance of its supported sensor types.")
                    sensorInventory.array("items").forEach { entry -> val sensor = entry.asJsonObject
                        MetadataRow(sensor.value("name"), "${sensor.value("vendor")} · ${sensor.value("access")}", sensor)
                    }
                    if (sensorInventory.array("items").isEmpty) Text("No public sensors reported.")
                }
            }
            item {
                InventorySection("Connections & capabilities", "Audio, paired devices, USB and available radios") {
                    listOf("bluetooth" to "Bluetooth", "bluetoothLE" to "Bluetooth LE", "gps" to "GPS", "nfc" to "NFC", "usbHost" to "USB host",
                        "usbAccessory" to "USB accessory", "infrared" to "Infrared", "microphone" to "Microphone", "audioOutput" to "Audio output",
                        "haptics" to "Vibration", "flash" to "Flashlight", "wifi" to "Wi-Fi", "wifiDirect" to "Wi-Fi Direct", "wifiAware" to "Wi-Fi Aware",
                        "wifiRtt" to "Wi-Fi ranging", "telephony" to "Cellular radio", "ethernet" to "Ethernet", "uwb" to "Ultra-wideband",
                        "fingerprint" to "Fingerprint authentication", "faceAuthentication" to "Face authentication").forEach { (key, label) ->
                        Text("$label · ${if (inventory.value(key) == "true") "Detected" else "Not reported"}")
                    }
                    Text("Available audio endpoints", style = MaterialTheme.typography.titleSmall)
                    connections.obj("audio").array("endpoints").forEach { entry -> val audio = entry.asJsonObject
                        MetadataRow(audio.value("name"), "${audio.value("type")} · input ${yes(audio, "input")} · output ${yes(audio, "output")}", audio)
                    }
                    val bluetooth = connections.obj("bluetooth")
                    Text("Paired Bluetooth devices", style = MaterialTheme.typography.titleSmall)
                    Text("Paired does not mean currently connected. Bluetooth metadata permission: ${if (bluetooth.value("permissionGranted") == "true") "granted" else "not granted"}.")
                    bluetooth.array("pairedDevices").forEach { entry -> val item = entry.asJsonObject; Text("${item.value("name")} · ${item.value("transport")}") }
                    val usb = connections.obj("usb").array("attachedDevices")
                    Text("Attached USB devices · ${usb.size()}", style = MaterialTheme.typography.titleSmall)
                    usb.forEach { entry -> val item = entry.asJsonObject; MetadataRow(item.value("product"), "${item.value("manufacturer")} · ${item.value("interfaceCount")} interfaces", item) }
                    MetadataRow("NFC", "Enabled: ${yes(connections.obj("nfc"), "enabled")}", connections.obj("nfc"))
                    MetadataRow("Infrared", "Emitter: ${yes(connections.obj("infrared"), "present")}", connections.obj("infrared"))
                    MetadataRow("Haptics", "${inventory.obj("hapticDetails").value("vibratorCount")} vibrators", inventory.obj("hapticDetails"))
                    Text("Radio and biometric detection does not imply PUB provides Wi-Fi, cellular or biometric control APIs.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                val device = inventory.obj("device")
                InventorySection("Display & device", "${device.value("manufacturer")} ${device.value("model")}") {
                    Text("Android ${device.value("android")} · API ${device.value("sdk")}")
                    Text("CPU: ${device.value("availableCpuCores")} available cores · ${device.array("cpuAbis").joinToString { it.asString }}")
                    val memory = device["totalMemoryBytes"]?.asLong ?: 0
                    Text("RAM: ${String.format(java.util.Locale.ROOT, "%.1f", memory / 1073741824.0)} GiB · OpenGL ES ${device.value("openGles")}")
                    inventory.array("displays").forEach { entry -> val display = entry.asJsonObject
                        MetadataRow(display.value("name"), "${display.value("width")} × ${display.value("height")} · ${display.value("refreshHz")} Hz · HDR ${display.array("hdrTypes").joinToString { it.asString }.ifEmpty { "not reported" }}", display)
                    }
                }
            }
            item {
                InventorySection("Android hardware features", "${inventory.array("hardwareFeatures").size()} declared features") {
                    Text("Android's feature declarations describe availability, not current permissions or a complete physical component list.")
                    inventory.array("hardwareFeatures").forEach { entry -> val feature = entry.asJsonObject
                        Text("${feature.value("name").removePrefix("android.hardware.")} · version ${feature.value("version")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text(inventory.value("privacy"), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun InventorySection(title: String, summary: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PubTextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) { Text("${if (open) "▾" else "▸"} $title", style = MaterialTheme.typography.titleMedium) }
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            if (open) content()
        }
    }
}
@Composable
private fun MetadataRow(title: String, summary: String, details: JsonObject) {
    var open by remember(title) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(summary, style = MaterialTheme.typography.bodySmall)
        PubTextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Show details") }
        if (open) details.entrySet().forEach { (key, value) -> Text("${readable(key)}: ${renderValue(value)}", style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
    }
}
private fun readable(value: String) = value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
private fun renderValue(value: JsonElement): String = when {
    value.isJsonNull -> "Not reported"
    value.isJsonPrimitive -> value.asString
    value.isJsonArray -> value.asJsonArray.joinToString("; ") { renderValue(it) }.ifEmpty { "None reported" }
    else -> value.asJsonObject.entrySet().joinToString(", ") { "${readable(it.key)} ${renderValue(it.value)}" }
}
private fun JsonObject.value(key: String, fallback: String = "Not reported") = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
private fun JsonObject.obj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
private fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
private fun yes(value: JsonObject, key: String) = when (value.value(key)) { "true" -> "yes"; "false" -> "no"; else -> "not reported" }
