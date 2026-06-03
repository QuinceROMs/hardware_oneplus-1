/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lunaris.dolby.R
import org.lunaris.dolby.data.DolbyDispatchers
import org.lunaris.dolby.data.DolbyRepository

class DolbyTileService : TileService() {

    private val repositoryLazy = lazy { DolbyRepository(applicationContext) }
    private val repository by repositoryLazy
    private val halScope = CoroutineScope(SupervisorJob() + DolbyDispatchers.hal)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        halScope.launch {
            val enabled = repository.getDolbyEnabled()
            repository.setDolbyEnabled(!enabled)
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
                    subtitle = profileName(profile)
                    updateTile()
                }
            }
        }
    }

    private fun profileName(profile: Int): String {
        val profiles = resources.getStringArray(R.array.dolby_profile_entries)
        val profileValues = resources.getStringArray(R.array.dolby_profile_values)

        return try {
            val index = profileValues.indexOf(profile.toString())
            if (index != -1) profiles[index] else getString(R.string.dolby_unknown)
        } catch (e: Exception) {
            getString(R.string.dolby_unknown)
        }
    }
}
