package com.example.myapplication

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun MeasurementScreen(modifier: Modifier, title: String, time: String, paused: Boolean, stop: () -> Unit, toggle: () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color(0xffe6ece5))) {
        val mapHeight = (maxHeight * .43f).coerceIn(160.dp, 420.dp)
        val panelHeight = (maxHeight - mapHeight).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(mapHeight)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val step = 88.dp.toPx()
                    for (i in -2..(w/step).toInt()+3) {
                        for (j in -1..(h/step).toInt()+1) {
                            val x=i*step; val y=j*step
                            drawLine(Color(0xffdce3dc), Offset(x,y), Offset(x+step*.96f,y+step*.57f), 2.dp.toPx())
                            drawLine(Color(0xffdae1da), Offset(x+step*.85f,y), Offset(x+step*.39f,y+step), 3.dp.toPx())
                        }
                    }
                    val origin = Offset(w*.37f,h*.30f)
                    val path = Path().apply {
                        moveTo(origin.x,origin.y);lineTo(w*.30f,h*.70f)
                        cubicTo(w*.25f,h*.93f,w*.35f,h*.99f,w*.48f,h*1.08f)
                    }
                    drawPath(path,Color(0xffa9db29),style=Stroke(4.dp.toPx(),cap=StrokeCap.Round))
                    drawCircle(Color.White,8.dp.toPx(),origin);drawCircle(Ink,4.dp.toPx(),origin)
                }
                Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    RoundControl("러닝 종료","×",Color.White,48,stop)
                    Label(title,14,true)
                    Box(Modifier.size(48.dp).background(Color.White,CircleShape).semantics { contentDescription="위치 미연결" },contentAlignment=Alignment.Center){Glyph("미션")}
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(min=panelHeight).background(Paper,RoundedCornerShape(topStart=28.dp,topEnd=28.dp))
                .padding(horizontal=20.dp,vertical=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
                    Spacer(Modifier.size(7.dp).background(if(paused) Muted else Color(0xff79c75e),CircleShape))
                    Label(if(paused)"일시정지" else "GPS 연결 전",10,true,Muted)
                }
                Label("현재 거리",11,false,Muted)
                Row(verticalAlignment=Alignment.Bottom){ Label("—",50,true);Label(" km",14,true,Muted) }
                HorizontalDivider(color=Ink.copy(.10f))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    listOf("케이던스" to "— spm","현재 페이스" to "— /km","러닝 시간" to time).forEachIndexed { index,(name,value) ->
                        if(index>0)Spacer(Modifier.width(1.dp).height(32.dp).background(Ink.copy(.10f)))
                        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Label(name,11,false,Muted);Label(value,16,true)}
                    }
                }
                HorizontalDivider(color=Ink.copy(.10f))
                Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){
                    Box(Modifier.align(Alignment.CenterStart).padding(start=16.dp)){RoundControl("종료","▢",Color(0xffecefeb),48,stop)}
                    RoundControl(if(paused)"계속하기" else "일시정지",if(paused)"▷" else "Ⅱ",Lime,64,toggle)
                }
                Label("무리하지 말고 나의 페이스로 달려요",11,false,Muted)
            }
        }
    }
}

@Composable
private fun RoundControl(label:String, mark:String, color:Color, diameter:Int, click:()->Unit){
    IconButton(onClick=click,modifier=Modifier.size(diameter.dp).background(color,CircleShape).semantics { contentDescription=label }){
        Text(mark,Modifier.clearAndSetSemantics {},color=Ink,fontSize=24.sp)
    }
}

@Composable
internal fun EndRunDialog(dismiss:()->Unit, save:()->Unit){
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){
            Spacer(Modifier.matchParentSize().clickable(indication=null,interactionSource=null,onClick=dismiss).clearAndSetSemantics {})
            Surface(Modifier.navigationBarsPadding().padding(14.dp).widthIn(max=460.dp).fillMaxWidth(),
                shape=RoundedCornerShape(26.dp),color=Color.White){
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Box(Modifier.size(48.dp).background(Color(0xffeef1ed),RoundedCornerShape(15.dp)),contentAlignment=Alignment.Center){Label("▢",21)}
                    Label("러닝을 종료할까요?",20,true)
                    Label("지금까지의 러닝 시간을 기록해요.",13,false,Muted)
                    Action("종료하고 저장하기",save)
                    TextButton(dismiss,Modifier.fillMaxWidth()){Label("계속 달리기",15,true,Muted)}
                }
            }
        }
    }
}

