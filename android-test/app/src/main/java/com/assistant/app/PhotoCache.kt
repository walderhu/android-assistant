package com.assistant.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Двухуровневый кеш превью + фоновая предзагрузка.
 * thumbCache — мелкие для грида и панели выбранных.
 * fullCache — большие для диалога превью.
 */
object PhotoCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val thumbCache: LruCache<Uri, Bitmap> = run {
        val maxBytes = (Runtime.getRuntime().maxMemory() / 8).toInt()
        object : LruCache<Uri, Bitmap>(maxBytes) {
            override fun sizeOf(key: Uri, value: Bitmap): Int = value.byteCount
        }
    }
    private val fullCache: LruCache<Uri, Bitmap> = run {
        val maxBytes = (Runtime.getRuntime().maxMemory() / 4).toInt()
        object : LruCache<Uri, Bitmap>(maxBytes) {
            override fun sizeOf(key: Uri, value: Bitmap): Int = value.byteCount
        }
    }

    /** Запустить фоновую загрузку превью (256dp) для списка URI. */
    fun preloadThumbs(context: Context, uris: List<Uri>) {
        val cr = context.contentResolver
        for (uri in uris) {
            if (thumbCache.get(uri) != null) continue
            scope.launch {
                loadInto(cr, uri, 256)?.let { thumbCache.put(uri, it) }
            }
        }
    }

    /** Синхронно: вернуть thumb из кеша или загрузить и положить. */
    fun thumb(context: Context, uri: Uri, reqSize: Int = 256): Bitmap? {
        thumbCache.get(uri)?.let { return it }
        val bmp = loadInto(context.contentResolver, uri, reqSize) ?: return null
        thumbCache.put(uri, bmp)
        return bmp
    }

    /** Синхронно: вернуть full из кеша или загрузить и положить. */
    fun full(context: Context, uri: Uri, reqSize: Int = 2048): Bitmap? {
        fullCache.get(uri)?.let { return it }
        val bmp = loadInto(context.contentResolver, uri, reqSize) ?: return null
        fullCache.put(uri, bmp)
        return bmp
    }

    private fun loadInto(cr: ContentResolver, uri: Uri, reqSize: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val sample = sampleSize(opts, reqSize)
            // RGB_565 — вдвое меньше памяти и быстрее декод, хватает для превью
            val opts2 = BitmapFactory.Options()
                .apply { inSampleSize = sample }
                .apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) }
        } catch (e: Exception) { null }
    }

    private fun sampleSize(opts: BitmapFactory.Options, req: Int): Int {
        val h = opts.outHeight
        val w = opts.outWidth
        var s = 1
        if (h > req || w > req) {
            val hh = h / 2
            val hw = w / 2
            while (hh / s >= req && hw / s >= req) s *= 2
        }
        return s
    }
}
