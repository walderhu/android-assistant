package com.assistant.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private val adapter = MessageAdapter()
    private val history = mutableListOf<Pair<String, String>>()

    private val prefs by lazy { getSharedPreferences("chat", Context.MODE_PRIVATE) }

    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var waveform: WaveformView
    private lateinit var recordingPanel: LinearLayout
    private lateinit var normalInput: LinearLayout
    private lateinit var recordingLabel: TextView
    private var amplitudeJob: Job? = null
    private var recordedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        voiceRecorder = VoiceRecorder(this)

        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        val edit = findViewById<EditText>(R.id.editMessage)
        val send = findViewById<ImageButton>(R.id.btnSend)
        val clip = findViewById<ImageButton>(R.id.btnClip)
        normalInput = findViewById(R.id.normalInput)
        recordingPanel = findViewById(R.id.recordingPanel)
        waveform = findViewById(R.id.waveform)
        recordingLabel = findViewById(R.id.recordingLabel)
        val btnStop = findViewById<ImageButton>(R.id.btnStopRec)
        val btnCancel = findViewById<ImageButton>(R.id.btnCancel)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        if (!loadHistory()) {
            adapter.add(Message("Привет! Чем могу помочь?", isUser = false))
        }
        recycler.scrollToPosition(adapter.itemCount - 1)

        fun refreshSendIcon() {
            val hasText = edit.text.toString().trim().isNotEmpty()
            send.setImageResource(if (hasText) R.drawable.ic_send else R.drawable.ic_micro)
            send.contentDescription = if (hasText) "Отправить" else "Голосовое сообщение"
        }
        refreshSendIcon()
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshSendIcon() }
        })

        clip.setOnClickListener { toast("Прикрепить (заглушка)") }

        send.setOnClickListener {
            val text = edit.text.toString().trim()
            if (text.isEmpty()) {
                startVoiceRecording()
            } else {
                sendText(text)
            }
        }

        btnStop.setOnClickListener { stopAndSendVoice() }
        btnCancel.setOnClickListener { cancelVoice() }
    }

    private fun sendText(text: String) {
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        val edit = findViewById<EditText>(R.id.editMessage)
        adapter.add(Message(text, isUser = true))
        history.add("user" to text)
        saveHistory()
        edit.text.clear()
        recycler.scrollToPosition(adapter.itemCount - 1)
        requestBotReply()
    }

    private fun requestBotReply() {
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        val loading = Message("●", isUser = false, isLoading = true)
        adapter.add(loading)
        recycler.scrollToPosition(adapter.itemCount - 1)
        lifecycleScope.launch {
            try {
                val reply = OpenRouterClient.send(history)
                adapter.replace({ it.isLoading }, Message(reply, isUser = false))
                history.add("assistant" to reply)
                saveHistory()
            } catch (e: Exception) {
                adapter.replace({ it.isLoading },
                    Message("Ошибка: ${e.message ?: e.javaClass.simpleName}", isUser = false))
            }
            recycler.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        try {
            recordedFile = voiceRecorder.start()
            normalInput.visibility = View.GONE
            recordingPanel.visibility = View.VISIBLE
            waveform.reset()
            recordingLabel.text = "Говорите..."
            amplitudeJob = lifecycleScope.launch {
                val start = System.currentTimeMillis()
                while (isActive) {
                    val amp = voiceRecorder.maxAmplitude()
                    waveform.pushAmplitude(amp)
                    val secs = (System.currentTimeMillis() - start) / 1000
                    recordingLabel.text = "Говорите... ${secs}s"
                    delay(50)
                }
            }
        } catch (e: Exception) {
            toast("Не удалось начать запись: ${e.message}")
        }
    }

    private fun stopAndSendVoice() {
        amplitudeJob?.cancel()
        val file = voiceRecorder.stop()
        if (file == null || !file.exists() || file.length() == 0L) {
            toast("Запись пустая")
            exitRecording()
            return
        }
        recordedFile = null
        recordingLabel.text = "Транскрибирую..."
        toast("Файл: ${file.length() / 1024}KB, отправляю в Groq...")
        lifecycleScope.launch {
            val groqKey = BuildConfig.GROQ_API_KEY
            if (groqKey.isBlank()) {
                addBotMessage("Ошибка: GROQ_API_KEY не задан (проверь ../.env и пересобери)")
                exitRecording()
                return@launch
            }
            try {
                val text = withContext(Dispatchers.IO) {
                    GroqTranscriptionClient.transcribe(groqKey, file)
                }
                file.delete()
                exitRecording()
                if (text.isBlank()) {
                    addBotMessage("Пустая транскрибация")
                    return@launch
                }
                addUserMessage("🎤 $text")
                requestBotReply()
            } catch (e: Exception) {
                file.delete()
                exitRecording()
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                addBotMessage("Ошибка транскрибации: $msg")
            }
        }
    }

    private fun addUserMessage(text: String) {
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        adapter.add(Message(text, isUser = true))
        history.add("user" to text)
        saveHistory()
        recycler.scrollToPosition(adapter.itemCount - 1)
    }

    private fun addBotMessage(text: String) {
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        adapter.add(Message(text, isUser = false))
        recycler.scrollToPosition(adapter.itemCount - 1)
    }

    private fun cancelVoice() {
        amplitudeJob?.cancel()
        voiceRecorder.cancel()
        recordedFile = null
        exitRecording()
    }

    private fun exitRecording() {
        normalInput.visibility = View.VISIBLE
        recordingPanel.visibility = View.GONE
        waveform.reset()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceRecording()
        } else {
            toast("Нужен доступ к микрофону")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        amplitudeJob?.cancel()
        voiceRecorder.cancel()
    }

    private fun saveHistory() {
        val arr = JSONArray()
        for ((role, text) in history) {
            arr.put(JSONObject().put("r", role).put("t", text))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory(): Boolean {
        val raw = prefs.getString("history", null) ?: return false
        return try {
            val arr = JSONArray(raw)
            history.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(o.getString("r") to o.getString("t"))
                adapter.add(Message(o.getString("t"), isUser = o.getString("r") == "user"))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
