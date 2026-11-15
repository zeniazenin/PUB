package app.pub.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.media.AudioDeviceInfo
import android.os.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Native adapters only. The owning broker must authorize every operation and close on background/revocation. */
class PlatformHardware(
    context: Context,
    chooseDevice: (List<DeviceChoice>, (String?) -> Unit) -> Unit = { _, callback -> callback(null) }
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sensors = this.context.getSystemService(SensorManager::class.java)
    private val locations = this.context.getSystemService(LocationManager::class.java)
    private val cameras = this.context.getSystemService(CameraManager::class.java)
    private val vibrator = this.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val infrared = this.context.getSystemService(ConsumerIrManager::class.java)
    private val audio = HardwareAudio(this.context, main, chooseDevice)
    private val infraredWorker = Executors.newSingleThreadExecutor()
    private val infraredBusy = AtomicBoolean(false)
    @Volatile private var closed = false
    private var lastInfraredMs = -1000L
    private var activeTorch: String? = null
    private val sensorStreams = mutableMapOf<String, SensorStream>()
    private var locationListener: LocationListener? = null
    private var locationCancellation: CancellationSignal? = null
    private var locationTimeout: Runnable? = null
    private var locationGeneration = 0L

    /** Native AudioRecord can use this after the user's explicit communication-device selection. */
    val preferredAudioInput: AudioDeviceInfo? get() = audio.preferredAudioInput

    /** Null means no explicit route preference, never a fallback from a missing chosen endpoint. */
    fun prepareMicrophone(onReady: (AudioDeviceInfo?) -> Unit, onError: (String) -> Unit) {
        onMain { if (!closed) audio.prepareMicrophone(onReady, onError) }
    }

    /** Hardware presence only: no readings, coordinates, device addresses, product names or stable identifiers. */
    fun inventory(): JsonObject = JsonObject().apply {
        add("sensors", sensorInventory())
        add("location", JsonObject().apply {
            addProperty("available", context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION))
            addProperty("gps", context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))
            addProperty("network", context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK))
        })
        addProperty("haptics", runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false))
        addProperty("torch", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
        addProperty("audioOutput", context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT))
        addProperty("audioRouting", true)
        addProperty("speechOutput", true) // Actual offline language/voice support is checked on first speak.
        addProperty("battery", true)
        addProperty("infrared", runCatching { infrared?.hasIrEmitter() == true }.getOrDefault(false))
    }

    fun handle(capability: String, operation: String, args: JsonObject, emit: (String, JsonObject) -> Unit) {
        if (closed) return
        val safeArgs = args.deepCopy()
        val safeEmit: (String, JsonObject) -> Unit = { event, data ->
            onMain { if (!closed) runCatching { emit(event, data) } }
        }
        onMain {
            if (closed) return@onMain
            try {
                when (capability) {
                    "sensors" -> handleSensors(operation, safeArgs, safeEmit)
                    "location" -> handleLocation(operation, safeArgs, safeEmit)
                    "haptics" -> handleHaptics(operation, safeArgs, safeEmit)
                    "torch" -> handleTorch(operation, safeArgs, safeEmit)
                    "audio" -> audio.handle(operation, safeArgs, safeEmit)
                    "battery" -> { require(operation == "get"); battery(safeEmit) }
                    "infrared" -> handleInfrared(operation, safeArgs, safeEmit)
                    else -> throw IllegalArgumentException("Unsupported hardware capability")
                }
            } catch (_: SecurityException) {
                safeEmit("error", error("permission_denied", "Android has not granted access to this hardware."))
            } catch (_: IllegalArgumentException) {
                safeEmit("error", error("invalid_arguments", "Invalid arguments for $capability.$operation."))
            } catch (_: Exception) {
                safeEmit("error", error("unavailable", "This hardware is unavailable or in use."))
            }
        }
    }

    private data class SensorSpec(val type: Int, val unit: String)
    private data class SensorStream(val listener: SensorEventListener, val expiry: Runnable)
    private val sensorSpecs = linkedMapOf(
        "accelerometer" to SensorSpec(Sensor.TYPE_ACCELEROMETER, "m/s²"),
        "accelerometer_uncalibrated" to SensorSpec(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, "m/s²"),
        "gyroscope" to SensorSpec(Sensor.TYPE_GYROSCOPE, "rad/s"),
        "gyroscope_uncalibrated" to SensorSpec(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, "rad/s"),
        "magnetic_field" to SensorSpec(Sensor.TYPE_MAGNETIC_FIELD, "µT"),
        "magnetic_field_uncalibrated" to SensorSpec(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, "µT"),
        "gravity" to SensorSpec(Sensor.TYPE_GRAVITY, "m/s²"),
        "linear_acceleration" to SensorSpec(Sensor.TYPE_LINEAR_ACCELERATION, "m/s²"),
        "rotation_vector" to SensorSpec(Sensor.TYPE_ROTATION_VECTOR, "rotation_vector"),
        "game_rotation_vector" to SensorSpec(Sensor.TYPE_GAME_ROTATION_VECTOR, "rotation_vector"),
        "geomagnetic_rotation_vector" to SensorSpec(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, "rotation_vector"),
        "hinge_angle" to SensorSpec(Sensor.TYPE_HINGE_ANGLE, "degrees"),
        "light" to SensorSpec(Sensor.TYPE_LIGHT, "lux"),
        "proximity" to SensorSpec(Sensor.TYPE_PROXIMITY, "cm"),
        "pressure" to SensorSpec(Sensor.TYPE_PRESSURE, "hPa"),
        "relative_humidity" to SensorSpec(Sensor.TYPE_RELATIVE_HUMIDITY, "%"),
        "ambient_temperature" to SensorSpec(Sensor.TYPE_AMBIENT_TEMPERATURE, "°C"),
        "step_counter" to SensorSpec(Sensor.TYPE_STEP_COUNTER, "steps_since_boot"),
        "step_detector" to SensorSpec(Sensor.TYPE_STEP_DETECTOR, "step")
    )
    private fun sensorInventory() = JsonArray().apply {
        sensorSpecs.forEach { (name, spec) ->
            if (runCatching { sensors?.getDefaultSensor(spec.type) != null }.getOrDefault(false)) add(JsonObject().apply {
                addProperty("sensor", name); addProperty("unit", spec.unit)
                addProperty("requiresActivityRecognition", name.startsWith("step_"))
            })
        }
    }
    private fun handleSensors(operation: String, args: JsonObject, emit: (String, JsonObject) -> Unit) {
        when (operation) {
            "list" -> emit("sensors.list", JsonObject().apply { add("sensors", sensorInventory()) })
            "stop" -> {
                val name = optionalText(args, "sensor", 40)
                if (name != null) { require(name in sensorSpecs); stopSensor(name) }
                else sensorStreams.keys.toList().forEach(::stopSensor)
                emit("sensors.stopped", JsonObject().apply { if (name != null) addProperty("sensor", name) })
            }
            "start" -> {
                val name = requiredText(args, "sensor", 40)
                val spec = sensorSpecs[name] ?: throw IllegalArgumentException("Unsupported sensor")
                val sensor = sensors?.getDefaultSensor(spec.type)
                if (sensor == null) { emit("error", error("unavailable", "This sensor is not present on this phone.")); return }
                if (name.startsWith("step_")) requirePermission(Manifest.permission.ACTIVITY_RECOGNITION)
                val rate = number(args, "rateHz", 10.0, 1.0, 10.0)
                val duration = integer(args, "durationMs", 300_000, 1000, 600_000).toLong()
                if (sensorStreams.size >= 4 && name !in sensorStreams) {
                    emit("error", error("limit", "At most four sensors can run at once.")); return
                }
                stopSensor(name)
                val intervalNanos = (1_000_000_000.0 / rate).toLong()
                var lastEvent = 0L
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (closed || sensorStreams[name]?.listener !== this) return
                        if (name.startsWith("step_") && !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                            stopSensor(name); emit("error", error("permission_denied", "Activity recognition access ended.")); return
                        }
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (now - lastEvent < intervalNanos || event.values.take(6).any { !it.isFinite() }) return
                        lastEvent = now
                        emit("sensor", JsonObject().apply {
                            addProperty("sensor", name); addProperty("unit", spec.unit)
                            addProperty("timestampNs", event.timestamp); addProperty("accuracy", event.accuracy)
                            add("values", JsonArray().apply { event.values.take(6).forEach { add(it) } })
                        })
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                val expiry = Runnable {
                    if (!closed && sensorStreams[name]?.listener === listener) {
                        stopSensor(name)
                        emit("sensors.stopped", JsonObject().apply { addProperty("sensor", name); addProperty("reason", "duration_elapsed") })
                    }
                }
                if (sensors!!.registerListener(listener, sensor, (1_000_000.0 / rate).toInt(), 0, main)) {
                    sensorStreams[name] = SensorStream(listener, expiry)
                    main.postDelayed(expiry, duration)
                    emit("sensors.started", JsonObject().apply { addProperty("sensor", name); addProperty("rateHz", rate); addProperty("durationMs", duration) })
                } else emit("error", error("unavailable", "The sensor could not be started."))
            }
            else -> throw IllegalArgumentException("Unsupported sensors operation")
        }
    }
    private fun stopSensor(name: String) {
        sensorStreams.remove(name)?.let { stream -> sensors?.unregisterListener(stream.listener); main.removeCallbacks(stream.expiry) }
    }

    @SuppressLint("MissingPermission") // The broker authorizes; adapter also checks current Android permission.
    private fun handleLocation(operation: String, args: JsonObject, emit: (String, JsonObject) -> Unit) {
        if (operation == "stop") { stopLocation(); emit("location.stopped", JsonObject()); return }
        require(operation == "start" || operation == "current")
        checkLocationPermission()
        val manager = locations ?: run { emit("location.unavailable", error("unavailable", "Location hardware is not available.")); return }
        if (!manager.isLocationEnabled) { emit("location.unavailable", error("disabled", "Location is turned off in Android Settings.")); return }
        val requested = optionalText(args, "provider", 12) ?: "auto"
        require(requested in setOf("auto", "gps", "network"))
        val candidates = when (requested) {
            "gps" -> listOf(LocationManager.GPS_PROVIDER)
            "network" -> listOf(LocationManager.NETWORK_PROVIDER)
            else -> if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        val provider = candidates.firstOrNull { name -> manager.allProviders.contains(name) && manager.isProviderEnabled(name) }
        if (provider == null) { emit("location.unavailable", error("unavailable", "No requested location provider is enabled.")); return }
        val interval = integer(args, "intervalMs", 1000, 1000, 60_000).toLong()
        val duration = integer(args, "durationMs", 300_000, 1000, 600_000).toLong()
        val timeout = integer(args, "timeoutMs", 20_000, 1000, 30_000).toLong()
        stopLocation()
        val generation = locationGeneration
        val fineAtStart = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (operation == "current") {
            val cancellation = CancellationSignal()
            locationCancellation = cancellation
            val deadline = Runnable {
                if (!closed && generation == locationGeneration) {
                    stopLocation(); emit("location.unavailable", error("timeout", "No location fix arrived before the timeout."))
                }
            }
            locationTimeout = deadline; main.postDelayed(deadline, timeout)
            manager.getCurrentLocation(provider, LocationRequest.Builder(0).setDurationMillis(timeout).setMaxUpdates(1).build(), cancellation, context.mainExecutor) { location ->
                if (closed || generation != locationGeneration) return@getCurrentLocation
                main.removeCallbacks(deadline); locationTimeout = null; locationCancellation = null
                if (location == null) emit("location.unavailable", error("unavailable", "A location fix is not available right now."))
                else emitLocation(location, fineAtStart, emit)
            }
        } else {
            var lastEmissionMs = -interval
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (closed || generation != locationGeneration || locationListener !== this) return
                    if (!hasLocationPermission()) { stopLocation(); emit("error", error("permission_denied", "Location permission was revoked.")); return }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEmissionMs < interval) return
                    lastEmissionMs = now; emitLocation(location, fineAtStart, emit)
                }
                override fun onProviderDisabled(disabledProvider: String) {
                    if (!closed && generation == locationGeneration && disabledProvider == provider) {
                        stopLocation(); emit("location.unavailable", error("disabled", "The selected location provider was disabled."))
                    }
                }
            }
            locationListener = listener
            manager.requestLocationUpdates(provider, LocationRequest.Builder(interval).setMinUpdateIntervalMillis(interval).setDurationMillis(duration).build(), context.mainExecutor, listener)
            val expiry = Runnable {
                if (!closed && generation == locationGeneration) { stopLocation(); emit("location.stopped", JsonObject().apply { addProperty("reason", "duration_elapsed") }) }
            }
            locationTimeout = expiry; main.postDelayed(expiry, duration)
            emit("location.started", JsonObject().apply {
                addProperty("provider", provider); addProperty("intervalMs", interval); addProperty("durationMs", duration)
                addProperty("approximate", !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            })
        }
    }
    private fun emitLocation(location: Location, fineAtStart: Boolean, emit: (String, JsonObject) -> Unit) {
        if (!hasLocationPermission()) { emit("error", error("permission_denied", "Location permission was revoked.")); return }
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (fineAtStart && !fine) {
            stopLocation()
            emit("location.unavailable", error("permission_changed", "Precise location access ended. Start location again to use approximate access."))
            return
        }
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        emit("location", JsonObject().apply {
            addProperty("latitude", location.latitude); addProperty("longitude", location.longitude)
            if (location.hasAccuracy() && location.accuracy.isFinite()) addProperty("accuracyMeters", location.accuracy)
            addProperty("timeMs", location.time); addProperty("elapsedRealtimeNanos", location.elapsedRealtimeNanos)
            addProperty("approximate", !fine); addProperty("mock", location.isMock)
            if (fine) {
                if (location.hasAltitude() && location.altitude.isFinite()) addProperty("altitudeMeters", location.altitude)
                if (location.hasSpeed() && location.speed.isFinite()) addProperty("speedMetersPerSecond", location.speed)
                if (location.hasBearing() && location.bearing.isFinite()) addProperty("bearingDegrees", location.bearing)
            }
        })
    }
    private fun stopLocation() {
        locationGeneration++
        locationListener?.let { runCatching { locations?.removeUpdates(it) } }; locationListener = null
        locationCancellation?.cancel(); locationCancellation = null
        locationTimeout?.let(main::removeCallbacks); locationTimeout = null
    }

    private fun handleHaptics(operation: String, args: JsonObject, emit: (String, JsonObject) -> Unit) {
        requirePermission(Manifest.permission.VIBRATE)
        if (operation == "stop") { vibrator?.cancel(); emit("haptics.stopped", JsonObject()); return }
        require(operation == "vibrate")
        if (vibrator?.hasVibrator() != true) { emit("error", error("unavailable", "This device has no vibrator.")); return }
        val duration = integer(args, "durationMs", 100, 1, 1000).toLong()
        val amplitude = integer(args, "amplitude", 128, 1, 255)
        vibrator.vibrate(VibrationEffect.createOneShot(duration, if (vibrator.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE))
        emit("haptics.done", JsonObject().apply { addProperty("durationMs", duration) })
    }
    private fun handleTorch(operation: String, args: JsonObject, emit: (String, JsonObject) -> Unit) {
        require(operation == "set"); requirePermission(Manifest.permission.CAMERA)
        val value = args.get("enabled")
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        val enabled = value.asBoolean
        if (!enabled) { stopTorch(); emit("torch.changed", JsonObject().apply { addProperty("enabled", false) }); return }
        val manager = cameras ?: run { emit("error", error("unavailable", "Camera flash is not available.")); return }
        val cameraId = manager.cameraIdList.firstOrNull {
            val c = manager.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        if (cameraId == null) { emit("error", error("unavailable", "This phone has no usable torch.")); return }
        manager.setTorchMode(cameraId, true); activeTorch = cameraId
        emit("torch.changed", JsonObject().apply { addProperty("enabled", true) })
    }
    private fun stopTorch() { activeTorch?.let { runCatching { cameras?.setTorchMode(it, false) } }; activeTorch = null }
    private fun battery(emit: (String, JsonObject) -> Unit) {
        val snapshot = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (snapshot == null) { emit("error", error("unavailable", "Battery information is unavailable.")); return }
        val level = snapshot.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = snapshot.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = snapshot.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        emit("battery", JsonObject().apply {
            if (level >= 0 && scale > 0) addProperty("percent", (100.0 * level / scale).coerceIn(0.0, 100.0))
            addProperty("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            addProperty("powerConnected", snapshot.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            if (snapshot.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) addProperty("temperatureCelsius", snapshot.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            if (snapshot.hasExtra(BatteryManager.EXTRA_VOLTAGE)) addProperty("voltageMillivolts", snapshot.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0))
        })
    }
    private fun handleInfrared(operation: String, args: JsonObject, emit: (String, JsonObject) -> Unit) {
        requirePermission(Manifest.permission.TRANSMIT_IR)
        val manager = infrared
        if (manager?.hasIrEmitter() != true) { emit("error", error("unavailable", "This phone has no infrared transmitter.")); return }
        val ranges = manager.carrierFrequencies ?: run { emit("error", error("unavailable", "Infrared carrier ranges are unavailable.")); return }
        when (operation) {
            "frequencies" -> emit("infrared.frequencies", JsonObject().apply { add("rangesHz", JsonArray().apply {
                ranges.take(64).forEach { range -> add(JsonObject().apply { addProperty("min", range.minFrequency); addProperty("max", range.maxFrequency) }) }
            }) })
            "transmit" -> {
                val frequency = integer(args, "frequencyHz", -1, 1, 1_000_000)
                require(ranges.any { frequency in it.minFrequency..it.maxFrequency })
                val parts = args.getAsJsonArray("patternUs") ?: throw IllegalArgumentException("Pattern required")
                require(parts.size() in 1..200)
                val pattern = parts.map { element ->
                    require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
                    val value = element.asDouble; require(value.isFinite() && value % 1.0 == 0.0 && value in 1.0..500_000.0); value.toInt()
                }.toIntArray()
                require(pattern.sumOf { it.toLong() } <= 500_000L)
                val now = SystemClock.elapsedRealtime()
                if (now - lastInfraredMs < 750 || !infraredBusy.compareAndSet(false, true)) {
                    emit("error", error("busy", "Wait before sending another infrared signal.")); return
                }
                lastInfraredMs = now
                infraredWorker.execute {
                    try {
                        if (!closed) {
                            requirePermission(Manifest.permission.TRANSMIT_IR)
                            manager.transmit(frequency, pattern)
                            emit("infrared.done", JsonObject().apply { addProperty("durationMicroseconds", pattern.sum()) })
                        }
                    } catch (_: Exception) { emit("error", error("unavailable", "Infrared transmission failed.")) }
                    finally { infraredBusy.set(false) }
                }
            }
            else -> throw IllegalArgumentException("Unsupported infrared operation")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        onMain {
            sensorStreams.keys.toList().forEach(::stopSensor)
            stopLocation(); stopTorch(); runCatching { vibrator?.cancel() }; audio.close()
            infraredWorker.shutdownNow()
            main.removeCallbacksAndMessages(null)
        }
    }
    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
    private fun hasPermission(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun requirePermission(permission: String) { if (!hasPermission(permission)) throw SecurityException("Permission required") }
    private fun hasLocationPermission() = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) || hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun checkLocationPermission() { if (!hasLocationPermission()) throw SecurityException("Location permission required") }

    companion object {
        internal fun error(code: String, message: String) = JsonObject().apply { addProperty("code", code); addProperty("message", message) }
        internal fun optionalText(args: JsonObject, key: String, limit: Int): String? {
            val value = args.get(key) ?: return null
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length <= limit)
            return value.asString
        }
        internal fun requiredText(args: JsonObject, key: String, limit: Int): String = optionalText(args, key, limit)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Text required")
        internal fun number(args: JsonObject, key: String, default: Double, min: Double, max: Double): Double {
            val element = args.get(key) ?: return default.also { require(it in min..max) }
            require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
            return element.asDouble.also { require(it.isFinite() && it in min..max) }
        }
        internal fun integer(args: JsonObject, key: String, default: Int, min: Int, max: Int): Int {
            val result = number(args, key, default.toDouble(), min.toDouble(), max.toDouble())
            require(result % 1.0 == 0.0); return result.toInt()
        }
    }
}
