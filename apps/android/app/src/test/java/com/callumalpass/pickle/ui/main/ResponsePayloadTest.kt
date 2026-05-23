package com.callumalpass.pickle.ui.main

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
  fun buildPayloadKeepsArraySelectionsAsJsonArrays() {
    val payload =
      buildPayload(
        arrayApprovalSchema(),
        mapOf("decision" to "approve", "approved_items" to """["issue-1","issue-3"]"""),
      ) as JsonObject

    assertEquals("""["issue-1","issue-3"]""", payload["approved_items"].toString())
  }

  @Test
  fun validateDraftRejectsInvalidArrayBeforeSubmit() {
    val empty = validateResponseDraft(arrayApprovalSchema(), mapOf("decision" to "approve"))
    assertEquals("Select at least one item", empty.errors["approved_items"])

    val invalid =
      validateResponseDraft(
        arrayApprovalSchema(),
        mapOf("decision" to "approve", "approved_items" to """["issue-4"]"""),
      )
    assertTrue(invalid.errors["approved_items"].orEmpty().contains("not allowed"))
  }

  @Test
  fun parseTagsNormalizesHashesAndDuplicates() {
    assertEquals(listOf("ops", "urgent", "review"), parseTagInput("#ops, urgent ops\nreview"))
  }

  private fun arrayApprovalSchema(): JsonObject =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to
          JsonObject(
            mapOf(
              "decision" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf(JsonPrimitive("approve"), JsonPrimitive("reject"))))),
              "approved_items" to
                JsonObject(
                  mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("string"),
                          "enum" to JsonArray(listOf(JsonPrimitive("issue-1"), JsonPrimitive("issue-2"), JsonPrimitive("issue-3"))),
                        ),
                      ),
                    "minItems" to JsonPrimitive(1),
                  ),
                ),
            ),
          ),
        "required" to JsonArray(listOf(JsonPrimitive("decision"), JsonPrimitive("approved_items"))),
      ),
    )
}
