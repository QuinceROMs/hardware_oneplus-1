/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.R
import org.lunaris.dolby.data.DolbyDispatchers
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.domain.models.*
import org.lunaris.dolby.utils.ToastHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EqualizerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DolbyRepository(application)
    private val context = application

    private val _uiState = MutableStateFlow<EqualizerUiState>(EqualizerUiState.Loading)
    val uiState: StateFlow<EqualizerUiState> = _uiState.asStateFlow()

    private var currentProfile = 0
    private var currentBandMode = BandMode.TEN_BAND
    private var profileChangeJob: Job? = null

    init {
        DolbyConstants.dlog(TAG, "ViewModel initialized")
        loadEqualizer()
        observeProfileChanges()
    }
    
    private fun observeProfileChanges() {
        profileChangeJob?.cancel()
        profileChangeJob = viewModelScope.launch {
            repository.currentProfile.collect {
                DolbyConstants.dlog(TAG, "Profile changed, reloading equalizer")
                loadEqualizer()
            }
        }
    }

    fun loadEqualizer() {
        viewModelScope.launch {
            try {
                val (profile, bandMode, state) = withContext(DolbyDispatchers.hal) {
                    val profile = repository.getCurrentProfile()
                    val bandMode = repository.getBandMode()
                    val bandGains = repository.getEqualizerGains(profile, bandMode)

                    val builtInPresets = getBuiltInPresets(bandMode)
                    val userPresets = repository.getUserPresets()
                    val allPresets = userPresets + builtInPresets

                    val currentPresetName = repository.getPresetName(profile)
                    val currentPreset = allPresets.find { it.name == currentPresetName }
                        ?: EqualizerPreset(
                            name = context.getString(R.string.dolby_preset_custom),
                            bandGains = bandGains,
                            bandMode = bandMode
                        )

                    Triple(
                        profile,
                        bandMode,
                        EqualizerUiState.Success(
                            presets = allPresets,
                            currentPreset = currentPreset,
                            bandGains = bandGains,
                            bandMode = bandMode
                        )
                    )
                }
                currentProfile = profile
                currentBandMode = bandMode
                _uiState.value = state
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error loading equalizer: ${e.message}")
                _uiState.value = EqualizerUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun getBuiltInPresets(bandMode: BandMode): List<EqualizerPreset> {
        val names = context.resources.getStringArray(R.array.dolby_preset_entries)
        val values = context.resources.getStringArray(R.array.dolby_preset_values)
        
        return names.mapIndexed { index, name ->
            val gains = values[index].split(",").map { it.toInt() }
            
            val frequencies = when (bandMode) {
                BandMode.TEN_BAND -> DolbyRepository.BAND_FREQUENCIES_10
                BandMode.FIFTEEN_BAND -> DolbyRepository.BAND_FREQUENCIES_15
                BandMode.TWENTY_BAND -> DolbyRepository.BAND_FREQUENCIES_20
            }
            
            val tenBandGains = gains.filterIndexed { i, _ -> i % 2 == 0 }
            
            val targetGains = when (bandMode) {
                BandMode.TEN_BAND -> tenBandGains
                BandMode.FIFTEEN_BAND -> {
                    val result = mutableListOf<Int>()
                    result.add(tenBandGains[0])
                    result.add(tenBandGains[0])
                    result.add(tenBandGains[0])
                    result.add(tenBandGains[1])
                    result.add(tenBandGains[2])
                    result.add(tenBandGains[3])
                    result.add(tenBandGains[4])
                    result.add((tenBandGains[4] + tenBandGains[5]) / 2)
                    result.add(tenBandGains[5])
                    result.add((tenBandGains[5] + tenBandGains[6]) / 2)
                    result.add(tenBandGains[6])
                    result.add((tenBandGains[6] + tenBandGains[7]) / 2)
                    result.add(tenBandGains[7])
                    result.add((tenBandGains[7] + tenBandGains[8]) / 2)
                    result.add(tenBandGains[9])
                    result
                }
                BandMode.TWENTY_BAND -> gains
            }
            
            EqualizerPreset(
                name = name,
                bandGains = frequencies.mapIndexed { i, freq ->
                    BandGain(frequency = freq, gain = targetGains.getOrElse(i) { 0 })
                },
                bandMode = bandMode
            )
        }
    }

    fun setBandMode(mode: BandMode) {
        viewModelScope.launch {
            try {
                withContext(DolbyDispatchers.hal) { repository.setBandMode(mode) }
                currentBandMode = mode
                loadEqualizer()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting band mode: ${e.message}")
            }
        }
    }

    fun setPreset(preset: EqualizerPreset) {
        viewModelScope.launch {
            try {
                val profile = currentProfile
                val bandMode = currentBandMode
                val targetGains = if (preset.bandMode != bandMode) {
                    convertPresetToBandMode(preset, bandMode)
                } else {
                    preset.bandGains
                }

                withContext(DolbyDispatchers.hal) {
                    repository.setEqualizerGains(profile, targetGains, bandMode)
                }
                loadEqualizer()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting preset: ${e.message}")
            }
        }
    }

    private fun convertPresetToBandMode(preset: EqualizerPreset, targetMode: BandMode): List<BandGain> {
        val sourceFreqs = when (preset.bandMode) {
            BandMode.TEN_BAND -> DolbyRepository.BAND_FREQUENCIES_10
            BandMode.FIFTEEN_BAND -> DolbyRepository.BAND_FREQUENCIES_15
            BandMode.TWENTY_BAND -> DolbyRepository.BAND_FREQUENCIES_20
        }
        
        val targetFreqs = when (targetMode) {
            BandMode.TEN_BAND -> DolbyRepository.BAND_FREQUENCIES_10
            BandMode.FIFTEEN_BAND -> DolbyRepository.BAND_FREQUENCIES_15
            BandMode.TWENTY_BAND -> DolbyRepository.BAND_FREQUENCIES_20
        }
        
        if (preset.bandGains.size != sourceFreqs.size) {
            DolbyConstants.dlog(TAG, 
                "Preset band count mismatch: expected ${sourceFreqs.size}, got ${preset.bandGains.size}")
            return targetFreqs.map { BandGain(frequency = it, gain = 0) }
        }

        return targetFreqs.map { targetFreq ->
            val closestIdx = sourceFreqs.indexOfFirst { it >= targetFreq }
            val gain = when {
                closestIdx == -1 -> {
                    preset.bandGains.lastOrNull()?.gain ?: 0
                }
                closestIdx == 0 -> {
                    preset.bandGains.firstOrNull()?.gain ?: 0
                }
                else -> {
                    val prevFreq = sourceFreqs[closestIdx - 1]
                    val nextFreq = sourceFreqs[closestIdx]
                    val prevGain = preset.bandGains.getOrNull(closestIdx - 1)?.gain ?: 0
                    val nextGain = preset.bandGains.getOrNull(closestIdx)?.gain ?: 0
                    
                    val ratio = (targetFreq - prevFreq).toFloat() / (nextFreq - prevFreq)
                    (prevGain + ratio * (nextGain - prevGain)).toInt()
                }
            }
            BandGain(frequency = targetFreq, gain = gain)
        }
    }

    fun setBandGain(index: Int, gain: Int) {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                if (state is EqualizerUiState.Success) {
                    val isFlatPreset = state.currentPreset.name == context.getString(R.string.dolby_preset_default)
                    if (!isFlatPreset && state.currentPreset.bandMode != currentBandMode) {
                        ToastHelper.showToast(
                            context,
                            "Cannot edit ${state.currentPreset.bandMode.displayName} preset in ${currentBandMode.displayName} mode. " +
                            "Switch to ${state.currentPreset.bandMode.displayName} or select a different preset."
                        )
                        return@launch
                    }
                    val newBandGains = state.bandGains.toMutableList()
                    newBandGains[index] = newBandGains[index].copy(gain = gain)
                    val profile = currentProfile
                    val bandMode = currentBandMode
                    withContext(DolbyDispatchers.hal) {
                        repository.setEqualizerGains(profile, newBandGains, bandMode)
                    }
                    loadEqualizer()
                }
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting band gain: ${e.message}")
            }
        }
    }

    fun savePreset(name: String): String? {
        val state = _uiState.value
        if (state !is EqualizerUiState.Success) return "Invalid state"
        
        if (state.presets.any { it.name.equals(name.trim(), ignoreCase = true) }) {
            return context.getString(R.string.dolby_geq_preset_name_exists)
        }
        
        if (name.length > 50) {
            return context.getString(R.string.dolby_geq_preset_name_too_long)
        }
        
        val bandMode = currentBandMode
        viewModelScope.launch {
            try {
                withContext(DolbyDispatchers.hal) {
                    repository.addUserPreset(name.trim(), state.bandGains, bandMode)
                }
                loadEqualizer()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error saving preset: ${e.message}")
            }
        }
        
        return null
    }

    fun deletePreset(preset: EqualizerPreset) {
        if (!preset.isUserDefined) return
        
        viewModelScope.launch {
            try {
                withContext(DolbyDispatchers.hal) { repository.deleteUserPreset(preset.name) }
                loadEqualizer()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error deleting preset: ${e.message}")
            }
        }
    }

    fun saveImportedPreset(preset: EqualizerPreset): String? {
        val state = _uiState.value
        if (state !is EqualizerUiState.Success) return "Invalid state"
        
        if (state.presets.any { it.name.equals(preset.name.trim(), ignoreCase = true) }) {
            return context.getString(R.string.dolby_geq_preset_name_exists)
        }
        
        if (preset.name.length > 50) {
            return context.getString(R.string.dolby_geq_preset_name_too_long)
        }
        
        viewModelScope.launch {
            try {
                withContext(DolbyDispatchers.hal) {
                    repository.addUserPreset(
                        preset.name.trim(),
                        preset.bandGains,
                        preset.bandMode
                    )
                }
                loadEqualizer()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error saving imported preset: ${e.message}")
            }
        }
        
        return null
    }

    fun resetGains() {
        viewModelScope.launch {
            try {
                val bandMode = currentBandMode
                val profile = currentProfile
                withContext(DolbyDispatchers.hal) {
                    val flatPreset = getBuiltInPresets(bandMode).first()
                    repository.setEqualizerGains(profile, flatPreset.bandGains, bandMode)
                }
                loadEqualizer()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error resetting gains: ${e.message}")
            }
        }
    }
    
    override fun onCleared() {
        DolbyConstants.dlog(TAG, "ViewModel onCleared")
        profileChangeJob?.cancel()
        profileChangeJob = null
        repository.close()
        super.onCleared()
    }
    
    companion object {
        private const val TAG = "EqualizerViewModel"
    }
}
