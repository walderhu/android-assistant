package com.assistant.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TelegramActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telegram)

        val urlInput = findViewById<EditText>(R.id.urlInput)
        val secretInput = findViewById<EditText>(R.id.secretInput)
        val enabledSwitch = findViewById<SwitchCompat>(R.id.enabledSwitch)
        val statusText = findViewById<TextView>(R.id.statusText)
        val syncBtn = findViewById<Button>(R.id.syncBtn)

        urlInput.setText(TelegramBridge.serverUrl(this))
        secretInput.setText(TelegramBridge.syncSecret(this))
        enabledSwitch.isChecked = TelegramBridge.isEnabled(this)

        findViewById<android.view.View>(R.id.closeBtn).setOnClickListener { finish() }

        fun save() {
            TelegramBridge.setServerUrl(this, urlInput.text.toString())
            TelegramBridge.setSyncSecret(this, secretInput.text.toString())
            TelegramBridge.setEnabled(this, enabledSwitch.isChecked)
        }

        enabledSwitch.setOnCheckedChangeListener { _, _ -> save() }

        syncBtn.setOnClickListener {
            save()
            statusText.text = "синк…"
            syncBtn.isEnabled = false
            lifecycleScope.launch {
                val r = runCatching { TelegramBridge.sync(this@TelegramActivity) }
                    .getOrElse { TelegramBridge.SyncResult(0, it.message ?: "ошибка") }
                statusText.text = r.message
                syncBtn.isEnabled = true
                if (r.applied > 0) {
                    Toast.makeText(this@TelegramActivity, "Записей: ${r.applied}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
