/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DolbyDispatchers
import org.lunaris.dolby.data.DolbyRepository

class DolbyNotificationListener : NotificationListenerService() {

    private lateinit var dolbyRepository: DolbyRepository
    private val halScope = CoroutineScope(SupervisorJob() + DolbyDispatchers.hal)

    override fun onCreate() {
        super.onCreate()
        DolbyConstants.dlog(TAG, "NotificationListener created")
        dolbyRepository = DolbyRepository(this)
        initializeDolbySettings()
        startAppProfileMonitoringIfEnabled()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        DolbyConstants.dlog(TAG, "NotificationListener connected")
        initializeDolbySettings()
        startAppProfileMonitoringIfEnabled()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DolbyConstants.dlog(TAG, "NotificationListener disconnected")
        requestRebind(android.content.ComponentName(this, DolbyNotificationListener::class.java))
    }

    private fun initializeDolbySettings() {
        val prefs = getSharedPreferences("dolby_prefs", MODE_PRIVATE)
        val savedProfile = prefs.getString(DolbyConstants.PREF_PROFILE, "0")?.toIntOrNull() ?: 0
        val enabled = prefs.getBoolean(DolbyConstants.PREF_ENABLE, false)
        DolbyConstants.dlog(TAG, "Initializing Dolby - enabled: $enabled, profile: $savedProfile")
        if (enabled) {
            halScope.launch {
                try {
                    dolbyRepository.setCurrentProfile(savedProfile)
                    dolbyRepository.setDolbyEnabled(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Dolby settings", e)
                }
            }
        }
    }

    private fun startAppProfileMonitoringIfEnabled() {
        val prefs = getSharedPreferences("dolby_prefs", MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("app_profile_monitoring_enabled", false)
        if (isEnabled) {
            DolbyConstants.dlog(TAG, "Starting app profile monitoring")
            AppProfileMonitorService.startMonitoring(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        halScope.cancel()
        dolbyRepository.close()
        DolbyConstants.dlog(TAG, "NotificationListener destroyed")
    }

    companion object {
        private const val TAG = "DolbyNotificationListener"
    }
}
