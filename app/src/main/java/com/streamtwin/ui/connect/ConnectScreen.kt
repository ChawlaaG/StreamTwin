package com.streamtwin.ui.connect

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.streamtwin.MainViewModel
import com.streamtwin.ui.settings.SettingsViewModel
import com.streamtwin.ui.theme.*
import com.streamtwin.util.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val mainViewModel: MainViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val coroutineScope = rememberCoroutineScope()

    val isTwitchConnected by mainViewModel.isTwitchConnected.collectAsState(false)
    val isYouTubeConnected by mainViewModel.isYouTubeConnected.collectAsState(false)
    val isKickConnected by mainViewModel.isKickConnected.collectAsState(false)
    
    val continueEnabled = isTwitchConnected || isYouTubeConnected || isKickConnected

    var showTwitchWebView by remember { mutableStateOf(false) }
    var showKickSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val kickUrl by settingsViewModel.kickRtmpUrl.collectAsState()
    val kickKey by settingsViewModel.kickStreamKey.collectAsState()

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account?.account != null) {
                coroutineScope.launch {
                    try {
                        val token = withContext(Dispatchers.IO) {
                            GoogleAuthUtil.getToken(context, account.account!!, "oauth2:https://www.googleapis.com/auth/youtube.force-ssl")
                        }
                        mainViewModel.handleYouTubeAuthToken(token)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        android.widget.Toast.makeText(context, "Token Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                android.widget.Toast.makeText(context, "Google Account is null", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: ApiException) {
            e.printStackTrace()
            android.widget.Toast.makeText(context, "Sign In Failed (Code: ${e.statusCode})", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    if (showTwitchWebView) {
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
                                    mainViewModel.handleTwitchAuthToken(token)
                                }
                                showTwitchWebView = false
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "STREAMTWIN",
                            fontFamily = Manrope,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            color = Primary,
                            letterSpacing = 0.2.em
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { }) {
                        Icon(imageVector = Icons.Outlined.GridView, contentDescription = "Menu", tint = Primary)
                    }
                },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(imageVector = Icons.Outlined.Settings, contentDescription = "Settings", tint = Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
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
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "Connect Your\nPlatforms",
                fontFamily = Manrope,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 40.sp,
                color = OnSurface,
                lineHeight = 44.sp,
                letterSpacing = (-1).sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Link at least one platform to start streaming to your audience.",
                fontFamily = Inter,
                fontSize = 16.sp,
                color = OnSurfaceVariant,
                modifier = Modifier.width(320.dp),
                lineHeight = 22.sp
            )
            
            Spacer(modifier = Modifier.height(40.dp))

            // Twitch Card
            PlatformCard(
                name = "Twitch",
                subtitle = "Direct API OAuth",
                icon = Icons.Outlined.Videocam,
                brandColor = TwitchPurple,
                isConnected = isTwitchConnected,
                onConnectClick = { showTwitchWebView = true }
            )
            Spacer(modifier = Modifier.height(12.dp))

            // YouTube Card
            PlatformCard(
                name = "YouTube",
                subtitle = "Streaming Hub",
                icon = Icons.Outlined.SmartDisplay,
                brandColor = YouTubeRed,
                isConnected = isYouTubeConnected,
                onConnectClick = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestEmail()
                        .requestScopes(Scope("https://www.googleapis.com/auth/youtube.force-ssl"))
                        .build()
                    val googleSignInClient = GoogleSignIn.getClient(context, gso)
                    googleSignInLauncher.launch(googleSignInClient.signInIntent)
                }
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Kick Card
            PlatformCard(
                name = "Kick",
                subtitle = "Manual key entry — no API login",
                icon = Icons.Outlined.Bolt,
                brandColor = KickGreen,
                isConnected = isKickConnected,
                onConnectClick = { showKickSheet = true }
            )

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(40.dp))

            // Bottom Actions
            Button(
                onClick = onAuthSuccess,
                enabled = continueEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary,
                    disabledContainerColor = SurfaceContainerHigh,
                    disabledContentColor = OnSurfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = "CONTINUE TO DASHBOARD",
                    fontFamily = Inter,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    letterSpacing = 0.1.em
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            TextButton(
                onClick = {
                    coroutineScope.launch { mainViewModel.markLoginSkipped() }
                    onAuthSuccess()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "SKIP FOR NOW",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = OnSurfaceVariant,
                    letterSpacing = 0.05.em
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        if (showKickSheet) {
            ModalBottomSheet(
                onDismissRequest = { showKickSheet = false },
                sheetState = sheetState,
                containerColor = SurfaceContainerLowest
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "Connect to Kick",
                        fontFamily = Manrope,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = OnSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter your RTMP URL and Stream Key from kick.com/dashboard/settings/stream",
                        fontFamily = Inter,
                        fontSize = 14.sp,
                        color = OnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    OutlinedTextField(
                        value = kickUrl,
                        onValueChange = { settingsViewModel.updateKickUrl(it) },
                        label = { Text("RTMP URL", color = OnSurfaceVariant) },
                        placeholder = { Text("rtmps://live.kick.com/app") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KickGreen,
                            unfocusedBorderColor = OutlineVariant,
                            focusedContainerColor = SurfaceContainerLow,
                            unfocusedContainerColor = SurfaceContainerLow,
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = kickKey,
                        onValueChange = { settingsViewModel.updateKickKey(it) },
                        label = { Text("Stream Key", color = OnSurfaceVariant) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KickGreen,
                            unfocusedBorderColor = OutlineVariant,
                            focusedContainerColor = SurfaceContainerLow,
                            unfocusedContainerColor = SurfaceContainerLow,
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface
                        )
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = {
                            settingsViewModel.setKickEnabled(kickUrl.isNotEmpty() && kickKey.isNotEmpty())
                            settingsViewModel.saveSettings()
                            coroutineScope.launch {
                                sheetState.hide()
                                showKickSheet = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KickGreen, contentColor = Background)
                    ) {
                        Text("SAVE KICK CONFIGURATION", fontFamily = Inter, fontWeight = FontWeight.Bold, letterSpacing = 0.05.em)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun PlatformCard(
    name: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    brandColor: androidx.compose.ui.graphics.Color,
    isConnected: Boolean,
    onConnectClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(brandColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = brandColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontFamily = Manrope,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = OnSurface
                )
                Text(
                    text = subtitle,
                    fontFamily = Inter,
                    fontSize = 12.sp,
                    color = if (brandColor == KickGreen) KickGreen else OnSurfaceVariant
                )
            }
            
            if (isConnected) {
                Row(
                    modifier = Modifier
                        .background(LiveGreen.copy(alpha = 0.1f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(8.dp).background(LiveGreen, CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CONNECTED",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = LiveGreen,
                        letterSpacing = 0.05.em
                    )
                }
            } else {
                Button(
                    onClick = onConnectClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Connect",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
