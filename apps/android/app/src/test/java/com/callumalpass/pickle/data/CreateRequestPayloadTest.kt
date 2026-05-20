package com.callumalpass.pickle.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateRequestPayloadTest {
  @Test
  fun messagePayloadIncludesExplicitSourceAndKind() {
    val encoded =
      Json.encodeToString(
        CreateRequestPayload(
          source = "callum",
          kind = "message",
          title = "hello",
          tags = listOf("ops"),
        ),
      )

    assertTrue(encoded.contains(""""source":"callum""""))
    assertTrue(encoded.contains(""""kind":"message""""))
    assertTrue(encoded.contains(""""tags":["ops"]"""))
  }

  @Test
  fun dismissMessagePayloadAcknowledgesWithoutStructuredDecision() {
    val payload = dismissMessagePayload() as JsonObject
    assertEquals(true, payload["acknowledged"]?.jsonPrimitive?.booleanOrNull)
  }
}
