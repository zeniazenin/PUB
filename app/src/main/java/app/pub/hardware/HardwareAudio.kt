package app.pub.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

/** All operations run on the adapter's main handler. No capture or route change occurs during construction. */
internal class HardwareAudio(
    private val context: Context,
    private val main: Handler,
    private val chooseDevice: (List<DeviceChoice>, (String?) -> Unit) -> Unit
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    @Volatile private var closed = false
    private var tone: ToneGenerator? = null
    private var outputGeneration = 0L
    private var outputDeadline: Runnable? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private data class Speech(val text: String, val language: Locale, val rate: Float, val pitch: Float, val generation: Long, val emit: (String, JsonObject) -> Unit)
    private var pendingSpeech: Speech? = null
    private var activeSpeech: Speech? = null
    private var routeGeneration = 0L
    private var routeDeadline: Runnable? = null
    private var originalMode: Int? = null
    @Volatile private var activeOutputId: Int? = null
    private var pendingOutputId: Int? = null
    private var pendingRouteError: ((String) -> Unit)? = null
    private var routeRequiresSelection = false
    private var routeEmitter: ((String, JsonObject) -> Unit)? = null
    private var chooserPending = false
    private var routeCallbacksRegistered = false
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = checkActiveRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = checkActiveRoute()
    }
    private val communicationListener = AudioManager.OnCommunicationDeviceChangedListener { main.post { checkActiveRoute() } }

    /** Null if the route is absent or changed. Addresses are used only internally, never emitted. */
    val preferredAudioInput: AudioDeviceInfo?
        @SuppressLint("MissingPermission") get() {
            if (closed || !hasBluetoothPermission()) return null
            return runCatching {
                val id = activeOutputId ?: return@runCatching null
                val selected = manager?.communicationDevice?.takeIf { it.id == id } ?: return@runCatching null
                matchingInput(selected)
            }.getOrNull()
        }

    fun handle(operation: String, args: JsonObject, emit: (String, JsonObject) -> Unit) {
        if (closed) return
        when (operation) {
            "tone", "speak" -> {
                val generation = outputGeneration
                prepareMicrophone({
                    if (!closed && generation == outputGeneration) try {
                        if (operation == "tone") tone(args, emit) else speak(args, emit)
                    } catch (_: IllegalArgumentException) {
                        emit("error", PlatformHardware.error("invalid_arguments", "Invalid audio arguments."))
                    } catch (_: Exception) {
                        emit("error", PlatformHardware.error("unavailable", "Audio playback is unavailable."))
                    }
                }, { message -> emit("error", PlatformHardware.error("route_unavailable", message)) })
            }
            "stop" -> { stopOutput(); emit("audio.stopped", JsonObject()) }
            "devices" -> listDevices(emit)
            "route" -> selectRoute(emit)
            "routeReset" -> { stopOutput(); resetRoute(); emit("audio.routeReset", JsonObject()) }
            else -> throw IllegalArgumentException("Unsupported audio operation")
        }
    }
    private fun tone(args: JsonObject, emit: (String, JsonObject) -> Unit) {
        val name = PlatformHardware.optionalText(args, "tone", 12) ?: "beep"
        val toneType = when (name) {
            "beep" -> ToneGenerator.TONE_PROP_BEEP
            "ack" -> ToneGenerator.TONE_PROP_ACK
            "error" -> ToneGenerator.TONE_PROP_NACK
            else -> if (name.matches(Regex("digit[0-9]"))) ToneGenerator.TONE_DTMF_0 + name.last().digitToInt() else throw IllegalArgumentException("Unsupported tone")
        }
        val duration = PlatformHardware.integer(args, "durationMs", 250, 50, 3000)
        val volume = PlatformHardware.integer(args, "volume", 70, 0, 100)
        stopOutput()
        val generation = outputGeneration
        val generator = ToneGenerator(if (activeOutputId != null) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC, volume)
        tone = generator
        if (!generator.startTone(toneType, duration)) {
            stopOutput(); emit("error", PlatformHardware.error("unavailable", "The tone could not be played.")); return
        }
        emit("audio.started", JsonObject().apply { addProperty("kind", "tone"); addProperty("durationMs", duration) })
        val finish = Runnable {
            if (!closed && generation == outputGeneration) {
                stopOutput(); emit("audio.done", JsonObject().apply { addProperty("kind", "tone") })
            }
        }
        outputDeadline = finish; main.postDelayed(finish, duration.toLong() + 50)
    }
    private fun speak(args: JsonObject, emit: (String, JsonObject) -> Unit) {
        val text = PlatformHardware.requiredText(args, "text", 500)
        val languageTag = PlatformHardware.optionalText(args, "language", 35)
        val locale = if (languageTag == null) Locale.getDefault() else Locale.forLanguageTag(languageTag)
        require(locale.language.isNotBlank())
        val rate = PlatformHardware.number(args, "rate", 1.0, .5, 2.0).toFloat()
        val pitch = PlatformHardware.number(args, "pitch", 1.0, .5, 2.0).toFloat()
        stopOutput()
        val speech = Speech(text, locale, rate, pitch, outputGeneration, emit)
        pendingSpeech = speech
        val expiry = Runnable {
            if (!closed && speech.generation == outputGeneration) {
                stopOutput(); emit("error", PlatformHardware.error("timeout", "Speech stopped at the 15-second limit."))
            }
        }
        outputDeadline = expiry; main.postDelayed(expiry, 15_000)
        if (ttsReady) startPendingSpeech()
        else if (tts == null) {
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status -> main.post {
                if (closed || tts !== engine) return@post
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = speechEvent(id, "audio.started")
                        override fun onDone(id: String?) = speechEvent(id, "audio.done")
                        @Deprecated("Android legacy callback")
                        override fun onError(id: String?) = speechEvent(id, "error")
                        override fun onError(id: String?, errorCode: Int) = speechEvent(id, "error")
                    })
                    startPendingSpeech()
                } else {
                    val pending = pendingSpeech; pendingSpeech = null
                    engine?.shutdown(); tts = null; ttsReady = false
                    stopOutput()
                    pending?.emit?.invoke("error", PlatformHardware.error("unavailable", "Text-to-speech could not start. Check Android speech settings."))
                }
            } }
            tts = engine
        }
    }
    private fun startPendingSpeech() {
        val speech = pendingSpeech ?: return
        if (closed || speech.generation != outputGeneration) { pendingSpeech = null; return }
        val engine = tts ?: return
        pendingSpeech = null
        try {
            val available = engine.voices.orEmpty().filter {
                !it.isNetworkConnectionRequired && it.locale.language == speech.language.language &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            val voice = available.firstOrNull { it.locale.country == speech.language.country } ?: available.firstOrNull()
            if (voice == null || engine.setVoice(voice) == TextToSpeech.ERROR) {
                stopOutput(); speech.emit("error", PlatformHardware.error("unavailable", "No installed offline voice is available for this language.")); return
            }
            engine.setSpeechRate(speech.rate); engine.setPitch(speech.pitch)
            engine.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(if (activeOutputId != null) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA).build())
            activeSpeech = speech
            if (engine.speak(speech.text, TextToSpeech.QUEUE_FLUSH, Bundle(), speech.generation.toString()) == TextToSpeech.ERROR) {
                stopOutput(); speech.emit("error", PlatformHardware.error("unavailable", "Speech could not be played."))
            }
        } catch (_: Exception) {
            stopOutput(); speech.emit("error", PlatformHardware.error("unavailable", "Offline speech is unavailable."))
        }
    }
    private fun speechEvent(id: String?, event: String) {
        main.post {
            val speech = activeSpeech ?: return@post
            if (closed || id != speech.generation.toString() || speech.generation != outputGeneration) return@post
            if (event != "audio.started") stopOutput()
            if (event == "error") speech.emit("error", PlatformHardware.error("unavailable", "Speech playback failed."))
            else speech.emit(event, JsonObject().apply { addProperty("kind", "speech"); addProperty("language", speech.language.toLanguageTag()) })
        }
    }
    private fun stopOutput() {
        outputGeneration++
        outputDeadline?.let(main::removeCallbacks); outputDeadline = null
        runCatching { tone?.stopTone(); tone?.release() }; tone = null
        pendingSpeech = null; activeSpeech = null
        runCatching { tts?.stop() }
    }

    @SuppressLint("MissingPermission")
    private fun listDevices(emit: (String, JsonObject) -> Unit) {
        requireBluetoothPermission()
        val devices = manager?.availableCommunicationDevices.orEmpty()
        emit("audio.devices", JsonObject().apply { add("devices", JsonArray().apply {
            devices.take(32).forEach { device -> add(safeDevice(device)) }
        }) })
    }
    @SuppressLint("MissingPermission")
    private fun selectRoute(emit: (String, JsonObject) -> Unit) {
        requireBluetoothPermission()
        val audioManager = manager ?: run { emit("error", PlatformHardware.error("unavailable", "Audio routing is unavailable.")); return }
        if (chooserPending || pendingOutputId != null) { emit("error", PlatformHardware.error("busy", "Finish the current audio-device choice first.")); return }
        if (originalMode == null && audioManager.mode != AudioManager.MODE_NORMAL) {
            emit("error", PlatformHardware.error("busy", "Another call or communication session is using audio routing.")); return
        }
        val devices = audioManager.availableCommunicationDevices.take(32)
        if (devices.isEmpty()) { emit("error", PlatformHardware.error("unavailable", "No communication audio devices are available.")); return }
        chooserPending = true
        val generation = ++routeGeneration
        chooseDevice(devices.map { device ->
            val product = device.productName?.toString()?.take(80).orEmpty()
            val generic = deviceLabel(device.type)
            DeviceChoice(deviceKey(device), if (product.isBlank() || product.equals(generic, true)) generic else "$product · $generic")
        }) { chosen -> main.post {
            if (closed || generation != routeGeneration || !chooserPending) return@post
            chooserPending = false
            if (chosen == null) { emit("error", PlatformHardware.error("cancelled", "Audio-device selection was cancelled.")); return@post }
            try {
                requireBluetoothPermission()
                val selected = audioManager.availableCommunicationDevices.firstOrNull { deviceKey(it) == chosen }
                if (selected == null) { loseRoute("The selected audio device disconnected."); emit("error", PlatformHardware.error("unavailable", "The selected audio device disconnected.")); return@post }
                stopOutput()
                activeOutputId = selected.id
                routeRequiresSelection = false
                routeEmitter = emit
                activateRoute(selected, {
                    emit("audio.route", safeDevice(selected).apply { addProperty("inputAvailable", true) })
                }, { message -> emit("error", PlatformHardware.error("route_unavailable", message)) })
            } catch (_: SecurityException) {
                loseRoute("Bluetooth access ended.")
                emit("error", PlatformHardware.error("permission_denied", "Android has not granted Bluetooth/audio routing access."))
            } catch (_: Exception) {
                loseRoute("Audio routing is unavailable. Choose a device again.")
                emit("error", PlatformHardware.error("unavailable", "Audio routing is unavailable."))
            }
        } }
    }

    /** Reacquires a deliberately selected route after Android's idle communication-mode demotion. */
    @SuppressLint("MissingPermission")
    fun prepareMicrophone(onReady: (AudioDeviceInfo?) -> Unit, onError: (String) -> Unit) {
        if (closed) return
        if (chooserPending || pendingOutputId != null) { onError("Audio-device preparation is already in progress."); return }
        if (routeRequiresSelection) { onError("The selected audio device disconnected. Choose a device again or reset audio routing."); return }
        val id = activeOutputId
        if (id == null) { onReady(null); return }
        if (!hasBluetoothPermission()) { loseRoute("Bluetooth access ended."); onError("Bluetooth access is required for the selected device."); return }
        val selected = runCatching { manager?.availableCommunicationDevices?.firstOrNull { it.id == id } }.getOrNull()
        if (selected == null) { loseRoute("The selected audio device disconnected."); onError("The selected audio device disconnected. Choose a device again."); return }
        activateRoute(selected, onReady, onError)
    }

    @SuppressLint("MissingPermission")
    private fun activateRoute(selected: AudioDeviceInfo, onReady: (AudioDeviceInfo) -> Unit, onError: (String) -> Unit) {
        val audioManager = manager ?: run { onError("Audio routing is unavailable."); return }
        if (pendingOutputId != null) { onError("Audio-device preparation is already in progress."); return }
        if (audioManager.mode != AudioManager.MODE_NORMAL && !(originalMode != null && audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)) {
            onError("Another call or communication session is using audio routing."); return
        }
        val generation = ++routeGeneration
        pendingOutputId = selected.id
        pendingRouteError = onError
        fun fail(message: String) {
            if (closed || generation != routeGeneration) return
            routeDeadline?.let(main::removeCallbacks); routeDeadline = null
            pendingOutputId = null; pendingRouteError = null
            releaseNativeRoute()
            onError(message)
        }
        try {
            requireBluetoothPermission()
            if (originalMode == null) originalMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            ensureRouteCallbacks()
            if (!audioManager.setCommunicationDevice(selected)) { fail("Android could not select that audio device."); return }
            val deadline = SystemClock.elapsedRealtime() + 5000
            lateinit var check: Runnable
            check = Runnable {
                if (closed || generation != routeGeneration || pendingOutputId != selected.id) return@Runnable
                if (!hasBluetoothPermission()) { loseRoute("Bluetooth access ended."); return@Runnable }
                val available = runCatching { audioManager.availableCommunicationDevices.any { it.id == selected.id } }.getOrDefault(false)
                if (!available) { loseRoute("The selected audio device disconnected."); return@Runnable }
                val current = runCatching { audioManager.communicationDevice }.getOrNull()
                val input = if (current != null && current.id == selected.id) runCatching { matchingInput(current) }.getOrNull() else null
                if (current != null && current.id == selected.id && input != null && audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    pendingOutputId = null; pendingRouteError = null; routeDeadline = null
                    onReady(input)
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    fail("The selected audio device did not become ready. Reconnect it and try again.")
                } else main.postDelayed(check, 100)
            }
            routeDeadline = check; main.post(check)
        } catch (_: SecurityException) {
            fail("Android has not granted Bluetooth/audio routing access.")
        } catch (_: Exception) {
            fail("Audio routing is unavailable.")
        }
    }

    private fun ensureRouteCallbacks() {
        if (routeCallbacksRegistered) return
        manager?.registerAudioDeviceCallback(deviceCallback, main)
        manager?.addOnCommunicationDeviceChangedListener(context.mainExecutor, communicationListener)
        routeCallbacksRegistered = true
    }
    @SuppressLint("MissingPermission")
    private fun checkActiveRoute() {
        val id = activeOutputId ?: return
        if (closed) return
        // The system can demote an idle communication route while the endpoint stays connected.
        // Keep the explicit choice; prepareMicrophone reacquires it before any new capture/output.
        val present = hasBluetoothPermission() && runCatching {
            manager?.availableCommunicationDevices?.any { it.id == id } == true
        }.getOrDefault(false)
        if (!present) loseRoute("The selected audio device disconnected or Bluetooth access ended.")
    }
    private fun loseRoute(message: String) {
        val notify = routeEmitter
        val pendingError = pendingRouteError
        stopOutput()
        resetRoute()
        routeRequiresSelection = true
        notify?.invoke("audio.routeLost", PlatformHardware.error("route_lost", message))
        pendingError?.invoke(message)
    }
    @SuppressLint("MissingPermission")
    private fun matchingInput(output: AudioDeviceInfo): AudioDeviceInfo? {
        val inputs = manager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
        val expectedType = when (output.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioDeviceInfo.TYPE_BUILTIN_MIC
            else -> output.type
        }
        val matching = inputs.filter { it.type == expectedType }
        val address = output.address
        if (expectedType == AudioDeviceInfo.TYPE_BUILTIN_MIC) return matching.firstOrNull()
        return if (address.isNotBlank()) matching.firstOrNull { it.address == address } ?: matching.singleOrNull { it.address.isBlank() }
        else matching.singleOrNull()
    }
    private fun resetRoute() {
        routeGeneration++; chooserPending = false; pendingOutputId = null; pendingRouteError = null
        activeOutputId = null; routeEmitter = null; routeRequiresSelection = false
        routeDeadline?.let(main::removeCallbacks); routeDeadline = null
        releaseNativeRoute()
    }
    private fun releaseNativeRoute() {
        if (originalMode != null) {
            runCatching { manager?.clearCommunicationDevice() }
            val oldMode = originalMode!!
            runCatching { manager?.let { if (it.mode == AudioManager.MODE_IN_COMMUNICATION) it.mode = oldMode } }
            originalMode = null
        }
    }
    fun close() {
        if (closed) return
        closed = true
        stopOutput(); resetRoute()
        if (routeCallbacksRegistered) {
            runCatching { manager?.unregisterAudioDeviceCallback(deviceCallback) }
            runCatching { manager?.removeOnCommunicationDeviceChangedListener(communicationListener) }
            routeCallbacksRegistered = false
        }
        runCatching { tts?.shutdown() }; tts = null; ttsReady = false
    }
    private fun hasBluetoothPermission() = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun requireBluetoothPermission() { if (!hasBluetoothPermission()) throw SecurityException("Bluetooth access required") }
    private fun deviceKey(device: AudioDeviceInfo) = "audio-${device.id}"
    private fun safeDevice(device: AudioDeviceInfo) = JsonObject().apply {
        addProperty("id", deviceKey(device)); addProperty("type", device.type); addProperty("label", deviceLabel(device.type))
    }
    private fun deviceLabel(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Phone speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio device"
        else -> "Audio device"
    }
}
