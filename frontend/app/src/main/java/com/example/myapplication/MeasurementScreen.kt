package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
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
import org.maplibre.geojson.LineString
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
        else -> "GPS 측정 중"
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color(0xffe6ece5))) {
        val mapHeight = (maxHeight * .43f).coerceIn(160.dp, 420.dp)
        val panelHeight = (maxHeight - mapHeight).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(mapHeight)) {
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
                    Spacer(Modifier.size(7.dp).background(if (trackingState.isPaused) Muted else Color(0xff79c75e), CircleShape))
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

    // 스타일 준비 시 기기 현재(마지막 알려진) 위치로 초기 마커 및 카메라 이동
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

        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && points.isEmpty()) {
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

    // 버튼 클릭 시 현재 위치(최신 좌표 또는 기기 마지막 위치)로 카메라 이동
    LaunchedEffect(recenterTrigger, mapLibreMap, isStyleReady) {
        if (recenterTrigger == 0 || !isStyleReady) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect

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

        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
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
        if (points.size >= 2) {
            val linePoints = points.map { Point.fromLngLat(it.lng, it.lat) }
            val feature = Feature.fromGeometry(LineString.fromLngLats(linePoints))
            routeSource?.setGeoJson(FeatureCollection.fromFeature(feature))
        } else {
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }

        val posSource = style.getSourceAs<GeoJsonSource>(CURRENT_POS_SOURCE_ID)
        val latest = points.lastOrNull()
        if (latest != null) {
            val latestPoint = Point.fromLngLat(latest.lng, latest.lat)
            posSource?.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(latestPoint)))
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(latest.lat, latest.lng), 16.0),
                400,
            )
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