package app.pub

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.hardware.HardwareInventory
import com.google.gson.JsonElement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareInventoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun enumeratesEveryPublicStaticAndDynamicSensorWithoutChangingPermissions() {
        val permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACTIVITY_RECOGNITION)
        val before = permissions.associateWith { context.checkSelfPermission(it) }
        val manager = context.getSystemService(SensorManager::class.java)
        val static = manager.getSensorList(Sensor.TYPE_ALL)
        val dynamic = manager.getDynamicSensorList(Sensor.TYPE_ALL)
        val inventory = HardwareInventory.inspect(context)
        val sensors = inventory.getAsJsonObject("sensorInventory")
        assertEquals(static.size, sensors["staticCount"].asInt)
        assertEquals(dynamic.size, sensors["dynamicCount"].asInt)
        val listed = sensors.getAsJsonArray("items")
        assertEquals(static.size + dynamic.size, listed.size())
        val expectedTypes = (static + dynamic).groupingBy { it.type }.eachCount()
        assertEquals(expectedTypes, listed.groupingBy { it.asJsonObject["type"].asInt }.eachCount())
        assertTrue(listed.all { it.asJsonObject.has("pubSupported") && it.asJsonObject.has("pubDefault") })
        assertEquals(before, permissions.associateWith { context.checkSelfPermission(it) })
        assertNoSensitiveFields(inventory)
    }

    @Test fun generationDescriptionIsBoundedAndExcludesLocalAccessoryMetadata() {
        val local = HardwareInventory.inspect(context) // Also invalidates the short static cache.
        val description = HardwareInventory.describe(context)
        assertTrue(description.toString().toByteArray(Charsets.UTF_8).size <= HardwareInventory.DESCRIPTION_MAX_BYTES)
        assertEquals(local.getAsJsonObject("sensorInventory")["staticCount"].asInt, description["staticSensorCount"].asInt)
        listOf("connections", "endpoints", "pairedDevices", "attachedDevices", "product", "productName").forEach {
            assertFalse("Local-only field leaked: $it", containsKey(description, it))
        }
        assertTrue(description.has("metadataTruncated"))
        assertTrue(description.getAsJsonArray("sensors").all { it.isJsonPrimitive })
        assertNoSensitiveFields(description)
        // Callers cannot mutate the cached generation context used by subsequent projects.
        description.addProperty("injectedAccessoryName", "Private headset")
        assertFalse(HardwareInventory.describe(context).has("injectedAccessoryName"))
    }

    private fun assertNoSensitiveFields(value: JsonElement) {
        listOf("serial", "serialNumber", "mac", "macAddress", "address", "ssid", "bssid", "latitude", "longitude", "readings", "sensorId", "uniqueId", "deviceName")
            .forEach { assertFalse("Unexpected sensitive field: $it", containsKey(value, it)) }
    }
    private fun containsKey(value: JsonElement, key: String): Boolean = when {
        value.isJsonObject -> value.asJsonObject.entrySet().any { it.key.equals(key, ignoreCase = true) || containsKey(it.value, key) }
        value.isJsonArray -> value.asJsonArray.any { containsKey(it, key) }
        else -> false
    }
}
