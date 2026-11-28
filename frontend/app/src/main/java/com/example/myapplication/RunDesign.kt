package com.example.myapplication

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

internal val Ink = Color(0xff14231f)
internal val Lime = Color(0xffcefa39)
internal val Muted = Color(0xff64716a)
internal val Paper = Color(0xfffafbf8)
internal val Mist = Color(0xffeff3e6)

@Composable internal fun Label(value:String, size:Int=14, bold:Boolean=false, color:Color=Ink) {
    Text(value, color=color, fontSize=size.sp, lineHeight=(size+6).sp, fontWeight=if(bold) FontWeight.Bold else FontWeight.Normal)
}
@Composable internal fun CardBox(color:Color=Color.White, content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(color)
        .border(1.dp,Ink.copy(alpha=.07f),RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}
@Composable internal fun Action(title:String, click:()->Unit) {
    Button(click, Modifier.fillMaxWidth().heightIn(min=50.dp),shape=RoundedCornerShape(15.dp),
        colors=ButtonDefaults.buttonColors(containerColor=Ink,contentColor=Color.White)){Text(title,fontWeight=FontWeight.Bold)}
}
@Composable internal fun Section(kicker:String,title:String) {
    Column { Label(kicker,12,true,Muted); Label(title,21,true) }
}
@Composable internal fun Stats(items:List<Pair<String,String>>) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        items.forEach { (value,name)->Column { Label(value,18,true);Label(name,12,false,Muted) } }
    }
}
@Composable internal fun WeekChart() {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceAround){
        listOf(24,5,38,5,58,5,5).forEachIndexed { i,h ->
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                Box(Modifier.height(64.dp),contentAlignment=Alignment.BottomCenter){
                    Spacer(Modifier.size(8.dp,h.dp).background(if(h>5)Lime else Mist,CircleShape))
                }
                Label("월화수목금토일"[i].toString(),11,false,Muted)
            }
        }
    }
}
@Composable internal fun Glyph(name:String, color:Color=Ink){
    Canvas(Modifier.size(22.dp)){
        val u=size.minDimension/24f
        fun line(x:Int,y:Int,a:Int,b:Int){drawLine(color,Offset(x*u,y*u),Offset(a*u,b*u),1.7f*u,StrokeCap.Round)}
        fun ring(x:Int,y:Int,r:Int){drawCircle(color,r*u,Offset(x*u,y*u),style=Stroke(1.7f*u))}
        when(name){
            "홈"->{line(2,11,12,3);line(12,3,22,11);line(5,10,5,21);line(5,21,19,21);line(19,21,19,10);line(10,20,10,14);line(10,14,14,14);line(14,14,14,20)}
            "마이"->{ring(12,7,4);drawArc(color,180f,180f,false,Offset(4*u,14*u),androidx.compose.ui.geometry.Size(16*u,14*u),style=Stroke(1.7f*u))}
            "기록"->{line(3,21,21,21);line(6,17,6,11);line(12,17,12,4);line(18,17,18,8)}
            "알림"->{drawArc(color,180f,180f,false,Offset(5*u,4*u),androidx.compose.ui.geometry.Size(14*u,14*u),style=Stroke(1.7f*u));line(5,11,4,17);line(4,17,20,17);line(20,17,19,11);line(10,20,14,20)}
            else->{ring(12,12,8);ring(12,12,3)}
        }
    }
}
