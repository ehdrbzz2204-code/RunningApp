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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.compose.runtime.collectAsState

import android.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.maplibre.android.MapLibre


internal data class DashboardMission(val title: String, val description: String, val id: String = "")

// AuthGate calls the two-argument entry point; the original three-argument preview remains available.
@Composable
internal fun RunningApp(
    session: SavedSession?,
    onLogout: () -> Unit,

    ) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf("홈") }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var measuring by rememberSaveable { mutableStateOf(false) }
    val repo = remember(session) { session?.let { RunnerRepository(context,it) } }
    val scope = rememberCoroutineScope()
    var data by remember(session) { mutableStateOf(repo?.cached()) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeMission by rememberSaveable { mutableStateOf<String?>(null) }
    var missionDetails by rememberSaveable { mutableStateOf<String?>(null) }
    var assessmentRun by rememberSaveable { mutableStateOf(true) }
    var resultJson by rememberSaveable(session?.userId) { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var pendingRuns by remember(repo) { mutableStateOf(repo?.pendingRuns().orEmpty()) }
    var lastSync by remember(repo) { mutableLongStateOf(repo?.lastSyncMillis() ?: 0L) }
    LaunchedEffect(repo,refresh) {
        if(repo==null) return@LaunchedEffect
        do {
            error=null
            try {
                repo.upload()
                data=repo.load()
            } catch(e:java.io.IOException) { error=e.message }
            catch(e:org.json.JSONException) { error="서버 응답을 읽지 못했어요." }
            pendingRuns = repo.pendingRuns()
            lastSync = repo.lastSyncMillis()
            if(pendingRuns.isEmpty()) break
            delay(30000)
        } while(true)
    }
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
        if (action == TrackingService.ACTION_START || action == TrackingService.ACTION_RESTORE) {
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    val startWithPermission = rememberStartRunWithPermissions(
        context = context,
        onPermissionGrantedStart = { title ->
            if (!serviceState.isActive) {
                if (!starting) scope.launch {
                    starting=true
                    try {
                        val offer=data?.missions?.find { it.title==title }
                        missionDetails=offer?.let { repo?.start(it.id)?.toString() }
                        activeMission=offer?.id
                        assessmentRun=(data?.completed ?: 0)<3
                        sendServiceAction(TrackingService.ACTION_START,title)
                        measuring=true
                    } catch(e:java.io.IOException){error=e.message}
                    finally {starting=false}
                }
            } else measuring = true
        },
        onPermissionDenied = { dialog = "위치 권한 필요" },
    )

    // 앱을 다시 열었을 때, 꺼지기 전에 보관된 러닝이 있으면 선택창(이어서 달리기 / 저장하기)에 넘긴다
    var recoverable by remember { mutableStateOf<RecoverableRun?>(null) }
    LaunchedEffect(boundService) {
        val service = boundService ?: return@LaunchedEffect
        if (service.state.value.isActive) return@LaunchedEffect
        val saved = withContext(Dispatchers.IO) { ActiveRunStore.load(context) } ?: return@LaunchedEffect
        if (repo?.isPending(saved.clientRunId) == true) {
            ActiveRunStore.clear(context) // 이미 저장 대기열에 들어간 기록
            return@LaunchedEffect
        }
        recoverable = saved
    }

    BackHandler(enabled = dialog == null && (resultJson != null || measuring || tab != "홈")) {
        if (resultJson != null) resultJson = null else if (measuring) measuring = false else tab = "홈"
    }

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            if (!measuring && resultJson == null) Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding()) {
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
            val finished = resultJson
            if (finished != null) {
                val run = remember(finished) { org.json.JSONObject(finished) }
                RunResultScreen(Modifier.padding(pad), run, data, repo?.isPending(run.getString("clientRunId")) == true,
                    error, retry = { refresh++ }, close = { resultJson = null })
            } else if (measuring) {
                var recenterTick by remember { mutableIntStateOf(0) }

                Column(Modifier.fillMaxSize().padding(pad)) {
                    remember(missionDetails) { missionDetails?.let { parseMission(org.json.JSONObject(it)) } }?.let { mission ->
                        val progress=com.example.myapplication.mission.MissionLogic.trackMission(mission,serviceState.distanceMeters,serviceState.elapsedMs/1000,serviceState.points.isNotEmpty())
                        Label("남은 거리 "+km(progress.remainingMeters)+" km · 목표 "+PaceCalculator.formatPace(mission.recommendedPaceSecondsPerKm),12)
                        LinearProgressIndicator(progress={progress.progress},modifier=Modifier.fillMaxWidth(),color=Lime)
                    }
                    MeasurementScreen(
                        modifier = Modifier.weight(1f).blur(if (dialog == "러닝 종료") 4.dp else 0.dp),
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
                }
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
                    error?.let { Label(it); TextButton({refresh++}){Text("다시 시도")} }
                    if(repo!=null && repo.pending()>0) Label("기기 저장 · 전송 대기 "+repo.pending()+"건")
                    val current=data
                    if (current==null) {
                        if(tab == "마이") TextButton({ dialog = "오프라인 기록 및 동기화" }) { Text("오프라인 기록 및 동기화") }
                        if(error==null) Label("러닝 정보를 불러오는 중이에요.")
                        Action("3km 자유 주행 시작"){startWithPermission("3km 자유 주행")}
                        TextButton({dialog="로그아웃"}){Text("로그아웃")}
                    } else when (tab) {
                        "홈" -> HomeContent(current, { startWithPermission("3km 자유 주행") }, { tab="기록" }, { tab="미션" })
                        "미션" -> {
                            if(current.completed>=3 && current.goal==null) Action("러닝 목표 설정"){dialog="나의 러닝 목표"}
                            else {
                                var selected by remember(current.missions) { mutableIntStateOf(0) }
                                if(current.missions.size>1) current.missions.forEachIndexed { i,m ->
                                    TextButton({selected=i}){Text(m.title)}
                                }
                                MissionContent(current.missions.getOrNull(selected),startWithPermission,current.completed)
                            }
                        }
                        "기록" -> RecordsContent(current)
                        "마이" -> ProfileContent(
                            session?.name, session?.email, current,
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
            "오프라인 기록 및 동기화" -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(title) },
                text = {
                    val formatter = remember { java.text.DateFormat.getDateTimeInstance() }
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Label("마지막 동기화 · " + if (lastSync > 0) formatter.format(java.util.Date(lastSync)) else "확인된 시각 없음")
                        Label("전송 대기 " + pendingRuns.size + "건", 16, true)
                        if (pendingRuns.isEmpty()) Label("전송을 기다리는 러닝 기록이 없어요.")
                        pendingRuns.forEach { run ->
                            val type = when (run.optString("type")) {
                                "mission" -> "미션 러닝"
                                "assessment" -> "초기 체력 측정"
                                else -> "자유 주행"
                            }
                            val finished = run.optString("finishedAt")
                            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ROOT)
                            val time = runCatching { formatter.format(requireNotNull(parser.parse(finished))) }.getOrDefault(finished)
                            val seconds = run.optInt("movingSeconds")
                            Column {
                                Label(type + " · " + time)
                                Label(km(run.optInt("distanceMeters")) + " km · " + seconds / 60 + "분 " + seconds % 60 + "초")
                            }
                        }
                        error?.let { Label(it) }
                    }
                },
                confirmButton = { TextButton({ dialog = null }) { Text("닫기") } },
                dismissButton = { TextButton({ refresh++ }) { Text("다시 동기화") } }
            )
            "러닝 종료" -> EndRunDialog(
                dismiss = { dialog = null },
                save = {
                    val snapshot = (boundService?.snapshotNow() ?: serviceState).copy(isActive = false, isPaused = false)
                    if (!snapshot.clientRunId.isBlank() && serviceState.isActive && repo!=null) {
                        try { resultJson = repo.enqueue(snapshot,activeMission,assessmentRun) }
                        catch(e:IllegalStateException){dialog=e.message;return@EndRunDialog}
                        pendingRuns = repo.pendingRuns()
                        refresh++
                    }
                    sendServiceAction(TrackingService.ACTION_STOP, null)
                    measuring = false
                    dialog = null
                }
            )
            "나의 러닝 목표" -> GoalDialog(data?.goal, {dialog=null}) { distance,value ->
                val saved = requireNotNull(repo).goal(distance,value)
                data = data?.copy(goal = saved)
                dialog=null
                refresh++
            }
            "로그아웃" -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("로그아웃") },
                text = { Text("정말 로그아웃할까요?") },
                confirmButton = { TextButton({ dialog = null; if(serviceState.isActive) dialog="러닝을 먼저 종료해 주세요." else onLogout() }) { Text("로그아웃") } },
                dismissButton = { TextButton({ dialog = null }) { Text("취소") } },
            )
            "위치 권한 필요" -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("정확한 위치 권한이 필요해요") },
                text = { Text("러닝 거리를 측정하려면 위치 권한을 '정확한 위치'로 허용해 주세요.") },
                confirmButton = { TextButton({ dialog = null; openAppSettings(context) }) { Text("설정으로 이동") } },
                dismissButton = { TextButton({ dialog = null }) { Text("닫기") } },
            )
            else -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(title) },
                confirmButton = { TextButton({ dialog = null }) { Text("닫기") } },
            )
        }
    }

    recoverable?.let { saved ->
        val seconds = saved.elapsedMs / 1000
        AlertDialog(
            onDismissRequest = {},
            title = { Text("끝나지 않은 러닝이 있어요") },
            text = { Text(km(saved.distanceMeters.toInt()) + " km · " + seconds / 60 + "분 " + seconds % 60 + "초 달렸어요. 이어서 달릴까요?") },
            confirmButton = {
                TextButton({
                    val intent = android.content.Intent(context, TrackingService::class.java).apply {
                        action = TrackingService.ACTION_RESTORE
                        putExtra(TrackingService.EXTRA_RESUME_AFTER_RESTORE, true)
                    }
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                    measuring = true
                    recoverable = null
                }) { Text("이어서 달리기") }
            },
            dismissButton = {
                TextButton({
                    if (repo != null) {
                        try {
                            resultJson = repo.enqueue(saved.toTrackingState(), activeMission, assessmentRun)
                            pendingRuns = repo.pendingRuns()
                            refresh++
                            ActiveRunStore.clear(context)
                        } catch (e: IllegalStateException) { dialog = e.message }
                    } else ActiveRunStore.clear(context)
                    recoverable = null
                }) { Text("저장하기") }
            },
        )
    }

}

private fun clock(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)



@Composable
internal fun RunningApp(recommendation: MissionRecommendation) {
    RunningApp(session = null, onLogout = {}, recommendation = recommendation)
}