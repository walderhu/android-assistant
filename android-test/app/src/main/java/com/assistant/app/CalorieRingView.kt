package com.assistant.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Зелёный полукруг калоража. View имеет фиксированное соотношение сторон
 * 2:1 (ширина:высота), арка занимает весь view. Текст сидит в верхней
 * половине арки. Послойная отрисовка: glow → track → progress → text.
 */
class CalorieRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var current: Int = 0
    var total: Int = 2000

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 100f
        isFakeBoldText = true
        color = 0xFFFFFFFF.toInt()
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        color = 0xFFFFFFFF.toInt()
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        color = 0xFF8A8A8A.toInt()
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
    }

    /** View всегда 2:1 (ширина:высота), арка заполняет весь view. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w / 2)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = w / 4f
        val radius = w / 2f - 4f
        val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        drawGlow(canvas, arcRect)
        drawTrack(canvas, arcRect)
        drawProgress(canvas, arcRect)
        drawText(canvas, cx, cy)
    }

    private fun drawGlow(canvas: Canvas, arcRect: RectF) {
        // Слой 1: широкий матовый glow
        ringPaint.color = 0x0D4CAF50
        ringPaint.strokeWidth = 100f
        canvas.drawArc(arcRect, 180f, 180f, false, ringPaint)
        // Слой 2: средний glow
        ringPaint.color = 0x224CAF50
        ringPaint.strokeWidth = 60f
        canvas.drawArc(arcRect, 180f, 180f, false, ringPaint)
        // Слой 3: ближний glow
        ringPaint.color = 0x444CAF50
        ringPaint.strokeWidth = 36f
        canvas.drawArc(arcRect, 180f, 180f, false, ringPaint)
    }

    private fun drawTrack(canvas: Canvas, arcRect: RectF) {
        ringPaint.color = 0x22FFFFFF.toInt()
        ringPaint.strokeWidth = 32f
        canvas.drawArc(arcRect, 180f, 180f, false, ringPaint)
    }

    private fun drawProgress(canvas: Canvas, arcRect: RectF) {
        val progress = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f
        val sweep = 180f * progress
        ringPaint.color = 0xFFFFFFFF.toInt()
        ringPaint.strokeWidth = 32f
        canvas.drawArc(arcRect, 180f, sweep, false, ringPaint)
    }

    private fun drawText(canvas: Canvas, cx: Float, cy: Float) {
        // «1831» + «ккал» — на одной базовой линии, в центре верхней половины арки
        val numText = current.toString()
        val numW = numberPaint.measureText(numText)
        val unitW = unitPaint.measureText("ккал")
        val totalW = numW + 14f + unitW
        val baseX = cx - totalW / 2f
        val textY = cy + 4f

        // Тени
        canvas.drawText(numText, baseX + 2f, textY + 2f, shadowPaint)
        canvas.drawText("ккал", baseX + numW + 14f + 2f, textY + 2f, shadowPaint)

        // Основной текст
        canvas.drawText(numText, baseX, textY, numberPaint)
        canvas.drawText("ккал", baseX + numW + 14f, textY, unitPaint)

        // «Осталось на сегодня» ниже, тоже в верхней половине арки
        val hintY = cy + 48f
        canvas.drawText("Осталось на сегодня", cx + 2f, hintY + 2f, shadowPaint)
        canvas.drawText("Осталось на сегодня", cx, hintY, hintPaint)
    }
}
