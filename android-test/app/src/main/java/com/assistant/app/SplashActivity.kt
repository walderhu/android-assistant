package com.assistant.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Movie
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup

/**
 * Сплеш-активити: проигрывает loading.gif (через android.graphics.Movie),
 * затем запускает MainActivity. windowBackground = первый кадр GIF
 * (см. SplashTheme → splash_screen.xml), чтобы холодный старт не мигал
 * пустым экраном.
 */
class SplashActivity : Activity() {

    private lateinit var gif: SplashGifView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gif = SplashGifView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(gif)
        gif.start()

        // Переход в MainActivity: длительность GIF, минимум 1200мс.
        val durationMs = gif.movieDuration().coerceAtLeast(0)
        val delay = if (durationMs in 200..6000) durationMs.toLong() else 1500L
        gif.postDelayed({ goToMain() }, delay)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

/** View, которая проигрывает GIF из res/raw или res/drawable через android.graphics.Movie. */
class SplashGifView(ctx: Context) : View(ctx) {

    private val movie: Movie? = try {
        resources.openRawResource(R.drawable.loading).use { Movie.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    private var startTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 33L)  // ~30 fps
        }
    }

    fun movieDuration(): Int = movie?.duration() ?: 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = movie ?: return
        if (startTime == 0L) startTime = SystemClock.uptimeMillis()
        val rel = (SystemClock.uptimeMillis() - startTime).toInt()
        val dur = m.duration()
        val t = if (dur > 0) rel % dur else 0
        m.setTime(t)
        // Равномерный scale по меньшей стороне, центрирование.
        val mw = m.width().toFloat()
        val mh = m.height().toFloat()
        if (mw <= 0f || mh <= 0f) {
            m.draw(canvas, 0f, 0f)
            return
        }
        val scale = minOf(width / mw, height / mh)
        val dx = (width - mw * scale) / 2f
        val dy = (height - mh * scale) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        m.draw(canvas, 0f, 0f)
        canvas.restore()
    }

    fun start() { handler.post(tick) }
    fun stop() { handler.removeCallbacks(tick) }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
