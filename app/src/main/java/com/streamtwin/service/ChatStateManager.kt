package com.streamtwin.service

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatMessage(
    val sender: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

object ChatStateManager {
    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 100)
    val messages: SharedFlow<ChatMessage> = _messages.asSharedFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    suspend fun addMessage(sender: String, message: String) {
        Log.d("ChatStateManager", "New message added: $sender: $message")
        _messages.emit(ChatMessage(sender, message))
        _unreadCount.value += 1
    }

    fun clearUnread() {
        _unreadCount.value = 0
    }
}
