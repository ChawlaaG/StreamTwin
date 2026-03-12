package com.streamtwin.service

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.streamtwin.R

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        try {
            messages.add(message)
            if (messages.size > 100) {
                messages.removeAt(0)
                notifyItemRemoved(0)
            }
            notifyItemInserted(messages.size - 1)
        } catch (e: Exception) {
            android.util.Log.e("ChatAdapter", "Error adding message", e)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_overlay, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        holder.bind(msg)
    }

    override fun getItemCount(): Int = messages.size

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val senderText: TextView = itemView.findViewById(R.id.chatSender)
        private val messageText: TextView = itemView.findViewById(R.id.chatMessage)

        fun bind(chatMessage: ChatMessage) {
            senderText.text = chatMessage.sender
            messageText.text = chatMessage.message
        }
    }
}
