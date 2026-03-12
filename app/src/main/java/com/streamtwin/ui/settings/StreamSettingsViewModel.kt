package com.streamtwin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamtwin.data.local.StreamDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamSettingsViewModel @Inject constructor(
    private val streamDataStore: StreamDataStore
) : ViewModel() {

    fun updateStreamQuality(quality: String) {
        viewModelScope.launch {
            streamDataStore.saveStreamQuality(quality)
        }
    }
}
