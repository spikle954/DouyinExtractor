package io.github.douyinextractor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.douyinextractor.ui.HomeScreen
import io.github.douyinextractor.ui.MainViewModel
import io.github.douyinextractor.ui.ShiYingTheme

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences("extraction_preferences", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model.setAutoBrowserEnabled(preferences.getBoolean("auto_browser", true))
        if (!model.initialIntentHandled) {
            model.initialIntentHandled = true
            receiveShare(intent)
        }
        setContent { ShiYingTheme {
            HomeScreen(model) { enabled ->
                model.setAutoBrowserEnabled(enabled)
                preferences.edit().putBoolean("auto_browser", enabled).apply()
            }
        } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    private fun receiveShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            model.receiveShare(text)
        }
    }
}
