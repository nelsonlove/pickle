package com.callumalpass.wickle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class WickleRequest(
  val id: String,
  val source: String,
  val kind: String,
  val title: String,
  val body: String = "",
  val schema: JsonObject = JsonObject(emptyMap()),
  val status: String,
  val priority: String = "normal",
  val tags: List<String> = emptyList(),
  val links: List<WickleLink> = emptyList(),
  val metadata: JsonElement? = null,
  @SerialName("created_at") val createdAt: String,
  @SerialName("updated_at") val updatedAt: String,
  @SerialName("answered_at") val answeredAt: String? = null,
  val response: WickleResponse? = null,
)

@Serializable data class WickleLink(val label: String, val url: String? = null, val path: String? = null)

@Serializable
data class WickleResponse(
  @SerialName("request_id") val requestId: String,
  val responder: String,
  val payload: JsonElement,
  @SerialName("created_at") val createdAt: String,
)

@Serializable data class InboxResponse(val requests: List<WickleRequest>)

@Serializable data class EventsResponse(val events: List<WickleEvent>)

@Serializable
data class WickleEvent(
  val id: Long,
  val type: String,
  @SerialName("request_id") val requestId: String? = null,
  val payload: JsonElement? = null,
  @SerialName("created_at") val createdAt: String,
)

@Serializable data class SubmitResponseRequest(val responder: String, val payload: JsonElement)

@Serializable
data class CreateRequestPayload(
  val source: String,
  val kind: String,
  val title: String,
  val body: String = "",
  val schema: JsonObject = defaultMessageSchema(),
  val priority: String = "normal",
  val tags: List<String> = emptyList(),
  val links: List<WickleLink> = emptyList(),
  val metadata: JsonObject = JsonObject(emptyMap()),
)

fun defaultMessageSchema(): JsonObject =
  JsonObject(
    mapOf(
      "type" to JsonPrimitive("object"),
      "properties" to JsonObject(emptyMap()),
      "additionalProperties" to JsonPrimitive(true),
    ),
  )
