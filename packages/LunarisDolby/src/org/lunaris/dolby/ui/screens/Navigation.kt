/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.lunaris.dolby.ui.components.FloatingNavToolbar
import org.lunaris.dolby.ui.viewmodel.AppProfileViewModel
import org.lunaris.dolby.ui.viewmodel.DolbyViewModel
import org.lunaris.dolby.ui.viewmodel.EqualizerViewModel

sealed class Screen(val route: String) {
    object Settings : Screen("settings")
    object Equalizer : Screen("equalizer")
    object Advanced : Screen("advanced")
    object AppProfiles : Screen("app_profiles")
    object ImportExport : Screen("import_export")
}

@Composable
fun DolbyNavHost(
    dolbyViewModel: DolbyViewModel,
    equalizerViewModel: EqualizerViewModel
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Settings.route
    ) {
        composable(Screen.Settings.route) {
            ModernDolbySettingsScreen(
                viewModel = dolbyViewModel,
                navController = navController
            )
        }

        composable(Screen.Equalizer.route) {
            LaunchedEffect(Unit) {
                equalizerViewModel.loadEqualizer()
            }

            ModernEqualizerScreen(
                viewModel = equalizerViewModel,
                navController = navController
            )
        }

        composable(Screen.Advanced.route) {
            ModernAdvancedSettingsScreen(
                viewModel = dolbyViewModel,
                navController = navController
            )
        }

        composable(Screen.AppProfiles.route) {
            val context = LocalContext.current
            val appProfileViewModel: AppProfileViewModel = viewModel(
                factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
                    context.applicationContext as android.app.Application
                )
            )

            AppProfileScreen(
                viewModel = appProfileViewModel,
                navController = navController
            )
        }

        composable(Screen.ImportExport.route) {
            PresetImportExportScreen(
                viewModel = equalizerViewModel,
                navController = navController
            )
        }
    }
}

/**
 * Bottom navigation bar shared by the Settings, Equalizer and Advanced screens.
 * Owns the back-stack route lookup, display-cutout insets and the navigate
 * action so the screens only need to place it inside their content.
 */
@Composable
fun DolbyBottomNav(
    navController: NavController,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val currentRoute by navController.currentBackStackEntryFlow.collectAsState(null)
    val layoutDirection = LocalLayoutDirection.current
    val cutoutInsets = WindowInsets.displayCutout.asPaddingValues()
    val route = currentRoute?.destination?.route ?: Screen.Settings.route

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = cutoutInsets.calculateStartPadding(layoutDirection),
                end = cutoutInsets.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding()
            ),
        contentAlignment = Alignment.Center
    ) {
        FloatingNavToolbar(
            currentRoute = route,
            onNavigate = { target ->
                if (route != target) {
                    navController.navigate(target) {
                        popUpTo(Screen.Settings.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        )
    }
}
