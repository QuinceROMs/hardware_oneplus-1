/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.AppProfileManager
import org.lunaris.dolby.data.DolbyDispatchers
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.domain.models.dolbyProfileName
import org.lunaris.dolby.utils.ToastHelper
import java.util.concurrent.atomic.AtomicReference

class AppProfileMonitorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val halScope = CoroutineScope(SupervisorJob() + DolbyDispatchers.hal)
    private lateinit var appProfileManager: AppProfileManager
    private lateinit var dolbyRepository: DolbyRepository
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private lateinit var monitorThread: HandlerThread
    // The polls, the delayed switches and the screen state receiver run here, so the state
    // below is only touched on this thread.
    private lateinit var monitorHandler: Handler
    private val lastPackageName = AtomicReference<String?>(null)
    private var isMonitoring = false
    private var pendingSwitchRunnable: Runnable? = null
    private var lastQueryTime: Long = 0
    // The receiver is registered on the monitor thread and unregistered there or in
    // onDestroy(), whichever comes first. Both fields are guarded by receiverLock.
    private val receiverLock = Any()
    private var receiverRegistered = false
    private var destroyed = false

    private val checkForegroundAppRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            if (isMonitoring) {
                monitorHandler.postDelayed(this, CHECK_INTERVAL)
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> resumePolling()
                Intent.ACTION_SCREEN_OFF -> monitorHandler.removeCallbacks(checkForegroundAppRunnable)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appProfileManager = AppProfileManager(this)
        dolbyRepository = DolbyRepository.getInstance(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(PowerManager::class.java)
        monitorThread = HandlerThread(TAG).apply { start() }
        monitorHandler = Handler(monitorThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> monitorHandler.post { startMonitoring() }
            ACTION_STOP_MONITORING -> {
                monitorHandler.post { stopMonitoring() }
                stopSelf()
            }
            else -> {
                val prefs = getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean(PREF_MONITORING_ENABLED, false)) {
                    monitorHandler.post { startMonitoring() }
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (!isMonitoring) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            synchronized(receiverLock) {
                if (destroyed) return
                registerReceiver(
                    screenStateReceiver, filter, null, monitorHandler,
                    Context.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            }
            isMonitoring = true
            // Checked after registering, so a screen change from here on reaches the receiver.
            if (powerManager.isInteractive) {
                resumePolling()
            }
            DolbyConstants.dlog(TAG, "Started monitoring foreground app")
        }
    }

    // Polling pauses while the screen is off. The top activity is paused when the display goes
    // to sleep and resumed when it wakes up, so the app in front reports ACTIVITY_RESUMED again
    // and the first polls after this see it.
    private fun resumePolling() {
        if (!isMonitoring) return
        monitorHandler.removeCallbacks(checkForegroundAppRunnable)
        monitorHandler.post(checkForegroundAppRunnable)
    }

    private fun unregisterScreenReceiver() {
        synchronized(receiverLock) {
            if (receiverRegistered) {
                receiverRegistered = false
                unregisterReceiver(screenStateReceiver)
            }
        }
    }

    private fun stopMonitoring() {
        if (isMonitoring) {
            isMonitoring = false
            unregisterScreenReceiver()
            monitorHandler.removeCallbacks(checkForegroundAppRunnable)
            pendingSwitchRunnable?.let { monitorHandler.removeCallbacks(it) }
            pendingSwitchRunnable = null
            
            // Queued behind any app switch already sent to the HAL dispatcher.
            halScope.launch { dolbyRepository.restoreUserProfile() }
            
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
            
            pendingSwitchRunnable?.let { monitorHandler.removeCallbacks(it) }
            
            val switchRunnable = Runnable {
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
                            mainHandler.post {
                                ToastHelper.showToast(
                                    this@AppProfileMonitorService,
                                    "Dolby: $profileName ($appName)"
                                )
                            }
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
            pendingSwitchRunnable = switchRunnable
            monitorHandler.postDelayed(switchRunnable, SWITCH_DELAY)
            
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
        // The context drops its receivers right after this returns, and unregistering one
        // after that throws, so it cannot be left to the monitor thread.
        synchronized(receiverLock) {
            destroyed = true
            unregisterScreenReceiver()
        }
        // halScope is not cancelled, so the restore queued by stopMonitoring() still runs.
        // quitSafely() still delivers messages that are already due, such as this one.
        monitorHandler.post { stopMonitoring() }
        monitorThread.quitSafely()
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
