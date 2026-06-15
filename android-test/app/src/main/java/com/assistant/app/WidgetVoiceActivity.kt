package com.assistant.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Запись ГС из виджета → транскрибация → агент питания. */
class WidgetVoiceActivity : AppCompatActivity() {

    private lateinit var voiceRecorder: VoiceRecorder
    private var timerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_voice)
        setFinishOnTouchOutside(false)
        voiceRecorder = VoiceRecorder(this)

        val meal = NutritionController.mealForHour(java.time.LocalTime.now().hour)
        findViewById<TextView>(R.id.widgetVoiceTitle).text = "«$meal» — говорите"
        findViewById<TextView>(R.id.widgetVoiceCancel).setOnClickListener {
            voiceRecorder.cancel()
            finish()
        }
        findViewById<ImageButton>(R.id.widgetVoiceStop).setOnClickListener { stopAndSend(meal) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 50)
            return
        }
        startRecording()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 50 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startRecording() {
        try {
            voiceRecorder.start()
            val timer = findViewById<TextView>(R.id.widgetVoiceTimer)
            timerJob = lifecycleScope.launch {
                while (isActive && voiceRecorder.isRecording) {
                    val ms = voiceRecorder.durationMs
                    timer.text = "%d:%02d".format(Locale.US, ms / 60000, (ms / 1000) % 60)
                    delay(200)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Микрофон: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun stopAndSend(meal: String) {
        timerJob?.cancel()
        val file = voiceRecorder.stop()
        if (file == null || !file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Пустая запись", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.widgetVoiceTitle).text = "Обработка…"
        findViewById<ImageButton>(R.id.widgetVoiceStop).isEnabled = false

        lifecycleScope.launch {
            try {
                val orKey = BuildConfig.OPENROUTER_API_KEY
                val groqKey = BuildConfig.GROQ_API_KEY
                val yandexKey = BuildConfig.YANDEX_API_KEY
                val yandexFolderId = BuildConfig.YANDEX_FOLDER_ID
                if (orKey.isBlank() && groqKey.isBlank() && yandexKey.isBlank()) {
                    Toast.makeText(this@WidgetVoiceActivity, "API ключи не заданы", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
                val voiceModel = Settings.get(this@WidgetVoiceActivity, Settings.Category.VOICE)
                val text = withContext(Dispatchers.IO) {
                    TranscriptionClient.transcribe(
                        orKey, groqKey, yandexKey, yandexFolderId, file, voiceModel
                    )
                }
                file.delete()
                val reply = NutritionAgentRunner.sendFoodText(this@WidgetVoiceActivity, text, meal)
                Toast.makeText(this@WidgetVoiceActivity, reply, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                file.delete()
                Toast.makeText(
                    this@WidgetVoiceActivity,
                    e.message ?: "Ошибка",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                finish()
            }
        }
    }
}
