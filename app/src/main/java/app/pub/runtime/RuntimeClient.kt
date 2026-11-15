package app.pub.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import app.pub.core.UiPacket
import com.google.gson.JsonObject
import java.util.ArrayDeque
import java.util.UUID

/** One authenticated Binder connection belongs to one host-selected project session. */
class RuntimeClient(
    private val context: Context,
    private val onReady: (Int) -> Unit,
    private val onPacket: (UiPacket) -> Unit,
    private val onError: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val instanceName = "pub_${UUID.randomUUID().toString().replace("-", "") }"
    private var remote: IRuntimeService? = null
    private var bound = false
    private var closed = false
    private var busy = false
    private var ready = false
    var responseIsUserAction = false; private set
    private var sequence = 0L
    private var source = ""
    private val pending = ArrayDeque<String>()
    private val responseDeadline = Runnable { fail("Runtime did not respond. Restart the project.") }
    private val callback = object : IRuntimeCallback.Stub() {
        override fun onReady(uid: Int) { main.post {
            if (closed) return@post
            if (uid == Process.myUid()) { fail("Runtime isolation check failed"); return@post }
            main.removeCallbacks(responseDeadline)
            ready = true
            this@RuntimeClient.onReady(uid)
            drain()
        } }
        override fun onResult(id: Long, packet: String) { main.post {
            if (closed || id != sequence || !busy) return@post
            main.removeCallbacks(responseDeadline)
            busy = false
            try { onPacket(UiPacket.parse(packet)) } catch (e: Exception) { fail("Invalid UI packet: ${e.message}") }
            drain()
        } }
        override fun onFailure(message: String) { main.post { fail(message) } }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) return
            remote = IRuntimeService.Stub.asInterface(service)
            runCatching { remote?.load(source, callback) }.onFailure { fail("Cannot load project runtime") }
        }
        override fun onServiceDisconnected(name: ComponentName) = fail("Isolated runtime stopped. Restart the project.")
        override fun onBindingDied(name: ComponentName) = fail("Runtime connection ended")
        override fun onNullBinding(name: ComponentName) = fail("Runtime unavailable")
    }
    fun start(script: String) {
        check(!closed && !bound) { "Runtime session already started or closed" }
        source = script
        bound = context.bindIsolatedService(Intent(context, ScriptService::class.java), Context.BIND_AUTO_CREATE,
            instanceName, context.mainExecutor, connection)
        if (!bound) fail("Cannot start isolated service")
        else main.postDelayed(responseDeadline, 10_000)
    }
    fun send(event: JsonObject, disposable: Boolean = false) {
        if (closed) return
        if (disposable && (busy || !ready || pending.isNotEmpty())) return
        if (pending.size >= 16) { fail("Too many queued project events"); return }
        pending.add(event.toString())
        drain()
    }
    private fun drain() {
        if (!ready || busy || closed || pending.isEmpty()) return
        busy = true
        sequence++
        main.postDelayed(responseDeadline, 3000)
        val next = pending.removeFirst()
        responseIsUserAction = com.google.gson.JsonParser.parseString(next).asJsonObject["type"]?.asString == "action"
        runCatching { remote?.dispatch(sequence, next) }.onFailure { fail("Runtime disconnected") }
    }
    private fun fail(message: String) {
        if (closed) return
        close()
        onError(message)
    }
    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(responseDeadline)
        pending.clear()
        runCatching { remote?.shutdown() }
        if (bound) runCatching { context.unbindService(connection) }
        remote = null
        bound = false
    }
}
