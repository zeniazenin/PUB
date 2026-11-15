package app.pub.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import app.pub.audio.MicrophoneCapture
import app.pub.core.PermissionPolicy
import app.pub.core.PermissionPolicy.Decision
import app.pub.core.PermissionPolicy.Rule
import app.pub.core.PitchDetector

/** The host owns identity and grants. The script can request, never authorize, capture. */
class PermissionBroker(
    private val context: Context,
    private val projectId: () -> String = { "guitar-tuner" },
    private val prepareInput: ((android.media.AudioDeviceInfo?) -> Unit, (String) -> Unit) -> Unit = { ready, _ -> ready(null) },
    private val declared: () -> Boolean,
    private val requestUser: () -> Unit,
    private val requestAndroid: () -> Unit,
    private val status: (Boolean, String) -> Unit,
    private val pitch: (PitchDetector.Result, Int) -> Unit
) {
    private val prefs = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)
    private val capture = MicrophoneCapture(context)
    private val handler = Handler(Looper.getMainLooper())
    var foreground = false
        private set
    var active = false
        private set
    private var sessionGrant = false
    private var preparing = false
    private var captureEpoch = 0L
    var globalBlocked: Boolean
        get() = prefs.getBoolean("microphone.blocked", false)
        set(value) { prefs.edit().putBoolean("microphone.blocked", value).apply(); policyChanged() }
    var globalDefault: Rule
        get() = readRule("microphone.default", Rule.ASK)
        set(value) { require(value == Rule.ASK || value == Rule.ALLOW); prefs.edit().putString("microphone.default", value.name).apply(); policyChanged() }
    var appRule: Rule
        get() = readRule("${projectId()}.microphone", Rule.INHERIT)
        set(value) { prefs.edit().putString("${projectId()}.microphone", value.name).apply(); sessionGrant = false; policyChanged() }
    fun projectChanged() { sessionGrant = false; stop("Project changed · microphone off") }
    private fun readRule(key: String, fallback: Rule) = runCatching { Rule.valueOf(prefs.getString(key, fallback.name)!!) }.getOrDefault(fallback)
    fun decision(): Decision = PermissionPolicy.evaluate(declared(), globalBlocked, globalDefault, appRule, sessionGrant,
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED, foreground)
    private val recheck = object : Runnable {
        override fun run() {
            if (!active) return
            if (decision() != Decision.ALLOW) stop("Microphone access ended")
            else handler.postDelayed(this, 250)
        }
    }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { sessionGrant = false; stop("Paused · reopen and start listening") }
    }
    fun start() {
        if (active || preparing) return
        when (decision()) {
            Decision.DENY -> status(false, "Microphone blocked · review Permissions")
            Decision.ASK_USER -> requestUser()
            Decision.ASK_ANDROID -> requestAndroid()
            Decision.ALLOW -> {
                preparing = true
                val epoch = ++captureEpoch
                // Preparation is not a terminal stop. Scripts use listening=false to clear their
                // requested-listening state, so emit status only when capture starts or truly ends.
                prepareInput({ input ->
                    if(epoch == captureEpoch && preparing && decision() == Decision.ALLOW) {
                        preparing = false
                        active = true
                        val label = when(input?.type) {
                            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth headset"
                            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                            android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB microphone"
                            else -> "Phone microphone"
                        }
                        status(true, "Microphone in use · $label")
                        capture.start({ result, sampleRate ->
                            if (active && epoch == captureEpoch && decision() == Decision.ALLOW) pitch(result, sampleRate)
                            else stop("Microphone access ended")
                        }, { message -> if(epoch == captureEpoch) stop(message) }, input)
                        handler.postDelayed(recheck, 250)
                    } else if(epoch == captureEpoch) stop("Microphone access ended")
                }, { message -> if(epoch == captureEpoch) stop(message) })
            }
        }
    }
    fun allowOnce() { sessionGrant = true; start() }
    fun allowApp() { appRule = Rule.ALLOW; start() }
    fun denied() { status(false, "Microphone access was not granted") }
    fun androidResult(granted: Boolean) { if (granted) start() else denied() }
    private fun policyChanged() {
        if ((active || preparing) && decision() != Decision.ALLOW) stop("Microphone access revoked")
    }
    fun stop(message: String = "Stopped") {
        captureEpoch++;preparing = false
        active = false
        handler.removeCallbacks(recheck)
        capture.stop()
        status(false, message)
    }
    fun close() { captureEpoch++;preparing=false;active=false;handler.removeCallbacksAndMessages(null); capture.close() }
}
