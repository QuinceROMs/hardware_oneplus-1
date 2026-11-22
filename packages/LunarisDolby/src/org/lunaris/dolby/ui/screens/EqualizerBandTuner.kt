/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.lunaris.dolby.R
import org.lunaris.dolby.domain.models.BandGain
import org.lunaris.dolby.domain.models.BandMode
import org.lunaris.dolby.ui.components.squishable
import org.lunaris.dolby.utils.HapticFeedbackHelper
import org.lunaris.dolby.utils.rememberHapticFeedback

@Composable
fun BandTunerCard(
    bandGains: List<BandGain>,
    bandMode: BandMode,
    onGainChange: (Int, Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (bandGains.isEmpty()) return

    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    var selectedIndex by remember(bandMode, bandGains.size) { mutableIntStateOf(0) }
    val index = selectedIndex.coerceIn(0, bandGains.lastIndex)
    val band = bandGains[index]

    var sliderValue by remember(index, band.gain) { mutableFloatStateOf(band.gain / 10f) }
    var lastHapticStep by remember(index) { mutableIntStateOf(band.gain) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.band_tuner),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.band_tuner_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BandStepButton(
                    icon = Icons.Default.ChevronLeft,
                    contentDescription = stringResource(R.string.band_tuner_previous),
                    enabled = enabled && index > 0,
                    onClick = { selectedIndex = index - 1 }
                )

                Text(
                    text = stringResource(
                        R.string.band_tuner_position,
                        index + 1,
                        bandGains.size
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )

                BandStepButton(
                    icon = Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.band_tuner_next),
                    enabled = enabled && index < bandGains.lastIndex,
                    onClick = { selectedIndex = index + 1 }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.band_tuner_label,
                        index + 1,
                        formatFrequency(band.frequency)
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (enabled) MaterialTheme.colorScheme.primaryContainer
                           else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = formatGain(sliderValue),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Slider(
                value = sliderValue,
                onValueChange = { newValue ->
                    val step = (newValue * 10).toInt()
                    if (step != lastHapticStep) {
                        scope.launch {
                            haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.TEXTURE_TICK)
                        }
                        lastHapticStep = step
                    }
                    sliderValue = newValue
                },
                onValueChangeFinished = {
                    onGainChange(index, (sliderValue * 10).toInt())
                },
                enabled = enabled,
                valueRange = -15f..15f,
                steps = 299,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledThumbColor = MaterialTheme.colorScheme.outline,
                    disabledActiveTrackColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BandAdjustButton(
                    label = stringResource(R.string.band_tuner_decrement),
                    enabled = enabled && band.gain > -150,
                    onClick = {
                        val newGain = (band.gain - 1).coerceAtLeast(-150)
                        sliderValue = newGain / 10f
                        onGainChange(index, newGain)
                    },
                    modifier = Modifier.weight(1f)
                )
                BandAdjustButton(
                    label = stringResource(R.string.band_tuner_reset),
                    enabled = enabled && band.gain != 0,
                    onClick = {
                        sliderValue = 0f
                        onGainChange(index, 0)
                    },
                    modifier = Modifier.weight(1f)
                )
                BandAdjustButton(
                    label = stringResource(R.string.band_tuner_increment),
                    enabled = enabled && band.gain < 150,
                    onClick = {
                        val newGain = (band.gain + 1).coerceAtMost(150)
                        sliderValue = newGain / 10f
                        onGainChange(index, newGain)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BandStepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
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
        enabled = enabled,
        modifier = Modifier
            .size(width = 64.dp, height = 40.dp)
            .squishable(enabled = enabled, scaleDown = 0.9f),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
               else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurface
                      else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun BandAdjustButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()

    Surface(
        onClick = {
            scope.launch {
                haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.TICK)
            }
            onClick()
        },
        enabled = enabled,
        modifier = modifier
            .height(48.dp)
            .squishable(enabled = enabled, scaleDown = 0.93f),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
               else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

private fun formatFrequency(frequency: Int): String =
    if (frequency >= 1000) "%.1f kHz".format(frequency / 1000f) else "$frequency Hz"

private fun formatGain(gain: Float): String {
    val rounded = Math.round(gain * 10f) / 10f
    return if (rounded > 0f) "+%.1f dB".format(rounded) else "%.1f dB".format(rounded + 0f)
}
