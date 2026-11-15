package app.pub.builder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import app.pub.MainActivity
import app.pub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/** Keeps a user-started build alive; the application-owned controller remains the source of truth. */
class BuildService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = BuilderControllers.get(application)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeDeadline = 0L
    private var runToken: String? = null
    private var lastStartId = 0
    private var foregroundReady = false
    private var terminal = false
    private var expectedStop = false
    private var cancelRequested = false
    private var lastPhase: String? = null

    override fun onCreate() {
        super.onCreate()
        // Android's foreground deadline starts before onStartCommand; do not wait for any I/O,
        // controller initialization, notification permission flow, or coroutine collection.
        try {
            BuildNotifications.createChannels(this)
            startForeground(BuildNotifications.ONGOING_ID, BuildNotifications.ongoing(this, "Preparing your build…", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            foregroundReady = true
        } catch (_: RuntimeException) {
            expectedStop = true; terminal = true
            runCatching { if (controller.buildInProgress) controller.cancel() }
            runCatching { BuildNotifications.result(this, BuildNotifications.Outcome.INTERRUPTED) }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!foregroundReady) { stopSelfResult(startId); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_RUN_TOKEN)
                if (token.isNullOrBlank()) { stopWithoutResult(); return START_NOT_STICKY }
                if (terminal && token == runToken) { stopWithoutResult(); return START_NOT_STICKY }
                if (token != runToken) {
                    observer?.cancel(); observer = null
                    releaseWakeLock()
                    runToken = token
                    terminal = false; expectedStop = false; cancelRequested = false; lastPhase = null
                    wakeDeadline = SystemClock.elapsedRealtime() + MAX_WAKE_MILLIS
                    BuildNotifications.dismissResult(this)
                    // A new start can reach the same Service instance after its previous stop request.
                    try {
                        startForeground(BuildNotifications.ONGOING_ID, BuildNotifications.ongoing(this, "Preparing your build…", token), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } catch (_: RuntimeException) {
                        interruptBuild(BuildNotifications.Outcome.INTERRUPTED)
                        return START_NOT_STICKY
                    }
                }
                if (controller.buildInProgress) acquireWakeLock()
                observeController()
            }
            ACTION_CANCEL -> {
                // An old Cancel action must never cancel a later paid build or restart a build.
                if (intent.getStringExtra(EXTRA_RUN_TOKEN) != runToken || runToken == null) {
                    if (runToken == null) stopWithoutResult()
                    return START_NOT_STICKY
                }
                if (terminal) { stopWithoutResult(); return START_NOT_STICKY }
                if (!controller.buildInProgress) { observeController(); return START_NOT_STICKY }
                cancelRequested = true
                BuildNotifications.updateOngoing(this, "Cancelling your build…", runToken)
                runCatching { controller.cancel() }.onFailure { interruptBuild(BuildNotifications.Outcome.INTERRUPTED) }
                if (!terminal) observeController() // Wait for the controller's durable terminal state.
            }
            else -> if (runToken == null) stopWithoutResult() // No sticky restart or implicit retry after process death.
        }
        return START_NOT_STICKY
    }

    private fun observeController() {
        if (observer != null) return
        observer = scope.launch {
            snapshotFlow { currentNoticeState() }.collect { state ->
                if (terminal) return@collect
                if (!state.running) {
                    finish(outcomeFor(state))
                } else {
                    // Also held during headless Compose capture: it must work with the screen off.
                    acquireWakeLock()
                    val phase = if (cancelRequested) "Cancelling your build…" else state.phase
                    if (phase != lastPhase) {
                        lastPhase = phase
                        BuildNotifications.updateOngoing(this@BuildService, phase, runToken)
                    }
                }
            }
        }
    }

    private fun currentNoticeState() = BuildNoticeState(
        running = controller.buildInProgress,
        phase = BuildNotifications.phase(controller.busy, controller.capturePending != null),
        hasDraft = controller.draft != null,
        needsReview = controller.visualReviewError != null || controller.checksError != null,
        storageError = controller.message == "Build storage error",
        cancelled = controller.message == "Cancelled" || controller.message?.startsWith("Build cancelled") == true
    )

    private fun outcomeFor(state: BuildNoticeState) = when {
        state.storageError -> BuildNotifications.Outcome.STORAGE_ERROR
        cancelRequested || state.cancelled -> BuildNotifications.Outcome.CANCELLED
        !state.hasDraft -> BuildNotifications.Outcome.FAILED
        state.needsReview -> BuildNotifications.Outcome.NEEDS_REVIEW
        else -> BuildNotifications.Outcome.READY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val remaining = wakeDeadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) return // Never extend the total wake budget on progress updates.
        runCatching {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app.pub:BuildService").apply {
                setReferenceCounted(false)
                wakeLock = this
            }
            lock.acquire(remaining)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    private fun finish(outcome: BuildNotifications.Outcome) {
        if (terminal) return
        terminal = true; expectedStop = true
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        BuildNotifications.result(this, outcome)
        stopSelfResult(lastStartId)
    }

    private fun interruptBuild(outcome: BuildNotifications.Outcome) {
        if (terminal) return
        // Timeouts cannot wait for disk I/O; the controller keeps its existing durable checkpoint.
        runCatching { if (controller.buildInProgress) controller.cancel() }
        finish(outcome)
    }

    private fun stopWithoutResult() {
        terminal = true; expectedStop = true
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelfResult(lastStartId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        interruptBuild(BuildNotifications.Outcome.INTERRUPTED)
        // Explicit stop is required promptly on Android 15 even if the controller is persisting.
        stopSelf()
    }

    override fun onDestroy() {
        if (!expectedStop && !terminal) {
            runCatching {
                val state = currentNoticeState()
                terminal = true
                if (state.running) {
                    controller.cancel()
                    BuildNotifications.result(this, BuildNotifications.Outcome.INTERRUPTED)
                } else BuildNotifications.result(this, outcomeFor(state))
            }
        }
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the launcher task does not cancel an explicitly started background build.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_START = "app.pub.builder.START_BUILD_SERVICE"
        internal const val ACTION_CANCEL = "app.pub.builder.CANCEL_BUILD"
        internal const val EXTRA_RUN_TOKEN = "build_service_run"
        // Covers a long generation, image generation, review and both automatic repair passes with the
        // screen off. It is a fixed upper bound per build, not extended by progress.
        private const val MAX_WAKE_MILLIS = 60 * 60 * 1000L

        /** Call from the initiating UI action after setting buildInProgress, before starting paid work. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, BuildService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RUN_TOKEN, UUID.randomUUID().toString()))
        }
    }
}

private data class BuildNoticeState(val running: Boolean, val phase: String, val hasDraft: Boolean,
    val needsReview: Boolean, val storageError: Boolean, val cancelled: Boolean)

/** Intentionally generic notifications: no prompt, app name, account identity, code or review text. */
internal object BuildNotifications {
    const val ONGOING_ID = 4101
    const val RESULT_ID = 4102
    const val ONGOING_CHANNEL = "pub_build_progress"
    const val RESULT_CHANNEL = "pub_build_results"
    enum class Outcome { READY, NEEDS_REVIEW, FAILED, CANCELLED, INTERRUPTED, STORAGE_ERROR }

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "App building", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress and cancellation for a build you started"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        manager.createNotificationChannel(NotificationChannel(RESULT_CHANNEL, "Build results", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a build finishes or needs attention"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    fun phase(busy: String?, capturing: Boolean): String {
        if (capturing) return "Rendering the app preview…"
        val label = busy?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "research" in label -> "Researching similar apps…"
            "planning" in label -> "Planning the screens…"
            // Before the repair and "check" rules, so behavior testing is never shown as a screenshot check.
            "testing" in label -> "Testing the app…"
            "repair" in label || "fix" in label || "revis" in label -> "Improving the app preview…"
            "review" in label || "check" in label -> "Checking the app preview…"
            "sav" in label || "persist" in label -> "Saving build progress…"
            else -> "Building your app…"
        }
    }

    fun openBuilder(context: Context): PendingIntent = PendingIntent.getActivity(context, 4100,
        Intent(context, MainActivity::class.java).setAction("app.pub.OPEN_BUILDER")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_builder", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun ongoing(context: Context, phase: String, token: String?): Notification = Notification.Builder(context, ONGOING_CHANNEL)
        .setSmallIcon(R.drawable.ic_build_notification).setContentTitle("PUB is building your app")
        .setContentText(phase).setContentIntent(openBuilder(context)).setCategory(Notification.CATEGORY_PROGRESS)
        .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
        .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        .apply {
            if (token != null) {
                val cancel = PendingIntent.getService(context, 4103,
                    Intent(context, BuildService::class.java).setAction(BuildService.ACTION_CANCEL)
                        .setData(Uri.parse("pub-build://cancel/$token")).putExtra(BuildService.EXTRA_RUN_TOKEN, token),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            }
        }.build()

    fun updateOngoing(context: Context, phase: String, token: String?) = notifySafely(context, ONGOING_ID, ongoing(context, phase, token))
    fun dismissResult(context: Context) { runCatching { context.getSystemService(NotificationManager::class.java).cancel(RESULT_ID) } }

    fun result(context: Context, outcome: Outcome) = notifySafely(context, RESULT_ID, completion(context, outcome))

    fun completion(context: Context, outcome: Outcome): Notification {
        val (title, text) = when (outcome) {
            Outcome.READY -> "Draft ready to review" to "Open PUB to review and test your app."
            Outcome.NEEDS_REVIEW -> "Build needs your review" to "Open PUB to inspect the draft and check results."
            Outcome.FAILED -> "Build could not finish" to "Open PUB for the build result and retry options."
            Outcome.CANCELLED -> "Build cancelled" to "Open PUB to view any completed draft and build history."
            Outcome.INTERRUPTED -> "Build interrupted" to "Open PUB to view saved progress. No build was automatically retried."
            Outcome.STORAGE_ERROR -> "Build needs attention" to "Open PUB and check available phone storage."
        }
        return Notification.Builder(context, RESULT_CHANNEL).setSmallIcon(R.drawable.ic_build_notification)
            .setContentTitle(title).setContentText(text).setContentIntent(openBuilder(context))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true).build()
    }

    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        // Foreground service promotion remains mandatory even when POST_NOTIFICATIONS is denied;
        // normal updates/results simply stay in the app's durable state in that case.
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@runCatching
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.areNotificationsEnabled()) manager.notify(id, notification)
        }
    }
}
