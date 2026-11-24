/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.AppProfileManager
import org.lunaris.dolby.data.DolbyDispatchers
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.domain.models.dolbyProfileName
import org.lunaris.dolby.utils.ToastHelper
import java.util.concurrent.atomic.AtomicReference

class AppProfileMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val switchHandler = Handler(Looper.getMainLooper())
    private val halScope = CoroutineScope(SupervisorJob() + DolbyDispatchers.hal)
    private lateinit var appProfileManager: AppProfileManager
    private lateinit var dolbyRepository: DolbyRepository
    private lateinit var audioManager: AudioManager
    private val lastPackageName = AtomicReference<String?>(null)
    private var isMonitoring = false
    private var pendingSwitchRunnable: Runnable? = null
    private var lastQueryTime: Long = 0

    private val checkForegroundAppRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            if (isMonitoring) {
                handler.postDelayed(this, CHECK_INTERVAL)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appProfileManager = AppProfileManager(this)
        dolbyRepository = DolbyRepository.getInstance(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                stopSelf()
            }
            else -> {
                val prefs = getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean(PREF_MONITORING_ENABLED, false)) {
                    startMonitoring()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (!isMonitoring) {
            isMonitoring = true
            DolbyConstants.dlog(TAG, "Started monitoring foreground app")
            handler.post(checkForegroundAppRunnable)
        }
    }

    private fun stopMonitoring() {
        if (isMonitoring) {
            isMonitoring = false
            handler.removeCallbacks(checkForegroundAppRunnable)
            
            synchronized(this) {
                pendingSwitchRunnable?.let { switchHandler.removeCallbacks(it) }
                pendingSwitchRunnable = null
            }
            
            // Restore on the serial HAL worker so it runs after any in-flight app-switch;
            // this teardown path is infrequent.
            runBlocking(DolbyDispatchers.hal) {
                dolbyRepository.restoreUserProfile()
            }
            
            DolbyConstants.dlog(TAG, "Stopped monitoring foreground app")
        }
    }

    private fun isHeadphoneConnected(): Boolean {
        return try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking headphone connection", e)
            false
        }
    }

    private fun checkForegroundApp() {
        try {
            val prefs = getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
            val headphoneOnlyMode = prefs.getBoolean("app_profile_headphone_only", false)
            
            if (headphoneOnlyMode && !isHeadphoneConnected()) {
                DolbyConstants.dlog(TAG, "Headphone-only mode enabled but no headphones connected, skipping profile switch")
                return
            }
            
            val packageName = getForegroundPackage() ?: return
            
            val previousPackage = lastPackageName.getAndSet(packageName)
            if (packageName == previousPackage) {
                return
            }
            
            DolbyConstants.dlog(TAG, "Foreground app changed: $previousPackage -> $packageName")
            
            synchronized(this) {
                pendingSwitchRunnable?.let { switchHandler.removeCallbacks(it) }
                
                pendingSwitchRunnable = Runnable {
                    synchronized(this) {
                        try {
                            if (headphoneOnlyMode && !isHeadphoneConnected()) {
                                DolbyConstants.dlog(TAG, "Headphones disconnected, aborting profile switch")
                                return@Runnable
                            }
                            
                            val assignedProfile = appProfileManager.getAppProfile(packageName)
                            val showToasts = prefs.getBoolean("app_profile_show_toasts", true)
                            
                            if (assignedProfile >= 0) {
                                DolbyConstants.dlog(TAG, "Switching to profile $assignedProfile for $packageName")
                                halScope.launch { dolbyRepository.setAppProfile(assignedProfile) }
                                
                                if (showToasts) {
                                    val profileName = dolbyProfileName(this@AppProfileMonitorService, assignedProfile)
                                    val appName = getAppName(packageName)
                                    ToastHelper.showToast(
                                        this@AppProfileMonitorService,
                                        "Dolby: $profileName ($appName)"
                                    )
                                }
                            } else {
                                halScope.launch { dolbyRepository.restoreUserProfile() }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error switching profile", e)
                        } finally {
                            pendingSwitchRunnable = null
                        }
                    }
                }
                
                switchHandler.postDelayed(pendingSwitchRunnable!!, SWITCH_DELAY)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking foreground app", e)
        }
    }

    private fun getForegroundPackage(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTime = System.currentTimeMillis()
        
        // Query everything since the previous poll, otherwise switches that happen between
        // polls are never seen. The lookback is capped for polls that were skipped, and the
        // wall clock may have been set back since the previous poll.
        val beginTime =
            maxOf(lastQueryTime, currentTime - MAX_LOOKBACK).coerceIn(0L, currentTime)
        val query = UsageEventsQuery.Builder(beginTime, currentTime)
            .setEventTypes(UsageEvents.Event.ACTIVITY_RESUMED)
            .build()
        val usageEvents = usageStatsManager.queryEvents(query) ?: return null
        lastQueryTime = currentTime
        val event = UsageEvents.Event()

        var lastPackage: String? = null

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPackage = event.packageName
            }
        }
        
        return lastPackage
    }
    
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        DolbyConstants.dlog(TAG, "Service destroyed")
        stopMonitoring()
        halScope.cancel()
    }

    companion object {
        private const val TAG = "AppProfileMonitor"
        private const val CHECK_INTERVAL = 2000L
        private const val MAX_LOOKBACK = 60000L
        private const val SWITCH_DELAY = 300L

        private const val PREF_MONITORING_ENABLED = "app_profile_monitoring_enabled"

        const val ACTION_START_MONITORING = "org.lunaris.dolby.START_MONITORING"
        const val ACTION_STOP_MONITORING = "org.lunaris.dolby.STOP_MONITORING"

        fun startMonitoring(context: Context) {
            val intent = Intent(context, AppProfileMonitorService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            context.startService(intent)
        }

        fun stopMonitoring(context: Context) {
            val intent = Intent(context, AppProfileMonitorService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.startService(intent)
        }
    }
}
