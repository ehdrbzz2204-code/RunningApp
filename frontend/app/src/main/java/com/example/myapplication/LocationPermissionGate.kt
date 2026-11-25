package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
        val granted = (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        val title = pendingTitle[0]
        pendingTitle[0] = null
        if (granted && title != null) {
            onPermissionGrantedStart(title)
        } else {
            onPermissionDenied()
        }
    }

    return { title ->
        if (hasLocationPermission(context)) {
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