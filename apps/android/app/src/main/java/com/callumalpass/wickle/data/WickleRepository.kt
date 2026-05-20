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
  val sending: Boolean = false,
  val connected: Boolean? = null,
  val pushEnabled: Boolean = false,
  val notice: String? = null,
  val error: String? = null,
)

class WickleRepository(context: Context, private val api: WickleApi = WickleApi()) {
  private val settingsStore = WickleSettings(context)
  private val mutableState =
    MutableStateFlow(
      WickleUiState(
        settings = settingsStore.load(),
        pushEnabled = settingsStore.isPushEnabled(),
      ),
    )
  val state: StateFlow<WickleUiState> = mutableState

  fun updateSettings(settings: ConnectionSettings) {
    settingsStore.save(settings)
    mutableState.update { it.copy(settings = settings, notice = "Settings saved", error = null) }
  }

  fun setPushEnabled(enabled: Boolean) {
    settingsStore.setPushEnabled(enabled)
    mutableState.update {
      it.copy(
        pushEnabled = enabled,
        notice = if (enabled) "Background push enabled" else "Background push stopped",
        error = null,
      )
    }
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
      mutableState.update { it.copy(requests = requests, selected = selected, loading = false, connected = true) }
    } catch (error: Throwable) {
      mutableState.update { it.copy(loading = false, connected = false, error = error.message ?: "Refresh failed") }
    }
  }

  fun select(request: WickleRequest) {
    mutableState.update { it.copy(selected = request) }
  }

  suspend fun sendMessage(title: String, body: String, tags: List<String>): Boolean {
    val trimmedTitle = title.trim()
    if (trimmedTitle.isEmpty()) {
      mutableState.update { it.copy(error = "Title is required") }
      return false
    }
    val settings = settingsStore.load()
    mutableState.update { it.copy(sending = true, error = null, notice = null, settings = settings) }
    return try {
      val created =
        api.createRequest(
          settings,
          CreateRequestPayload(
            source = "callum",
            kind = "message",
            title = trimmedTitle,
            body = body.trim(),
            tags = normalizeTags(tags),
          ),
        )
      mutableState.update { state ->
        state.copy(
          requests = listOf(created) + state.requests.filterNot { it.id == created.id },
          selected = created,
          sending = false,
          connected = true,
          notice = "Message posted",
          error = null,
        )
      }
      true
    } catch (error: Throwable) {
      mutableState.update { it.copy(sending = false, connected = false, error = error.message ?: "Could not post message") }
      false
    }
  }

  suspend fun respond(request: WickleRequest, payload: JsonElement) {
    val settings = settingsStore.load()
    try {
      val updated = api.respond(settings, request.id, SubmitResponseRequest(responder = "callum", payload = payload))
      mutableState.update { state ->
        val remaining = state.requests.filterNot { it.id == updated.id }
        state.copy(requests = remaining, selected = null, notice = "Response sent", error = null)
      }
    } catch (error: Throwable) {
      mutableState.update { it.copy(error = error.message ?: "Could not submit response") }
    }
  }

  fun clearNotice() {
    mutableState.update { it.copy(notice = null, error = null) }
  }

  private fun normalizeTags(tags: List<String>): List<String> {
    val seen = linkedSetOf<String>()
    tags
      .flatMap { it.split(",", " ") }
      .map { it.trim().trimStart('#') }
      .filter { it.isNotEmpty() }
      .forEach { seen.add(it) }
    return seen.toList()
  }
}
