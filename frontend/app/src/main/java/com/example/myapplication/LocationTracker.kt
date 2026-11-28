package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class LocationTracker(context: Context) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private var lastAcceptedLocation: Location? = null
    private var isPaused: Boolean = false

    // GPS 끊김 판단용. lastFixMs는 SystemClock.elapsedRealtime 기준(ms), -1이면 아직 수신 없음
    private var lastFixMs: Long = -1L
    private var lastAccuracyM: Float? = null
    private var locationAvailable: Boolean = true
    private var segment: Int = 0
    private val isEmulator: Boolean = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK built for")

    private val _distanceMeters = MutableStateFlow(0.0)
    val distanceMeters: StateFlow<Double> = _distanceMeters.asStateFlow()

    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points: StateFlow<List<TrackPoint>> = _points.asStateFlow()

    private val _currentPaceSecPerKm = MutableStateFlow<Int?>(null)
    val currentPaceSecPerKm: StateFlow<Int?> = _currentPaceSecPerKm.asStateFlow()

    private val _gpsSignal = MutableStateFlow(GpsSignal.SEARCHING)
    val gpsSignal: StateFlow<GpsSignal> = _gpsSignal.asStateFlow()

    /** 위치가 경로에 추가될 때마다 (점, 그 시점의 누적 거리)와 함께 호출된다. 기기 저장용. */
    var onPointAccepted: ((TrackPoint, Double) -> Unit)? = null

    private val callback = object : LocationCallback() {
        override fun onLocationAvailability(availability: LocationAvailability) {
            locationAvailable = availability.isLocationAvailable
            evaluateSignal()
        }

        override fun onLocationResult(result: LocationResult) {
            if (isPaused) return
            // 한꺼번에 여러 위치가 전달돼도(화면이 꺼진 동안 등) 시간순으로 모두 처리한다
            val locations = result.locations.ifEmpty { listOfNotNull(result.lastLocation) }
            for (loc in locations.sortedBy { it.elapsedRealtimeNanos }) handleLocation(loc)
        }
    }

    private fun handleLocation(loc: Location) {
        // ageMs: 위치가 측정된 뒤 앱에 전달되기까지 걸린 시간 (화면 갱신 지연 확인용)
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        Log.d(
            TAG,
            "loc received: lat=${loc.latitude}, lng=${loc.longitude}, acc=${loc.accuracy}, time=${loc.time}, ageMs=$ageMs",
        )

        if (!isValidLocation(loc)) {
            Log.d(TAG, "loc filtered by accuracy: ${loc.accuracy}")
            evaluateSignal()
            return
        }

        val fixMs = loc.elapsedRealtimeNanos / 1_000_000L
        var prev = lastAcceptedLocation
        if (prev != null && lastFixMs >= 0L && fixMs - lastFixMs >= LOST_AFTER_MS) {
            // 끊겼던 구간은 거리에 더하지 않고, 이번 위치를 새 기준점으로만 쓴다
            prev = null
        }
        // 일시정지 복귀나 끊김 복구로 기준점이 없는 상태면 경로를 새 구간으로 시작
        if (prev == null && _points.value.isNotEmpty()) segment++

        if (prev != null) {
            val deltaMeters = prev.distanceTo(loc).toDouble()
            // 기준점에서 충분히 움직였을 때만 거리에 더한다. 멈춰 있을 때 GPS가 흔들리는 만큼이
            // 거리로 쌓이지 않게 하고, 기준점은 그대로 두므로 천천히 움직여도 누적되어 반영된다.
            if (deltaMeters >= minStepMeters(loc)) {
                val dtSec = (((loc.time - prev.time).takeIf { it > 0L } ?: 1000L)) / 1000.0
                _distanceMeters.update { it + deltaMeters }
                val pace = (dtSec / (deltaMeters / 1000.0)).toInt().coerceIn(90, 2400)
                _currentPaceSecPerKm.value = pace
                lastAcceptedLocation = loc
            }
        } else {
            lastAcceptedLocation = loc
        }

        lastFixMs = fixMs
        lastAccuracyM = if (loc.hasAccuracy()) loc.accuracy else null
        val point = loc.toTrackPoint(segment)
        _points.update { it + point }
        onPointAccepted?.invoke(point, _distanceMeters.value)
        Log.d(TAG, "fix accepted: points=${_points.value.size}, dist=${"%.1f".format(_distanceMeters.value)}m")
        evaluateSignal()
    }

    private fun minStepMeters(loc: Location): Double {
        if (isEmulator) return 0.5
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0
        return maxOf(MIN_STEP_M, accuracy * STEP_ACCURACY_RATIO)
    }

    /**
     * 마지막 정상 수신 이후 경과 시간과 정확도로 신호 상태를 갱신한다.
     * 위치가 안 들어오는 동안에도 상태가 바뀌어야 해서 서비스 타이머에서 주기적으로 호출한다.
     */
    fun evaluateSignal() {
        if (isPaused) return
        val next = if (lastFixMs < 0L) {
            GpsSignal.SEARCHING
        } else {
            val ageMs = SystemClock.elapsedRealtime() - lastFixMs
            when {
                ageMs >= LOST_AFTER_MS -> GpsSignal.LOST
                !locationAvailable || ageMs >= WEAK_AFTER_MS ||
                        (lastAccuracyM ?: 0f) > WEAK_ACCURACY_M -> GpsSignal.WEAK
                else -> GpsSignal.GOOD
            }
        }
        if (next == GpsSignal.LOST && _gpsSignal.value != GpsSignal.LOST) {
            // 끊기는 순간 기준점을 버려서, 복구 후 첫 위치와 직선으로 이어지지 않게 한다
            lastAcceptedLocation = null
            _currentPaceSecPerKm.value = null
        }
        _gpsSignal.value = next
    }

    private fun isValidLocation(loc: Location): Boolean {
        // 실기기는 정확도 30m 이하만 사용. 에뮬레이터 가짜 GPS는 정확도 값이 크게 잡힐 수 있어 100m 이하 허용
        val limit = if (isEmulator) EMULATOR_MAX_ACCURACY_M else MAX_ACCURACY_M
        if (loc.hasAccuracy() && loc.accuracy > limit) return false
        return true
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
        if (paused) {
            lastAcceptedLocation = null
            _currentPaceSecPerKm.value = null
        } else if (lastFixMs >= 0L || _points.value.isNotEmpty()) {
            // 재개 시점부터 다시 끊김을 판단 (일시정지 시간이 끊김으로 잡히지 않게).
            // 앱이 꺼졌다 복구된 러닝은 lastFixMs가 없지만 경로가 있으므로 같은 방식으로 판단한다.
            lastFixMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 기기에 보관된 기록으로 상태를 되돌린다. 일시정지 상태로 복원되며,
     * 재개 후 첫 위치는 기준점으로만 쓰이고 새 경로 구간으로 시작한다.
     */
    fun restore(distanceMeters: Double, restoredPoints: List<TrackPoint>) {
        lastAcceptedLocation = null
        lastFixMs = -1L
        lastAccuracyM = null
        locationAvailable = true
        segment = restoredPoints.lastOrNull()?.segment ?: 0
        isPaused = true
        _distanceMeters.value = distanceMeters
        _points.value = restoredPoints
        _currentPaceSecPerKm.value = null
        _gpsSignal.value = GpsSignal.SEARCHING
    }

    fun reset() {
        isPaused = false
        lastAcceptedLocation = null
        lastFixMs = -1L
        lastAccuracyM = null
        locationAvailable = true
        segment = 0
        _gpsSignal.value = GpsSignal.SEARCHING
        _distanceMeters.value = 0.0
        _points.value = emptyList()
        _currentPaceSecPerKm.value = null
    }

    @SuppressLint("MissingPermission")
    fun startUpdates() {
        fusedClient.removeLocationUpdates(callback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stopUpdates() {
        fusedClient.removeLocationUpdates(callback)
    }

    companion object {
        private const val TAG = "LocationTracker"
        private const val WEAK_AFTER_MS = 3_000L
        private const val LOST_AFTER_MS = 5_000L
        private const val WEAK_ACCURACY_M = 20f
        private const val MAX_ACCURACY_M = 30f
        private const val EMULATOR_MAX_ACCURACY_M = 100f
        private const val MIN_STEP_M = 2.0
        private const val STEP_ACCURACY_RATIO = 0.5
    }
}