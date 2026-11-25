package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
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

    private val _distanceMeters = MutableStateFlow(0.0)
    val distanceMeters: StateFlow<Double> = _distanceMeters.asStateFlow()

    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points: StateFlow<List<TrackPoint>> = _points.asStateFlow()

    private val _currentPaceSecPerKm = MutableStateFlow<Int?>(null)
    val currentPaceSecPerKm: StateFlow<Int?> = _currentPaceSecPerKm.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (isPaused) return
            val loc = result.lastLocation
                ?: result.locations.lastOrNull()
                ?: return

            Log.d(
                TAG,
                "loc received: lat=${loc.latitude}, lng=${loc.longitude}, acc=${loc.accuracy}, time=${loc.time}",
            )

            if (!isValidLocation(loc)) {
                Log.d(TAG, "loc filtered by accuracy: ${loc.accuracy}")
                return
            }

            val prev = lastAcceptedLocation
            if (prev != null) {
                val deltaMeters = prev.distanceTo(loc).toDouble()
                val dtSec = (((loc.time - prev.time).takeIf { it > 0L } ?: 1000L)) / 1000.0
                if (deltaMeters >= 0.5) {
                    _distanceMeters.update { it + deltaMeters }
                    val pace = (dtSec / (deltaMeters / 1000.0)).toInt().coerceIn(90, 2400)
                    _currentPaceSecPerKm.value = pace
                }
            }

            lastAcceptedLocation = loc
            _points.update { it + loc.toTrackPoint() }
        }
    }

    private fun isValidLocation(loc: Location): Boolean {
        // 에뮬레이터 가짜 GPS는 정확도 값이 크게 잡힐 수 있어 100m 이하 허용
        if (loc.hasAccuracy() && loc.accuracy > 100f) return false
        return true
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
        if (paused) {
            lastAcceptedLocation = null
            _currentPaceSecPerKm.value = null
        }
    }

    fun reset() {
        isPaused = false
        lastAcceptedLocation = null
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
    }
}