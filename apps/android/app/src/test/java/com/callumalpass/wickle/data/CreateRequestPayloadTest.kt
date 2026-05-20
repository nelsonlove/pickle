package com.callumalpass.wickle.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
}
