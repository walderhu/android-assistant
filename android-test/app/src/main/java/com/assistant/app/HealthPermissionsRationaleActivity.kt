package com.assistant.app

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class HealthPermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AlertDialog.Builder(this)
            .setTitle("Доступ к активным калориям")
            .setMessage("Приложение читает только активные потраченные ккал за день из Health Connect.")
            .setPositiveButton("Ок") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
