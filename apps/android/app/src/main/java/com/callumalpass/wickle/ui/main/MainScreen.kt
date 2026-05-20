package com.callumalpass.wickle.ui.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.callumalpass.wickle.data.ConnectionSettings
import com.callumalpass.wickle.data.WickleRequest
import com.callumalpass.wickle.data.WickleUiState
import com.callumalpass.wickle.realtime.WickleRealtimeService
import com.callumalpass.wickle.theme.WickleTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel(factory = MainScreenViewModel.factory(LocalContext.current)),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val notificationLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        WickleRealtimeService.start(context)
        viewModel.setPushEnabled(true)
      } else {
        viewModel.setPushEnabled(false)
      }
    }

  LaunchedEffect(Unit) { viewModel.refresh() }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

  WickleApp(
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
        viewModel.setPushEnabled(true)
      }
    },
    onStopRealtime = {
      WickleRealtimeService.stop(context)
      viewModel.setPushEnabled(false)
    },
    onRespond = viewModel::respond,
    onSendMessage = viewModel::sendMessage,
    onNoticeShown = viewModel::clearNotice,
    modifier = modifier,
  )
}

@Composable
fun WickleApp(
  state: WickleUiState,
  onRefresh: () -> Unit,
  onSelect: (WickleRequest) -> Unit,
  onSaveSettings: (ConnectionSettings) -> Unit,
  onTestConnection: () -> Unit,
  onStartRealtime: () -> Unit,
  onStopRealtime: () -> Unit,
  onRespond: (WickleRequest, JsonElement) -> Unit,
  onSendMessage: (String, String, List<String>, () -> Unit) -> Unit,
  onNoticeShown: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var route by rememberSaveable { mutableStateOf(WickleRoute.Inbox.name) }
  val snackbarHostState = remember { SnackbarHostState() }
  val selected = state.selected ?: state.requests.firstOrNull()

  LaunchedEffect(state.notice, state.error) {
    val message = state.notice ?: state.error
    if (!message.isNullOrBlank()) {
      snackbarHostState.showSnackbar(message)
      onNoticeShown()
    }
  }

  Scaffold(
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      WickleTopBar(
        route = route,
        pendingCount = state.requests.size,
        connected = state.connected,
        onBack = { route = WickleRoute.Inbox.name },
        onRefresh = onRefresh,
        onStartRealtime = onStartRealtime,
      )
    },
    bottomBar = {
      WickleBottomBar(
        route = route,
        onNavigate = { route = it.name },
      )
    },
  ) { padding ->
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(padding)
          .safeDrawingPadding()
          .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      when (route) {
        WickleRoute.Detail.name ->
          DetailPage(
            request = selected,
            onBack = { route = WickleRoute.Inbox.name },
            onRespond = { request, payload ->
              onRespond(request, payload)
              route = WickleRoute.Inbox.name
            },
          )

        WickleRoute.Compose.name ->
          ComposePage(
            sending = state.sending,
            onSend = { title, body, tags ->
              onSendMessage(title, body, tags) {
                route = WickleRoute.Inbox.name
              }
            },
          )

        WickleRoute.Settings.name ->
          SettingsPage(
            settings = state.settings,
            connected = state.connected,
            pushEnabled = state.pushEnabled,
            error = state.error,
            onSave = onSaveSettings,
            onTest = onTestConnection,
            onStartRealtime = onStartRealtime,
            onStopRealtime = onStopRealtime,
          )

        else ->
          InboxPage(
            requests = state.requests,
            loading = state.loading,
            onRefresh = onRefresh,
            onOpen = { request ->
              onSelect(request)
              route = WickleRoute.Detail.name
            },
          )
      }
    }
  }
}

private enum class WickleRoute(val label: String) {
  Inbox("Inbox"),
  Compose("Post"),
  Settings("Settings"),
  Detail("Detail"),
}

@Composable
private fun WickleTopBar(
  route: String,
  pendingCount: Int,
  connected: Boolean?,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onStartRealtime: () -> Unit,
) {
  val isDetail = route == WickleRoute.Detail.name
  Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
    Row(
      modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (isDetail) {
        IconButton(onClick = onBack) {
          Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
      } else {
        WickleMark()
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = if (isDetail) "Detail" else "Wickle",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = if (isDetail) "Structured handoff" else "$pendingCount pending",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      ConnectionDot(connected)
      IconButton(onClick = onRefresh) {
        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
      }
      IconButton(onClick = onStartRealtime) {
        Icon(Icons.Rounded.Notifications, contentDescription = "Start push")
      }
    }
  }
}

@Composable
private fun WickleBottomBar(route: String, onNavigate: (WickleRoute) -> Unit) {
  NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
    NavigationBarItem(
      modifier = Modifier.semantics { contentDescription = "Inbox tab" },
      selected = route == WickleRoute.Inbox.name || route == WickleRoute.Detail.name,
      onClick = { onNavigate(WickleRoute.Inbox) },
      icon = { Icon(Icons.Rounded.Inbox, contentDescription = null) },
      label = { Text(WickleRoute.Inbox.label) },
    )
    NavigationBarItem(
      modifier = Modifier.semantics { contentDescription = "Post tab" },
      selected = route == WickleRoute.Compose.name,
      onClick = { onNavigate(WickleRoute.Compose) },
      icon = { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null) },
      label = { Text(WickleRoute.Compose.label) },
    )
    NavigationBarItem(
      modifier = Modifier.semantics { contentDescription = "Settings tab" },
      selected = route == WickleRoute.Settings.name,
      onClick = { onNavigate(WickleRoute.Settings) },
      icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
      label = { Text(WickleRoute.Settings.label) },
    )
  }
}

@Composable
private fun WickleMark(modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.height(42.dp),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.primary,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      MiniPost(Color.White.copy(alpha = 0.95f), 18.dp)
      MiniPost(MaterialTheme.colorScheme.secondary, 24.dp)
      MiniPost(Color.White.copy(alpha = 0.95f), 18.dp)
    }
  }
}

@Composable
private fun MiniPost(color: Color, height: androidx.compose.ui.unit.Dp) {
  Surface(modifier = Modifier.height(height).padding(vertical = 3.dp), color = color, shape = RoundedCornerShape(6.dp)) {
    Spacer(Modifier.padding(horizontal = 4.dp))
  }
}

@Composable
private fun ConnectionDot(connected: Boolean?) {
  val color =
    when (connected) {
      true -> MaterialTheme.colorScheme.primary
      false -> MaterialTheme.colorScheme.tertiary
      null -> MaterialTheme.colorScheme.outline
    }
  Box(modifier = Modifier.height(12.dp).clip(CircleShape).background(color).padding(horizontal = 6.dp))
}

@Preview(showBackground = true, widthDp = 390, heightDp = 820)
@Composable
fun WickleAppPreview() {
  WickleTheme {
    WickleApp(
      state =
        WickleUiState(
          connected = true,
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
                priority = "normal",
                tags = listOf("ops", "review"),
                createdAt = "2026-05-20T21:50:00Z",
                updatedAt = "2026-05-20T21:50:00Z",
              ),
            ),
        ),
      onRefresh = {},
      onSelect = {},
      onSaveSettings = {},
      onTestConnection = {},
      onStartRealtime = {},
      onStopRealtime = {},
      onRespond = { _, _ -> },
      onSendMessage = { _, _, _, done -> done() },
      onNoticeShown = {},
    )
  }
}
