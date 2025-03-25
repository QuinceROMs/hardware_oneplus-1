/*
 * Copyright (C) 2019 CypherOS
 * Copyright (C) 2014-2020 Paranoid Android
 * Copyright (C) 2023 The LineageOS Project
 * Copyright (C) 2023 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.device

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.AmbientDisplayConfiguration
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.android.systemui.plugins.OverlayPlugin
import com.android.systemui.plugins.annotations.Requires

@Requires(target = OverlayPlugin::class, version = OverlayPlugin.VERSION)
class AlertSliderPlugin : OverlayPlugin, LifecycleObserver {
    private lateinit var pluginContext: Context
    private lateinit var handler: NotificationHandler
    private lateinit var ambientConfig: AmbientDisplayConfiguration
    private val dialogLock = Any()
    private var isSetupWizardRunning = false
    private var navBarView: View? = null
    private var lastConfigChangeTime = 0L
    private var adaptiveDelay = 250L

    private data class NotificationInfo(
        val position: Int,
        val mode: Int,
    )

    private val updateReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val startTime = System.currentTimeMillis()
                try {
                    isSetupWizardRunning = isInSetupMode(context)
                    if (isSetupWizardRunning) return // Early exit if setup wizard is running

                    when (intent.action) {
                        KeyHandler.SLIDER_UPDATE_ACTION -> {
                            synchronized(dialogLock) {
                                val ringer =
                                    intent.getIntExtra("position_value", NONE).takeIf { it != NONE } ?: return

                                // Double-check setup state - just to be sure
                                if (isInSetupMode(context)) return

                                retryOperation({
                                    handler
                                        .obtainMessage(
                                            MSG_DIALOG_UPDATE,
                                            NotificationInfo(
                                                intent.getIntExtra("position", KeyHandler.POSITION_BOTTOM),
                                                ringer
                                            )
                                        )
                                        .sendToTarget()
                                    handler.sendEmptyMessage(MSG_DIALOG_SHOW)
                                })
                            }
                        }
                        Intent.ACTION_CONFIGURATION_CHANGED -> {
                            synchronized(dialogLock) {
                                // Apply debouncing for configuration changes
                                val now = System.currentTimeMillis()
                                if (now - lastConfigChangeTime < CONFIG_CHANGE_DEBOUNCE_MS) {
                                    return // Ignore rapid configuration changes
                                }
                                lastConfigChangeTime = now

                                Handler(Looper.getMainLooper()).postDelayed({
                                    handler.context = context
                                    if (!isInSetupMode(context)) {
                                        handler.sendEmptyMessage(MSG_DIALOG_RECREATE)
                                    }
                                }, calculateAdaptiveDelay())
                            }
                        }
                        Intent.ACTION_USER_UNLOCKED -> {
                            isSetupWizardRunning = isInSetupMode(context)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in updateReceiver", e)
                } finally {
                    logPerformanceMetrics("updateReceiver", startTime)
                }
            }
        }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onSystemStart() {
        Log.d(TAG, "System started")
        monitorResourceUsage()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onSystemStop() {
        Log.d(TAG, "System stopped")
    }

    override fun onCreate(context: Context, plugin: Context) {
        pluginContext = plugin
        handler = NotificationHandler(plugin)
        ambientConfig = AmbientDisplayConfiguration(context)
        isSetupWizardRunning = isInSetupMode(context)

        val filter = IntentFilter().apply {
            addAction(KeyHandler.SLIDER_UPDATE_ACTION)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        plugin.registerReceiver(
            updateReceiver,
            filter,
            Context.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        pluginContext.unregisterReceiver(updateReceiver)
    }

    override fun setup(statusBar: View, navBar: View?) {
        navBarView = navBar
        isSetupWizardRunning = isInSetupMode(statusBar.context)
    }

    private fun isInSetupMode(context: Context): Boolean {
        return Settings.Secure.getInt(context.contentResolver,
            Settings.Secure.USER_SETUP_COMPLETE, 0) == 0
    }

    private fun calculateAdaptiveDelay(): Long {
        // Simple adaptive delay calculation: 80% of current delay + 20% of base delay
        adaptiveDelay = (adaptiveDelay * 0.8 + BASE_ADAPTIVE_DELAY * 0.2).toLong().coerceIn(MIN_ADAPTIVE_DELAY, MAX_ADAPTIVE_DELAY)
        return adaptiveDelay
    }

    private fun logPerformanceMetrics(action: String, startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Performance: $action took $duration ms")
    }

    private fun retryOperation(operation: () -> Unit, maxRetries: Int = 3) {
        var attempts = 0
        while (attempts < maxRetries) {
            try {
                operation()
                return
            } catch (e: Exception) {
                attempts++
                Log.w(TAG, "Operation failed, retrying (${attempts}/$maxRetries)", e)
                Thread.sleep(RETRY_DELAY_MS * attempts.toLong()) // Use a constant for retry delay
            }
        }
        Log.e(TAG, "Operation failed after $maxRetries attempts")
    }

    private fun monitorResourceUsage() {
        val memoryInfo = ActivityManager.MemoryInfo()
        (pluginContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)
        Log.d(TAG, "Available memory: ${memoryInfo.availMem / 1024 / 1024} MB")
    }

    fun updateConfiguration(newConfig: PluginConfig) {
        // Apply new configuration
        Log.d(TAG, "Updating configuration: $newConfig")
    }

    private inner class NotificationHandler(var context: Context) :
        Handler(Looper.getMainLooper()) {
        private var dialog = AlertSliderDialog(context)
        private var currUIMode = context.resources.configuration.uiMode
        private var currRotation = context.display.rotation
        private var showing = false
            set(value) {
                synchronized(dialogLock) {
                    // Don't show dialog if setup is running
                    if (isSetupWizardRunning && value) {
                        return
                    }

                    if (field != value) {
                        // Remove pending messages
                        removeMessages(MSG_DIALOG_SHOW)
                        removeMessages(MSG_DIALOG_DISMISS)
                        removeMessages(MSG_DIALOG_RESET)

                        // Show/hide dialog
                        if (value) {
                            handleResetTimeout()
                            handleDoze()
                            dialog.show()
                        } else {
                            dialog.dismiss()
                        }
                    }
                    field = value
                }
            }

        override fun handleMessage(msg: Message) =
            when (msg.what) {
                MSG_DIALOG_SHOW -> handleShow()
                MSG_DIALOG_DISMISS -> handleDismiss()
                MSG_DIALOG_RESET -> handleResetTimeout()
                MSG_DIALOG_UPDATE -> handleUpdate(msg.obj as NotificationInfo)
                MSG_DIALOG_RECREATE -> handleRecreate()
                else -> {}
            }

        private fun handleShow() {
            if (isSetupWizardRunning) return
            showing = true
        }

        private fun handleDismiss() {
            showing = false
        }

        private fun handleResetTimeout() {
            synchronized(dialogLock) {
                removeMessages(MSG_DIALOG_DISMISS)
                sendMessageDelayed(
                    obtainMessage(MSG_DIALOG_DISMISS, MSG_DIALOG_RESET, 0),
                    DIALOG_TIMEOUT
                )
            }
        }

        private fun handleUpdate(info: NotificationInfo) {
            synchronized(dialogLock) {
                if (isSetupWizardRunning) return
                handleResetTimeout()
                handleDoze()
                dialog.setState(info.position, info.mode)
            }
        }

        private fun handleDoze() {
            if (!ambientConfig.pulseOnNotificationEnabled(UserHandle.USER_CURRENT)) return
            val intent = Intent("com.android.systemui.doze.pulse")
            context.sendBroadcastAsUser(intent, UserHandle.CURRENT)
        }

        private fun handleRecreate() {
            if (isSetupWizardRunning) return
            val uiMode = context.resources.configuration.uiMode
            val rotation = context.display.rotation
            val themeChanged = uiMode != currUIMode
            val rotationChanged = rotation != currRotation
            if (themeChanged || rotationChanged) {
                showing = false
                dialog = AlertSliderDialog(context)
                currUIMode = uiMode
                currRotation = rotation
            }
        }
    }

    companion object {
        private const val TAG = "AlertSliderPlugin"

        // Handler
        private const val MSG_DIALOG_SHOW = 1
        private const val MSG_DIALOG_DISMISS = 2
        private const val MSG_DIALOG_RESET = 3
        private const val MSG_DIALOG_UPDATE = 4
        private const val MSG_DIALOG_RECREATE = 5
        private const val DIALOG_TIMEOUT = 2000L
        private const val CONFIG_CHANGE_DEBOUNCE_MS = 500L

        // Ringer mode
        private const val NONE = -1

        // Constants for adaptive delay calculation
        private const val BASE_ADAPTIVE_DELAY = 250L
        private const val MIN_ADAPTIVE_DELAY = 100L
        private const val MAX_ADAPTIVE_DELAY = 500L

        // Constant for retry delay
        private const val RETRY_DELAY_MS = 100L
    }
}

// Placeholder for PluginConfig class
data class PluginConfig(val someConfig: String)
