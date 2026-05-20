package com.callumalpass.wickle.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.callumalpass.wickle.data.WickleRequest

@Composable
internal fun InboxPage(
  requests: List<WickleRequest>,
  loading: Boolean,
  onRefresh: () -> Unit,
  onOpen: (WickleRequest) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Inbox", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
          if (loading) "Refreshing" else "${requests.size} waiting",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(7.dp)) {
        Icon(Icons.Rounded.Refresh, contentDescription = null)
        Text("Refresh", modifier = Modifier.padding(start = 8.dp))
      }
    }

    if (requests.isEmpty()) {
      EmptyInbox(onRefresh)
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(requests, key = { it.id }) { request ->
          RequestRow(request = request, onClick = { onOpen(request) })
        }
      }
    }
  }
}

@Composable
private fun EmptyInbox(onRefresh: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Nothing waiting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("New agent handoffs will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRefresh, shape = RoundedCornerShape(7.dp)) {
          Icon(Icons.Rounded.Refresh, contentDescription = null)
          Text("Refresh", modifier = Modifier.padding(start = 8.dp))
        }
      }
    }
  }
}

@Composable
private fun RequestRow(request: WickleRequest, onClick: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            request.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            "${request.source} / ${formatTimestamp(request.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        KindChip(request.kind, request.priority)
      }
      if (request.body.isNotBlank()) {
        Text(
          request.body,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
      TagRow(request.tags)
    }
  }
}
