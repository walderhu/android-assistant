package com.assistant.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FC3F7")
        strokeWidth = 4f.dp
        strokeCap = Paint.Cap.ROUND
    }
    private val maxBars = 60
    private val amplitudes = ArrayDeque<Int>()
    private val gap = 4f.dp
    private val minBar = 4f.dp
    private val maxBar = 60f.dp

    fun pushAmplitude(raw: Int) {
        // raw is 0..32767 from MediaRecorder.getMaxAmplitude()
        val normalized = min(raw, 32767).coerceAtLeast(1)
        val db = (20.0 * kotlin.math.log10(normalized / 32767.0)).toFloat()
        // map -40..0 dB → 0..1
        val v = ((db + 40f) / 40f).coerceIn(0f, 1f)
        amplitudes.addLast((v * 1000).toInt())
        while (amplitudes.size > maxBars) amplitudes.removeFirst()
        invalidate()
    }

    fun reset() {
        amplitudes.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val list = amplitudes.toList()
        val startX = width - (list.size * (barPaint.strokeWidth + gap))
        list.forEachIndexed { i, a ->
            val v = a / 1000f
            val h = minBar + (maxBar - minBar) * v
            val x = startX + i * (barPaint.strokeWidth + gap)
            canvas.drawLine(x, cy - h / 2, x, cy + h / 2, barPaint)
        }
    }

    private val Float.dp: Float
        get() = this * resources.displayMetrics.density
}
