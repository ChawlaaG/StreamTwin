package com.streamtwin.service

import android.app.Service
import android.content.Intent
import android.content.Context
import android.graphics.PixelFormat
import android.os.*
import android.os.PowerManager
import android.util.Log
import android.view.*
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.streamtwin.R
import com.streamtwin.data.local.StreamDataStore
import com.streamtwin.data.clip.ClipRepository
import com.streamtwin.data.repository.TwitchRepository
import com.streamtwin.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import androidx.core.content.ContextCompat
import javax.inject.Inject
import android.content.pm.ServiceInfo
import com.streamtwin.MainActivity
import com.streamtwin.ClipPermissionActivity

enum class OverlayMode { STREAM, CLIP }

class MyLifecycleOwner : SavedStateRegistryOwner, LifecycleOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun start() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

@AndroidEntryPoint
class FloatingOverlayService : Service() {

    @Inject
    lateinit var repository: TwitchRepository

    @Inject
    lateinit var dataStore: StreamDataStore

    @Inject
    lateinit var clipRepository: ClipRepository

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var params: WindowManager.LayoutParams
    private var isViewAdded = false
    // MutableState so Compose re-renders immediately when mode changes.
    // Initialized from companion savedMode so that START_STICKY restarts
    // (which deliver a null intent) preserve CLIP mode without a new intent.
    private val currentModeState = androidx.compose.runtime.mutableStateOf(savedMode)
    private var currentMode: OverlayMode
        get() = currentModeState.value
        set(value) { 
            currentModeState.value = value
            savedMode = value
            getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
                .edit().putString("saved_mode", value.name).apply()
        }
    private var preExpandY: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Lift isExpanded to service level for gesture coordination
    private val isExpandedState = androidx.compose.runtime.mutableStateOf(false)
    private var isExpanded: Boolean
        get() = isExpandedState.value
        set(value) { isExpandedState.value = value }

    // Interaction states lifted for robust event handling
    private val lastInteractionMs = androidx.compose.runtime.mutableStateOf(System.currentTimeMillis())
    private val showSavingFlash = androidx.compose.runtime.mutableStateOf(false)
    private var isProcessingClick = false

    // ── Drag-to-dismiss (Messenger-style) ──────────────────────────────────
    // A secondary full-screen transparent overlay that appears only while the
    // user is dragging the bubble. It shows an X circle at the bottom-center
    // of the screen. Releasing the bubble over the X dismisses the overlay.
    private var dismissView: android.widget.FrameLayout? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var isDismissViewShown = false
    // Tracks whether the bubble is currently hovering over the X zone
    private val isOverDismissZone = androidx.compose.runtime.mutableStateOf(false)

    private fun wakeUp() {
        lastInteractionMs.value = System.currentTimeMillis()
    }

    /** Shows the bottom-center X dismiss target. Called when drag starts. */
    private fun showDismissTarget() {
        if (isDismissViewShown) return
        try {
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val dm = resources.displayMetrics
            val zoneSizePx = (72 * dm.density).toInt()

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                zoneSizePx + (48 * dm.density).toInt(), // zone + padding
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 0
            }
            dismissParams = lp

            val ctx = android.view.ContextThemeWrapper(this, R.style.Theme_StreamTwin)
            val frame = android.widget.FrameLayout(ctx)
            val compDismiss = ComposeView(ctx).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val overZone by isOverDismissZone
                    DismissTargetView(isActive = overZone)
                }
            }
            frame.addView(compDismiss, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            dismissView = frame
            windowManager.addView(frame, lp)
            isDismissViewShown = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show dismiss target", e)
        }
    }

    /** Hides the X dismiss target. Called when drag ends without dismissing. */
    private fun hideDismissTarget() {
        if (!isDismissViewShown) return
        try {
            dismissView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        dismissView = null
        isDismissViewShown = false
        isOverDismissZone.value = false
    }

    /**
     * Returns true if the bubble's current position (params.x, params.y) is
     * over the dismiss X circle at the bottom-center of the screen.
     * The zone is a 96dp circle centered at (screenW/2, screenH - 80dp).
     */
    private fun isOverDismissTarget(): Boolean {
        val dm = resources.displayMetrics
        val bubbleCx = params.x + (56 * dm.density / 2)  // bubble center X
        val bubbleCy = params.y + (56 * dm.density / 2)  // bubble center Y
        val zoneCx = dm.widthPixels / 2f
        val zoneCy = dm.heightPixels - (80 * dm.density)
        val zoneR = 48 * dm.density  // 96dp diameter → 48dp radius
        val dx = bubbleCx - zoneCx
        val dy = bubbleCy - zoneCy
        return (dx * dx + dy * dy) <= (zoneR * zoneR)
    }

    private fun openClipStarter() {
        // Launch the transparent trampoline activity so the MediaProjection consent
        // dialog appears directly over BGMI without switching the user to our app.
        val intent = Intent(this, ClipPermissionActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open ClipPermissionActivity", e)
            Toast.makeText(this, "Open StreamTwin to start clip mode", Toast.LENGTH_SHORT).show()
        }
    }

    private val lifecycleOwner = MyLifecycleOwner()
    private val overlayScope = CoroutineScope(Dispatchers.Main + Job())
    // If user requests a clip while the buffer is still warming, queue it and auto-fire when ready
    private var queuedClipSave = false
    // Immediately reflects values selected on the main screen to avoid UI lag
    private val liveClipDuration = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val liveClipIncludeMic = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

    companion object {
        const val TAG = "FloatingOverlay"
        const val OVERLAY_NOTIF_ID = 1003
        /**
         * Persists the overlay mode across in-process START_STICKY restarts.
         * Reset to STREAM on explicit STOP_OVERLAY so the next session starts fresh.
         */
        @Volatile var savedMode: OverlayMode = OverlayMode.STREAM
    }

    override fun onCreate() {
        super.onCreate()
        
        // Restore mode from SharedPreferences to survive deep process kills
        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        val savedModeStr = prefs.getString("saved_mode", OverlayMode.STREAM.name)
        savedMode = if (savedModeStr == "CLIP") OverlayMode.CLIP else OverlayMode.STREAM
        currentModeState.value = savedMode
        
        startForegroundService()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlayWithRetry()
    }

    private fun setupOverlayWithRetry() {
        if (isViewAdded) return
        try {
            setupOverlay()
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isViewAdded) setupOverlay()
            }, 500)
        }
    }

    private fun startForegroundService() {
        val channelId = "OverlayServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Overlay Service",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("StreamTwin Overlay")
            .setContentText("Overlay is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(OVERLAY_NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(OVERLAY_NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            try { startForeground(OVERLAY_NOTIF_ID, notification) } catch (e2: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only update mode when the intent explicitly provides the OVERLAY_MODE extra.
        // This prevents the mode from resetting to STREAM on system restarts or
        // intents that don't carry the extra (e.g. START_STICKY re-delivery).
        val modeStr = intent?.getStringExtra("OVERLAY_MODE")
        if (modeStr != null) {
            currentMode = if (modeStr == "CLIP") OverlayMode.CLIP else OverlayMode.STREAM
        }
        // Immediately apply values from intent to skip DataStore propagation delay
        val clipDur = intent?.getIntExtra("CLIP_DURATION", 0) ?: 0
        if (clipDur > 0) liveClipDuration.value = clipDur
        if (intent?.hasExtra("CLIP_MUTE") == true) {
            liveClipIncludeMic.value = !intent.getBooleanExtra("CLIP_MUTE", false)
        }
        
        if (intent?.action == "STOP_OVERLAY") {
            savedMode = OverlayMode.STREAM  // Reset so the next clip session starts clean
            getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
                .edit().putString("saved_mode", OverlayMode.STREAM.name).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isViewAdded) setupOverlayWithRetry()

        // If the view was already added but may have been detached by the OS
        // (common when BGMI reclaims overlay layers under memory pressure),
        // proactively re-attach it.
        if (isViewAdded) ensureViewAttached()

        return START_STICKY
    }

    private fun setupOverlay() {
        if (isViewAdded) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            // NOTE: FLAG_KEEP_SCREEN_ON intentionally omitted — it keeps the GPU
            // display pipeline fully powered at all times and contributes to device
            // heating. The PARTIAL_WAKE_LOCK in StreamingService keeps the CPU alive.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay Permission Missing", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleOwner.start()

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isExpanded) {
                    val isLive = StreamStateManager.isLive.value
                    if (currentMode == OverlayMode.CLIP && !ClipModeService.isRunning && !clipRepository.isClipModeActive.value) {
                        Log.d(TAG, "Single tap: Opening clip starter because engine is inactive")
                        openClipStarter()
                    } else if (currentMode == OverlayMode.CLIP || isLive) {
                        isExpanded = true
                        Log.d(TAG, "Single tap: Expansion ($currentMode, isLive=$isLive)")
                    } else if (currentMode == OverlayMode.STREAM) {
                        Log.d(TAG, "Single tap: Starting stream directly")
                        startService(Intent(this@FloatingOverlayService, StreamingService::class.java).apply { 
                            action = "START_STREAM_NOW" 
                        })
                    }
                    wakeUp()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isExpanded) {
                    if (isProcessingClick) {
                        Log.d(TAG, "Double tap ignored: Processing previous click")
                        return true
                    }
                    isProcessingClick = true
                    Log.d(TAG, "Double tap detected: Saving clip")
                    wakeUp()
                    if (currentMode == OverlayMode.CLIP) {
                        showSavingFlash.value = true
                        overlayScope.launch {
                            delay(1000)
                            showSavingFlash.value = false
                        }
                        ensureViewAttached()
                        val intent = Intent(this@FloatingOverlayService, ClipModeService::class.java).apply {
                            action = ClipModeService.ACTION_SAVE_CLIP
                        }
                        ContextCompat.startForegroundService(this@FloatingOverlayService, intent)
                    } else {
                        // Forward to StreamingService for clip creation (Twitch/YouTube)
                        startService(Intent(this@FloatingOverlayService, StreamingService::class.java).apply { 
                            action = "CREATE_CLIP" 
                        })
                    }
                    
                    // Reset click lock after 500ms
                    Handler(Looper.getMainLooper()).postDelayed({
                        isProcessingClick = false
                    }, 500)
                    
                    // Enforce that overlay is still attached after action
                    ensureViewAttached()
                }
                return true
            }
        })

        val contextThemeWrapper = ContextThemeWrapper(this, R.style.Theme_StreamTwin)
        composeView = ComposeView(contextThemeWrapper).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            var downX = 0f
            var downY = 0f
            var isDragging = false

            setOnTouchListener { v, event ->
                // Always feed detector first so it can track gesture timing/sequence correctly
                gestureDetector.onTouchEvent(event)

                // If dashboard is expanded, let Compose handle all internal touches (sliders, etc.)
                if (isExpanded) return@setOnTouchListener false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        isDragging = false
                        Log.d(TAG, "Touch DOWN | dragging=false | attached=${parent != null}")
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        val absDx = Math.abs(dx)
                        val absDy = Math.abs(dy)
                        if (absDx > 10 || absDy > 10) {
                            if (!isDragging) {
                                Log.d(TAG, "Drag threshold reached")
                                // ── Dismiss zone: only show when user is dragging
                                // significantly DOWNWARD toward the bottom of the screen.
                                // This prevents it from popping up during normal relocation
                                // drags (left/right/up) which is the common case.
                                // Threshold: moved at least 120px net downward (dy > 120)
                                // AND the bubble is already in the bottom half of the screen.
                                val dm = resources.displayMetrics
                                val inBottomHalf = params.y > dm.heightPixels / 2
                                val draggingDown = dy > 120
                                if (draggingDown || inBottomHalf) {
                                    showDismissTarget()
                                }
                            }
                            isDragging = true
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            downX = event.rawX
                            downY = event.rawY
                            wakeUp()
                            // Update dismiss zone highlight
                            isOverDismissZone.value = isDismissViewShown && isOverDismissTarget()
                            try {
                                if (parent != null) windowManager.updateViewLayout(v, params)
                            } catch (e: Exception) {
                                Log.e(TAG, "Layout update failed during drag", e)
                                ensureViewAttached()
                            }
                        }
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP -> {
                        Log.d(TAG, "Touch UP | dragging=$isDragging | attached=${parent != null}")
                        if (isDragging && isDismissViewShown && isOverDismissTarget()) {
                            // User dragged bubble onto the X — dismiss the overlay
                            Log.d(TAG, "Released over dismiss zone — stopping overlay service")
                            hideDismissTarget()
                            // Stop clip mode too if it's running
                            if (ClipModeService.isRunning) {
                                try {
                                    ContextCompat.startForegroundService(
                                        this@FloatingOverlayService,
                                        Intent(this@FloatingOverlayService, ClipModeService::class.java).apply {
                                            action = ClipModeService.ACTION_STOP
                                        }
                                    )
                                } catch (_: Exception) {}
                            }
                            stopSelf()
                        } else if (isDragging) {
                            hideDismissTarget()
                        }
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // Touch cancelled (e.g. by another window) — always hide zone
                        hideDismissTarget()
                        return@setOnTouchListener true
                    }
                }
                false
            }

            setContent {
                StreamTwinTheme {
                    // Observe reactive state
                    val mode by currentModeState
                    val expanded by isExpandedState
                    val lastInteraction by lastInteractionMs
                    val savingFlash by showSavingFlash
                    val clipDurationFromStore by dataStore.clipDurationFlow.collectAsState(initial = 60)
                    val clipDurationLive by liveClipDuration.collectAsState()
                    val clipDuration = if (clipDurationLive > 0) clipDurationLive else clipDurationFromStore
                    
                    val clipIncludeMicFromStore by dataStore.clipIncludeMicFlow.collectAsState(initial = true)
                    val liveIncludeMic by liveClipIncludeMic.collectAsState()
                    val clipIncludeMic = liveIncludeMic ?: clipIncludeMicFromStore

                    val overlayClipActive by clipRepository.isClipModeActive.collectAsState(initial = false)
                    val overlayClipReady by clipRepository.clipReady.collectAsState(initial = false)

                    LiveOverlayPanel(
                        isExpanded = expanded,
                        lastInteractionMs = lastInteraction,
                        savingFlash = savingFlash,
                        onDrag = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            try { windowManager.updateViewLayout(this, params) } catch (e: Exception) {}
                        },
                        onFocusChange = { focused ->
                            if (focused) {
                                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                            } else {
                                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            }
                            try { windowManager.updateViewLayout(this, params) } catch (e: Exception) {}
                        },
                        onExpandToggled = { expanded ->
                            isExpanded = expanded
                            if (expanded) {
                                preExpandY = params.y
                                val metrics = resources.displayMetrics
                                val panelHeightPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 440f, metrics)
                                if (params.y + panelHeightPx > metrics.heightPixels) {
                                    params.y = (metrics.heightPixels - panelHeightPx).toInt()
                                    try { windowManager.updateViewLayout(this, params) } catch(e: Exception) {}
                                }
                            } else {
                                preExpandY?.let { oldY ->
                                    params.y = oldY
                                    try { windowManager.updateViewLayout(this, params) } catch(e: Exception) {}
                                    preExpandY = null
                                }
                            }
                        },
                        onStartStream = {
                            // CRITICAL GUARD: Never forward streaming commands while in clip mode
                            if (currentMode != OverlayMode.CLIP) {
                                startService(Intent(this@FloatingOverlayService, StreamingService::class.java).apply { action = "START_STREAM_NOW" })
                            } else {
                                Log.d(TAG, "onStartStream ignored: currently in CLIP mode")
                            }
                        },
                        onStopStream = {
                            // CRITICAL GUARD: Never forward streaming commands while in clip mode
                            if (currentMode != OverlayMode.CLIP) {
                                startService(Intent(this@FloatingOverlayService, StreamingService::class.java).apply { action = "STOP_STREAM" })
                            } else {
                                Log.d(TAG, "onStopStream ignored: currently in CLIP mode")
                            }
                        },
                        onAction = { action, value ->
                            if (action == "SAVE_CLIP") {
                                showSavingFlash.value = true
                                overlayScope.launch {
                                    delay(1000)
                                    showSavingFlash.value = false
                                }
                                // At this point clipActive was already verified by onDoubleTap.
                                // Always forward the intent to ClipModeService — it handles
                                // service itself when it wasn't running yet (clipRepository.queuedSave).
                                ensureViewAttached()
                                val intent = Intent(this@FloatingOverlayService, ClipModeService::class.java).apply {
                                    this.action = ClipModeService.ACTION_SAVE_CLIP
                                }
                                ContextCompat.startForegroundService(this@FloatingOverlayService, intent)
                            } else if (action == "STOP_CLIP_MODE") {
                                val intent = Intent(this@FloatingOverlayService, ClipModeService::class.java).apply {
                                    this.action = ClipModeService.ACTION_STOP
                                }
                                ContextCompat.startForegroundService(this@FloatingOverlayService, intent)
                            } else if (action == "SET_CLIP_MUTE") {
                                val isMuted = value == 1f
                                val intent = Intent(this@FloatingOverlayService, ClipModeService::class.java).apply {
                                    this.action = ClipModeService.ACTION_SET_MUTE
                                    putExtra(ClipModeService.EXTRA_MUTE, isMuted)
                                }
                                ContextCompat.startForegroundService(this@FloatingOverlayService, intent)
                            } else {
                                val intent = Intent(this@FloatingOverlayService, StreamingService::class.java).apply { 
                                    this.action = action 
                                    if (value != null) {
                                        putExtra("VOLUME", value)
                                    }
                                }
                                startService(intent)
                            }
                        },
                        clipActive = overlayClipActive,
                        clipReady = overlayClipReady,
                        mode = mode,
                        clipDuration = clipDuration,
                        onSetClipDuration = { dur ->
                            liveClipDuration.value = dur  // instant UI update
                            overlayScope.launch { dataStore.saveClipDuration(dur) }
                            ContextCompat.startForegroundService(this@FloatingOverlayService, Intent(this@FloatingOverlayService, ClipModeService::class.java).apply {
                                action = ClipModeService.ACTION_UPDATE_DURATION
                                putExtra(ClipModeService.EXTRA_CLIP_DURATION, dur)
                            })
                        },
                        clipIncludeMic = clipIncludeMic,
                        onSetClipIncludeMic = { include ->
                            liveClipIncludeMic.value = include
                            overlayScope.launch { dataStore.saveClipIncludeMic(include) }
                            ContextCompat.startForegroundService(this@FloatingOverlayService, Intent(this@FloatingOverlayService, ClipModeService::class.java).apply {
                                action = ClipModeService.ACTION_SET_MUTE
                                putExtra(ClipModeService.EXTRA_MUTE, !include)
                            })
                        }
                    )
                }
            }
        }

        windowManager.addView(composeView, params)
        isViewAdded = true

        // ── Overlay heartbeat ─────────────────────────────────────────────────
        // Games like BGMI (Unreal Engine) can cause the overlay window to detach
        // under memory pressure even though the service is still alive.
        // Proactively re-attach every 5 seconds so the bubble never disappears.
        overlayScope.launch {
            while (true) {
                delay(5_000)
                try {
                    ensureViewAttached()
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat ensureViewAttached failed", e)
                }
            }
        }

        // Monitor clipReady and fire any queued save when the buffer becomes ready.
        // This covers both saves queued by the overlay (queuedClipSave) and by the
        // service itself when it wasn't running yet (clipRepository.queuedSave).
        overlayScope.launch {
            try {
                clipRepository.clipReady.collect { ready ->
                    if (ready && (queuedClipSave || clipRepository.queuedSave.value)) {
                        queuedClipSave = false
                        clipRepository.setQueuedSave(false)
                        val intent = Intent(this@FloatingOverlayService, ClipModeService::class.java).apply {
                            this.action = ClipModeService.ACTION_SAVE_CLIP
                        }
                        ContextCompat.startForegroundService(this@FloatingOverlayService, intent)
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(this@FloatingOverlayService, "✂️ Saving queued clip…", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) { /* best-effort */ }
        }

        // Force a re-layout after a short delay to ensure visibility on some devices
        Handler(Looper.getMainLooper()).postDelayed({
            if (isViewAdded) {
                try {
                    windowManager.updateViewLayout(composeView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Error forcing layout update", e)
                }
            }
        }, 300)

        // Acquire a screen-bright wake lock so the screen doesn't dim/lock
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            // PARTIAL_WAKE_LOCK: keeps the CPU alive so recording/streaming continues
            // when the screen dims, but lets Android manage display brightness normally.
            //
            // SCREEN_BRIGHT_WAKE_LOCK (old): forced the display to stay at maximum
            // brightness at all times — the display backlight is the biggest single
            // contributor to battery drain and SoC heat on mobile devices.
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StreamTwin:OverlayWakeLock"
            )
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // up to 24 h
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun ensureViewAttached() {
        if (!isViewAdded || composeView.parent == null) {
            try {
                if (composeView.parent != null) windowManager.removeView(composeView)
                windowManager.addView(composeView, params)
                isViewAdded = true
                Log.d(TAG, "Overlay view re-attached (was detached or missing)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-attach overlay view", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove the main overlay view
        if (isViewAdded) {
            try {
                Log.d(TAG, "Cleaning up overlay in onDestroy")
                if (composeView.parent != null) {
                    windowManager.removeView(composeView)
                }
                isViewAdded = false
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view in onDestroy", e)
            }
        }
        // Remove the dismiss X view if it's still showing (e.g. service killed mid-drag)
        hideDismissTarget()
        releaseWakeLock()
        lifecycleOwner.stop()
        overlayScope.cancel()
    }
}

@Composable
fun LiveOverlayPanel(
    isExpanded: Boolean,
    lastInteractionMs: Long,
    savingFlash: Boolean,
    onDrag: (Float, Float) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onExpandToggled: (Boolean) -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onAction: (String, Float?) -> Unit,
    // clipActive: service has started recording
    clipActive: Boolean = false,
    // clipReady: buffer has keyframes and can produce a clip
    clipReady: Boolean = false,
    mode: OverlayMode = OverlayMode.STREAM,
    clipDuration: Int = 60,
    onSetClipDuration: (Int) -> Unit = {},
    clipIncludeMic: Boolean = true,
    onSetClipIncludeMic: (Boolean) -> Unit = {}
) {
    val isLive by StreamStateManager.isLive.collectAsState()
    val streamTime by StreamStateManager.streamDuration.collectAsState()
    
    val messages = remember { androidx.compose.runtime.mutableStateListOf<ChatMessage>() }
    LaunchedEffect(Unit) {
        ChatStateManager.messages.collect { msg ->
            messages.add(msg)
            if (messages.size > 50) messages.removeAt(0)
        }
    }

    // Wrap everything in a Box so the two AnimatedVisibility blocks
    // overlap each other (bubble <-> panel) during transitions
    Box(contentAlignment = Alignment.Center) {
    
    var streamMicMuted by remember { mutableStateOf(false) }
    val effectiveMute = if (mode == OverlayMode.CLIP) !clipIncludeMic else streamMicMuted
    
    var isPrivacyMode by remember { mutableStateOf(false) }
    var micVolume by remember { mutableStateOf(1f) }
    var gameVolume by remember { mutableStateOf(1f) }

    // ---- Idle fade/shrink logic using lifted state ----
    var isIdle by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        while (true) {
            // 2000ms resolution is more than sufficient for a 5-second idle timeout.
            // 500ms (old) polled 4x more often than needed for no visible benefit.
            delay(2000)
            isIdle = !isExpanded && (System.currentTimeMillis() - lastInteractionMs > 5_000L)
        }
    }

    // Auto-collapse expanded dashboard after 3s of inactivity
    LaunchedEffect(isExpanded, lastInteractionMs) {
        if (isExpanded) {
            delay(3_000L)
            if (System.currentTimeMillis() - lastInteractionMs >= 3_000L) {
                onExpandToggled(false)
            }
        }
    }

    val idleAlpha by animateFloatAsState(
        targetValue = if (isIdle) 0.35f else 1.0f,
        animationSpec = tween(800),
        label = "idleAlpha"
    )
    val idleScale by animateFloatAsState(
        targetValue = if (isIdle) 0.75f else 1.0f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "idleScale"
    )
    // ---------------------------------------------------------------------------

    val formatTime = { time: Long ->
        val h = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(time)
        val m = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(time) % 60
        val s = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(time) % 60
        if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val lastDoubleTapTime = 0L // No longer needed in compose side

    LaunchedEffect(isExpanded) {
        onFocusChange(isExpanded)
    }
    
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && isExpanded) {
            listState.scrollToItem(messages.size - 1) // snap, not animated — avoids jank
        }
    }

    // ---- Bubble (collapsed state) ----
    AnimatedVisibility(
        visible = !isExpanded,
        enter = scaleIn(tween(150)) + fadeIn(tween(150)),
        exit  = scaleOut(tween(120)) + fadeOut(tween(120))
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .alpha(idleAlpha)
                .scale(idleScale)
                .background(Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (mode == OverlayMode.CLIP && !clipReady) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(pulseScale)
                        .border(2.dp, Primary.copy(alpha = 0.3f), CircleShape)
                )
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (savingFlash) Color.White else SurfaceContainerHighest.copy(alpha = 0.8f))
                    .border(1.dp, OutlineVariant.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (mode == OverlayMode.CLIP) {
                    Icon(Icons.Filled.ContentCut, contentDescription = null, tint = Primary, modifier = Modifier.size(24.dp))
                } else if (isLive) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.size(8.dp).background(LiveGreen, CircleShape))
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(formatTime(streamTime), fontFamily = JetBrainsMono, fontSize = 10.sp, color = OnSurface, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Go Live", tint = Primary, modifier = Modifier.size(24.dp))
                }
            }
            // Status indicator: top-right small dot showing buffer readiness
            if (mode == OverlayMode.CLIP) {
                val statusColor = when {
                    clipReady -> LiveGreen
                    clipActive -> StandbyAmber
                    else -> OnSurface.copy(alpha = 0.3f)
                }
                Box(modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(12.dp).background(statusColor, CircleShape).border(1.dp, OutlineVariant.copy(alpha = 0.5f), CircleShape))
            }
        }
    }

    // ---- Expanded Dashboard ----
    AnimatedVisibility(
        visible = isExpanded,
        enter = slideInVertically(tween(180, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(180)),
        exit  = slideOutVertically(tween(140)) { it / 4 } + fadeOut(tween(140))
    ) {
        Box(
            modifier = Modifier
                .width(280.dp)
                .height(420.dp)
                .background(SurfaceContainerHighest.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
                .border(1.dp, OutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Area
                Row(
                    modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (mode == OverlayMode.CLIP) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(if (clipReady) LiveGreen else Primary, CircleShape))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (clipReady) "READY" else "BUFFERING ${clipDuration}s", color = if (clipReady) LiveGreen else Primary, fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 0.1f.em)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(10.dp).background(LiveGreen, CircleShape))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("LIVE", fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = LiveGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(formatTime(streamTime), fontFamily = JetBrainsMono, fontSize = 14.sp, color = OnSurface)
                        }
                    }
                    
                    IconButton(onClick = { onExpandToggled(false) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Collapse", tint = OnSurfaceVariant)
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = OutlineVariant.copy(alpha=0.3f))
                Spacer(modifier = Modifier.height(8.dp))

                if (mode == OverlayMode.CLIP) {
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Text("CLIP DURATION", color = OnSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf(15, 30, 60, 120).forEach { dur ->
                                val selected = clipDuration == dur
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) Primary else SurfaceContainerLow)
                                        .clickable { onSetClipDuration(dur) }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${dur}s", color = if (selected) OnPrimary else OnSurface, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.ContentCut, contentDescription = null, tint = Primary, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("BUFFERING", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                } else {
                    // Unified Chat Area
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(messages) { msg ->
                                val color = when(msg.platform) {
                                    "TWITCH" -> TwitchPurple
                                    "YOUTUBE" -> YouTubeRed
                                    "KICK" -> KickGreen
                                    else -> Primary
                                }
                                Row(modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()) {
                                    Text(msg.sender, color = color, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(msg.message, color = OnSurface, fontFamily = Inter, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    // Volume Controls
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, contentDescription = "Mic Vol", tint = OnSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(
                            value = micVolume,
                            onValueChange = { micVolume = it; onAction("SET_MIC_VOLUME", it) },
                            modifier = Modifier.weight(1f).height(24.dp),
                            colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "Game Vol", tint = OnSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(
                            value = gameVolume,
                            onValueChange = { gameVolume = it; onAction("SET_INTERNAL_VOLUME", it) },
                            modifier = Modifier.weight(1f).height(24.dp),
                            colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = OutlineVariant.copy(alpha=0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                // Footer Actions
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (mode == OverlayMode.CLIP) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                            onSetClipIncludeMic(!clipIncludeMic)
                        }) {
                            Box(modifier = Modifier.size(44.dp).background(if (effectiveMute) ErrorRed.copy(alpha=0.2f) else SurfaceContainerLow, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(if (effectiveMute) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Mic", tint = if (effectiveMute) ErrorRed else OnSurface)
                            }
                            Text(if (effectiveMute) "Unmute" else "Mute", color = OnSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                         Button(
                            onClick = { 
                                onAction("SAVE_CLIP", null)
                            },
                            enabled = clipReady,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary)
                        ) {
                            Icon(Icons.Outlined.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("CLIP IT", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        IconButton(onClick = { 
                            onAction("STOP_CLIP_MODE", null)
                            onExpandToggled(false)
                        }, modifier = Modifier.size(48.dp).background(SurfaceContainerLow, CircleShape)) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = ErrorRed)
                        }
                    } else {
                        // Mic Toggle
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                            streamMicMuted = !streamMicMuted
                            onAction(if (streamMicMuted) "MUTE_AUDIO" else "UNMUTE_AUDIO", null)
                        }) {
                            Box(modifier = Modifier.size(44.dp).background(if (streamMicMuted) ErrorRed.copy(alpha=0.2f) else SurfaceContainerLow, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(if (streamMicMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Mic", tint = if (streamMicMuted) ErrorRed else OnSurface)
                            }
                            Text(if (streamMicMuted) "Unmute" else "Mute", color = OnSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }

                        // Privacy Toggle
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                            isPrivacyMode = !isPrivacyMode
                            onAction(if (isPrivacyMode) "ENABLE_PRIVACY" else "DISABLE_PRIVACY", null)
                        }) {
                            Box(modifier = Modifier.size(44.dp).background(if (isPrivacyMode) StandbyAmber.copy(alpha=0.2f) else SurfaceContainerLow, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(if (isPrivacyMode) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Privacy", tint = if (isPrivacyMode) StandbyAmber else OnSurface)
                            }
                            Text("Privacy", color = OnSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }

                        // Stop Button
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { 
                            onExpandToggled(false)
                            onStopStream() 
                        }) {
                            Box(modifier = Modifier.size(44.dp).background(ErrorRed.copy(alpha=0.2f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = ErrorRed)
                            }
                            Text("End", color = OnSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
    } // end Box
}

/**
 * The "drop to close" target shown at the bottom-center of the screen while
 * the floating bubble is being dragged. Inspired by Messenger / YouTube PiP.
 *
 * @param isActive  true when the bubble is currently hovering over this target.
 *                  Changes the appearance from subtle to "danger red" to signal
 *                  that releasing here will close the overlay.
 */
@Composable
fun DismissTargetView(isActive: Boolean) {
    val animScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isActive) 1.25f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "dismissScale"
    )
    val bgAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isActive) 0.95f else 0.65f,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "dismissAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Scrim gradient to make the X zone visible against any background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color.Transparent,
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = if (isActive) 0.55f else 0.30f)
                        )
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 28.dp)
        ) {
            // Label
            Text(
                text = if (isActive) "Release to close" else "Drag here to close",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = bgAlpha),
                fontSize = 12.sp,
                fontFamily = Inter,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            // X circle
            Box(
                modifier = Modifier
                    .size((64 * animScale).dp)
                    .background(
                        color = if (isActive)
                            androidx.compose.ui.graphics.Color(0xFFE53935).copy(alpha = bgAlpha)
                        else
                            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f),
                        shape = CircleShape
                    )
                    .border(
                        width = if (isActive) 2.dp else 1.dp,
                        color = if (isActive)
                            androidx.compose.ui.graphics.Color(0xFFFF5252)
                        else
                            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.45f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close overlay",
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(if (isActive) 30.dp else 24.dp)
                )
            }
        }
    }
}
