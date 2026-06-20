package com.streamtwin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.content.ContentValues
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.MediaStore
import java.io.FileInputStream
import android.os.Binder
import android.os.StatFs
import android.os.Vibrator
import android.os.VibrationEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.streamtwin.R
import com.streamtwin.data.clip.CircularBufferSink
import com.streamtwin.data.clip.ClipRepository
import com.streamtwin.data.local.StreamDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import javax.inject.Inject

@AndroidEntryPoint
class ClipModeService : Service() {

    @Inject lateinit var clipRepository: ClipRepository
    @Inject lateinit var streamDataStore: StreamDataStore
    
    private val binder = LocalBinder()
    private var bufferSink: CircularBufferSink? = null
    private val isSaving = AtomicBoolean(false)
    // Prevents double-calls to stopClipMode() from onDestroy + MediaProjection.onStop callback
    private val isStopping = AtomicBoolean(false)
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var automaticGainControl: android.media.audiofx.AutomaticGainControl? = null
    
    private var currentVideoWidth = 0
    private var currentVideoHeight = 0
    private var videoLoopJob: kotlinx.coroutines.Job? = null
    private var isMicMuted = false
    private var isInternalAudio = false
    private var forceFixed1080p: Boolean = false
    @Volatile private var recordingStartNs = 0L
    private var lastIsLandscape: Boolean? = null
    private var lockedLandscape: Boolean? = null
    private var pendingOrientation: Boolean? = null
    private var orientationChangeCount = 0
    private var lastOrientationResetMs = 0L
    @Volatile private var audioTotalFrames = 0L // Kept as counter for debug only
    private var lastAudioPtsUs = 0L
    private var pendingClipRequest = false
    private var lastClipTime = 0L
    private var lastThermalAdjust = 0L
    private var lastKeyframeRequest = 0L
    private var initialBitrate = 0
    private var currentBitrate = 0
    private var currentThermalFps = 60  // tracks the current thermally-adjusted FPS target

    // Counts consecutive negative reads from micAudioRecord (e.g. when BGMI voice chat
    // briefly takes exclusive mic ownership). Used only for logging throttle — no action
    // is taken; the game releases the mic between push-to-talk presses.
    @Volatile private var micConsecutiveErrors = 0

    // ── Display Listener for Dynamic Adaptation ──────────────────────────────────
    private var displayManager: android.hardware.display.DisplayManager? = null
    private var orientationMonitorJob: kotlinx.coroutines.Job? = null
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                checkOrientationAndRestartEncoder()
            }
        }
    }

    private fun getContentSize(): Pair<Int, Int> {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)

        // On Android 11+, calling WindowManager.currentWindowMetrics from a Service
        // context returns the physical device size in its natural orientation (portrait).
        // It does NOT reflect the current rotation of the foreground app.
        // The only robust way to get rotation-aware dimensions from a background service
        // is to read the physical screen size, then read the Display rotation, and swap
        // width/height ourselves.
        val rotation = display?.rotation ?: android.view.Surface.ROTATION_0
        val isLandscapeRotation = rotation == android.view.Surface.ROTATION_90 ||
                                  rotation == android.view.Surface.ROTATION_270

        var physicalW = 0
        var physicalH = 0

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val mode = display?.mode
            if (mode != null) {
                physicalW = mode.physicalWidth
                physicalH = mode.physicalHeight
            }
        }
        
        if (physicalW == 0 || physicalH == 0) {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)
            physicalW = metrics.widthPixels
            physicalH = metrics.heightPixels
        }

        if (physicalW == 0 || physicalH == 0) {
            // Ultimate fallback
            val metrics = android.content.res.Resources.getSystem().displayMetrics
            physicalW = metrics.widthPixels
            physicalH = metrics.heightPixels
        }

        val rawW = if (isLandscapeRotation) maxOf(physicalW, physicalH) else minOf(physicalW, physicalH)
        val rawH = if (isLandscapeRotation) minOf(physicalW, physicalH) else maxOf(physicalW, physicalH)

        Log.d("DISPLAY", "captureSize=${rawW}x${rawH} (rotation=$rotation, physical=${physicalW}x${physicalH})")
        return rawW to rawH
    }

    private fun stopAudioCaptureSystem() {
        try { noiseSuppressor?.release();         noiseSuppressor = null }       catch (_: Exception) {}
        try { echoCanceler?.release();            echoCanceler = null }          catch (_: Exception) {}
        try { automaticGainControl?.release();    automaticGainControl = null }  catch (_: Exception) {}

        try { internalAudioRecord?.stop() } catch (_: Exception) {}
        try { micAudioRecord?.stop() }      catch (_: Exception) {}

        audioCaptureThread?.interrupt()
        audioEncodeThread?.interrupt()
        
        try { audioCaptureThread?.join(500) } catch (_: Exception) {}
        try { audioEncodeThread?.join(500) } catch (_: Exception) {}

        audioCaptureThread = null
        audioEncodeThread = null

        try { internalAudioRecord?.release(); internalAudioRecord = null } catch (_: Exception) {}
        try { micAudioRecord?.release();      micAudioRecord = null }      catch (_: Exception) {}

        try { audioEncoder?.stop()   } catch (_: Exception) {}
        try { audioEncoder?.release(); audioEncoder = null } catch (_: Exception) {}
    }



    // Orientation restart: 5-second cooldown prevents rapid oscillation during rotation animation
    private var lastEncoderRestartMs = 0L
    // PTS cutoff: saveClip() filters to only use frames after the latest orientation restart.
    // Keeps the PTS timeline continuous (no reset) and avoids mixed-dimension frames in the muxer.
    @Volatile private var lastRestartPtsUs = 0L

    private fun checkOrientationAndRestartEncoder() {
        val (rawW, rawH) = getContentSize()
        val newIsLandscape = rawW > rawH
        val nowMs = System.currentTimeMillis()

        if (lastIsLandscape != null && newIsLandscape != lastIsLandscape && nowMs - lastEncoderRestartMs > 5000) {
            Log.d("ClipModeService", "Orientation: $lastIsLandscape→$newIsLandscape, restarting encoder (continuous PTS)...")
            lastIsLandscape = newIsLandscape
            lastEncoderRestartMs = nowMs

            serviceScope.launch {
                videoLoopJob?.cancel()
                videoLoopJob?.join()

                // CRITICAL: Record the PTS cutoff BEFORE releasing the old encoder.
                // saveClip() will only use frames with PTS > this value, so old-orientation
                // portrait frames from the launcher are excluded seamlessly.
                lastRestartPtsUs = lastVideoPtsUs
                Log.d("ClipModeService", "PTS cutoff for orientation switch: ${lastRestartPtsUs / 1_000_000}s")

                try { virtualDisplay?.release() } catch (e: Exception) {}
                try { videoEncoder?.stop(); videoEncoder?.release() } catch (e: Exception) {}
                virtualDisplay = null
                videoEncoder = null

                stopAudioCaptureSystem()

                // DO NOT reset audioTotalFrames or recordingStartNs!
                // New encoder frames will get PTS that continues monotonically from where they
                // left off, perfectly syncing with the continuous audio PTS. This avoids A/V desync.

                try {
                    setupVideoEncoder()
                    Log.d("ClipModeService", "Orientation restart done. Buffer intact, save will use frames > ${lastRestartPtsUs / 1_000_000}s")
                } catch (e: Exception) {
                    Log.e("ClipModeService", "setupVideoEncoder() failed on orientation flip — using old config", e)
                }
                setupAudioEncoder()
                videoLoopJob = serviceScope.launch { runVideoEncoderLoop() }
                startAudioCaptureThreads()
            }
        }
    }


    // ── Back-tap clip trigger ─────────────────────────────────────────────────
    @Volatile private var backTapEnabled = false
    private var sensorManager: SensorManager? = null
    private var backTapListener: SensorEventListener? = null
    // Ring buffer of recent tap timestamps (epoch ms)
    private val backTapTimes = ArrayDeque<Long>(4)
    // False-positive guard: if 3+ clips saved in <10s via back-tap, pause sensor
    private val backTapSaveTimes = ArrayDeque<Long>(4)
    @Volatile private var backTapPausedUntil = 0L

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService() = this@ClipModeService
    }

    override fun onBind(intent: Intent) = binder

    override fun onCreate() {
        super.onCreate()
        // The companion-object isRunning flag can be left as `true` if the process was
        // killed mid-session (service crash, OOM, etc.). Reset it here so a fresh service
        // instance can always start a new clip session.
        isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("CLIP_INIT", "onStartCommand: action=$action isRunning=$isRunning")

        // If the system started this service via startForegroundService() for an
        // action that doesn't initialize the projection (e.g., SAVE_CLIP), we
        // must call startForeground() quickly to avoid RemoteServiceException.
        if (!isRunning && action != null && action != ACTION_START) {
            try {
                // Use a lightweight notification until the real foreground
                // state is established by ACTION_START.
                startForeground(CLIP_NOTIF_ID, buildClipNotification())
            } catch (e: Exception) { /* best-effort */ }
        }

        when (action) {
            ACTION_START -> {
                val projectionData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
                val rawDuration = intent.getIntExtra(EXTRA_CLIP_DURATION, 60)
                val clipDuration = minOf(rawDuration, 60)
                isMicMuted = intent.getBooleanExtra(EXTRA_MUTE, false)
                // Read optional fixed-1080p capture flag
                forceFixed1080p = intent.getBooleanExtra(EXTRA_FORCE_1080P, false)
                Log.d("ClipModeService", "Force fixed 1080p = $forceFixed1080p")
                if (projectionData != null) {
                    startClipMode(resultCode, projectionData, clipDuration)
                }
            }
            ACTION_SAVE_CLIP -> {
                // If the capture engine isn't running yet, queue the save and
                // return quickly. This avoids leaving the service running
                // unnecessarily after satisfying startForeground requirements.
                if (!isRunning) {
                    serviceScope.launch(Dispatchers.Main) {
                        try { clipRepository.setQueuedSave(true) } catch (e: Exception) {}
                    }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, "⏳ Queued — will save when engine starts", Toast.LENGTH_SHORT).show()
                    }
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
                    stopSelf()
                } else {
                    saveClip()
                }
            }
            ACTION_STOP -> stopClipMode(isExplicitStop = true)
            ACTION_SET_MUTE -> {
                isMicMuted = intent.getBooleanExtra(EXTRA_MUTE, false)
            }
            ACTION_UPDATE_DURATION -> {
                val rawDuration = intent.getIntExtra(EXTRA_CLIP_DURATION, 60)
                val newDuration = minOf(rawDuration, 60)
                bufferSink?.durationSeconds = newDuration
                Log.d("ClipModeService", "Buffer duration updated to ${newDuration}s")
            }
            null -> {
                // START_STICKY restart with null intent — we cannot reconstruct the MediaProjection
                // session so clean up and let the overlay know the engine isn't running.
                if (!isRunning) {
                    Log.w("ClipModeService", "START_STICKY restart with no session — stopping cleanly")
                    try { clipRepository.setClipModeActive(false) } catch (e: Exception) {}
                    try { clipRepository.setClipReady(false) } catch (e: Exception) {}
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startClipMode(resultCode: Int, data: Intent, clipDuration: Int) {
        Log.d("CLIP_INIT", "startClipMode ENTERED: isRunning=$isRunning resultCode=$resultCode clipDuration=$clipDuration")
        if (isRunning) return
        isRunning = true
        Log.d("CLIP_INIT", "startClipMode: isRunning set to true, creating bufferSink")
        // Notify repository that clip mode is now active so UI/overlay can reflect true service state
        try { clipRepository.setClipModeActive(true) } catch (e: Exception) { /* best-effort */ }
        bufferSink = CircularBufferSink(this, clipDuration)

        // Read back-tap preference and arm the detector if enabled
        serviceScope.launch {
            try {
                streamDataStore.backTapEnabledFlow.collect { enabled ->
                    Log.d("BACK_TAP", "Preference changed: backTapEnabled=$enabled")
                    backTapEnabled = enabled
                    if (enabled && isRunning) startBackTapDetector()
                    else stopBackTapDetector()
                }
            } catch (e: Exception) {
                Log.e("BACK_TAP", "Failed to observe backTapEnabled preference", e)
            }
        }

        createNotificationChannel()

        // On Android Q+, startForeground() MUST specify the foreground service type(s) declared
        // in the manifest. Including MICROPHONE type is only allowed when RECORD_AUDIO is granted;
        // otherwise Android throws SecurityException and kills the service.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(CLIP_NOTIF_ID, buildClipNotification(), fgsType)
        } else {
            startForeground(CLIP_NOTIF_ID, buildClipNotification())
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // Register callback immediately after obtaining MediaProjection — required on Android 14+
        // before createVirtualDisplay() is called.
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w("ClipModeService", "MediaProjection stopped externally")
                stopClipMode(isExplicitStop = false)
            }
        }, Handler(Looper.getMainLooper()))

        // The delay is kept so that IF the game reaches rotation in <2s we avoid
        // an unnecessary encoder restart, but the displayListener ensures it works
        // even if it takes longer.
        serviceScope.launch {
            delay(2000)
            if (!isRunning) return@launch  // service stopped while we were waiting

            // IMPORTANT: If a previous BGMI session crashed or left the mic open, the device
            // might be stuck in MODE_IN_COMMUNICATION. In this mode, Vivo's audio policy
            // often routes all game audio away from the standard media streams, causing
            // AudioPlaybackCapture to return pure silence (even for games like Clash Royale).
            // We force reset the mode here to recover from that broken state.
            try {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                if (am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) {
                    Log.w("CLIP_INIT", "Device was stuck in MODE_IN_COMMUNICATION! Forcing reset to MODE_NORMAL.")
                    am.mode = android.media.AudioManager.MODE_NORMAL
                }
            } catch (e: Exception) {
                Log.w("CLIP_INIT", "Could not reset audio mode: ${e.message}")
            }

            // IMPORTANT: Setup audio BEFORE video. On some devices (notably Vivo),
            // createVirtualDisplay() on the MediaProjection locks the projection
            // in a state where AudioPlaybackCapture returns zeros. By creating the
            // AudioPlaybackCapture AudioRecord first, the audio policy registers
            // the capture before VirtualDisplay claims the projection.
            Log.d("CLIP_INIT", "Step 1: Setting up audio encoder FIRST...")
            try {
                setupAudioEncoder()
                Log.d("CLIP_INIT", "Step 2: Audio encoder done. audioEncoder=${audioEncoder != null} internal=${internalAudioRecord != null} mic=${micAudioRecord != null}")
            } catch (e: Exception) {
                Log.e("CLIP_INIT", "Step 2: setupAudioEncoder() CRASHED (audio will be missing)", e)
            }

            Log.d("CLIP_INIT", "Step 3: Setting up video encoder...")
            try {
                setupVideoEncoder()
            } catch (e: Exception) {
                Log.e("CLIP_INIT", "FATAL: setupVideoEncoder() failed, cannot start clip mode", e)
                isRunning = false
                try { clipRepository.setClipModeActive(false) } catch (_: Exception) {}
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            Log.d("CLIP_INIT", "Step 4: Video encoder OK.")

            displayManager = getSystemService(android.hardware.display.DisplayManager::class.java)
            displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

            videoLoopJob = serviceScope.launch { runVideoEncoderLoop() }

            Log.d("CLIP_INIT", "Step 4: Starting audio capture threads...")
            try {
                startAudioCaptureThreads()
                Log.d("CLIP_INIT", "Step 5: Audio capture threads started OK")
            } catch (e: Exception) {
                Log.e("CLIP_INIT", "Step 5: startAudioCaptureThreads() CRASHED", e)
            }

            // Mark buffer as ready once first keyframe arrives
            try {
                var marked = false
                while (isRunning && !marked) {
                    val snapshot = bufferSink?.snapshot()
                    val videoChunks = snapshot?.first ?: emptyList()
                    val hasKeyframe = videoChunks.any { chunk ->
                        (chunk.bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    }
                    if (hasKeyframe) {
                        try { clipRepository.setClipReady(true) } catch (e: Exception) {}
                        marked = true
                        break
                    }
                    kotlinx.coroutines.delay(200)
                }
            } catch (e: Exception) {
                // best-effort
            }
        }

        // NOTE: FloatingOverlayService is pre-started from HomeScreen while in the foreground.
        // Do NOT start it here — Android 14+ will throw ForegroundServiceStartNotAllowedException
        // because by this point the app is backgrounded (user selected an app in MediaProjection dialog).
    }

    private fun setupVideoEncoder() {
        // getContentSize() uses WindowMetrics on API 30+ (avoids removed Display.getRealSize()).
        // rawW/rawH = the actual pixel dimensions of the content currently on screen,
        // correctly accounting for the device's current rotation.
        val (rawW, rawH) = getContentSize()

        // Compute display density for VirtualDisplay.
        // IMPORTANT: Use the real Display object DPI, NOT Resources.getSystem().densityDpi.
        // Resources.getSystem() returns logical/scaled DPI which can be lower than the physical
        // display DPI, causing the VirtualDisplay to render content at a reduced resolution.
        // The actual Display DPI ensures 1:1 pixel mapping on the captured surface.
        val displayDpi = run {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)
            if (metrics.densityDpi > 0) metrics.densityDpi
            else android.content.res.Resources.getSystem().displayMetrics.densityDpi
        }
        val densityDpi = displayDpi

        val isLandscape = rawW > rawH
        var width: Int
        var height: Int

        // IMPORTANT: align to 2 (not 16) so height preserves the EXACT display AR.
        // Aligning to 16 breaks the AR by up to ~15px, which causes the GPU to
        // letterbox/pillarbox the content when the VD and encoder dims don't match.
        // All modern Android H.264/HEVC hardware encoders accept even-number dims.
        if (forceFixed1080p) {
            if (isLandscape) {
                // Long edge = 1920, scale height to preserve AR exactly
                width = 1920
                val exactH = rawH.toFloat() * 1920f / rawW.toFloat()
                height = (Math.round(exactH) / 2) * 2
            } else {
                // Portrait: short edge = 1080
                width = 1080
                val exactH = rawH.toFloat() * 1080f / rawW.toFloat()
                height = (Math.round(exactH) / 2) * 2
            }
        } else {
            // Capture native 1:1 pixels for pristine zoom detail in video editors!
            // Most modern phones are 2400x1080 etc. Capturing native prevents blocky scaling artifacts.
            // Absolute safety cap at 2560 (1440p class) to prevent MediaCodec crashes on 4K tablets.
            if (maxOf(rawW, rawH) > 2560) {
                val scale = 2560f / maxOf(rawW, rawH).toFloat()
                width = (Math.round(rawW * scale) / 2) * 2
                height = (Math.round(rawH * scale) / 2) * 2
            } else {
                width = (rawW / 2) * 2
                height = (rawH / 2) * 2
            }
        }
        if (width < 2) width = 2
        if (height < 2) height = 2

        currentVideoWidth = width
        currentVideoHeight = height
        lastIsLandscape = isLandscape

        Log.d("ENCODER", "contentSize=${rawW}x${rawH} encoderSize=${width}x${height}")

        // Create encoder with fixed values. If configuration fails, fall back
        // to 1280x720 (landscape) or 720x1280 (portrait).
        val mime = if (MediaCodecList(MediaCodecList.ALL_CODECS)
                .codecInfos.any { it.isEncoder && it.supportedTypes.contains("video/hevc") }) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }

        var inputSurface: android.view.Surface? = null
        try {
            videoEncoder = MediaCodec.createEncoderByType(mime)
            val pixels = width * height

            // BITRATE RATIONALE (Editor Quality):
            // Boosted to ensure sufficient detail when zooming in editing apps like CapCut/Filmora.
            // HEVC is ~2x more efficient than AVC. These values yield visually lossless quality.
            // Portrait gets a 10% reduction (less motion than landscape), not 15%.
            // BITRATE RATIONALE (Text Legibility + Editor Quality):
            // Using CBR (see below) means these values ARE the constant bitrate — the encoder
            // will spend exactly this many bits per second on EVERY frame, including static
            // scoreboard/HUD frames with fine text. VBR would starve such frames of bits,
            // turning small text into a blurry, blocky mess.
            // Boosted ~30% over previous VBR values to compensate:
            //   - HEVC CBR can be ~20% lower than AVC CBR for same quality
            //   - Fine text (thin strokes, small fonts) needs higher per-frame budget than motion
            var bitrate = when {
                pixels >= 2560 * 1400 -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 55_000_000 else 75_000_000
                pixels >= 1920 * 1080 -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 40_000_000 else 55_000_000
                pixels >= 1280 * 720  -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 25_000_000 else 35_000_000
                else                  -> if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 18_000_000 else 24_000_000
            }
            if (!isLandscape) bitrate = (bitrate * 0.90f).toInt() // portrait has less motion, needs slightly less bits
            if (isDeviceOverheating()) {
                Log.w("THERMAL", "Device warm at startup, reducing bitrate 25%")
                bitrate = (bitrate * 0.75f).toInt()
            }
            initialBitrate = bitrate
            currentBitrate = bitrate

            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                // 30fps: most Android games render at 30–40fps. Recording at 60fps
                // means encoding duplicate frames, wasting SoC cycles with zero quality gain.
                // 30fps clips play smoothly in all editors and look indistinguishable from 60fps
                // for typical highlight-clip durations (15–120s).
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_CAPTURE_RATE, 30)
                // CBR (Constant Bit Rate): encoder spends the full bitrate budget on EVERY frame.
                // This is critical for text legibility — VBR aggressively starves low-motion frames
                // (scoreboards, HUDs, kill-feeds) of bits, turning small text into blurry blocks.
                // CBR ensures fine text, thin lines, and small fonts are always encoded cleanly.
                // Trade-off: ~20-30% more thermal output vs VBR on static scenes — acceptable for
                // the significant quality improvement on text-heavy game UI.
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                // 2-second keyframe interval: halves keyframe encoding overhead vs 1s.
                // Still fine for clip durations; editors handle 2s GOP easily.
                // 1-second keyframe interval: critical for editing software (CapCut, Filmora, etc.)
                // When zooming/scrubbing, editors decode from the nearest keyframe.
                // A 2s GOP means up to 2s of frame delta artifacts on zoom. 1s halves that.
                // The overhead is ~5% more bitrate — negligible vs quality improvement.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                try { setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (e: Exception) {}
                try {
                    if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                    } else {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                    }
                } catch (e: Exception) {}
            }
            videoEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Log.d("DEBUG_ENCODER", "Configured mime=$mime ${width}x${height} @ 30fps ${bitrate/1_000_000}Mbps VBR")

            inputSurface = videoEncoder!!.createInputSurface()
            videoEncoder!!.start()
            
            // Force early keyframe
            try {
                videoEncoder!!.setParameters(android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            } catch (e: Exception) {}

        } catch (e: Exception) {
            Log.w("ClipModeService", "Failed to configure/start primary encoder, falling back to 720p", e)
            try { videoEncoder?.release() } catch (ignored: Exception) {}
            
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            if (isLandscape) {
                width = 1280
                val exactH = rawH.toFloat() * 1280f / rawW.toFloat()
                height = (Math.round(exactH) / 2) * 2
            } else {
                width = 720
                val exactH = rawH.toFloat() * 720f / rawW.toFloat()
                height = (Math.round(exactH) / 2) * 2
            }
            currentVideoWidth = width
            currentVideoHeight = height
            val fallbackFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)  // bumped from 14Mbps for text legibility
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_CAPTURE_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)  // CBR for text legibility
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            videoEncoder!!.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Log.d("ENCODER", "Fallback 720p AVC 30fps 10Mbps VBR: ${width}x${height}")
            
            inputSurface = videoEncoder!!.createInputSurface()
            videoEncoder!!.start()
        }

        // If both primary and fallback encoders failed to produce a surface, abort
        if (inputSurface == null) {
            Log.e("ClipModeService", "Both primary and fallback encoders failed — cannot create VirtualDisplay")
            return
        }

        // VirtualDisplay identical size -> no black bars
        val displayWidth = width
        val displayHeight = height

        Log.d("DISPLAY", "VD=${displayWidth}x${displayHeight} == encoder=${width}x${height} → zero black bars")

        virtualDisplay?.release()
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ClipModeCapture",
            displayWidth,
            displayHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )
    }


    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null

    private fun setupAudioEncoder() {
        try {
            // CRITICAL: Ensure any existing audio resources are released before re-allocating.
            // "could not register audio policy" often happens if a previous AudioRecord 
            // wasn't fully released, as Android only allows one active capture policy per session.
            try {
                internalAudioRecord?.stop()
                internalAudioRecord?.release()
                internalAudioRecord = null
            } catch (_: Exception) {}
            
            try {
                micAudioRecord?.stop()
                micAudioRecord?.release()
                micAudioRecord = null
            } catch (_: Exception) {}

            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.w("ClipModeService", "RECORD_AUDIO not granted — audio capture disabled")
                return
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                // 192kbps for high-fidelity audio in CapCut/Filmora. Negligible thermal cost.
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder!!.start()

            val bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

            // Setup Internal Audio — captures all device audio output (game, music, etc.)
            //
            // Strategy: Try multiple configurations in order of broadness.
            // Android's built-in screen recorder captures ALL audio by not adding
            // any usage filters. We try the same approach first.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {

                fun buildAudioRecord(usages: List<Int>): AudioRecord? {
                    return try {
                        val builder = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                        for (usage in usages) builder.addMatchingUsage(usage)
                        val config = builder.build()
                        val record = AudioRecord.Builder()
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(44100)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                    .build()
                            )
                            .setAudioPlaybackCaptureConfig(config)
                            .setBufferSizeInBytes(maxOf(bufferSize, 8192) * 2)
                            .build()
                        record.startRecording()
                        record
                    } catch (e: Exception) {
                        Log.w("CLIP_INIT", "AudioPlaybackCapture failed for usages $usages: ${e.message}")
                        null
                    }
                }

                // Stage 1: Core usages
                val coreUsages = listOf(
                    AudioAttributes.USAGE_MEDIA,
                    AudioAttributes.USAGE_GAME,
                    AudioAttributes.USAGE_UNKNOWN
                )
                internalAudioRecord = buildAudioRecord(coreUsages)

                if (internalAudioRecord != null) {
                    Log.d("CLIP_INIT", "✓ Internal audio started with MEDIA+GAME+UNKNOWN")
                } else {
                    Log.w("CLIP_INIT", "Core usages failed, trying MEDIA+GAME...")
                    internalAudioRecord = buildAudioRecord(listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME))
                    if (internalAudioRecord == null) {
                        Log.w("CLIP_INIT", "MEDIA+GAME failed, trying MEDIA only...")
                        internalAudioRecord = buildAudioRecord(listOf(AudioAttributes.USAGE_MEDIA))
                    }
                    if (internalAudioRecord != null) {
                        Log.d("CLIP_INIT", "✓ Internal audio started with fallback usages")
                    } else {
                        Log.e("CLIP_INIT", "ALL internal audio capture attempts FAILED")
                    }
                }
            }

            // Setup Mic Audio
            try {
                if (!isMicMuted) {
                    micAudioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC, 44100,
                        AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4
                    )
                    
                    val sessionId = micAudioRecord!!.audioSessionId
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(sessionId)
                        noiseSuppressor?.enabled = true
                    }
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = android.media.audiofx.AcousticEchoCanceler.create(sessionId)
                        echoCanceler?.enabled = true
                    }
                    if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                        automaticGainControl = android.media.audiofx.AutomaticGainControl.create(sessionId)
                        automaticGainControl?.enabled = true
                    }
                    micAudioRecord!!.startRecording()
                } else {
                    Log.d("CLIP_INIT", "Mic is muted, completely skipping mic AudioRecord creation to prevent echo-loop privacy policies from blocking internal capture.")
                }
            } catch (e: Exception) {
                Log.e("ClipModeService", "Mic audio setup failed", e)
            }

            if (internalAudioRecord == null && micAudioRecord == null) {
                throw Exception("Failed to open any audio streams")
            }

        } catch (e: Exception) {
            Log.e("CLIP_INIT", "setupAudioEncoder FAILED: ${e.message}", e)
            try { audioEncoder?.stop() } catch (_: Exception) {}
            try { audioEncoder?.release() } catch (_: Exception) {}
            audioEncoder = null
        }
    }

    private var lastVideoPtsUs = 0L
    private var lastLoggedPtsUs = 0L

    private suspend fun runVideoEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning) {
            // Yield first so the Kotlin scheduler can service other coroutines
            // (e.g. thermal checks, buffer pruning) even during heavy encode sessions.
            delay(0)
            try {
                // 33ms timeout = one 30fps frame period. The encoder produces frames at 30fps
                // so this blocks just until the next frame is ready rather than waking 3× per
                // frame (old 10ms timeout) and burning CPU issuing no-op syscalls.
                val outputIndex = videoEncoder?.dequeueOutputBuffer(bufferInfo, 33_000L) ?: continue
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = videoEncoder!!.outputFormat
                    bufferSink?.videoFormat = format

                    val width = if (format.containsKey(MediaFormat.KEY_WIDTH))
                        format.getInteger(MediaFormat.KEY_WIDTH) else -1
                    val height = if (format.containsKey(MediaFormat.KEY_HEIGHT))
                        format.getInteger(MediaFormat.KEY_HEIGHT) else -1
                        
                    Log.d("ENCODER", "Output format changed to: ${width}x${height}")
                    continue
                }
                if (outputIndex >= 0) {
                    val outputBuffer = videoEncoder?.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        var ptsUs = bufferInfo.presentationTimeUs
                        val expectedFrameTime = 1_000_000L / 60

                        // Ensure absolute monotonicity to prevent MediaMuxer crashes
                        val prevVideoPtsUs = lastVideoPtsUs
                        if (ptsUs <= prevVideoPtsUs) {
                            ptsUs = prevVideoPtsUs + 1 
                        }

                        lastVideoPtsUs = ptsUs

                        val correctedInfo = MediaCodec.BufferInfo().apply {
                            set(bufferInfo.offset, bufferInfo.size, ptsUs, bufferInfo.flags)
                        }
                        bufferSink?.onEncodedVideo(outputBuffer, correctedInfo)

                        // Frame drop detection (now correctly using the OLD prev value)
                        if (prevVideoPtsUs != 0L) {
                            val frameGap = ptsUs - prevVideoPtsUs
                            if (frameGap > 100_000) { // >100ms gap
                                Log.w("PERF", "Frame drop detected: ${frameGap / 1000}ms")
                            }
                        }

                        // Encoder starvation detection: positive PTS but empty buffer might indicate encoder pressure.
                        // Filter out codec config frames as they have 0 size but are not evidence of starvation.
                        if (bufferInfo.size == 0 && 
                            bufferInfo.presentationTimeUs > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            Log.w("PERF", "Encoder starvation/pressure detected")
                        }

                        if (bufferInfo.presentationTimeUs - lastLoggedPtsUs > 1_000_000) {
                            Log.d("PERF", "Encoder running smooth")
                            
                                // Dynamic Thermal Adjustment: poll every 2s (was 5s) for faster response
                                val now = System.currentTimeMillis()
                                if (now - lastThermalAdjust > 2000) {
                                    lastThermalAdjust = now
                                    
                                    val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                                        pm.currentThermalStatus
                                    } else -1

                                    val shouldThrottle = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
                                    val shouldRecover = thermalStatus <= PowerManager.THERMAL_STATUS_LIGHT && thermalStatus != -1
                                    
                                    var targetBitrate = currentBitrate
                                    var targetFps = 60  // default

                                    when {
                                        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> {
                                            // Severe heat: cut bitrate 40% AND drop to 24fps
                                            targetBitrate = (initialBitrate * 0.6f).toInt()
                                            targetFps = 24
                                        }
                                        thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> {
                                            // Moderate heat: cut bitrate 30% AND drop to 30fps
                                            targetBitrate = (initialBitrate * 0.7f).toInt()
                                            targetFps = 30
                                        }
                                        shouldRecover && currentBitrate < initialBitrate -> {
                                            // Cooling down: recover bitrate gradually and restore 60fps
                                            targetBitrate = minOf(initialBitrate, currentBitrate + 2_000_000)
                                            targetFps = 60
                                        }
                                    }
                                    
                                    if (targetBitrate != currentBitrate || targetFps != currentThermalFps) {
                                        currentBitrate = targetBitrate
                                        currentThermalFps = targetFps
                                        Log.w("THERMAL", "Adjusting: bitrate=${currentBitrate/1000}kbps fps=$targetFps (Thermal=$thermalStatus)")
                                        
                                        val params = android.os.Bundle()
                                        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, currentBitrate)
                                        // KEY_OPERATING_RATE signals the encoder how many frames/sec
                                        // it will actually receive — lets the HW encoder reduce its
                                        // internal clock to match, saving SoC power directly.
                                        params.putInt("operating-rate", targetFps)
                                        
                                        // Throttle keyframe requests to avoid sudden spikes or micro-freezes
                                        if (now - lastKeyframeRequest > 2000) {
                                            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                            lastKeyframeRequest = now
                                        }
                                        
                                        videoEncoder?.setParameters(params)
                                    }
                                }
                            
                            lastLoggedPtsUs = bufferInfo.presentationTimeUs
                        }

                        if (pendingClipRequest && ptsUs >= 2_000_000L) {
                            pendingClipRequest = false
                            Log.d("CLIP", "Auto-firing queued clip save")
                            saveClip()
                        }
                    }
                    videoEncoder?.releaseOutputBuffer(outputIndex, false)
                }
            } catch (e: Exception) {
                Log.e("ClipModeService", "Video encoding loop error", e)
            }
            // Note: delay(0) at the top of this loop already yields the coroutine scheduler.
            // A second yield() here is redundant and removed.
        } // end while (isRunning)
    }

    // ─── Audio capture threads (dedicated real-time threads, not coroutines) ──────────────────
    private var audioCaptureThread: Thread? = null
    private var audioEncodeThread: Thread? = null

    // Note: audio capture, mixing, and encoding all happen in audioCaptureThread
    // using stack-allocated buffers. No shared volatile PCM fields are needed.

    private fun startAudioCaptureThreads() {
        // BLOCK = 512 stereo shorts = ~5.8ms per read at 44.1kHz.
        // (was 1024 = ~11.6ms; smaller block = faster drain of the ring buffer
        //  = less gap between when audio was produced and when we read it)
        val BLOCK = 512

        audioCaptureThread = Thread({
            // THREAD_PRIORITY_AUDIO (not URGENT_AUDIO): clip recording writes to a ring buffer,
            // not real-time playback. URGENT_AUDIO is the highest priority class and prevents
            // the CPU governor from reducing clock frequency on this core, contributing to heating.
            // THREAD_PRIORITY_AUDIO is still well above normal and ensures no buffer underruns.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            // ── DIAGNOSTIC: Log the state of audio sources at thread start ────
            Log.d("AUDIO_DIAG", "Audio capture thread started")
            Log.d("AUDIO_DIAG", "  internalAudioRecord = ${if (internalAudioRecord != null) "ACTIVE (state=${internalAudioRecord?.recordingState})" else "NULL (no game audio)"}")
            Log.d("AUDIO_DIAG", "  micAudioRecord = ${if (micAudioRecord != null) "ACTIVE (state=${micAudioRecord?.recordingState})" else "NULL (no mic)"}")
            Log.d("AUDIO_DIAG", "  audioEncoder = ${if (audioEncoder != null) "ACTIVE" else "NULL"}")
            Log.d("AUDIO_DIAG", "  isMicMuted = $isMicMuted")

            val intBuf   = ShortArray(BLOCK * 2)  // stereo
            val micBuf   = ShortArray(BLOCK * 2)
            val mixBuf   = ShortArray(BLOCK * 2)
            val rawBytes = ByteArray(BLOCK * 2 * 2) // 2 bytes/short
            audioTotalFrames = 0L

            // ── Sample-accurate PTS state ─────────────────────────────────────
            // totalStereoFrames counts stereo frames (L+R pairs) fed to the encoder
            // since this thread started. Combined with audioStartNs it gives us
            // a perfectly linear PTS timeline that doesn't drift with read jitter.
            var totalStereoFrames = 0L
            // audioStartNs = CLOCK_MONOTONIC wall time (same clock as the video
            // hardware encoder). Set lazily on first read so it's as close as
            // possible to when audio actually starts flowing.
            var audioStartNs = 0L

            // ── Periodic diagnostic counters ──────────────────────────────────
            var diagLastLogMs = System.currentTimeMillis()
            var diagInternalTotal = 0L   // total samples from internal
            var diagMicTotal = 0L        // total samples from mic
            var diagSilenceBlocks = 0L   // times silence keepalive fired
            var diagEncoderFed = 0L      // times real audio was fed to encoder
            var diagPeakInternal = 0     // peak amplitude from internal audio (0 = all zeros = silence)
            var diagPeakMic = 0          // peak amplitude from mic audio

            val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager

            while (isRunning) {
                try {
                    // ── Read internal (game) audio ──────────────────────────────────
                    // AudioRecord.read() can return negative error codes (ERROR=-1,
                    // ERROR_INVALID_OPERATION=-3, etc.). Treat all negatives as 0.
                    val gotInternal = internalAudioRecord?.let { rec ->
                        val n = rec.read(intBuf, 0, intBuf.size)
                        if (n < 0) {
                            Log.w("AUDIO", "Internal AudioRecord error: $n — game audio unavailable")
                            0
                        } else n
                    } ?: 0

                    // ── Always drain micAudioRecord, even when muted ─────────────────
                    //
                    // CRITICAL BUG FIX: The old code skipped read() entirely when
                    // isMicMuted=true. If internalAudioRecord was also null/failing
                    // (common: many games set allowAudioPlaybackCapture=false), both
                    // gotInternal and gotMic were 0 → the loop hit `continue` → the
                    // audio encoder received NO input → the clip's audio track was
                    // completely absent → clips appeared muted.
                    //
                    // Fix: ALWAYS read from micAudioRecord so:
                    //   1. The hardware buffer stays drained (no overflow / error state).
                    //   2. The loop is naturally paced by the blocking read when internal
                    //      audio is unavailable, so encoder timing stays correct.
                    //   3. The mute flag ONLY controls whether mic samples enter the MIX,
                    //      not whether the hardware is read.
                    //
                    // MIC RECOVERY: When BGMI takes exclusive mic access (voice chat),
                    // every read() returns ERROR_INVALID_OPERATION (-3). We count
                    // consecutive failures and auto-mute after threshold.
                    val gotMicRaw = micAudioRecord?.let { rec ->
                        val n = rec.read(micBuf, 0, micBuf.size)
                        if (n < 0) {
                            // BGMI voice chat takes exclusive mic ownership temporarily.
                            // Previously this triggered autoMuteMicOnConflict() which set
                            // isMicMuted=true permanently for the session, silencing ALL
                            // mic audio. Now we just count errors for logging and continue —
                            // the game releases the mic between push-to-talk presses,
                            // so normal reads will resume naturally.
                            micConsecutiveErrors++
                            if (micConsecutiveErrors % 50 == 1) { // log every ~290ms, not every read
                                Log.w("AUDIO", "Mic AudioRecord error: $n (consecutive: $micConsecutiveErrors) — game may be using mic")
                            }
                            0
                        } else {
                            if (micConsecutiveErrors > 0) {
                                Log.d("AUDIO", "Mic reads resumed after $micConsecutiveErrors errors")
                                micConsecutiveErrors = 0
                            }
                            n
                        }
                    } ?: 0

                    // When muted: mic buffer was drained above but contributes 0 to mix.
                    val gotMic = if (isMicMuted) 0 else gotMicRaw

                    // Use the larger sample count so the encoder is fed whenever EITHER
                    // source has data. gotMicRaw (not gotMic) ensures the loop is paced
                    // by real hardware reads even when mic toggle is off.
                    val samples = maxOf(gotInternal, gotMicRaw)

                    // ── Track peak amplitude to detect silent-but-counting data ──
                    for (i in 0 until gotInternal) {
                        val abs = Math.abs(intBuf[i].toInt())
                        if (abs > diagPeakInternal) diagPeakInternal = abs
                    }
                    for (i in 0 until gotMicRaw) {
                        val abs = Math.abs(micBuf[i].toInt())
                        if (abs > diagPeakMic) diagPeakMic = abs
                    }

                    // ── Periodic audio health diagnostic (every 5 seconds) ──────
                    diagInternalTotal += gotInternal
                    diagMicTotal += gotMicRaw
                    val nowDiag = System.currentTimeMillis()
                    if (nowDiag - diagLastLogMs >= 5000) {
                        Log.d("AUDIO_DIAG", "=== Audio Health (last 5s) ===")
                        Log.d("AUDIO_DIAG", "  internal samples: $diagInternalTotal | mic samples: $diagMicTotal")
                        Log.d("AUDIO_DIAG", "  PEAK AMPLITUDE: internal=$diagPeakInternal mic=$diagPeakMic ${if (diagPeakInternal == 0) "⚠️ INTERNAL IS SILENT!" else "✓ has audio"}")
                        Log.d("AUDIO_DIAG", "  silence blocks: $diagSilenceBlocks | encoder fed: $diagEncoderFed")
                        Log.d("AUDIO_DIAG", "  internalRec=${internalAudioRecord?.recordingState} micRec=${micAudioRecord?.recordingState}")
                        Log.d("AUDIO_DIAG", "  isMicMuted=$isMicMuted totalStereoFrames=$totalStereoFrames audioMode=${audioManager.mode}")
                        Log.d("AUDIO_DIAG", "  bufferSink audioFormat=${bufferSink?.audioFormat != null} audioChunks=${bufferSink?.let { "exists" } ?: "null"}")
                        diagLastLogMs = nowDiag
                        diagInternalTotal = 0
                        diagMicTotal = 0
                        diagSilenceBlocks = 0
                        diagEncoderFed = 0
                        diagPeakInternal = 0
                        diagPeakMic = 0
                    }

                    // ── Compute PTS BEFORE advancing the frame counter ────────────
                    // PTS strategy (API 24+): use AudioRecord.getTimestamp() which
                    // returns the hardware position and the CLOCK_MONOTONIC nanotime
                    // at which that frame was captured. We extrapolate backwards to
                    // the start of our current block. This is the ACTUAL game-sound
                    // production time — not the time we finished blocking on read().
                    //
                    // Root cause of bullet-sound delay:
                    //   AudioRecord keeps a ring buffer. The game fills it; we drain
                    //   it later. Old code: ptsUs = System.nanoTime() (= read-time),
                    //   but the samples were produced earlier (= production-time).
                    //   Stamping with read-time makes every sound appear LATE in the
                    //   clip by exactly the ring-buffer pre-fill depth.
                    //
                    // Fallback (API < 24 or timestamp unavailable): sample-count PTS
                    // anchored to audioStartNs. This is linear and jitter-free, even
                    // though it can't correct for the initial pre-fill offset.
                    val frameAtBlockStart = totalStereoFrames

                    var ptsUs: Long
                    val ts = AudioTimestamp()
                    val activeRec = internalAudioRecord ?: micAudioRecord
                    val gotTs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activeRec != null) {
                        activeRec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                    } else false

                    if (gotTs && ts.framePosition > 0) {
                        val frameOffsetNs = (frameAtBlockStart - ts.framePosition) * 1_000_000_000L / 44100L
                        ptsUs = (ts.nanoTime + frameOffsetNs) / 1000L
                    } else {
                        if (audioStartNs == 0L) audioStartNs = System.nanoTime()
                        ptsUs = audioStartNs / 1000L + (frameAtBlockStart * 1_000_000L / 44100L)
                    }

                    if (samples <= 0) {
                        // AUDIO KEEPALIVE: Both sources gave 0 samples. Feed silence so
                        // the audio track stays present and decodable in the saved MP4.
                        diagSilenceBlocks++
                        val encoder = audioEncoder
                        if (encoder == null) { Thread.sleep(2); continue }
                        val inputIdx = encoder.dequeueInputBuffer(8_000L)
                        if (inputIdx >= 0) {
                            val inputBuf = encoder.getInputBuffer(inputIdx)
                            if (inputBuf != null) {
                                inputBuf.clear()
                                val silentBytes = BLOCK * 2 * 2
                                inputBuf.put(ByteArray(silentBytes))
                                if (ptsUs <= lastAudioPtsUs) ptsUs = lastAudioPtsUs + 1
                                lastAudioPtsUs = ptsUs
                                encoder.queueInputBuffer(inputIdx, 0, silentBytes, ptsUs, 0)
                                totalStereoFrames += BLOCK.toLong()
                            } else {
                                encoder.queueInputBuffer(inputIdx, 0, 0, 0, 0)
                            }
                        } else {
                            Thread.sleep(1)
                        }
                        continue
                    }

                    // Advance frame counter now that we know how many samples were read
                    totalStereoFrames += (samples / 2).toLong()

                    // ── Mix ──────────────────────────────────────────────────────────
                    // a = internal game audio (0 if unavailable or silent)
                    // b = mic audio (auto-enabled when internal is silent)
                    for (i in 0 until samples) {
                        val a = if (gotInternal > i) intBuf[i].toInt() else 0
                        val b = if (gotMic      > i) micBuf[i].toInt() else 0
                        var m = a + b
                        if (m > Short.MAX_VALUE)  m = Short.MAX_VALUE.toInt()
                        if (m < Short.MIN_VALUE) m = Short.MIN_VALUE.toInt()
                        mixBuf[i] = m.toShort()
                    }

                    // ── Feed encoder ───────────────────────────────────────────────
                    val encoder = audioEncoder ?: continue
                    val inputIdx = encoder.dequeueInputBuffer(8_000L)
                    if (inputIdx >= 0) {
                        val inputBuf = encoder.getInputBuffer(inputIdx)
                        if (inputBuf == null) {
                            encoder.releaseOutputBuffer(inputIdx, false)
                            continue
                        }
                        inputBuf.clear()
                        // Convert ShortArray → byte array (little-endian PCM)
                        val bb = java.nio.ByteBuffer.wrap(rawBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until samples) bb.putShort(mixBuf[i])
                        inputBuf.put(rawBytes, 0, samples * 2)

                        if (ptsUs <= lastAudioPtsUs) ptsUs = lastAudioPtsUs + 1
                        lastAudioPtsUs = ptsUs
                        encoder.queueInputBuffer(inputIdx, 0, samples * 2, ptsUs, 0)
                        diagEncoderFed++
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e("ClipModeService", "Audio capture/mix error", e)
                }
            }
        }, "st-audio-capture")
        audioCaptureThread?.start()

        // ── Drain encoder output on its own thread ─────────────────────────────
        audioEncodeThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning) {
                try {
                    val encoder = audioEncoder
                    if (encoder == null) {
                        Thread.sleep(2)
                        continue
                    }
                    // 33ms timeout matches the 30fps frame period — one wakeup per frame
                    // instead of 3 (old 10ms). Eliminates 2/3 of no-op syscall overhead.
                    val outputIdx = encoder.dequeueOutputBuffer(bufferInfo, 33_000L)
                    when {
                        outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            bufferSink?.audioFormat = encoder.outputFormat
                        }
                        outputIdx >= 0 -> {
                            val outputBuf = encoder.getOutputBuffer(outputIdx)
                            if (outputBuf != null &&
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                bufferSink?.onEncodedAudio(outputBuf, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outputIdx, false)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e("ClipModeService", "Audio encode drain error", e)
                }
            }
        }, "st-audio-encode")
        audioEncodeThread?.start()
    }


    private fun saveClip() {
        Log.e("REAL_BUFFER", "CHECK LOG WORKING")
        if (!isRunning) {
            Log.e("CLIP", "Not recording, ignoring")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastClipTime < 2000) {
            Log.d("CLIP", "Cooldown active, ignoring request")
            return
        }
        lastClipTime = now

        // Haptic feedback for immediate user confirmation
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) { /* best-effort */ }

        val startTimePerf = System.currentTimeMillis()
        // Prevent concurrent save operations from rapid double-taps
        if (!isSaving.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Clip save already in progress…", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // If the service hasn't started yet (still waiting for permission / engine init),
        // queue the save and it will auto-fire once the buffer has a keyframe.
        if (!isRunning || bufferSink == null) {
            isSaving.set(false)
            // Set queued flag so FloatingOverlayService auto-fires when clipReady becomes true
            serviceScope.launch(Dispatchers.Main) {
                try { clipRepository.setQueuedSave(true) } catch (e: Exception) {}
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "⏳ Queued — will save when engine starts", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Engine is running — show instant feedback then proceed
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "✂️ Saving clip…", Toast.LENGTH_SHORT).show()
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
            val sink = bufferSink ?: run { isSaving.set(false); return@launch }
            // Storage check
            val stat = StatFs(filesDir.path)
            if (stat.availableBytes < 50L * 1024 * 1024) {
                Log.w("STORAGE", "Not enough space to save clip")
                sink.releaseProtectedFiles()
                isSaving.set(false)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@ClipModeService, "💾 Storage full — free up space to save clips", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // Update notification to indicate active saving
            updateNotification("Saving clip…")

            sink.flushBuffers()
            val (rawVideoChunks, rawAudioChunks) = sink.snapshotProtected()

            // Filter to frames from the CURRENT orientation session only.
            // lastRestartPtsUs is 0 if no orientation flip happened (use all frames).
            // After a flip it's the PTS of the last frame from the old orientation, so
            // we drop mixed-dimension frames that would corrupt the muxer output.
            val orientationCutoff = lastRestartPtsUs
            val videoChunks = if (orientationCutoff > 0)
                rawVideoChunks.filter { it.bufferInfo.presentationTimeUs > orientationCutoff }
            else rawVideoChunks
            val audioChunks = if (orientationCutoff > 0)
                rawAudioChunks.filter { it.bufferInfo.presentationTimeUs > orientationCutoff }
            else rawAudioChunks

            Log.d("DEBUG_BUFFER",
                "raw chunks: video=${rawVideoChunks.size} audio=${rawAudioChunks.size} " +
                "after orientationCutoff(${orientationCutoff/1_000_000}s): video=${videoChunks.size} audio=${audioChunks.size}"
            )

            val first = videoChunks.firstOrNull()?.bufferInfo?.presentationTimeUs ?: -1
            val last = videoChunks.lastOrNull()?.bufferInfo?.presentationTimeUs ?: -1

            Log.d("DEBUG_BUFFER",
                "firstPTS=$first lastPTS=$last durationUs=${last - first}"
            )
            val bufferDuration = last - first
            Log.e("REAL_BUFFER", "Buffer duration sec = ${bufferDuration / 1_000_000}")
            
            val latest = videoChunks.lastOrNull()?.bufferInfo?.presentationTimeUs ?: 0L

            if (latest < 3_000_000L) {
                Log.w("CLIP", "Not enough buffer yet")
                sink.releaseProtectedFiles()
                isSaving.set(false)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@ClipModeService, "⏳ Not enough buffer yet — keep playing!", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            Log.d("CLIP", "Chunks: ${videoChunks.size}")
            Log.d("CLIP", "Latest PTS: $latest")
            Log.d("CLIP", "Width x Height: $currentVideoWidth x $currentVideoHeight")

            Log.d("DEBUG", "Saving clip with ${videoChunks.size} frames (audio: ${audioChunks.size})")
            Log.d("ClipModeService", "saveClip: sink.durationSeconds=${sink.durationSeconds}, videoChunks=${videoChunks.size}, audioChunks=${audioChunks.size}")
            
            if (videoChunks.isEmpty()) {
                sink.releaseProtectedFiles()
                isSaving.set(false)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@ClipModeService, "Nothing to clip yet – keep playing!", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val latestTimeUs = videoChunks.last().bufferInfo.presentationTimeUs
            val durationUs = sink.durationSeconds * 1_000_000L

            val bufferStartUs = videoChunks.first().bufferInfo.presentationTimeUs
            val requestedStartUs = latestTimeUs - (sink.durationSeconds * 1_000_000L)

            val targetCutoffUs = maxOf(bufferStartUs, requestedStartUs)
            Log.d("ClipModeService", "saveClip: latestTimeUs=$latestTimeUs, targetCutoffUs=$targetCutoffUs")
            
            var firstKeyframeIndex = videoChunks.indexOfFirst {
                it.bufferInfo.presentationTimeUs >= targetCutoffUs &&
                it.bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            }

            if (firstKeyframeIndex == -1) {
                firstKeyframeIndex = videoChunks.indexOfFirst {
                    it.bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                }
            }

            if (firstKeyframeIndex == -1) firstKeyframeIndex = 0
            val trimmedVideo = videoChunks.drop(firstKeyframeIndex)
            Log.d("DEBUG_TRIM",
                "targetCutoffUs=$targetCutoffUs firstKeyframeIndex=$firstKeyframeIndex trimmedVideo=${trimmedVideo.size}"
            )
            Log.d("ClipModeService", "saveClip: firstKeyframeIndex=$firstKeyframeIndex, trimmedVideo=${trimmedVideo.size}")
            if (trimmedVideo.isEmpty()) {
                sink.releaseProtectedFiles()
                isSaving.set(false)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@ClipModeService, "Video data is empty", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val fileName = "clip_${sink.durationSeconds}s_${sdf.format(Date())}.mp4"
            // Use a temporary file for muxing first
            val tempFile = File(cacheDir, "temp_clip_${System.currentTimeMillis()}.mp4")

            var muxer: MediaMuxer? = null
            try {
                muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                if (sink.videoFormat == null) {
                    sink.releaseProtectedFiles()
                    isSaving.set(false)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@ClipModeService, "Still buffering, wait a moment and try again", Toast.LENGTH_SHORT).show()
                    }
                    muxer.release()
                    return@launch
                }
                val videoTrack = muxer.addTrack(sink.videoFormat!!)
                val videoStartTimeUs = trimmedVideo.first().bufferInfo.presentationTimeUs
                val durationUs = latestTimeUs - videoStartTimeUs

                // AUDIO SYNC FIX: Anchor audio to the exact same PTS reference as video.
                //
                // Both video (hardware encoder surface timestamps) and audio (System.nanoTime())
                // use CLOCK_MONOTONIC (nanoseconds since boot), so they share the same time base.
                // The key insight: video is trimmed to start at videoStartTimeUs. We must find the
                // audio chunk whose PTS is closest to videoStartTimeUs so both tracks start at t=0
                // simultaneously in the muxed file. Using (latestAudio - durationUs) as the audio
                // anchor was wrong when audio and video clocks had any startup offset, causing
                // audio to appear late.
                //
                // New approach: find the audio chunk near videoStartTimeUs, use that as audioStartTimeUs.
                val audioFirstPts = audioChunks.firstOrNull()?.bufferInfo?.presentationTimeUs ?: 0L
                val audioLastPts  = audioChunks.lastOrNull()?.bufferInfo?.presentationTimeUs ?: 0L

                // The audio chunk closest to where video starts (videoStartTimeUs)
                val audioStartTimeUs = maxOf(audioFirstPts, videoStartTimeUs)

                Log.d("CLIP_INIT", "SAVE: audioChunks total=${audioChunks.size} audioFirstPts=$audioFirstPts audioLastPts=$audioLastPts")
                Log.d("CLIP_INIT", "SAVE: videoStartTimeUs=$videoStartTimeUs audioStartTimeUs=$audioStartTimeUs")
                Log.d("CLIP_INIT", "SAVE: audioFormat=${sink.audioFormat != null}")

                // A/V Sync Guard — log any remaining drift for diagnostics
                val latestAudioTimeUs = audioLastPts
                val avDrift = Math.abs(latestTimeUs - latestAudioTimeUs)
                if (avDrift > 100_000) {
                    Log.w("CLIP_INIT", "SAVE: AV drift=${avDrift / 1000}ms (video=${latestTimeUs/1000}ms audio=${latestAudioTimeUs/1000}ms)")
                }

                val validAudioChunks = audioChunks.filter { it.bufferInfo.presentationTimeUs >= audioStartTimeUs }
                Log.d("CLIP_INIT", "SAVE: validAudioChunks=${validAudioChunks.size} (after filtering >= audioStartTimeUs)")

                val audioTrack = if (sink.audioFormat != null && validAudioChunks.isNotEmpty())
                    muxer.addTrack(sink.audioFormat!!) else -1
                Log.d("CLIP_INIT", "SAVE: audioTrack=$audioTrack (${if (audioTrack >= 0) "AUDIO WILL BE MUXED" else "NO AUDIO IN CLIP"})")

                // Ensure players respect the recorded screen orientation by setting
                // the orientation hint on the muxer (degrees). This fixes cases where
                // the saved MP4 appears rotated or has wrong aspect in gallery apps.
                try {
                    val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                    val defDisp = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                    val rotation = defDisp?.rotation ?: android.view.Surface.ROTATION_0
                    // Simplified: always write zero rotation hint. Encoder pixels are
                    // produced in the fixed orientation (1920x1080 or 1080x1920),
                    // so no rotation metadata is required.
                    try {
                        muxer.setOrientationHint(0)
                        Log.d("ClipModeService", "saveClip: setOrientationHint=0 (encoder=${currentVideoWidth}x${currentVideoHeight})")
                    } catch (e: Exception) { /* best-effort */ }
                } catch (e: Exception) { /* best-effort */ }

                val startPts = trimmedVideo.firstOrNull()?.bufferInfo?.presentationTimeUs ?: -1
                val endPts = trimmedVideo.lastOrNull()?.bufferInfo?.presentationTimeUs ?: -1

                Log.d("DEBUG_MUX",
                    "startPts=$startPts endPts=$endPts durationUs=${endPts - startPts}"
                )

                muxer.start()

                // Crucial fix: Timestamp re-zeroing
                val allSamples = mutableListOf<Pair<Int, com.streamtwin.data.clip.DiskChunkRef>>()
                trimmedVideo.forEach { allSamples.add(Pair(videoTrack, it)) }
                if (audioTrack >= 0) {
                    validAudioChunks.forEach { allSamples.add(Pair(audioTrack, it)) }
                }
                allSamples.sortBy { it.second.bufferInfo.presentationTimeUs }

                var trackLastVideoPts = -1L
                var trackLastAudioPts = -1L

                val fileReaders = mutableMapOf<String, java.io.RandomAccessFile>()

                allSamples.forEach { (trackIdx, chunk) ->
                    val raf = fileReaders.getOrPut(chunk.file.absolutePath) { java.io.RandomAccessFile(chunk.file, "r") }
                    raf.seek(chunk.offset)
                    val bytes = ByteArray(chunk.bufferInfo.size)
                    raf.readFully(bytes)
                    val bb = ByteBuffer.wrap(bytes)
                    var pts = 0L
                    if (trackIdx == videoTrack) {
                        pts = chunk.bufferInfo.presentationTimeUs - videoStartTimeUs
                        val frameTime = 1_000_000L / 60
                        if (pts <= trackLastVideoPts) {
                            pts = trackLastVideoPts + frameTime
                        }
                        trackLastVideoPts = pts
                    } else if (trackIdx == audioTrack) {
                        pts = chunk.bufferInfo.presentationTimeUs - audioStartTimeUs
                        if (pts < 0) pts = 0 // Guard against slight floating point offsets
                        if (pts <= trackLastAudioPts) pts = trackLastAudioPts + 1
                        trackLastAudioPts = pts
                    }

                    val info = MediaCodec.BufferInfo().apply {
                        offset = 0
                        size = chunk.bufferInfo.size
                        presentationTimeUs = pts
                        flags = chunk.bufferInfo.flags
                    }
                    if (info.presentationTimeUs >= 0) {
                        muxer.writeSampleData(trackIdx, bb, info)
                    }
                }
                
                fileReaders.values.forEach { it.close() }

                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Log.w("MUX", "Muxer stop failed (possibly no frames written)", e)
                }
                try {
                    muxer.release()
                } catch (e: Exception) {
                    Log.w("MUX", "Muxer release failed", e)
                }

                Log.d("CLIP", "Final clip duration: ${(latestTimeUs - videoStartTimeUs)/1_000_000}s")

                val finalUri = saveToGallery(tempFile, fileName)

                if (finalUri != null) {
                    clipRepository.onClipSaved(fileName)
                    showClipSavedNotification(fileName, finalUri)
                    Log.d("ClipModeService", "Clip saved: $fileName, Uri: $finalUri")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@ClipModeService, "🎬 Clip saved to Vault!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@ClipModeService, "Failed writing to Vault storage", Toast.LENGTH_LONG).show()
                    }
                }

                tempFile.delete()
                Log.d("PERF", "Clip saved in ${System.currentTimeMillis() - startTimePerf} ms")

            } catch (e: Exception) {
                Log.e("ClipModeService", "Error muxing mp4", e)
                try { muxer?.release() } catch (e2: Exception) {}
                tempFile.delete()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@ClipModeService, "Failed to save clip: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                updateNotification("Double-tap bubble to save a clip")
                sink.releaseProtectedFiles()
                isSaving.set(false)
            }
            } finally {
                // Outer try safety net — always release the save lock
                if (isSaving.get()) isSaving.set(false)
            }
        }
    }

    private fun saveToGallery(sourceFile: File, fileName: String): android.net.Uri? {
        val resolver = contentResolver
        val videoCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StreamTwin")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        var resultUri: android.net.Uri? = null
        val videoUri = resolver.insert(videoCollection, contentValues)
        videoUri?.let { uri ->
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(sourceFile).use { inputStream ->
                        inputStream.copyTo(outputStream, bufferSize = 1024 * 1024)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                resultUri = uri
            } catch (e: Exception) {
                Log.e("ClipModeService", "Error copying clip to gallery", e)
                resolver.delete(uri, null, null)
            }
        }
        
        // Backup: if MediaStore fails or we're on < Q and want a file URI, we can try direct file creation
        if (resultUri == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StreamTwin")
                if (!dir.exists()) dir.mkdirs()
                val destFile = File(dir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)
                resultUri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", destFile)
                
                // Trigger MediaScanner so the system Gallery indexes it instantly
                MediaScannerConnection.scanFile(this, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null)
            } catch (e: Exception) {
                Log.e("ClipModeService", "Fallback direct save failed", e)
            }
        }
        return resultUri
    }

    private fun stopClipMode(isExplicitStop: Boolean = true) {
        // Guard against double-invocation (e.g. onDestroy AND MediaProjection.onStop firing concurrently)
        if (!isStopping.compareAndSet(false, true)) return

        isRunning = false

        try { displayManager?.unregisterDisplayListener(displayListener) } catch (e: Exception) {}

        // ── STEP 1: Stop AudioRecord sources BEFORE interrupting threads ──────────
        stopAudioCaptureSystem()
        
        try { videoEncoder?.stop()   } catch (_: Exception) {}
        try { videoEncoder?.release(); videoEncoder = null } catch (_: Exception) {}

        // ── STEP 5: Repository state, VD, MediaProjection, Buffer ─────────────────
        try {
            clipRepository.setClipModeActive(false)
            clipRepository.setClipReady(false)
        } catch (_: Exception) {}

        // Stop accelerometer before releasing VD / stopping the service scope
        stopBackTapDetector()

        virtualDisplay?.release()
        mediaProjection?.stop()
        bufferSink?.clear()
        serviceScope.cancel()

        stopForeground(STOP_FOREGROUND_REMOVE)

        // Only kill the overlay on an explicit user-initiated stop (not on MediaProjection crash)
        if (isExplicitStop) {
            try {
                startService(Intent(this, FloatingOverlayService::class.java).apply {
                    action = "STOP_OVERLAY"
                })
            } catch (e: Exception) {
                Log.w("ClipModeService", "Could not stop overlay: ${e.message}")
            }
        }

        stopSelf()
    }

    // ─── Back-tap detector ────────────────────────────────────────────────────

    /**
     * Register the accelerometer listener.
     * Uses TYPE_LINEAR_ACCELERATION (gravity already removed) at SENSOR_DELAY_GAME (~50Hz).
     * Power draw: ~0.15mA — negligible vs video encoder (~500mA).
     *
     * Tap criteria:
     *   - |Z| > 3.0g  (sharp back-face impact)
     *   - |X| + |Y| < 2.0g  (Z-isolated — rejects game rumble which shakes all axes)
     *   - 3 such events within 800ms → saveClip()
     *
     * False-positive guard:
     *   - If 3+ clips saved via back-tap within 10s, pause the sensor for 30s and
     *     show a Toast so the user knows why it stopped responding.
     */
    private fun startBackTapDetector() {
        if (backTapListener != null) {
            Log.d("BACK_TAP", "Detector already running, skipping re-register")
            return
        }
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            Log.e("BACK_TAP", "SensorManager unavailable — back-tap disabled")
            return
        }
        sensorManager = sm

        // Prefer TYPE_LINEAR_ACCELERATION (gravity already removed by the driver).
        // Fall back to TYPE_ACCELEROMETER on devices that don't expose the composite sensor.
        val linearSensor  = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val rawSensor      = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val chosenSensor   = linearSensor ?: rawSensor
        val usingLinear    = (chosenSensor == linearSensor && linearSensor != null)

        if (chosenSensor == null) {
            Log.e("BACK_TAP", "No accelerometer sensor on this device — back-tap disabled")
            return
        }

        Log.d("BACK_TAP", "Using sensor: ${chosenSensor.name} | type=${chosenSensor.type} | usingLinear=$usingLinear")

        // Gravity EMA for the TYPE_ACCELEROMETER fallback path.
        // When the phone is stationary, the EMA converges to gravity (≈9.8 m/s² downward).
        // Subtracting the EMA isolates the sudden tap impulse.
        // alpha=0.9 → fast convergence; effectively a high-pass filter above ~1Hz at 50Hz.
        val gravityEma = FloatArray(3) { 0f }
        val alpha      = 0.9f

        backTapListener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor, accuracy: Int) = Unit

            override fun onSensorChanged(event: SensorEvent) {
                if (!isRunning || !backTapEnabled) return

                val now = System.currentTimeMillis()
                if (now < backTapPausedUntil) return

                val raw = event.values
                val gFactor = 9.81f

                // Compute the impulse vector (gravity-free acceleration)
                val ix: Float; val iy: Float; val iz: Float
                if (usingLinear) {
                    // TYPE_LINEAR_ACCELERATION already has gravity removed by the hardware fusion
                    ix = raw[0]; iy = raw[1]; iz = raw[2]
                } else {
                    // TYPE_ACCELEROMETER: update EMA to track gravity, then subtract
                    gravityEma[0] = alpha * gravityEma[0] + (1 - alpha) * raw[0]
                    gravityEma[1] = alpha * gravityEma[1] + (1 - alpha) * raw[1]
                    gravityEma[2] = alpha * gravityEma[2] + (1 - alpha) * raw[2]
                    ix = raw[0] - gravityEma[0]
                    iy = raw[1] - gravityEma[1]
                    iz = raw[2] - gravityEma[2]
                }

                val magnitude = Math.sqrt((ix*ix + iy*iy + iz*iz).toDouble()).toFloat()

                // Log every candidate event so we can calibrate threshold via logcat
                // "D/BACK_TAP: mag=Xg" — check logcat while tapping to see real values
                if (magnitude > 0.5f * gFactor) {
                    Log.d("BACK_TAP", "Impulse detected: mag=${String.format("%.2f", magnitude / gFactor)}g | count=${backTapTimes.size}")
                }

                // 0.8g threshold — firm finger tap on glass back.
                // If you see logcat values above 0.5g but clips aren't saving, lower this.
                val TAP_THRESHOLD = 0.8f * gFactor

                if (magnitude > TAP_THRESHOLD) {
                    // 150ms cooldown: prevents a single tap's rebound registering as 2
                    val lastTap = backTapTimes.lastOrNull() ?: 0L
                    if (now - lastTap < 150) return

                    // Prune taps outside the 1200ms detection window
                    while (backTapTimes.isNotEmpty() && now - backTapTimes.first() > 1200) {
                        backTapTimes.removeFirst()
                    }
                    backTapTimes.addLast(now)

                    Log.d("BACK_TAP", "Tap ${backTapTimes.size}/3 counted (mag=${String.format("%.2f", magnitude / gFactor)}g)")

                    // Short haptic so the user FEELS each counted tap
                    try {
                        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION") vibrator?.vibrate(25)
                        }
                    } catch (_: Exception) {}

                    if (backTapTimes.size >= 3) {
                        backTapTimes.clear()
                        Log.d("BACK_TAP", "Triple-tap confirmed → saveClip()")

                        // False-positive guard: 3 auto-saves within 10s → pause 30s
                        while (backTapSaveTimes.isNotEmpty() && now - backTapSaveTimes.first() > 10_000) {
                            backTapSaveTimes.removeFirst()
                        }
                        backTapSaveTimes.addLast(now)
                        if (backTapSaveTimes.size >= 3) {
                            backTapSaveTimes.clear()
                            backTapPausedUntil = now + 30_000L
                            Log.w("BACK_TAP", "False-positive guard — pausing 30s")
                            Toast.makeText(this@ClipModeService,
                                "⚠️ Back-tap paused 30s (too many clips saved)",
                                Toast.LENGTH_LONG).show()
                            return
                        }

                        saveClip()
                    }
                }
            }
        }

        // SENSOR_DELAY_UI ≈ 16ms per event — fast enough to catch sharp tap spikes
        // without the overhead of SENSOR_DELAY_FASTEST (1ms). Lower than GAME (20ms).
        sm.registerListener(backTapListener, chosenSensor,
            SensorManager.SENSOR_DELAY_UI, Handler(Looper.getMainLooper()))
        Log.d("BACK_TAP", "Detector registered. Tap 3× the back firmly. Watch logcat for 'BACK_TAP' tag.")
    }



    private fun stopBackTapDetector() {
        backTapListener?.let { sensorManager?.unregisterListener(it) }
        backTapListener = null
        sensorManager = null
        backTapTimes.clear()
        Log.d("BACK_TAP", "Back-tap detector stopped")
    }

    private fun isDeviceOverheating(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val status = powerManager.currentThermalStatus
            return status >= PowerManager.THERMAL_STATUS_MODERATE
        }
        return false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "streamtwin_clip",
            "Clip Mode Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildClipNotification(): Notification {
        return NotificationCompat.Builder(this, "streamtwin_clip")
            .setContentTitle("Clip Mode Active")
            .setContentText("Double-tap the bubble to save a clip")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(CLIP_NOTIF_ID, buildCustomNotification(text))
        } catch (e: Exception) { /* best-effort */ }
    }

    private fun buildCustomNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "streamtwin_clip")
            .setContentTitle("Clip Mode Active")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showClipSavedNotification(fileName: String, uri: android.net.Uri) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingView = android.app.PendingIntent.getActivity(
            this, uri.hashCode(), viewIntent, 
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooserIntent = Intent.createChooser(shareIntent, "Share Clip")
        val pendingShare = android.app.PendingIntent.getActivity(
            this, uri.hashCode() + 1, chooserIntent, 
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, "streamtwin_clip")
            .setContentTitle("Clip Saved!")
            .setContentText(fileName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingView)
            .addAction(android.R.drawable.ic_menu_share, "Share", pendingShare)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notifManager.notify(System.currentTimeMillis().toInt(), notif)
    }

    override fun onDestroy() {
        // Call stopClipMode only if it hasn't been called yet (isStopping is false)
        if (!isStopping.get()) {
            stopClipMode(isExplicitStop = false)
        }
        isRunning = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "START_CLIP_MODE"
        const val ACTION_SAVE_CLIP = "SAVE_CLIP"
        const val ACTION_STOP = "STOP_CLIP_MODE"
        const val ACTION_SET_MUTE = "SET_CLIP_MUTE"
        const val ACTION_UPDATE_DURATION = "UPDATE_CLIP_DURATION"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_CLIP_DURATION = "clip_duration"
        const val EXTRA_MUTE = "clip_mute"
        const val EXTRA_FORCE_1080P = "force_1080p"
        const val CLIP_NOTIF_ID = 1002

        var isRunning = false
            internal set
    }
}
