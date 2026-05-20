package com.callumalpass.wickle.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ConnectionSettings(
  val serverUrl: String = "http://10.0.2.2:8787",
  val token: String = "",
)

class WickleSettings(context: Context) {
  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences("wickle", Context.MODE_PRIVATE)

  private val flow = MutableStateFlow(load())
  val settings: StateFlow<ConnectionSettings> = flow

  fun load(): ConnectionSettings =
    ConnectionSettings(
      serverUrl = prefs.getString(KEY_SERVER_URL, "http://10.0.2.2:8787") ?: "http://10.0.2.2:8787",
      token = prefs.getString(KEY_TOKEN, "") ?: "",
    )

  fun save(settings: ConnectionSettings) {
    prefs.edit().putString(KEY_SERVER_URL, settings.serverUrl.trim()).putString(KEY_TOKEN, settings.token.trim()).apply()
    flow.value = load()
  }

  companion object {
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_TOKEN = "token"
  }
}
