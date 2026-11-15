package app.pub.builder

import android.content.Context
import android.os.SystemClock
import app.pub.core.AppTestSuite
import app.pub.core.ChecksReport
import app.pub.core.ProjectBundle
import app.pub.runtime.RuntimeClient
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * Runs an app's behavior checks in PUB's isolated runtime, one fresh session per test, on the main thread.
 * Like the visual preview, a session starts from init with the preview device context, empty settings and empty
 * storage, and has no broker, permissions, hardware, saved data or photos: every effect is discarded. UI packets
 * are validated by RuntimeClient exactly as for a running app. A runtime failure or timeout fails only that test.
 * Every client is closed, including on cancellation, which propagates to the caller.
 */
object BehaviorChecks {
    const val TEST_TIMEOUT_MS = 10_000L
    const val TOTAL_TIMEOUT_MS = 60_000L

    suspend fun run(context: Context, bundle: ProjectBundle, suite: AppTestSuite, device: JsonObject): ChecksReport =
        withContext(Dispatchers.Main.immediate) {
            val app = context.applicationContext
            val deadline = SystemClock.elapsedRealtime() + TOTAL_TIMEOUT_MS
            ChecksReport.of(suite.tests.map { test ->
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining < 1_000) ChecksReport.Entry.failed(test.name, "Not run: all behavior checks together are limited to ${TOTAL_TIMEOUT_MS / 1000} seconds.")
                else runTest(app, bundle.source, test, device, min(TEST_TIMEOUT_MS, remaining))
            })
        }

    private sealed interface Update {
        data object Ready : Update
        class Packet(val ui: JsonObject) : Update
        class Failed(val message: String) : Update
    }

    private suspend fun runTest(context: Context, source: String, test: AppTestSuite.TestCase, device: JsonObject, timeoutMs: Long): ChecksReport.Entry {
        val run = AppTestSuite.Run(test)
        val updates = Channel<Update>(Channel.UNLIMITED)
        // Callbacks arrive on the main thread; effects are deliberately ignored.
        val client = RuntimeClient(context, onReady = { updates.trySend(Update.Ready) },
            onPacket = { updates.trySend(Update.Packet(it.ui)) }, onError = { updates.trySend(Update.Failed(it)) })
        try {
            val finished = withTimeoutOrNull(timeoutMs) {
                runCatching { client.start(source) }.onFailure { run.fail("The isolated runtime could not start.") }
                while (!run.isFinished) {
                    when (val update = updates.receive()) {
                        Update.Ready -> client.send(AppTestSuite.Run.initEvent(device))
                        is Update.Packet -> run.next(update.ui)?.let { client.send(it) }
                        is Update.Failed -> run.fail(update.message)
                    }
                }
            }
            // withTimeout would throw a CancellationException that callers treat as a cancelled build.
            if (finished == null) run.fail("no response within ${timeoutMs / 1000} seconds")
        } finally {
            client.close()
            updates.close()
        }
        return run.entry()
    }
}
