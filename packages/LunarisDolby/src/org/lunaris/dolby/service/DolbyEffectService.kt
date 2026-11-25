/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DeviceStateManager
import org.lunaris.dolby.data.DolbyDispatchers
import org.lunaris.dolby.data.DolbyRepository

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val dolbyPrefs: SharedPreferences by lazy {
        getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    }
    private val isDeviceStateMemoryEnabled: Boolean
        get() = dolbyPrefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, false)
    private val handler = Handler(Looper.getMainLooper())
    private val halScope = CoroutineScope(SupervisorJob() + DolbyDispatchers.hal)
    private var isPlaybackActive = false
    private lateinit var repository: DolbyRepository
    private lateinit var deviceStateManager: DeviceStateManager
    private var previousActiveDevice: AudioDeviceInfo? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices added: ${addedDevices.map { it.debugString() }}")
            handleDeviceChange()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices removed: ${removedDevices.map { it.debugString() }}")
            // handleDeviceChange() snapshots the device that was actually routed. Saving the
            // current state under every removed device would overwrite the memory of devices
            // that were not the active output.
            handleDeviceChange()
        }
    }

    // Media can also move to another device that is already connected, e.g. when the output is
    // picked in the media output switcher, without any device being added or removed.
    private val routeListener = AudioManager.OnDevicesForAttributesChangedListener { _, _ ->
        handleDeviceChange()
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = configs?.any { it.isActive } == true
            val started = isActive && !isPlaybackActive
            isPlaybackActive = isActive
            if (!isActive) return
            // Other changes during playback only need a lost handle recreated, e.g. after an
            // audioserver restart, which pushes the saved state to the new one.
            halScope.launch {
                if (started) repository.applySavedState() else repository.ensureEffect()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DolbyRepository.getInstance(this)
        deviceStateManager = DeviceStateManager(this)
        val currentDevice = repository.getCurrentOutputDevice()
        previousActiveDevice = currentDevice
        val memoryEnabled = isDeviceStateMemoryEnabled
        halScope.launch {
            if (currentDevice != null && memoryEnabled) {
                deviceStateManager.switchTo(deviceStateManager.deviceKey(currentDevice))
            }
            repository.applySavedState()
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        audioManager.addOnDevicesForAttributesChangedListener(
            DolbyRepository.ATTRIBUTES_MEDIA, mainExecutor, routeListener)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        Log.d(TAG, "Dolby effect service created")
    }

    private fun handleDeviceChange() {
        val newDevice = repository.getCurrentOutputDevice()
        val oldDevice = previousActiveDevice
        val memoryEnabled = isDeviceStateMemoryEnabled
        previousActiveDevice = newDevice

        // Every added or removed device ends up here, inputs and outputs that are not routed
        // included, as does the initial callback on registration and every change of the media
        // route. If the routed output did not change there is nothing to save or restore.
        val oldKey = oldDevice?.let { deviceStateManager.deviceKey(it) }
        val newKey = newDevice?.let { deviceStateManager.deviceKey(it) }
        if (oldKey == newKey) return

        halScope.launch {
            if (newKey == null) {
                repository.updateSpeakerState()
            } else if (memoryEnabled) {
                Log.d(TAG, "Switching the settings to $newKey")
                deviceStateManager.switchTo(newKey)
            }
            repository.applySavedState()
        }
    }

    private fun AudioDeviceInfo.debugString(): String =
        "name=$productName,type=$type,id=$id,address=$address,isSink=$isSink"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        halScope.launch { repository.applySavedState() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.removeOnDevicesForAttributesChangedListener(routeListener)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        handler.removeCallbacksAndMessages(null)
        halScope.cancel()
        Log.d(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"

        private fun shouldRun(context: Context): Boolean {
            val prefs = context.getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean(DolbyConstants.PREF_ENABLE, false) ||
                prefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, false)
        }

        /**
         * Runs the service while Dolby or the device state memory is on. The memory needs it even
         * while Dolby is off, because a snapshot also restores the enabled state. Otherwise the
         * service is stopped and the saved state is pushed once, which turns DAP off at boot.
         * Call after changing either preference.
         */
        fun update(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            if (shouldRun(context)) {
                context.startService(intent)
            } else {
                context.stopService(intent)
                val repository = DolbyRepository.getInstance(context)
                DolbyDispatchers.processScope.launch { repository.applySavedState() }
            }
        }

        /**
         * Call after changing PREF_DEVICE_STATE_MEMORY. Turning the memory on gives the current
         * settings to the current output, so that the service starting up does not restore an
         * older snapshot of that output over them.
         */
        fun onDeviceStateMemoryChanged(context: Context) {
            val prefs = context.getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, false)) {
                val deviceStateManager = DeviceStateManager(context.applicationContext)
                val device = DolbyRepository.getInstance(context).getCurrentOutputDevice()
                deviceStateManager.setActiveDevice(device?.let(deviceStateManager::deviceKey))
            }
            update(context)
        }
    }
}
