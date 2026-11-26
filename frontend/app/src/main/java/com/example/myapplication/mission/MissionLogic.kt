package com.example.myapplication.mission

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * 미션 로직: 초기 체력 측정 결과, 활성 목표, 이전 수행 결과로 미션을 추천하고 판정합니다.
 * Android 의존성이 없는 순수 Kotlin 이라 JVM 단위 테스트로 바로 검증할 수 있습니다.
 * 단위와 필드 이름은 백엔드 API와 같습니다: 거리 m, 시간 초, 페이스 초/km(값이 작을수록 빠름).
 * minSdk 24 에서 그대로 동작하도록 java.time 을 쓰지 않습니다.
 */

object MissionRules {
    const val DISTANCE_STEP_METERS = 250
    const val MIN_MISSION_DISTANCE_METERS = 1000
    /** GPS 오차를 고려해 목표 거리의 98% 이상이면 거리 달성 */
    const val DISTANCE_TOLERANCE = 0.98
    /** 미달성이라도 목표 거리의 90% 이상이면 같은 거리로 재도전 */
    const val RETRY_THRESHOLD = 0.9
    /** 거리 미달성 시 다음 거리는 최대 20%까지만 줄임 */
    const val MAX_DECREASE_RATIO = 0.8
    /** 페이스 목표에는 거리 필드가 없으므로 기준 거리를 5km로 둠 */
    const val PACE_GOAL_REFERENCE_METERS = 5000
    /** 실시간 판정에서 처음 200m는 GPS 오차로 페이스를 판단하지 않음 */
    const val PACE_WARMUP_METERS = 200.0
    const val ASSESSMENT_MIN_DISTANCE_METERS = 3000
    const val ASSESSMENT_REQUIRED_RUNS = 3
}

/* ------------------------------------------------------------------ */
/* 입력 모델 (백엔드 응답과 같은 모양)                                       */
/* ------------------------------------------------------------------ */

/** GET /assessment 응답 */
data class Assessment(
    val completedRuns: Int,
    val requiredRuns: Int = MissionRules.ASSESSMENT_REQUIRED_RUNS,
    val status: String,                         // "in_progress" | "completed"
    val baselinePaceSecondsPerKm: Int?,
) {
    val completed: Boolean get() = status == "completed" && baselinePaceSecondsPerKm != null
}

/** 러닝 1회 기록 (POST /runs 와 같은 필드) */
data class RunInput(val distanceMeters: Int, val movingSeconds: Int)

/** GET /goals/active 의 goal */
sealed class Goal {
    data class Distance(val targetDistanceMeters: Int) : Goal()
    data class Pace(val targetPaceSecondsPerKm: Int) : Goal()
}

/* ------------------------------------------------------------------ */
/* 출력 모델                                                            */
/* ------------------------------------------------------------------ */

object MissionType {
    const val EASY = "easy"
    const val LONG = "long"
    const val TEMPO = "tempo"
    const val GOAL_PACE = "goal_pace"
    const val GOAL_CHALLENGE = "goal_challenge"
}

data class Mission(
    val id: String,
    val date: String,
    val type: String,
    val title: String,
    val description: String,
    val targetDistanceMeters: Int,
    /** 이 페이스보다 느리면 미달성 */
    val maxPaceSecondsPerKm: Int,
    /** 이 페이스보다 빠르면 '너무 빨라요' 안내 (실패는 아님) */
    val minPaceSecondsPerKm: Int?,
    val recommendedPaceSecondsPerKm: Int,
)

data class Fitness(val level: Int, val label: String, val baselinePaceSecondsPerKm: Int)

/** 사용자별 훈련 상태. 미션 결과마다 갱신해서 저장합니다. */
data class TrainingState(
    val easyDistanceMeters: Int,
    val longDistanceMeters: Int,
    val tempoDistanceMeters: Int,
    val easyPaceSecondsPerKm: Int,
    val tempoPaceSecondsPerKm: Int,
    val consecutiveSuccess: Int = 0,
    val consecutiveFail: Int = 0,
    /** 편안한 러닝 연속 성공 수 (2회마다 거리 +250m) */
    val easySuccessStreak: Int = 0,
    val completedMissionCount: Int = 0,
    val recoveryNext: Boolean = false,
)

sealed class Recommendation {
    /** 초기 측정 3회 미만 (UC-02 2a). 오류나 빈 목록이 아님 */
    data class Locked(val completedRuns: Int, val requiredRuns: Int, val remainingRuns: Int, val message: String) : Recommendation()
    /** 측정은 끝났지만 목표 없음 → 목표 설정으로 유도 */
    data class NeedsGoal(val fitness: Fitness, val message: String) : Recommendation()
    /** 오늘의 미션. trainingState 는 저장해야 다음 추천에 반영됩니다 */
    data class Ready(val date: String, val fitness: Fitness, val missions: List<Mission>, val trainingState: TrainingState) : Recommendation()
}

data class GoalIssue(val code: String, val message: String)
data class GoalValidation(val errors: List<GoalIssue>, val warnings: List<GoalIssue>) {
    val valid: Boolean get() = errors.isEmpty()
}

data class MissionResult(
    val missionId: String,
    val outcome: String,          // "success" | "failed"
    val failReason: String?,      // null | "distance_short" | "pace_too_slow" | "both"
    val distanceMeters: Int,
    val movingSeconds: Int,
    val paceSecondsPerKm: Int?,
    val distanceRatio: Double,
    val ranTooFast: Boolean,
) {
    val success: Boolean get() = outcome == "success"
}

data class Feedback(val title: String, val message: String, val nextHint: String)
data class AppliedResult(val trainingState: TrainingState, val goalAchieved: Boolean, val feedback: Feedback)

enum class PaceStatus { MEASURING, ON_TARGET, TOO_SLOW, TOO_FAST, GPS_LOST }

/** 러닝 측정 화면에 바인딩할 실시간 진행 */
data class MissionProgress(
    val progress: Float,                // 0f ~ 1f
    val remainingMeters: Int,           // "0.90km 남음"
    val paceSecondsPerKm: Int?,         // null 이면 "—"
    val paceStatus: PaceStatus,
    val distanceReached: Boolean,
    val requiredPaceForRemaining: Int?, // 템포 계열 미션에서만 값 있음
)

/* ------------------------------------------------------------------ */
/* 로직                                                                */
/* ------------------------------------------------------------------ */

object MissionLogic {
    private const val STEP = MissionRules.DISTANCE_STEP_METERS
    private const val MIN_DISTANCE = MissionRules.MIN_MISSION_DISTANCE_METERS

    /* ---------- 표시 도우미 ---------- */

    /** 432 → "7′12″" */
    fun formatPace(secondsPerKm: Int?): String =
        if (secondsPerKm == null || secondsPerKm <= 0) "—"
        else "%d′%02d″".format(secondsPerKm / 60, secondsPerKm % 60)

    /** 2000 → "2km", 2500 → "2.5km", 2250 → "2.25km" */
    fun formatKm(meters: Number): String {
        val km = (meters.toDouble() / 10.0).roundToInt() / 100.0
        return (if (km % 1.0 == 0.0) km.toInt().toString() else km.toString()) + "km"
    }

    fun paceOf(distanceMeters: Number, movingSeconds: Number): Int? {
        val d = distanceMeters.toDouble(); val t = movingSeconds.toDouble()
        return if (d <= 0 || t <= 0) null else (t / (d / 1000.0)).roundToInt()
    }

    private fun floorTo(m: Double) = floor(m / STEP).toInt() * STEP
    private fun roundTo(m: Double) = (m / STEP).roundToInt() * STEP
    private fun clamp(v: Int, lo: Int, hi: Int) = min(max(v, lo), max(lo, hi))

    /* ---------- 초기 체력 (FR-01) ---------- */

    /**
     * 기기에 저장된 초기 측정 기록으로 진행률을 계산합니다 (오프라인 표시용).
     * 백엔드 summarizeAssessment 와 같은 규칙: 3km 이상 기록의 앞 3회, 총 시간 ÷ 총 거리.
     * runs 는 저장 순서(오래된 것부터)로 넘깁니다.
     */
    fun summarizeAssessment(runs: List<RunInput>): Assessment {
        val valid = runs.filter { it.distanceMeters >= MissionRules.ASSESSMENT_MIN_DISTANCE_METERS }
            .take(MissionRules.ASSESSMENT_REQUIRED_RUNS)
        val complete = valid.size == MissionRules.ASSESSMENT_REQUIRED_RUNS
        val pace = if (complete) paceOf(valid.sumOf { it.distanceMeters }, valid.sumOf { it.movingSeconds }) else null
        return Assessment(valid.size, MissionRules.ASSESSMENT_REQUIRED_RUNS, if (complete) "completed" else "in_progress", pace)
    }

    private data class Level(val level: Int, val label: String, val slowest: Int)
    private val levels = listOf(
        Level(5, "중상급", 315), Level(4, "중급", 360), Level(3, "초중급", 420),
        Level(2, "초급", 480), Level(1, "입문", Int.MAX_VALUE),
    )

    fun fitnessLevel(baselinePaceSecondsPerKm: Int): Fitness {
        val l = levels.first { baselinePaceSecondsPerKm <= it.slowest }
        return Fitness(l.level, l.label, baselinePaceSecondsPerKm)
    }

    /* ---------- 목표 검증 (FR-03) ---------- */

    /** "6:30", "6'30", "6분 30초", "6분" → 초/km. 실패하면 null */
    fun parsePace(input: String): Int? {
        val m = Regex("""^(\d{1,2})\s*[:'′분]\s*(\d{1,2})?\s*["″초]?$""").matchEntire(input.trim()) ?: return null
        val sec = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (sec >= 60) null else m.groupValues[1].toInt() * 60 + sec
    }

    /** errors 가 있으면 저장하지 않고, warnings 는 저장하되 안내합니다. 측정 중에도 호출 가능 */
    fun validateGoal(goal: Goal, assessment: Assessment? = null): GoalValidation {
        val errors = mutableListOf<GoalIssue>()
        val warnings = mutableListOf<GoalIssue>()
        val baseline = if (assessment?.completed == true) assessment.baselinePaceSecondsPerKm else null
        when (goal) {
            is Goal.Distance -> {
                val t = goal.targetDistanceMeters
                if (t < 1000) errors += GoalIssue("distance_too_short", "목표 거리는 1km 이상이어야 해요.")
                if (t > 42195) errors += GoalIssue("distance_too_long", "목표 거리는 42.195km 이하로 설정해 주세요.")
                if (errors.isEmpty() && baseline != null && fitnessLevel(baseline).level <= 2 && t > 12000)
                    warnings += GoalIssue("distance_ambitious", "현재 수준에서는 긴 목표예요. 미션이 단계적으로 거리를 늘려 갈게요.")
            }
            is Goal.Pace -> {
                val p = goal.targetPaceSecondsPerKm
                if (p < 180) errors += GoalIssue("pace_too_fast", "목표 페이스는 3′00″보다 느리게 설정해 주세요.")
                if (p > 720) errors += GoalIssue("pace_too_slow", "목표 페이스는 12′00″보다 빠르게 설정해 주세요.")
                if (errors.isEmpty() && baseline != null) {
                    if (p >= baseline) warnings += GoalIssue("pace_already_reached",
                        "이미 측정 기록(${formatPace(baseline)}/km)보다 느린 목표예요. 더 빠른 목표를 권장해요.")
                    else if ((baseline - p).toDouble() / baseline > 0.2) warnings += GoalIssue("pace_ambitious",
                        "측정 기록보다 20% 이상 빠른 목표예요. 장기 목표로 잡고 단계적으로 접근할게요.")
                }
            }
        }
        return GoalValidation(errors, warnings)
    }

    /* ---------- 훈련 상태 ---------- */

    fun createTrainingState(assessment: Assessment, goal: Goal): TrainingState {
        require(assessment.completed) { "assessment_not_completed" }
        val base = assessment.baselinePaceSecondsPerKm!!
        val level = fitnessLevel(base).level
        val easy = intArrayOf(0, 2000, 2500, 3000, 4000, 5000)[level]
        val tempo = intArrayOf(0, 1500, 2000, 2000, 3000, 3000)[level]
        return adjustStateForGoal(TrainingState(
            easyDistanceMeters = easy,
            longDistanceMeters = roundTo(easy * 1.5),
            tempoDistanceMeters = tempo,
            easyPaceSecondsPerKm = base + 60, // 대화 가능한 편한 속도
            tempoPaceSecondsPerKm = base - 5,  // 측정 페이스보다 약간 빠르게
        ), goal)
    }

    private data class Caps(val easyMax: Int, val longMax: Int, val tempoMax: Int, val fastestTempoPace: Int)

    private fun capsFor(goal: Goal): Caps = when (goal) {
        is Goal.Distance -> {
            val t = goal.targetDistanceMeters
            Caps(max(2000, floorTo(t * 0.7)), max(MIN_DISTANCE, t), max(MIN_DISTANCE, floorTo(t * 0.5)), 180)
        }
        is Goal.Pace -> {
            val ref = MissionRules.PACE_GOAL_REFERENCE_METERS
            // 목표보다 빠르게 훈련시키지 않음
            Caps(max(2000, ref), floorTo(ref * 1.5), ref, goal.targetPaceSecondsPerKm)
        }
    }

    /** 목표를 바꿨을 때도 호출해서 거리 상한과 페이스 하한을 다시 맞춥니다 */
    fun adjustStateForGoal(s: TrainingState, goal: Goal): TrainingState {
        val c = capsFor(goal)
        val tempoPace = max(s.tempoPaceSecondsPerKm, c.fastestTempoPace)
        return s.copy(
            easyDistanceMeters = clamp(s.easyDistanceMeters, MIN_DISTANCE, c.easyMax),
            longDistanceMeters = clamp(s.longDistanceMeters, MIN_DISTANCE, c.longMax),
            tempoDistanceMeters = clamp(s.tempoDistanceMeters, MIN_DISTANCE, c.tempoMax),
            tempoPaceSecondsPerKm = tempoPace,
            easyPaceSecondsPerKm = max(s.easyPaceSecondsPerKm, tempoPace + 45),
        )
    }

    /* ---------- 미션 추천 (FR-04) ---------- */

    /** "YYYY-MM-DD" → 1970-01-01 부터의 일수 (java.time 없이 계산) */
    fun dayNumber(date: String): Long {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(date) ?: throw IllegalArgumentException("invalid_date")
        var y = m.groupValues[1].toLong(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
        require(mo in 1..12 && d in 1..31) { "invalid_date" }
        if (mo <= 2) y -= 1
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (mo + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun readyForChallenge(goal: Goal, s: TrainingState): Boolean {
        if (s.consecutiveSuccess < 1) return false
        return when (goal) {
            is Goal.Distance -> s.longDistanceMeters >= goal.targetDistanceMeters
            is Goal.Pace -> s.tempoPaceSecondsPerKm <= goal.targetPaceSecondsPerKm + 5 &&
                s.tempoDistanceMeters >= MissionRules.PACE_GOAL_REFERENCE_METERS * 0.6
        }
    }

    /**
     * 오늘의 미션. 같은 입력이면 항상 같은 결과입니다.
     * @param trainingState 저장된 상태, 처음이면 null (Ready.trainingState 를 저장하세요)
     * @param date 기기 기준 오늘 "YYYY-MM-DD"
     */
    fun recommendMissions(assessment: Assessment, goal: Goal?, trainingState: TrainingState?, date: String): Recommendation {
        if (!assessment.completed) {
            val remaining = assessment.requiredRuns - assessment.completedRuns
            return Recommendation.Locked(assessment.completedRuns, assessment.requiredRuns, remaining,
                "3km 자유 주행을 ${remaining}번 더 완료하면 맞춤 미션이 열려요.")
        }
        val fitness = fitnessLevel(assessment.baselinePaceSecondsPerKm!!)
        if (goal == null) return Recommendation.NeedsGoal(fitness, "러닝 목표를 설정하면 맞춤 미션을 받을 수 있어요.")

        val s = trainingState?.let { adjustStateForGoal(it, goal) } ?: createTrainingState(assessment, goal)
        val even = dayNumber(date) % 2 == 0L
        val types = when {
            s.recoveryNext -> listOf(MissionType.EASY)
            goal is Goal.Distance -> listOf(MissionType.EASY, when {
                readyForChallenge(goal, s) -> MissionType.GOAL_CHALLENGE
                even -> MissionType.LONG
                else -> MissionType.TEMPO
            })
            goal is Goal.Pace -> listOf(MissionType.EASY, when {
                readyForChallenge(goal, s) -> MissionType.GOAL_CHALLENGE
                even -> MissionType.TEMPO
                s.tempoPaceSecondsPerKm - goal.targetPaceSecondsPerKm <= 30 -> MissionType.GOAL_PACE
                else -> MissionType.LONG
            })
            else -> listOf(MissionType.EASY)
        }
        return Recommendation.Ready(date, fitness, types.mapIndexed { i, t -> buildMission(t, i, date, goal, s) }, s)
    }

    private fun buildMission(type: String, index: Int, date: String, goal: Goal, s: TrainingState): Mission {
        val id = "$date-$index-$type"
        return when (type) {
            MissionType.EASY -> {
                val d = if (s.recoveryNext) max(MIN_DISTANCE, floorTo(s.easyDistanceMeters * 0.8)) else s.easyDistanceMeters
                Mission(id, date, type, "편안하게 ${formatKm(d)} 달리기",
                    if (s.recoveryNext) "최근 미션이 힘들었어요. 오늘은 가볍게 회복 러닝을 해요."
                    else "옆 사람과 대화할 수 있을 정도의 속도로 꾸준히 달려요.",
                    d, s.easyPaceSecondsPerKm + 45, s.easyPaceSecondsPerKm - 45, s.easyPaceSecondsPerKm)
            }
            MissionType.LONG -> Mission(id, date, type, "${formatKm(s.longDistanceMeters)} 끝까지 달리기",
                "속도보다 완주가 목표예요. 힘들면 천천히 달려도 괜찮아요.",
                s.longDistanceMeters, s.easyPaceSecondsPerKm + 60, s.easyPaceSecondsPerKm - 45, s.easyPaceSecondsPerKm + 10)
            MissionType.TEMPO -> Mission(id, date, type,
                "${formatKm(s.tempoDistanceMeters)}를 ${formatPace(s.tempoPaceSecondsPerKm)} 페이스로",
                "조금 숨이 차지만 유지할 수 있는 속도로 일정하게 달려요.",
                s.tempoDistanceMeters, s.tempoPaceSecondsPerKm + 5, s.tempoPaceSecondsPerKm - 30, s.tempoPaceSecondsPerKm)
            MissionType.GOAL_PACE -> {
                val p = (goal as Goal.Pace).targetPaceSecondsPerKm
                val d = max(MIN_DISTANCE, floorTo(s.tempoDistanceMeters * 0.5))
                Mission(id, date, type, "목표 페이스 ${formatPace(p)}로 ${formatKm(d)}",
                    "목표 페이스를 몸에 익혀요. 짧은 거리지만 페이스를 지키는 것이 핵심이에요.",
                    d, p + 5, p - 20, p)
            }
            else -> when (goal) { // GOAL_CHALLENGE
                is Goal.Distance -> Mission(id, date, type, "목표 도전! ${formatKm(goal.targetDistanceMeters)} 완주",
                    "지금까지의 훈련으로 목표에 도전할 준비가 됐어요. 페이스는 편하게 유지하세요.",
                    goal.targetDistanceMeters, s.easyPaceSecondsPerKm + 60, null, s.easyPaceSecondsPerKm + 10)
                is Goal.Pace -> Mission(id, date, type,
                    "목표 도전! ${formatKm(MissionRules.PACE_GOAL_REFERENCE_METERS)}를 ${formatPace(goal.targetPaceSecondsPerKm)}로",
                    "목표 페이스로 기준 거리를 완주하면 목표 달성이에요.",
                    MissionRules.PACE_GOAL_REFERENCE_METERS, goal.targetPaceSecondsPerKm + 3, null, goal.targetPaceSecondsPerKm)
            }
        }
    }

    /* ---------- 판정 (UC-02 4~5) ---------- */

    fun evaluateMission(mission: Mission, run: RunInput): MissionResult {
        val pace = paceOf(run.distanceMeters, run.movingSeconds)
        val ratio = run.distanceMeters.toDouble() / mission.targetDistanceMeters
        val distanceOk = ratio >= MissionRules.DISTANCE_TOLERANCE
        val paceOk = pace != null && pace <= mission.maxPaceSecondsPerKm
        val reason = when {
            distanceOk && paceOk -> null
            !distanceOk && !paceOk -> "both"
            !distanceOk -> "distance_short"
            else -> "pace_too_slow"
        }
        return MissionResult(
            mission.id, if (reason == null) "success" else "failed", reason,
            run.distanceMeters, run.movingSeconds, pace, (ratio * 1000).roundToInt() / 1000.0,
            pace != null && mission.minPaceSecondsPerKm != null && pace < mission.minPaceSecondsPerKm,
        )
    }

    /* ---------- 난이도 조정 (UC-02 4a) ---------- */

    fun applyMissionResult(trainingState: TrainingState, goal: Goal, mission: Mission, result: MissionResult): AppliedResult =
        if (result.success) onSuccess(trainingState, goal, mission, result) else onFail(trainingState, goal, mission, result)

    private fun onSuccess(s0: TrainingState, goal: Goal, m: Mission, r: MissionResult): AppliedResult {
        val streak = s0.consecutiveSuccess + 1
        var s = s0.copy(consecutiveSuccess = streak, consecutiveFail = 0, recoveryNext = false,
            completedMissionCount = s0.completedMissionCount + 1)
        when (m.type) {
            MissionType.EASY -> {
                val easyStreak = s0.easySuccessStreak + 1
                s = s.copy(easySuccessStreak = easyStreak,
                    easyDistanceMeters = s.easyDistanceMeters + if (easyStreak % 2 == 0) STEP else 0)
            }
            MissionType.LONG -> s = s.copy(longDistanceMeters = max(s.longDistanceMeters + STEP, roundTo(s.longDistanceMeters * 1.1)))
            MissionType.TEMPO -> s = s.copy(tempoPaceSecondsPerKm = s.tempoPaceSecondsPerKm - 3,
                tempoDistanceMeters = s.tempoDistanceMeters + if (streak % 3 == 0) STEP else 0)
            MissionType.GOAL_PACE -> s = s.copy(tempoPaceSecondsPerKm = s.tempoPaceSecondsPerKm - 2)
        }
        s = adjustStateForGoal(s.copy(easyPaceSecondsPerKm = min(s.easyPaceSecondsPerKm, s.tempoPaceSecondsPerKm + 65)), goal)

        val hint = when (m.type) {
            MissionType.EASY -> if (s.easyDistanceMeters > s0.easyDistanceMeters) "편안한 러닝 거리가 ${formatKm(s.easyDistanceMeters)}로 늘어나요."
                else "이 흐름을 유지하면 편안한 러닝이 점점 더 쉬워져요."
            MissionType.LONG -> "다음 거리 늘리기 미션은 ${formatKm(s.longDistanceMeters)}예요."
            MissionType.TEMPO -> "다음 템포 러닝은 ${formatPace(s.tempoPaceSecondsPerKm)}/km로 조금 빨라져요."
            MissionType.GOAL_PACE -> "목표 페이스에 한 걸음 더 가까워졌어요."
            else -> "목표를 달성했어요! 새로운 목표를 설정해 보세요."
        }
        val pacePart = r.paceSecondsPerKm?.let { " ${formatPace(it)}/km로" } ?: ""
        val fastPart = if (r.ranTooFast) " 다만 권장보다 많이 빨랐어요. 무리하지 않게 페이스를 조절해 주세요." else ""
        val achieved = m.type == MissionType.GOAL_CHALLENGE
        return AppliedResult(s, achieved, Feedback(
            if (achieved) "목표 달성!" else "미션 성공!",
            "${formatKm(r.distanceMeters)}를$pacePart 완주했어요.$fastPart", hint))
    }

    private fun onFail(s0: TrainingState, goal: Goal, m: Mission, r: MissionResult): AppliedResult {
        val fails = s0.consecutiveFail + 1
        var s = s0.copy(consecutiveFail = fails, consecutiveSuccess = 0, recoveryNext = fails >= 2,
            easySuccessStreak = 0, completedMissionCount = s0.completedMissionCount + 1)
        val hints = mutableListOf<String>()

        if (r.failReason == "distance_short" || r.failReason == "both") {
            if (r.distanceRatio >= MissionRules.RETRY_THRESHOLD) {
                hints += "거의 다 왔어요. 같은 거리로 한 번 더 도전해요."
            } else {
                val target = m.targetDistanceMeters
                val next = clamp(floorTo(r.distanceMeters.toDouble()),
                    max(MIN_DISTANCE, roundTo(target * MissionRules.MAX_DECREASE_RATIO)), max(MIN_DISTANCE, target - STEP))
                s = when (m.type) {
                    MissionType.EASY -> s.copy(easyDistanceMeters = next)
                    MissionType.LONG, MissionType.GOAL_CHALLENGE -> s.copy(longDistanceMeters = min(s.longDistanceMeters, next))
                    else -> s.copy(tempoDistanceMeters = min(s.tempoDistanceMeters, next))
                }
                hints += "다음 미션 거리를 ${formatKm(next)}로 조정했어요."
            }
        }
        if (r.failReason == "pace_too_slow" || r.failReason == "both") {
            val half = ((r.paceSecondsPerKm ?: m.maxPaceSecondsPerKm) - m.maxPaceSecondsPerKm) / 2
            if (m.type == MissionType.EASY || m.type == MissionType.LONG) {
                s = s.copy(easyPaceSecondsPerKm = s.easyPaceSecondsPerKm + clamp(half, 5, 15))
                hints += "편안한 러닝 기준 페이스를 ${formatPace(s.easyPaceSecondsPerKm)}/km로 조정했어요."
            } else {
                s = s.copy(tempoPaceSecondsPerKm = s.tempoPaceSecondsPerKm + clamp(half, 3, 10))
                hints += "다음 템포 페이스를 ${formatPace(s.tempoPaceSecondsPerKm)}/km로 완화했어요."
            }
        }
        if (s.recoveryNext) hints += "다음에는 회복 러닝 하나만 준비할게요."
        s = adjustStateForGoal(s, goal)

        val percent = min(100, (r.distanceRatio * 100).roundToInt())
        val pacePart = r.paceSecondsPerKm?.let { ", 평균 ${formatPace(it)}/km" } ?: ""
        return AppliedResult(s, false, Feedback("아쉽게 미달성",
            "목표 ${formatKm(m.targetDistanceMeters)} 중 ${formatKm(r.distanceMeters)} ($percent%)$pacePart",
            hints.joinToString(" ")))
    }

    /* ---------- 실시간 진행 (러닝 측정 화면, 1초마다 호출) ---------- */

    private val paceCritical = setOf(MissionType.TEMPO, MissionType.GOAL_PACE, MissionType.GOAL_CHALLENGE)

    /** movingSeconds 는 일시정지·GPS 끊김 구간을 뺀 시간입니다 */
    fun trackMission(m: Mission, distanceMeters: Double, movingSeconds: Long, gpsAvailable: Boolean = true): MissionProgress {
        val target = m.targetDistanceMeters.toDouble()
        val remaining = (target - distanceMeters).coerceAtLeast(0.0).roundToInt()
        val reached = distanceMeters >= target * MissionRules.DISTANCE_TOLERANCE
        val pace = if (distanceMeters < MissionRules.PACE_WARMUP_METERS) null else paceOf(distanceMeters, movingSeconds)
        val status = when {
            !gpsAvailable -> PaceStatus.GPS_LOST
            pace == null -> PaceStatus.MEASURING
            pace > m.maxPaceSecondsPerKm -> PaceStatus.TOO_SLOW
            m.minPaceSecondsPerKm != null && pace < m.minPaceSecondsPerKm -> PaceStatus.TOO_FAST
            else -> PaceStatus.ON_TARGET
        }
        val required = if (m.type in paceCritical && !reached && remaining > 0) {
            val left = m.maxPaceSecondsPerKm * target / 1000.0 - movingSeconds
            if (left <= 0) 0 else (left / (remaining / 1000.0)).roundToInt()
        } else null
        return MissionProgress((distanceMeters / target).coerceIn(0.0, 1.0).toFloat(), remaining, pace, status, reached, required)
    }
}
