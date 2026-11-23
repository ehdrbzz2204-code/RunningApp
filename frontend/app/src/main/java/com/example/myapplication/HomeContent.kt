package com.example.myapplication

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable internal fun HomeHeading(caption: String, title: String, size: Int = 19) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(caption, 11, true, Muted)
        Text(title, color = Ink, fontSize = size.sp, lineHeight = (size + 6).sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.7).sp)
    }
}

@Composable internal fun HomeContent(
    recommendation: MissionRecommendation?, start: () -> Unit, records: () -> Unit, missions: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().shadow(8.dp, shape, spotColor = Lime).clip(shape).background(Lime)) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = .13f), 84.dp.toPx(), Offset(size.width, 0f))
            drawCircle(Color.White.copy(alpha = .10f), 55.dp.toPx(), Offset(size.width, 0f))
        }
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(Ink.copy(alpha=.07f),CircleShape).padding(horizontal=9.dp,vertical=4.dp)){Label("초기 체력 측정 중",11,true)}
                Label("2 / 3",13,true)
            }
            Spacer(Modifier.height(3.dp))
            Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text("마지막 3km만 남았어요",fontSize=23.sp,fontWeight=FontWeight.ExtraBold,color=Ink,letterSpacing=(-.8).sp)
                Label("나만의 러닝 미션을 만나기까지 한 걸음이에요.",13)
            }
            Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
                Box(Modifier.fillMaxWidth().height(5.dp).background(Ink.copy(alpha=.13f),CircleShape)){
                    Spacer(Modifier.fillMaxWidth(2f/3f).fillMaxHeight().background(Ink,CircleShape))
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    (1..3).forEach { n -> Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                        Box(Modifier.size(16.dp).background(if(n<3) Ink else Color.Transparent,CircleShape).border(1.dp,Ink.copy(alpha=.25f),CircleShape),contentAlignment=Alignment.Center){Label(if(n<3)"✓" else "3",10,true,if(n<3)Lime else Ink)}
                        Label("${n}회",11)
                    } }
                }
            }
            Button(start, Modifier.fillMaxWidth().heightIn(min=50.dp),shape=RoundedCornerShape(15.dp),colors=ButtonDefaults.buttonColors(containerColor=Ink)){
                Box(Modifier.size(21.dp).background(Lime,CircleShape),contentAlignment=Alignment.Center){Label("▷",13,true)}
                Spacer(Modifier.width(8.dp));Label("3km 자유 주행 시작",15,true,Color.White)
            }
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){Label("걷거나 쉬어도 괜찮아요. 나의 페이스 그대로 기록해요.",11)}
        }
    }
    Spacer(Modifier.height(1.dp))
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.SpaceBetween){
        HomeHeading("이번 주","차곡차곡 쌓인 러닝")
        TextButton(records){Label("자세히 ›",12,false,Muted)}
    }
    CardBox {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            listOf(Triple("8.4","km","총 거리"),Triple("2","회","러닝 횟수"),Triple("7′12″","/km","평균 페이스")).forEachIndexed { i,(value,unit,title) ->
                if(i>0) Spacer(Modifier.width(1.dp).height(26.dp).background(Mist))
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
                    Row(verticalAlignment=Alignment.Bottom){Label(value,19,true);Label(" $unit",10,false,Muted)}
                    Label(title,11,false,Muted)
                }
            }
        }
        HorizontalDivider(color=Mist);WeekChart()
    }
    Spacer(Modifier.height(1.dp))
    HomeHeading("맞춤 미션","나를 위한 다음 러닝")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Color(0xfff0f3ef)).clickable(onClick=missions).padding(15.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){
        Box(Modifier.size(39.dp).background(Color.White,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){
            if(recommendation!=null) Glyph("미션") else Canvas(Modifier.size(20.dp)){
                val u=size.width/20
                drawArc(Ink,180f,180f,false,Offset(6*u,2*u),Size(8*u,12*u),style=Stroke(1.4f*u))
                drawRoundRect(Ink,Offset(4*u,8*u),Size(12*u,10*u),androidx.compose.ui.geometry.CornerRadius(2*u),style=Stroke(1.4f*u))
                drawLine(Ink,Offset(10*u,11*u),Offset(10*u,14*u),1.4f*u)
            }
        }
        Column(Modifier.weight(1f)){Label(recommendation?.title?:"아직 미션이 잠겨 있어요",14,true);Label(recommendation?.description?:"체력 측정을 완료하면 맞춤 미션이 열려요",12,false,Muted)}
        Label("›",22)
    }
}
