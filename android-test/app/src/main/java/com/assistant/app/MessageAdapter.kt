package com.assistant.app

import android.animation.ObjectAnimator
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Message(
    val text: String,
    val isUser: Boolean,
    val isLoading: Boolean = false
)

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {
    private val items = mutableListOf<Message>()

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
        val text: TextView = v.findViewById(R.id.textMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.text.text = m.text
        val params = holder.text.layoutParams as ViewGroup.MarginLayoutParams
        params.marginStart = if (m.isUser) 80 else 0
        params.marginEnd = if (m.isUser) 0 else 80
        holder.text.layoutParams = params

        holder.text.setBackgroundColor(
            when {
                m.isUser -> Color.parseColor("#6750A4")
                m.isLoading -> Color.parseColor("#E6E0EF")
                else -> Color.parseColor("#F1ECF7")
            }
        )
        holder.text.setTextColor(
            when {
                m.isUser -> Color.WHITE
                m.isLoading -> Color.parseColor("#6750A4")
                else -> Color.parseColor("#1D1B20")
            }
        )

        stopAnimation(holder.itemId)
        if (m.isLoading) {
            holder.text.alpha = 1f
            val anim = ObjectAnimator.ofFloat(holder.text, "alpha", 0.25f, 1.0f).apply {
                duration = 500
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = LinearInterpolator()
            }
            animators[holder.itemId] = anim
            anim.start()
        } else {
            holder.text.alpha = 1f
        }
    }

    private fun stopAnimation(itemId: Long) {
        animators.remove(itemId)?.cancel()
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        stopAnimation(holder.itemId)
    }
}
