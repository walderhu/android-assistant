package com.assistant.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val adapter = MessageAdapter()
    private val history = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recycler = findViewById<RecyclerView>(R.id.recyclerMessages)
        val edit = findViewById<EditText>(R.id.editMessage)
        val send = findViewById<ImageButton>(R.id.btnSend)
        val clip = findViewById<ImageButton>(R.id.btnClip)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // Приветствие
        adapter.add(Message("Привет! Чем могу помочь?", isUser = false))

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

        // Заглушки
        clip.setOnClickListener { toast("Прикрепить (заглушка)") }

        send.setOnClickListener {
            val text = edit.text.toString().trim()
            if (text.isEmpty()) {
                toast("Запись голоса (заглушка)")
                return@setOnClickListener
            }

            adapter.add(Message(text, isUser = true))
            history.add("user" to text)
            edit.text.clear()
            recycler.scrollToPosition(adapter.itemCount - 1)

            // Добавляем loading-индикатор ●
            val loading = Message("●", isUser = false, isLoading = true)
            adapter.add(loading)
            recycler.scrollToPosition(adapter.itemCount - 1)

            lifecycleScope.launch {
                try {
                    val reply = OpenRouterClient.send(history)
                    // Заменяем loading на реальный ответ
                    adapter.replace({ it.isLoading }, Message(reply, isUser = false))
                    history.add("assistant" to reply)
                } catch (e: Exception) {
                    adapter.replace({ it.isLoading },
                        Message("Ошибка: ${e.message ?: e.javaClass.simpleName}", isUser = false))
                }
                recycler.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
