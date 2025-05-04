/*
 * Copyright (C) 2024 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi.geq.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_PRESET
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.dlog
import co.aospa.dolby.xiaomi.DolbyController
import co.aospa.dolby.xiaomi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

class EqualizerRepository(
    private val context: Context
) {
    // Lazy initialization of DolbyController instance
    private val dolbyController by lazy { DolbyController.getInstance(context) }

    // Current profile ID for equalizer settings
    private val profile get() = dolbyController.profile

    // SharedPreferences for profile-specific settings using efficient lazy initialization
    private val profileSharedPrefs by lazy {
        context.getSharedPreferences(
            "profile_$profile",
            Context.MODE_PRIVATE
        )
    }

    // SharedPreferences for user-defined presets with lazy initialization
    private val presetsSharedPrefs by lazy {
        context.getSharedPreferences(
            "presets",
            Context.MODE_PRIVATE
        )
    }

    // Built-in presets loaded from resources using efficient list mapping
    val builtInPresets: List<Preset> by lazy {
        val names = context.resources.getStringArray(R.array.dolby_preset_entries)
        val values = context.resources.getStringArray(R.array.dolby_preset_values)

        List(names.size) { index ->
            Preset(
                name = names[index],
                bandGains = deserializeGains(values[index]),
                isUserDefined = false
            )
        }
    }

    // Default flat preset using first built-in entry
    val defaultPreset by lazy { builtInPresets.first() }

    // Flow for observing user-defined presets changes using callbackFlow
    val userPresets: Flow<List<Preset>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(loadUserPresets())
        }

        presetsSharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        listener.onSharedPreferenceChanged(presetsSharedPrefs, null) // Initial emission

        awaitClose {
            presetsSharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // region Public API
    /**
     * Retrieves current band gains from storage or controller
     */
    suspend fun getBandGains(): List<BandGain> = withContext(Dispatchers.IO) {
        profileSharedPrefs.getString(PREF_PRESET, dolbyController.getPreset())
            ?.takeIf { it.isNotEmpty() }
            ?.let(::deserializeGains)
            ?: defaultPreset.bandGains
    }.also {
        dlog(TAG, "Retrieved band gains: $it")
    }

    /**
     * Saves new band gains to both controller and persistent storage
     */
    suspend fun setBandGains(bandGains: List<BandGain>) = withContext(Dispatchers.IO) {
        dlog(TAG, "Setting new band gains: $bandGains")
        val serialized = serializeGains(bandGains)

        dolbyController.setPreset(serialized)
        profileSharedPrefs.edit(commit = true) {
            putString(PREF_PRESET, serialized)
        }
    }

    /**
     * Adds new user-defined preset to storage
     */
    suspend fun addPreset(preset: Preset) = withContext(Dispatchers.IO) {
        presetsSharedPrefs.edit(commit = true) {
            putString(preset.name, serializeGains(preset.bandGains))
        }
    }

    /**
     * Removes existing user-defined preset from storage
     */
    suspend fun removePreset(preset: Preset) = withContext(Dispatchers.IO) {
        presetsSharedPrefs.edit(commit = true) {
            remove(preset.name)
        }
    }
    // endregion

    // region Private Helpers
    /**
     * Loads all user-defined presets from SharedPreferences
     */
    private fun loadUserPresets() = presetsSharedPrefs.all.map { (name, value) ->
        Preset(
            name = name,
            bandGains = deserializeGains(value.toString()),
            isUserDefined = true
        )
    }

    /**
     * Extension function for safer SharedPreferences editing
     */
    private inline fun SharedPreferences.edit(
        commit: Boolean = false,
        action: SharedPreferences.Editor.() -> Unit
    ) {
        edit().apply(action).run {
            if (commit) commit() else apply()
        }
    }
    // endregion

    // region Companion Object Utilities
    private companion object {
        const val TAG = "EqRepository"

        // Frequency values for 10-band equalizer
        private val tenBandFreqs = intArrayOf(
            32, 64, 125, 250, 500,
            1000, 2000, 4000, 8000, 16000
        )

        /**
         * Converts serialized string to BandGain objects
         */
        fun deserializeGains(bandGains: String): List<BandGain> {
            return bandGains.split(",")
                .runCatching {
                    require(size == 20) { "Invalid preset format" }
                    map { it.toInt() }
                        .twentyToTenBandGains()
                }
                .getOrDefault(List(10) { 0 })
                .mapIndexed { i, gain ->
                    BandGain(tenBandFreqs[i], gain)
                }
        }

        /**
         * Converts BandGain objects to serialized string
         */
        fun serializeGains(bandGains: List<BandGain>): String {
            return bandGains
                .map { it.gain }
                .tenToTwentyBandGains()
                .joinToString(",")
        }

        /**
         * Converts 10-band gains to 20-band format required by backend
         */
        private fun List<Int>.tenToTwentyBandGains() = List(20) { index ->
            when {
                index % 2 == 1 && index < 19 -> (this[(index - 1)/2] + this[(index + 1)/2]) / 2
                else -> this[index / 2]
            }
        }

        /**
         * Converts 20-band gains to 10-band format for UI
         */
        private fun List<Int>.twentyToTenBandGains() = filterIndexed { i, _ -> i % 2 == 0 }
    }
    // endregion
}
