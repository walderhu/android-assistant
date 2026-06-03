package com.assistant.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Тайл — фото (или первая плитка «камера»). Весь тайл = toggle выбора.
 * Долгое нажатие = открыть превью. Битмапы грузятся асинхронно — без
 * блокировки UI при открытии шторки.
 */
class AttachAdapter(
    private val scope: CoroutineScope,
    private val onCamera: () -> Unit,
    private val onPreview: (Uri) -> Unit,
    private val isSelected: (Uri) -> Boolean,
    private val onToggle: (Uri) -> Unit
) : RecyclerView.Adapter<AttachAdapter.VH>() {

    data class Item(val uri: Uri?, val isCamera: Boolean = false)

    private val items = mutableListOf<Item>()
    private val cameraCount = 1

    fun submit(uris: List<Uri>) {
        items.clear()
        repeat(cameraCount) { items += Item(null, isCamera = true) }
        uris.forEach { items += Item(it, isCamera = false) }
        notifyDataSetChanged()
    }

    fun append(uris: List<Uri>) {
        val start = items.size
        uris.forEach { items += Item(it, isCamera = false) }
        notifyItemRangeInserted(start, uris.size)
    }

    fun photoCount(): Int = items.count { !it.isCamera }
    fun allUris(): List<Uri> = items.mapNotNull { if (it.isCamera) null else it.uri }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.tileImage)
        val tint: View = v.findViewById(R.id.tileSelectionTint)
        val cameraGroup: View = v.findViewById(R.id.tileCameraGroup)
        val checkBg: ImageView = v.findViewById(R.id.tileCheckBg)
        val checkIcon: ImageView = v.findViewById(R.id.tileCheckIcon)
        var currentUri: Uri? = null
        var currentJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_attach_tile, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        if (item.isCamera) {
            holder.currentJob?.cancel()
            holder.currentUri = null
            holder.img.setImageDrawable(null)
            holder.img.setBackgroundColor(0xFF2E5C8A.toInt())
            holder.tint.visibility = View.GONE
            holder.cameraGroup.visibility = View.VISIBLE
            holder.checkBg.visibility = View.GONE
            holder.checkIcon.visibility = View.GONE
            holder.itemView.setOnClickListener { onCamera() }
            holder.itemView.setOnLongClickListener(null)
            return
        }
        val uri = item.uri!!
        if (holder.currentUri != uri) {
            holder.currentJob?.cancel()
            holder.currentUri = uri
        }
        val sel = isSelected(uri)
        holder.cameraGroup.visibility = View.GONE
        holder.tint.visibility = if (sel) View.VISIBLE else View.GONE
        holder.checkBg.visibility = View.VISIBLE
        holder.checkIcon.visibility = if (sel) View.VISIBLE else View.GONE

        val cached = PhotoCache.thumb(holder.itemView.context, uri, 256)
        if (cached != null) {
            holder.img.setImageBitmap(cached)
        } else {
            holder.img.setImageDrawable(null)
            holder.img.setBackgroundColor(0xFF2B2B2B.toInt())
            holder.currentJob = scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    PhotoCache.thumb(holder.itemView.context, uri, 256)
                }
                if (bmp != null && holder.currentUri == uri) {
                    holder.img.setImageBitmap(bmp)
                }
            }
        }
        holder.itemView.setOnClickListener { onToggle(uri) }
        holder.itemView.setOnLongClickListener { onPreview(uri); true }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.currentJob?.cancel()
        holder.currentJob = null
        holder.currentUri = null
    }
}
