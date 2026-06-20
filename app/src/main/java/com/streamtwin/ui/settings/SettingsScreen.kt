package com.streamtwin.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.streamtwin.ui.components.StreamTwinBottomNav
import com.streamtwin.ui.theme.*
import com.streamtwin.ui.clip.ClipViewModel
import com.streamtwin.util.AppConfig
import com.streamtwin.util.PermissionSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    clipViewModel: ClipViewModel = hiltViewModel()
) {
    val user by viewModel.twitchUser.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var showWebView by remember { mutableStateOf(false) }
    var showAutoClipApps by remember { mutableStateOf(false) }
    var showUsageAccessRationale by remember { mutableStateOf(false) }
    var hasUsageAccess by remember { mutableStateOf(viewModel.hasUsageAccess()) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account?.account != null) {
                scope.launch {
                    try {
                        val token = withContext(Dispatchers.IO) {
                            GoogleAuthUtil.getToken(context, account.account!!, "oauth2:https://www.googleapis.com/auth/youtube.force-ssl")
                        }
                        viewModel.handleYouTubeAuthToken(token)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: ApiException) {
            e.printStackTrace()
        }
    }

    if (showWebView) {
        val authUrl = "${AppConfig.TWITCH_AUTH_URL}" +
                "?client_id=${AppConfig.TWITCH_CLIENT_ID}" +
                "&redirect_uri=${AppConfig.TWITCH_REDIRECT_URI}" +
                "&response_type=token" +
                "&scope=channel:read:stream_key+user:read:email+chat:read+chat:edit+channel:manage:broadcast"

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                    webChromeClient = android.webkit.WebChromeClient()
                    webViewClient = object : android.webkit.WebViewClient() {
                        private fun checkAndHandleRedirect(url: String): Boolean {
                            if (url.startsWith(AppConfig.TWITCH_REDIRECT_URI) || url.startsWith("https://localhost")) {
                                val fragment = Uri.parse(url).fragment
                                val token = fragment?.split("&")?.firstOrNull { it.startsWith("access_token=") }?.substringAfter("=")
                                if (token != null) {
                                    viewModel.handleTwitchAuthToken(token)
                                }
                                showWebView = false
                                return true
                            }
                            return false
                        }

                        override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (checkAndHandleRedirect(url)) return true
                            return super.shouldOverrideUrlLoading(view, request)
                        }

                        override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            if (url != null && checkAndHandleRedirect(url)) {
                                view?.stopLoading()
                                return
                            }
                            super.onPageStarted(view, url, favicon)
                        }
                    }
                    loadUrl(authUrl)
                }
            }
        )
        return
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsageAccess = viewModel.hasUsageAccess()
                viewModel.refreshAutoClipMonitor()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showUsageAccessRationale) {
        AlertDialog(
            onDismissRequest = { showUsageAccessRationale = false },
            icon = { Icon(Icons.Outlined.Security, contentDescription = null, tint = Primary) },
            title = {
                Text(
                    "Allow Usage Access",
                    fontFamily = Manrope,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Auto Clip needs Usage Access to know when one of your selected games is opened. StreamTwin uses it only to show the clip overlay for the apps you choose.",
                    fontFamily = Inter,
                    color = OnSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUsageAccessRationale = false
                        PermissionSettings.openUsageAccess(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("Open Settings", color = OnPrimary, fontFamily = Inter, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUsageAccessRationale = false }) {
                    Text("Not Now", fontFamily = Inter)
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "SETTINGS",
                        fontFamily = Manrope,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        color = OnSurface,
                        letterSpacing = (-0.5).sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Outlined.GridView, contentDescription = "Menu", tint = OnSurface)
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(36.dp)
                            .background(SurfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            user?.displayName?.take(1) ?: "U",
                            fontFamily = Manrope,
                            fontWeight = FontWeight.ExtraBold,
                            color = Primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        bottomBar = {
            StreamTwinBottomNav(
                currentRoute = "settings",
                onNavigate = { route ->
                    if (route == "home") onNavigateBack()
                }
            )
        },
        containerColor = Background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("MY ACCOUNT")
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
            ) {
                if (user != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(TwitchPurple.copy(alpha=0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(user?.displayName?.take(1) ?: "T", color = TwitchPurple, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, fontFamily = Manrope)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user?.displayName ?: "Loading...", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = OnSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Connected via Twitch", fontFamily = Inter, color = TwitchPurple, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(Icons.Outlined.Logout, "Disconnect", tint = OnSurfaceVariant)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Not Connected to Twitch", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OnSurface)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showWebView = true },
                            colors = ButtonDefaults.buttonColors(containerColor = TwitchPurple),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Connect Twitch", fontFamily = Inter, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── Streaming Destinations Section ──
            SectionHeader("STREAMING DESTINATIONS")
            Spacer(modifier = Modifier.height(12.dp))
            
            val kickEnabled by viewModel.kickEnabled.collectAsState(false)
            val youtubeEnabled by viewModel.youtubeEnabled.collectAsState(false)
            val twitchEnabled by viewModel.twitchEnabled.collectAsState(true)
            val kickKey by viewModel.kickStreamKey.collectAsState()
            val kickUrl by viewModel.kickRtmpUrl.collectAsState()
            val saveSuccess by viewModel.saveSuccess.collectAsState()

            val isTwitchConnected = user != null
            val isYoutubeConnected by viewModel.isYoutubeConnected.collectAsState()
            val isKickConnected by viewModel.isKickConnected.collectAsState()

            val atLeastOneEnabled = { 
                var count = 0
                if (twitchEnabled) count++
                if (youtubeEnabled) count++
                if (kickEnabled) count++
                count
            }

            val showValidationError = {
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("At least one streaming destination must be active")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
            ) {
                Column {
                    SettingRow(
                        icon = Icons.Outlined.Videocam, "Twitch", TwitchPurple,
                        trailing = {
                            if (isTwitchConnected) {
                                Switch(
                                    checked = twitchEnabled,
                                    onCheckedChange = { 
                                        if (!it && atLeastOneEnabled() <= 1) showValidationError() else viewModel.setTwitchEnabled(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = OnPrimary,
                                        checkedTrackColor = TwitchPurple,
                                        uncheckedThumbColor = OnSurfaceVariant,
                                        uncheckedTrackColor = SurfaceContainerHigh,
                                        uncheckedBorderColor = Color.Transparent
                                    )
                                )
                            } else {
                                ConnectButton(onClick = { showWebView = true })
                            }
                        }
                    )
                    HorizontalDivider(color = OutlineVariant.copy(alpha=0.3f), modifier = Modifier.padding(start = 56.dp))

                    SettingRow(
                        icon = Icons.Outlined.SmartDisplay, "YouTube", YouTubeRed,
                        trailing = {
                            if (isYoutubeConnected) {
                                Switch(
                                    checked = youtubeEnabled,
                                    onCheckedChange = { 
                                        if (!it && atLeastOneEnabled() <= 1) showValidationError() else viewModel.setYoutubeEnabled(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = OnPrimary,
                                        checkedTrackColor = YouTubeRed,
                                        uncheckedThumbColor = OnSurfaceVariant,
                                        uncheckedTrackColor = SurfaceContainerHigh,
                                        uncheckedBorderColor = Color.Transparent
                                    )
                                )
                            } else {
                                ConnectButton(onClick = {
                                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                        .requestEmail()
                                        .requestScopes(Scope("https://www.googleapis.com/auth/youtube.force-ssl"))
                                        .build()
                                    val googleSignInClient = GoogleSignIn.getClient(context, gso)
                                    googleSignInLauncher.launch(googleSignInClient.signInIntent)
                                })
                            }
                        }
                    )

                    HorizontalDivider(color = OutlineVariant.copy(alpha=0.3f), modifier = Modifier.padding(start = 56.dp))

                    SettingRow(
                        icon = Icons.Outlined.Bolt, "Kick", KickGreen,
                        trailing = {
                            if (isKickConnected || kickEnabled) {
                                Switch(
                                    checked = kickEnabled,
                                    onCheckedChange = { 
                                        if (!it && atLeastOneEnabled() <= 1) showValidationError() else viewModel.setKickEnabled(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Background,
                                        checkedTrackColor = KickGreen,
                                        uncheckedThumbColor = OnSurfaceVariant,
                                        uncheckedTrackColor = SurfaceContainerHigh,
                                        uncheckedBorderColor = Color.Transparent
                                    )
                                )
                            } else {
                                ConnectButton(onClick = { viewModel.setKickEnabled(true) })
                            }
                        }
                    )
                    if (kickEnabled) {
                        Column(modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 16.dp)) {
                            SecureTextField(
                                value = kickUrl,
                                onValueChange = { viewModel.updateKickUrl(it) },
                                label = "RTMP URL (Kick)",
                                placeholder = "rtmp://..."
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SecureTextField(
                                value = kickKey,
                                onValueChange = { viewModel.updateKickKey(it) },
                                label = "Stream Key (Kick)",
                                placeholder = "sk_...",
                                isPassword = true
                            )
                        }
                    }

                    if (kickEnabled || youtubeEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.saveSettings() },
                            modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (saveSuccess) LiveGreen else Primary)
                        ) {
                            Text(if (saveSuccess) "SAVED CONFIG" else "SAVE DESTINATIONS", fontFamily=Inter, fontWeight=FontWeight.Bold, color=if(saveSuccess) Background else OnPrimary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            SectionHeader("APPLICATION")
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
            ) {
                Column {
                    SettingRow(icon = Icons.Outlined.Info, "Version", Primary, trailing = { Text("1.0.0", color = OnSurfaceVariant, fontFamily = Inter) })
                    HorizontalDivider(color = OutlineVariant.copy(alpha=0.3f), modifier = Modifier.padding(start = 56.dp))
                    SettingRow(icon = Icons.Outlined.Description, "Terms of Use", Primary)
                    HorizontalDivider(color = OutlineVariant.copy(alpha=0.3f), modifier = Modifier.padding(start = 56.dp))
                    SettingRow(icon = Icons.Outlined.Policy, "Privacy Policy", Primary)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            SectionHeader("CLIP MODE")
            Spacer(modifier = Modifier.height(12.dp))
            
            val clipDuration by clipViewModel.clipDuration.collectAsState()
            val includeMic by clipViewModel.includeMic.collectAsState()
            val autoClipEnabled by viewModel.autoClipEnabled.collectAsState()
            val autoClipPackages by viewModel.autoClipPackages.collectAsState()
            val installedApps by viewModel.installedApps.collectAsState()
            val selectedAppNames = installedApps
                .filter { autoClipPackages.contains(it.packageName) }
                .map { it.label }
                .take(3)
                .joinToString(", ")

            if (showAutoClipApps) {
                AutoClipAppPickerSheet(
                    apps = installedApps,
                    selectedPackages = autoClipPackages,
                    onTogglePackage = { viewModel.toggleAutoClipPackage(it) },
                    onDismiss = { showAutoClipApps = false }
                )
            }
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Clip Duration", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 90, 120).forEach { seconds ->
                            val isSelected = clipDuration == seconds
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Primary else SurfaceContainerHigh)
                                    .clickable { clipViewModel.updateClipDuration(seconds) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${seconds}s", color = if (isSelected) OnPrimary else OnSurface, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = OutlineVariant.copy(alpha=0.3f))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Include Microphone", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurface)
                            Text("Record your voice in clips", fontFamily = Inter, fontSize = 12.sp, color = OnSurfaceVariant)
                        }
                        Switch(
                            checked = includeMic,
                            onCheckedChange = { clipViewModel.updateIncludeMic(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnPrimary,
                                checkedTrackColor = Primary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = OutlineVariant.copy(alpha=0.3f))
                    Spacer(modifier = Modifier.height(8.dp))

                    val backTapEnabled by clipViewModel.backTapEnabled.collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Back Tap to Clip", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurface)
                            Text(
                                "Triple-tap the back of your phone to save a clip. Turn off if you get false triggers during gameplay.",
                                fontFamily = Inter, fontSize = 12.sp, color = OnSurfaceVariant
                            )
                        }
                        Switch(
                            checked = backTapEnabled,
                            onCheckedChange = { clipViewModel.updateBackTapEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnPrimary,
                                checkedTrackColor = Primary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = OutlineVariant.copy(alpha=0.3f))
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Auto Clip for Apps", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurface)
                            Text(
                                when {
                                    autoClipPackages.isEmpty() -> "Choose games and StreamTwin will show the clip bubble when they open."
                                    selectedAppNames.isNotEmpty() -> selectedAppNames + if (autoClipPackages.size > 3) " +${autoClipPackages.size - 3}" else ""
                                    else -> "${autoClipPackages.size} selected apps"
                                },
                                fontFamily = Inter,
                                fontSize = 12.sp,
                                color = OnSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoClipEnabled,
                            onCheckedChange = { enabled ->
                                when {
                                    enabled && !hasUsageAccess -> {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Usage Access is needed only for Auto Clip app detection")
                                        }
                                        showUsageAccessRationale = true
                                    }
                                    enabled && autoClipPackages.isEmpty() -> {
                                        showAutoClipApps = true
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Select at least one app for Auto Clip")
                                        }
                                    }
                                    else -> viewModel.setAutoClipEnabled(enabled)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnPrimary,
                                checkedTrackColor = Primary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showAutoClipApps = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurface)
                        ) {
                            Text(
                                if (autoClipPackages.isEmpty()) "Choose Apps" else "Edit Apps",
                                fontFamily = Inter,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { showUsageAccessRationale = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasUsageAccess) LiveGreen else StandbyAmber)
                        ) {
                            Text(
                                if (hasUsageAccess) "Usage Access On" else "Grant Access",
                                fontFamily = Inter,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
            
            TextButton(
                onClick = {
                    viewModel.disconnect()
                    onSignOut()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    "Log Out from StreamTwin",
                    fontFamily = Inter,
                    color = ErrorRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    iconColor: Color = Primary,
    trailing: @Composable() (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).background(iconColor.copy(alpha=0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(title, fontFamily = Inter, color = OnSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun SecureTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isPassword: Boolean = false
) {
    Column {
        Text(label, fontFamily = Inter, color = OnSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            placeholder = { Text(placeholder, color = OnSurfaceVariant.copy(alpha = 0.5f), fontSize = 14.sp) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnSurface, fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = OutlineVariant,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontFamily = Inter,
        color = OnSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.1.em,
        modifier = Modifier.padding(start = 8.dp)
    )
}

@Composable
fun ConnectButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Primary),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Text("Connect", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = OnPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoClipAppPickerSheet(
    apps: List<LaunchableAppInfo>,
    selectedPackages: Set<String>,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceContainerLow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 20.dp)
        ) {
            Text(
                "Choose Auto Clip Apps",
                fontFamily = Manrope,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                color = OnSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Games are listed first when Android identifies them. You can still choose any app.",
                fontFamily = Inter,
                fontSize = 13.sp,
                color = OnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search apps", color = OnSurfaceVariant.copy(alpha = 0.7f)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = OnSurfaceVariant) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = OutlineVariant,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val selected = selectedPackages.contains(app.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onTogglePackage(app.packageName) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(if (selected) Primary.copy(alpha = 0.2f) else SurfaceContainerHigh, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                app.label.take(1).uppercase(),
                                fontFamily = Manrope,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (selected) Primary else OnSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(app.label, fontFamily = Inter, fontWeight = FontWeight.Bold, color = OnSurface, fontSize = 14.sp)
                                if (app.isGame) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Game",
                                        fontFamily = Inter,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        color = LiveGreen,
                                        modifier = Modifier
                                            .background(LiveGreen.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(app.packageName, fontFamily = Inter, color = OnSurfaceVariant, fontSize = 11.sp)
                        }
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onTogglePackage(app.packageName) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Primary,
                                uncheckedColor = OnSurfaceVariant,
                                checkmarkColor = OnPrimary
                            )
                        )
                    }
                    HorizontalDivider(color = OutlineVariant.copy(alpha = 0.2f))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Done", fontFamily = Inter, fontWeight = FontWeight.Bold, color = OnPrimary)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
