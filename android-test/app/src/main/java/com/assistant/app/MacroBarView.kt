package com.assistant.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Столбик макронутриента: подпись сверху, шкала по центру, значение снизу.
 * Используется горизонтальным рядом из 3 штук для Б/Ж/У.
 */
class MacroBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var label: String = ""
    var current: Float = 0f
    var total: Float = 100f
    var barColor: Int = 0xFFFF5555.toInt()
    var trackColor: Int = 0x33FFFFFF.toInt()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        isFakeBoldText = true
        color = 0xFFE6E6E6.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = 0xFFAAAAAA.toInt()
        textAlign = Paint.Align.CENTER
    }

    private val barHeight = 6f
    private val barRadius = 3f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, 88)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        canvas.drawText(label, w / 2, 30f, labelPaint)
        val barTop = 48f
        val barRect = RectF(0f, barTop, w, barTop + barHeight)
        canvas.drawRoundRect(barRect, barRadius, barRadius, barPaint.apply { color = trackColor })
        val ratio = if (total > 0) (current / total).coerceIn(0f, 1f) else 0f
        val fillRect = RectF(0f, barTop, w * ratio, barTop + barHeight)
        canvas.drawRoundRect(fillRect, barRadius, barRadius, barPaint.apply { color = barColor })
        canvas.drawText(
            "${current.toInt()}/${total.toInt()} г",
            w / 2, barTop + barHeight + 24f, valuePaint
        )
    }
}
