package com.callumalpass.pickle.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.callumalpass.pickle.data.PickleRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val responseJson = Json { ignoreUnknownKeys = true }

@Composable
internal fun ResponseForm(
  request: PickleRequest,
  sending: Boolean,
  onRespond: (PickleRequest, JsonElement) -> Unit,
) {
  val values = remember(request.id) { mutableStateMapOf<String, String>() }
  var fieldErrors by remember(request.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
  val properties = responseProperties(request.schema)
  val required = responseRequiredFields(request.schema)

  fun updateValue(name: String, value: String) {
    values[name] = value
    if (fieldErrors.containsKey(name)) {
      fieldErrors = fieldErrors - name
    }
  }

  fun submit(overrides: Map<String, String> = emptyMap()) {
    overrides.forEach { (name, value) -> values[name] = value }
    val draft = values.toMap() + overrides
    val result = validateResponseDraft(request.schema, draft)
    if (result.errors.isNotEmpty()) {
      fieldErrors = result.errors
      return
    }
    fieldErrors = emptyMap()
    onRespond(request, result.payload)
  }

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
        FieldEditor(
          name = name,
          property = property,
          required = required.contains(name),
          value = values[name].orEmpty(),
          error = fieldErrors[name],
          enabled = !sending,
          onValueChange = { updateValue(name, it) },
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
          onClick = { submit() },
          enabled = !sending,
          shape = RoundedCornerShape(7.dp),
        ) {
          Text(if (sending) "Sending" else "Submit")
        }
        if (properties["decision"] != null) {
          OutlinedButton(
            onClick = { submit(mapOf("decision" to "reject")) },
            enabled = !sending,
            shape = RoundedCornerShape(7.dp),
          ) {
            Text("Reject")
          }
        }
      }
    }
  }
}

@Composable
private fun FieldEditor(
  name: String,
  property: JsonObject,
  required: Boolean,
  value: String,
  error: String?,
  enabled: Boolean,
  onValueChange: (String) -> Unit,
) {
  val enumValues = property.enumDisplayValues()
  val type = property.schemaType()
  Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
    when {
      type == "array" -> ArrayFieldEditor(name, property, required, value, error, enabled, onValueChange)
      enumValues.isNotEmpty() -> EnumFieldEditor(name, enumValues, required, value, error, enabled, onValueChange)
      type == "boolean" -> BooleanFieldEditor(name, required, value, error, enabled, onValueChange)
      else -> TextFieldEditor(name, property, required, value, error, enabled, onValueChange)
    }
  }
}

@Composable
private fun EnumFieldEditor(
  name: String,
  enumValues: List<String>,
  required: Boolean,
  value: String,
  error: String?,
  enabled: Boolean,
  onValueChange: (String) -> Unit,
) {
  FieldLabel(name, required)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    enumValues.forEach { option ->
      FilterChip(
        selected = value == option,
        enabled = enabled,
        onClick = { onValueChange(option) },
        label = { Text(option) },
      )
    }
  }
  ValidationMessage(error)
}

@Composable
private fun BooleanFieldEditor(
  name: String,
  required: Boolean,
  value: String,
  error: String?,
  enabled: Boolean,
  onValueChange: (String) -> Unit,
) {
  FieldLabel(name, required)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Checkbox(
      checked = value.toBoolean(),
      enabled = enabled,
      onCheckedChange = { onValueChange(it.toString()) },
    )
    Text(if (value.toBoolean()) "Yes" else "No")
  }
  ValidationMessage(error)
}

@Composable
private fun TextFieldEditor(
  name: String,
  property: JsonObject,
  required: Boolean,
  value: String,
  error: String?,
  enabled: Boolean,
  onValueChange: (String) -> Unit,
) {
  val type = property.schemaType()
  FieldLabel(name, required)
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    isError = error != null,
    singleLine = false,
    minLines = if (name.contains("comment", ignoreCase = true)) 3 else 1,
    keyboardOptions =
      KeyboardOptions(
        keyboardType =
          when (type) {
            "integer", "number" -> KeyboardType.Number
            else -> KeyboardType.Text
          },
      ),
    modifier = Modifier.fillMaxWidth(),
  )
  ValidationMessage(error)
}

@Composable
private fun ArrayFieldEditor(
  name: String,
  property: JsonObject,
  required: Boolean,
  value: String,
  error: String?,
  enabled: Boolean,
  onValueChange: (String) -> Unit,
) {
  val itemSchema = property.itemSchema()
  val enumValues = itemSchema.enumDisplayValues()
  val selected = arrayStringValues(value).filter { it.isNotBlank() }
  val minItems = property.minItems()

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FieldLabel(name, required)
      SelectionCount(selected.size, minItems)
    }
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (enumValues.isNotEmpty()) {
          ArrayEnumEditor(enumValues, selected, enabled, onValueChange)
        } else if (itemSchema.schemaType("string") in setOf("string", "integer", "number")) {
          ArrayPrimitiveEditor(itemSchema, value, enabled, onValueChange)
        } else {
          OutlinedTextField(
            value = value.ifBlank { "[]" },
            onValueChange = onValueChange,
            enabled = enabled,
            isError = error != null,
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
    ValidationMessage(error)
  }
}

@Composable
private fun ArrayEnumEditor(
  enumValues: List<String>,
  selected: List<String>,
  enabled: Boolean,
  onValueChange: (String) -> Unit,
) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    enumValues.forEach { option ->
      val isSelected = selected.contains(option)
      FilterChip(
        selected = isSelected,
        enabled = enabled,
        onClick = {
          val next =
            if (isSelected) selected.filterNot { it == option }
            else selected + option
          onValueChange(encodeStringArray(next))
        },
        label = { Text(option) },
      )
    }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TextButton(
      onClick = { onValueChange(encodeStringArray(enumValues)) },
      enabled = enabled && selected.size < enumValues.size,
    ) {
      Text("All")
    }
    TextButton(
      onClick = { onValueChange(encodeStringArray(emptyList())) },
      enabled = enabled && selected.isNotEmpty(),
    ) {
      Text("Clear")
    }
  }
}

@Composable
private fun ArrayPrimitiveEditor(
  itemSchema: JsonObject,
  value: String,
  enabled: Boolean,
  onValueChange: (String) -> Unit,
) {
  val itemType = itemSchema.schemaType("string")
  val rows = arrayStringValues(value).ifEmpty { listOf("") }
  rows.forEachIndexed { index, item ->
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = item,
        onValueChange = { onValueChange(updateArrayItem(value, index, it)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(
            keyboardType =
              when (itemType) {
                "integer", "number" -> KeyboardType.Number
                else -> KeyboardType.Text
              },
          ),
        modifier = Modifier.weight(1f),
      )
      IconButton(
        onClick = { onValueChange(removeArrayItem(value, index)) },
        enabled = enabled && (rows.size > 1 || item.isNotBlank()),
      ) {
        Icon(Icons.Rounded.Close, contentDescription = "Remove item")
      }
    }
  }
  OutlinedButton(
    onClick = { onValueChange(encodeStringArray(arrayStringValues(value) + "")) },
    enabled = enabled,
    shape = RoundedCornerShape(7.dp),
  ) {
    Icon(Icons.Rounded.Add, contentDescription = null)
    Text("Item", modifier = Modifier.padding(start = 8.dp))
  }
}

@Composable
private fun FieldLabel(name: String, required: Boolean) {
  Text(if (required) "$name *" else name, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun SelectionCount(count: Int, minItems: Int) {
  val text = if (minItems > 0) "$count/$minItems" else "$count selected"
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = MaterialTheme.colorScheme.primaryContainer,
  ) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
  }
}

@Composable
private fun ValidationMessage(error: String?) {
  if (error.isNullOrBlank()) return
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(
      Icons.Rounded.ErrorOutline,
      contentDescription = null,
      modifier = Modifier.size(16.dp),
      tint = MaterialTheme.colorScheme.tertiary,
    )
    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
  }
}

internal data class DraftValidationResult(
  val payload: JsonElement,
  val errors: Map<String, String>,
)

internal fun validateResponseDraft(schema: JsonObject, values: Map<String, String>): DraftValidationResult {
  val properties = responseProperties(schema)
  val required = responseRequiredFields(schema)
  val errors = linkedMapOf<String, String>()
  properties.forEach { (name, raw) ->
    val property = raw as? JsonObject ?: JsonObject(emptyMap())
    validateField(name, property, values[name].orEmpty(), required.contains(name))?.let { errors[name] = it }
  }
  if (errors.isNotEmpty()) {
    return DraftValidationResult(JsonObject(emptyMap()), errors)
  }
  return DraftValidationResult(buildPayload(schema, values), emptyMap())
}

internal fun buildPayload(schema: JsonObject, values: Map<String, String>): JsonElement {
  val properties = responseProperties(schema)
  val required = responseRequiredFields(schema)
  return buildJsonObject {
    properties.forEach { (name, raw) ->
      val property = raw as? JsonObject ?: JsonObject(emptyMap())
      val type = property.schemaType()
      val value = values[name].orEmpty()
      if (!required.contains(name) && !hasValueForType(type, value)) return@forEach
      when (type) {
        "boolean" -> put(name, JsonPrimitive(value.toBooleanStrictOrNull() ?: false))
        "integer" -> put(name, JsonPrimitive(value.toLongOrNull() ?: 0L))
        "number" -> put(name, JsonPrimitive(value.toDoubleOrNull() ?: 0.0))
        "array" -> put(name, buildArrayPayload(property, value))
        "object" -> put(name, parseObjectValue(value) ?: JsonObject(emptyMap()))
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

private fun validateField(name: String, property: JsonObject, value: String, required: Boolean): String? {
  val type = property.schemaType()
  if (required && type !in setOf("array", "boolean") && value.isBlank()) {
    return if (type == "array") "Add at least one item" else "Required"
  }
  if (!required && value.isBlank()) return null
  return when (type) {
    "integer" -> if (value.toLongOrNull() == null) "Enter a whole number" else null
    "number" -> if (value.toDoubleOrNull() == null) "Enter a number" else null
    "array" -> validateArrayField(property, value)
    "object" -> if (parseObjectValue(value) == null) "Enter a JSON object" else null
    else -> validateStringField(property, value)
  }
}

private fun validateStringField(property: JsonObject, value: String): String? {
  val allowed = property.enumElements()
  if (allowed.isNotEmpty() && allowed.none { it == JsonPrimitive(value) }) {
    return "Choose an allowed value"
  }
  return null
}

private fun validateArrayField(property: JsonObject, value: String): String? {
  val array = parseArrayValue(value) ?: return "Enter a JSON array"
  val payload = buildArrayPayload(property, value)
  val minItems = property.minItems()
  if (payload.size < minItems) {
    return if (minItems == 1) "Select at least one item" else "Select at least $minItems items"
  }
  val itemSchema = property.itemSchema()
  payload.forEachIndexed { index, item ->
    validateJsonValue("Item ${index + 1}", itemSchema, item)?.let { return it }
  }
  return if (array.size == payload.size) null else null
}

private fun validateJsonValue(label: String, property: JsonObject, value: JsonElement): String? {
  val type = property.schemaType("")
  if (type.isNotBlank() && !matchesJsonType(value, type)) {
    return "$label must be ${type.article()} $type"
  }
  val allowed = property.enumElements()
  if (allowed.isNotEmpty() && allowed.none { it == value }) {
    return "$label is not allowed"
  }
  return null
}

private fun matchesJsonType(value: JsonElement, type: String): Boolean =
  when (type) {
    "string" -> (value as? JsonPrimitive)?.isString == true
    "boolean" -> (value as? JsonPrimitive)?.booleanOrNull != null
    "number" -> (value as? JsonPrimitive)?.doubleOrNull != null
    "integer" -> (value as? JsonPrimitive)?.longOrNull != null
    "object" -> value is JsonObject
    "array" -> value is JsonArray
    else -> true
  }

private fun buildArrayPayload(property: JsonObject, value: String): JsonArray {
  val itemSchema = property.itemSchema()
  val itemType = itemSchema.schemaType("string")
  val items = parseArrayValue(value) ?: JsonArray(emptyList())
  return JsonArray(
    items.mapNotNull { item ->
      val content = (item as? JsonPrimitive)?.contentOrNull
      if (itemType in setOf("string", "integer", "number") && content.isNullOrBlank()) {
        null
      } else {
        coerceArrayItem(item, itemType)
      }
    },
  )
}

private fun coerceArrayItem(item: JsonElement, itemType: String): JsonElement {
  val content = (item as? JsonPrimitive)?.contentOrNull
  return when (itemType) {
    "boolean" -> JsonPrimitive(content?.toBooleanStrictOrNull() ?: (item as? JsonPrimitive)?.booleanOrNull ?: false)
    "integer" -> JsonPrimitive(content?.toLongOrNull() ?: (item as? JsonPrimitive)?.longOrNull ?: 0L)
    "number" -> JsonPrimitive(content?.toDoubleOrNull() ?: (item as? JsonPrimitive)?.doubleOrNull ?: 0.0)
    "object" -> item as? JsonObject ?: parseObjectValue(content.orEmpty()) ?: JsonObject(emptyMap())
    "array" -> item as? JsonArray ?: parseArrayValue(content.orEmpty()) ?: JsonArray(emptyList())
    else -> JsonPrimitive(content.orEmpty())
  }
}

private fun hasValueForType(type: String, value: String): Boolean =
  when (type) {
    "array" -> buildArrayPayload(JsonObject(emptyMap()), value).isNotEmpty()
    else -> value.isNotBlank()
  }

private fun responseProperties(schema: JsonObject): JsonObject {
  val properties = schema["properties"] as? JsonObject
  if (properties != null && properties.isNotEmpty()) return properties
  return JsonObject(
    mapOf(
      "decision" to
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to JsonArray(listOf(JsonPrimitive("approve"), JsonPrimitive("reject"), JsonPrimitive("revise"))),
          ),
        ),
    ),
  )
}

private fun responseRequiredFields(schema: JsonObject): Set<String> {
  val properties = schema["properties"] as? JsonObject
  if (properties == null || properties.isEmpty()) return setOf("decision")
  return (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
}

private fun JsonObject.schemaType(default: String = "string"): String =
  (this["type"] as? JsonPrimitive)?.contentOrNull ?: default

private fun JsonObject.itemSchema(): JsonObject =
  this["items"] as? JsonObject ?: JsonObject(mapOf("type" to JsonPrimitive("string")))

private fun JsonObject.minItems(): Int =
  (this["minItems"] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject.enumElements(): List<JsonElement> =
  (this["enum"] as? JsonArray)?.toList().orEmpty()

private fun JsonObject.enumDisplayValues(): List<String> =
  enumElements().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun parseArrayValue(value: String): JsonArray? {
  if (value.isBlank()) return JsonArray(emptyList())
  return runCatching { responseJson.parseToJsonElement(value) as? JsonArray }.getOrNull()
}

private fun parseObjectValue(value: String): JsonObject? {
  if (value.isBlank()) return null
  return runCatching { responseJson.parseToJsonElement(value) as? JsonObject }.getOrNull()
}

private fun arrayStringValues(value: String): List<String> =
  parseArrayValue(value)
    ?.map { item -> (item as? JsonPrimitive)?.contentOrNull ?: item.toString() }
    .orEmpty()

private fun updateArrayItem(value: String, index: Int, item: String): String {
  val items = arrayStringValues(value).toMutableList()
  while (items.size <= index) items.add("")
  items[index] = item
  return encodeStringArray(items)
}

private fun removeArrayItem(value: String, index: Int): String {
  val items = arrayStringValues(value).toMutableList()
  if (index in items.indices) items.removeAt(index)
  return encodeStringArray(items)
}

private fun encodeStringArray(items: List<String>): String =
  JsonArray(items.map { JsonPrimitive(it) }).toString()

private fun String.article(): String =
  if (firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
