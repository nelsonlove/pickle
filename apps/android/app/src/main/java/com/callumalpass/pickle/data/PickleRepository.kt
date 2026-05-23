package com.callumalpass.pickle.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PickleUiState(
  val settings: ConnectionSettings = ConnectionSettings(),
  val requests: List<PickleRequest> = emptyList(),
  val selected: PickleRequest? = null,
  val attachmentPreviews: Map<String, AttachmentPreview> = emptyMap(),
  val loading: Boolean = false,
  val sending: Boolean = false,
  val connected: Boolean? = null,
  val pushEnabled: Boolean = false,
  val notice: String? = null,
  val error: String? = null,
)

data class AttachmentPreview(
  val loading: Boolean = false,
  val text: String? = null,
  val imageBytes: ByteArray? = null,
  val error: String? = null,
)

class PickleRepository(context: Context, private val api: PickleApi = PickleApi()) {
  private val settingsStore = PickleSettings(context)
  private val mutableState =
    MutableStateFlow(
      PickleUiState(
        settings = settingsStore.load(),
        pushEnabled = settingsStore.isPushEnabled(),
      ),
    )
  val state: StateFlow<PickleUiState> = mutableState

  fun updateSettings(settings: ConnectionSettings) {
    settingsStore.save(settings)
    mutableState.update { it.copy(settings = settingsStore.load(), notice = "Settings saved", error = null) }
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
    mutableState.update { it.copy(error = null, settings = settings) }
    try {
      val ok = api.health(settings)
      mutableState.update {
        it.copy(
          connected = ok,
          notice = if (ok) "Connected to Pickle" else null,
          error = if (ok) null else "Server did not return healthy",
        )
      }
    } catch (error: Throwable) {
      mutableState.update {
        it.copy(
          connected = false,
          error = friendlyConnectionError(error),
        )
      }
    }
  }

  suspend fun refresh(status: String = "pending") {
    val settings = settingsStore.load()
    mutableState.update { it.copy(loading = true, error = null, settings = settings) }
    try {
      val requests = api.inbox(settings, status)
      val selected = mutableState.value.selected?.let { current -> requests.firstOrNull { it.id == current.id } }
      mutableState.update { it.copy(requests = requests, selected = selected, loading = false, connected = true) }
    } catch (error: Throwable) {
      mutableState.update { it.copy(loading = false, connected = false, error = friendlyConnectionError(error)) }
    }
  }

  fun select(request: PickleRequest) {
    mutableState.update { it.copy(selected = request) }
  }

  suspend fun loadAttachment(request: PickleRequest, attachment: PickleAttachment) {
    val existing = mutableState.value.attachmentPreviews[attachment.id]
    if (existing != null && (existing.loading || existing.text != null || existing.imageBytes != null || existing.error != null)) {
      return
    }
    val settings = settingsStore.load()
    mutableState.update { state ->
      state.copy(
        attachmentPreviews = state.attachmentPreviews + (attachment.id to AttachmentPreview(loading = true)),
        error = null,
      )
    }
    try {
      val bytes = api.attachment(settings, request.id, attachment.id)
      val preview =
        when {
          attachment.contentType == "text/plain" || attachment.contentType == "text/markdown" ->
            AttachmentPreview(text = bytes.toString(Charsets.UTF_8))
          attachment.contentType.startsWith("image/") ->
            AttachmentPreview(imageBytes = bytes)
          else ->
            AttachmentPreview(error = "Unsupported attachment type")
        }
      mutableState.update { state ->
        state.copy(attachmentPreviews = state.attachmentPreviews + (attachment.id to preview))
      }
    } catch (error: Throwable) {
      mutableState.update { state ->
        state.copy(
          attachmentPreviews =
            state.attachmentPreviews + (attachment.id to AttachmentPreview(error = friendlyConnectionError(error))),
        )
      }
    }
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
      mutableState.update { it.copy(sending = false, connected = false, error = friendlyConnectionError(error)) }
      false
    }
  }

  suspend fun respond(request: PickleRequest, payload: JsonElement): Boolean {
    return submitResponse(request, payload, "Response sent")
  }

  suspend fun dismissMessage(request: PickleRequest): Boolean {
    return submitResponse(request, dismissMessagePayload(), "Message dismissed")
  }

  private suspend fun submitResponse(request: PickleRequest, payload: JsonElement, successNotice: String): Boolean {
    val settings = settingsStore.load()
    mutableState.update { it.copy(sending = true, error = null, notice = null, settings = settings) }
    try {
      val updated = api.respond(settings, request.id, SubmitResponseRequest(responder = "callum", payload = payload))
      mutableState.update { state ->
        val remaining = state.requests.filterNot { it.id == updated.id }
        state.copy(requests = remaining, selected = null, sending = false, notice = successNotice, error = null)
      }
      return true
    } catch (error: Throwable) {
      mutableState.update { it.copy(sending = false, error = friendlyConnectionError(error)) }
      return false
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

internal fun dismissMessagePayload(): JsonElement =
  buildJsonObject {
    put("acknowledged", JsonPrimitive(true))
  }
