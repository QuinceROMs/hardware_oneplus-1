/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DolbyDispatchers
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.domain.models.*
import org.lunaris.dolby.service.DolbyEffectService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DolbyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DolbyRepository(application)

    private val _uiState = MutableStateFlow<DolbyUiState>(DolbyUiState.Loading)
    val uiState: StateFlow<DolbyUiState> = _uiState.asStateFlow()
    val currentProfile: StateFlow<Int> = repository.currentProfile
    
    private var speakerStateJob: Job? = null
    private var profileChangeJob: Job? = null

    init {
        DolbyConstants.dlog(TAG, "ViewModel initialized")
        loadSettings()
        observeSpeakerState()
        observeProfileChanges()
    }
    
    private fun observeSpeakerState() {
        speakerStateJob?.cancel()
        speakerStateJob = viewModelScope.launch {
            repository.isOnSpeaker.collect {
                DolbyConstants.dlog(TAG, "Speaker state changed: $it")
                loadSettings()
            }
        }
    }
    
    private fun observeProfileChanges() {
        profileChangeJob?.cancel()
        profileChangeJob = viewModelScope.launch {
            repository.currentProfile.collect {
                DolbyConstants.dlog(TAG, "Profile changed to: $it")
                loadSettings()
            }
        }
    }

    fun loadSettings() {
        viewModelScope.launch {
            try {
                val newState = withContext(DolbyDispatchers.hal) {
                    val enabled = repository.getDolbyEnabled()
                    val profile = repository.getCurrentProfile()
                    val bandMode = repository.getBandMode()

                    val settings = DolbySettings(
                        enabled = enabled,
                        currentProfile = profile,
                        bassEnhancerEnabled = repository.getBassEnhancerEnabled(profile),
                        volumeLevelerEnabled = repository.getVolumeLevelerEnabled(profile),
                        bandMode = bandMode
                    )

                    val profileSettings = ProfileSettings(
                        profile = profile,
                        ieqPreset = repository.getIeqPreset(profile),
                        headphoneVirtualizerEnabled = repository.getHeadphoneVirtualizerEnabled(profile),
                        speakerVirtualizerEnabled = repository.getSpeakerVirtualizerEnabled(profile),
                        stereoWideningAmount = repository.getStereoWideningAmount(profile),
                        dialogueEnhancerEnabled = repository.getDialogueEnhancerEnabled(profile),
                        dialogueEnhancerAmount = repository.getDialogueEnhancerAmount(profile),
                        bassLevel = repository.getBassLevel(profile),
                        midLevel = repository.getMidLevel(profile),
                        trebleLevel = repository.getTrebleLevel(profile),
                        bassCurve = repository.getBassCurve(profile)
                    )

                    DolbyUiState.Success(
                        settings = settings,
                        profileSettings = profileSettings,
                        currentPresetName = repository.getPresetName(profile),
                        isOnSpeaker = repository.isOnSpeaker.value
                    )
                }
                _uiState.value = newState
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error loading settings: ${e.message}")
                _uiState.value = DolbyUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun setDolbyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                withContext(DolbyDispatchers.hal) { repository.setDolbyEnabled(enabled) }
                if (enabled) {
                    DolbyEffectService.start(getApplication())
                } else {
                    DolbyEffectService.stop(getApplication())
                }
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting Dolby enabled: ${e.message}")
            }
        }
    }

    fun setProfile(profile: Int) {
        viewModelScope.launch {
            try {
                withContext(DolbyDispatchers.hal) { repository.setCurrentProfile(profile) }
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting profile: ${e.message}")
            }
        }
    }

    private fun mutate(block: suspend (Int) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(DolbyDispatchers.hal) { block(repository.getCurrentProfile()) }
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error updating setting: ${e.message}")
            }
        }
    }

    private fun mutateLevel(name: String, block: suspend (Int) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(DolbyDispatchers.hal) { block(repository.getCurrentProfile()) }
                loadSettings()
            } catch (e: IllegalArgumentException) {
                DolbyConstants.dlog(TAG, "Invalid $name level: ${e.message}")
                _uiState.value = DolbyUiState.Error("Invalid $name level: ${e.message}")
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting $name level: ${e.message}")
                _uiState.value = DolbyUiState.Error("Failed to set $name level")
            }
        }
    }

    fun setBassEnhancer(enabled: Boolean) = mutate { repository.setBassEnhancerEnabled(it, enabled) }

    fun setBassLevel(level: Int) = mutateLevel("bass") { repository.setBassLevel(it, level) }

    fun setBassCurve(curve: Int) = mutate { repository.setBassCurve(it, curve) }

    fun setMidLevel(level: Int) = mutateLevel("mid") { repository.setMidLevel(it, level) }

    fun setTrebleLevel(level: Int) = mutateLevel("treble") { repository.setTrebleLevel(it, level) }

    fun setVolumeLeveler(enabled: Boolean) = mutate { repository.setVolumeLevelerEnabled(it, enabled) }

    fun setIeqPreset(preset: Int) = mutate { repository.setIeqPreset(it, preset) }

    fun setHeadphoneVirtualizer(enabled: Boolean) = mutate { repository.setHeadphoneVirtualizerEnabled(it, enabled) }

    fun setSpeakerVirtualizer(enabled: Boolean) = mutate { repository.setSpeakerVirtualizerEnabled(it, enabled) }

    fun setStereoWidening(amount: Int) = mutate { repository.setStereoWideningAmount(it, amount) }

    fun setDialogueEnhancer(enabled: Boolean) = mutate { repository.setDialogueEnhancerEnabled(it, enabled) }

    fun setDialogueEnhancerAmount(amount: Int) = mutate { repository.setDialogueEnhancerAmount(it, amount) }

    fun resetAllProfiles() {
        viewModelScope.launch {
            try {
                withContext(DolbyDispatchers.hal) { repository.resetAllProfiles() }
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error resetting profiles: ${e.message}")
            }
        }
    }

    fun updateSpeakerState() {
        viewModelScope.launch {
            withContext(DolbyDispatchers.hal) { repository.updateSpeakerState() }
        }
    }
    
    override fun onCleared() {
        DolbyConstants.dlog(TAG, "ViewModel onCleared")
        speakerStateJob?.cancel()
        speakerStateJob = null
        profileChangeJob?.cancel()
        profileChangeJob = null
        repository.close()
        super.onCleared()
    }
    
    companion object {
        private const val TAG = "DolbyViewModel"
    }
}
