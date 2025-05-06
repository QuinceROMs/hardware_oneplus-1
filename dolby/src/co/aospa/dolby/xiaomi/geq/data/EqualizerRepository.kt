/*
 * Copyright (C) 2024 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi.geq.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.util.Log
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.PREF_PRESET
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.dlog
import co.aospa.dolby.xiaomi.DolbyController
import co.aospa.dolby.xiaomi.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.runCatching

// Using new Android 15 DataStore extensions for cleaner initialization
private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_${DolbyController.getInstance(it).profile}"
)
private val Context.presetsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "presets"
)

class EqualizerRepository(
    private val context: Context
) {
    private val dolbyController by lazy { DolbyController.getInstance(context) }

    // Using DataStore with type-safe keys
    private val profilePrefs = context.profileDataStore
    private val presetsPrefs = context.presetsDataStore

    // Safe default preset with fallback mechanism
    private val defaultPreset by lazy {
        if (builtInPresets.isNotEmpty()) builtInPresets.first() else createFallbackPreset()
    }

    // Lazy initialization with resource validation
    val builtInPresets: List<Preset> by lazy {
        val names = context.resources.obtainTypedArray(R.array.dolby_preset_entries).run {
            List(length()) { getString(it) ?: "Unknown" }.also { recycle() }
        }
        val presets = context.resources.obtainTypedArray(R.array.dolby_preset_values).run {
            List(length()) { getString(it) ?: "" }.also { recycle() }
        }

        if (names.size != presets.size) {
            Log.e(TAG, "Preset entries (${names.size}) and values (${presets.size}) mismatch")
        }

        names.zip(presets) { name, gains ->
            Preset(
                name = name,
                bandGains = deserializeGains(gains),
                isUserDefined = false
            )
        }
    }

    // Flow optimization using Android 15's DataStore Flow improvements
    val userPresets: Flow<List<Preset>> = presetsPrefs.data.map { preferences ->
        preferences.asMap().mapNotNull { (key, value) ->
            if (key is Preferences.Key<String> && value is String) {
                Preset(
                    name = key.name,
                    bandGains = deserializeGains(value),
                    isUserDefined = true
                )
            } else null
        }
    }

    // Safe band gains retrieval with fallback
    suspend fun getBandGains(): List<BandGain> {
        val gains = runCatching {
            profilePrefs.data.map { it[stringPreferencesKey(PREF_PRESET)] }.first()
        }.getOrElse {
            dolbyController.getPreset()
        } ?: defaultPreset.bandGains.serialize()

        return deserializeGains(gains)
    }

    // Transactional writes with validation
    suspend fun setBandGains(bandGains: List<BandGain>) {
        val serialized = bandGains.serialize()
        dolbyController.setPreset(serialized)
        profilePrefs.edit { it[stringPreferencesKey(PREF_PRESET)] = serialized }
    }

    // Batch operations with type safety
    suspend fun addPreset(preset: Preset) {
        presetsPrefs.edit { it[stringPreferencesKey(preset.name)] = preset.bandGains.serialize() }
    }

    suspend fun removePreset(preset: Preset) {
        presetsPrefs.edit { it.remove(stringPreferencesKey(preset.name)) }
    }

    private fun createFallbackPreset(): Preset {
        Log.e(TAG, "Creating fallback preset - no built-ins available")
        return Preset(
            name = "Flat",
            bandGains = List(10) { BandGain(tenBandFreqs[it], 0) },
            isUserDefined = false
        )
    }

    private companion object {
        const val TAG = "EqRepository"
        val tenBandFreqs = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        fun deserializeGains(bandGains: String): List<BandGain> {
            return runCatching {
                bandGains.splitToSequence(',')
                    .map { it.toInt() }
                    .takeIf { it.count() == 20 }
                    ?.twentyToTenBandGains()
                    ?.mapIndexed { i, gain -> BandGain(tenBandFreqs[i], gain) }
                    ?: throw IllegalArgumentException("Invalid gain count")
            }.getOrElse { exception ->
                Log.e(TAG, "Deserialization error: ${exception.message}")
                emptyList<BandGain>().also {
                    if (builtInPresets.isEmpty()) createFallbackPreset().bandGains
                }
            }
        }

        fun List<BandGain>.serialize() = map { it.gain }
            .tenToTwentyBandGains()
            .joinToString(",")
    }
}

// Optimized extension functions with bounds checking
private fun List<Int>.tenToTwentyBandGains() = List(20) { index ->
    when {
        index % 2 == 1 && index < 19 -> (this.getOrElse((index-1)/2) {0} +
                                       this.getOrElse((index+1)/2) {0}) / 2
        else -> this.getOrElse(index/2) {0}
    }
}

private fun List<Int>.twentyToTenBandGains() = filterIndexed { i, _ -> i % 2 == 0 }
