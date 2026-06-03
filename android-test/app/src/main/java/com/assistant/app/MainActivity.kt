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

    private enum class SendMode { MIC }
    private val sendMode = SendMode.MIC
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

    private val requestImagesPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showAttachSheet()
        else pickImage.launch(androidx.activity.result.PickVisualMediaRequest())
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
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
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

        // фоновая предзагрузка первых 100 фото в LruCache
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val uris = loadRecentImages(0, 100)
            PhotoCache.preloadThumbs(this@MainActivity, uris)
        }

        refreshSendIcon()
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshSendIcon() }
        })

        clip.setOnClickListener { openAttachSheet() }

        // Жесты по EditText: свайп вверх → скрепка, свайп справа-налево → микрофон
        val inputEdit = findViewById<EditText>(R.id.editMessage)
        val editGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (e1 == null) return false
                val totalDx = e2.x - e1.x
                val totalDy = e2.y - e1.y
                val slop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
                val slopX = slop * 4
                val slopY = slop * 4
                if (totalDy < -slopY && Math.abs(totalDy) > Math.abs(totalDx) * 1.3f) {
                    openAttachSheet()
                    return true
                }
                if (totalDx < -slopX && Math.abs(totalDx) > Math.abs(totalDy) * 1.3f) {
                    if (recordingPanel.visibility != View.VISIBLE) {
                        startVoiceRecording(locked = true)
                    }
                    return true
                }
                return false
            }
        })
        inputEdit.setOnTouchListener { _, ev -> editGesture.onTouchEvent(ev); false }

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
                    if (!isLocked && dy > 200f) {
                        isLocked = true
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        lockHintText?.text = "Запись зафиксирована"
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val held = System.currentTimeMillis() - touchDownTime
                    when {
                        isLocked -> {
                            // удержали + свайпнули вверх: запись идёт
                        }
                        held < 250 -> {
                            // короткий тап — отмена записи
                            cancelVoice()
                        }
                        else -> {
                            stopAndSendVoice()
                        }
                    }
                    true
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

    private fun applySendModeIcon() {
        val send = findViewById<ImageButton>(R.id.btnSend)
        send.setImageResource(R.drawable.ic_micro)
        send.contentDescription = "Голосовое сообщение"
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
            send.setImageResource(R.drawable.ic_micro)
            send.contentDescription = "Голосовое сообщение"
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
                val model = Settings.get(this@MainActivity, Settings.Category.TEXT)
                val reply = OpenRouterClient.send(history, model)
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

    private fun startVoiceRecording(locked: Boolean = false) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        try {
            recordedFile = voiceRecorder.start()
            isLocked = locked
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
            val groqKey = BuildConfig.GROQ_API_KEY
            if (orKey.isBlank()) {
                adapter.replace({ it.isLoading }, Message("⚠️ OPENROUTER_API_KEY не задан", isUser = false))
                recycler.scrollToPosition(adapter.itemCount - 1)
                return@launch
            }
            try {
                val voiceModel = Settings.get(this@MainActivity, Settings.Category.VOICE)
                val text = withContext(Dispatchers.IO) {
                    TranscriptionClient.transcribe(orKey, groqKey, file, voiceModel)
                }
                file.delete()
                when {
                    text.isBlank() || isLikelyHallucination(text) -> {
                        adapter.replace({ it.isLoading },
                            Message("⚠️ Неразборчиво / галлюцинация Whisper", isUser = false))
                        recycler.scrollToPosition(adapter.itemCount - 1)
                    }
                    else -> {
                        confirmVoiceSend(text)
                    }
                }
            } catch (e: Exception) {
                file.delete()
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                adapter.replace({ it.isLoading }, Message("⚠️ $msg", isUser = false))
            }
            recycler.scrollToPosition(adapter.itemCount - 1)
        }
    }

    /** Типичные галлюцинации Whisper — такие строки не отправляем. */
    private fun isLikelyHallucination(text: String): Boolean {
        val t = text.lowercase().trim().trimEnd('.', '!', '?', '…')
        if (t.length < 3) return true
        val known = setOf(
            "you", "bye", "thanks", "thank you",
            "thanks for watching", "thank you for watching",
            "subscribe", "like and subscribe", "see you",
            "bye bye", "the end", "субтитры", "субтитры создавал",
            "subtitles by", "translated by", "music", "[music]", "(music)",
            "аплодисменты", "тишина", "молчание"
        )
        if (known.contains(t)) return true
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 3 && words.toSet().size == 1) return true
        return false
    }

    /** Диалог подтверждения транскрибации голосового. */
    private fun confirmVoiceSend(text: String) {
        val view = android.widget.TextView(this).apply {
            this.text = text
            setPadding(48, 32, 48, 32)
            textSize = 16f
            setTextColor(0xFFE6E6E6.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("Распознано")
            .setView(view)
            .setPositiveButton("Отправить") { _, _ ->
                val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
                adapter.replace({ it.isLoading }, Message(text, isUser = true, isVoice = true))
                repo.appendMessage(state, state.currentId, "user", text)
                refreshChatDrawer()
                recycler.scrollToPosition(adapter.itemCount - 1)
                requestBotReply()
            }
            .setNeutralButton("Редактировать") { _, _ ->
                val edit = findViewById<EditText>(R.id.editMessage)
                edit.setText(text)
                edit.setSelection(text.length)
                adapter.remove({ it.isLoading })
                edit.requestFocus()
            }
            .setNegativeButton("Отмена") { _, _ ->
                val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
                adapter.replace({ it.isLoading }, Message("✖️ Не отправлено", isUser = false))
                recycler.scrollToPosition(adapter.itemCount - 1)
            }
            .show()
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

    private fun handlePickedImage(uri: Uri, explicitCaption: String? = null) {
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        val edit = findViewById<EditText>(R.id.editMessage)
        val caption = explicitCaption
            ?: edit.text.toString().trim().ifBlank { "Опиши это изображение" }
        if (explicitCaption == null) {
            edit.text.clear()
            refreshSendIconLocal()
        }

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
                val imageModel = Settings.get(this@MainActivity, Settings.Category.IMAGE)
                val reply = OpenRouterClient.describeImage(prompt, b64, mime, imageModel)
                cached.delete()
                adapter.replace({ it.isLoading }, Message(reply, isUser = false))
                repo.appendMessage(state, state.currentId, "assistant", reply)
                refreshChatDrawer()
            } catch (e: Exception) {
                cached.delete()
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
        if (hasText) {
            send.setImageResource(R.drawable.ic_send)
        } else {
            applySendModeIcon()
        }
    }

    /** Открыть нижнее меню скрепки (камера + последние фото). */
    private fun openAttachSheet() {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            showAttachSheet()
        } else {
            requestImagesPerm.launch(perm)
        }
    }

    private fun loadRecentImages(offset: Int, limit: Int): List<Uri> {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(android.provider.MediaStore.Images.Media._ID)
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
        } else {
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val sort = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            contentResolver.query(collection, projection, null, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                var skipped = 0
                while (c.moveToNext()) {
                    if (skipped < offset) { skipped++; continue }
                    if (uris.size >= limit) break
                    val id = c.getLong(idCol)
                    uris += android.content.ContentUris.withAppendedId(collection, id)
                }
            }
        } catch (e: Exception) { /* без фото */ }
        return uris
    }

    private fun showAttachSheet() {
        val selected = mutableSetOf<Uri>()
        var loadOffset = 0
        val pageSize = 100
        val reserve = 6
        var loading = false
        var endReached = false

        val view = layoutInflater.inflate(R.layout.bottom_sheet_attach, null, false)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerAttach)
        val empty = view.findViewById<android.widget.TextView>(R.id.emptyHint)
        val title = view.findViewById<android.widget.TextView>(R.id.sheetTitle)
        val countTv = view.findViewById<android.widget.TextView>(R.id.sheetCount)
        val bottomBar = view.findViewById<android.view.View>(R.id.bottomBar)
        val selectedThumbs = view.findViewById<android.widget.LinearLayout>(R.id.selectedThumbs)
        val btnSend = view.findViewById<android.widget.Button>(R.id.btnSendSelected)
        val btnClose = view.findViewById<android.view.View>(R.id.btnSheetClose)

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(view)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.setOnShowListener { sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED }

        recycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        lateinit var attachAdapter: AttachAdapter
        attachAdapter = AttachAdapter(
            scope = lifecycleScope,
            onCamera = { sheet.dismiss(); launchCamera() },
            onPreview = { uri -> showImagePreview(uri, selected, attachAdapter.allUris(), attachAdapter) },
            isSelected = { uri -> selected.contains(uri) },
            onToggle = { uri ->
                if (selected.contains(uri)) selected.remove(uri) else selected.add(uri)
                attachAdapter.notifyDataSetChanged()
                refreshSelectionUi(selected, countTv, title, bottomBar, sheet, selectedThumbs, btnSend)
            }
        )
        recycler.adapter = attachAdapter

        fun loadNextPage() {
            if (loading || endReached) return
            loading = true
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val batch = loadRecentImages(loadOffset, pageSize)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    loading = false
                    if (batch.isEmpty()) {
                        endReached = true
                    } else {
                        loadOffset += batch.size
                        // первая страница — submit (кладёт плитку камеры + батч), дальше append
                        if (loadOffset == batch.size) {
                            attachAdapter.submit(batch)
                        } else {
                            attachAdapter.append(batch)
                        }
                        if (batch.size < pageSize) endReached = true
                    }
                    empty.visibility = if (attachAdapter.photoCount() == 0)
                        android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
        loadNextPage()

        recycler.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as androidx.recyclerview.widget.GridLayoutManager
                val total = lm.itemCount
                val last = lm.findLastVisibleItemPosition()
                if (last >= total - 1 - reserve) loadNextPage()
            }
        })

        btnClose.setOnClickListener { sheet.dismiss() }
        btnSend.setOnClickListener {
            val edit = findViewById<EditText>(R.id.editMessage)
            val caption = edit.text.toString().trim().ifBlank { "Опиши это изображение" }
            if (explicitCaptionRef == null) {
                edit.text.clear()
                refreshSendIconLocal()
            }
            val uris = selected.toList()
            sheet.dismiss()
            uris.forEach { handlePickedImage(it, caption) }
        }

        sheet.show()
    }

    // сохраняем caption, чтобы не терялся при множественной отправке
    private var explicitCaptionRef: String? = null

    private fun refreshSelectionUi(
        selected: Set<Uri>,
        countTv: android.widget.TextView,
        title: android.widget.TextView,
        bottomBar: android.view.View,
        sheet: com.google.android.material.bottomsheet.BottomSheetDialog,
        selectedThumbs: android.widget.LinearLayout,
        btnSend: android.widget.Button
    ) {
        val n = selected.size
        if (n == 0) {
            countTv.visibility = android.view.View.GONE
            title.text = "Прикрепить"
            bottomBar.visibility = android.view.View.GONE
        } else {
            countTv.visibility = android.view.View.VISIBLE
            countTv.text = "Выбрано: $n"
            title.text = "Прикрепить"
            bottomBar.visibility = android.view.View.VISIBLE
            btnSend.text = "Отправить ($n)"
            // миниатюры выбранных
            selectedThumbs.removeAllViews()
            val inflater = layoutInflater
            for (uri in selected) {
                val thumb = android.widget.ImageView(this)
                val size = (40 * resources.displayMetrics.density).toInt()
                val lp = android.widget.LinearLayout.LayoutParams(size, size)
                lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
                thumb.layoutParams = lp
                thumb.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                thumb.setBackgroundColor(0xFF2B2B2B.toInt())
                val bmp = PhotoCache.thumb(this@MainActivity, uri, 96)
                if (bmp != null) thumb.setImageBitmap(bmp)
                thumb.setOnClickListener { showImagePreview(uri, selected.toMutableSet(), selected.toList(), null) }
                selectedThumbs.addView(thumb)
            }
        }
    }

    private fun showImagePreview(
        uri: Uri,
        selected: MutableSet<Uri>,
        allLoaded: List<Uri>,
        parentAdapter: AttachAdapter?
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_image_preview, null, false)
        val pager = view.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.previewPager)
        val btnClose = view.findViewById<android.view.View>(R.id.btnPreviewClose)
        val btnToggle = view.findViewById<android.widget.ImageView>(R.id.btnPreviewToggle)
        val checkIcon = view.findViewById<android.widget.ImageView>(R.id.previewCheckIcon)
        val posTv = view.findViewById<android.widget.TextView>(R.id.previewPosition)

        val start = allLoaded.indexOf(uri).coerceAtLeast(0)
        pager.adapter = PreviewPagerAdapter(allLoaded, lifecycleScope)
        pager.setCurrentItem(start, false)
        pager.offscreenPageLimit = 1

        fun refreshToggle() {
            val cur = allLoaded.getOrNull(pager.currentItem) ?: return
            val sel = selected.contains(cur)
            btnToggle.setImageResource(
                if (sel) R.drawable.select_circle_checked else R.drawable.select_circle_bg
            )
            checkIcon.visibility = if (sel) android.view.View.VISIBLE else android.view.View.GONE
            posTv.text = "${pager.currentItem + 1} / ${allLoaded.size}"
        }
        refreshToggle()
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = refreshToggle()
        })
        btnToggle.setOnClickListener {
            val cur = allLoaded.getOrNull(pager.currentItem) ?: return@setOnClickListener
            if (selected.contains(cur)) selected.remove(cur) else selected.add(cur)
            parentAdapter?.notifyDataSetChanged()
            refreshToggle()
        }

        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg.setContentView(view)
        btnClose.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun loadThumbSync(uri: Uri, reqSize: Int): android.graphics.Bitmap? =
        PhotoCache.thumb(this, uri, reqSize)

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
