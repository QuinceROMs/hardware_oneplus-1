/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.lunaris.dolby.R
import org.lunaris.dolby.ui.components.*
import org.lunaris.dolby.ui.viewmodel.EqualizerViewModel
import org.lunaris.dolby.domain.models.*
import org.lunaris.dolby.utils.*
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.unit.Dp

enum class EqualizerViewMode {
    CURVE,
    SLIDERS
}

private const val EQUALIZER_FREQUENCY_RANGE = "32Hz - 19.7kHz"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModernEqualizerScreen(
    viewModel: EqualizerViewModel,
    navController: NavController,
    bottomPadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val showTitle by remember { derivedStateOf { scrollState.value > 0 } }
    var showActions by remember { mutableStateOf(false) }
    var showAutoEqDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(EqualizerViewMode.CURVE) }

    Scaffold(
        topBar = {
            DolbyTopAppBar(
                title = stringResource(R.string.dolby_preset),
                showTitle = showTitle || uiState !is EqualizerUiState.Success,
                actions = {
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(Icons.Default.Save, stringResource(R.string.dolby_save_preset))
                    }
                    IconButton(onClick = { showAutoEqDialog = true }) {
                        Icon(Icons.Default.Headphones, stringResource(R.string.dolby_autoeq_title))
                    }
                    Box {
                        IconButton(onClick = { showActions = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.dolby_more_options))
                        }
                        DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.preset_import_export)) },
                                leadingIcon = { Icon(Icons.Default.ImportExport, null) },
                                onClick = {
                                    showActions = false
                                    navController.navigate("import_export")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dolby_geq_reset_gains)) },
                                leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                                onClick = {
                                    showActions = false
                                    showResetDialog = true
                                },
                            )
                            if ((uiState as? EqualizerUiState.Success)?.currentPreset?.isUserDefined == true) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.dolby_geq_delete_preset)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = {
                                        showActions = false
                                        showDeleteDialog = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is EqualizerUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            is EqualizerUiState.Success -> {
                ModernEqualizerContent(
                    state = state,
                    scrollState = scrollState,
                    viewModel = viewModel,
                    viewMode = viewMode,
                    onViewModeChange = { viewMode = it },
                    bottomPadding = bottomPadding,
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is EqualizerUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            onSave = { name ->
                val error = viewModel.savePreset(name)
                if (error == null) {
                    showSaveDialog = false
                }
                error
            },
            onDismiss = { showSaveDialog = false }
        )
    }

    if (showDeleteDialog && uiState is EqualizerUiState.Success) {
        val state = uiState as EqualizerUiState.Success
        ModernConfirmDialog(
            title = stringResource(R.string.dolby_geq_delete_preset),
            message = stringResource(R.string.dolby_geq_delete_preset_prompt),
            icon = Icons.Default.Delete,
            onConfirm = {
                viewModel.deletePreset(state.currentPreset)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showResetDialog) {
        ModernConfirmDialog(
            title = stringResource(R.string.dolby_geq_reset_gains),
            message = stringResource(R.string.dolby_geq_reset_gains_prompt),
            icon = Icons.Default.RestartAlt,
            onConfirm = {
                viewModel.resetGains()
                showResetDialog = false
            },
            onDismiss = { showResetDialog = false }
        )
    }

    if (showAutoEqDialog) {
        AutoEqSelectionDialog(
            viewModel = viewModel,
            onDismiss = { showAutoEqDialog = false }
        )
    }
}

@Composable
fun EqualizerSectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModernEqualizerContent(
    state: EqualizerUiState.Success,
    scrollState: ScrollState,
    viewModel: EqualizerViewModel,
    viewMode: EqualizerViewMode,
    onViewModeChange: (EqualizerViewMode) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val isFlatPreset = state.currentPreset.name == stringResource(R.string.dolby_preset_default)
    val isBandModeCompatible = state.currentPreset.bandMode == state.bandMode
    val canEdit = isBandModeCompatible || isFlatPreset
    val isActive = canEdit && !isFlatPreset
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 12.dp + bottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        DolbyPageHeading(
            title = stringResource(R.string.dolby_preset),
            subtitle = stringResource(R.string.dolby_equalizer_summary),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            ModernPresetSelector(
                presets = state.presets,
                currentPreset = state.currentPreset,
                onPresetSelected = { viewModel.setPreset(it) }
            )
        }
        
        BandModeSelector(
            currentMode = state.bandMode,
            onModeChange = { viewModel.setBandMode(it) }
        )
        
        if (!isBandModeCompatible && !isFlatPreset) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.band_mode_mismatch),
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.band_mode_mismatch_message,
                                state.currentPreset.bandMode.label(),
                                state.bandMode.label()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                EqualizerSectionHeader(
                    icon = Icons.Default.Visibility,
                    title = stringResource(R.string.dolby_geq_view),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ViewModeTile(
                        title = stringResource(R.string.dolby_geq_view_curve),
                        icon = Icons.AutoMirrored.Filled.ShowChart,
                        isSelected = viewMode == EqualizerViewMode.CURVE,
                        onClick = { onViewModeChange(EqualizerViewMode.CURVE) },
                        modifier = Modifier.weight(1f)
                    )
                    
                    ViewModeTile(
                        title = stringResource(R.string.dolby_geq_view_sliders),
                        icon = Icons.Default.Tune,
                        isSelected = viewMode == EqualizerViewMode.SLIDERS,
                        onClick = { onViewModeChange(EqualizerViewMode.SLIDERS) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        val viewTransitionSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = viewMode,
            transitionSpec = {
                fadeIn(animationSpec = viewTransitionSpec) togetherWith
                fadeOut(animationSpec = viewTransitionSpec)
            },
            label = "equalizer_view_transition"
        ) { mode ->
            when (mode) {
                EqualizerViewMode.CURVE -> {
                    CurveViewContent(
                        state = state,
                        viewModel = viewModel,
                        canEdit = canEdit,
                        isActive = isActive
                    )
                }
                EqualizerViewMode.SLIDERS -> {
                    SlidersViewContent(
                        state = state,
                        viewModel = viewModel,
                        canEdit = canEdit
                    )
                }
            }
        }

        BandTunerCard(
            bandGains = state.bandGains,
            bandMode = state.bandMode,
            onGainChange = { index, gain ->
                if (canEdit) {
                    viewModel.setBandGain(index, gain)
                }
            },
            enabled = canEdit
        )

    }
}

@Composable
private fun CurveViewContent(
    state: EqualizerUiState.Success,
    viewModel: EqualizerViewModel,
    canEdit: Boolean,
    isActive: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (canEdit) stringResource(R.string.interactive_frequency_response)
                          else stringResource(R.string.frequency_response_ro),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = if (canEdit) MaterialTheme.colorScheme.onSurface
                          else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (canEdit) MaterialTheme.colorScheme.secondaryContainer
                          else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = "${state.bandMode.bandCount} " + stringResource(R.string.dolby_geq_l_bands),
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = if (canEdit) MaterialTheme.colorScheme.onSecondaryContainer
                              else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Text(
                text = if (canEdit) 
                    stringResource(R.string.interactive_frequency_response_summary) + " • ${EQUALIZER_FREQUENCY_RANGE}"
                else
                    stringResource(R.string.frequency_response_ro_summary),
                style = MaterialTheme.typography.bodySmall,
                color = if (canEdit) MaterialTheme.colorScheme.onSurfaceVariant
                      else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
            InteractiveFrequencyResponseCurve(
                bandGains = state.bandGains,
                onBandGainChange = { index, newGain ->
                    if (canEdit) {
                        viewModel.setBandGain(index, newGain)
                    }
                },
                isActive = isActive,
                isEditable = canEdit,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun SlidersViewContent(
    state: EqualizerUiState.Success,
    viewModel: EqualizerViewModel,
    canEdit: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.frequency_response),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = EQUALIZER_FREQUENCY_RANGE,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                FrequencyResponseCurve(
                    bandGains = state.bandGains,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (canEdit) stringResource(R.string.dolby_geq_slider_label_gain)
                              else "${stringResource(R.string.dolby_geq_slider_label_gain)} (${stringResource(R.string.dolby_read_only)})",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = if (canEdit) MaterialTheme.colorScheme.onSurface
                              else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (!canEdit) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = stringResource(R.string.locked),
                                    style = MaterialTheme.typography.labelSmallEmphasized,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(state.bandGains) { index, bandGain ->
                        ModernEqualizerBand(
                            frequency = bandGain.frequency,
                            gain = bandGain.gain,
                            onGainChange = { newGain ->
                                if (canEdit) {
                                    viewModel.setBandGain(index, newGain)
                                }
                            },
                            enabled = canEdit
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewModeTile(
    title: String,
    icon: ImageVector,
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
            .height(72.dp)
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
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = if (isSelected)
                    MaterialTheme.shapes.extraLarge
                else
                    MaterialTheme.shapes.medium,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = title,
                style = if (isSelected) MaterialTheme.typography.titleMediumEmphasized
                    else MaterialTheme.typography.titleMedium,
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
