package com.example.myapplication

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** 앱이 꺼진 뒤 복구할 수 있는, 기기에 보관된 진행 중 러닝 기록 */
internal data class RecoverableRun(
    val clientRunId: String,
    val title: String,
    /** 마지막으로 저장된 시점까지의 경과 시간 (최대 약 2초 전 값) */
    val elapsedMs: Long,
    val distanceMeters: Double,
    val points: List<TrackPoint>,
    /** 꺼지기 직전에 일시정지 상태였는지 */
    val wasPaused: Boolean,
    /** 마지막 저장 시각 (System.currentTimeMillis) */
    val savedAtMs: Long,
) {
    /** 서비스를 거치지 않고 바로 repo.enqueue(...)에 넘길 수 있는 종료 상태 */
    fun toTrackingState() = RunTrackingState(
        clientRunId = clientRunId,
        title = title,
        isActive = false,
        isPaused = false,
        elapsedMs = elapsedMs,
        distanceMeters = distanceMeters,
        currentPaceSecPerKm = null,
        avgPaceSecPerKm = PaceCalculator.computeAvgPaceSecPerKm(distanceMeters, elapsedMs),
        points = points,
    )
}

/**
 * 진행 중인 러닝을 기기(filesDir)에 보관한다.
 * - meta.json   : 주행 ID, 제목, 경과 시간, 일시정지 여부 (덮어쓰기)
 * - points.csv  : 경로 한 줄 = lat,lng,timestampMs,speed,segment,누적거리 (이어 붙이기)
 * 누적 거리를 점과 같은 줄에 적어서, 어느 시점에 꺼져도 거리와 경로가 어긋나지 않는다.
 * 모든 파일 작업은 단일 스레드에서 순서대로 처리한다.
 */
internal object ActiveRunStore {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "active-run-store").apply { isDaemon = true } }

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "active_run").apply { mkdirs() }

    /** 새 러닝을 시작할 때 호출. 이전에 보관된 기록은 지운다. */
    fun begin(context: Context, clientRunId: String, title: String) {
        val ctx = context.applicationContext
        io.execute {
            deleteFiles(ctx)
            writeMeta(ctx, clientRunId, title, 0L, false)
        }
    }

    fun saveMeta(context: Context, clientRunId: String, title: String, elapsedMs: Long, isPaused: Boolean) {
        val ctx = context.applicationContext
        io.execute { writeMeta(ctx, clientRunId, title, elapsedMs, isPaused) }
    }

    fun appendPoint(context: Context, point: TrackPoint, distanceMeters: Double) {
        val ctx = context.applicationContext
        io.execute {
            runCatching {
                val line = buildString {
                    append(point.lat).append(',')
                    append(point.lng).append(',')
                    append(point.timestampMs).append(',')
                    append(point.speedMps ?: "").append(',')
                    append(point.segment).append(',')
                    append(distanceMeters).append('\n')
                }
                FileOutputStream(File(dir(ctx), POINTS_FILE), true).use { it.write(line.toByteArray()) }
            }
        }
    }

    /** 러닝을 정상 종료(저장)했거나 복구를 포기할 때 호출 */
    fun clear(context: Context) {
        val ctx = context.applicationContext
        io.execute { deleteFiles(ctx) }
    }

    /**
     * 보관된 기록을 읽는다. 없거나 읽을 수 없으면 null.
     * 파일을 읽으므로 Dispatchers.IO 같은 백그라운드 스레드에서 호출할 것.
     */
    fun load(context: Context): RecoverableRun? {
        val ctx = context.applicationContext
        return runCatching { io.submit(Callable { read(ctx) }).get() }.getOrNull()
    }

    private fun read(ctx: Context): RecoverableRun? {
        val dir = dir(ctx)
        val metaFile = File(dir, META_FILE)
        if (!metaFile.exists()) return null
        val meta = JSONObject(metaFile.readText())
        val runId = meta.optString("clientRunId")
        if (runId.isBlank()) return null

        val points = ArrayList<TrackPoint>()
        var distance = 0.0
        val pointsFile = File(dir, POINTS_FILE)
        if (pointsFile.exists()) {
            pointsFile.forEachLine { line ->
                // 꺼지는 순간 일부만 써진 마지막 줄은 건너뛴다
                val f = line.split(',')
                if (f.size != 6) return@forEachLine
                val lat = f[0].toDoubleOrNull() ?: return@forEachLine
                val lng = f[1].toDoubleOrNull() ?: return@forEachLine
                val ts = f[2].toLongOrNull() ?: return@forEachLine
                val seg = f[4].toIntOrNull() ?: return@forEachLine
                val dist = f[5].toDoubleOrNull() ?: return@forEachLine
                points += TrackPoint(lat, lng, ts, f[3].toFloatOrNull(), seg)
                distance = dist
            }
        }
        return RecoverableRun(
            clientRunId = runId,
            title = meta.optString("title", "3km 자유 주행"),
            elapsedMs = meta.optLong("elapsedMs", 0L),
            distanceMeters = distance,
            points = points,
            wasPaused = meta.optBoolean("isPaused", false),
            savedAtMs = meta.optLong("savedAtMs", 0L),
        )
    }

    private fun writeMeta(ctx: Context, clientRunId: String, title: String, elapsedMs: Long, isPaused: Boolean) {
        runCatching {
            val dir = dir(ctx)
            val json = JSONObject()
                .put("clientRunId", clientRunId)
                .put("title", title)
                .put("elapsedMs", elapsedMs)
                .put("isPaused", isPaused)
                .put("savedAtMs", System.currentTimeMillis())
            // 쓰는 도중 꺼져도 이전 파일이 남도록 임시 파일에 쓴 뒤 교체
            val tmp = File(dir, "$META_FILE.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(File(dir, META_FILE))
        }
    }

    private fun deleteFiles(ctx: Context) {
        val dir = dir(ctx)
        File(dir, META_FILE).delete()
        File(dir, "$META_FILE.tmp").delete()
        File(dir, POINTS_FILE).delete()
    }

    private const val META_FILE = "meta.json"
    private const val POINTS_FILE = "points.csv"
}