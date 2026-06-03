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
import android.view.GestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
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

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: MessageAdapter
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var repo: ChatRepository
    private lateinit var state: ChatRepository.State
    private lateinit var drawer: DrawerLayout

    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var waveform: WaveformView
    private lateinit var recordingPanel: LinearLayout
    private lateinit var normalInput: LinearLayout
    private lateinit var swipeDetector: GestureDetector
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
        repo = ChatRepository(this)
        state = repo.load()

        drawer = findViewById(R.id.drawerLayout)
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        val edit = findViewById<EditText>(R.id.editMessage)
        val send = findViewById<ImageButton>(R.id.btnSend)
        val clip = findViewById<ImageButton>(R.id.btnClip)
        val burger = findViewById<View>(R.id.btnBurger)
        val recyclerChats = findViewById<RecyclerView>(R.id.recyclerChats)
        val btnNewChat = findViewById<ImageButton>(R.id.btnNewChat)
        val btnCloseDrawer = findViewById<ImageButton>(R.id.btnCloseDrawer)
        normalInput = findViewById(R.id.normalInput)
        recordingPanel = findViewById(R.id.recordingPanel)
        waveform = findViewById(R.id.waveform)
        val btnStop = findViewById<ImageButton>(R.id.btnStopRec)
        val btnCancel = findViewById<ImageButton>(R.id.btnCancel)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = MessageAdapter { msg, anchor -> showMessageActions(msg, anchor) }
        recycler.adapter = adapter

        chatAdapter = ChatAdapter(
            onClick = { id -> switchToChat(id); drawer.closeDrawers() },
            onMenu = { chat, anchor -> showChatMenu(chat, anchor) }
        )
        recyclerChats.layoutManager = LinearLayoutManager(this)
        recyclerChats.adapter = chatAdapter
        chatAdapter.submit(state.chats, state.currentId)

        renderCurrentChat()
        refreshChatDrawer()

        burger.setOnClickListener { drawer.openDrawer(android.view.Gravity.START) }
        btnCloseDrawer.setOnClickListener { drawer.closeDrawers() }
        btnNewChat.setOnClickListener {
            repo.createChat(state)
            renderCurrentChat()
            refreshChatDrawer()
            drawer.closeDrawers()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            drawer.closeDrawers()
            toast("Настройки скоро появятся")
        }

        // Свайп вправо из любой точки → открыть дровер
        val slopPx = ViewConfiguration.get(this).scaledTouchSlop
        val minFlingVx = ViewConfiguration.get(this).scaledMinimumFlingVelocity.toFloat()
        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (e1 == null) return false
                if (Math.abs(dy) > Math.abs(dx) * 1.2f) return false
                val totalDx = e2.x - e1.x
                if (totalDx > slopPx * 2.5f) {
                    drawer.openDrawer(android.view.Gravity.START)
                    return true
                }
                return false
            }
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (e1 == null) return false
                if (Math.abs(vy) > Math.abs(vx) * 1.2f) return false
                if (vx > minFlingVx && e2.x - e1.x > slopPx) {
                    drawer.openDrawer(android.view.Gravity.START)
                    return true
                }
                return false
            }
        })

        refreshSendIcon()
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshSendIcon() }
        })

        clip.setOnClickListener { pickImage.launch(androidx.activity.result.PickVisualMediaRequest()) }

        send.setOnClickListener {
            val text = edit.text.toString().trim()
            if (text.isNotEmpty()) sendText(text)
        }

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

    private fun currentChat(): ChatRepository.Chat? =
        state.chats.firstOrNull { it.id == state.currentId }

    private fun renderCurrentChat() {
        adapter.clear()
        val chat = currentChat()
        if (chat == null || chat.messages.isEmpty()) {
            adapter.add(Message("Привет! Чем могу помочь?", isUser = false))
        } else {
            for (m in chat.messages) adapter.add(m)
        }
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        recycler.scrollToPosition(adapter.itemCount - 1)
    }

    private fun refreshChatDrawer() {
        chatAdapter.submit(repo.sortedChats(state), state.currentId)
    }

    private fun switchToChat(id: String) {
        if (state.currentId == id) return
        state.currentId = id
        repo.save(state)
        renderCurrentChat()
        refreshChatDrawer()
    }

    private fun confirmDeleteChat(id: String) {
        val chat = state.chats.firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(this)
            .setTitle("Удалить диалог?")
            .setMessage("«${chat.title}» будет удалён безвозвратно.")
            .setPositiveButton("Удалить") { _, _ ->
                repo.deleteChat(state, id)
                renderCurrentChat()
                refreshChatDrawer()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showChatMenu(chat: ChatRepository.Chat, anchor: View) {
        val view = layoutInflater.inflate(R.layout.popup_chat_menu, null, false)
        val popup = android.widget.PopupWindow(
            view,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF181818.toInt()))
        popup.isOutsideTouchable = true
        // tint иконок
        val tint = 0xFF8A8A8A.toInt()
        for (i in 0 until (view as android.view.ViewGroup).childCount) {
            val row = view.getChildAt(i) as? android.view.ViewGroup ?: continue
            val icon = row.getChildAt(0) as? android.widget.ImageView ?: continue
            icon.setColorFilter(tint)
        }
        view.findViewById<View>(R.id.menu_rename).setOnClickListener {
            popup.dismiss(); renameChat(chat.id, chat.title)
        }
        val pinText = view.findViewById<android.widget.TextView>(R.id.menu_toggle_pin_text)
        pinText.text = if (chat.pinned) "Открепить" else "Закрепить"
        view.findViewById<View>(R.id.menu_toggle_pin).setOnClickListener {
            popup.dismiss(); repo.togglePin(state, chat.id); refreshChatDrawer()
        }
        view.findViewById<View>(R.id.menu_delete).setOnClickListener {
            popup.dismiss(); confirmDeleteChat(chat.id)
        }
        // позиционируем: правый край попапа = правый край анкора, чуть ниже
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val xOff = -(view.measuredWidth - anchor.width)
        val yOff = anchor.height / 2
        popup.showAsDropDown(anchor, xOff, yOff)
    }

    private fun renameChat(id: String, currentTitle: String) {
        val edit = EditText(this).apply {
            setText(currentTitle)
            setSelection(text.length)
            setTextColor(0xFFE6E6E6.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("Переименовать чат")
            .setView(edit)
            .setPositiveButton("Ок") { _, _ ->
                repo.updateTitle(state, id, edit.text.toString().trim())
                refreshChatDrawer()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun sendText(text: String) {
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        val edit = findViewById<EditText>(R.id.editMessage)
        adapter.add(Message(text, isUser = true))
        repo.appendMessage(state, state.currentId, "user", text)
        refreshChatDrawer()
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
                val chat = currentChat()
                val history = chat?.messages
                    ?.filter { !it.isLoading }
                    ?.map { (if (it.isUser) "user" else "assistant") to it.text }
                    ?: emptyList()
                val reply = OpenRouterClient.send(history)
                adapter.replace({ it.isLoading }, Message(reply, isUser = false))
                repo.appendMessage(state, state.currentId, "assistant", reply)
                refreshChatDrawer()
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
                    repo.appendMessage(state, state.currentId, "user", text)
                    refreshChatDrawer()
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
        repo.appendMessage(state, state.currentId, "user", text)
        refreshChatDrawer()
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
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
        repo.appendMessage(state, state.currentId, "user", prompt)
        refreshChatDrawer()
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
                repo.appendMessage(state, state.currentId, "assistant", reply)
                refreshChatDrawer()
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

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
