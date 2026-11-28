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

private data class TrackerSnapshot(
    val distanceMeters: Double,
    val points: List<TrackPoint>,
    val paceSecPerKm: Int?,
    val signal: GpsSignal,
)

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
        locationTracker.onPointAccepted = { point, distance ->
            ActiveRunStore.appendPoint(applicationContext, point, distance)
        }
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
            ACTION_RESTORE -> {
                val resumeAfterRestore = intent.getBooleanExtra(EXTRA_RESUME_AFTER_RESTORE, false)
                val restored = restoreRun()
                if (restored && resumeAfterRestore) {
                    resumeRun()
                }
            }
        }
        return START_STICKY
    }

    private fun observeTracker() {
        serviceScope.launch {
            combine(
                locationTracker.distanceMeters,
                locationTracker.points,
                locationTracker.currentPaceSecPerKm,
                locationTracker.gpsSignal,
            ) { dist, pts, curPace, signal ->
                TrackerSnapshot(dist, pts, curPace, signal)
            }.collect { snap ->
                _state.update { current ->
                    val elapsed = currentElapsedMs()
                    val avgPace = if (snap.signal == GpsSignal.LOST) {
                        current.avgPaceSecPerKm
                    } else {
                        PaceCalculator.computeAvgPaceSecPerKm(snap.distanceMeters, elapsed)
                    }
                    current.copy(
                        elapsedMs = elapsed,
                        distanceMeters = snap.distanceMeters,
                        points = snap.points,
                        currentPaceSecPerKm = if (current.isPaused) null else snap.paceSecPerKm,
                        avgPaceSecPerKm = avgPace,
                        gpsSignal = snap.signal,
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

        ActiveRunStore.begin(applicationContext, runId, title)
        locationTracker.startUpdates()
        startForegroundWithNotification(buildNotification(title, isPaused = false, elapsedMs = 0L))
        startTimerLoop()
    }

    fun restoreRun(): Boolean {
        val current = _state.value
        if (current.isActive) {
            startForegroundWithNotification(buildNotification(current.title, current.isPaused, current.elapsedMs))
            return true
        }
        val saved = ActiveRunStore.load(applicationContext)
        if (saved == null) {
            startForegroundWithNotification(buildNotification("러닝", isPaused = true, elapsedMs = 0L))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }

        timerJob?.cancel()
        locationTracker.stopUpdates()
        locationTracker.restore(saved.distanceMeters, saved.points)

        accumulatedMs = saved.elapsedMs
        startedRealtimeMs = SystemClock.elapsedRealtime()
        _state.value = RunTrackingState(
            clientRunId = saved.clientRunId,
            title = saved.title,
            isActive = true,
            isPaused = true,
            elapsedMs = saved.elapsedMs,
            distanceMeters = saved.distanceMeters,
            currentPaceSecPerKm = null,
            avgPaceSecPerKm = PaceCalculator.computeAvgPaceSecPerKm(saved.distanceMeters, saved.elapsedMs),
            points = saved.points,
            gpsSignal = GpsSignal.SEARCHING,
        )
        locationTracker.startUpdates()
        startForegroundWithNotification(buildNotification(saved.title, isPaused = true, elapsedMs = saved.elapsedMs))
        persistMeta()
        return true
    }

    fun snapshotNow(): RunTrackingState {
        val st = _state.value
        val elapsed = currentElapsedMs()
        return st.copy(
            elapsedMs = elapsed,
            avgPaceSecPerKm = if (st.gpsSignal == GpsSignal.LOST) {
                st.avgPaceSecPerKm
            } else {
                PaceCalculator.computeAvgPaceSecPerKm(st.distanceMeters, elapsed)
            },
        )
    }

    private fun persistMeta() {
        val st = _state.value
        if (!st.isActive) return
        ActiveRunStore.saveMeta(applicationContext, st.clientRunId, st.title, currentElapsedMs(), st.isPaused)
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
        persistMeta()
        updateNotification()
    }

    fun resumeRun() {
        val current = _state.value
        if (!current.isActive || !current.isPaused) return
        startedRealtimeMs = SystemClock.elapsedRealtime()
        locationTracker.setPaused(false)
        _state.update { it.copy(isPaused = false) }
        persistMeta()
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
        ActiveRunStore.clear(applicationContext)
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
            var tick = 0
            while (true) {
                locationTracker.evaluateSignal()
                if (tick++ % 8 == 0) persistMeta()
                val elapsed = currentElapsedMs()
                _state.update { st ->
                    st.copy(
                        elapsedMs = elapsed,
                        avgPaceSecPerKm = if (st.gpsSignal == GpsSignal.LOST) {
                            st.avgPaceSecPerKm
                        } else {
                            PaceCalculator.computeAvgPaceSecPerKm(st.distanceMeters, elapsed)
                        },
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
        const val ACTION_RESTORE = "com.example.myapplication.action.RESTORE"
        const val EXTRA_TITLE = "com.example.myapplication.extra.TITLE"
        const val EXTRA_RESUME_AFTER_RESTORE = "com.example.myapplication.extra.RESUME_AFTER_RESTORE"
    }
}