package com.streamtwin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.streamtwin.R
import com.streamtwin.data.local.StreamDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AppLaunchMonitorService : Service() {

    @Inject lateinit var streamDataStore: StreamDataStore

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var lastForegroundPackage: String? = null
    private var lastOverlayPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        serviceScope.launch {
            while (isActive) {
                var nextPollDelayMs = ACTIVE_POLL_INTERVAL_MS
                try {
                    val enabled = streamDataStore.autoClipEnabledFlow.first()
                    val selectedPackages = streamDataStore.autoClipPackagesFlow.first()

                    // If disabled or no apps selected, slow-poll and wait rather than
                    // stopping the service outright. The ViewModel will call stop() explicitly
                    // when the user disables the toggle. This prevents a race condition where
                    // the service kills itself before it finishes reading DataStore on startup.
                    if (!enabled || selectedPackages.isEmpty()) {
                        delay(IDLE_POLL_INTERVAL_MS)
                        continue
                    }

                    val foregroundPackage = getCurrentForegroundPackage()
                    if (foregroundPackage != null) {
                        if (foregroundPackage != lastForegroundPackage) {
                            Log.d(TAG, "Foreground package: $foregroundPackage")
                            lastForegroundPackage = foregroundPackage
                        }

                        if (foregroundPackage != packageName && selectedPackages.contains(foregroundPackage)) {
                            showClipOverlayFor(foregroundPackage)
                            nextPollDelayMs = SELECTED_APP_POLL_INTERVAL_MS
                        } else {
                            // If OUR OWN app temporarily becomes the foreground
                            // (e.g. the MediaProjection permission dialog is showing,
                            // or the user is in the StreamTwin main UI), do NOT remove
                            // the overlay — when the user returns to the game it should
                            // still be there. Only remove it when a genuinely different
                            // foreground app appears (launcher, another game, etc.).
                            val isOurApp = foregroundPackage == packageName
                            if (lastOverlayPackage != null && !isOurApp) {
                                Log.d(TAG, "Switching out of game to $foregroundPackage - removing overlay")
                                val stopIntent = Intent(this@AppLaunchMonitorService, FloatingOverlayService::class.java).apply {
                                    action = "STOP_OVERLAY"
                                }
                                startService(stopIntent)
                                lastOverlayPackage = null
                            } else if (isOurApp) {
                                Log.d(TAG, "StreamTwin in foreground temporarily — keeping overlay alive for ${lastOverlayPackage}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Monitor tick failed", e)
                }
                delay(nextPollDelayMs)
            }
        }
    }

    private fun getCurrentForegroundPackage(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        // Use a 60-second window (was 10s).
        // Games that have been open for >10s without another app switch were
        // completely invisible to the old code because the MOVE_TO_FOREGROUND
        // event had already aged out of the 10-second query window.
        val begin = end - 60_000L

        // ── Pass 1: event-based (real-time, most accurate) ────────────────────
        // Accept both MOVE_TO_FOREGROUND *and* ACTIVITY_RESUMED — different
        // Android versions and OEM ROMs report different event types for the
        // same foreground transition.
        val events = usm.queryEvents(begin, end)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTimestamp = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForeground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                               event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            if (isForeground && event.timeStamp >= latestTimestamp) {
                latestPackage = event.packageName
                latestTimestamp = event.timeStamp
            }
        }
        if (latestPackage != null) return latestPackage

        // ── Pass 2: interval-based fallback ───────────────────────────────────
        // If no events appeared in the last 60s (e.g. user opened the game
        // before the service started, or the device throttled event delivery),
        // fall back to `queryUsageStats`. This returns per-app aggregates with
        // `lastTimeUsed`, so we pick the app most recently in the foreground.
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
        return stats
            ?.filter { it.packageName != packageName } // exclude our own app
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    private suspend fun showClipOverlayFor(packageName: String) {
        if (StreamStateManager.isLive.value) return
        if (lastOverlayPackage == packageName && FloatingOverlayService.savedMode == OverlayMode.CLIP) return

        // Guard: overlay permission may have been revoked while the monitor
        // was running. Starting FloatingOverlayService without SYSTEM_ALERT_WINDOW
        // causes a silent failure (service starts but can't draw any window).
        // Bail out early and log — the user will need to re-grant the permission.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted — skipping clip overlay for $packageName")
            return
        }

        val clipDuration = streamDataStore.clipDurationFlow.first()
        val includeMic = streamDataStore.clipIncludeMicFlow.first()
        val overlayIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = "SHOW_OVERLAY"
            putExtra("OVERLAY_MODE", "CLIP")
            putExtra("CLIP_DURATION", clipDuration)
            putExtra("CLIP_MUTE", !includeMic)
        }

        try {
            ContextCompat.startForegroundService(this, overlayIntent)
            lastOverlayPackage = packageName
            Log.d(TAG, "Clip overlay shown for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show clip overlay for $packageName", e)
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Typed foreground start failed, retrying without type", e)
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Clip is watching selected games")
            .setContentText("StreamTwin will show clipping controls when a selected app opens.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Clip Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AppLaunchMonitor"
        private const val CHANNEL_ID = "AutoClipMonitorChannel"
        private const val NOTIFICATION_ID = 1004
        private const val ACTIVE_POLL_INTERVAL_MS = 1_500L
        // When no apps selected / disabled, check every 10s instead of stopping service.
        // The ViewModel calls stop() explicitly when user disables the toggle.
        private const val IDLE_POLL_INTERVAL_MS = 10_000L
        // Reduced from 8s → 3s: after a selected game is detected, we still
        // poll at this rate to catch when the user leaves and re-enters the game.
        private const val SELECTED_APP_POLL_INTERVAL_MS = 3_000L
        const val ACTION_STOP = "STOP_AUTO_CLIP_MONITOR"

        fun start(context: Context) {
            val intent = Intent(context, AppLaunchMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AppLaunchMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
