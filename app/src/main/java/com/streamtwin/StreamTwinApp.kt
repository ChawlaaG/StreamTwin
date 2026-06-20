package com.streamtwin

import android.app.Application
import android.content.Context
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class StreamTwinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val prefs = getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("cleared_for_v12", false)) {
            Log.d("StreamTwinApp", "First launch of v1.2.0 - Clearing old app data to prevent crashes.")
            try {
                // Clear all shared preferences except "app_updates"
                val sharedPrefsDir = File(applicationInfo.dataDir, "shared_prefs")
                if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) {
                    for (file in sharedPrefsDir.listFiles() ?: emptyArray()) {
                        val name = file.nameWithoutExtension
                        if (name != "app_updates") {
                            getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                            file.delete()
                        }
                    }
                }
                
                // Clear DataStore
                val datastoreDir = File(filesDir, "datastore")
                if (datastoreDir.exists()) {
                    datastoreDir.deleteRecursively()
                }
                
                // Clear Cache
                cacheDir?.deleteRecursively()
                
            } catch (e: Exception) {
                Log.e("StreamTwinApp", "Error clearing old data", e)
            } finally {
                prefs.edit().putBoolean("cleared_for_v12", true).apply()
            }
        }
    }
}
