package com.assistant.app

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Мини-диалог с полем ввода — из виджета, без открытия приложения. */
class WidgetQuickActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_quick)
        setFinishOnTouchOutside(true)

        val meal = NutritionController.mealForHour(java.time.LocalTime.now().hour)
        val title = findViewById<TextView>(R.id.widgetQuickTitle)
        val input = findViewById<EditText>(R.id.widgetQuickInput)
        title.text = "Добавить в «$meal»"
        input.hint = "Например: овсянка 200 г"

        if (intent.action == ACTION_ADD) {
            input.requestFocus()
            input.postDelayed({ showKeyboard(input) }, 120)
        }

        findViewById<TextView>(R.id.widgetQuickCancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.widgetQuickSend).setOnClickListener { submit(input, meal) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit(input, meal)
                true
            } else false
        }
    }

    private fun submit(input: EditText, meal: String) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Введите текст", Toast.LENGTH_SHORT).show()
            return
        }
        input.isEnabled = false
        lifecycleScope.launch {
            try {
                val reply = NutritionAgentRunner.sendFoodText(this@WidgetQuickActivity, text, meal)
                Toast.makeText(this@WidgetQuickActivity, reply, Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                input.isEnabled = true
                Toast.makeText(
                    this@WidgetQuickActivity,
                    e.message ?: "Ошибка",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showKeyboard(et: EditText) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
    }

    companion object {
        const val ACTION_TEXT = "com.assistant.app.WIDGET_TEXT"
        const val ACTION_ADD = "com.assistant.app.WIDGET_ADD"
    }
}
