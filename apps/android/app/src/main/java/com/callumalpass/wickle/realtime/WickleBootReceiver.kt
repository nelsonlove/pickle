package com.callumalpass.wickle.realtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.callumalpass.wickle.data.WickleSettings

class WickleBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action !in SUPPORTED_ACTIONS) return
    if (WickleSettings(context).isPushEnabled()) {
      WickleRealtimeService.start(context)
    }
  }

  companion object {
    private val SUPPORTED_ACTIONS =
      setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
      )
  }
}
