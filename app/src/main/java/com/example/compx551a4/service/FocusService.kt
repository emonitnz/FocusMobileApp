package com.example.compx551a4.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.compx551a4.AppContainer
import com.example.compx551a4.FocusApp
import com.example.compx551a4.core.DeviceState
import com.example.compx551a4.core.EegSource
import com.example.compx551a4.core.FlushReason
import com.example.compx551a4.core.FocusEngine
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.ServiceController
import com.example.compx551a4.core.SessionRecorder
import com.example.compx551a4.core.Suppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.min

class FocusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var intentionalStop = false

    private lateinit var deps: AppContainer

    override fun onCreate() {
        super.onCreate()
        deps = resolveDeps(applicationContext)

        createNotificationChannelIfNeeded()
        val notification = buildNotification(statusLine = "Starting…")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )

        logBatteryOptimizationState()

        isRunning.value = true
        intentionalStop = false
        loopJob = scope.launch { runDecisionLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        intentionalStop = true
        loopJob?.cancel()
        deps.eegSource.stop()
        scope.launch { deps.sessionRecorder.onSessionEnd(System.currentTimeMillis()) }
        isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null // not a bound service

    private suspend fun runDecisionLoop() {
        deps.sessionRecorder.onSessionStart(System.currentTimeMillis())
        startWithReconnectBackoff()

        scope.launch {
            deps.eegSource.epochs.collect { epoch ->
                deps.focusEngine.submit(epoch)
            }
        }

        scope.launch {
            combine(deps.eegSource.status, deps.focusEngine.state) { status, state ->
                if (!status.usable) FocusState.UNKNOWN else state
            }.distinctUntilChanged().collect { effectiveState ->
                deps.suppressor.apply(effectiveState)
                updateNotification(effectiveState)
            }
        }

        scope.launch {
            deps.focusEngine.decisions.collect { decision ->
                deps.sessionRecorder.onDecision(decision)
            }
        }

        scope.launch { watchForHeadsetRemoved() }
    }

    private suspend fun watchForHeadsetRemoved() {
        var consecutiveNoContact = 0
        deps.eegSource.status.collect { status ->
            val allNoContact = status.contact.worst >= com.example.compx551a4.core.ContactQuality.NO_CONTACT
            consecutiveNoContact = if (allNoContact) consecutiveNoContact + 1 else 0
            if (consecutiveNoContact == REMOVED_DWELL_TICKS) {
                deps.suppressor.flush(FlushReason.HEADSET_REMOVED)
            }
        }
    }

    private suspend fun startWithReconnectBackoff() {
        deps.eegSource.start()

        var backoffMs = INITIAL_BACKOFF_MS
        var wasUsable = false

        scope.launch {
            deps.eegSource.status.collect { status ->
                if (status.usable) {
                    wasUsable = true
                    backoffMs = INITIAL_BACKOFF_MS // reset on a clean (re)connect
                } else if (status.state == DeviceState.DISCONNECTED && wasUsable && !intentionalStop) {
                    wasUsable = false
                    Log.w(TAG, "EegSource dropped, retrying in ${backoffMs}ms")
                    delay(backoffMs)
                    if (!intentionalStop) {
                        deps.eegSource.start()
                        backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                    }
                }
            }
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Focus session",
            NotificationManager.IMPORTANCE_LOW, // ambient status, not itself worth interrupting for
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusLine: String): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = openAppIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus deferral running")
            .setContentText(statusLine)
            .setSmallIcon(android.R.drawable.presence_online) // placeholder — D owns real iconography
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification(state: FocusState) {
        val statusLine = when (state) {
            FocusState.FOCUSED -> "Focused — notifications held"
            FocusState.NOT_FOCUSED -> "Not focused — notifications delivered"
            FocusState.UNKNOWN -> "No signal — notifications delivered normally"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusLine))
    }

    private fun logBatteryOptimizationState() {
        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
        if (!exempt) {
            Log.w(TAG, "Not exempt from battery optimizations — service may be killed by Doze/OEM killers")
        }
    }

    companion object {
        private const val TAG = "FocusService"
        private const val CHANNEL_ID = "focus_service"
        private const val NOTIFICATION_ID = 1001
        private const val REMOVED_DWELL_TICKS = 5 // ~5 consecutive epochs at ~1Hz
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L

        private val isRunning = MutableStateFlow(false)

        val isRunningFlow: StateFlow<Boolean> get() = isRunning

        fun start(context: Context) {
            val intent = Intent(context, FocusService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FocusService::class.java))
        }

        private fun resolveDeps(context: Context): AppContainer {
            val app = context.applicationContext as? FocusApp
                ?: error("Application must be FocusApp to supply FocusService's dependencies")
            return app.container
        }
    }
}

class AndroidServiceController(private val context: Context) : ServiceController {
    override val running: StateFlow<Boolean> = FocusService.isRunningFlow

    override fun start() = FocusService.start(context)
    override fun stop() = FocusService.stop(context)
}