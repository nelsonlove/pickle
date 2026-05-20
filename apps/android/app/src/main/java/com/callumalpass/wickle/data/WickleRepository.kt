package com.callumalpass.wickle.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

data class WickleUiState(
  val settings: ConnectionSettings = ConnectionSettings(),
  val requests: List<WickleRequest> = emptyList(),
  val selected: WickleRequest? = null,
  val loading: Boolean = false,
  val connected: Boolean? = null,
  val error: String? = null,
)

class WickleRepository(context: Context, private val api: WickleApi = WickleApi()) {
  private val settingsStore = WickleSettings(context)
  private val mutableState = MutableStateFlow(WickleUiState(settings = settingsStore.load()))
  val state: StateFlow<WickleUiState> = mutableState

  fun updateSettings(settings: ConnectionSettings) {
    settingsStore.save(settings)
    mutableState.update { it.copy(settings = settings, error = null) }
  }

  suspend fun testConnection() {
    val settings = settingsStore.load()
    val ok = api.health(settings)
    mutableState.update { it.copy(connected = ok, error = if (ok) null else "Server did not return healthy") }
  }

  suspend fun refresh(status: String = "pending") {
    val settings = settingsStore.load()
    mutableState.update { it.copy(loading = true, error = null, settings = settings) }
    try {
      val requests = api.inbox(settings, status)
      val selected = mutableState.value.selected?.let { current -> requests.firstOrNull { it.id == current.id } }
      mutableState.update { it.copy(requests = requests, selected = selected ?: requests.firstOrNull(), loading = false, connected = true) }
    } catch (error: Throwable) {
      mutableState.update { it.copy(loading = false, connected = false, error = error.message ?: "Refresh failed") }
    }
  }

  fun select(request: WickleRequest) {
    mutableState.update { it.copy(selected = request) }
  }

  suspend fun respond(request: WickleRequest, payload: JsonElement) {
    val settings = settingsStore.load()
    try {
      val updated = api.respond(settings, request.id, SubmitResponseRequest(responder = "callum", payload = payload))
      mutableState.update { state ->
        val remaining = state.requests.filterNot { it.id == updated.id }
        state.copy(requests = remaining, selected = remaining.firstOrNull(), error = null)
      }
    } catch (error: Throwable) {
      mutableState.update { it.copy(error = error.message ?: "Could not submit response") }
    }
  }
}
