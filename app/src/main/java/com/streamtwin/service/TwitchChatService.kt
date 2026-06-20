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
    private val privMsgRegex = Regex("^(?:@([^ ]+) )?:([^ ]+) PRIVMSG #[^ ]+ :(.*)$")
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

    private var isConnecting = false
    private var channelRequested: String? = null
    private var reconnectAttempt = 0

    private fun connectToChat() {
        if (isConnecting || webSocket != null) {
            Log.d(TAG, "connectToChat: Already connected or connecting, skipping.")
            return
        }
        
        isConnecting = true
        serviceScope.launch {
            val token = securePreferences.getAccessToken() ?: run { isConnecting = false; return@launch }
            val user = repository.getCurrentUser().getOrNull() ?: run { isConnecting = false; return@launch }
            val channelName = user.login.lowercase()
            channelRequested = channelName

            val request = Request.Builder()
                .url("wss://irc-ws.chat.twitch.tv:443")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnecting = false
                    reconnectAttempt = 0
                    Log.d(TAG, "IRC: Connected to WebSocket. Authenticating...")
                    webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                    webSocket.send("PASS oauth:$token")
                    webSocket.send("NICK $channelName")
                    webSocket.send("JOIN #$channelName")
                    Log.d(TAG, "IRC: Sent JOIN for #$channelName")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.v(TAG, "IRC RAWMESSAGE: $text")
                    // IRC messages can be batched in one frame, separated by CRLF
                    text.split("\r\n").forEach { line ->
                        if (line.isNotEmpty()) {
                            handleIrcMessage(line)
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "IRC: Closing: $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "IRC: Failure", t)
                    this@TwitchChatService.webSocket = null
                    isConnecting = false
                    reconnectAttempt++
                    val backoff = minOf((1000 * Math.pow(2.0, reconnectAttempt.toDouble())).toLong(), 60000L)
                    // Auto-reconnect with exponential backoff (max 60 seconds)
                    serviceScope.launch {
                        delay(backoff)
                        connectToChat()
                    }
                }
            })
        }
    }

    private fun handleIrcMessage(rawMessage: String) {
        val matchResult = privMsgRegex.find(rawMessage)
        if (matchResult != null) {
            try {
                val tagsStr = matchResult.groups[1]?.value ?: ""
                val prefix = matchResult.groups[2]?.value ?: ""
                val content = matchResult.groups[3]?.value ?: ""
                
                if (content.isNotEmpty()) {
                    var displayName = prefix.substringBefore("!")
                    
                    // Try to get display-name from tags
                    if (tagsStr.isNotEmpty()) {
                        tagsStr.split(";").forEach { tag ->
                            if (tag.startsWith("display-name=")) {
                                val value = tag.substringAfter("=")
                                if (value.isNotEmpty()) displayName = value
                            }
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
