/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.tile

import android.content.Context
import android.content.SharedPreferences
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DolbyDispatchers
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.domain.models.dolbyProfileName
import org.lunaris.dolby.service.DolbyEffectService

class DolbyTileService : TileService() {

    private val repositoryLazy = lazy { DolbyRepository(applicationContext) }
    private val repository by repositoryLazy
    private val halScope = CoroutineScope(SupervisorJob() + DolbyDispatchers.hal)
    private val prefs by lazy { getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE) }
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DolbyConstants.PREF_ENABLE || key == DolbyConstants.PREF_PROFILE) {
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        updateTile()
    }

    override fun onStopListening() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        halScope.launch {
            val newState = !repository.getDolbyEnabled()
            repository.setDolbyEnabled(newState)
            if (newState) {
                DolbyEffectService.start(applicationContext)
            } else {
                DolbyEffectService.stop(applicationContext)
            }
        }
        updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        halScope.cancel()
        if (repositoryLazy.isInitialized()) repository.close()
    }

    private fun updateTile() {
        halScope.launch {
            val enabled = repository.getDolbyEnabled()
            val profile = repository.getCurrentProfile()
            withContext(Dispatchers.Main) {
                qsTile?.apply {
                    state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    subtitle = dolbyProfileName(this@DolbyTileService, profile)
                    updateTile()
                }
            }
        }
    }
}
