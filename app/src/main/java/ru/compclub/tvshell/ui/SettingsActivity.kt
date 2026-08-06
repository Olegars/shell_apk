package ru.compclub.tvshell.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Legacy entry — redirects to Setup (registration + fans). */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }
}
