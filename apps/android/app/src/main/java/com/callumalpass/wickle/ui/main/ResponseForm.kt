package com.callumalpass.wickle.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.callumalpass.wickle.data.WickleRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Composable
internal fun ResponseForm(request: WickleRequest, onRespond: (WickleRequest, JsonElement) -> Unit) {
  val values = remember(request.id) { mutableStateMapOf<String, String>() }
  val properties = request.schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
  val required = request.schema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Response", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      properties.forEach { (name, raw) ->
        val property = raw as? JsonObject ?: JsonObject(emptyMap())
        val enumValues = property["enum"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val type = property["type"]?.jsonPrimitive?.contentOrNull ?: "string"
        FieldEditor(name, type, enumValues, required.contains(name), values)
      }
      if (properties.isEmpty()) {
        FieldEditor("decision", "string", listOf("approve", "reject", "revise"), true, values)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
          onClick = { onRespond(request, buildPayload(request.schema, values)) },
          shape = RoundedCornerShape(7.dp),
        ) {
          Text("Submit")
        }
        OutlinedButton(
          onClick = {
            values["decision"] = "reject"
            onRespond(request, buildPayload(request.schema, values))
          },
          shape = RoundedCornerShape(7.dp),
        ) {
          Text("Reject")
        }
      }
    }
  }
}

@Composable
private fun FieldEditor(
  name: String,
  type: String,
  enumValues: List<String>,
  required: Boolean,
  values: MutableMap<String, String>,
) {
  Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
    Text(if (required) "$name *" else name, style = MaterialTheme.typography.labelLarge)
    if (enumValues.isNotEmpty()) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        enumValues.forEach { option ->
          FilterChip(selected = values[name] == option, onClick = { values[name] = option }, label = { Text(option) })
        }
      }
    } else if (type == "boolean") {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = values[name].toBoolean(), onCheckedChange = { values[name] = it.toString() })
        Text(if (values[name].toBoolean()) "Yes" else "No")
      }
    } else {
      OutlinedTextField(
        value = values[name].orEmpty(),
        onValueChange = { values[name] = it },
        singleLine = false,
        minLines = if (name.contains("comment", ignoreCase = true)) 3 else 1,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

internal fun buildPayload(schema: JsonObject, values: Map<String, String>): JsonElement {
  val properties = schema["properties"]?.jsonObject ?: JsonObject(mapOf("decision" to JsonObject(emptyMap())))
  return buildJsonObject {
    properties.forEach { (name, raw) ->
      val property = raw as? JsonObject ?: JsonObject(emptyMap())
      val type = property["type"]?.jsonPrimitive?.contentOrNull ?: "string"
      val value = values[name].orEmpty()
      when (type) {
        "boolean" -> put(name, JsonPrimitive(value.toBooleanStrictOrNull() ?: false))
        "integer" -> put(name, JsonPrimitive(value.toLongOrNull() ?: 0L))
        "number" -> put(name, JsonPrimitive(value.toDoubleOrNull() ?: 0.0))
        else -> put(name, JsonPrimitive(value))
      }
    }
  }
}

internal fun sampleApprovalSchema(): JsonObject =
  JsonObject(
    mapOf(
      "properties" to
        JsonObject(
          mapOf(
            "decision" to JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(listOf(JsonPrimitive("approve"), JsonPrimitive("reject"), JsonPrimitive("revise"))))),
            "comment" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
          ),
        ),
      "required" to JsonArray(listOf(JsonPrimitive("decision"))),
    ),
  )
