/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import org.lunaris.dolby.service.AppProfileMonitorService
import org.lunaris.dolby.service.DolbyEffectService
import org.lunaris.dolby.service.DolbyNotificationListener

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received intent: ${intent.action}")
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED -> {
                try {
                    DolbyEffectService.start(context)
                    
                    val prefs = context.getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
                    if (prefs.getBoolean("app_profile_monitoring_enabled", false)) {
                        AppProfileMonitorService.startMonitoring(context)
                    }
                    
                    if (isNotificationListenerEnabled(context)) {
                        requestNotificationListenerRebind(context)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Dolby", e)
                }
            }
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val cn = ComponentName(context, DolbyNotificationListener::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun requestNotificationListenerRebind(context: Context) {
        val cn = ComponentName(context, DolbyNotificationListener::class.java)
        NotificationListenerService.requestRebind(cn)
        Log.d(TAG, "Requested notification listener rebind")
    }

    companion object {
        private const val TAG = "Dolby-Boot"
    }
}
