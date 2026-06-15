package com.assistant.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup

class SettingsActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private lateinit var sortLabel: TextView
    private var activeCategory: Settings.Category = Settings.Category.TEXT
    private var activeSort: Settings.SortMode = Settings.SortMode.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        container = findViewById(R.id.listContainer)
        sortLabel = findViewById(R.id.sortLabel)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        activeCategory = Settings.Category.TEXT
        activeSort = Settings.getSort(this)
        sortLabel.text = activeSort.label

        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.categoryToggle)
        toggle.check(R.id.tabText)
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            activeCategory = when (checkedId) {
                R.id.tabVoice -> Settings.Category.VOICE
                R.id.tabImage -> Settings.Category.IMAGE
                else -> Settings.Category.TEXT
            }
            renderList()
        }

        findViewById<View>(R.id.sortRow).setOnClickListener { showSortMenu(it) }
        renderList()
    }

    private fun showSortMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        Settings.SortMode.values().forEachIndexed { idx, mode ->
            popup.menu.add(0, idx, idx, mode.label)
        }
        popup.setOnMenuItemClickListener { item ->
            val mode = Settings.SortMode.values()[item.itemId]
            activeSort = mode
            Settings.setSort(this, mode)
            sortLabel.text = mode.label
            renderList()
            true
        }
        popup.show()
    }

    private fun renderList() {
        val selectedId = Settings.get(this, activeCategory)
        val inflater = LayoutInflater.from(this)
        container.removeAllViews()

        if (activeCategory == Settings.Category.TEXT) {
            val cfg = inflater.inflate(R.layout.item_setting_local_server, container, false)
            val urlEt = cfg.findViewById<EditText>(R.id.localUrl)
            val modelEt = cfg.findViewById<EditText>(R.id.localModel)
            val keyEt = cfg.findViewById<EditText>(R.id.localApiKey)
            urlEt.setText(Settings.getLocalUrl(this))
            modelEt.setText(Settings.getLocalModelName(this))
            keyEt.setText(Settings.getLocalApiKey(this))
            fun saveLocal() {
                Settings.setLocalUrl(this, urlEt.text.toString())
                Settings.setLocalModelName(this, modelEt.text.toString())
                Settings.setLocalApiKey(this, keyEt.text.toString())
            }
            urlEt.setOnFocusChangeListener { _, has -> if (!has) saveLocal() }
            modelEt.setOnFocusChangeListener { _, has -> if (!has) saveLocal() }
            keyEt.setOnFocusChangeListener { _, has -> if (!has) saveLocal() }
            container.addView(cfg)
        }

        // шапка
        val headerView = inflater.inflate(R.layout.item_setting_header, container, false)
        val (hName, hIn, hOut) = Settings.header(activeCategory)
        headerView.findViewById<TextView>(R.id.headerName).text = hName
        headerView.findViewById<TextView>(R.id.headerIn).text = hIn
        headerView.findViewById<TextView>(R.id.headerOut).text = hOut
        container.addView(headerView)

        for (opt in Settings.sortedOptions(activeCategory, activeSort)) {
            val row = inflater.inflate(R.layout.item_setting_model, container, false)
            val label = row.findViewById<TextView>(R.id.modelLabel)
            val inCol = row.findViewById<TextView>(R.id.modelIn)
            val outCol = row.findViewById<TextView>(R.id.modelOut)
            val check = row.findViewById<ImageView>(R.id.modelCheck)
            label.text = opt.label
            inCol.text = Settings.fmtPrice(opt.inputPrice)
            outCol.text = Settings.fmtPrice(opt.outputPrice)
            val isSelected = opt.id == selectedId
            check.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            label.setTypeface(
                label.typeface,
                if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            row.setOnClickListener {
                if (activeCategory == Settings.Category.TEXT) {
                    val cfg = container.getChildAt(0)
                    cfg?.findViewById<EditText>(R.id.localUrl)?.let {
                        Settings.setLocalUrl(this, it.text.toString())
                    }
                    cfg?.findViewById<EditText>(R.id.localModel)?.let {
                        Settings.setLocalModelName(this, it.text.toString())
                    }
                    cfg?.findViewById<EditText>(R.id.localApiKey)?.let {
                        Settings.setLocalApiKey(this, it.text.toString())
                    }
                }
                Settings.set(this, activeCategory, opt.id)
                renderList()
            }
            container.addView(row)
        }
    }
}
