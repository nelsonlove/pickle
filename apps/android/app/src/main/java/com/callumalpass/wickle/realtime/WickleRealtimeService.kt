package com.callumalpass.wickle.realtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.callumalpass.wickle.MainActivity
import com.callumalpass.wickle.R
import com.callumalpass.wickle.data.WickleApi
import com.callumalpass.wickle.data.WickleEvent
import com.callumalpass.wickle.data.WickleSettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class WickleRealtimeService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val client = OkHttpClient()
  private val api = WickleApi(client)
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }
  private val running = AtomicBoolean(false)
  private var job: Job? = null
  private var socket: WebSocket? = null

  override fun onCreate() {
    super.onCreate()
    ensureChannels()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("Connecting"))
    if (running.compareAndSet(false, true)) {
      job = scope.launch { runLoop() }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    running.set(false)
    socket?.cancel()
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private suspend fun runLoop() {
    var backoffMs = 1000L
    while (running.get()) {
      val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
      val settings = WickleSettings(applicationContext).load()
      socket =
        client.newWebSocket(
          api.streamRequest(settings),
          object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              backoffMs = 1000L
              notifyService("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
              handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
              notifyService("Reconnecting")
              closed.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
              notifyService("Disconnected")
              closed.complete(Unit)
            }
          },
        )
      closed.await()
      delay(backoffMs)
      backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
    }
  }

  private fun handleMessage(text: String) {
    if (text.contains("stream.ready")) return
    val event = runCatching { json.decodeFromString<WickleEvent>(text) }.getOrNull() ?: return
    if (event.type == "request.created") {
      val title =
        event.payload
          ?.let { payload -> payload.toString().substringAfter("\"title\":\"", "").substringBefore("\"", "New Wickle request") }
          ?: "New Wickle request"
      postRequestNotification(title, event.requestId)
    }
  }

  private fun notifyService(state: String) {
    val manager = getSystemService(NotificationManager::class.java)
    manager.notify(SERVICE_NOTIFICATION_ID, serviceNotification(state))
  }

  private fun serviceNotification(state: String) =
    NotificationCompat.Builder(this, CHANNEL_SERVICE)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Wickle push")
      .setContentText(state)
      .setOngoing(true)
      .setContentIntent(mainPendingIntent())
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()

  private fun postRequestNotification(title: String, requestId: String?) {
    val manager = getSystemService(NotificationManager::class.java)
    val notification =
      NotificationCompat.Builder(this, CHANNEL_REQUESTS)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(requestId ?: "Pending request")
        .setContentIntent(mainPendingIntent())
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
    manager.notify((requestId ?: System.currentTimeMillis().toString()).hashCode(), notification)
  }

  private fun mainPendingIntent(): PendingIntent {
    val intent = Intent(this, MainActivity::class.java)
    return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  private fun ensureChannels() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Wickle connection", NotificationManager.IMPORTANCE_LOW))
    manager.createNotificationChannel(NotificationChannel(CHANNEL_REQUESTS, "Wickle requests", NotificationManager.IMPORTANCE_HIGH))
  }

  companion object {
    private const val ACTION_STOP = "com.callumalpass.wickle.STOP_REALTIME"
    private const val CHANNEL_SERVICE = "wickle_service"
    private const val CHANNEL_REQUESTS = "wickle_requests"
    private const val SERVICE_NOTIFICATION_ID = 101

    fun start(context: Context) {
      val intent = Intent(context, WickleRealtimeService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, WickleRealtimeService::class.java))
    }
  }
}
