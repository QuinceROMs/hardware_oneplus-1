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
import org.lunaris.dolby.data.AppProfileManager
import org.lunaris.dolby.domain.models.AppProfileUiState
import org.lunaris.dolby.utils.ToastHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val appProfileManager = AppProfileManager(application)
    private val context = application

    private val _uiState = MutableStateFlow<AppProfileUiState>(AppProfileUiState.Loading)
    val uiState: StateFlow<AppProfileUiState> = _uiState.asStateFlow()
    
    init {
        DolbyConstants.dlog(TAG, "ViewModel initialized")
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            try {
                _uiState.value = AppProfileUiState.Loading
                val (apps, appsWithProfiles) = withContext(Dispatchers.IO) {
                    appProfileManager.getInstalledApps() to appProfileManager.getAppsWithProfiles()
                }

                _uiState.value = AppProfileUiState.Success(
                    apps = apps,
                    appsWithProfiles = appsWithProfiles
                )
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error loading apps: ${e.message}")
                _uiState.value = AppProfileUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun setAppProfile(packageName: String, profile: Int) {
        viewModelScope.launch {
            try {
                if (profile == -1) {
                    withContext(Dispatchers.IO) { appProfileManager.removeAppProfile(packageName) }
                    ToastHelper.showToast(context, "Profile reset to default")
                } else {
                    withContext(Dispatchers.IO) { appProfileManager.setAppProfile(packageName, profile) }
                    val profileName = getProfileName(profile)
                    ToastHelper.showToast(context, "Profile set to: $profileName")
                }
                loadApps()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting app profile: ${e.message}")
            }
        }
    }

    fun removeAppProfile(packageName: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { appProfileManager.removeAppProfile(packageName) }
                ToastHelper.showToast(context, "Profile removed")
                loadApps()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error removing app profile: ${e.message}")
            }
        }
    }

    fun clearAllAppProfiles() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { appProfileManager.clearAllAppProfiles() }
                ToastHelper.showToast(context, "All app profiles cleared")
                loadApps()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error clearing app profiles: ${e.message}")
            }
        }
    }
    
    private fun getProfileName(profile: Int): String {
        return try {
            val profiles = context.resources.getStringArray(R.array.dolby_profile_entries)
            val profileValues = context.resources.getStringArray(R.array.dolby_profile_values)
            
            val index = profileValues.indexOfFirst { it.toInt() == profile }
            if (index >= 0) profiles[index] else "Unknown"
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting profile name: ${e.message}")
            "Unknown"
        }
    }
    
    override fun onCleared() {
        DolbyConstants.dlog(TAG, "ViewModel onCleared")
        super.onCleared()
    }
    
    companion object {
        private const val TAG = "AppProfileViewModel"
    }
}
