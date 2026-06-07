package com.assistant.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Полноэкранный просмотр crash-лога. Открывается из дровера. */
class CrashLogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_log)

        val text = CrashLog.read(this)
        val logText = findViewById<EditText>(R.id.logText)
        logText.setText(if (text.isBlank()) "Пусто." else text)

        findViewById<View>(R.id.closeBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.shareBtn).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, "Crash log"))
        }
        findViewById<View>(R.id.clearBtn).setOnClickListener {
            CrashLog.clear(this)
            logText.setText("Очищено.")
            Toast.makeText(this, "Лог очищен", Toast.LENGTH_SHORT).show()
        }
    }
}
