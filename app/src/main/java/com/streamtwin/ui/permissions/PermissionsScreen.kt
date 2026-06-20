package com.streamtwin.ui.permissions

import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import com.streamtwin.ui.theme.*
import com.streamtwin.util.PermissionSettings

@Composable
fun PermissionsScreen(
    onAllPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val runtimePermissions = mutableListOf(
        PermissionSpec(
            permission = Manifest.permission.RECORD_AUDIO,
            title = "Microphone",
            description = "Used only when you include mic audio in streams or clips. You can mute it anytime."
        )
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runtimePermissions.add(
            PermissionSpec(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                title = "Notifications",
                description = "Shows active recording status so Android keeps streaming and clipping reliable.",
                icon = Icons.Default.Notifications
            )
        )
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
        runtimePermissions.add(
            PermissionSpec(
                permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
                title = "Storage",
                description = "Saves clips to your StreamTwin vault on older Android versions.",
                icon = Icons.Default.Folder
            )
        )
    }

    var refreshKey by remember { mutableIntStateOf(0) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val runtimePermissionStates = runtimePermissions.associateWith { spec ->
        refreshKey
        ContextCompat.checkSelfPermission(context, spec.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val allRuntimePermissionsGranted = runtimePermissionStates.values.all { it }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshKey++
    }

    // Check overlay permission when app resumes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
                hasOverlayPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-navigate if granted
    LaunchedEffect(allRuntimePermissionsGranted, hasOverlayPermission) {
        if (allRuntimePermissionsGranted && hasOverlayPermission) {
            onAllPermissionsGranted()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Permissions",
                fontFamily = Manrope,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = OnSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                "StreamTwin asks only for the permissions needed to record, save, and control clips while you play.",
                fontFamily = Inter,
                fontSize = 16.sp,
                color = OnSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            runtimePermissions.forEachIndexed { index, spec ->
                PermissionItem(
                    icon = spec.icon,
                    title = spec.title,
                    description = spec.description,
                    isGranted = runtimePermissionStates[spec] == true,
                    onClick = {
                        runtimePermissionLauncher.launch(arrayOf(spec.permission))
                    }
                )
                if (index != runtimePermissions.lastIndex) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PermissionItem(
                icon = Icons.Default.PictureInPicture,
                title = "Floating Overlay",
                description = "Shows the clip button over your selected game. Android should open StreamTwin's overlay permission page.",
                isGranted = hasOverlayPermission,
                onClick = { PermissionSettings.openOverlayPermission(context) }
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = {
                    if (!allRuntimePermissionsGranted) {
                        runtimePermissionLauncher.launch(
                            runtimePermissions
                                .filter { runtimePermissionStates[it] != true }
                                .map { it.permission }
                                .toTypedArray()
                        )
                    } else if (!hasOverlayPermission) {
                        PermissionSettings.openOverlayPermission(context)
                    } else {
                        onAllPermissionsGranted()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text(
                    "Grant Permissions",
                    color = OnPrimary,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

private data class PermissionSpec(
    val permission: String,
    val title: String,
    val description: String,
    val icon: ImageVector = Icons.Default.Mic
)

@Composable
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isGranted) SurfaceContainerHigh else SurfaceContainerLowest,
        border = if (isGranted) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f))
                 else androidx.compose.foundation.BorderStroke(1.dp, Outline)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(if (isGranted) Color(0xFF4CAF50).copy(alpha = 0.2f) else SurfaceContainerHigh, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isGranted) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                } else {
                    Icon(icon, contentDescription = null, tint = OnSurface)
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontFamily = Manrope,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = OnSurface
                )
                Text(
                    description,
                    fontFamily = Inter,
                    fontSize = 12.sp,
                    color = OnSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
