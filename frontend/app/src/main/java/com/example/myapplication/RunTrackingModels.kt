package com.example.myapplication

import android.location.Location
import java.util.Locale
import java.util.UUID
import kotlin.math.max

internal data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val timestampMs: Long,
    val speedMps: Float?,
)

internal data class RunTrackingState(
    val clientRunId: String = UUID.randomUUID().toString(),
    val title: String = "3km 자유 주행",
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    val distanceMeters: Double = 0.0,
    val currentPaceSecPerKm: Int? = null,
    val avgPaceSecPerKm: Int? = null,
    val points: List<TrackPoint> = emptyList(),
)

internal object PaceCalculator {
    fun formatPace(secPerKm: Int?): String {
        if (secPerKm == null || secPerKm <= 0 || secPerKm > 3600) return "— /km"
        val min = secPerKm / 60
        val sec = secPerKm % 60
        return String.format(Locale.ROOT, "%d'%02d\" /km", min, sec)
    }

    fun computeAvgPaceSecPerKm(distanceMeters: Double, elapsedMs: Long): Int? {
        if (distanceMeters < 10.0 || elapsedMs < 1000L) return null
        val km = distanceMeters / 1000.0
        val seconds = elapsedMs / 1000.0
        return max(1, (seconds / km).toInt())
    }
}

internal fun Location.toTrackPoint(): TrackPoint = TrackPoint(
    lat = latitude,
    lng = longitude,
    timestampMs = time,
    speedMps = if (hasSpeed()) speed else null,
)