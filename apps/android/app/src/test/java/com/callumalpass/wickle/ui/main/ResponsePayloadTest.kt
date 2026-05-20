package com.callumalpass.wickle.ui.main

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsePayloadTest {
  @Test
  fun buildPayloadKeepsDecisionAndCommentShape() {
    val payload = buildPayload(sampleApprovalSchema(), mapOf("decision" to "approve", "comment" to "Looks right."))
    val obj = payload as JsonObject
    assertEquals("approve", obj["decision"]?.toString()?.trim('"'))
    assertEquals("""{"decision":"approve","comment":"Looks right."}""", Json.encodeToString(JsonObject.serializer(), obj))
  }

  @Test
  fun parseTagsNormalizesHashesAndDuplicates() {
    assertEquals(listOf("ops", "urgent", "review"), parseTagInput("#ops, urgent ops\nreview"))
  }
}
