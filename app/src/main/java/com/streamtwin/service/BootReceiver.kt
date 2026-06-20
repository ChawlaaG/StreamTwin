package com.streamtwin.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.streamtwin.data.local.StreamDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts [AppLaunchMonitorService] after device boot (or after the process
 * was killed by the OS) if the user had Auto Clip enabled with apps selected.
 *
 * Declared in AndroidManifest with RECEIVE_BOOT_COMPLETED permission.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var streamDataStore: StreamDataStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.d(TAG, "Boot completed — checking if AutoClip monitor should restart")

        // Use goAsync so we can wait for the DataStore reads before the receiver is killed
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope.launch {
            try {
                val enabled = streamDataStore.autoClipEnabledFlow.first()
                val packages = streamDataStore.autoClipPackagesFlow.first()
                val hasOverlayPerm = android.provider.Settings.canDrawOverlays(context)

                if (enabled && packages.isNotEmpty() && hasOverlayPerm) {
                    Log.d(TAG, "Auto Clip is ON with ${packages.size} apps — starting monitor")
                    AppLaunchMonitorService.start(context)
                } else {
                    Log.d(TAG, "Auto Clip inactive (enabled=$enabled, apps=${packages.size}, overlay=$hasOverlayPerm) — skipping")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during boot restart", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
