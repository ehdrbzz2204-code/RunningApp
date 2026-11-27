package com.example.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

private val korea = TimeZone.getTimeZone("Asia/Seoul")
private fun dateFormat(pattern: String, zone: TimeZone = korea) =
    SimpleDateFormat(pattern, Locale.KOREA).apply { timeZone = zone }

private data class DayTotals(var meters: Long = 0, var seconds: Long = 0) {
    val pace get() = if (meters == 0L) null else (seconds * 1000.0 / meters).roundToInt()
}

@Composable
internal fun RecordsContent(data: RunnerData) {
    var monthly by rememberSaveable { mutableStateOf(false) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    val stamp = remember { dateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC")) }
    val runs = remember(data.history) {
        data.history.map { run -> run to run.optString("finishedAt").takeIf { !it.isNullOrBlank() && it!="null" }?.let { stamp.parse(it) } }
    }
    val current = if (monthly) data.month else data.week
    val date = remember { dateFormat("yyyy-MM-dd") }
    val start = Calendar.getInstance(korea).apply {
        time = date.parse(current.getString("startDate"))!!
        add(if (monthly) Calendar.MONTH else Calendar.WEEK_OF_YEAR, offset)
    }
    val end = (start.clone() as Calendar).apply { add(if (monthly) Calendar.MONTH else Calendar.WEEK_OF_YEAR, 1) }
    val visible = remember(runs,start.timeInMillis,end.timeInMillis) {
        runs.filter { (_,t) -> t != null && t.time >= start.timeInMillis && t.time < end.timeInMillis }
    }
    val buckets = remember(visible,monthly,start.timeInMillis) {
        val count = if(monthly) (start.getActualMaximum(Calendar.DAY_OF_MONTH)+6)/7 else 7
        List(count){DayTotals()}.also { values ->
            visible.forEach { (run,t) ->
                val day = ((t!!.time-start.timeInMillis)/86400000L).toInt()
                val bucket = values[if(monthly) day/7 else day]
                bucket.meters += run.getLong("distanceMeters")
                bucket.seconds += run.getLong("movingSeconds")
            }
        }
    }
    val totals = DayTotals(buckets.sumOf { it.meters }, buckets.sumOf { it.seconds })
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(false,true).forEach { month ->
            FilterChip(selected=month==monthly,onClick={if(month!=monthly){monthly=month;offset=0}},label={Text(if(month)"월간" else "주간")})
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        TextButton({offset--}){Text("이전")}
        val last = Date(end.timeInMillis-1)
        Label(if(monthly) dateFormat("yyyy년 M월").format(start.time) else dateFormat("yy.MM.dd").format(start.time)+" ~ "+dateFormat("MM.dd").format(last),14,true)
        TextButton({offset++},enabled=offset<0){Text("다음")}
    }
    CardBox(Ink) {
        Label("총 거리",12,false,Color.White)
        Label(String.format(Locale.ROOT,"%.2f km",totals.meters/1000.0),30,true,Color.White)
        Label("러닝 ${visible.size}회   ·   평균 "+PaceCalculator.formatPace(totals.pace),14,false,Lime)
    }
    val labels = if(monthly) buckets.indices.map { "${it*7+1}일" } else "월화수목금토일".map { it.toString() }
    if(visible.isNotEmpty()) {
        Chart("거리 (km)",buckets.map { it.meters/1000.0 },labels){String.format(Locale.ROOT,"%.2f",it)}
        Chart("평균 페이스 (분/km)",buckets.map { it.pace?.toDouble() },labels){
            String.format(Locale.ROOT,"%d:%02d",it.toInt()/60,it.toInt()%60)
        }
    }
    HomeHeading("최근 활동","러닝 기록")
    val shortDate = remember { dateFormat("M월 d일 HH:mm") }
    fun show(run:JSONObject) { detail=run.getString("clientRunId") }
    if(visible.isEmpty()) Label("이 기간에 완료한 러닝이 없어요.",14,false,Muted)
    visible.forEach { (run,t) -> RecordRow(run,shortDate.format(t!!)){show(run)} }
    val undated=runs.filter { it.second==null }
    if(undated.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        TextButton({expanded=!expanded}){Text("날짜 없는 이전 기록 (${undated.size})")}
        if(expanded) undated.forEach { (run,_) -> RecordRow(run,"날짜 미기록"){show(run)} }
    }
    runs.firstOrNull { it.first.getString("clientRunId")==detail }?.let { (run,t) ->
        AlertDialog(onDismissRequest={detail=null}, title={Text("러닝 기록 상세")}, text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Label(t?.let(shortDate::format) ?: "날짜 미기록")
                Label(when(run.optString("type")){"mission"->"미션 주행";"assessment"->"초기 체력 측정";else->"자유 주행"},16,true)
                val sec=run.getLong("movingSeconds")
                Label("거리 "+km(run.getInt("distanceMeters"))+" km")
                Label("시간 "+String.format(Locale.ROOT,"%d분 %02d초",sec/60,sec%60))
                Label("평균 페이스 "+PaceCalculator.formatPace(run.optInt("averagePaceSecondsPerKm")))
                run.optJSONObject("missionResult")?.let { MissionFeedback(it) }
            }
        },confirmButton={TextButton({detail=null}){Text("닫기")}})
    }
}

@Composable private fun RecordRow(run:JSONObject,date:String,open:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween){
        Column { Label(date,12,false,Muted); Label(km(run.getInt("distanceMeters"))+" km",17,true) }
        Label(PaceCalculator.formatPace(run.optInt("averagePaceSecondsPerKm"))+"  ›")
    }
    HorizontalDivider()
}

@Composable private fun Chart(title:String,values:List<Double?>,labels:List<String>,format:(Double)->String) {
    val max=values.filterNotNull().maxOrNull()?.coerceAtLeast(0.001) ?: 1.0
    CardBox {
        Label(title,15,true)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom){
            values.forEachIndexed { i,value ->
                Column(Modifier.weight(1f).semantics { contentDescription=labels[i]+" "+(value?.let(format) ?: "측정 없음") },horizontalAlignment=Alignment.CenterHorizontally){
                    Label(value?.let(format) ?: "—",10)
                    Box(Modifier.height(54.dp),contentAlignment=Alignment.BottomCenter){
                        if(value!=null && value>0) Spacer(Modifier.width(10.dp).height((value/max*52).dp).background(Lime,RoundedCornerShape(5.dp)))
                    }
                    Label(labels[i],11,false,Muted)
                }
            }
        }
    }
}
