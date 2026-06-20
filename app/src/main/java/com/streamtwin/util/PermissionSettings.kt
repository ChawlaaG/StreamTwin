package com.streamtwin.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

object PermissionSettings {
    fun openOverlayPermission(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")

        // Build a priority-ordered list of intents. The first one whose
        // activity resolves on this device will be launched.
        val candidates = mutableListOf<Intent>()

        // ── MIUI / HyperOS (Xiaomi, Redmi, POCO) ─────────────────────────────
        // On MIUI, `ACTION_MANAGE_OVERLAY_PERMISSION` resolves successfully but
        // opens the *list* of all apps, not the StreamTwin toggle. The dedicated
        // MIUI permission editor activity is the only reliable deep-link.
        // We add this unconditionally — if the device isn't MIUI, it will just
        // promptly throw an ActivityNotFoundException and move to the next intent.
        candidates += Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )
            putExtra("extra_pkgname", context.packageName)
        }
        // HyperOS / MIUI 14+ uses a different activity path
        candidates += Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
            )
            putExtra("extra_pkgname", context.packageName)
        }

        // ── Stock Android / Samsung / OnePlus / others ────────────────────────
        // On API 23+, this intent with a package URI should open the per-app
        // overlay toggle directly on stock Android and most OEM ROMs.
        candidates += Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)

        // ── Fallbacks ─────────────────────────────────────────────────────────
        // App Info page — user can find overlay from there in 1 tap.
        candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        // Bare overlay list — last resort if all else fails.
        candidates += Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

        context.startFirstAvailableSettingsActivity(*candidates.toTypedArray())
    }

    fun openUsageAccess(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        context.startFirstAvailableSettingsActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = packageUri
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        )
    }

    private fun Context.startFirstAvailableSettingsActivity(vararg intents: Intent) {
        // IMPORTANT: Do NOT use resolveActivity() to pre-filter intents.
        // On Android 11+ (API 30+), resolveActivity() returns null for any
        // package not declared in <queries> — including MIUI's security center.
        // Explicit intents (those with setClassName) are exempt from this
        // restriction at *launch* time, so we try each intent directly and
        // catch ActivityNotFoundException to move to the next candidate.
        if (this !is Activity) {
            intents.forEach { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }

        for (intent in intents) {
            try {
                startActivity(intent)
                return // launched successfully — stop here
            } catch (_: Exception) {
                // ActivityNotFoundException or SecurityException — try next candidate
            }
        }

        Toast.makeText(this, "Unable to open Android settings for this permission.", Toast.LENGTH_LONG).show()
    }
}

