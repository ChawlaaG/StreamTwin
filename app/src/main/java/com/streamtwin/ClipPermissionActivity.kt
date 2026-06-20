package com.streamtwin

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.streamtwin.service.ClipModeService
import com.streamtwin.data.local.StreamDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Transparent trampoline Activity that launches the system MediaProjection
 * consent dialog without bringing the StreamTwin UI to the foreground.
 *
 * Flow:
 *  1. FloatingOverlayService calls startActivity(ClipPermissionActivity)
 *  2. This Activity is transparent — the user still sees BGMI behind the dialog
 *  3. System shows the "Record screen / Share audio" chooser
 *  4. On consent: starts ClipModeService with the projection token and finishes
 *  5. On deny: shows a short toast and finishes
 *
 * Must be declared in AndroidManifest with Theme.Translucent so no app-switch
 * animation occurs and BGMI remains visually in the foreground.
 */
@AndroidEntryPoint
class ClipPermissionActivity : ComponentActivity() {

    @Inject lateinit var streamDataStore: StreamDataStore

    private companion object {
        const val TAG = "ClipPermissionActivity"
        const val REQ_MEDIA_PROJECTION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No content view — this is purely a permission bridge.
        // The window is fully transparent (set in manifest via Theme.Translucent).

        Log.d(TAG, "Requesting MediaProjection permission")
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQ_MEDIA_PROJECTION) {
            finish()
            return
        }

        if (resultCode == RESULT_OK && data != null) {
            Log.d(TAG, "MediaProjection granted — starting ClipModeService")
            val clipDuration = runBlocking {
                runCatching { streamDataStore.clipDurationFlow.first() }.getOrDefault(60)
            }
            val includeMic = runBlocking {
                runCatching { streamDataStore.clipIncludeMicFlow.first() }.getOrDefault(true)
            }
            val intent = Intent(this, ClipModeService::class.java).apply {
                action = ClipModeService.ACTION_START
                putExtra(ClipModeService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ClipModeService.EXTRA_PROJECTION_DATA, data)
                putExtra(ClipModeService.EXTRA_CLIP_DURATION, clipDuration)
                putExtra(ClipModeService.EXTRA_MUTE, !includeMic)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            Log.d(TAG, "MediaProjection denied")
            android.widget.Toast.makeText(
                this,
                "Screen capture permission needed for clipping",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // If already shown, just finish — the permission dialog is already up
        finish()
    }
}
