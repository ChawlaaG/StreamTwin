package com.streamtwin.ui.connect

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.streamtwin.ui.theme.*
import com.streamtwin.util.AppConfig

@Composable
fun ConnectScreen(
    onAuthSuccess: () -> Unit // NavGraph or MainActivity observes auth
) {
    val context = LocalContext.current
    var showWebView by remember { mutableStateOf(false) }

    val authUrl = "${AppConfig.TWITCH_AUTH_URL}" +
            "?client_id=${AppConfig.TWITCH_CLIENT_ID}" +
            "&redirect_uri=${AppConfig.TWITCH_REDIRECT_URI}" +
            "&response_type=token" +
            "&scope=channel:read:stream_key+user:read:email+chat:read+chat:edit+channel:manage:broadcast"

    if (showWebView) {
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

                    webChromeClient = android.webkit.WebChromeClient()
                    webViewClient = object : android.webkit.WebViewClient() {

                        private fun checkAndHandleRedirect(url: String): Boolean {
                            if (url.startsWith(AppConfig.TWITCH_REDIRECT_URI)) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    setPackage(context.packageName)
                                }
                                context.startActivity(intent)
                                showWebView = false
                                return true
                            }
                            return false
                        }

                        override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (checkAndHandleRedirect(url)) return true
                            return super.shouldOverrideUrlLoading(view, request)
                        }

                        override fun onPageStarted(
                            view: android.webkit.WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
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
        return // Take up the whole screen
    }

    // ── entrance animation ──
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(600, delayMillis = 200, easing = EaseOutCubic)
    )
    val cardOffset by animateFloatAsState(
        targetValue = if (visible) 0f else 40f,
        animationSpec = tween(600, delayMillis = 200, easing = EaseOutCubic)
    )

    val backgroundGradient = Brush.linearGradient(
        colors = listOf(TwitchPurpleDark, DarkBackground, DarkBackground),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        // ── Decorative orbs ──
        Box(
            modifier = Modifier
                .size(200.dp)
                .offset(x = (-40).dp, y = (-60).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(TwitchPurpleGlow, DarkBackground.copy(alpha = 0f)),
                        radius = 300f
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(160.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 60.dp, y = 80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(TwitchPurpleGlow, DarkBackground.copy(alpha = 0f)),
                        radius = 250f
                    ),
                    CircleShape
                )
        )

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ── Title Area ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.3f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "StreamTwin",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your streaming companion",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                )
            }

            // ── Card Area ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
                    .alpha(cardAlpha)
                    .offset(y = cardOffset.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // ── Twitch Logo Circle ──
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(TwitchPurple, TwitchPurpleDark)
                                    ),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("T", color = PureWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Connect your Twitch account",
                            style = MaterialTheme.typography.titleMedium.copy(color = TextPrimary)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "We only request your stream key\nand basic profile info.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(28.dp))

                        // ── Connect Button with Gradient ──
                        Button(
                            onClick = { showWebView = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TwitchPurple),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 6.dp,
                                pressedElevation = 2.dp
                            )
                        ) {
                            Text(
                                "Connect with Twitch",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = PureWhite
                            )
                        }
                    }
                }
            }

            // ── Footer ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Terms of Use · Privacy Policy",
                    style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                )
            }
        }
    }
}
