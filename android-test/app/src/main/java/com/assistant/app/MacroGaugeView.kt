package com.assistant.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Маленький полукруг-индикатор для макро-карточки. */
class MacroGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Float = 0f
    var gaugeColor: Int = 0xFF4CAF50.toInt()
    var trackColor: Int = 0xFF2A2A2A.toInt()

    private val ringStroke = 7f
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStroke
        strokeCap = Paint.Cap.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w / 2 + 8)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val radius = (w / 2f) - ringStroke / 2f
        val cy = h - radius
        val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        ringPaint.color = trackColor
        canvas.drawArc(arcRect, 180f, 180f, false, ringPaint)

        ringPaint.color = gaugeColor
        val sweep = 180f * progress.coerceIn(0f, 1f)
        canvas.drawArc(arcRect, 180f, sweep, false, ringPaint)
    }
}
