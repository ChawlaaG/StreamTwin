package com.streamtwin.ui.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamtwin.data.local.SecureStorageManager
import com.streamtwin.data.local.StreamDataStore
import com.streamtwin.data.repository.TwitchRepository
import com.streamtwin.domain.model.TwitchUser
import com.streamtwin.domain.usecase.GetTwitchUserUseCase
import com.streamtwin.service.AppLaunchMonitorService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: TwitchRepository,
    private val getTwitchUserUseCase: GetTwitchUserUseCase,
    private val streamDataStore: StreamDataStore,
    private val secureStorageManager: SecureStorageManager
) : ViewModel() {

    private val _twitchUser = MutableStateFlow<TwitchUser?>(null)
    val twitchUser: StateFlow<TwitchUser?> = _twitchUser

    val kickEnabled = streamDataStore.kickEnabledFlow
    val youtubeEnabled = streamDataStore.youtubeEnabledFlow
    val twitchEnabled = streamDataStore.twitchEnabledFlow

    val kickStreamKey = MutableStateFlow(secureStorageManager.kickStreamKey)
    val kickRtmpUrl = MutableStateFlow(secureStorageManager.kickRtmpUrl)

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess

    val isYoutubeConnected = MutableStateFlow(!secureStorageManager.youtubeAccessToken.isNullOrEmpty())

    val isKickConnected: StateFlow<Boolean> = combine(kickStreamKey, kickRtmpUrl) { key, url ->
        key.isNotEmpty() && url.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoClipEnabled = streamDataStore.autoClipEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoClipPackages = streamDataStore.autoClipPackagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _installedApps = MutableStateFlow<List<LaunchableAppInfo>>(emptyList())
    val installedApps: StateFlow<List<LaunchableAppInfo>> = _installedApps

    init {
        refreshTwitchUser()
        loadInstalledApps()
    }

    private fun refreshTwitchUser() {
        viewModelScope.launch {
            _twitchUser.value = getTwitchUserUseCase().getOrNull()
        }
    }

    fun handleTwitchAuthToken(token: String) {
        viewModelScope.launch {
            repository.saveAccessToken(token)
            streamDataStore.saveTwitchEnabled(true)
            refreshTwitchUser()
        }
    }

    fun handleYouTubeAuthToken(token: String) {
        viewModelScope.launch {
            secureStorageManager.youtubeAccessToken = token
            streamDataStore.saveYoutubeEnabled(true)
            isYoutubeConnected.value = true
        }
    }

    fun setKickEnabled(enabled: Boolean) {
        viewModelScope.launch {
            streamDataStore.saveKickEnabled(enabled)
        }
    }

    fun setYoutubeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            streamDataStore.saveYoutubeEnabled(enabled)
        }
    }

    fun setTwitchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            streamDataStore.saveTwitchEnabled(enabled)
        }
    }

    fun updateKickKey(key: String) {
        kickStreamKey.value = key
    }

    fun updateKickUrl(url: String) {
        kickRtmpUrl.value = url
    }

    fun saveSettings() {
        viewModelScope.launch {
            secureStorageManager.kickStreamKey = kickStreamKey.value
            secureStorageManager.kickRtmpUrl = kickRtmpUrl.value
            _saveSuccess.value = true
            kotlinx.coroutines.delay(2000)
            _saveSuccess.value = false
        }
    }

    fun disconnect() {
        repository.logout()
    }

    fun setAutoClipEnabled(enabled: Boolean) {
        viewModelScope.launch {
            streamDataStore.saveAutoClipEnabled(enabled)
            val hasSelectedApps = streamDataStore.autoClipPackagesFlow.first().isNotEmpty()
            if (enabled && hasSelectedApps && hasUsageAccess()) {
                AppLaunchMonitorService.start(appContext)
            } else {
                AppLaunchMonitorService.stop(appContext)
            }
        }
    }

    fun toggleAutoClipPackage(packageName: String) {
        viewModelScope.launch {
            val current = streamDataStore.autoClipPackagesFlow.first().toMutableSet()
            if (current.contains(packageName)) current.remove(packageName) else current.add(packageName)
            streamDataStore.saveAutoClipPackages(current)

            if (autoClipEnabled.value && current.isNotEmpty() && hasUsageAccess()) {
                AppLaunchMonitorService.start(appContext)
            } else if (current.isEmpty()) {
                streamDataStore.saveAutoClipEnabled(false)
                AppLaunchMonitorService.stop(appContext)
            }
        }
    }

    fun refreshAutoClipMonitor() {
        viewModelScope.launch {
            if (autoClipEnabled.value && autoClipPackages.value.isNotEmpty() && hasUsageAccess()) {
                AppLaunchMonitorService.start(appContext)
            }
        }
    }

    fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val pm = appContext.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { resolveInfo ->
                    val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                    if (packageName == appContext.packageName) return@mapNotNull null
                    val label = resolveInfo.loadLabel(pm)?.toString()?.trim().orEmpty()
                    if (label.isEmpty()) return@mapNotNull null
                    val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                    LaunchableAppInfo(
                        label = label,
                        packageName = packageName,
                        isGame = isGameApp(appInfo)
                    )
                }
                .distinctBy { it.packageName }
                .sortedWith(compareBy<LaunchableAppInfo> { !it.isGame }.thenBy { it.label.lowercase() })
            _installedApps.value = apps
        }
    }

    private fun isGameApp(appInfo: ApplicationInfo?): Boolean {
        if (appInfo == null) return false
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                appInfo.category == ApplicationInfo.CATEGORY_GAME
    }
}

data class LaunchableAppInfo(
    val label: String,
    val packageName: String,
    val isGame: Boolean = false
)
