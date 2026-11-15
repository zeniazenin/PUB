package app.pub.hardware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.pub.core.HardwareContract
import app.pub.core.PermissionPolicy.Rule
import app.pub.core.PhoneFilesContract
import com.google.gson.JsonObject

/** All identity, permission and device selection decisions stay in the native host. */
data class DeviceChoice(val id: String, val label: String)
data class HardwarePrompt(val capability: String, val operation: String)
data class DevicePicker(val choices: List<DeviceChoice>, val complete: (String?) -> Unit)

/**
 * The open app's side of the files capability: the engine, the app's scope and [openPicker], which asks the host to show
 * the system file picker (false when one is already open).
 */
class FileAccess(val files: PhoneFiles, val scope: PhoneFiles.Scope, val openPicker: (FilePick) -> Boolean)

class HardwareBroker(
    private val context: Context,
    private val projectId: String,
    private val preview: Boolean,
    private val declared: Set<String>,
    private val foreground: () -> Boolean,
    private val prompt: (HardwarePrompt?) -> Unit,
    private val requestAndroid: (Array<String>?) -> Unit,
    private val picker: (DevicePicker?) -> Unit,
    private val camera: (((JsonObject?) -> Unit)?) -> Unit,
    private val nfc: (((JsonObject?) -> Unit)?) -> Unit,
    private val emit: (JsonObject, Boolean) -> Unit,
    private val changed: () -> Unit,
    private val fileAccess: FileAccess? = null
) {
    private val prefs = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    val isClosed get() = closed
    private var pending: JsonObject? = null
    private var nativeRequest: Any? = null
    private val grants = mutableSetOf<String>()
    private val activePermissions = mutableSetOf<String>()
    private val activeCaps = mutableSetOf<String>()
    val activeCapabilities get() = if(closed) emptySet<String>() else activeCaps.toSet()
    private var platform: PlatformHardware? = null
    private var bluetooth: BluetoothHardware? = null
    private var usb: UsbHardware? = null
    private var fileSession: PhoneFiles.Session? = null
    val preferredAudioInput: AudioDeviceInfo? get() = platform?.preferredAudioInput
    fun prepareMicrophone(ready:(AudioDeviceInfo?)->Unit, failure:(String)->Unit) {
        if(closed || !foreground()) {failure("Hardware session ended.");return}
        if("audio" !in activeCaps || platform==null) {ready(null);return}
        if(!allowed("audio") || activePermissions.any { !granted(it) }) {failure("Audio device access ended. Choose a device again.");return}
        platform!!.prepareMicrophone({ input ->
            if(!closed && foreground() && allowed("audio") && activePermissions.all(::granted)) ready(input)
            else failure("Audio device access ended.")
        },failure)
    }
    private val choose: (List<DeviceChoice>, (String?) -> Unit) -> Unit = { choices, callback ->
        main.post { if (!closed && foreground()) picker(DevicePicker(choices.take(64)) { id ->
            picker(null)
            if (!closed && foreground() && activeCaps.all(::allowed) && activePermissions.all(::granted)) callback(id)
        }) }
    }
    private fun rule(key: String, fallback: Rule) = runCatching { Rule.valueOf(prefs.getString(key,fallback.name)!!) }.getOrDefault(fallback)
    private fun allowed(cap: String): Boolean {
        if (closed || !foreground() || cap !in declared || prefs.getBoolean("$cap.blocked",false)) return false
        val own=rule("$projectId.$cap",Rule.INHERIT)
        val effective=if(own==Rule.INHERIT) rule("$cap.default",Rule.ASK) else own
        return effective!=Rule.DENY && (effective==Rule.ALLOW || cap in grants)
    }
    private fun blocked(cap: String): Boolean {
        val own=rule("$projectId.$cap",Rule.INHERIT)
        return cap !in declared || prefs.getBoolean("$cap.blocked",false) || own==Rule.DENY || (own==Rule.INHERIT && rule("$cap.default",Rule.ASK)==Rule.DENY)
    }
    private fun permissions(r: JsonObject): Array<String> {
        val cap=r["capability"].asString; val op=r["operation"].asString
        return when(cap) {
            "camera", "torch" -> arrayOf(Manifest.permission.CAMERA)
            "bluetooth" -> if(op=="choose" && r["args"].asJsonObject["bondedOnly"]?.asBoolean!=true)
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN)
                else arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            "audio" -> if(op in setOf("devices","route")) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
            "location" -> if(granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)) emptyArray()
                else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)
            "sensors" -> if(r["args"].asJsonObject["sensor"]?.asString in setOf("step_counter","step_detector")) arrayOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyArray()
            else -> emptyArray()
        }
    }
    private fun granted(permission: String) = context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
    private var recheckRunning=false
    private val recheck=object:Runnable {
        override fun run() {
            if(closed) { recheckRunning=false; return }
            if(activeCaps.any { !allowed(it) } || activePermissions.any { !granted(it) } ||
                ("location" in activeCaps && !granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION))) {
                close(); changed(); return
            }
            main.postDelayed(this,250)
        }
    }
    /** Requests that open a native screen: device choosers, camera capture, audio routes, NFC reading and the file picker. */
    private fun interactive(r: JsonObject): Boolean {
        val cap=r["capability"].asString
        return r["operation"].asString in setOf("choose","capture","route") || cap=="nfc" || (cap==PhoneFilesContract.CAPABILITY && PhoneFilesContract.opensPicker(r))
    }
    fun request(request: JsonObject, userAction: Boolean) {
        HardwareContract.validate(request)
        if(closed || !foreground()) return
        val cap=request["capability"].asString; val op=request["operation"].asString
        val files=cap==PhoneFilesContract.CAPABILITY
        // Files the user already picked stay usable without a new tap once file access is allowed (an Allow rule, or
        // consent in this session), so a saved app can reopen its documents at init. Asking still needs a tap.
        val resumable=files && !interactive(request) && allowed(cap)
        if(cap !in activeCaps && !userAction && !resumable) {error(request,"gesture_required","Start hardware access from a button.");return}
        if(blocked(cap)) { error(request,"blocked","Access is blocked or was not declared by this app."); return }
        if(nativeRequest!=null && interactive(request)) { error(request,"busy","Finish the open hardware screen first.");return }
        if(pending!=null) { error(request,"busy","Finish the current permission request first."); return }
        if(interactive(request)) {
            if(!userAction) { error(request,"gesture_required","Use a button to open this hardware control."); return }
        }
        // Deleting the user's documents is never done from init or a callback.
        if(files && op=="delete" && !userAction) { error(request,"gesture_required","Delete files on the phone only from a button the user taps."); return }
        if(!allowed(cap)) {
            if(!userAction) { error(request,"gesture_required","Start hardware access from a button."); return }
            pending=request.deepCopy(); prompt(HardwarePrompt(cap,op)); return
        }
        execute(request,userAction)
    }
    fun consent(allow: Boolean, always: Boolean) {
        val r=pending ?: return
        pending=null; prompt(null)
        if(!foreground() || closed) return
        if(!allow || blocked(r["capability"].asString)) { error(r,"denied","Hardware access was not granted."); return }
        val cap=r["capability"].asString
        grants.add(cap)
        if(always && !preview) { prefs.edit().putString("$projectId.$cap",Rule.ALLOW.name).apply(); changed() }
        execute(r,userAction=true) // The user just answered the host consent dialog.
    }
    fun androidResult() {
        val r=pending ?: return
        pending=null; requestAndroid(null)
        if(!closed && foreground()) {
            if(permissions(r).all(::granted)) execute(r,userAction=true)
            else error(r,"android_permission","Android permission was not granted. Review Settings → Android app permissions.")
        }
    }
    private fun execute(r: JsonObject, userAction: Boolean) {
        val cap=r["capability"].asString; val op=r["operation"].asString
        if(!allowed(cap)) { error(r,"revoked","Access ended."); return }
        val needed=permissions(r)
        if(needed.any { !granted(it) }) {
            // An already-active capability can still need a new Android permission (for example audio
            // devices needing Nearby devices). That system prompt must also start from a button.
            if(!userAction) { error(r,"gesture_required","Use a button to allow the Android permission this needs."); return }
            pending=r; requestAndroid(needed); return
        }
        val interactive=interactive(r)
        if(interactive && nativeRequest!=null) {error(r,"busy","Finish the open hardware screen first.");return}
        val nativeToken=if(interactive) Any() else null
        if(nativeToken!=null) nativeRequest=nativeToken
        activate(cap,needed)
        val reply=replier(r,nativeToken)
        try {
            when(cap) {
                "camera" -> if(op=="list") reply("cameras",ProjectMedia(context).inventory()) else {
                    val token=nativeToken!!
                    camera { result ->
                        val accept=!closed && nativeRequest===token && allowed(cap) && permissions(r).all(::granted)
                        if(accept) {
                            nativeRequest=null;camera(null)
                            send(r,if(result==null) "cancelled" else "photo",result ?: JsonObject())
                        } else if(result!=null) {
                            result["asset"]?.asString?.let { asset -> runCatching {ProjectMedia(context).resolve(projectId,asset)?.delete()} }
                        }
                    }
                }
                "nfc" -> {
                    val token=nativeToken!!
                    nfc {result -> if(!closed && nativeRequest===token && allowed(cap)) {nativeRequest=null;nfc(null);send(r,if(result==null) "cancelled" else "tag",result ?: JsonObject())} }
                }
                "bluetooth" -> (bluetooth ?: BluetoothHardware(context,choose).also { bluetooth=it }).handle(op,r["args"].asJsonObject,reply)
                "usb" -> (usb ?: UsbHardware(context,choose).also { usb=it }).handle(op,r["args"].asJsonObject,reply)
                PhoneFilesContract.CAPABILITY -> files(r,nativeToken,reply)
                else -> (platform ?: PlatformHardware(context,choose).also { platform=it }).handle(cap,op,r["args"].asJsonObject,reply)
            }
        } catch (_: Exception) { if(nativeToken!=null && nativeRequest===nativeToken) {nativeRequest=null;picker(null)};error(r,"invalid_request","Hardware request could not be completed. Check the arguments and device availability.") }
    }
    private fun activate(cap: String, needed: Array<String>) {
        // Notify only when the active set grows: each change makes the host recompose its hardware controls.
        if (activeCaps.add(cap) or activePermissions.addAll(needed)) changed()
        // Start the 250 ms revocation check once. Rescheduling it on every request meant a stream of
        // requests closer than 250 ms apart postponed it indefinitely.
        if(!recheckRunning) { recheckRunning=true; main.postDelayed(recheck,250) }
    }
    /** Delivers results of [r] on the main thread while access still holds; an end result frees the native screen [nativeToken]. */
    private fun replier(r: JsonObject, nativeToken: Any?): (String,JsonObject)->Unit {
        val cap=r["capability"].asString
        return { event,data -> main.post {
            if(allowed(cap) && permissions(r).all(::granted)) {
                if(nativeToken!=null && nativeRequest===nativeToken && event in setOf("error","cancelled","device","audio.route")) {
                    nativeRequest=null;picker(null)
                }
                send(r,event,data,event=="sensor" || event=="location" || (event=="value" && data["notification"]?.asBoolean==true))
            }
        } }
    }
    private fun fileSession(access: FileAccess) = fileSession ?: access.files.open(access.scope).also { fileSession=it }
    /** The files capability. A picker request is checked first (room for a grant, the file to export); then the host opens the picker. */
    private fun files(r: JsonObject, nativeToken: Any?, reply: (String,JsonObject)->Unit) {
        val access=fileAccess ?: return reply(PhoneFilesContract.ERROR,PhoneFilesContract.error("unavailable","Files on this phone are not available here."))
        val session=fileSession(access)
        if(nativeToken==null) { session.request(r,reply); return }
        val parsed=try { PhoneFilesContract.parse(r) } catch (failure: PhoneFilesContract.Failure) { return reply(PhoneFilesContract.ERROR,PhoneFilesContract.error(failure)) }
        session.prepare(parsed) { failure ->
            if(closed || nativeRequest!==nativeToken) return@prepare
            if(failure!=null) { nativeRequest=null; send(r,PhoneFilesContract.ERROR,failure); return@prepare }
            if(!access.openPicker(FilePick(r,parsed,PhoneFilesContract.CAPABILITY in grants,nativeToken))) { nativeRequest=null; error(r,"busy","Finish the open file picker first.") }
        }
    }
    /**
     * The answer of the system file picker [pick] ([uris] null when cancelled; [failed] when no picker could open). Leaving
     * PUB for the picker ends the session that asked, so a new session usually answers: it keeps the asking session's
     * consent and replies to the original request, if file access is still allowed.
     */
    fun completePick(pick: FilePick, uris: List<Uri>?, failed: Boolean) {
        if(closed || !foreground()) return
        val cap=PhoneFilesContract.CAPABILITY
        if(nativeRequest===pick.token) nativeRequest=null
        if(pick.consented && !blocked(cap)) grants.add(cap)
        if(!allowed(cap)) { error(pick.request,"revoked","Access ended."); return }
        activate(cap,emptyArray())
        val reply=replier(pick.request,null)
        val access=fileAccess ?: return reply(PhoneFilesContract.ERROR,PhoneFilesContract.error("unavailable","Files on this phone are not available here."))
        when {
            failed -> reply(PhoneFilesContract.ERROR,PhoneFilesContract.error("unavailable","Android's file picker is not available on this phone."))
            uris.isNullOrEmpty() -> reply(PhoneFilesContract.CANCELLED,JsonObject())
            else -> fileSession(access).picked(pick.parsed,uris,reply)
        }
    }
    private fun send(r:JsonObject,event:String,data:JsonObject,disposable:Boolean=false) {
        if(closed || !foreground()) return
        emit(JsonObject().apply { addProperty("type","hardware");addProperty("id",r["id"].asString);addProperty("capability",r["capability"].asString);addProperty("event",event);add("data",data) },disposable)
    }
    private fun error(r:JsonObject,code:String,message:String) = send(r,"error",JsonObject().apply { addProperty("code",code);addProperty("message",message) })
    fun close() {
        if(closed) return
        closed=true; pending=null;nativeRequest=null;grants.clear();main.removeCallbacksAndMessages(null);recheckRunning=false
        prompt(null);requestAndroid(null);picker(null);camera(null);nfc(null)
        platform?.close();bluetooth?.close();usb?.close();fileSession?.close()
        platform=null;bluetooth=null;usb=null;fileSession=null
    }
}
