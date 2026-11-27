package com.example.myapplication

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch

@Composable
internal fun GoalDialog(close:()->Unit, save:suspend (Boolean,Int)->Unit) {
    var distance by remember { mutableStateOf(true) }
    var value by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    val number=value.toIntOrNull()?.takeIf { it>0 }
    AlertDialog(onDismissRequest={if(!busy)close()},title={Text("나의 러닝 목표")},text={
        Column {
            TextButton({distance=!distance;value=""},enabled=!busy){Text(if(distance)"거리 목표 · 페이스로 변경" else "페이스 목표 · 거리로 변경")}
            OutlinedTextField(value,{value=it},enabled=!busy,singleLine=true,label={Text(if(distance)"목표 거리 (m)" else "목표 페이스 (초/km)")})
            error?.let {Text(it)}
        }
    },confirmButton={TextButton({scope.launch {
        busy=true
        try {save(distance,number!!)} catch(e:java.io.IOException){error=e.message} finally {busy=false}
    }},enabled=number!=null && !busy){Text("저장")}},dismissButton={TextButton(close,enabled=!busy){Text("취소")}})
}
