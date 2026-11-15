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
    private class Queued(val json: String, val userAction: Boolean, val key: String?)
    private val pending = ArrayDeque<Queued>()
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
    /**
     * Queues [event] for the runtime.
     * - [disposable]: dropped instead of queued while the runtime is busy (high-rate frames such as pitch).
     * - [key]: only the newest pending event per key is kept, in the slot of the one it replaces, so a
     *   continuous stream (sensor values, text typed into one field) takes at most one queue slot and
     *   cannot exhaust the queue. A pending event is never replaced across a later queued user action,
     *   so the script still sees exactly the state each action applied to.
     */
    fun send(event: JsonObject, disposable: Boolean = false, key: String? = null) {
        if (closed) return
        if (disposable && (busy || !ready || pending.isNotEmpty())) return
        val item = Queued(event.toString(), event["type"]?.asString == "action", key)
        if (key != null) {
            val index = pending.indexOfLast { it.key == key }
            if (index >= 0 && (index + 1 until pending.size).none { pending[it].userAction }) {
                pending[index] = item
                drain()
                return
            }
        }
        if (pending.size >= 16) { fail("Too many queued project events"); return }
        pending.add(item)
        drain()
    }
    /** Drops queued events whose key starts with [prefix], such as hardware data invalidated by a stop. */
    fun discard(prefix: String) { pending.removeAll { it.key?.startsWith(prefix) == true } }
    private fun drain() {
        if (!ready || busy || closed || pending.isEmpty()) return
        busy = true
        sequence++
        main.postDelayed(responseDeadline, 3000)
        val next = pending.removeFirst()
        responseIsUserAction = next.userAction
        runCatching { remote?.dispatch(sequence, next.json) }.onFailure { fail("Runtime disconnected") }
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
