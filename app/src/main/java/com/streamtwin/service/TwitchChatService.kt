package com.streamtwin.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.streamtwin.data.local.StreamTwinSecurePrefs
import com.streamtwin.data.repository.TwitchRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import okhttp3.*
import javax.inject.Inject

@AndroidEntryPoint
class TwitchChatService : Service() {

    @Inject
    lateinit var repository: TwitchRepository
    
    @Inject
    lateinit var securePreferences: StreamTwinSecurePrefs

    private val TAG = "TwitchChatService"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP_CHAT") {
            stopSelf()
            return START_NOT_STICKY
        }

        connectToChat()
        return START_STICKY
    }

    private fun connectToChat() {
        serviceScope.launch {
            val token = securePreferences.getAccessToken() ?: return@launch
            val user = repository.getCurrentUser().getOrNull() ?: return@launch
            val channelName = user.login.lowercase()

            val request = Request.Builder()
                .url("wss://irc-ws.chat.twitch.tv:443")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "IRC: Connected to WebSocket. Authenticating...")
                    webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                    webSocket.send("PASS oauth:$token")
                    webSocket.send("NICK $channelName")
                    webSocket.send("JOIN #$channelName")
                    Log.d(TAG, "IRC: Sent JOIN for #$channelName")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.v(TAG, "IRC RAWMESSAGE: $text")
                    handleIrcMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "IRC: Closing: $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "IRC: Failure", t)
                    // Auto-reconnect after 5 seconds
                    serviceScope.launch {
                        delay(5000)
                        connectToChat()
                    }
                }
            })
        }
    }

    private fun handleIrcMessage(rawMessage: String) {
        if (rawMessage.contains("PRIVMSG")) {
            try {
                // Example with tags: @color=#FFFFFF;display-name=User;... :user!user@user.tmi.twitch.tv PRIVMSG #channel :message
                val tags = if (rawMessage.startsWith("@")) rawMessage.substringBefore(" :").drop(1) else null
                val remaining = if (tags != null) rawMessage.substringAfter(" :") else rawMessage.drop(1)
                
                val prefix = remaining.substringBefore(" PRIVMSG ")
                val content = remaining.substringAfter(" PRIVMSG ").substringAfter(" :", "")
                
                if (content.isNotEmpty()) {
                    var displayName = prefix.substringBefore("!")
                    
                    // Try to get display-name from tags
                    tags?.split(";")?.forEach { tag ->
                        if (tag.startsWith("display-name=")) {
                            val value = tag.substringAfter("=")
                            if (value.isNotEmpty()) displayName = value
                        }
                    }
                    
                    Log.d(TAG, "IRC: Parsed message from $displayName: $content")
                    serviceScope.launch {
                        ChatStateManager.addMessage(displayName, content)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing IRC message: $rawMessage", e)
            }
        } else if (rawMessage.startsWith("PING")) {
            webSocket?.send("PONG :tmi.twitch.tv")
        } else if (rawMessage.contains(" 001 ")) {
            Log.d(TAG, "IRC: Authenticated successfully! (001 received)")
        } else if (rawMessage.contains(" JOIN ")) {
            Log.d(TAG, "IRC: Confirmed joined channel: $rawMessage")
        }
    }

    override fun onDestroy() {
        webSocket?.close(1000, "Service destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
