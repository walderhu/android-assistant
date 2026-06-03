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

class PreviewPagerAdapter(
    private val uris: List<Uri>,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PreviewPagerAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.previewImage)
        var currentUri: Uri? = null
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_preview_page, parent, false)
        return VH(v)
    }

    override fun getItemCount() = uris.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val uri = uris[position]
        if (holder.currentUri != uri) {
            holder.job?.cancel()
            holder.currentUri = uri
        }
        val bmp = PhotoCache.full(holder.itemView.context, uri, 2048)
        if (bmp != null) {
            holder.img.setImageBitmap(bmp)
        } else {
            holder.img.setImageDrawable(null)
            holder.img.setBackgroundColor(0xFF000000.toInt())
            holder.job = scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    PhotoCache.full(holder.itemView.context, uri, 2048)
                }
                if (loaded != null && holder.currentUri == uri) {
                    holder.img.setImageBitmap(loaded)
                }
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.job?.cancel()
        holder.job = null
        holder.currentUri = null
    }
}
