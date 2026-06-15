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
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.drawerlayout.widget.DrawerLayout
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_NUTRITION = "open_nutrition"
        const val EXTRA_WIDGET_ADD_MEAL = "widget_add_meal"
        const val EXTRA_WIDGET_VOICE = "widget_voice"
    }
    private lateinit var adapter: MessageAdapter
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var repo: ChatRepository
    private lateinit var state: ChatRepository.State
    private lateinit var drawer: DrawerLayout
    private lateinit var nutritionViewModel: NutritionViewModel

    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var waveform: WaveformView
    private lateinit var recordingPanel: LinearLayout
    private lateinit var normalInput: LinearLayout
    private lateinit var tabSwipeDetector: GestureDetector
    private var tabSwipeConsumed = false
    private var amplitudeJob: Job? = null
    private var recordedFile: File? = null

    private enum class SendMode { MIC }
    private val sendMode = SendMode.MIC
    private var touchDownTime = 0L
    private var touchStartY = 0f
    private var isLocked = false
    private enum class ModeTab { CHAT, INFO, SHOPPING, PARAMS, PRODUCTS, DISHES }
    private var currentModeTab = ModeTab.CHAT
    private var recTimerText: android.widget.TextView? = null
    private var lockHintText: android.widget.TextView? = null
    private var activeCaloriesText: TextView? = null
    private var healthPermissionRequestInFlight = false
    private var isDayAnimating = false
    private var isTabAnimating = false
    private var pendingMeal: String? = null
    private var pendingVoiceMealPrefix: String? = null
    private var dbSelectionActive = false
    private var infoSelectionActive = false
    private var isTranscribing = false

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) handlePickedImage(uri)
    }

    private var productPhotoCallback: ((Uri?) -> Unit)? = null
    private val pickProductPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        productPhotoCallback?.invoke(uri)
        productPhotoCallback = null
    }
    // Отдельный result launcher для «Сделать снимок» из карточки продукта —
    // чтобы не путать URI с чат-камерой (та пишет в handlePickedImage).
    private val takeProductPicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) productPhotoCallback?.invoke(productCameraUri)
        else productPhotoCallback?.invoke(null)
        productPhotoCallback = null
    }
    private var productCameraUri: Uri? = null
    private var productPhotoSourceSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    /** Bottom sheet «Камера / Галерея» — аналог нажатия на скрепку в чате. */
    fun showProductPhotoSourceSheet(onPicked: (Uri?) -> Unit) {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_photo_picker, null, false)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(view)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.setOnDismissListener { productPhotoSourceSheet = null }
        productPhotoSourceSheet = sheet
        view.findViewById<View>(R.id.btnCamera).setOnClickListener {
            sheet.dismiss()
            showProductCamera(onPicked)
        }
        view.findViewById<View>(R.id.btnGallery).setOnClickListener {
            sheet.dismiss()
            productPhotoCallback = onPicked
            pickProductPhoto.launch(androidx.activity.result.PickVisualMediaRequest())
        }
        view.findViewById<View>(R.id.btnCancel).setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    /** Сразу открывает галерею для выбора фото (без bottom sheet). */
    fun showProductGallery(onPicked: (Uri?) -> Unit) {
        productPhotoCallback = onPicked
        pickProductPhoto.launch(androidx.activity.result.PickVisualMediaRequest())
    }

    /** Сразу открывает камеру для снимка (без bottom sheet). */
    fun showProductCamera(onPicked: (Uri?) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            productPhotoCallback = onPicked
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 44)
            return
        }
        try {
            val file = File(cacheDir, "product_camera_${System.currentTimeMillis()}.jpg")
            productCameraUri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            productPhotoCallback = onPicked
            takeProductPicture.launch(productCameraUri!!)
        } catch (e: Exception) {
            toast("Камера недоступна: ${e.message}")
        }
    }

    // Сканер штрихкодов/QR через камеру (ZXing)
    private var pendingBarcodeCallback: ((String?) -> Unit)? = null
    private val scanBarcodeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = result.data?.getStringExtra("SCAN_RESULT")
        val cb = pendingBarcodeCallback
        pendingBarcodeCallback = null
        cb?.invoke(code)
    }

    /** Запускает камеру-сканер в портретной ориентации, понимает все типы штрихкодов + QR. */
    private fun launchBarcodeScanner(onResult: (String?) -> Unit) {
        pendingBarcodeCallback = onResult
        val integrator = com.google.zxing.integration.android.IntentIntegrator(this).apply {
            setOrientationLocked(true)
            setBeepEnabled(false)
            setPrompt("Наведите камеру на штрихкод или QR")
            setCaptureActivity(PortraitCaptureActivity::class.java)
            setDesiredBarcodeFormats(
                com.google.zxing.integration.android.IntentIntegrator.ALL_CODE_TYPES
            )
        }
        scanBarcodeLauncher.launch(integrator.createScanIntent())
    }

    private val healthPermissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        healthPermissionRequestInFlight = false
        if (granted.containsAll(HealthConnectCaloriesUseCase.PERMISSIONS)) {
            val date = state.selectedDate?.let {
                runCatching { java.time.LocalDate.parse(it) }.getOrNull()
            } ?: java.time.LocalDate.now()
            nutritionViewModel.loadActiveCaloriesForDate(date)
        }
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
        // После setContentView окно уже не «splash»: фон Activity — @color/app_background
        // из корня layout. Сбрасываем windowBackground на прозрачный, чтобы loading.png
        // не светился под панелями.
        window.setBackgroundDrawableResource(android.R.color.transparent)

        voiceRecorder = VoiceRecorder(this)
        repo = ChatRepository(this)
        state = repo.load()
        repo.clearCurrentChat(state)
        nutritionViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[NutritionViewModel::class.java]

        drawer = findViewById(R.id.drawerLayout)
        // Отслеживаем изменения активных ккал, чтобы перерисовать инфо-плашку
        // (теперь активные ккал встроены в большое число остатка)
        lifecycleScope.launch {
            nutritionViewModel.activeCalories.collect { state ->
                if (isDayAnimating) return@collect
                if (currentModeTab == ModeTab.INFO) renderInfoContent()
                if (state is NutritionViewModel.ActiveCaloriesState.PermissionRequired) {
                    requestHealthCaloriesPermissionIfNeeded()
                }
            }
        }
        // Дровер — тап по центру шапки (50% ширины экрана).
        drawer.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        val edit = findViewById<EditText>(R.id.editMessage)
        val send = findViewById<ImageButton>(R.id.btnSend)
        val clip = findViewById<ImageButton>(R.id.btnClip)
        val btnNavBack = findViewById<ImageButton>(R.id.btnNavBack)
        val btnDrawerCenter = findViewById<View>(R.id.btnDrawerCenter)
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
        if (intent.getBooleanExtra(EXTRA_OPEN_NUTRITION, false)) {
            Modes.byId("nutrition")?.let { openOrCreateModeChat(it) }
            currentModeTab = ModeTab.INFO
            applyModeTabsSelection()
        }
        handleWidgetIntent(intent)
        scheduleDbTabsPreload()

        NutritionController.onOverlayChanged = { updateHeaderNav() }
        NutritionController.onDbSelectionChanged = { active ->
            dbSelectionActive = active
            updateFabVisibility()
            if (active) dbSelectionOverlay()?.bringToFront()
        }
        NutritionController.onInfoSelectionChanged = { active ->
            infoSelectionActive = active
            if (active) dbSelectionOverlay()?.bringToFront()
        }

        btnDrawerCenter.setOnClickListener { openDrawerWithSave() }
        btnNavBack.setOnClickListener { navigateBackOneStep() }
        findViewById<ImageButton>(R.id.btnNavDb).setOnClickListener { openShoppingFromHeader() }

        // Язычок слева — визуальный индикатор (дровер по центру шапки).
        btnCloseDrawer.setOnClickListener { drawer.closeDrawers() }
        btnNewChat.setOnClickListener {
            repo.createChat(state)
            renderCurrentChat()
            refreshChatDrawer()
            drawer.closeDrawers()
        }
        findViewById<View>(R.id.btnTelegram).setOnClickListener {
            drawer.closeDrawers()
            startActivity(android.content.Intent(this, TelegramActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            drawer.closeDrawers()
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnCrashLog).setOnClickListener {
            drawer.closeDrawers()
            startActivity(android.content.Intent(this, CrashLogActivity::class.java))
        }
        findViewById<View>(R.id.btnShoppingList).setOnClickListener {
            drawer.closeDrawers()
            Modes.byId("nutrition")?.let { openOrCreateModeChat(it) }
            currentModeTab = ModeTab.SHOPPING
            applyModeTabsSelection()
        }

        // Свайпы только в зоне поля ввода. SwipeInterceptor перехватывает
        // жесты на onInterceptTouchEvent — дети получают обычные тапы.
        val bottomContainer = findViewById<SwipeInterceptor>(R.id.bottomContainer)
        bottomContainer.onSwipeUp = { openAttachSheet() }
        bottomContainer.onSwipeRightToLeft = {
            if (recordingPanel.visibility != View.VISIBLE) {
                startVoiceRecording(locked = true)
            }
        }

        // Свайп по верхним табам мода — переключение между под-табами.
        val minTabSwipePx = resources.displayMetrics.widthPixels * 0.15f
        val minFlingVx = ViewConfiguration.get(this).scaledMinimumFlingVelocity.toFloat()
        tabSwipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true.also { tabSwipeConsumed = false }
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (e1 == null || tabSwipeConsumed) return false
                if (!inTabSwipeZone) return false
                val totalDx = e2.x - e1.x
                if (Math.abs(dy) < Math.abs(dx) && Math.abs(totalDx) > minTabSwipePx) {
                    tabSwipeConsumed = true
                    cycleNutritionTab(if (totalDx < 0) +1 else -1)
                    return true
                }
                return false
            }
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (e1 == null || tabSwipeConsumed) return false
                if (!inTabSwipeZone) return false
                if (Math.abs(vy) < Math.abs(vx) && Math.abs(vx) > minFlingVx) {
                    tabSwipeConsumed = true
                    cycleNutritionTab(if (vx < 0) +1 else -1)
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
                    if (isTranscribing) return@setOnTouchListener true
                    touchDownTime = System.currentTimeMillis()
                    startVoiceRecording()
                    lockHintText?.text = "Удерживайте для записи"
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val held = System.currentTimeMillis() - touchDownTime
                    if (held < 250) cancelVoice() else stopAndSendVoice()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelVoice()
                    true
                }
                else -> false
            }
        }
        btnCancel.setOnClickListener { cancelVoice() }
    }

    override fun onResume() {
        super.onResume()
        if (TelegramBridge.isEnabled(this)) {
            lifecycleScope.launch {
                val r = runCatching { TelegramBridge.sync(this@MainActivity) }.getOrElse {
                    TelegramBridge.SyncResult(0, it.message ?: "ошибка")
                }
                if (r.applied > 0) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        renderCurrentChat()
                    }
                }
            }
        }
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
        applyModeTabs()
    }

    /** В режиме мода: показать табы Чат | [Mode] | Параметры. */
    private fun applyModeTabs() {
        val chat = currentChat()
        val mode = chat?.mode?.let { Modes.byId(it) }
        val tabs = findViewById<View>(R.id.modeTabs)
        if (mode == null) {
            tabs.visibility = View.GONE
            currentModeTab = ModeTab.CHAT
            applyModeTabsSelection()
            return
        }
        tabs.visibility = View.VISIBLE
        findViewById<android.widget.TextView>(R.id.tabInfo).text = mode.name
        // «Питание» (Info) — главная вкладка мода. Дефолт при входе в мод.
        if (currentModeTab == ModeTab.CHAT) {
            currentModeTab = ModeTab.INFO
        }
        val tabInfo = findViewById<android.widget.TextView>(R.id.tabInfo)
        val tabDatabase = findViewById<android.widget.TextView>(R.id.tabDatabase)
        val tabProducts = findViewById<android.widget.TextView>(R.id.tabProducts)
        val tabDishes = findViewById<android.widget.TextView>(R.id.tabDishes)
        val tabChat = findViewById<android.widget.TextView>(R.id.tabChat)
        val isNutrition = mode.id == "nutrition"
        tabInfo.visibility = if (isNutrition) View.VISIBLE else View.GONE
        tabDatabase.visibility = if (isNutrition) View.VISIBLE else View.GONE
        if (!isNutrition && currentModeTab in arrayOf(ModeTab.PRODUCTS, ModeTab.DISHES, ModeTab.SHOPPING)) {
            currentModeTab = ModeTab.INFO
        }
        tabInfo.setOnClickListener { if (currentModeTab != ModeTab.INFO) { currentModeTab = ModeTab.INFO; applyModeTabsSelection() } }
        tabDatabase.setOnClickListener {
            lastDbTab = ModeTab.PRODUCTS
            openProductsFromInfo()
        }
        tabProducts.setOnClickListener { if (currentModeTab != ModeTab.PRODUCTS) { currentModeTab = ModeTab.PRODUCTS; applyModeTabsSelection() } }
        tabDishes.setOnClickListener { if (currentModeTab != ModeTab.DISHES) { currentModeTab = ModeTab.DISHES; applyModeTabsSelection() } }
        tabChat.setOnClickListener {
            if (state.chats.isEmpty()) {
                val created = repo.createChat(state)
                switchToChat(created.id)
            } else if (state.currentId == null) {
                switchToChat(state.chats.first().id)
            }
            currentModeTab = ModeTab.CHAT
            applyModeTabsSelection()
        }
        applyModeTabsSelection()
    }

    // Помнит последнюю открытую вкладку внутри БД (чтобы вернуться туда же)
    private var lastDbTab: ModeTab = ModeTab.PRODUCTS
    private var cachedProductsTab: android.widget.LinearLayout? = null
    private var cachedDishesTab: android.widget.LinearLayout? = null
    private var refreshProductsTab: (() -> Unit)? = null
    private var refreshDishesTab: (() -> Unit)? = null
    private var productsTabDirty = false
    private var dishesTabDirty = false
    private var dbTabsPreloadJob: Job? = null
    // Запоминаем вкладку до открытия drawer, чтобы вернуться на неё свайпом вправо
    private var tabBeforeDrawer: ModeTab? = null

    private fun openDrawerWithSave() {
        tabBeforeDrawer = currentModeTab
        drawer.openDrawer(android.view.Gravity.START)
    }

    private fun applyModeTabsSelection() {
        val tabInfo = findViewById<android.widget.TextView>(R.id.tabInfo)
        val tabDatabase = findViewById<android.widget.TextView>(R.id.tabDatabase)
        val tabProducts = findViewById<android.widget.TextView>(R.id.tabProducts)
        val tabDishes = findViewById<android.widget.TextView>(R.id.tabDishes)
        val tabChat = findViewById<android.widget.TextView>(R.id.tabChat)
        val recycler = findViewById<View>(R.id.recyclerMessages)
        val info = findViewById<View>(R.id.infoContainer)
        val params = findViewById<View>(R.id.paramsContainer)
        val bottom = findViewById<View>(R.id.bottomContainer)
        val active = 0xFFE6E6E6.toInt()
        val inactive = 0xFF8A8A8A.toInt()
        fun style(t: android.widget.TextView, on: Boolean) {
            t.setTextColor(if (on) active else inactive)
            t.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        val inDb = currentModeTab == ModeTab.PRODUCTS || currentModeTab == ModeTab.DISHES
        val isNutrition = currentChat()?.mode == "nutrition"
        // Внутри БД показываем «Продукты|Блюда», прячем внешние «Питание|База|Чат»
        tabInfo.visibility = if (inDb || !isNutrition) View.GONE else View.VISIBLE
        tabDatabase.visibility = if (inDb || !isNutrition) View.GONE else View.VISIBLE
        tabChat.visibility = if (inDb) View.GONE else View.VISIBLE
        tabProducts.visibility = if (inDb) View.VISIBLE else View.GONE
        tabDishes.visibility = if (inDb) View.VISIBLE else View.GONE
        style(tabInfo, currentModeTab == ModeTab.INFO)
        style(tabProducts, currentModeTab == ModeTab.PRODUCTS)
        style(tabDishes, currentModeTab == ModeTab.DISHES)
        style(tabChat, currentModeTab == ModeTab.CHAT)
        recycler.visibility = if (currentModeTab == ModeTab.CHAT) View.VISIBLE else View.GONE
        info.visibility = if (currentModeTab != ModeTab.CHAT && currentModeTab != ModeTab.PARAMS) View.VISIBLE else View.GONE
        params.visibility = if (currentModeTab == ModeTab.PARAMS) View.VISIBLE else View.GONE
        bottom.visibility = if (currentModeTab == ModeTab.CHAT) View.VISIBLE else View.GONE
        if (!inDb) {
            dbSelectionActive = false
            NutritionController.clearDbSelectionOverlay(dbSelectionOverlay())
        }
        if (currentModeTab != ModeTab.INFO) {
            infoSelectionActive = false
            NutritionController.clearInfoSelectionOverlay(dbSelectionOverlay())
        }
        updateFabVisibility()
        if (currentModeTab != ModeTab.CHAT) hideKeyboard()
        if (inDb) lastDbTab = currentModeTab
        if (currentModeTab == ModeTab.INFO) renderInfoContent()
        if (currentModeTab == ModeTab.SHOPPING) renderShoppingContent()
        if (currentModeTab == ModeTab.PRODUCTS) renderProductsContent()
        if (currentModeTab == ModeTab.DISHES) renderDishesContent()
        if (currentModeTab == ModeTab.PARAMS) renderParamsContent()
        updateHeaderNav()
    }

    /** Кнопка «←» в шапке: карточка → список БД, БД → главная питания. */
    private fun navigateBackOneStep() {
        if (dismissOpenCardIfAny()) {
            updateHeaderNav()
            return
        }
        if (currentModeTab == ModeTab.PRODUCTS || currentModeTab == ModeTab.DISHES) {
            lastDbTab = ModeTab.PRODUCTS
            currentModeTab = ModeTab.INFO
            applyModeTabsSelection()
            return
        }
        if (currentModeTab == ModeTab.SHOPPING) {
            currentModeTab = ModeTab.INFO
            applyModeTabsSelection()
        }
    }

    private fun updateHeaderNav() {
        val header = findViewById<View>(R.id.header)
        val back = findViewById<View>(R.id.btnNavBack)
        val dbBtn = findViewById<View>(R.id.btnNavDb)
        val inDb = currentModeTab == ModeTab.PRODUCTS || currentModeTab == ModeTab.DISHES
        val isNutrition = currentChat()?.mode == "nutrition"
        val onNutritionInfo = isNutrition && currentModeTab == ModeTab.INFO
        val showBack = isNutrition && (hasOpenCard() || inDb || currentModeTab == ModeTab.SHOPPING)
        back.visibility = if (showBack) View.VISIBLE else View.GONE
        dbBtn.visibility = if (onNutritionInfo && !hasOpenCard()) View.VISIBLE else View.GONE
        header.bringToFront()
        header.elevation = if (showBack || dbBtn.visibility == View.VISIBLE) 8f else 0f
    }

    private fun openShoppingFromHeader() {
        if (currentChat()?.mode != "nutrition" || hasOpenCard()) return
        currentModeTab = ModeTab.SHOPPING
        applyModeTabsSelection()
    }

    /** Скрыть клавиатуру, если она открыта. */
    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        val edit = findViewById<EditText>(R.id.editMessage)
        imm.hideSoftInputFromWindow(edit.windowToken, 0)
    }

    /** Инфографика активного мода. Сейчас реализована только для Питания. */
    @Suppress("MissingSuperCall", "DEPRECATION")
    override fun onBackPressed() {
        // Если открыта карточка редактирования — закрыть её (вернуться в список)
        if (dismissOpenCardIfAny()) {
            updateHeaderNav()
            return
        }
        // В под-табах мода (Чат, Параметры, База, Покупки) «назад» возвращает
        // на Инфо — главную вкладку мода. Из Инфо или вне мода — выход.
        if (currentChat()?.mode != null) {
            when (currentModeTab) {
                ModeTab.CHAT, ModeTab.SHOPPING, ModeTab.PRODUCTS, ModeTab.DISHES, ModeTab.PARAMS -> {
                    if (currentModeTab == ModeTab.PRODUCTS || currentModeTab == ModeTab.DISHES) {
                        lastDbTab = ModeTab.PRODUCTS
                    }
                    currentModeTab = ModeTab.INFO
                    applyModeTabsSelection()
                    return
                }
                else -> {} // INFO — выход из приложения
            }
        }
        super.onBackPressed()
    }

    /** Закрывает открытую overlay-карточку в [R.id.infoContainer], если она есть. */
    private fun hasOpenCard(): Boolean {
        val container = findViewById<ViewGroup>(R.id.infoContainer) ?: return false
        val root = container.parent as? ViewGroup ?: container
        return root.children.any { it.tag == NutritionController.CARD_TAG }
    }

    private fun dismissOpenCardIfAny(): Boolean {
        if (!hasOpenCard()) return false
        val container = findViewById<ViewGroup>(R.id.infoContainer) ?: return false
        val root = container.parent as? ViewGroup ?: container
        val open = root.children.firstOrNull { it.tag == NutritionController.CARD_TAG } ?: return false
        (open.parent as? ViewGroup)?.removeView(open)
        hideKeyboard()
        updateHeaderNav()
        return true
    }

    private fun renderInfoContent() {
        val content = findViewById<android.widget.LinearLayout>(R.id.infoContent)
        content.removeAllViews()
        val mode = currentChat()?.mode?.let { Modes.byId(it) } ?: return
        if (mode.id == "nutrition") {
            renderNutritionInfo(content)
        } else {
            val tv = android.widget.TextView(this).apply {
                text = "Раздел «${mode.name}» в разработке.\n\n" +
                    "Скоро здесь появится инфографика и кнопки действий для этого мода."
                setPadding(0, 24, 0, 0)
                setTextColor(0xFFE6E6E6.toInt())
                textSize = 14f
            }
            content.addView(tv)
        }
    }

    private fun renderNutritionInfo(content: android.widget.LinearLayout) {
        val selectedDate = state.selectedDate?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        } ?: java.time.LocalDate.now()
        val activeKcal = when (val s = nutritionViewModel.activeCalories.value) {
            is NutritionViewModel.ActiveCaloriesState.Value -> s.kcal
            else -> 0.0
        }
        NutritionController.renderInfo(
            this,
            content,
            selectedDate = selectedDate,
            activeKcal = activeKcal,
            onMealClick = { meal -> pendingMeal = meal; openProductsFromInfo() },
            onCaloriesClick = { openParamsFromInfo() },
            onDateChange = { newDate ->
                state.selectedDate = newDate.toString()
                repo.save(state)
                nutritionViewModel.loadActiveCaloriesForDate(newDate)
                applyModeTabsSelection()
            },
            container = findViewById(R.id.infoContainer),
            onPickPhoto = { cb ->
                productPhotoCallback = cb
                pickProductPhoto.launch(androidx.activity.result.PickVisualMediaRequest())
            },
            onTakePhoto = { cb -> showProductCamera(cb) },
            onScanBarcode = { cb -> launchBarcodeScanner(cb) },
            onSendToAgent = { text, meal -> sendToNutritionAgent(text, meal) },
            onPickerAttach = { meal -> attachPhotoToNutritionAgent(meal) },
            onPickerVoice = { meal -> voiceToNutritionAgent(meal) },
            onDayStep = { delta -> cycleDay(delta) },
            overlayHost = dbSelectionOverlay()
        )
        // Подгружаем активные ккал для выбранного дня (если ещё не загружены)
        if (nutritionViewModel.activeCalories.value is NutritionViewModel.ActiveCaloriesState.Idle) {
            nutritionViewModel.loadActiveCaloriesForDate(selectedDate)
        }
    }

    /** Тап по ссылке «База данных» в инфо-плашке → переход на последнюю активную вкладку БД. */
    private fun openProductsFromInfo() {
        if (currentChat()?.mode != "nutrition") return
        if (currentModeTab in arrayOf(ModeTab.PRODUCTS, ModeTab.DISHES)) return
        lastDbTab = ModeTab.PRODUCTS
        currentModeTab = ModeTab.PRODUCTS
        applyModeTabsSelection()
    }

    /** Тап по большому числу калорий → переключаемся в под-таб Параметры. */
    private fun openParamsFromInfo() {
        if (currentModeTab == ModeTab.PARAMS) return
        currentModeTab = ModeTab.PARAMS
        applyModeTabsSelection()
    }

    private fun renderActiveCaloriesUi(content: android.widget.LinearLayout) {
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.card_bg)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        activeCaloriesText = tv
        content.addView(tv, 0)
        updateActiveCaloriesUi(nutritionViewModel.activeCalories.value)
    }

    private fun updateActiveCaloriesUi(state: NutritionViewModel.ActiveCaloriesState) {
        activeCaloriesText?.text = when (state) {
            NutritionViewModel.ActiveCaloriesState.Idle -> "Активно потрачено: —"
            NutritionViewModel.ActiveCaloriesState.Loading -> "Активно потрачено: загрузка"
            NutritionViewModel.ActiveCaloriesState.PermissionRequired -> {
                requestHealthCaloriesPermissionIfNeeded()
                "Активно потрачено: нужен доступ"
            }
            NutritionViewModel.ActiveCaloriesState.HealthConnectUnavailable ->
                "Активно потрачено: Health Connect недоступен"
            is NutritionViewModel.ActiveCaloriesState.Value ->
                "Активно потрачено сегодня: ${"%.0f".format(state.kcal)} ккал"
            is NutritionViewModel.ActiveCaloriesState.Error ->
                "Активно потрачено: ошибка"
        }
    }

    private fun requestHealthCaloriesPermissionIfNeeded() {
        if (currentChat()?.mode != "nutrition" || healthPermissionRequestInFlight) return
        healthPermissionRequestInFlight = true
        healthPermissionsLauncher.launch(HealthConnectCaloriesUseCase.PERMISSIONS)
    }

    private fun scheduleDbTabsPreload() {
        if (cachedProductsTab != null && cachedDishesTab != null) return
        dbTabsPreloadJob?.cancel()
        dbTabsPreloadJob = lifecycleScope.launch {
            delay(400)
            withContext(Dispatchers.Default) {
                runCatching {
                    val db = NutritionDatabase(this@MainActivity)
                    db.listProducts()
                    db.listCustomItems()
                    db.listDishes()
                }
            }
            if (cachedProductsTab == null) ensureProductsTab()
            if (cachedDishesTab == null) ensureDishesTab()
        }
    }

    private fun dbContainer() = findViewById<ViewGroup>(R.id.infoContainer)

    private fun dbSelectionOverlay() = findViewById<ViewGroup>(R.id.dbSelectionOverlay)

    private fun ensureProductsTab() {
        if (cachedProductsTab != null) return
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        refreshProductsTab = NutritionController.renderProductsTab(
            this, root, dbContainer(), dbSelectionOverlay(),
            onMealClick = { text -> focusChatForMeal(text) },
            onPickPhoto = { cb -> showProductGallery(cb) },
            onTakePhoto = { cb -> showProductCamera(cb) },
            onScanBarcode = { cb -> launchBarcodeScanner(cb) },
            onAddToMeal = { product -> openAddProduct(product) }
        )
        cachedProductsTab = root
    }

    private fun ensureDishesTab() {
        if (cachedDishesTab != null) return
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        refreshDishesTab = NutritionController.renderDishesTab(
            this, root, dbContainer(), dbSelectionOverlay(),
            onPickPhoto = { cb -> showProductGallery(cb) },
            onTakePhoto = { cb -> showProductCamera(cb) },
            onScanBarcode = { cb -> launchBarcodeScanner(cb) },
            onAddToMeal = { dish -> openAddDish(dish) }
        )
        cachedDishesTab = root
    }

    private fun mountDbTab(root: android.widget.LinearLayout) {
        val content = findViewById<android.widget.LinearLayout>(R.id.infoContent)
        if (content.childCount == 1 && content.getChildAt(0) === root) return
        content.removeAllViews()
        (root.parent as? ViewGroup)?.removeView(root)
        content.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun invalidateProductsTab() {
        cachedProductsTab = null
        refreshProductsTab = null
        productsTabDirty = true
    }

    private fun invalidateDishesTab() {
        cachedDishesTab = null
        refreshDishesTab = null
        dishesTabDirty = true
    }

    private fun renderProductsContent() {
        ensureProductsTab()
        mountDbTab(cachedProductsTab!!)
        if (productsTabDirty) {
            NutritionController.clearDbSelectionOverlay(dbSelectionOverlay())
            refreshProductsTab?.invoke()
            productsTabDirty = false
        }
        bindFab {
            NutritionController.createProduct(
                container = dbContainer(),
                onScanBarcode = { cb -> launchBarcodeScanner(cb) },
                onPickPhoto = { cb -> showProductGallery(cb) },
                onTakePhoto = { cb -> showProductCamera(cb) },
                onSaved = {
                    productsTabDirty = true
                    dishesTabDirty = true
                    refreshProductsTab?.invoke()
                    productsTabDirty = false
                }
            )
        }
    }

    private fun renderDishesContent() {
        ensureDishesTab()
        mountDbTab(cachedDishesTab!!)
        if (dishesTabDirty) {
            NutritionController.clearDbSelectionOverlay(dbSelectionOverlay())
            refreshDishesTab?.invoke()
            dishesTabDirty = false
        }
        bindFab {
            NutritionController.createDish(
                dbContainer(),
                onPickPhoto = { cb -> showProductGallery(cb) },
                onTakePhoto = { cb -> showProductCamera(cb) },
                onScanBarcode = { cb -> launchBarcodeScanner(cb) }
            ) {
                dishesTabDirty = true
                productsTabDirty = true
                refreshDishesTab?.invoke()
                dishesTabDirty = false
            }
        }
    }

    /** Тап по продукту в БД — добавить к приёму пищи с выбором порции. */
    private fun openAddProduct(product: NutritionDatabase.Product) {
        runCatching {
            val meal = pendingMeal ?: suggestedMealForNow()
            val dateKey = state.selectedDate
                ?: java.time.LocalDate.now().toString()
            pendingMeal = null
            NutritionController.showAddProductToMeal(this, product, meal, dateKey) {
                if (currentModeTab != ModeTab.INFO) currentModeTab = ModeTab.INFO
                applyModeTabsSelection()
            }
        }.onFailure {
            android.widget.Toast.makeText(
                this, "Ошибка: ${it.message ?: it.javaClass.simpleName}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Тап по блюду в БД — добавить к приёму пищи с выбором порции. */
    private fun openAddDish(dish: NutritionDatabase.Dish) {
        runCatching {
            val db = NutritionDatabase(this)
            val macros = db.dishMacrosPer100(dish)
            val meal = pendingMeal ?: suggestedMealForNow()
            val dateKey = state.selectedDate
                ?: java.time.LocalDate.now().toString()
            pendingMeal = null
            NutritionController.showAddDishToMeal(this, dish, macros, meal, dateKey) {
                if (currentModeTab != ModeTab.INFO) currentModeTab = ModeTab.INFO
                applyModeTabsSelection()
            }
        }.onFailure {
            android.widget.Toast.makeText(
                this, "Ошибка: ${it.message ?: it.javaClass.simpleName}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Сбросить все записи приёмов пищи за выбранный день. */
    fun clearSelectedDay() {
        val dateKey = state.selectedDate
            ?: java.time.LocalDate.now().toString()
        NutritionController.clearDayKcal(this, dateKey)
        if (currentModeTab == ModeTab.INFO) applyModeTabsSelection()
    }

    private fun suggestedMealForNow(): String =
        com.assistant.app.NutritionController.mealForHour(java.time.LocalTime.now().hour)

    private fun renderShoppingContent() {
        val content = findViewById<android.widget.LinearLayout>(R.id.infoContent)
        content.removeAllViews()
        NutritionController.renderShoppingList(this, content)
    }

    private fun bindFab(action: () -> Unit) {
        val fab = findViewById<View>(R.id.fabCreate)
        fab.setOnClickListener { action() }
    }

    private fun updateFabVisibility() {
        val inDb = currentModeTab == ModeTab.PRODUCTS || currentModeTab == ModeTab.DISHES
        findViewById<View>(R.id.fabCreate).visibility =
            if (inDb && !dbSelectionActive) View.VISIBLE else View.GONE
    }
    // ===== Параметры мода (на сейчас только Питание) =====
    // Вся логика вынесена в NutritionController.

    private fun renderParamsContent() {
        val content = findViewById<android.widget.LinearLayout>(R.id.paramsContent)
        content.removeAllViews()
        val mode = currentChat()?.mode?.let { Modes.byId(it) }
        if (mode == null) return

        val header = android.widget.TextView(this).apply {
            text = "Параметры «${mode.name}»"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        content.addView(header)

        val hint = android.widget.TextView(this).apply {
            text = "Можно в граммах (например 90) или в % от нормы (например 30%)."
            setTextColor(0xFF8A8A8A.toInt())
            textSize = 12f
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, p, 0, p * 2)
        }
        content.addView(hint)

        if (mode.id == "nutrition") {
            NutritionController.renderParams(this, content) { toast("Сохранено") }
        } else {
            val tv = android.widget.TextView(this).apply {
                text = "Параметры для этого мода пока не настроены."
                setTextColor(0xFF8A8A8A.toInt())
                textSize = 13f
            }
            content.addView(tv)
        }
    }

    /** Фокус на поле ввода и вставка подсказки про приём пищи. */
    private fun focusChatForMeal(meal: String) {
        ensureNutritionChatTab()
        val edit = findViewById<EditText>(R.id.editMessage)
        val meals = setOf("Завтрак", "Обед", "Ужин", "Перекус")
        edit.setText(if (meals.contains(meal)) "[$meal] " else "$meal ")
        edit.setSelection(edit.text.length)
        edit.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun mealMessagePrefix(meal: String): String {
        val meals = setOf("Завтрак", "Обед", "Ужин", "Перекус")
        return if (meals.contains(meal)) "[$meal] " else "$meal: "
    }

    private fun ensureNutritionChatTab() {
        Modes.byId("nutrition")?.let { openOrCreateModeChat(it) }
        if (currentModeTab != ModeTab.CHAT) {
            currentModeTab = ModeTab.CHAT
            applyModeTabsSelection()
        }
    }

    private fun sendToNutritionAgent(text: String, meal: String) {
        ensureNutritionChatTab()
        val prefix = mealMessagePrefix(meal)
        val msg = if (text.startsWith("[")) text else prefix + text
        sendText(msg)
    }

    private fun attachPhotoToNutritionAgent(meal: String) {
        ensureNutritionChatTab()
        val edit = findViewById<EditText>(R.id.editMessage)
        edit.setText(mealMessagePrefix(meal))
        edit.setSelection(edit.text.length)
        openAttachSheet()
    }

    private fun voiceToNutritionAgent(meal: String) {
        ensureNutritionChatTab()
        pendingVoiceMealPrefix = mealMessagePrefix(meal)
        startVoiceRecording(locked = true)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: android.content.Intent?) {
        if (intent == null) return
        when {
            intent.getBooleanExtra(EXTRA_WIDGET_ADD_MEAL, false) -> {
                intent.removeExtra(EXTRA_WIDGET_ADD_MEAL)
                window.decorView.post { openAddMealFromWidget() }
            }
            intent.getBooleanExtra(EXTRA_WIDGET_VOICE, false) -> {
                intent.removeExtra(EXTRA_WIDGET_VOICE)
                window.decorView.post { voiceToNutritionAgent(suggestedMealForNow()) }
            }
        }
    }

    private fun openAddMealFromWidget() {
        Modes.byId("nutrition")?.let { openOrCreateModeChat(it) }
        currentModeTab = ModeTab.INFO
        applyModeTabsSelection()
        val meal = suggestedMealForNow()
        val dateKey = state.selectedDate
            ?: java.time.LocalDate.now().toString()
        NutritionController.openProductPickerForMeal(
            this, meal, dateKey, findViewById(R.id.infoContainer),
            onScanBarcode = { cb -> launchBarcodeScanner(cb) },
            onPickPhoto = { cb -> showProductGallery(cb) },
            onTakePhoto = { cb -> showProductCamera(cb) },
            onSendToAgent = { text, m -> sendToNutritionAgent(text, m) },
            onPickerAttach = { m -> attachPhotoToNutritionAgent(m) },
            onPickerVoice = { m -> voiceToNutritionAgent(m) },
            onAdded = { applyModeTabsSelection() }
        )
    }

    private fun saveMealToDatabase(meal: String) {
        // Сохраняем приём пищи как простой продукт в базу
        val db = NutritionDatabase(this)
        val product = NutritionDatabase.Product(
            id = java.util.UUID.randomUUID().toString(),
            name = meal,
            brand = "",
            barcode = null,
            protein = 0.0,
            fat = 0.0,
            carbs = 0.0,
            servingG = 100.0,
            photoPath = null,
            source = "manual",
            favorite = false
        )
        db.upsertProduct(product)
        toast("Приём пищи сохранён: $meal")
        android.util.Log.d("MainActivity", "Saved meal: $meal")
    }

private fun refreshChatDrawer() {
        val regular = repo.regularChats(state).let { repo.sortedChats(state, base = it) }
        chatAdapter.submit(regular, state.currentId)
        renderModes()
    }

    private fun renderModes() {
        val container = findViewById<android.widget.LinearLayout>(R.id.modesList)
        container.removeAllViews()
        val inflater = layoutInflater
        val activeMode = currentChat()?.mode
        for (mode in Modes.all) {
            val row = inflater.inflate(R.layout.item_mode, container, false)
            row.findViewById<View>(R.id.modeColor).setBackgroundColor(mode.color)
            row.findViewById<android.widget.TextView>(R.id.modeName).text = mode.name
            val check = row.findViewById<View>(R.id.modeActive)
            check.visibility = if (mode.id == activeMode) View.VISIBLE else View.GONE
            row.setOnClickListener {
                openOrCreateModeChat(mode)
            }
            container.addView(row)
        }
    }

    private fun openOrCreateModeChat(mode: Modes.Mode) {
        val chat = repo.findModeChat(state, mode.id)
            ?: repo.createChat(state, modeId = mode.id, title = mode.name)
        switchToChat(chat.id)
        drawer.closeDrawers()
        if (mode.id == "nutrition") scheduleDbTabsPreload()
    }

    private fun switchToChat(id: String) {
        if (state.currentId != id) {
            state.currentId = id
            repo.save(state)
        }
        // всегда перерисовываем: createChat() сам ставит currentId, и
        // без этого рендера табы мода / галочка в дровере не появятся
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
        val chat = currentChat()
        val loading = Message("●", isUser = false, isLoading = true)
        adapter.add(loading)
        recycler.scrollToPosition(adapter.itemCount - 1)
        lifecycleScope.launch {
            try {
                val history = chat?.messages
                    ?.filter { !it.isLoading }
                    ?.map { (if (it.isUser) "user" else "assistant") to it.text }
                    ?: emptyList()
                val model = Settings.get(this@MainActivity, Settings.Category.TEXT)
                val modeId = chat?.mode
                val dateKey = NutritionFoodLogger.dateKeyFrom(state)
                val sysPrompt = modeId?.let { Modes.byId(it)?.systemPrompt }?.let { base ->
                    if (modeId == "nutrition") {
                        base + "\n\n" + NutritionFoodLogger.diaryContext(this@MainActivity, dateKey) +
                            "\n\n" + NutritionFoodLogger.LOG_INSTRUCTIONS
                    } else base
                }
                var reply = ChatClient.send(this@MainActivity, history, model, sysPrompt)
                if (modeId == "nutrition") {
                    val (visible, log) = NutritionFoodLogger.stripLogBlock(reply)
                    reply = visible.ifBlank { "Записал." }
                    val n = NutritionFoodLogger.apply(this@MainActivity, dateKey, log)
                    if (n > 0 && currentModeTab == ModeTab.INFO) renderInfoContent()
                }
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
        if (isTranscribing) return
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
            findViewById<View>(R.id.lockIcon).visibility = View.VISIBLE
            recTimerText?.visibility = View.VISIBLE
            waveform.visibility = View.VISIBLE
            findViewById<View>(R.id.btnCancel).visibility = View.VISIBLE
            findViewById<View>(R.id.btnStopRec).visibility = View.VISIBLE
            lockHintText?.text = if (locked) "Запись…" else "Удерживайте для записи"
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
        isTranscribing = true
        refreshSendIconLocal()

        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        adapter.add(Message("●", isUser = true, isLoading = true, isVoice = true))
        recycler.scrollToPosition(adapter.itemCount - 1)

        lifecycleScope.launch {
            try {
                val orKey = BuildConfig.OPENROUTER_API_KEY
                val groqKey = BuildConfig.GROQ_API_KEY
                val yandexKey = BuildConfig.YANDEX_API_KEY
                val yandexFolderId = BuildConfig.YANDEX_FOLDER_ID
                if (orKey.isBlank() && groqKey.isBlank() && yandexKey.isBlank()) {
                    adapter.remove { it.isUser && it.isLoading && it.isVoice }
                    toast("⚠️ API ключи не заданы")
                    return@launch
                }
                val voiceModel = Settings.get(this@MainActivity, Settings.Category.VOICE)
                val text = withContext(Dispatchers.IO) {
                    TranscriptionClient.transcribe(orKey, groqKey, yandexKey, yandexFolderId, file, voiceModel)
                }
                file.delete()
                completeVoiceSend(text)
            } catch (e: Exception) {
                file.delete()
                adapter.remove { it.isUser && it.isLoading && it.isVoice }
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                toast("⚠️ $msg")
            } finally {
                isTranscribing = false
                refreshSendIconLocal()
            }
        }
    }

    private fun completeVoiceSend(rawText: String) {
        val prefix = pendingVoiceMealPrefix
        pendingVoiceMealPrefix = null
        val finalText = if (prefix != null && !rawText.startsWith("[")) prefix + rawText else rawText.trim()
        adapter.replace(
            { it.isUser && it.isLoading && it.isVoice },
            Message(finalText, isUser = true, isVoice = true)
        )
        repo.appendMessage(state, state.currentId, "user", finalText)
        refreshChatDrawer()
        findViewById<RecyclerView>(R.id.recyclerMessages).scrollToPosition(adapter.itemCount - 1)
        requestBotReply()
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
        pendingVoiceMealPrefix = null
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
        val granted = grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            42 -> if (granted) startVoiceRecording() else toast("Нужен доступ к микрофону")
            43 -> if (granted) launchCamera() else toast("Нужен доступ к камере")
            44 -> if (granted) productPhotoCallback?.let { showProductCamera(it) }
                else toast("Нужен доступ к камере")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        amplitudeJob?.cancel()
        voiceRecorder.cancel()
    }

    private var tabSwipeInProgress = false
    private var inTabSwipeZone = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val tabs = findViewById<View>(R.id.modeTabs)
        if (tabs.visibility == View.VISIBLE && ::tabSwipeDetector.isInitialized) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val tabsLoc = IntArray(2); tabs.getLocationOnScreen(tabsLoc)
                    val screenH = resources.displayMetrics.heightPixels
                    val y = ev.rawY.toInt()
                    tabSwipeInProgress = y in tabsLoc[1]..screenH
                    inTabSwipeZone = tabSwipeInProgress && isNutritionTabSwipeEnabled()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (tabSwipeInProgress) tabSwipeDetector.onTouchEvent(ev)
                    tabSwipeInProgress = false
                    inTabSwipeZone = false
                }
            }
            if (tabSwipeInProgress) tabSwipeDetector.onTouchEvent(ev)
        } else {
            tabSwipeInProgress = false
            inTabSwipeZone = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isNutritionTabSwipeEnabled(): Boolean {
        if (currentChat()?.mode != "nutrition") return false
        return currentModeTab in arrayOf(ModeTab.INFO, ModeTab.CHAT, ModeTab.PRODUCTS, ModeTab.DISHES)
    }

    /** Свайп: INFO↔CHAT; INFO→БД; в БД — Продукты↔Блюда, БД→INFO. */
    private fun cycleNutritionTab(delta: Int) {
        if (!isNutritionTabSwipeEnabled() || isTabAnimating || isDayAnimating) return
        if (drawer.isDrawerOpen(android.view.Gravity.START)) return
        if (hasOpenCard()) return
        val next = nextNutritionTab(currentModeTab, delta) ?: return
        animateTabSwipe(delta) {
            if (next == ModeTab.PRODUCTS || next == ModeTab.DISHES) lastDbTab = next
            currentModeTab = next
            applyModeTabsSelection()
        }
    }

    private fun nextNutritionTab(cur: ModeTab, delta: Int): ModeTab? = when (cur) {
        ModeTab.INFO -> when (delta) {
            -1 -> ModeTab.CHAT
            +1 -> lastDbTab
            else -> null
        }
        ModeTab.CHAT -> when (delta) {
            +1 -> ModeTab.INFO
            else -> null
        }
        ModeTab.PRODUCTS -> when (delta) {
            -1 -> ModeTab.INFO
            +1 -> ModeTab.DISHES
            else -> null
        }
        ModeTab.DISHES -> if (delta < 0) ModeTab.PRODUCTS else null
        else -> null
    }

    private fun viewsForTab(tab: ModeTab): List<View> = when (tab) {
        ModeTab.CHAT -> listOf(
            findViewById(R.id.recyclerMessages),
            findViewById(R.id.bottomContainer)
        )
        ModeTab.INFO, ModeTab.PRODUCTS, ModeTab.DISHES -> listOf(findViewById(R.id.infoContainer))
        else -> emptyList()
    }

    private fun animateTabSwipe(delta: Int, onMid: () -> Unit) {
        val outgoing = viewsForTab(currentModeTab)
        val w = outgoing.firstOrNull()?.width?.toFloat()?.takeIf { it > 0f }
            ?: resources.displayMetrics.widthPixels.toFloat()
        val outX = if (delta > 0) -w else w
        val inX = -outX
        isTabAnimating = true
        fun resetViews(views: List<View>) {
            views.forEach { it.translationX = 0f; it.alpha = 1f }
        }
        if (outgoing.isEmpty()) {
            onMid()
            isTabAnimating = false
            return
        }
        var done = 0
        fun onOutDone() {
            if (++done < outgoing.size) return
            resetViews(outgoing)
            onMid()
            val incoming = viewsForTab(currentModeTab)
            incoming.forEach { it.translationX = inX; it.alpha = 0f }
            if (incoming.isEmpty()) {
                isTabAnimating = false
                return
            }
            var inDone = 0
            incoming.forEach { v ->
                v.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        if (++inDone >= incoming.size) {
                            isTabAnimating = false
                            updateHeaderNav()
                        }
                    }
                    .start()
            }
        }
        outgoing.forEach { v ->
            v.animate()
                .translationX(outX)
                .alpha(0f)
                .setDuration(180)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { onOutDone() }
                .start()
        }
    }

    /** Кнопки ‹ › — смена дня (±1). */
    private fun cycleDay(delta: Int) {
        if (currentModeTab != ModeTab.INFO || isDayAnimating || isTabAnimating) return
        val cur = state.selectedDate?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        } ?: java.time.LocalDate.now()
        val next = if (delta > 0) cur.plusDays(1) else cur.minusDays(1)
        val minDate = java.time.LocalDate.of(2026, 1, 1)
        val today = java.time.LocalDate.now()
        if (next.isBefore(minDate) || next.isAfter(today)) return
        isDayAnimating = true
        state.selectedDate = next.toString()
        repo.save(state)
        nutritionViewModel.loadActiveCaloriesForDate(next)
        animateDayChange(delta) { applyModeTabsSelection() }
    }

    /** Свайп-анимация перелистывания дня: старый контент уезжает, новый приезжает. */
    private fun animateDayChange(delta: Int, onMid: () -> Unit) {
        val content = findViewById<View>(R.id.infoContent) ?: run { onMid(); return }
        val w = content.width.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.widthPixels.toFloat()
        val outX = if (delta > 0) -w else w
        val inX = -outX
        isDayAnimating = true
        // 1. Старый контент уезжает
        content.animate()
            .translationX(outX)
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                // 2. Перерисовка с новой датой (вне экрана)
                content.translationX = inX
                content.alpha = 0f
                onMid()
                // 3. Новый контент приезжает
                content.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                    isDayAnimating = false
                    // Дотянуть UI до актуального значения активных ккал,
                    // если они пришли во время анимации (observer был заблокирован).
                    if (currentModeTab == ModeTab.INFO) renderInfoContent()
                }
                    .start()
            }
            .start()
    }

    /** Свайп между «Питание» (лево) и «Покупки» (право):
     *  - дровер открыт → свайп закрывает его и прыгает на Питание (повторный → Покупки)
     *  - с Питания влево → открыть дровер
     *  - с Покупок вправо → блок
     *  - в остальных под-табах (Чат/Параметры/База) — снап к Питание/Покупки. */
    private fun cycleSubTab(delta: Int) {
        if (drawer.isDrawerOpen(android.view.Gravity.START)) {
            // свайп «вправо» (из меню) → закрыть и вернуть туда, где были
            val restore = tabBeforeDrawer ?: ModeTab.INFO
            drawer.closeDrawer(android.view.Gravity.START)
            tabBeforeDrawer = null
            if (currentModeTab != restore) {
                currentModeTab = restore
                applyModeTabsSelection()
            }
            return
        }
        if (currentChat()?.mode == null) return
        // Две независимые группы табов: внешние (Питание|Купить) и БД (Продукты|Блюда).
        val group = when (currentModeTab) {
            ModeTab.INFO -> listOf(ModeTab.INFO)
            ModeTab.PRODUCTS, ModeTab.DISHES -> listOf(ModeTab.PRODUCTS, ModeTab.DISHES)
            else -> return
        }
        val idx = group.indexOf(currentModeTab)
        if (delta == 1) {
            // Свайп «вправо» (= палец справа налево) → следующий таб в группе
            if (idx < group.size - 1) {
                currentModeTab = group[idx + 1]
                applyModeTabsSelection()
            }
            // иначе — крайний правый, ничего
        } else if (delta == -1) {
            // Свайп «влево» (= палец слева направо) на крайнем левом теперь
            // ничего не делает (раньше открывал drawer — убрали, drawer
            // открывается только по бургеру). Иначе — переход на предыдущий таб.
            if (idx > 0) {
                currentModeTab = group[idx - 1]
                applyModeTabsSelection()
            }
        }
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

    private var currentAttachSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    /** Открыть нижнее меню скрепки (камера + последние фото). */
    private fun openAttachSheet() {
        // уже открыто — не плодим
        if (currentAttachSheet?.isShowing == true) return
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
        sheet.setOnDismissListener { currentAttachSheet = null }
        currentAttachSheet = sheet

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
