package com.callumalpass.wickle.ui.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import com.callumalpass.wickle.data.ConnectionSettings
import com.callumalpass.wickle.data.WickleLink
import com.callumalpass.wickle.data.WickleRequest
import com.callumalpass.wickle.realtime.WickleRealtimeService
import com.callumalpass.wickle.theme.WickleTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel(factory = MainScreenViewModel.factory(LocalContext.current)),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val notificationLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      WickleRealtimeService.start(context)
    }

  LaunchedEffect(Unit) { viewModel.refresh() }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

  WickleInbox(
    state = state,
    onRefresh = viewModel::refresh,
    onSelect = viewModel::select,
    onSaveSettings = viewModel::saveSettings,
    onTestConnection = viewModel::testConnection,
    onStartRealtime = {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      } else {
        WickleRealtimeService.start(context)
      }
    },
    onStopRealtime = { WickleRealtimeService.stop(context) },
    onRespond = viewModel::respond,
    modifier = modifier.safeDrawingPadding(),
  )
}

@Composable
private fun WickleInbox(
  state: com.callumalpass.wickle.data.WickleUiState,
  onRefresh: () -> Unit,
  onSelect: (WickleRequest) -> Unit,
  onSaveSettings: (ConnectionSettings) -> Unit,
  onTestConnection: () -> Unit,
  onStartRealtime: () -> Unit,
  onStopRealtime: () -> Unit,
  onRespond: (WickleRequest, JsonElement) -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    val compact = maxWidth < 760.dp
    Column(
      modifier = Modifier.fillMaxSize().padding(if (compact) 14.dp else 18.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Header(state.connected, compact, onRefresh, onStartRealtime, onStopRealtime)
      ConnectionPanel(state.settings, state.error, compact, onSaveSettings, onTestConnection)
      if (compact) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          RequestList(state.requests, state.selected?.id, onSelect, Modifier.fillMaxWidth().height(260.dp))
          RequestDetail(state.selected, onRespond, Modifier.fillMaxWidth().weight(1f))
        }
      } else {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          RequestList(state.requests, state.selected?.id, onSelect, Modifier.weight(0.92f).fillMaxSize())
          RequestDetail(state.selected, onRespond, Modifier.weight(1.08f).fillMaxSize())
        }
      }
    }
  }
}

@Composable
private fun Header(
  connected: Boolean?,
  compact: Boolean,
  onRefresh: () -> Unit,
  onStartRealtime: () -> Unit,
  onStopRealtime: () -> Unit,
) {
  if (compact) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Wickle", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
          Text("Agent inbox", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusPill(connected)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(7.dp), modifier = Modifier.weight(1f)) { Text("Refresh") }
        Button(onClick = onStartRealtime, shape = RoundedCornerShape(7.dp), modifier = Modifier.weight(1f)) { Text("Push") }
        TextButton(onClick = onStopRealtime) { Text("Stop") }
      }
    }
  } else {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Wickle", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text("Agent inbox", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      StatusPill(connected)
      Spacer(Modifier.width(10.dp))
      OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(7.dp)) { Text("Refresh") }
      Spacer(Modifier.width(8.dp))
      Button(onClick = onStartRealtime, shape = RoundedCornerShape(7.dp)) { Text("Push") }
      Spacer(Modifier.width(6.dp))
      TextButton(onClick = onStopRealtime) { Text("Stop") }
    }
  }
}

@Composable
private fun StatusPill(connected: Boolean?) {
  val text = when (connected) {
    true -> "Connected"
    false -> "Offline"
    null -> "Idle"
  }
  Surface(
    shape = RoundedCornerShape(999.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    color = MaterialTheme.colorScheme.surface,
  ) {
    Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
  }
}

@Composable
private fun ConnectionPanel(
  settings: ConnectionSettings,
  error: String?,
  compact: Boolean,
  onSave: (ConnectionSettings) -> Unit,
  onTest: () -> Unit,
) {
  var serverUrl by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
  var token by remember(settings.token) { mutableStateOf(settings.token) }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ElevatedButton(onClick = { onSave(ConnectionSettings(serverUrl, token)) }, shape = RoundedCornerShape(7.dp), modifier = Modifier.weight(1f)) { Text("Save") }
            OutlinedButton(onClick = onTest, shape = RoundedCornerShape(7.dp), modifier = Modifier.weight(1f)) { Text("Test") }
          }
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server") },
            singleLine = true,
            modifier = Modifier.weight(1.2f),
          )
          OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          ElevatedButton(onClick = { onSave(ConnectionSettings(serverUrl, token)) }, shape = RoundedCornerShape(7.dp)) { Text("Save") }
          OutlinedButton(onClick = onTest, shape = RoundedCornerShape(7.dp)) { Text("Test") }
        }
      }
      if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun RequestList(
  requests: List<WickleRequest>,
  selectedId: String?,
  onSelect: (WickleRequest) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Column {
      Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Pending", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${requests.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      LazyColumn {
        items(requests, key = { it.id }) { request ->
          RequestRow(request, selected = request.id == selectedId, onClick = { onSelect(request) })
        }
      }
    }
  }
}

@Composable
private fun RequestRow(request: WickleRequest, selected: Boolean, onClick: () -> Unit) {
  val color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
  Column(
    modifier = Modifier.fillMaxWidth().background(color).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(request.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Text(request.priority, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("${request.source} · ${request.kind}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun RequestDetail(
  request: WickleRequest?,
  onRespond: (WickleRequest, JsonElement) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    if (request == null) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No pending requests", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } else {
      Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(request.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
          Text("${request.source} · ${request.createdAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (request.body.isNotBlank()) {
          Text(request.body, style = MaterialTheme.typography.bodyLarge)
        }
        LinkBlock(request.links)
        ResponseForm(request, onRespond)
      }
    }
  }
}

@Composable
private fun LinkBlock(links: List<WickleLink>) {
  if (links.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Links", style = MaterialTheme.typography.labelLarge)
    links.forEach { link ->
      Text("${link.label}: ${link.url ?: link.path}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
  }
}

@Composable
private fun ResponseForm(request: WickleRequest, onRespond: (WickleRequest, JsonElement) -> Unit) {
  val values = remember(request.id) { mutableStateMapOf<String, String>() }
  val properties = request.schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
  val required = request.schema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()

  Card(
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Response", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      properties.forEach { (name, raw) ->
        val property = raw.jsonObject
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

private fun buildPayload(schema: JsonObject, values: Map<String, String>): JsonElement {
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

@Preview(showBackground = true, widthDp = 980)
@Composable
fun WickleInboxPreview() {
  WickleTheme {
    WickleInbox(
      state =
        com.callumalpass.wickle.data.WickleUiState(
          requests =
            listOf(
              WickleRequest(
                id = "req_preview",
                source = "tasknotes-ops",
                kind = "approval",
                title = "Close TaskNotes issue #1530 as wontfix?",
                body = "Agent recommends posting the drafted comment and closing the issue.",
                schema =
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
                  ),
                status = "pending",
                createdAt = "2026-05-20T21:50:00Z",
                updatedAt = "2026-05-20T21:50:00Z",
              ),
            ),
          selected = null,
        ),
      onRefresh = {},
      onSelect = {},
      onSaveSettings = {},
      onTestConnection = {},
      onStartRealtime = {},
      onStopRealtime = {},
      onRespond = { _, _ -> },
    )
  }
}
