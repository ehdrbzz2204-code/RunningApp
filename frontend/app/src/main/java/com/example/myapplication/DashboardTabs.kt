package com.example.myapplication

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlin.math.min

private val Line = Ink.copy(alpha = .09f)

@Composable
private fun DashboardTile(kind: String, background: Color, tint: Color = Muted, size: Int = 40) {
    Box(Modifier.size(size.dp).background(background, RoundedCornerShape((size / 3).dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(21.dp)) {
            val u = this.size.width / 24
            val pen = Stroke(1.8f * u, cap = StrokeCap.Round)
            fun point(x: Int, y: Int) = Offset(x * u, y * u)
            fun ring(x: Int, y: Int, r: Int) = drawCircle(tint, r * u, point(x,y), style = pen)
            fun line(x: Int,y: Int,a: Int,b: Int) = drawLine(tint, point(x,y),point(a,b),pen.width,StrokeCap.Round)
            when(kind) {
                "lock" -> {
                    drawArc(tint,180f,180f,false,point(7,2),Size(10*u,12*u),style=pen)
                    drawRoundRect(tint,point(4,10),Size(16*u,12*u),CornerRadius(3*u),style=pen)
                    line(12,14,12,17)
                }
                "clock" -> {ring(12,12,9);line(12,6,12,12);line(12,12,16,15)}
                "flame" -> {
                    val path=Path().apply {moveTo(13*u,2*u);cubicTo(15*u,8*u,21*u,10*u,19*u,16*u);cubicTo(17*u,24*u,5*u,23*u,5*u,15*u);cubicTo(5*u,11*u,8*u,9*u,9*u,7*u);lineTo(10*u,12*u);close()}
                    drawPath(path,tint,style=pen)
                }
                "goal" -> {ring(12,12,8);ring(12,12,3);line(12,1,12,4);line(20,12,23,12);line(12,20,12,23);line(1,12,4,12)}
                else -> {
                    ring(5,18,2);ring(19,5,2)
                    val path=Path().apply {moveTo(7*u,18*u);lineTo(11*u,18*u);cubicTo(17*u,18*u,15*u,12*u,11*u,12*u);cubicTo(3*u,12*u,4*u,5*u,10*u,5*u);lineTo(14*u,5*u)}
                    drawPath(path,tint,style=pen)
                }
            }
        }
    }
}

@Composable
internal fun MissionContent(recommendation: DashboardMission?, start: (String)->Unit, completed: Int) {
    Column(Modifier.fillMaxWidth().background(Mist,RoundedCornerShape(24.dp)).padding(20.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(104.dp),contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)){
                drawCircle(Muted.copy(.45f),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),2.dp.toPx()))))
                drawCircle(Lime,4.dp.toPx(),Offset(size.width*.85f,size.height*.15f))
                drawCircle(Lime,3.dp.toPx(),Offset(size.width*.15f,size.height*.85f))
            }
            Box(Modifier.rotate(-5f)){DashboardTile(if(recommendation==null)"lock" else "goal",Lime,Ink,60)}
        }
        Box(Modifier.background(Ink.copy(.07f),CircleShape).padding(horizontal=12.dp,vertical=5.dp)){
            Label(if(recommendation==null)"${3-completed}회 남음" else "오늘의 미션",11,true)
        }
        Label(recommendation?.title?:"맞춤 미션을 준비하고 있어요",19)
        Text(recommendation?.description?:"남은 초기 측정을 완료하면\n러닝 수준을 분석해 첫 미션을 알려드려요.",
            color=Muted,fontSize=13.sp,lineHeight=20.sp,textAlign=TextAlign.Center)
        Action(if(recommendation==null)"3km 측정 시작하기" else "이 훈련을 미션으로 시작"){start(recommendation?.title?:"3km 자유 주행")}
    }
    HomeHeading("미리 보기","이런 미션을 만나게 돼요")
    Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
        listOf(Triple("route","거리 적응 러닝","편안한 페이스로 거리를 늘려요"),Triple("clock","페이스 인터벌","빠르게, 천천히 리듬을 익혀요"),Triple("flame","지구력 러닝","멈추지 않고 달리는 힘을 길러요")).forEach{(icon,title,desc)->
            Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(16.dp)).border(1.dp,Line,RoundedCornerShape(16.dp)).padding(13.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                DashboardTile(icon,Paper);Column{Label(title,14,true);Label(desc,11,false,Muted)}
            }
        }
    }
}

@Composable private fun Pill(title:String) {
    Box(Modifier.border(1.dp,Line,CircleShape).padding(horizontal=11.dp,vertical=5.dp)){Label(title,11,false,Muted)}
}
@Composable internal fun ProfileContent(name: String?, email: String?, data: RunnerData, open:(String)->Unit, logout:()->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Box(Modifier.size(52.dp).rotate(-4f).background(Lime,RoundedCornerShape(17.dp)),contentAlignment=Alignment.Center){Glyph("마이")}
        Column(Modifier.weight(1f)){Label(name ?: "러너",19);Label(email ?: "첫 러닝을 기다리고 있어요",11,false,Muted)}
        Box(Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).clickable{open("편집")},contentAlignment=Alignment.Center){Pill("편집")}
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(Ink).clickable{open("나의 러닝 목표")}.padding(16.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        DashboardTile("goal",Lime,Ink)
        Column(Modifier.weight(1f)){Label("나의 러닝 목표",10,false,Color.LightGray);Label(data.goal?.let { if(it.optString("type")=="distance") km(it.getInt("targetDistanceMeters"))+" km" else PaceCalculator.formatPace(it.getInt("targetPaceSecondsPerKm")) } ?: "목표 미설정",16,true,Color.White);Label("거리 또는 페이스 목표",11,true,Lime)}
        Label("›",19,false,Color.White)
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        val meters=data.history.sumOf { it.getLong("distanceMeters") }; val seconds=data.history.sumOf { it.getLong("movingSeconds") }; listOf(data.history.size.toString() to "총 러닝",String.format(java.util.Locale.ROOT,"%.2f",meters/1000.0) to "누적 km",PaceCalculator.formatPace(if(meters>0)(seconds*1000/meters).toInt() else null) to "평균 페이스").forEachIndexed{i,(value,name)->
            if(i>0)Spacer(Modifier.width(1.dp).height(35.dp).background(Line))
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Label(value,18,true);Label(name,11,false,Muted)}
        }
    }
    Column {
        HorizontalDivider(color=Line)
        listOf("알림 설정","오프라인 기록 및 동기화","앱 설정","도움말 및 문의").forEach { title->
            Row(Modifier.fillMaxWidth().clickable{open(title)}.heightIn(min=48.dp).padding(horizontal=3.dp),
                horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Label(title,14);Label("›",18)}
            HorizontalDivider(color=Line)
        }
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .border(1.dp,Color(0xff9e2a2b).copy(alpha=.3f),RoundedCornerShape(15.dp))
            .clickable(onClick=logout).heightIn(min=50.dp),
        contentAlignment=Alignment.Center,
    ){ Label("로그아웃",14,true,Color(0xff9e2a2b)) }

}




