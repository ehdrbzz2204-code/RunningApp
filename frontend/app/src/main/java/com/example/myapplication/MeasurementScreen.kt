package com.example.myapplication

import android.Manifest
import android.location.Location
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.util.Locale

private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_SOURCE_ID = "run-route-source"
private const val ROUTE_LAYER_ID = "run-route-layer"
private const val CURRENT_POS_SOURCE_ID = "current-pos-source"
private const val CURRENT_POS_LAYER_ID = "current-pos-layer"
private const val CURRENT_POS_ICON_ID = "current-pos-icon"

private val ROUTE_GREEN = AndroidColor.parseColor("#66BB6A")
private val MARKER_FILL_GREEN = AndroidColor.parseColor("#2E7D32")

@Composable
internal fun MeasurementScreen(
    modifier: Modifier,
    trackingState: RunTrackingState,
    stop: () -> Unit,
    toggle: () -> Unit,
    reset: () -> Unit = {},
    recenterTrigger: Int = 0,
) {
    val distanceKmText = if (trackingState.distanceMeters <= 0.0) {
        "—"
    } else {
        String.format(Locale.ROOT, "%.2f", trackingState.distanceMeters / 1000.0)
    }
    val paceText = PaceCalculator.formatPace(
        trackingState.currentPaceSecPerKm ?: trackingState.avgPaceSecPerKm,
    )
    val secTotal = trackingState.elapsedMs / 1000
    val timeText = String.format(Locale.ROOT, "%02d:%02d", secTotal / 60, secTotal % 60)
    val statusText = when {
        trackingState.isPaused -> "일시정지"
        trackingState.points.isEmpty() -> "GPS 연결 중"
        else -> when (trackingState.gpsSignal) {
            GpsSignal.SEARCHING -> "GPS 연결 중"
            GpsSignal.GOOD -> "GPS 측정 중"
            GpsSignal.WEAK -> "GPS 신호 약함"
            GpsSignal.LOST -> "GPS 신호 끊김"
        }
    }
    val statusDotColor = when {
        trackingState.isPaused -> Muted
        trackingState.gpsSignal == GpsSignal.WEAK -> Color(0xffe0a030)
        trackingState.gpsSignal == GpsSignal.LOST -> Color(0xffd9534f)
        else -> Color(0xff79c75e)
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color(0xffe6ece5))) {
        val mapHeight = (maxHeight * .43f).coerceIn(160.dp, 420.dp)
        val panelHeight = (maxHeight - mapHeight).coerceAtLeast(0.dp)
        // 지도를 누르고 있는 동안에는 화면 전체 스크롤을 꺼서, 지도 드래그가 스크롤에 뺏겨 끊기지 않게 한다
        var mapTouching by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = !mapTouching)) {
            Box(
                Modifier.fillMaxWidth().height(mapHeight).pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            // 이벤트를 소비하지 않고 눌림 상태만 관찰한다 (지도는 그대로 터치를 받는다)
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            mapTouching = event.changes.any { it.pressed }
                        }
                    }
                },
            ) {
                MapLibreRunMapView(
                    modifier = Modifier.fillMaxSize(),
                    points = trackingState.points,
                    recenterTrigger = recenterTrigger,
                )
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControl("러닝 종료", "×", Color.White, 48, stop)
                    Label(trackingState.title, 14, true)
                    Box(
                        Modifier.size(48.dp).background(Color.White, CircleShape)
                            .semantics { contentDescription = statusText },
                        contentAlignment = Alignment.Center,
                    ) { Glyph("미션") }
                }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min = panelHeight).background(Paper, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Spacer(Modifier.size(7.dp).background(statusDotColor, CircleShape))
                    Label(statusText, 10, true, Muted)
                }
                Label("현재 거리", 11, false, Muted)
                Row(verticalAlignment = Alignment.Bottom) {
                    Label(distanceKmText, 50, true)
                    Label(" km", 14, true, Muted)
                }
                HorizontalDivider(color = Ink.copy(.10f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    listOf("케이던스" to "— spm", "현재 페이스" to paceText, "러닝 시간" to timeText).forEachIndexed { index, (name, value) ->
                        if (index > 0) Spacer(Modifier.width(1.dp).height(32.dp).background(Ink.copy(.10f)))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Label(name, 11, false, Muted)
                            Label(value, 16, true)
                        }
                    }
                }
                HorizontalDivider(color = Ink.copy(.10f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundControl("종료", "▢", Color(0xffecefeb), 48, stop)
                        RoundControl("내 위치로 이동", "↺", Color(0xffecefeb), 44, reset)
                    }
                    RoundControl(
                        if (trackingState.isPaused) "계속하기" else "일시정지",
                        if (trackingState.isPaused) "▷" else "Ⅱ",
                        Lime,
                        64,
                        toggle,
                    )
                }
                Label("무리하지 말고 나의 페이스로 달려요", 11, false, Muted)
            }
        }
    }
}

private fun createCurrentLocationBitmap(): Bitmap {
    val size = 56
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f

    // 바깥 흰색 테두리
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 23f, borderPaint)

    // 안쪽 진한 초록색 점
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MARKER_FILL_GREEN
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 16f, fillPaint)

    return bitmap
}

/**
 * 캐시된 마지막 위치(lastLocation)는 예전에 있던 장소일 수 있어서 쓰지 않고,
 * 지금 위치를 새로 측정해서 돌려준다. (10초 이내 캐시만 허용, 최대 15초 대기)
 */
@SuppressLint("MissingPermission")
private fun requestFreshLocation(client: FusedLocationProviderClient, onResult: (Location) -> Unit) {
    val request = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setMaxUpdateAgeMillis(10_000L)
        .setDurationMillis(15_000L)
        .build()
    client.getCurrentLocation(request, null).addOnSuccessListener { loc ->
        if (loc != null) onResult(loc)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun MapLibreRunMapView(
    modifier: Modifier = Modifier,
    points: List<TrackPoint>,
    recenterTrigger: Int = 0,
) {
    val context = LocalContext.current
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var isStyleReady by remember { mutableStateOf(false) }
    // true면 새 위치마다 카메라가 따라간다. 사용자가 지도를 손으로 움직이면 false, 내 위치 버튼을 누르면 다시 true
    var following by remember { mutableStateOf(true) }
    val fusedClient = remember {
        LocationServices.getFusedLocationProviderClient(context.applicationContext)
    }

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .camera(
                CameraPosition.Builder()
                    .target(LatLng(37.5665, 126.9780))
                    .zoom(15.5)
                    .build(),
            )
        MapView(context, options).apply {
            getMapAsync { map ->
                mapLibreMap = map
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false
                }
                map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                    style.addImage(CURRENT_POS_ICON_ID, createCurrentLocationBitmap())

                    style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
                    style.addLayer(
                        LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                            PropertyFactory.lineColor(ROUTE_GREEN),
                            PropertyFactory.lineWidth(7f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                    )

                    style.addSource(GeoJsonSource(CURRENT_POS_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
                    style.addLayer(
                        SymbolLayer(CURRENT_POS_LAYER_ID, CURRENT_POS_SOURCE_ID).withProperties(
                            PropertyFactory.iconImage(CURRENT_POS_ICON_ID),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                        ),
                    )

                    isStyleReady = true
                }
            }
        }
    }

    // 스타일 준비 시 기기의 현재 위치를 새로 측정해서 초기 마커 및 카메라 이동
    LaunchedEffect(mapLibreMap, isStyleReady) {
        if (!isStyleReady || points.isNotEmpty()) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return@LaunchedEffect

        requestFreshLocation(fusedClient) { loc ->
            if (points.isEmpty()) {
                val latLng = LatLng(loc.latitude, loc.longitude)
                val posSource = map.style?.getSourceAs<GeoJsonSource>(CURRENT_POS_SOURCE_ID)
                posSource?.setGeoJson(
                    FeatureCollection.fromFeature(
                        Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude)),
                    ),
                )
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16.0), 400)
            }
        }
    }

    // 버튼 클릭 시 현재 위치(최신 경로 좌표 또는 새로 측정한 위치)로 카메라 이동
    LaunchedEffect(recenterTrigger, mapLibreMap, isStyleReady) {
        if (recenterTrigger == 0 || !isStyleReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        following = true

        val latest = points.lastOrNull()
        if (latest != null) {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(latest.lat, latest.lng), 16.0),
                400,
            )
            return@LaunchedEffect
        }

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return@LaunchedEffect

        requestFreshLocation(fusedClient) { loc ->
            run {
                val latLng = LatLng(loc.latitude, loc.longitude)
                val posSource = map.style?.getSourceAs<GeoJsonSource>(CURRENT_POS_SOURCE_ID)
                posSource?.setGeoJson(
                    FeatureCollection.fromFeature(
                        Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude)),
                    ),
                )
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16.0), 400)
            }
        }
    }

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapLibreMap, isStyleReady, points) {
        if (!isStyleReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect

        val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        // 신호 끊김·일시정지로 나뉜 구간(segment)은 서로 이어 그리지 않는다
        val segments = points.groupBy { it.segment }.values.filter { it.size >= 2 }
        if (segments.isNotEmpty()) {
            val lines = segments.map { seg -> seg.map { Point.fromLngLat(it.lng, it.lat) } }
            val feature = Feature.fromGeometry(MultiLineString.fromLngLats(lines))
            routeSource?.setGeoJson(FeatureCollection.fromFeature(feature))
        } else {
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }

        val posSource = style.getSourceAs<GeoJsonSource>(CURRENT_POS_SOURCE_ID)
        val latest = points.lastOrNull()
        if (latest != null) {
            val latestPoint = Point.fromLngLat(latest.lng, latest.lat)
            posSource?.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(latestPoint)))
            Log.d("RunUi", "map updated: points=${points.size}")
            if (following) {
                val zoom = if (points.size <= 1) 16.0 else map.cameraPosition.zoom
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(latest.lat, latest.lng), zoom),
                    400,
                )
            }
        } else {
            posSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )
}

@Composable
private fun RoundControl(label: String, mark: String, color: Color, diameter: Int, click: () -> Unit) {
    IconButton(
        onClick = click,
        modifier = Modifier.size(diameter.dp).background(color, CircleShape).semantics { contentDescription = label },
    ) {
        Text(mark, Modifier.clearAndSetSemantics {}, color = Ink, fontSize = 24.sp)
    }
}

@Composable
internal fun EndRunDialog(dismiss: () -> Unit, save: () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Spacer(Modifier.matchParentSize().clickable(indication = null, interactionSource = null, onClick = dismiss).clearAndSetSemantics {})
            Surface(
                Modifier.navigationBarsPadding().padding(14.dp).widthIn(max = 460.dp).fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = Color.White,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(48.dp).background(Color(0xffeef1ed), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                        Label("▢", 21)
                    }
                    Label("러닝을 종료할까요?", 20, true)
                    Label("지금까지의 러닝 기록과 경로를 저장해요.", 13, false, Muted)
                    Action("종료하고 저장하기", save)
                    TextButton(dismiss, Modifier.fillMaxWidth()) { Label("계속 달리기", 15, true, Muted) }
                }
            }
        }
    }
}