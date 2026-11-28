package com.example.myapplication

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun RunResultScreen(
    modifier: Modifier, run: JSONObject, data: RunnerData?, pending: Boolean,
    error: String?, retry: () -> Unit, close: () -> Unit
) {
    val id = run.getString("clientRunId")
    val synced = data?.history?.find { it.optString("clientRunId") == id }
    val meters = run.getInt("distanceMeters")
    val seconds = run.getLong("movingSeconds")
    val pace = if (meters > 0) (seconds * 1000.0 / meters).roundToInt() else null
    val type = run.getString("type")
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        HomeHeading(when(type) { "mission" -> "미션 주행"; "assessment" -> "초기 체력 측정"; else -> "자유 주행" }, "러닝을 마쳤어요", 25)
        CardBox(Mist) {
            Label("달린 거리", 12)
            Label(km(meters) + " km", 32, true)
            HorizontalResultMetrics(seconds, pace)
        }
        if (type == "assessment") {
            CardBox {
                Label("초기 측정 결과", 16, true)
                Label(if (meters < 3000) "3km 미만으로 종료해 기록만 저장했어요."
                    else "3km 완주 조건을 충족했어요.")
                if (synced != null && data != null) {
                    Label("완료한 초기 측정  ${data.completed} / 3회")
                    data.baseline?.let { Label("기준 페이스  " + PaceCalculator.formatPace(it), 16, true) }
                }
            }
        }
        if (type == "mission") {
            val result = synced?.optJSONObject("missionResult")
            CardBox {
                Label("미션 결과",16,true)
                if (result != null) MissionFeedback(result) else Label("서버 확인 후 결과가 표시돼요.")
            }
        }
        Label(when { synced != null -> "서버 저장 완료"; pending -> "기기에 저장됨 · 서버 전송 대기"; else -> "기기에 저장됨 · 서버 결과 확인 중" },13,false,Muted)
        if (error != null && synced == null) { Label(error); TextButton(retry) { Text("다시 시도") } }
        Action("확인", close)
    }
}

@Composable
private fun HorizontalResultMetrics(seconds: Long, pace: Int?) {
    Stats(listOf(String.format(Locale.ROOT,"%d:%02d",seconds / 60, seconds % 60) to "러닝 시간",
        PaceCalculator.formatPace(pace) to "평균 페이스"))
}


@Composable
internal fun MissionFeedback(result: JSONObject) {
    val feedback = result.optJSONObject("feedback")
    val title = feedback?.optString("title").orEmpty().ifBlank {
        when (result.optString("outcome")) {
            "success" -> "미션 성공"
            "failed" -> "미션 미달성"
            else -> "미션 결과"
        }
    }
    Label(title, 18, true)
    feedback?.optString("message")?.takeIf { it.isNotBlank() }?.let { Label(it) }
    val reason = when (result.optString("failReason")) {
        "distance_short" -> "목표 거리 기준을 채우지 못했어요."
        "pace_too_slow" -> "목표 페이스 기준을 충족하지 못했어요."
        "both" -> "거리와 페이스 기준을 모두 충족하지 못했어요."
        else -> null
    }
    reason?.let { Label("미달성 사유 · $it", 14) }
    feedback?.optString("nextHint")?.takeIf { it.isNotBlank() }?.let {
        Label("다음 훈련", 13, true, Muted)
        Label(it)
    }
}
