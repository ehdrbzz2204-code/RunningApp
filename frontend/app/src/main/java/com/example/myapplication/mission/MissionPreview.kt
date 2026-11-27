package com.example.myapplication.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.myapplication.Action
import com.example.myapplication.CardBox
import com.example.myapplication.HomeHeading
import com.example.myapplication.Ink
import com.example.myapplication.Label
import com.example.myapplication.Lime
import com.example.myapplication.Mist
import com.example.myapplication.MissionRecommendation
import com.example.myapplication.Muted
import com.example.myapplication.Paper
import com.example.myapplication.MissionContent
import kotlin.math.roundToInt

/*
 * 미션 로직 미리보기 (개발 확인용).
 * 서버 없이 가짜 초기 측정 기록으로 미션 추천 → 성공/실패 → 다음 날 난이도 변화와
 * 러닝 중 진행률을 확인합니다. 실제 화면 코드(MainActivity 등)는 건드리지 않습니다.
 *
 * 보는 방법: 이 파일을 열고 오른쪽 위 Split/Design 으로 미리보기를 보거나,
 * 각 @Preview 함수 왼쪽 ▶ 아이콘 → Run Preview 로 에뮬레이터에 띄웁니다.
 */

private val START_DAY = MissionLogic.dayNumber("2026-11-26")

private val goalOptions: List<Pair<String, Goal?>> = listOf(
    "5km 완주" to Goal.Distance(5000),
    "10km 완주" to Goal.Distance(10000),
    "6′40″ 페이스" to Goal.Pace(400),
    "목표 없음" to null,
)

@Composable
internal fun MissionPlayground() {
    var runsDone by remember { mutableStateOf(3) }
    var basePace by remember { mutableStateOf(432f) }
    var goalIndex by remember { mutableStateOf(0) }
    var day by remember { mutableStateOf(0) }
    var dayStartState by remember { mutableStateOf<TrainingState?>(null) } // 오늘 미션을 만든 기준 상태
    var state by remember { mutableStateOf<TrainingState?>(null) }         // 결과가 반영된 최신 상태
    var doneIds by remember { mutableStateOf(setOf<String>()) }
    var feedback by remember { mutableStateOf<AppliedResult?>(null) }
    var trackingId by remember { mutableStateOf<String?>(null) }
    var liveDistance by remember { mutableStateOf(0f) }
    var livePace by remember { mutableStateOf(432f) }

    fun reset() {
        day = 0; dayStartState = null; state = null; doneIds = emptySet(); feedback = null; trackingId = null
    }

    val assessment = MissionLogic.summarizeAssessment(List(runsDone) { RunInput(3000, basePace.roundToInt() * 3) })
    val goal = goalOptions[goalIndex].second
    val date = dateOf(START_DAY + day)
    val rec = MissionLogic.recommendMissions(assessment, goal, dayStartState, date)

    Column(
        Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HomeHeading("미션 로직 미리보기", "가짜 기록으로 확인해요", 22)

        CardBox {
            Label("초기 측정 완료: $runsDone / 3", 14, true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..3).forEach { n ->
                    FilterChip(selected = runsDone == n, onClick = { runsDone = n; reset() }, label = { Text("${n}회") })
                }
            }
            Label("3km 측정 페이스: ${MissionLogic.formatPace(basePace.roundToInt())}/km", 14, true)
            Slider(value = basePace, onValueChange = { basePace = it; reset() }, valueRange = 300f..600f)
            Label("목표", 14, true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                goalOptions.forEachIndexed { i, (name, _) ->
                    FilterChip(selected = goalIndex == i, onClick = { goalIndex = i; reset() }, label = { Text(name) })
                }
            }
        }

        when (rec) {
            is Recommendation.Locked -> CardBox(Mist) {
                Label("미션 잠김 · ${rec.completedRuns}/${rec.requiredRuns}", 16, true)
                Label(rec.message, 13, false, Muted)
            }
            is Recommendation.NeedsGoal -> CardBox(Mist) {
                Label("체력 ${rec.fitness.level}레벨 · ${rec.fitness.label}", 16, true)
                Label(rec.message, 13, false, Muted)
            }
            is Recommendation.Ready -> {
                val activeGoal = goal!!
                CardBox(Ink) {
                    Label("${day + 1}일차 · $date", 12, false, Color.LightGray)
                    Label("체력 ${rec.fitness.level}레벨 ${rec.fitness.label} · 기준 ${MissionLogic.formatPace(rec.fitness.baselinePaceSecondsPerKm)}/km", 16, true, Color.White)
                    Label("오늘의 미션 ${rec.missions.count { it.id in doneIds }}/${rec.missions.size} 완료", 13, true, Lime)
                }

                rec.missions.forEach { m ->
                    MissionCard(
                        m, done = m.id in doneIds,
                        onTrack = { trackingId = m.id; liveDistance = m.targetDistanceMeters * 0.5f; livePace = m.recommendedPaceSecondsPerKm.toFloat() },
                        onSuccess = {
                            val run = RunInput(m.targetDistanceMeters, (m.targetDistanceMeters / 1000.0 * m.recommendedPaceSecondsPerKm).roundToInt())
                            val applied = MissionLogic.applyMissionResult(state ?: rec.trainingState, activeGoal, m, MissionLogic.evaluateMission(m, run))
                            state = applied.trainingState; feedback = applied; doneIds = doneIds + m.id
                        },
                        onFail = {
                            val d = m.targetDistanceMeters * 0.7
                            val run = RunInput(d.roundToInt(), (d / 1000.0 * (m.maxPaceSecondsPerKm + 20)).roundToInt())
                            val applied = MissionLogic.applyMissionResult(state ?: rec.trainingState, activeGoal, m, MissionLogic.evaluateMission(m, run))
                            state = applied.trainingState; feedback = applied; doneIds = doneIds + m.id
                        },
                    )
                }

                feedback?.let { f ->
                    CardBox(if (f.goalAchieved) Lime else Color.White) {
                        Label(f.feedback.title, 17, true)
                        Label(f.feedback.message, 13)
                        Label(f.feedback.nextHint, 13, true)
                    }
                }

                if (rec.missions.all { it.id in doneIds }) {
                    Action("다음 날로 넘어가기") {
                        dayStartState = state; day += 1; doneIds = emptySet(); feedback = null; trackingId = null
                    }
                }

                rec.missions.firstOrNull { it.id == trackingId }?.let { m ->
                    LiveCard(m, liveDistance, livePace, { liveDistance = it }, { livePace = it })
                }
            }
        }
    }
}

@Composable
private fun MissionCard(m: Mission, done: Boolean, onTrack: () -> Unit, onSuccess: () -> Unit, onFail: () -> Unit) {
    CardBox(if (done) Mist else Color.White) {
        Label(typeLabel(m.type), 11, true, Muted)
        Label(m.title, 17, true)
        Label(m.description, 12, false, Muted)
        Label("성공 조건: ${MissionLogic.formatKm(m.targetDistanceMeters)} 이상, 평균 ${MissionLogic.formatPace(m.maxPaceSecondsPerKm)}/km보다 빠르게", 12, true)
        Label("권장 ${MissionLogic.formatPace(m.recommendedPaceSecondsPerKm)}/km" +
            (m.minPaceSecondsPerKm?.let { " · ${MissionLogic.formatPace(it)}/km보다 빠르면 무리" } ?: ""), 12, false, Muted)
        if (done) {
            Label("완료한 미션이에요", 13, true, Muted)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onTrack, Modifier.weight(1f)) { Text("러닝 중") }
                Button(onSuccess, Modifier.weight(1f)) { Text("성공") }
                OutlinedButton(onFail, Modifier.weight(1f)) { Text("실패") }
            }
        }
    }
}

@Composable
private fun LiveCard(m: Mission, distance: Float, pace: Float, onDistance: (Float) -> Unit, onPace: (Float) -> Unit) {
    val moving = (distance / 1000f * pace).toLong()
    val p = MissionLogic.trackMission(m, distance.toDouble(), moving)
    CardBox {
        Label("러닝 중 화면 · ${m.title}", 14, true)
        Label("${MissionLogic.formatKm(distance.roundToInt())} / ${MissionLogic.formatKm(m.targetDistanceMeters)} · ${MissionLogic.formatKm(p.remainingMeters)} 남음", 16, true)
        Box(Modifier.fillMaxWidth().height(8.dp).background(Mist, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(p.progress).fillMaxHeight().background(Lime, RoundedCornerShape(4.dp)))
        }
        Label("현재 평균 ${MissionLogic.formatPace(p.paceSecondsPerKm)}/km · ${statusText(p.paceStatus)}", 13)
        p.requiredPaceForRemaining?.let {
            Label(if (it == 0) "이번엔 페이스 조건 달성이 어려워요" else "남은 거리는 ${MissionLogic.formatPace(it)}/km 이내로 달려야 해요", 13, true)
        }
        if (p.distanceReached) Label("미션 거리 달성!", 13, true)
        Label("거리 조절", 11, false, Muted)
        Slider(value = distance, onValueChange = onDistance, valueRange = 0f..(m.targetDistanceMeters * 1.1f))
        Label("평균 페이스 조절", 11, false, Muted)
        Slider(value = pace, onValueChange = onPace, valueRange = 300f..660f)
    }
}

private fun typeLabel(type: String) = when (type) {
    MissionType.EASY -> "편안한 러닝"
    MissionType.LONG -> "거리 늘리기"
    MissionType.TEMPO -> "템포 러닝"
    MissionType.GOAL_PACE -> "목표 페이스 체험"
    else -> "목표 도전"
}

private fun statusText(s: PaceStatus) = when (s) {
    PaceStatus.MEASURING -> "측정 중"
    PaceStatus.ON_TARGET -> "좋아요"
    PaceStatus.TOO_SLOW -> "조금 느려요"
    PaceStatus.TOO_FAST -> "너무 빨라요"
    PaceStatus.GPS_LOST -> "GPS 끊김"
}

/** 1970-01-01 부터의 일수 → "YYYY-MM-DD" */
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

private val previewColors = lightColorScheme(primary = Ink, background = Paper, surface = Color.White, onSurface = Ink)

/** 가짜 기록으로 미션 추천·판정·난이도 변화를 직접 눌러보는 화면 */
@Preview(name = "미션 로직 놀이터", showBackground = true, heightDp = 1600)
@Composable
private fun MissionPlaygroundPreview() {
    MaterialTheme(colorScheme = previewColors) { MissionPlayground() }
}

/** 실제 앱 화면에 미션이 열린 모습 (초기 측정 3회 완료 + 5km 목표 가정) */
@Preview(name = "앱 화면 - 미션 열림", showBackground = true, heightDp = 900)
@Composable
private fun AppWithMissionPreview() {
    val assessment = MissionLogic.summarizeAssessment(List(3) { RunInput(3000, 1296) })
    val first = (MissionLogic.recommendMissions(assessment, Goal.Distance(5000), null, "2026-11-26") as Recommendation.Ready).missions[0]
    MaterialTheme(colorScheme = previewColors) { MissionContent(MissionRecommendation(first.title, first.description), {}, 3) }
}

