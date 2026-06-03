package com.assistant.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val onClick: (String) -> Unit,
    private val onMenu: (ChatRepository.Chat, View) -> Unit
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<ChatRepository.Chat>()
    private var currentId: String = ""
    private val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    fun submit(chats: List<ChatRepository.Chat>, currentId: String) {
        items.clear()
        items.addAll(chats)
        this.currentId = currentId
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.chatTitle)
        val preview: TextView = v.findViewById(R.id.chatPreview)
        val pin: ImageView = v.findViewById(R.id.pinIndicator)
        val more: ImageButton = v.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.title.text = c.title.ifBlank { "Чат" }
        val last = c.messages.lastOrNull { !it.isLoading }
        val ts = if (c.updatedAt > 0) " · " + timeFmt.format(Date(c.updatedAt)) else ""
        val previewText = when {
            last == null -> "Пустой диалог"
            last.isUser -> "Вы: ${last.text}"
            else -> last.text
        }
        holder.preview.text = previewText + ts
        holder.itemView.alpha = if (c.id == currentId) 1f else 0.7f
        holder.title.setTypeface(
            holder.title.typeface,
            if (c.pinned) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        holder.pin.visibility = if (c.pinned) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(c.id) }
        holder.more.setOnClickListener { onMenu(c, it) }
    }
}
