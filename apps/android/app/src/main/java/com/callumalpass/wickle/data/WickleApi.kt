package com.callumalpass.wickle.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class WickleApi(private val client: OkHttpClient = OkHttpClient()) {
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }

  suspend fun health(settings: ConnectionSettings): Boolean =
    withContext(Dispatchers.IO) {
      val request = Request.Builder().url(settings.serverUrl.trimEnd('/') + "/health").get().build()
      client.newCall(request).execute().use { response -> response.isSuccessful }
    }

  suspend fun inbox(settings: ConnectionSettings, status: String = "pending"): List<WickleRequest> =
    withContext(Dispatchers.IO) {
      val request =
        authed(settings, settings.serverUrl.trimEnd('/') + "/api/v1/inbox?status=$status")
          .get()
          .build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Inbox failed: HTTP ${response.code}")
        json.decodeFromString<InboxResponse>(response.body.string()).requests
      }
    }

  suspend fun request(settings: ConnectionSettings, id: String): WickleRequest =
    withContext(Dispatchers.IO) {
      val request = authed(settings, settings.serverUrl.trimEnd('/') + "/api/v1/requests/$id").get().build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Request failed: HTTP ${response.code}")
        json.decodeFromString<WickleRequest>(response.body.string())
      }
    }

  suspend fun respond(settings: ConnectionSettings, id: String, body: SubmitResponseRequest): WickleRequest =
    withContext(Dispatchers.IO) {
      val requestBody = json.encodeToString(body).toRequestBody("application/json".toMediaType())
      val request =
        authed(settings, settings.serverUrl.trimEnd('/') + "/api/v1/requests/$id/responses")
          .post(requestBody)
          .build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Response failed: HTTP ${response.code}: ${response.body.string()}")
        json.decodeFromString<WickleRequest>(response.body.string())
      }
    }

  fun streamRequest(settings: ConnectionSettings): Request {
    val url =
      settings.serverUrl
        .trimEnd('/')
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/api/v1/stream"
    return authed(settings, url).build()
  }

  private fun authed(settings: ConnectionSettings, url: String): Request.Builder {
    val builder = Request.Builder().url(url)
    if (settings.token.isNotBlank()) builder.header("Authorization", "Bearer ${settings.token}")
    return builder
  }
}
