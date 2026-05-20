package com.callumalpass.pickle.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.callumalpass.pickle.data.ConnectionSettings
import com.callumalpass.pickle.data.PickleAttachment
import com.callumalpass.pickle.data.PickleRepository
import com.callumalpass.pickle.data.PickleRequest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

class MainScreenViewModel(private val repository: PickleRepository) : ViewModel() {
  val uiState: StateFlow<com.callumalpass.pickle.data.PickleUiState> = repository.state

  fun refresh() {
    viewModelScope.launch { repository.refresh() }
  }

  fun testConnection() {
    viewModelScope.launch { repository.testConnection() }
  }

  fun saveSettings(settings: ConnectionSettings) {
    repository.updateSettings(settings)
  }

  fun setPushEnabled(enabled: Boolean) {
    repository.setPushEnabled(enabled)
  }

  fun select(request: PickleRequest) {
    repository.select(request)
  }

  fun loadAttachment(request: PickleRequest, attachment: PickleAttachment) {
    viewModelScope.launch { repository.loadAttachment(request, attachment) }
  }

  fun respond(request: PickleRequest, payload: JsonElement) {
    viewModelScope.launch { repository.respond(request, payload) }
  }

  fun dismissMessage(request: PickleRequest) {
    viewModelScope.launch { repository.dismissMessage(request) }
  }

  fun sendMessage(title: String, body: String, tags: List<String>, onSent: () -> Unit = {}) {
    viewModelScope.launch {
      if (repository.sendMessage(title, body, tags)) onSent()
    }
  }

  fun clearNotice() {
    repository.clearNotice()
  }

  companion object {
    fun factory(context: Context): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
          MainScreenViewModel(PickleRepository(context.applicationContext)) as T
      }
  }
}
