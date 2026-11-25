package com.example.myapplication

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.runtime.collectAsState

import android.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.maplibre.android.MapLibre


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapLibre.getInstance(applicationContext)

        setContent {

            Configuration.getInstance().load(
                applicationContext,
                PreferenceManager.getDefaultSharedPreferences(applicationContext),
            )
            Configuration.getInstance().userAgentValue = "MyApplication/1.0 (com.example.myapplication)"

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Ink,
                    background = Paper,
                    surface = Color.White,
                    onSurface = Ink,
                )
            ) {
                AuthGate()
            }
        }
    }
}

@Composable
private fun AuthGate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionStore = remember(context) { SessionStore(context) }
    val authApi = remember { AuthApiClient() }
    var session by remember { mutableStateOf(sessionStore.loadSession()) }
    var isAuthLoading by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    if (session == null) {
        AuthScreen(
            isLoading = isAuthLoading,
            errorMessage = authError,
            onClearError = { authError = null },
            onSubmit = { isRegister, name, email, password ->
                scope.launch {
                    isAuthLoading = true
                    authError = null
                    try {
                        if (isRegister) {
                            if (name.isBlank()) {
                                authError = "이름을 입력해 주세요."
                                return@launch
                            }
                            when (val reg = authApi.register(email, password)) {
                                is AuthResult.Error -> {
                                    authError = reg.message
                                    return@launch
                                }
                                is AuthResult.Success -> sessionStore.saveName(email, name)
                            }
                        }
                        when (val loginRes = authApi.login(email, password)) {
                            is AuthResult.Success -> {
                                val withName = loginRes.data.copy(name = sessionStore.getName(email))
                                sessionStore.saveSession(withName)
                                session = withName
                            }
                            is AuthResult.Error -> {
                                authError = loginRes.message
                            }
                        }
                    } finally {
                        isAuthLoading = false
                    }
                }
            },
        )
    } else {
        RunningApp(
            session = session,
            onLogout = {
                scope.launch {
                    session?.accessToken?.let { token ->
                        authApi.logout(token)
                    }
                    sessionStore.clearSession()
                    session = null
                }
            },
        )
    }
}

internal data class MissionRecommendation(val title: String, val description: String)

@Composable
internal fun RunningApp(
    session: SavedSession? = null,
    onLogout: () -> Unit = {},
    recommendation: MissionRecommendation? = null,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf("홈") }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var measuring by rememberSaveable { mutableStateOf(false) }
    var lastRun by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFinishedRun by remember { mutableStateOf<RunTrackingState?>(null) }

    var boundService by remember { mutableStateOf<TrackingService?>(null) }
    val serviceState by (boundService?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(RunTrackingState()) }).collectAsState()

    DisposableEffect(context) {
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                val binder = service as? TrackingService.LocalBinder
                boundService = binder?.getService()
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                boundService = null
            }
        }
        val intent = android.content.Intent(context, TrackingService::class.java)
        context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE)
        onDispose {
            context.unbindService(connection)
        }
    }

    val sendServiceAction: (String, String?) -> Unit = { action, title ->
        val intent = android.content.Intent(context, TrackingService::class.java).apply {
            this.action = action
            if (title != null) putExtra(TrackingService.EXTRA_TITLE, title)
        }
        if (action == TrackingService.ACTION_START) {
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    val startWithPermission = rememberStartRunWithPermissions(
        context = context,
        onPermissionGrantedStart = { title ->
            if (!serviceState.isActive) {
                sendServiceAction(TrackingService.ACTION_START, title)
            }
            measuring = true
        },
    )

    BackHandler(enabled = dialog == null && (measuring || tab != "홈")) {
        if (measuring) measuring = false else tab = "홈"
    }

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            if (!measuring) Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding()) {
                listOf("홈", "미션", "기록", "마이").forEach { name ->
                    Column(
                        Modifier.weight(1f).clickable { tab = name }.padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.size(26.dp, 3.dp).background(if (tab == name) Lime else Color.Transparent))
                        Spacer(Modifier.height(8.dp))
                        Glyph(name, if (tab == name) Ink else Muted)
                        Label(name, 11, tab == name)
                    }
                }
            }
        }
    ) { pad ->
        key(tab, measuring) {
            if (measuring) {
                var recenterTick by remember { mutableIntStateOf(0) }

                MeasurementScreen(
                    modifier = Modifier.padding(pad).blur(if (dialog == "러닝 종료") 4.dp else 0.dp),
                    trackingState = serviceState,
                    stop = { dialog = "러닝 종료" },
                    toggle = {
                        if (serviceState.isPaused) {
                            sendServiceAction(TrackingService.ACTION_RESUME, null)
                        } else {
                            sendServiceAction(TrackingService.ACTION_PAUSE, null)
                        }
                    },
                    reset = { recenterTick++ },
                    recenterTrigger = recenterTick,
                )
            }else {
                Column(
                    Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    val headers = mapOf(
                        "홈" to ("러닝을 시작해요" to "${session?.name ?: "러너"}님, 오늘도 가볍게"),
                        "미션" to ("내 페이스에 꼭 맞는" to "러닝 미션"),
                        "기록" to ("조금씩, 분명하게" to "나의 성장 기록"),
                        "마이" to ("내 러닝을 한눈에" to "마이 페이지"),
                    )
                    val header = headers.getValue(tab)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HomeHeading(header.first, header.second, 25)
                        IconButton(
                            { dialog = "알림" },
                            Modifier.background(Color.White, CircleShape)
                                .semantics { contentDescription = "알림" },
                        ) { Glyph("알림") }
                    }
                    if (serviceState.isActive) {
                        Action("진행 중인 러닝으로 돌아가기") { measuring = true }
                    }
                    when (tab) {
                        "홈" -> HomeContent(
                            recommendation,
                            { startWithPermission("3km 자유 주행") },
                            { tab = "기록" },
                            { tab = "미션" },
                        )
                        "미션" -> MissionContent(recommendation, startWithPermission)
                        "기록" -> RecordsContent(lastRun)
                        "마이" -> ProfileContent(
                            session?.name,
                            session?.email,
                            open = { dialog = it },
                            logout = { dialog = "로그아웃" },
                        )
                    }
                }
            }
        }
    }

    dialog?.let { title ->
        when (title) {
            "러닝 종료" -> EndRunDialog(
                dismiss = { dialog = null },
                save = {
                    val snapshot = serviceState.copy(isActive = false, isPaused = false)
                    lastFinishedRun = snapshot
                    lastRun = "${clock(snapshot.elapsedMs)} · ${String.format(Locale.ROOT, "%.2fkm", snapshot.distanceMeters / 1000.0)}"
                    sendServiceAction(TrackingService.ACTION_STOP, null)
                    measuring = false
                    dialog = null
                }
            )
            "로그아웃" -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("로그아웃") },
                text = { Text("정말 로그아웃할까요?") },
                confirmButton = { TextButton({ dialog = null; onLogout() }) { Text("로그아웃") } },
                dismissButton = { TextButton({ dialog = null }) { Text("취소") } },
            )
            else -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(title) },
                confirmButton = { TextButton({ dialog = null }) { Text("닫기") } },
            )
        }
    }

}

private fun clock(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)