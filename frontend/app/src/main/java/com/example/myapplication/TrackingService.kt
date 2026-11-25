package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

internal class TrackingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var locationTracker: LocationTracker
    private var timerJob: Job? = null
    private var startedRealtimeMs: Long = 0L
    private var accumulatedMs: Long = 0L

    private val _state = MutableStateFlow(RunTrackingState())
    val state: StateFlow<RunTrackingState> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        locationTracker = LocationTracker(applicationContext)
        ensureChannel()
        observeTracker()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "3km 자유 주행"
                startRun(title)
            }
            ACTION_PAUSE -> pauseRun()
            ACTION_RESUME -> resumeRun()
            ACTION_STOP -> stopRun()
        }
        return START_STICKY
    }

    private fun observeTracker() {
        serviceScope.launch {
            combine(
                locationTracker.distanceMeters,
                locationTracker.points,
                locationTracker.currentPaceSecPerKm,
            ) { dist, pts, curPace ->
                Triple(dist, pts, curPace)
            }.collect { (dist, pts, curPace) ->
                _state.update { current ->
                    val elapsed = currentElapsedMs()
                    val avgPace = PaceCalculator.computeAvgPaceSecPerKm(dist, elapsed)
                    current.copy(
                        elapsedMs = elapsed,
                        distanceMeters = dist,
                        points = pts,
                        currentPaceSecPerKm = if (current.isPaused) null else curPace,
                        avgPaceSecPerKm = avgPace,
                    )
                }
            }
        }
    }

    fun startRun(title: String) {
        timerJob?.cancel()
        locationTracker.stopUpdates()
        locationTracker.reset()
        locationTracker.setPaused(false)

        val runId = UUID.randomUUID().toString()
        accumulatedMs = 0L
        startedRealtimeMs = SystemClock.elapsedRealtime()

        _state.value = RunTrackingState(
            clientRunId = runId,
            title = title,
            isActive = true,
            isPaused = false,
            elapsedMs = 0L,
            distanceMeters = 0.0,
            currentPaceSecPerKm = null,
            avgPaceSecPerKm = null,
            points = emptyList(),
        )

        locationTracker.startUpdates()
        startForegroundWithNotification(buildNotification(title, isPaused = false, elapsedMs = 0L))
        startTimerLoop()
    }

    fun pauseRun() {
        val current = _state.value
        if (!current.isActive || current.isPaused) return
        accumulatedMs += (SystemClock.elapsedRealtime() - startedRealtimeMs).coerceAtLeast(0L)
        timerJob?.cancel()
        locationTracker.setPaused(true)
        _state.update {
            it.copy(
                isPaused = true,
                elapsedMs = accumulatedMs,
                currentPaceSecPerKm = null,
            )
        }
        updateNotification()
    }

    fun resumeRun() {
        val current = _state.value
        if (!current.isActive || !current.isPaused) return
        startedRealtimeMs = SystemClock.elapsedRealtime()
        locationTracker.setPaused(false)
        _state.update { it.copy(isPaused = false) }
        startTimerLoop()
        updateNotification()
    }

    fun stopRun() {
        val finalElapsed = currentElapsedMs()
        timerJob?.cancel()
        locationTracker.stopUpdates()
        _state.update {
            it.copy(
                isActive = false,
                isPaused = false,
                elapsedMs = finalElapsed,
                currentPaceSecPerKm = null,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun currentElapsedMs(): Long {
        val cur = _state.value
        if (!cur.isActive) return cur.elapsedMs
        return if (cur.isPaused) {
            accumulatedMs
        } else {
            accumulatedMs + (SystemClock.elapsedRealtime() - startedRealtimeMs).coerceAtLeast(0L)
        }
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (true) {
                val elapsed = currentElapsedMs()
                _state.update { st ->
                    st.copy(
                        elapsedMs = elapsed,
                        avgPaceSecPerKm = PaceCalculator.computeAvgPaceSecPerKm(st.distanceMeters, elapsed),
                    )
                }
                delay(250L)
            }
        }
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                },
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val st = _state.value
        if (!st.isActive) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(st.title, st.isPaused, st.elapsedMs))
    }

    private fun buildNotification(title: String, isPaused: Boolean, elapsedMs: Long): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val statusText = if (isPaused) "일시정지됨" else "GPS 측정 중"
        val secTotal = elapsedMs / 1000
        val timeText = String.format(Locale.ROOT, "%02d:%02d", secTotal / 60, secTotal % 60)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("$title · $timeText")
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "러닝 GPS 측정",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "화면 잠금 중에도 러닝 거리와 시간을 측정합니다."
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        locationTracker.stopUpdates()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "running_gps_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.myapplication.action.START"
        const val ACTION_PAUSE = "com.example.myapplication.action.PAUSE"
        const val ACTION_RESUME = "com.example.myapplication.action.RESUME"
        const val ACTION_STOP = "com.example.myapplication.action.STOP"
        const val EXTRA_TITLE = "com.example.myapplication.extra.TITLE"
    }
}