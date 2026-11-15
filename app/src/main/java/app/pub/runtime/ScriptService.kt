package app.pub.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import app.pub.core.ScriptEngine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ScriptService : Service() {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private var engine: ScriptEngine? = null
    @Volatile private var callback: IRuntimeCallback? = null
    private fun submit(block: () -> Unit) {
        try {
            worker.execute {
                // Covers native/library work that interpreter instruction counting cannot interrupt.
                val timeout = watchdog.schedule({ Process.killProcess(Process.myPid()) }, 2, TimeUnit.SECONDS)
                try { block() } catch (error: Throwable) {
                    engine = null
                    runCatching { callback?.onFailure(error.message?.take(240) ?: "Script failed") }
                } finally { timeout.cancel(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            runCatching { callback?.onFailure("Runtime queue limit exceeded") }
        }
    }
    private val binder = object : IRuntimeService.Stub() {
        override fun load(source: String, response: IRuntimeCallback) {
            callback = response
            submit {
                check(engine == null) { "A session is already loaded" }
                engine = ScriptEngine().also { it.load(source) }
                response.onReady(Process.myUid())
            }
        }
        override fun dispatch(sequence: Long, event: String) = submit {
            val result = checkNotNull(engine) { "Runtime unavailable" }.dispatch(event)
            callback?.onResult(sequence, result)
        }
        override fun shutdown() { Process.killProcess(Process.myPid()) }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() {
        worker.shutdownNow()
        watchdog.shutdownNow()
        super.onDestroy()
    }
}
