package com.callumalpass.wickle.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.callumalpass.wickle.data.ConnectionSettings
import com.callumalpass.wickle.data.WickleRepository
import com.callumalpass.wickle.data.WickleRequest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

class MainScreenViewModel(private val repository: WickleRepository) : ViewModel() {
  val uiState: StateFlow<com.callumalpass.wickle.data.WickleUiState> = repository.state

  fun refresh() {
    viewModelScope.launch { repository.refresh() }
  }

  fun testConnection() {
    viewModelScope.launch { repository.testConnection() }
  }

  fun saveSettings(settings: ConnectionSettings) {
    repository.updateSettings(settings)
  }

  fun select(request: WickleRequest) {
    repository.select(request)
  }

  fun respond(request: WickleRequest, payload: JsonElement) {
    viewModelScope.launch { repository.respond(request, payload) }
  }

  companion object {
    fun factory(context: Context): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
          MainScreenViewModel(WickleRepository(context.applicationContext)) as T
      }
  }
}
