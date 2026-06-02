package com.assistant.app

import android.animation.ObjectAnimator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Message(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = true,
    val isLoading: Boolean = false,
    val imageUri: String? = null,
    val isVoice: Boolean = false
)

class MessageAdapter(
    private val onMessageClick: (Message, View) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<MessageAdapter.VH>() {
    private val items = mutableListOf<Message>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun add(m: Message) {
        items.add(m)
        notifyItemInserted(items.size - 1)
    }

    fun replace(predicate: (Message) -> Boolean, with: Message) {
        val idx = items.indexOfFirst(predicate)
        if (idx >= 0) {
            items[idx] = with
            notifyItemChanged(idx)
        }
    }

    fun remove(predicate: (Message) -> Boolean) {
        val idx = items.indexOfFirst(predicate)
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    private val animators = mutableMapOf<Long, ObjectAnimator>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: LinearLayout = v.findViewById(R.id.rowContainer)
        val botAvatar: ImageView = v.findViewById(R.id.botAvatar)
        val bubble: LinearLayout = v.findViewById(R.id.bubble)
        val text: TextView = v.findViewById(R.id.textMessage)
        val timestamp: TextView = v.findViewById(R.id.timestamp)
        val image: ImageView = v.findViewById(R.id.imageMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]

        holder.row.gravity = if (m.isUser) Gravity.END else Gravity.START
        holder.botAvatar.visibility = if (m.isUser) View.GONE else View.VISIBLE

        holder.bubble.setBackgroundResource(
            if (m.isUser) R.drawable.bubble_user else R.drawable.bubble_bot
        )

        val maxWidth = (holder.itemView.context.resources.displayMetrics.widthPixels * 0.78f).toInt()
        holder.bubble.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.UNSPECIFIED
        )
        if (holder.bubble.measuredWidth > maxWidth) {
            (holder.bubble.layoutParams as LinearLayout.LayoutParams).width = maxWidth
            holder.bubble.requestLayout()
        } else {
            (holder.bubble.layoutParams as LinearLayout.LayoutParams).width = ViewGroup.LayoutParams.WRAP_CONTENT
            holder.bubble.requestLayout()
        }

        holder.text.text = m.text
        val tag = if (m.isVoice) " · транскрибация" else ""
        holder.timestamp.text = timeFmt.format(Date(m.timestamp)) + tag
        if (m.imageUri != null) {
            holder.image.visibility = View.VISIBLE
            holder.image.setImageURI(android.net.Uri.parse(m.imageUri))
        } else {
            holder.image.visibility = View.GONE
        }

        stopAnimation(holder.itemId)
        if (m.isLoading) {
            holder.bubble.alpha = 1f
            val anim = ObjectAnimator.ofFloat(holder.bubble, "alpha", 0.25f, 1.0f).apply {
                duration = 500
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = LinearInterpolator()
            }
            animators[holder.itemId] = anim
            anim.start()
        } else {
            holder.bubble.alpha = 1f
        }

        holder.bubble.setOnClickListener { onMessageClick(m, holder.bubble) }
    }

    private fun stopAnimation(itemId: Long) {
        animators.remove(itemId)?.cancel()
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        stopAnimation(holder.itemId)
    }
}
