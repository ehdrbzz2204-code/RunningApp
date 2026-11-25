package com.example.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AuthScreen(
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onSubmit: (isRegister: Boolean, name: String, email: String, password: String) -> Unit,
    onClearError: () -> Unit = {},
) {
    var isRegister by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val fieldShape = RoundedCornerShape(15.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White,
        focusedBorderColor = Ink,
        unfocusedBorderColor = Ink.copy(alpha = 0.14f),
        focusedLabelColor = Ink,
        unfocusedLabelColor = Muted,
        cursorColor = Ink,
        focusedTextColor = Ink,
        unfocusedTextColor = Ink,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .background(Lime, CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Label("RUNNING APP", 11, bold = true, color = Ink)
        }

        HomeHeading(
            caption = if (isRegister) "새 러너 계정 만들기" else "다시 만나서 반가워요",
            title = if (isRegister) "회원가입하고\n기록을 남겨보세요" else "로그인하고\n러닝을 시작해요",
            size = 25,
        )

        CardBox {
            // 로그인 / 회원가입 모드 전환 바
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Mist)
                    .padding(4.dp),
            ) {
                listOf(false to "로그인", true to "회원가입").forEach { (registerMode, title) ->
                    val selected = isRegister == registerMode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color.White else Color.Transparent)
                            .clickable(enabled = !isLoading) {
                                isRegister = registerMode
                                onClearError()
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Label(
                            value = title,
                            size = 13,
                            bold = selected,
                            color = if (selected) Ink else Muted,
                        )
                    }
                }
            }

            // 이름 (회원가입 모드에서만)
            if (isRegister) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (errorMessage != null) onClearError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    singleLine = true,
                    shape = fieldShape,
                    colors = fieldColors,
                    label = { Text("이름", fontSize = 13.sp) },
                    placeholder = { Text("러너", color = Muted.copy(alpha = 0.7f)) },
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    if (errorMessage != null) onClearError()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                singleLine = true,
                shape = fieldShape,
                colors = fieldColors,
                label = { Text("이메일", fontSize = 13.sp) },
                placeholder = { Text("runner@example.test", color = Muted.copy(alpha = 0.7f)) },
            )

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    if (errorMessage != null) onClearError()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                singleLine = true,
                shape = fieldShape,
                colors = fieldColors,
                label = { Text("비밀번호", fontSize = 13.sp) },
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = !isLoading,
                    ) {
                        Label(
                            value = if (passwordVisible) "숨김" else "표시",
                            size = 12,
                            bold = true,
                            color = Muted,
                        )
                    }
                },
            )

            Label(
                value = if (isRegister) {
                    "비밀번호는 15~128자(공백 포함)로 입력해 주세요."
                } else {
                    "가입한 이메일과 비밀번호로 로그인합니다."
                },
                size = 11,
                color = Muted,
            )

            if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xfffdecec))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Label(
                        value = errorMessage,
                        size = 12,
                        bold = true,
                        color = Color(0xff9e2a2b),
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Ink),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Lime,
                        strokeWidth = 2.5.dp,
                    )
                }
            } else {
                Action(
                    title = if (isRegister) "회원가입 완료" else "로그인하기",
                    click = { onSubmit(isRegister, name.trim(), email, password) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                value = if (isRegister) "이미 계정이 있나요? " else "아직 계정이 없나요? ",
                size = 12,
                color = Muted,
            )
            Text(
                text = if (isRegister) "로그인" else "회원가입",
                color = Ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = !isLoading) {
                    isRegister = !isRegister
                    onClearError()
                },
            )
        }
    }
}