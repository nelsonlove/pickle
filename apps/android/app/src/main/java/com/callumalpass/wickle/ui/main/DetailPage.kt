package com.callumalpass.wickle.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.callumalpass.wickle.data.WickleLink
import com.callumalpass.wickle.data.WickleRequest
import kotlinx.serialization.json.JsonElement

@Composable
internal fun DetailPage(
  request: WickleRequest?,
  onBack: () -> Unit,
  onRespond: (WickleRequest, JsonElement) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (request == null) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(onClick = onBack, shape = RoundedCornerShape(7.dp)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
        Text("Inbox", modifier = Modifier.padding(start = 8.dp))
      }
      EmptyDetail()
    }
    return
  }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          KindChip(request.kind, request.priority)
          TagRow(request.tags)
        }
        Text(request.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
          "${request.source} / ${formatTimestamp(request.createdAt)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (request.body.isNotBlank()) {
          Text(request.body, style = MaterialTheme.typography.bodyLarge)
        }
        LinkBlock(request.links)
      }
    }

    if (request.response != null) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Response", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(request.response.payload.toString(), style = MaterialTheme.typography.bodyMedium)
        }
      }
    } else if (request.kind == "message") {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
      ) {
        Text(
          "Posted message",
          modifier = Modifier.padding(14.dp),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
      }
    } else {
      ResponseForm(request, onRespond)
    }

    OutlinedButton(onClick = onBack, shape = RoundedCornerShape(7.dp)) {
      Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
      Text("Back to inbox", modifier = Modifier.padding(start = 8.dp))
    }
  }
}

@Composable
private fun EmptyDetail() {
  Surface(
    modifier = Modifier.fillMaxSize(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Text(
      "No handoff selected",
      modifier = Modifier.padding(24.dp),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun LinkBlock(links: List<WickleLink>) {
  if (links.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Links", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    links.forEach { link ->
      Text("${link.label}: ${link.url ?: link.path}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
  }
}
