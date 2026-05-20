package com.callumalpass.wickle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.callumalpass.wickle.data.ConnectionSettings
import com.callumalpass.wickle.data.WickleSettings
import com.callumalpass.wickle.theme.WickleTheme
import com.callumalpass.wickle.ui.main.MainScreen

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applyIntentSettings()
    enableEdgeToEdge()
    setContent {
      WickleTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainScreen()
        }
      }
    }
  }

  private fun applyIntentSettings() {
    val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
    val token = intent.getStringExtra(EXTRA_TOKEN)
    if (!serverUrl.isNullOrBlank() || !token.isNullOrBlank()) {
      val current = WickleSettings(this).load()
      WickleSettings(this)
        .save(
          ConnectionSettings(
            serverUrl = serverUrl?.takeIf { it.isNotBlank() } ?: current.serverUrl,
            token = token?.takeIf { it.isNotBlank() } ?: current.token,
          ),
        )
    }
  }

  companion object {
    const val EXTRA_SERVER_URL = "wickle_server_url"
    const val EXTRA_TOKEN = "wickle_token"
  }
}
