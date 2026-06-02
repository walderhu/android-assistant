package com.assistant.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
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

    private enum class SendMode { MIC, CAMERA }
    private var sendMode = SendMode.MIC
    private var touchDownTime = 0L
    private var touchStartY = 0f
    private var isLocked = false
    private var recTimerText: android.widget.TextView? = null
    private var lockHintText: android.widget.TextView? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) handlePickedImage(uri)
    }

    private var pendingCameraUri: android.net.Uri? = null
    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingCameraUri != null) handlePickedImage(pendingCameraUri!!)
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
        adapter = MessageAdapter { msg, anchor -> showMessageActions(msg, anchor) }
        recycler.adapter = adapter

        if (!loadHistory()) {
            adapter.add(Message("Привет! Чем могу помочь?", isUser = false))
        }
        recycler.scrollToPosition(adapter.itemCount - 1)

        refreshSendIcon()
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshSendIcon() }
        })

        clip.setOnClickListener { pickImage.launch(androidx.activity.result.PickVisualMediaRequest()) }

        btnStop.setOnClickListener { stopAndSendVoice() }
        recTimerText = findViewById(R.id.recTimer)
        lockHintText = findViewById(R.id.lockHint)

        send.setOnTouchListener { v, event ->
            val text = edit.text.toString().trim()
            if (text.isNotEmpty()) {
                // Текст есть — обычная отправка
                if (event.action == MotionEvent.ACTION_UP) v.performClick()
                false
            } else when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownTime = System.currentTimeMillis()
                    touchStartY = event.rawY
                    isLocked = false
                    startVoiceRecording()
                    lockHintText?.text = "Сдвиньте вверх для фиксации"
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = touchStartY - event.rawY
                    if (dy > 120f && !isLocked) {
                        isLocked = true
                        lockHintText?.text = "Запись зафиксирована"
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val held = System.currentTimeMillis() - touchDownTime
                    if (isLocked) {
                        // Не останавливаем — пользователь отпустил после фиксации
                        true
                    } else if (held < 250) {
                        // Короткое нажатие — переключаем режим микрофон/камера
                        cancelVoice()
                        toggleSendMode()
                        true
                    } else {
                        // Обычная отправка голосового
                        stopAndSendVoice()
                        true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (!isLocked) cancelVoice()
                    true
                }
                else -> false
            }
        }
        btnCancel.setOnClickListener { cancelVoice() }
    }

    private fun toggleSendMode() {
        val send = findViewById<ImageButton>(R.id.btnSend)
        sendMode = if (sendMode == SendMode.MIC) SendMode.CAMERA else SendMode.MIC
        when (sendMode) {
            SendMode.MIC -> {
                send.setImageResource(R.drawable.ic_micro)
                send.contentDescription = "Голосовое сообщение"
            }
            SendMode.CAMERA -> {
                send.setImageResource(R.drawable.ic_camera)
                send.contentDescription = "Камера"
                launchCamera()
            }
        }
    }

    private fun launchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 43)
            return
        }
        try {
            val file = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            pendingCameraUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("Камера недоступна: ${e.message}")
        }
    }

    private fun refreshSendIcon() {
        val send = findViewById<ImageButton>(R.id.btnSend)
        val edit = findViewById<EditText>(R.id.editMessage)
        val hasText = edit.text.toString().trim().isNotEmpty()
        if (hasText) {
            send.setImageResource(R.drawable.ic_send)
            send.contentDescription = "Отправить"
        } else {
            when (sendMode) {
                SendMode.MIC -> {
                    send.setImageResource(R.drawable.ic_micro)
                    send.contentDescription = "Голосовое сообщение"
                }
                SendMode.CAMERA -> {
                    send.setImageResource(R.drawable.ic_camera)
                    send.contentDescription = "Камера"
                }
            }
        }
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
        exitRecording()
        refreshSendIconLocal()
        findViewById<EditText>(R.id.editMessage).requestFocus()

        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        val placeholder = Message("…", isUser = true, isVoice = true, isLoading = true)
        adapter.add(placeholder)
        recycler.scrollToPosition(adapter.itemCount - 1)

        lifecycleScope.launch {
            val orKey = BuildConfig.OPENROUTER_API_KEY
            if (orKey.isBlank()) {
                adapter.replace({ it.isLoading }, Message("⚠️ OPENROUTER_API_KEY не задан", isUser = false))
                recycler.scrollToPosition(adapter.itemCount - 1)
                return@launch
            }
            try {
                val text = withContext(Dispatchers.IO) {
                    TranscriptionClient.transcribe(orKey, file)
                }
                file.delete()
                if (text.isBlank()) {
                    adapter.replace({ it.isLoading }, Message("⚠️ Пустая транскрибация", isUser = false))
                } else {
                    adapter.replace({ it.isLoading }, Message(text, isUser = true, isVoice = true))
                    history.add("user" to text)
                    saveHistory()
                    requestBotReply()
                }
            } catch (e: Exception) {
                file.delete()
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                adapter.replace({ it.isLoading }, Message("⚠️ $msg", isUser = false))
            }
            recycler.scrollToPosition(adapter.itemCount - 1)
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
        } else if (requestCode == 43 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
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

    private fun showMessageActions(msg: Message, anchor: View) {
        if (msg.isLoading) return
        val text = msg.text
        if (text.isBlank()) return
        val popupView = layoutInflater.inflate(R.layout.popup_copy, null)
        val popup = android.widget.PopupWindow(
            popupView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        )
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.setOnDismissListener { /* no-op */ }
        popupView.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("message", text))
            toast("Скопировано")
            popup.dismiss()
        }
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val x = location[0]
        val y = location[1] + anchor.height
        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, x, y)
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
