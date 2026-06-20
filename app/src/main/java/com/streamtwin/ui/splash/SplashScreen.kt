package com.streamtwin.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import android.Manifest
import android.os.Build
import android.provider.Settings
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import com.streamtwin.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SplashScreen(
    onNavigateToHome: () -> Unit,
    onNavigateToConnect: () -> Unit,
    onNavigateToPermissionsFromSplash: (String) -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    val shouldSkipConnect by viewModel.shouldSkipConnect.collectAsState()

    val context = LocalContext.current
    
    val permissionsList = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
        permissionsList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    val permissionsState = rememberMultiplePermissionsState(permissions = permissionsList)
    val hasOverlay = Settings.canDrawOverlays(context)

    LaunchedEffect(Unit) {
        delay(1500)
        if (!permissionsState.allPermissionsGranted || !hasOverlay) {
            onNavigateToPermissionsFromSplash("permissions")
        } else {
            onNavigateToHome()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceContainerLowest)
    ) {
        // Center Content
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(SurfaceContainerHigh, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Sensors,
                    contentDescription = "StreamTwin Logo",
                    tint = Primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "STREAMTWIN",
                fontFamily = Manrope,
                fontWeight = FontWeight.Black,
                fontSize = 36.sp,
                color = Primary,
                letterSpacing = 0.25.em
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "STREAM EVERYWHERE. AT ONCE.",
                fontFamily = Inter,
                fontSize = 13.sp,
                color = OnSurfaceVariant.copy(alpha = 0.6f),
                letterSpacing = 0.15.em
            )
        }

        // Bottom Loading Content
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .width(140.dp)
                    .height(2.dp),
                color = Primary,
                trackColor = Primary.copy(alpha = 0.1f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "INITIALIZING CORE",
                fontFamily = Inter,
                fontSize = 10.sp,
                color = OnSurfaceVariant.copy(alpha = 0.4f),
                letterSpacing = 0.1.em
            )
        }
    }
}
