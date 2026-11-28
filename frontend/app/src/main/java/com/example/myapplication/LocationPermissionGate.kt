package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat

internal fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

/** 러닝 거리 측정에는 '정확한 위치'가 필요하다. '대략적인 위치'만 허용된 경우는 false. */
internal fun hasPreciseLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

/** 권한을 다시 물을 수 없을 때(거부 후 "다시 묻지 않음") 앱 설정 화면으로 보낸다. */
internal fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
internal fun rememberStartRunWithPermissions(
    context: Context,
    onPermissionGrantedStart: (String) -> Unit,
    onPermissionDenied: () -> Unit = {},
): (String) -> Unit {
    val pendingTitle = remember { arrayOfNulls<String>(1) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // '대략적인 위치'만 허용하면 수백 m~수 km씩 틀어져서 거리 측정이 안 되므로 정확한 위치만 허용으로 본다
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val title = pendingTitle[0]
        pendingTitle[0] = null
        if (granted && title != null) {
            onPermissionGrantedStart(title)
        } else {
            onPermissionDenied()
        }
    }

    return { title ->
        if (hasPreciseLocationPermission(context)) {
            onPermissionGrantedStart(title)
        } else {
            pendingTitle[0] = title
            val perms = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            launcher.launch(perms)
        }
    }
}