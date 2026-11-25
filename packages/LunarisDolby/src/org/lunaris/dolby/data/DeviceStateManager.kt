/*
 * Copyright (C) 2026 tranQuila-Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo

import org.lunaris.dolby.DolbyConstants

/**
 * Keeps a snapshot of the settings per output device. Snapshots are copies of the saved settings,
 * not of the effect, which rejects parameters right after boot; DolbyRepository.applySavedState
 * pushes the saved settings to the effect.
 */
class DeviceStateManager(private val context: Context) {

    private val dolbyPrefs = context.getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)

    fun deviceKey(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> {
                val addr = device.address?.takeIf { it.isNotBlank() } ?: "unknown"
                "bt_${addr.replace(":", "_")}"
            }
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> DEVICE_WIRED
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> DEVICE_USB
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
            else -> "device_type_${device.type}"
        }
    }

    /**
     * Gives the saved settings to [deviceKey]: they are stored as the snapshot of the device they
     * belong to and replaced by the snapshot of [deviceKey]. If [deviceKey] has no snapshot, or the
     * settings belong to no known device, it takes them over as they are. Apply the saved state
     * afterwards.
     */
    fun switchTo(deviceKey: String) {
        splitLegacyUsbSnapshot()
        val owner = dolbyPrefs.getString(PREF_ACTIVE_DEVICE, null)
        if (owner == deviceKey) return
        if (owner != null) {
            saveSnapshot(owner)
            restoreSnapshot(deviceKey)
        }
        setActiveDevice(deviceKey)
    }

    /** Records that the saved settings belong to [deviceKey], or to no known device if null. */
    fun setActiveDevice(deviceKey: String?) {
        dolbyPrefs.edit().putString(PREF_ACTIVE_DEVICE, deviceKey).apply()
    }

    private fun saveSnapshot(deviceKey: String) {
        // Not the active profile: restoring an app's profile would make it the user's choice.
        val profile = dolbyPrefs.getInt(DolbyConstants.PREF_USER_PROFILE, 0)
        val settings = getProfilePrefs(profile)
        // A profile whose equalizer was never set keeps the flat default of the DAX configuration.
        val gains = parseGains(settings.getString(DolbyConstants.PREF_PRESET, null)) ?: IntArray(EQ_BANDS)
        val editor = getDevicePrefs(deviceKey).edit()
            .clear()
            .putInt(KEY_VERSION, SNAPSHOT_VERSION)
            .putBoolean(KEY_DOLBY_ENABLED, dolbyPrefs.getBoolean(DolbyConstants.PREF_ENABLE, false))
            .putInt(KEY_PROFILE, profile)
            .putInt(KEY_IEQ, settings.getString(DolbyConstants.PREF_IEQ, null)?.toIntOrNull() ?: 0)
            .putString(KEY_EQ_GAINS, gains.joinToString(","))
        BOOLEAN_SETTINGS.forEach { (key, pref) -> editor.putBoolean(key, settings.getBoolean(pref, false)) }
        INT_SETTINGS.forEach { editor.putInt(it.key, settings.getInt(it.pref, it.default)) }
        editor.apply()
        DolbyConstants.dlog(TAG, "Snapshot saved for device=$deviceKey profile=$profile")
    }

    private fun restoreSnapshot(deviceKey: String) {
        val snapshot = getDevicePrefs(deviceKey)
        if (!snapshot.contains(KEY_VERSION)) {
            DolbyConstants.dlog(TAG, "No snapshot for device=$deviceKey, keeping the current settings")
            return
        }
        val storedVersion = snapshot.getInt(KEY_VERSION, -1)
        if (storedVersion != SNAPSHOT_VERSION) {
            DolbyConstants.dlog(TAG,
                "Snapshot version mismatch for $deviceKey: stored=$storedVersion current=$SNAPSHOT_VERSION — discarding")
            snapshot.edit().clear().apply()
            return
        }

        val profile = snapshot.getInt(KEY_PROFILE, 0)
        dolbyPrefs.edit()
            .putBoolean(DolbyConstants.PREF_ENABLE, snapshot.getBoolean(KEY_DOLBY_ENABLED, true))
            .putString(DolbyConstants.PREF_PROFILE, profile.toString())
            .putInt(DolbyConstants.PREF_USER_PROFILE, profile)
            .apply()

        val editor = getProfilePrefs(profile).edit()
            .putString(DolbyConstants.PREF_IEQ, snapshot.getInt(KEY_IEQ, 0).toString())
        BOOLEAN_SETTINGS.forEach { (key, pref) -> editor.putBoolean(pref, snapshot.getBoolean(key, false)) }
        INT_SETTINGS.forEach { editor.putInt(it.pref, snapshot.getInt(it.key, it.default)) }
        val gains = parseGains(snapshot.getString(KEY_EQ_GAINS, null))
        if (gains != null) {
            editor.putString(DolbyConstants.PREF_PRESET, gains.joinToString(","))
        } else {
            DolbyConstants.dlog(TAG, "Invalid EQ gains in the snapshot of $deviceKey — skipping EQ restore")
        }
        editor.apply()
        DolbyConstants.dlog(TAG, "Snapshot restored for device=$deviceKey profile=$profile")
    }

    // DolbyRepository only applies a preset with a gain for every band.
    private fun parseGains(value: String?): IntArray? =
        value?.split(",")?.map { it.toIntOrNull() ?: return null }
            ?.takeIf { it.size == EQ_BANDS }?.toIntArray()

    private fun getProfilePrefs(profile: Int): SharedPreferences =
        context.getSharedPreferences(DolbyConstants.profilePrefsFile(profile), Context.MODE_PRIVATE)

    private fun getDevicePrefs(deviceKey: String): SharedPreferences =
        context.getSharedPreferences("device_state_$deviceKey", Context.MODE_PRIVATE)

    // USB outputs used to share the wired snapshot with the headphone jack, so it holds the state
    // of whichever of them was used last. Give the USB output a copy once, so neither loses what
    // it restored before; from then on each keeps its own.
    private fun splitLegacyUsbSnapshot() {
        if (dolbyPrefs.getBoolean(PREF_USB_SNAPSHOT_SPLIT, false)) return

        val wired = getDevicePrefs(DEVICE_WIRED)
        val usb = getDevicePrefs(DEVICE_USB)
        if (wired.contains(KEY_VERSION) && !usb.contains(KEY_VERSION)) {
            val editor = usb.edit()
            wired.all.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.apply()
            DolbyConstants.dlog(TAG, "Copied the wired snapshot to $DEVICE_USB")
        }
        dolbyPrefs.edit().putBoolean(PREF_USB_SNAPSHOT_SPLIT, true).apply()
    }

    private class IntSetting(val key: String, val pref: String, val default: Int)

    companion object {
        private const val TAG = "DeviceStateManager"

        private const val SNAPSHOT_VERSION = 1

        private const val EQ_BANDS = 20

        private const val DEVICE_WIRED = "wired_headphones"
        private const val DEVICE_USB = "usb_audio"
        private const val PREF_USB_SNAPSHOT_SPLIT = "device_state_usb_split"
        private const val PREF_ACTIVE_DEVICE = "device_state_active"

        private const val KEY_VERSION = "snapshot_version"
        private const val KEY_DOLBY_ENABLED = "enabled"
        private const val KEY_PROFILE = "profile"
        private const val KEY_IEQ = "ieq"
        private const val KEY_EQ_GAINS = "eq_gains"

        // Snapshot keys and the profile settings they hold, with the defaults DolbyRepository
        // reads and applies when a setting is missing.
        private val BOOLEAN_SETTINGS = mapOf(
            "hp_virt" to DolbyConstants.PREF_HP_VIRTUALIZER,
            "spk_virt" to DolbyConstants.PREF_SPK_VIRTUALIZER,
            "dialogue" to DolbyConstants.PREF_DIALOGUE,
            "bass_enabled" to DolbyConstants.PREF_BASS,
            "treble_enabled" to DolbyConstants.PREF_TREBLE,
            "mid_enabled" to DolbyConstants.PREF_MID,
            "volume" to DolbyConstants.PREF_VOLUME,
        )
        private val INT_SETTINGS = listOf(
            IntSetting("dialogue_amt", DolbyConstants.PREF_DIALOGUE_AMOUNT, 6),
            IntSetting("bass_level", DolbyConstants.PREF_BASS_LEVEL, 0),
            IntSetting("bass_curve", DolbyConstants.PREF_BASS_CURVE, 0),
            IntSetting("treble_level", DolbyConstants.PREF_TREBLE_LEVEL, 0),
            IntSetting("mid_level", DolbyConstants.PREF_MID_LEVEL, 0),
            IntSetting("stereo", DolbyConstants.PREF_STEREO_WIDENING, 32),
        )
    }
}
