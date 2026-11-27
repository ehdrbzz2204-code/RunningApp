package com.example.myapplication

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun goalSummary(goal: JSONObject): String =
    if (goal.getString("type") == "pace") "목표 페이스 " + PaceCalculator.formatPace(goal.getInt("targetPaceSecondsPerKm"))
    else km(goal.getInt("targetDistanceMeters")) + " km 완주"

@Composable
internal fun GoalDialog(goal: JSONObject?, close: () -> Unit, save: suspend (Boolean, Int) -> Unit) {
    val initialDistance = goal?.optString("type") != "pace"
    val initialValue = goal?.optString(if (initialDistance) "targetDistanceMeters" else "targetPaceSecondsPerKm").orEmpty()
    var distance by rememberSaveable { mutableStateOf(initialDistance) }
    var value by rememberSaveable { mutableStateOf(initialValue) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val number = value.toIntOrNull()?.takeIf { it > 0 }
    val dismiss = {
        if (!busy) {
            if (distance != initialDistance || value != initialValue) confirming = true else close()
        }
    }
    val submit: () -> Unit = {
        if (!busy && number != null) {
            busy = true
            scope.launch {
                try { save(distance, number) }
                catch (e: java.io.IOException) { error = e.message; confirming = false }
                finally { busy = false }
            }
        }
    }
    if (confirming) {
        AlertDialog(onDismissRequest = { if (!busy) confirming = false },
            title = { Text("목표 변경사항을 저장할까요?") },
            confirmButton = { TextButton(submit, enabled = number != null && !busy) { Text("저장") } },
            dismissButton = {
                Column {
                    TextButton({ confirming = false }, enabled = !busy) { Text("계속 작성") }
                    TextButton(close, enabled = !busy) { Text("변경사항 버리기") }
                }
            })
    } else {
        AlertDialog(onDismissRequest = dismiss, title = { Text("나의 러닝 목표") }, text = {
            Column {
                TextButton({
                    distance = !distance
                    value = if (distance == initialDistance) initialValue else ""
                    error = null
                }, enabled = !busy) { Text(if (distance) "거리 목표 · 페이스로 변경" else "페이스 목표 · 거리로 변경") }
                OutlinedTextField(value, { value = it; error = null }, enabled = !busy, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(if (distance) "목표 거리 (m)" else "목표 페이스 (초/km)") },
                    supportingText = { Text(if (distance) "예: 5000 m = 5 km" else "예: 390초/km = 6분 30초/km") },
                    isError = value.isNotEmpty() && number == null)
                error?.let { Text(it) }
            }
        }, confirmButton = { TextButton(submit, enabled = number != null && !busy) { Text("저장") } },
            dismissButton = { TextButton(dismiss, enabled = !busy) { Text("닫기") } })
    }
}
