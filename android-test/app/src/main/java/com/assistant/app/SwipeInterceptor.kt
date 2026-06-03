package com.assistant.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout

/**
 * LinearLayout, перехватывающий свайп вверх/вправо-налево от детей
 * (через onInterceptTouchEvent). Используется как контейнер для поля
 * ввода: дети получают тапы (можно печатать), свайпы уходят вверх
 * по иерархии и ловятся здесь.
 */
class SwipeInterceptor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onSwipeUp: (() -> Unit)? = null
    var onSwipeRightToLeft: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var swipeHandled = false
    private val slopPx = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                swipeHandled = false
                // DOWN не перехватываем — дети должны получить тап
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeHandled) return true
                val totalDx = ev.rawX - downX
                val totalDy = ev.rawY - downY
                val absDx = Math.abs(totalDx)
                val absDy = Math.abs(totalDy)
                // вверх
                if (absDx < absDy * 1.3f && totalDy < -slopPx * 2.5f) {
                    swipeHandled = true
                    onSwipeUp?.invoke()
                    return true
                }
                // вправо налево
                if (absDy < absDx * 1.3f && totalDx < -slopPx * 2.5f) {
                    swipeHandled = true
                    onSwipeRightToLeft?.invoke()
                    return true
                }
            }
        }
        return false
    }
}
