package com.assistant.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: MessageAdapter
    private val history = mutableListOf<Pair<String, String>>()

    private val prefs by lazy { getSharedPreferences("chat", Context.MODE_PRIVATE) }

    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var waveform: WaveformView
    private lateinit var recordingPanel: LinearLayout
    private lateinit var normalInput: LinearLayout
    private var amplitudeJob: Job? = null
    private var recordedFile: File? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) handlePickedImage(uri)
    }

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
        val btnStop = findViewById<ImageButton>(R.id.btnStopRec)
        val btnCancel = findViewById<ImageButton>(R.id.btnCancel)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = MessageAdapter { msg -> showMessageActions(msg) }
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

        clip.setOnClickListener { pickImage.launch(androidx.activity.result.PickVisualMediaRequest()) }

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
            amplitudeJob = lifecycleScope.launch {
                while (isActive) {
                    val amp = voiceRecorder.maxAmplitude()
                    waveform.pushAmplitude(amp)
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
            exitRecording()
            return
        }
        recordedFile = null
        lifecycleScope.launch {
            val orKey = BuildConfig.OPENROUTER_API_KEY
            if (orKey.isBlank()) {
                addBotMessage("Ошибка: OPENROUTER_API_KEY не задан")
                exitRecording()
                return@launch
            }
            try {
                val text = withContext(Dispatchers.IO) {
                    TranscriptionClient.transcribe(orKey, file)
                }
                file.delete()
                exitRecording()
                if (text.isBlank()) {
                    addBotMessage("Пустая транскрибация")
                    return@launch
                }
                addUserMessage(text, isVoice = true)
                requestBotReply()
            } catch (e: Exception) {
                file.delete()
                exitRecording()
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                val errMsg = Message("⚠️ $msg", isUser = false, timestamp = System.currentTimeMillis())
                adapter.add(errMsg)
                findViewById<RecyclerView>(R.id.recyclerMessages)
                    .scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    private fun addUserMessage(text: String, isVoice: Boolean = false) {
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        adapter.add(Message(text, isUser = true, isVoice = isVoice))
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

    private fun handlePickedImage(uri: Uri) {
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        val edit = findViewById<EditText>(R.id.editMessage)
        val caption = edit.text.toString().trim().ifBlank { "Опиши это изображение" }
        edit.text.clear()
        refreshSendIconLocal()

        val cached = copyToCache(uri) ?: run { toast("Не удалось загрузить фото"); return }
        val prompt = caption
        val userMsg = Message(prompt, isUser = true, imageUri = cached.absolutePath)
        adapter.add(userMsg)
        history.add("user" to prompt)
        saveHistory()
        recycler.scrollToPosition(adapter.itemCount - 1)

        val loading = Message("●", isUser = false, isLoading = true)
        adapter.add(loading)
        recycler.scrollToPosition(adapter.itemCount - 1)

        lifecycleScope.launch {
            try {
                val bytes = cached.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val reply = OpenRouterClient.describeImage(prompt, b64, mime)
                cached.delete()
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

    private fun copyToCache(uri: Uri): File? {
        return try {
            val out = File(cacheDir, "img_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun refreshSendIconLocal() {
        val send = findViewById<ImageButton>(R.id.btnSend)
        val edit = findViewById<EditText>(R.id.editMessage)
        val hasText = edit.text.toString().trim().isNotEmpty()
        send.setImageResource(if (hasText) R.drawable.ic_send else R.drawable.ic_micro)
    }

    private fun showMessageActions(msg: Message) {
        if (msg.isLoading) return
        val text = msg.text
        if (text.isBlank()) return
        val popup = androidx.appcompat.widget.PopupMenu(this, findViewById<RecyclerView>(R.id.recyclerMessages))
        popup.menu.add(0, 1, 0, "Копировать")
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("message", text))
                toast("Скопировано")
            }
            true
        }
        popup.show()
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
