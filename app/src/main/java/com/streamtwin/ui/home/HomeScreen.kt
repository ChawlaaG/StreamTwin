package com.streamtwin.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import com.streamtwin.ui.theme.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.streamtwin.data.remote.model.TwitchCategory
import com.streamtwin.ui.components.StreamTwinBottomNav
import com.streamtwin.ui.clip.ClipViewModel
import com.streamtwin.service.ClipModeService
import com.streamtwin.service.FloatingOverlayService
import android.media.projection.MediaProjectionManager

/**
 * Represents the currently selected mode on the Home screen.
 */
private enum class HomeMode {
    CHOOSER,   // Landing: pick Streaming or Clipping
    STREAMING, // Full streaming dashboard
    CLIPPING   // Full clipping dashboard
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    startMode: String? = null,
    onNavigateToSettings: () -> Unit,
    onNavigateToLive: () -> Unit,
    onNavigateToVault: () -> Unit,
    onNavigateToConnect: () -> Unit,
    onPermissionsRevoked: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    clipViewModel: ClipViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val twitchUser by viewModel.twitchUser.collectAsState()
    val streamTitle by viewModel.streamTitle.collectAsState()
    val streamQuality by viewModel.streamQuality.collectAsState()
    val streamFps by viewModel.streamFps.collectAsState()
    val streamBitrate by viewModel.streamBitrate.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()
    val streamHealth by viewModel.streamHealth.collectAsState()

    var showStreamSettings by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    val selectedGameId by viewModel.selectedGameId.collectAsState()
    val selectedGameName by viewModel.selectedGameName.collectAsState()
    val isStreamKeyReady by viewModel.isStreamKeyReady.collectAsState()

    val twitchEnabled by viewModel.twitchEnabled.collectAsState()
    val youtubeEnabled by viewModel.youtubeEnabled.collectAsState()
    val kickEnabled by viewModel.kickEnabled.collectAsState()
    val isSignedIn = twitchEnabled || youtubeEnabled || kickEnabled

    val clipDuration by clipViewModel.clipDuration.collectAsState()
    val includeMic by clipViewModel.includeMic.collectAsState()

    // --- Mode State ---
    var selectedMode by remember { 
        mutableStateOf(
            when (startMode) {
                "STREAMING" -> HomeMode.STREAMING
                "CLIPPING_AUTO" -> HomeMode.CLIPPING
                "CLIPPING" -> HomeMode.CLIPPING
                else -> HomeMode.CHOOSER
            }
        )
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val startIntent = Intent(context, ClipModeService::class.java).apply {
                action = ClipModeService.ACTION_START
                putExtra(ClipModeService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ClipModeService.EXTRA_PROJECTION_DATA, result.data)
                putExtra(ClipModeService.EXTRA_CLIP_DURATION, clipDuration)
                putExtra(ClipModeService.EXTRA_MUTE, !includeMic)
            }
            try {
                context.startForegroundService(startIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            clipViewModel.setClipModeActive(false)
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }

    var localTitle by remember { mutableStateOf(streamTitle) }

    LaunchedEffect(streamTitle) {
        if (localTitle != streamTitle) {
            localTitle = streamTitle
        }
    }

    LaunchedEffect(localTitle) {
        kotlinx.coroutines.delay(500L)
        viewModel.updateStreamTitle(localTitle)
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // If permissions revoked while backgrounded, boot to permissions screen
                val hasOverlay = Settings.canDrawOverlays(context)
                val requiredPermissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
                val hasRuntimePermissions = requiredPermissions.all { permission ->
                    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (!hasOverlay || !hasRuntimePermissions) {
                    onPermissionsRevoked()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val handleGoLive = l@{
        if (selectedMode == HomeMode.CLIPPING) {
            try {
                clipViewModel.setClipModeActive(true)
                val overlayIntent = Intent(context, FloatingOverlayService::class.java).apply {
                    putExtra("OVERLAY_MODE", "CLIP")
                    putExtra("CLIP_DURATION", clipDuration)
                    putExtra("CLIP_MUTE", !includeMic)
                }
                androidx.core.content.ContextCompat.startForegroundService(context, overlayIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            isSyncing = true
            viewModel.updateStreamTitle(localTitle)
            viewModel.syncSettingsWithTwitch(localTitle, selectedGameId) {
                isSyncing = false
                onNavigateToLive()
            }
        }
    }

    LaunchedEffect(startMode) {
        if (startMode == "CLIPPING_AUTO") {
            selectedMode = HomeMode.CLIPPING
            kotlinx.coroutines.delay(250)
            handleGoLive()
        }
    }

    // Dialogs removed since permissions are handled by PermissionsScreen

    if (showStreamSettings) {
        StreamSettingsSheet(
            onDismissRequest = { showStreamSettings = false },
            currentQuality = streamQuality,
            onQualitySelected = { viewModel.updateStreamQuality(it) },
            currentFps = streamFps,
            onFpsSelected = { viewModel.updateStreamFps(it) },
            currentBitrateKbps = streamBitrate / 1024,
            onBitrateChanged = { viewModel.updateStreamBitrate(it * 1024) },
            currentAspectRatio = aspectRatio,
            onAspectRatioSelected = { viewModel.updateAspectRatio(it) }
        )
    }

    if (showCategorySheet) {
        val categories by viewModel.categories.collectAsState()
        CategorySelectionSheet(
            onDismissRequest = { showCategorySheet = false },
            categories = categories,
            onCategorySelected = { cat ->
                viewModel.selectCategory(cat.id, cat.name)
                showCategorySheet = false
            },
            onSearch = { viewModel.searchCategories(it) }
        )
    }

    // --- Main Scaffold ---
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (selectedMode) {
                        HomeMode.CHOOSER -> "STREAMTWIN"
                        HomeMode.STREAMING -> "STREAMING"
                        HomeMode.CLIPPING -> "CLIPPING"
                    }
                    Text(
                        title,
                        fontFamily = Manrope,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        color = OnSurface,
                        letterSpacing = (-0.5).sp
                    )
                },
                navigationIcon = {
                    if (selectedMode != HomeMode.CHOOSER && isSignedIn) {
                        IconButton(onClick = { selectedMode = HomeMode.CHOOSER }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = OnSurface
                            )
                        }
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
                            twitchUser?.displayName?.take(1) ?: "U",
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
                currentRoute = "home",
                onNavigate = { route ->
                    if (route == "settings") onNavigateToSettings()
                    if (route == "vault") onNavigateToVault()
                }
            )
        },
        containerColor = Background
    ) { paddingValues ->

        AnimatedContent(
            targetState = selectedMode,
            transitionSpec = {
                fadeIn(animationSpec = tween(350)) + scaleIn(
                    initialScale = 0.94f,
                    animationSpec = tween(350)
                ) togetherWith fadeOut(animationSpec = tween(200))
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            label = "home_mode_transition"
        ) { mode ->
            when (mode) {
                HomeMode.CHOOSER -> ModeChooserContent(
                    onStreamingSelected = { 
                        if (isSignedIn) selectedMode = HomeMode.STREAMING 
                        else onNavigateToConnect() 
                    },
                    onClippingSelected = { selectedMode = HomeMode.CLIPPING },
                    twitchEnabled = twitchEnabled,
                    youtubeEnabled = youtubeEnabled,
                    kickEnabled = kickEnabled
                )

                HomeMode.STREAMING -> StreamingDashboardContent(
                    localTitle = localTitle,
                    onTitleChange = { localTitle = it },
                    selectedGameName = selectedGameName,
                    onCategoryClick = { showCategorySheet = true },
                    streamQuality = streamQuality,
                    streamFps = streamFps,
                    streamBitrate = streamBitrate,
                    onEditQuality = { showStreamSettings = true },
                    twitchEnabled = twitchEnabled,
                    youtubeEnabled = youtubeEnabled,
                    kickEnabled = kickEnabled,
                    isStreamKeyReady = isStreamKeyReady,
                    isSyncing = isSyncing,
                    onGoLive = handleGoLive
                )

                HomeMode.CLIPPING -> ClippingDashboardContent(
                    clipDuration = clipDuration,
                    onDurationChange = { clipViewModel.updateClipDuration(it) },
                    includeMic = includeMic,
                    onIncludeMicChange = { clipViewModel.updateIncludeMic(it) },
                    onStartClipping = handleGoLive
                )
            }
        }
    }
}

// =====================================================================
//  MODE CHOOSER — Premium landing with two large hero cards
// =====================================================================
@Composable
private fun ModeChooserContent(
    onStreamingSelected: () -> Unit,
    onClippingSelected: () -> Unit,
    twitchEnabled: Boolean,
    youtubeEnabled: Boolean,
    kickEnabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "What do you\nwant to do?",
            fontFamily = Manrope,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 34.sp,
            color = OnSurface,
            lineHeight = 40.sp,
            letterSpacing = (-1).sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Choose your mode to get started.",
            fontFamily = Inter,
            fontSize = 16.sp,
            color = OnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- Streaming Card ---
        ModeHeroCard(
            title = "Go Live",
            subtitle = "Stream to your audience in real time",
            icon = Icons.Outlined.Sensors,
            gradientColors = listOf(
                TwitchPurple.copy(alpha = 0.25f),
                YouTubeRed.copy(alpha = 0.12f),
                SurfaceContainerLow
            ),
            accentColor = Primary,
            onClick = onStreamingSelected,
            badges = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (twitchEnabled) MiniPlatformBadge("Twitch", TwitchPurple)
                    if (youtubeEnabled) MiniPlatformBadge("YouTube", YouTubeRed)
                    if (kickEnabled) MiniPlatformBadge("Kick", KickGreen)
                    if (!twitchEnabled && !youtubeEnabled && !kickEnabled) {
                        MiniPlatformBadge("Setup from Settings", OnSurfaceVariant)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Clipping Card ---
        ModeHeroCard(
            title = "Clip It",
            subtitle = "Capture your best gaming moments instantly",
            icon = Icons.Outlined.ContentCut,
            gradientColors = listOf(
                LiveGreen.copy(alpha = 0.2f),
                Secondary.copy(alpha = 0.1f),
                SurfaceContainerLow
            ),
            accentColor = LiveGreen,
            onClick = onClippingSelected,
            badges = {
                Row(
                    modifier = Modifier
                        .background(LiveGreen.copy(alpha = 0.1f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.FiberManualRecord, contentDescription = null, tint = LiveGreen, modifier = Modifier.size(10.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "NO LOGIN REQUIRED",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = LiveGreen,
                        letterSpacing = 0.05.em
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ModeHeroCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradientColors: List<Color>,
    accentColor: Color,
    onClick: () -> Unit,
    badges: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "card_press_scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(gradientColors),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(
                    width = 1.dp,
                    color = accentColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(24.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Icon(
                        Icons.Outlined.ArrowForward,
                        contentDescription = "Enter",
                        tint = accentColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = title,
                    fontFamily = Manrope,
                    fontWeight = FontWeight.Black,
                    fontSize = 28.sp,
                    color = OnSurface,
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    fontFamily = Inter,
                    fontSize = 14.sp,
                    color = OnSurfaceVariant,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                badges()
            }
        }
    }
}

@Composable
private fun MiniPlatformBadge(label: String, color: Color) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            label,
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = color
        )
    }
}

// =====================================================================
//  STREAMING DASHBOARD — existing stream setup UI, cleaned up
// =====================================================================
@Composable
private fun StreamingDashboardContent(
    localTitle: String,
    onTitleChange: (String) -> Unit,
    selectedGameName: String,
    onCategoryClick: () -> Unit,
    streamQuality: String,
    streamFps: Int,
    streamBitrate: Int,
    onEditQuality: () -> Unit,
    twitchEnabled: Boolean,
    youtubeEnabled: Boolean,
    kickEnabled: Boolean,
    isStreamKeyReady: Boolean,
    isSyncing: Boolean,
    onGoLive: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Destination pills
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (twitchEnabled) item { PlatformPill("Twitch", TwitchPurple, Icons.Outlined.Videocam) }
                if (youtubeEnabled) item { PlatformPill("YouTube", YouTubeRed, Icons.Outlined.SmartDisplay) }
                if (kickEnabled) item { PlatformPill("Kick", KickGreen, Icons.Outlined.Bolt) }
                if (!twitchEnabled && !youtubeEnabled && !kickEnabled) {
                    item { PlatformPill("No Platforms", SurfaceContainerHighest, Icons.Outlined.Warning) }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Stream Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("STREAM INFO", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Primary, letterSpacing = 0.05.em)
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    TextField(
                        value = localTitle,
                        onValueChange = onTitleChange,
                        placeholder = { Text("Stream Title", color = OnSurfaceVariant) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceContainerHighest.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = Inter,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceContainerHighest.copy(alpha = 0.5f))
                            .clickable { onCategoryClick() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = selectedGameName.ifEmpty { "Select Category" },
                            fontFamily = Inter,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (selectedGameName.isEmpty()) OnSurfaceVariant else OnSurface
                        )
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = OnSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Quality Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("OUTPUT QUALITY", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Primary, letterSpacing = 0.05.em)
                        }
                        Text(
                            "EDIT",
                            fontFamily = Inter,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Primary,
                            modifier = Modifier.clickable { onEditQuality() }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("RESOLUTION", fontFamily = Inter, fontSize = 10.sp, color = OnSurfaceVariant, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(streamQuality, fontFamily = JetBrainsMono, fontSize = 16.sp, color = OnSurface, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("FRAMERATE", fontFamily = Inter, fontSize = 10.sp, color = OnSurfaceVariant, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("${streamFps}fps", fontFamily = JetBrainsMono, fontSize = 16.sp, color = OnSurface, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("BITRATE", fontFamily = Inter, fontSize = 10.sp, color = OnSurfaceVariant, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("${streamBitrate / 1024}k", fontFamily = JetBrainsMono, fontSize = 16.sp, color = OnSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(110.dp))
        }

        // Fixed GO LIVE button
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
            Button(
                onClick = onGoLive,
                enabled = isStreamKeyReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary,
                    disabledContainerColor = SurfaceContainerHigh,
                    disabledContentColor = OnSurfaceVariant.copy(alpha = 0.5f)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
            ) {
                val statusText = when {
                    isSyncing -> "INITIALIZING..."
                    isStreamKeyReady -> "GO LIVE"
                    else -> "SETUP REQUIRED"
                }
                Text(
                    text = statusText,
                    fontFamily = Manrope,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    letterSpacing = 0.05.em
                )
            }
        }
    }
}

// =====================================================================
//  CLIPPING DASHBOARD — settings + start button
// =====================================================================
@Composable
private fun ClippingDashboardContent(
    clipDuration: Int,
    onDurationChange: (Int) -> Unit,
    includeMic: Boolean,
    onIncludeMicChange: (Boolean) -> Unit,
    onStartClipping: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Hero header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(LiveGreen.copy(alpha = 0.12f), Color.Transparent)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(LiveGreen.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.ContentCut, contentDescription = null, tint = LiveGreen, modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Instant Replay", fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = OnSurface)
                    Text("Capture highlights while you play", fontFamily = Inter, fontSize = 13.sp, color = OnSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Clip Duration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timer, contentDescription = null, tint = LiveGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("CLIP DURATION", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = LiveGreen, letterSpacing = 0.05.em)
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "${clipDuration} seconds",
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        color = OnSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "How far back to capture when you clip",
                        fontFamily = Inter,
                        fontSize = 13.sp,
                        color = OnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 120).forEach { seconds ->
                            FilterChip(
                                selected = clipDuration == seconds,
                                onClick = { onDurationChange(seconds) },
                                label = { Text("${seconds}s", fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LiveGreen,
                                    selectedLabelColor = Background,
                                    containerColor = SurfaceContainerHigh,
                                    labelColor = OnSurface
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = Color.Transparent,
                                    selectedBorderColor = Color.Transparent,
                                    enabled = true,
                                    selected = false
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Audio Toggle Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Mic, contentDescription = null, tint = LiveGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Include Microphone", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurface)
                            Text("Record your voice in clips", fontFamily = Inter, fontSize = 12.sp, color = OnSurfaceVariant)
                        }
                    }
                    Switch(
                        checked = includeMic,
                        onCheckedChange = onIncludeMicChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Background,
                            checkedTrackColor = LiveGreen,
                            uncheckedThumbColor = OnSurfaceVariant,
                            uncheckedTrackColor = SurfaceContainerHigh
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(16.dp))

            // How It Works
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("HOW IT WORKS", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = OnSurfaceVariant, letterSpacing = 0.05.em)
                    Spacer(modifier = Modifier.height(16.dp))
                    HowItWorksStep(
                        step = "1",
                        title = "Start Clipping",
                        description = "Tap the button below to start the recording buffer"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HowItWorksStep(
                        step = "2",
                        title = "Play Your Game",
                        description = "A floating button appears over your screen"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HowItWorksStep(
                        step = "3",
                        title = "Save the Moment",
                        description = "Tap the overlay button to save the last ${clipDuration}s"
                    )
                }
            }

            Spacer(modifier = Modifier.height(110.dp))
        }

        // Fixed START CLIPPING button
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val enabled = true

                Button(
                    onClick = onStartClipping,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(64.dp),
                    shape = RoundedCornerShape(32.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (enabled) LiveGreen else SurfaceContainerHigh,
                        contentColor = if (enabled) Background else OnSurfaceVariant
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                ) {
                    Icon(Icons.Outlined.ContentCut, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "START CLIPPING",
                        fontFamily = Manrope,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        letterSpacing = 0.05.em
                    )
                }
            }
        }
    }
}

@Composable
private fun HowItWorksStep(step: String, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(LiveGreen.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(step, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LiveGreen)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurface)
            Text(description, fontFamily = Inter, fontSize = 12.sp, color = OnSurfaceVariant)
        }
    }
}

// =====================================================================
//  REUSABLE COMPONENTS
// =====================================================================
@Composable
fun PlatformPill(name: String, color: Color, icon: ImageVector) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            name,
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = color
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamSettingsSheet(
    onDismissRequest: () -> Unit,
    currentQuality: String,
    onQualitySelected: (String) -> Unit,
    currentFps: Int,
    onFpsSelected: (Int) -> Unit,
    currentBitrateKbps: Int,
    onBitrateChanged: (Int) -> Unit,
    currentAspectRatio: String,
    onAspectRatioSelected: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceContainerLowest,
        dragHandle = { BottomSheetDefaults.DragHandle(color = OutlineVariant) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Stream Settings", fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = OnSurface)
            Spacer(modifier = Modifier.height(24.dp))

            Text("Resolution", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("720p", "1080p").forEach { res ->
                    FilterChip(
                        selected = currentQuality == res,
                        onClick = { onQualitySelected(res) },
                        label = { Text(res, fontFamily = Inter, fontWeight = FontWeight.Medium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary,
                            selectedLabelColor = OnPrimary,
                            containerColor = SurfaceContainerLow,
                            labelColor = OnSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = Color.Transparent,
                            selectedBorderColor = Color.Transparent,
                            enabled = true,
                            selected = false
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Framerate", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60).forEach { fps ->
                    FilterChip(
                        selected = currentFps == fps,
                        onClick = { onFpsSelected(fps) },
                        label = { Text("${fps}fps", fontFamily = Inter, fontWeight = FontWeight.Medium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary,
                            selectedLabelColor = OnPrimary,
                            containerColor = SurfaceContainerLow,
                            labelColor = OnSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = Color.Transparent,
                            selectedBorderColor = Color.Transparent,
                            enabled = true,
                            selected = false
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Video Bitrate (${currentBitrateKbps} kbps)", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = currentBitrateKbps.toFloat(),
                onValueChange = { onBitrateChanged(it.toInt()) },
                valueRange = 1000f..10000f,
                steps = 90,
                colors = SliderDefaults.colors(
                    thumbColor = Primary,
                    activeTrackColor = Primary,
                    inactiveTrackColor = SurfaceContainerHigh
                )
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text("Orientation", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("landscape" to "Landscape", "portrait" to "Portrait").forEach { (value, label) ->
                    FilterChip(
                        selected = currentAspectRatio == value,
                        onClick = { onAspectRatioSelected(value) },
                        label = { Text(label, fontFamily = Inter, fontWeight = FontWeight.Medium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary,
                            selectedLabelColor = OnPrimary,
                            containerColor = SurfaceContainerLow,
                            labelColor = OnSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = Color.Transparent,
                            selectedBorderColor = Color.Transparent,
                            enabled = true,
                            selected = false
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySelectionSheet(
    onDismissRequest: () -> Unit,
    categories: List<TwitchCategory>,
    onCategorySelected: (TwitchCategory) -> Unit,
    onSearch: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceContainerLowest,
        dragHandle = { BottomSheetDefaults.DragHandle(color = OutlineVariant) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 24.dp)
        ) {
            Text("Select Category", fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = OnSurface)
            Spacer(modifier = Modifier.height(16.dp))

            TextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearch(it)
                },
                placeholder = { Text("Search Games...", color = OnSurfaceVariant) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = OnSurfaceVariant) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, OutlineVariant, RoundedCornerShape(12.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceContainerLow,
                    unfocusedContainerColor = SurfaceContainerLow,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(categories) { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCategorySelected(category) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        coil.compose.AsyncImage(
                            model = coil.request.ImageRequest.Builder(LocalContext.current)
                                .data(category.boxArtUrl.replace("{width}", "138").replace("{height}", "190"))
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier
                                .width(40.dp)
                                .height(56.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = category.name,
                            fontFamily = Inter,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = OnSurface
                        )
                    }
                    HorizontalDivider(color = OutlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}
