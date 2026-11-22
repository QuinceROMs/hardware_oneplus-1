/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.lunaris.dolby.R
import org.lunaris.dolby.ui.components.*
import org.lunaris.dolby.domain.models.*
import org.lunaris.dolby.utils.*

@Composable
internal fun BandMode.label(): String =
    pluralStringResource(R.plurals.band_count, bandCount, bandCount)

@Composable
internal fun BandModeSelector(
    currentMode: BandMode,
    onModeChange: (BandMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            EqualizerSectionHeader(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.band_configuration),
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Text(
                text = stringResource(R.string.choose_equalizer_precision),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BandMode.values().forEach { mode ->
                    BandModeTile(
                        mode = mode,
                        isSelected = currentMode == mode,
                        onClick = {
                            scope.launch {
                                haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.CLICK)
                            }
                            onModeChange(mode)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BandModeTile(
    mode: BandMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    
    Surface(
        onClick = {
            scope.launch {
                haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.CLICK)
            }
            onClick()
        },
        modifier = modifier
            .height(80.dp)
            .squishable(enabled = true, scaleDown = 0.93f),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isSelected)
            MaterialTheme.colorScheme.onPrimaryContainer
        else
            MaterialTheme.colorScheme.onSurface,
        shape = if (isSelected)
            MaterialTheme.shapes.extraLarge
        else
            MaterialTheme.shapes.large,
        border = tileSelectionBorder(isSelected)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = if (isSelected)
                    MaterialTheme.shapes.extraLarge
                else
                    MaterialTheme.shapes.small,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = mode.value,
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = mode.label(),
                style = if (isSelected) MaterialTheme.typography.labelSmallEmphasized
                    else MaterialTheme.typography.labelSmall,
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

