package com.streamtwin.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamtwin.R
import com.streamtwin.data.repository.TwitchRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class FloatingOverlayService : Service() {

    @Inject
    lateinit var repository: TwitchRepository

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: WindowManager.LayoutParams
    
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    
    private var isExpanded = false
    private var preExpandX = 0
    private var preExpandY = 0
    
    private var isMicMuted = false
    
    private val overlayScope = CoroutineScope(Dispatchers.Main + Job())
    private var chatPopupJob: Job? = null
    private var autoCollapseJob: Job? = null
    private var idleJob: Job? = null

    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
        startStatsUpdateLoop()
        observeChat()
    }

    private fun setupOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_live, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        windowManager.addView(overlayView, params)

        val bubbleContainer = overlayView.findViewById<View>(R.id.bubbleContainer)
        bubbleContainer.setOnTouchListener { v, event -> handleTouch(event, v) }

        val minimizeBtn = overlayView.findViewById<View>(R.id.minimizeButton)
        minimizeBtn.setOnClickListener { toggleMode(false) }

        val stopBtn = overlayView.findViewById<View>(R.id.overlayStopButton)
        stopBtn.setOnClickListener {
            showStopConfirmationDialog()
        }

        val micBtn = overlayView.findViewById<ImageView>(R.id.btnOverlayMic)
        micBtn.setOnClickListener {
            isMicMuted = !isMicMuted
            val action = if (isMicMuted) "MUTE_AUDIO" else "UNMUTE_AUDIO"
            startService(Intent(this, StreamingService::class.java).apply { this.action = action })
            
            // Update icon and color
            if (isMicMuted) {
                micBtn.setImageResource(R.drawable.ic_mic_off)
                micBtn.setBackgroundResource(R.drawable.bg_red_circle)
            } else {
                micBtn.setImageResource(R.drawable.ic_mic)
                micBtn.setBackgroundResource(R.drawable.bg_purple_pill)
            }
        }

        val editBtn = overlayView.findViewById<View>(R.id.quickEditButton)
        editBtn.setOnClickListener { showEditMetadataDialog() }
        
        val clipBtn = overlayView.findViewById<View>(R.id.clipButton)
        clipBtn.setOnClickListener {
            val clipIntent = Intent(this, StreamingService::class.java).apply { action = "CREATE_CLIP" }
            startService(clipIntent)
            toggleMode(false)
        }

        val seek = overlayView.findViewById<SeekBar>(R.id.transparencySeekBar)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                overlayView.findViewById<View>(R.id.expandedRoot).alpha = p / 100f
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        setupChatList()
        resetIdleTimer()
    }

    private fun setupChatList() {
        val recyclerView = overlayView.findViewById<RecyclerView>(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter()
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
    }

    private fun observeChat() {
        overlayScope.launch {
            ChatStateManager.messages.collect { msg ->
                chatAdapter.addMessage(msg)
                if (!isExpanded) {
                    showChatPopup(msg)
                }
            }
        }
        
        overlayScope.launch {
            ChatStateManager.unreadCount.collect { count ->
                updateUnreadBadge(count)
            }
        }
    }

    private fun handleTouch(event: MotionEvent, view: View): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                wakeBubble()
                resetAutoCollapse()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = initialX + (event.rawX - initialTouchX).toInt()
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                try { windowManager.updateViewLayout(overlayView, params) } catch (e: Exception) {}
                return true
            }
            MotionEvent.ACTION_UP -> {
                val diffX = abs(event.rawX - initialTouchX)
                val diffY = abs(event.rawY - initialTouchY)
                if (diffX < 15 && diffY < 15) {
                    toggleMode(true)
                }
                return true
            }
        }
        return false
    }

    private fun toggleMode(expand: Boolean) {
        isExpanded = expand
        val bubbleContainer = overlayView.findViewById<View>(R.id.bubbleContainer)
        val expandedRoot = overlayView.findViewById<View>(R.id.expandedRoot)

        if (expand) {
            idleJob?.cancel()
            preExpandX = params.x
            preExpandY = params.y
            
            bubbleContainer.alpha = 1.0f
            bubbleContainer.scaleX = 1.0f
            bubbleContainer.scaleY = 1.0f
            bubbleContainer.visibility = View.GONE
            expandedRoot.visibility = View.VISIBLE
            ChatStateManager.clearUnread()
            
            adjustParamsForExpansion()
            updateFocusable(true)
            resetAutoCollapse()
        } else {
            autoCollapseJob?.cancel()
            params.x = preExpandX
            params.y = preExpandY
            bubbleContainer.visibility = View.VISIBLE
            expandedRoot.visibility = View.GONE
            
            try { windowManager.updateViewLayout(overlayView, params) } catch (e: Exception) {}
            updateFocusable(false)
            resetIdleTimer()
        }
    }

    private fun adjustParamsForExpansion() {
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val expandedWidth = (280 * dm.density).toInt()
        val expandedHeight = (420 * dm.density).toInt()

        if (params.x + expandedWidth > screenWidth) params.x = screenWidth - expandedWidth - 20
        if (params.y + expandedHeight > screenHeight) params.y = screenHeight - expandedHeight - 20
        if (params.x < 0) params.x = 20
        if (params.y < 0) params.y = 20

        try { windowManager.updateViewLayout(overlayView, params) } catch (e: Exception) {}
    }

    private fun updateFocusable(focusable: Boolean) {
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try { windowManager.updateViewLayout(overlayView, params) } catch (e: Exception) {}
    }

    private fun showChatPopup(message: ChatMessage) {
        chatPopupJob?.cancel()
        val popup = overlayView.findViewById<View>(R.id.chatPopup) ?: return
        val sender = overlayView.findViewById<TextView>(R.id.popupSender)
        val msgText = overlayView.findViewById<TextView>(R.id.popupMessage)

        sender.text = message.sender
        msgText.text = message.message
        
        val (shiftX, shiftY) = adjustPopupPosition(popup)
        params.x += shiftX
        params.y += shiftY
        
        popup.visibility = View.VISIBLE
        
        try { windowManager.updateViewLayout(overlayView, params) } catch (e: Exception) {}

        chatPopupJob = overlayScope.launch {
            delay(5000)
            popup.visibility = View.GONE
            params.x -= shiftX
            params.y -= shiftY
            
            val bubbleContainer = overlayView.findViewById<View>(R.id.bubbleContainer)
            val bParams = bubbleContainer.layoutParams as FrameLayout.LayoutParams
            bParams.setMargins(0, 0, 0, 0)
            bubbleContainer.layoutParams = bParams
            
            try { windowManager.updateViewLayout(overlayView, params) } catch (e: Exception) {}
        }
    }

    private fun adjustPopupPosition(popup: View): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val bubbleSize = (60 * dm.density).toInt()
        val popupWidth = (200 * dm.density).toInt()
        val defaultChatHeight = (80 * dm.density).toInt()
        
        val layoutParams = popup.layoutParams as FrameLayout.LayoutParams
        val bubbleParams = overlayView.findViewById<View>(R.id.bubbleContainer).layoutParams as FrameLayout.LayoutParams
        
        var shiftX = 0
        var shiftY = 0
        
        bubbleParams.gravity = Gravity.TOP or Gravity.START
        bubbleParams.setMargins(0, 0, 0, 0)
        
        if (params.x > screenWidth / 2) {
            layoutParams.gravity = Gravity.TOP or Gravity.START
            layoutParams.setMargins(0, 0, 0, 0)
            val margin = popupWidth + 8
            bubbleParams.setMargins(margin, 0, 0, 0)
            shiftX = -margin
        } else {
            layoutParams.gravity = Gravity.TOP or Gravity.START
            layoutParams.setMargins(bubbleSize + 8, 0, 0, 0)
            bubbleParams.setMargins(0, 0, 0, 0)
            shiftX = 0
        }
        
        if (params.y > screenHeight * 0.7) {
            val currentChatHeight = popup.height.takeIf { it > 0 } ?: defaultChatHeight
            val marginY = currentChatHeight + 8
            bubbleParams.topMargin += marginY
            shiftY = -marginY
        }
        
        overlayView.findViewById<View>(R.id.bubbleContainer).layoutParams = bubbleParams
        popup.layoutParams = layoutParams
        
        return Pair(shiftX, shiftY)
    }

    private fun resetAutoCollapse() {
        if (!isExpanded) return
        autoCollapseJob?.cancel()
        autoCollapseJob = overlayScope.launch {
            delay(15000)
            toggleMode(false)
        }
    }

    private fun wakeBubble() {
        if (isExpanded) return
        val bubbleContainer = overlayView.findViewById<View>(R.id.bubbleContainer) ?: return
        bubbleContainer.alpha = 1.0f
        bubbleContainer.scaleX = 1.0f
        bubbleContainer.scaleY = 1.0f
        resetIdleTimer()
    }

    private fun resetIdleTimer() {
        if (isExpanded) return
        idleJob?.cancel()
        idleJob = overlayScope.launch {
            delay(15000)
            val bubbleContainer = overlayView.findViewById<View>(R.id.bubbleContainer) ?: return@launch
            bubbleContainer.animate().alpha(0.4f).scaleX(0.7f).scaleY(0.7f).setDuration(500).start()
        }
    }

    private fun showEditMetadataDialog() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val dialogParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.6f
            gravity = Gravity.CENTER
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_metadata, null)
        val editTitle = dialogView.findViewById<EditText>(R.id.editStreamTitle)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        editTitle.setText(StreamStateManager.streamTitle.value)

        btnCancel.setOnClickListener { try { windowManager.removeView(dialogView) } catch (e: Exception) {} }

        btnSave.setOnClickListener {
            val newTitle = editTitle.text.toString()
            overlayScope.launch {
                val user = repository.getCurrentUser().getOrNull()
                if (user != null) {
                    val result = repository.updateStreamMetadata(user.id, newTitle, null)
                    if (result.isSuccess) {
                        StreamStateManager.saveStreamTitle(newTitle)
                        try { windowManager.removeView(dialogView) } catch (e: Exception) {}
                    }
                }
            }
        }

        windowManager.addView(dialogView, dialogParams)
    }

    private fun updateUnreadBadge(count: Int) {
        val badge = overlayView.findViewById<TextView>(R.id.unreadBadge)
        if (count > 0) {
            badge.visibility = View.VISIBLE
            badge.text = if (count > 9) "9+" else count.toString()
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun showStopConfirmationDialog() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val dialogParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.6f
            gravity = Gravity.CENTER
        }

        // Programmatic Layout to be safe since we don't know the exact XML of dialog_edit_metadata.
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_overlay_glass)
            setPadding(64, 64, 64, 64)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        
        val title = TextView(this).apply {
            text = "End Stream?"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        val desc = TextView(this).apply {
            text = "Are you sure you want to stop streaming?"
            setTextColor(android.graphics.Color.LTGRAY)
            textSize = 14f
            setPadding(0, 16, 0, 48)
        }
        
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        
        val btnCancel = Button(this).apply {
            text = "Cancel"
            setBackgroundResource(R.drawable.bg_purple_pill)
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { try { windowManager.removeView(container) } catch (e: Exception) {} }
        }
        
        val btnConfirm = Button(this).apply {
            text = "End Stream"
            setBackgroundResource(R.drawable.bg_red_circle)
            setTextColor(android.graphics.Color.WHITE)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(16, 0, 0, 0)
            layoutParams = params
            setOnClickListener {
                val stopIntent = Intent(this@FloatingOverlayService, StreamingService::class.java).apply { action = "STOP_STREAM" }
                startService(stopIntent)
                try { windowManager.removeView(container) } catch (e: Exception) {}
            }
        }
        
        btnRow.addView(btnCancel)
        btnRow.addView(btnConfirm)
        
        container.addView(title)
        container.addView(desc)
        container.addView(btnRow)

        windowManager.addView(container, dialogParams)
    }

    private fun startStatsUpdateLoop() {
        overlayScope.launch {
            while (isActive) {
                updateStats()
                delay(5000)
            }
        }
    }

    private fun updateStats() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        overlayView.findViewById<TextView>(R.id.batteryText)?.text = "$batteryPct%"
        overlayView.findViewById<TextView>(R.id.overlayStreamTitle).text = StreamStateManager.streamTitle.value
        overlayView.findViewById<TextView>(R.id.overlayBitrateText)?.text = "${StreamStateManager.currentBitrate.value} kbps"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlayScope.cancel()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (e: Exception) {}
        }
        super.onDestroy()
    }
}
