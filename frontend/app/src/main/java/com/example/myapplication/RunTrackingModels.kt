package com.example.myapplication

import android.location.Location
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/** GPS 수신 상태. SEARCHING: 첫 위치를 기다리는 중 / GOOD: 정상 / WEAK: 약하거나 불안정 / LOST: 끊김(거리·페이스 계산 중단) */
internal enum class GpsSignal { SEARCHING, GOOD, WEAK, LOST }

internal data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val timestampMs: Long,
    val speedMps: Float?,
    /** 신호 끊김·일시정지로 경로가 나뉠 때마다 1씩 증가. 같은 값끼리만 선으로 이어서 그려야 해요. */
    val segment: Int = 0,
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
    val gpsSignal: GpsSignal = GpsSignal.SEARCHING,
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

internal fun Location.toTrackPoint(segment: Int = 0): TrackPoint = TrackPoint(
    lat = latitude,
    lng = longitude,
    timestampMs = time,
    speedMps = if (hasSpeed()) speed else null,
    segment = segment,
)