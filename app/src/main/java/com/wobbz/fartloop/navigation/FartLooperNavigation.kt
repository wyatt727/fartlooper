package com.wobbz.fartloop.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wobbz.fartloop.SettingsScreen
import com.wobbz.fartloop.design.theme.FartLooperTheme
import com.wobbz.fartloop.feature.home.HomeScreen
import com.wobbz.fartloop.feature.home.model.HomeViewModel
import com.wobbz.fartloop.feature.library.LibraryScreen
import com.wobbz.fartloop.feature.library.model.LibraryViewModel
import com.wobbz.fartloop.feature.rules.RuleBuilderScreen
import timber.log.Timber

/**
 * Main navigation composable for Fart-Looper app.
 *
 * Architecture Finding: Bottom navigation provides immediate access to all primary features.
 * Four-tab structure balances functionality without overwhelming users.
 * Each tab represents a distinct workflow: blast, manage clips, automate, configure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FartLooperNavigation() {
    val navController = rememberNavController()

    FartLooperTheme {
        Scaffold(
            bottomBar = {
                FartLooperBottomBar(navController = navController)
            },
        ) { innerPadding ->
            FartLooperNavHost(
                navController = navController,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

/**
 * Bottom navigation bar with four primary sections.
 *
 * UX Finding: Icons and labels provide immediate recognition of functionality.
 * Badge support ready for future notification features (rule triggers, etc.).
 */
@Composable
private fun FartLooperBottomBar(
    navController: NavHostController,
) {
    val items = listOf(
        BottomNavItem.Home,
        BottomNavItem.Library,
        BottomNavItem.Rules,
        BottomNavItem.Settings,
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        items.forEach { item ->
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.title,
                    )
                },
                label = { Text(item.title) },
                selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                onClick = {
                    Timber.d("Navigation: ${item.title} selected")
                    navController.navigate(item.route) {
                        // Pop up to the start destination of the graph to
                        // avoid building up a large stack of destinations
                        // on the back stack as users select items
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        // Avoid multiple copies of the same destination when
                        // reselecting the same item
                        launchSingleTop = true
                        // Restore state when reselecting a previously selected item
                        restoreState = true
                    }
                },
            )
        }
    }
}

/**
 * Main navigation host containing all feature screens.
 *
 * Implementation Finding: Each feature module provides its own screen composables.
 * Navigation is centralized here to avoid circular dependencies between features.
 * Settings screen will be implemented as a simple composable in this file for now.
 */
@Composable
private fun FartLooperNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = BottomNavItem.Home.route,
        modifier = modifier,
    ) {
        composable(BottomNavItem.Home.route) {
            HomeScreenRoute()
        }

        composable(BottomNavItem.Library.route) {
            LibraryScreenRoute()
        }

        composable(BottomNavItem.Rules.route) {
            RuleBuilderScreen(
                onNavigateBack = {
                    // For now, rules screen doesn't have back navigation
                    // Future: could navigate to rules list first, then builder
                    Timber.d("Rule builder back navigation")
                },
            )
        }

        composable(BottomNavItem.Settings.route) {
            SettingsScreen()
        }
    }
}

/**
 * Bottom navigation items with their routes and display properties.
 *
 * Design Finding: Route names match feature module organization for clarity.
 * Icons chosen for immediate recognition of primary app functions.
 */
sealed class BottomNavItem(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    object Home : BottomNavItem(
        route = "home",
        title = "Home",
        icon = Icons.Default.Home,
    )

    object Library : BottomNavItem(
        route = "library",
        title = "Library",
        icon = Icons.Default.LibraryMusic,
    )

    object Rules : BottomNavItem(
        route = "rules",
        title = "Rules",
        icon = Icons.Default.Rule,
    )

    object Settings : BottomNavItem(
        route = "settings",
        title = "Settings",
        icon = Icons.Default.Settings,
    )
}

/**
 * Home screen route wrapper that injects ViewModel and handles state management.
 *
 * Implementation Finding: Navigation-level ViewModel injection provides clean separation.
 * Each route handles its own ViewModel lifecycle without exposing to navigation internals.
 */
@Composable
private fun HomeScreenRoute() {
    val viewModel: HomeViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

    HomeScreen(
        uiState = uiState,
        onBlastClick = viewModel::startBlast,
        onDeviceClick = viewModel::onDeviceSelected,
        onToggleMetrics = viewModel::toggleMetricsExpansion,
    )
}

/**
 * Library screen route wrapper that injects ViewModel and handles state management.
 */
@Composable
private fun LibraryScreenRoute() {
    val viewModel: LibraryViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

    LibraryScreen(
        uiState = uiState,
        onClipSelected = viewModel::selectClip,
        onClipRemoved = viewModel::removeClip,
        onFilePickerResult = viewModel::handleFilePicked,
        onUrlValidated = viewModel::validateAndAddUrl,
        onShowUrlDialog = viewModel::showUrlDialog,
        onDismissUrlDialog = viewModel::dismissUrlDialog,
    )
}
