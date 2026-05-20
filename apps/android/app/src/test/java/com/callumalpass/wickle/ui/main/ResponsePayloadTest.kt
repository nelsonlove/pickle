package com.callumalpass.wickle.ui.main

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsePayloadTest {
  @Test
  fun jsonObjectKeepsDecisionAndCommentShape() {
    val payload =
      JsonObject(
        mapOf(
          "decision" to JsonPrimitive("approve"),
          "comment" to JsonPrimitive("Looks right."),
        ),
      )
    assertEquals("approve", payload["decision"]?.toString()?.trim('"'))
    assertEquals("""{"decision":"approve","comment":"Looks right."}""", Json.encodeToString(JsonObject.serializer(), payload))
  }
}
