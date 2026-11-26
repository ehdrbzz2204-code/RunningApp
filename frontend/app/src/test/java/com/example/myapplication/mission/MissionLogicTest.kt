package com.example.myapplication.mission

import com.example.myapplication.mission.MissionLogic.applyMissionResult
import com.example.myapplication.mission.MissionLogic.evaluateMission
import com.example.myapplication.mission.MissionLogic.recommendMissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class MissionLogicTest {

    /** 3km 를 pace 로 3번 달린 초기 측정 결과 (백엔드 GET /assessment 와 같은 모양) */
    private fun assessed(pace: Int) = MissionLogic.summarizeAssessment(List(3) { RunInput(3000, pace * 3) })
    private fun run(distance: Double, pace: Int) = RunInput(distance.roundToInt(), (distance / 1000 * pace).roundToInt())
    private fun ready(r: Recommendation) = r as Recommendation.Ready

    private val evenDay = "2026-11-26"
    private val oddDay = "2026-11-27"

    @Test fun `FR-01 초기 측정은 3km 이상 앞 3회, 거리 가중 평균 페이스`() {
        val two = MissionLogic.summarizeAssessment(listOf(RunInput(3000, 1260), RunInput(2990, 1200), RunInput(3100, 1300)))
        assertEquals(2, two.completedRuns)
        assertEquals("in_progress", two.status)

        val done = MissionLogic.summarizeAssessment(listOf(RunInput(3000, 1260), RunInput(3000, 1300), RunInput(3000, 1330), RunInput(5000, 1500)))
        assertEquals("completed", done.status)
        assertEquals(432, done.baselinePaceSecondsPerKm) // (1260+1300+1330)/9km, 4번째 기록은 무시
    }

    @Test fun `UC-02 2a 측정 3회 미만이면 잠김과 남은 횟수`() {
        val two = MissionLogic.summarizeAssessment(listOf(RunInput(3000, 1260), RunInput(3100, 1300)))
        val r = recommendMissions(two, Goal.Distance(5000), null, evenDay) as Recommendation.Locked
        assertEquals(2, r.completedRuns)
        assertEquals(1, r.remainingRuns)
    }

    @Test fun `측정 완료 + 목표 없음이면 목표 설정 유도`() {
        assertTrue(recommendMissions(assessed(432), null, null, evenDay) is Recommendation.NeedsGoal)
    }

    @Test fun `체력 레벨 경계`() {
        assertEquals(listOf(5, 4, 3, 2, 1), listOf(315, 316, 420, 480, 481).map { MissionLogic.fitnessLevel(it).level })
    }

    @Test fun `FR-03 목표 범위와 현실성 확인`() {
        assertTrue(MissionLogic.validateGoal(Goal.Distance(5000)).valid)
        assertEquals("distance_too_short", MissionLogic.validateGoal(Goal.Distance(500)).errors[0].code)
        assertEquals("pace_too_fast", MissionLogic.validateGoal(Goal.Pace(150)).errors[0].code)
        val a = assessed(432)
        assertEquals("pace_already_reached", MissionLogic.validateGoal(Goal.Pace(450), a).warnings[0].code)
        assertEquals("pace_ambitious", MissionLogic.validateGoal(Goal.Pace(300), a).warnings[0].code)
        assertTrue(MissionLogic.validateGoal(Goal.Pace(400), a).warnings.isEmpty())
        assertTrue(MissionLogic.validateGoal(Goal.Pace(400), MissionLogic.summarizeAssessment(emptyList())).valid)
    }

    @Test fun `페이스 입력 변환`() {
        assertEquals(390, MissionLogic.parsePace("6:30"))
        assertEquals(432, MissionLogic.parsePace("7분 12초"))
        assertEquals(360, MissionLogic.parsePace("6분"))
        assertNull(MissionLogic.parsePace("6:75"))
    }

    @Test fun `FR-04 거리와 페이스가 명시되고 체력에 따라 다르다`() {
        val goal = Goal.Distance(10000)
        val beginner = ready(recommendMissions(assessed(560), goal, null, evenDay))
        val mid = ready(recommendMissions(assessed(345), goal, null, evenDay))
        for (m in beginner.missions + mid.missions) {
            assertTrue(m.targetDistanceMeters >= 1000 && m.maxPaceSecondsPerKm > 0 && m.recommendedPaceSecondsPerKm > 0)
        }
        assertEquals("편안하게 2km 달리기", beginner.missions[0].title)
        assertTrue(beginner.missions[0].targetDistanceMeters < mid.missions[0].targetDistanceMeters)
        assertTrue(beginner.missions[0].recommendedPaceSecondsPerKm > mid.missions[0].recommendedPaceSecondsPerKm)
    }

    @Test fun `FR-04 두 번째 미션은 날짜별로 거리 늘리기와 템포가 번갈아 나온다`() {
        val types = listOf(evenDay, oddDay).map { ready(recommendMissions(assessed(432), Goal.Distance(10000), null, it)).missions[1].type }
        assertEquals(setOf(MissionType.LONG, MissionType.TEMPO), types.toSet())
    }

    @Test fun `같은 입력이면 같은 미션`() {
        val a = ready(recommendMissions(assessed(432), Goal.Pace(400), null, evenDay))
        val b = ready(recommendMissions(assessed(432), Goal.Pace(400), a.trainingState, evenDay))
        assertEquals(a.missions, b.missions)
    }

    @Test fun `페이스 목표면 목표보다 빠르게 훈련시키지 않는다`() {
        assertTrue(MissionLogic.createTrainingState(assessed(300), Goal.Pace(330)).tempoPaceSecondsPerKm >= 330)
    }

    @Test fun `판정 GPS 오차 2퍼센트 허용하고 실제 값 저장`() {
        val m = ready(recommendMissions(assessed(432), Goal.Distance(5000), null, evenDay)).missions[0]
        assertTrue(evaluateMission(m, run(m.targetDistanceMeters * 0.985, m.recommendedPaceSecondsPerKm)).success)
        val short = evaluateMission(m, run(1200.0, 480))
        assertEquals("distance_short", short.failReason)
        assertEquals(1200, short.distanceMeters)
    }

    @Test fun `UC-02 4a 미달성이면 다음 거리를 낮추되 최대 20퍼센트까지만`() {
        val goal = Goal.Distance(10000)
        val rec = ready(recommendMissions(assessed(432), goal, null, evenDay))
        val m = rec.missions[0] // 편안하게 2.5km
        val next = applyMissionResult(rec.trainingState, goal, m, evaluateMission(m, run(1200.0, 480)))
        assertEquals(2000, next.trainingState.easyDistanceMeters)
        assertTrue(next.feedback.nextHint.contains("2km"))
        assertEquals("아쉽게 미달성", next.feedback.title)
    }

    @Test fun `90퍼센트 이상 달렸으면 같은 거리로 재도전`() {
        val goal = Goal.Distance(10000)
        val rec = ready(recommendMissions(assessed(432), goal, null, evenDay))
        val m = rec.missions[0]
        val next = applyMissionResult(rec.trainingState, goal, m, evaluateMission(m, run(m.targetDistanceMeters * 0.92, 480)))
        assertEquals(rec.trainingState.easyDistanceMeters, next.trainingState.easyDistanceMeters)
    }

    @Test fun `페이스가 느려 미달성이면 페이스를 완화`() {
        val goal = Goal.Pace(400)
        val rec = ready(recommendMissions(assessed(432), goal, null, evenDay))
        val tempo = listOf(evenDay, oddDay).flatMap { ready(recommendMissions(assessed(432), goal, null, it)).missions }
            .first { it.type == MissionType.TEMPO }
        val result = evaluateMission(tempo, run(tempo.targetDistanceMeters.toDouble(), tempo.maxPaceSecondsPerKm + 20))
        assertEquals("pace_too_slow", result.failReason)
        val next = applyMissionResult(rec.trainingState, goal, tempo, result)
        assertEquals(rec.trainingState.tempoPaceSecondsPerKm + 10, next.trainingState.tempoPaceSecondsPerKm)
    }

    @Test fun `2회 연속 미달성이면 다음은 회복 러닝 1개`() {
        val goal = Goal.Distance(10000)
        var state: TrainingState? = null
        for (date in listOf(evenDay, oddDay)) {
            val rec = ready(recommendMissions(assessed(432), goal, state, date))
            val m = rec.missions[0]
            state = applyMissionResult(rec.trainingState, goal, m, evaluateMission(m, run(500.0, 500))).trainingState
        }
        val rec = ready(recommendMissions(assessed(432), goal, state, "2026-11-28"))
        assertEquals(1, rec.missions.size)
        assertTrue(rec.missions[0].description.contains("회복"))
    }

    @Test fun `편안한 러닝은 2회 연속 성공마다 250m 늘어난다`() {
        val goal = Goal.Distance(10000)
        var state: TrainingState? = null
        for (date in listOf(evenDay, oddDay)) {
            val rec = ready(recommendMissions(assessed(432), goal, state, date))
            state = rec.trainingState
            for (m in rec.missions) {
                state = applyMissionResult(state!!, goal, m,
                    evaluateMission(m, run(m.targetDistanceMeters.toDouble(), m.recommendedPaceSecondsPerKm))).trainingState
            }
        }
        assertEquals(2750, state!!.easyDistanceMeters)
    }

    private fun reachesGoal(goal: Goal): TrainingState {
        var state: TrainingState? = null
        val start = MissionLogic.dayNumber("2026-11-01")
        for (i in 0 until 60) {
            val date = dateOf(start + i)
            val rec = ready(recommendMissions(assessed(432), goal, state, date))
            state = rec.trainingState
            for (m in rec.missions) {
                val result = evaluateMission(m, run(m.targetDistanceMeters.toDouble(), m.recommendedPaceSecondsPerKm))
                assertTrue("${m.title}은 권장 페이스로 완주하면 성공해야 함", result.success)
                val next = applyMissionResult(state!!, goal, m, result)
                state = next.trainingState
                if (next.goalAchieved) return state
            }
        }
        throw AssertionError("목표 도전까지 도달하지 못함")
    }

    @Test fun `꾸준히 성공하면 거리 목표 도전에 도달`() {
        assertTrue(reachesGoal(Goal.Distance(5000)).longDistanceMeters <= 5000)
    }

    @Test fun `꾸준히 성공하면 페이스 목표 도전에 도달`() {
        assertTrue(reachesGoal(Goal.Pace(400)).tempoPaceSecondsPerKm >= 400)
    }

    @Test fun `실시간 진행률 남은 거리 필요 페이스 GPS 끊김`() {
        val easy = Mission("e", evenDay, MissionType.EASY, "", "", 3000, 480, 360, 432)
        val live = MissionLogic.trackMission(easy, 2100.0, 907)
        assertEquals(900, live.remainingMeters)
        assertEquals(PaceStatus.ON_TARGET, live.paceStatus)
        assertNull(live.requiredPaceForRemaining)

        val tempo = Mission("t", evenDay, MissionType.TEMPO, "", "", 2000, 400, 370, 395)
        val slow = MissionLogic.trackMission(tempo, 1000.0, 420)
        assertEquals(PaceStatus.TOO_SLOW, slow.paceStatus)
        assertEquals(380, slow.requiredPaceForRemaining)
        assertEquals(PaceStatus.GPS_LOST, MissionLogic.trackMission(tempo, 1000.0, 430, gpsAvailable = false).paceStatus)
        assertEquals(PaceStatus.MEASURING, MissionLogic.trackMission(tempo, 150.0, 60).paceStatus)
    }

    @Test fun `표시 형식과 날짜 계산`() {
        assertEquals("7′12″", MissionLogic.formatPace(432))
        assertEquals("—", MissionLogic.formatPace(null))
        assertEquals(listOf("2km", "2.5km", "2.25km"), listOf(2000, 2500, 2250).map { MissionLogic.formatKm(it) })
        assertEquals(0L, MissionLogic.dayNumber("1970-01-01"))
        assertEquals(20418L, MissionLogic.dayNumber("2025-11-26"))
    }

    /** 테스트용: 1970-01-01 부터의 일수 → "YYYY-MM-DD" */
    private fun dateOf(days: Long): String {
        val z = days + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "%04d-%02d-%02d".format(y, m, d)
    }
}
