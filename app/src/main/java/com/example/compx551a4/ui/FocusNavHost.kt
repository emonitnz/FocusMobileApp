package com.example.compx551a4.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.compx551a4.data.UiPreferences
import com.example.compx551a4.ui.calibration.CalibrationScreen
import com.example.compx551a4.ui.home.HomeScreen
import com.example.compx551a4.ui.onboarding.OnboardingScreen
import com.example.compx551a4.ui.settings.SettingsScreen
import com.example.compx551a4.ui.stats.StatsScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// [D] Navigation graph and routes.
//
// String routes rather than navigation-compose's type-safe destinations: the type-safe API needs the
// kotlinx-serialization compiler plugin, and this graph has five destinations and no arguments.

sealed class Route(val path: String) {
    data object Onboarding : Route("onboarding")
    data object Home : Route("home")
    data object Stats : Route("stats")
    data object Settings : Route("settings")
    data object Calibration : Route("calibration")
}

/** The destinations reachable from the bottom bar. Onboarding and calibration are full-screen. */
private data class BarItem(val route: Route, val label: String, val icon: ImageVector)

private val BAR_ITEMS = listOf(
    BarItem(Route.Home, "Focus", Icons.Default.Home),
    BarItem(Route.Stats, "Stats", Icons.Default.DateRange),
    BarItem(Route.Settings, "Settings", Icons.Default.Settings),
)

class RootViewModel(uiPreferences: UiPreferences) : ViewModel() {
    /** Null while the preference is still being read — the graph cannot be built without it. */
    val startRoute = uiPreferences.prefs
        .map { if (it.onboardingComplete) Route.Home.path else Route.Onboarding.path }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        val Factory = viewModelFactory {
            initializer { RootViewModel(container().uiPreferences) }
        }
    }
}

@Composable
fun FocusNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    viewModel: RootViewModel = viewModel(factory = RootViewModel.Factory),
) {
    val startRoute by viewModel.startRoute.collectAsStateWithLifecycle()

    // Latched: once the graph exists, its start destination must not change. Finishing onboarding
    // flips the preference, and letting that reach `startDestination` would rebuild the whole graph
    // underneath the user mid-navigation.
    var latchedStart by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(startRoute) {
        if (latchedStart == null) latchedStart = startRoute
    }

    // Building the NavHost before the start destination is known would land the user on Home and
    // then yank them to Onboarding a frame later.
    val start = latchedStart ?: run {
        Box(modifier.fillMaxSize())
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    val showBar = BAR_ITEMS.any { item ->
        currentRoute?.hierarchy?.any { it.route == item.route.path } == true
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    BAR_ITEMS.forEach { item ->
                        val selected = currentRoute?.hierarchy?.any { it.route == item.route.path } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // Insets are applied per destination, not to the NavHost as a whole: the calibration task
        // screens must paint edge to edge, or the light system-bar strips break the matched visual
        // field the whole protocol depends on.
        val barPadding = Modifier.padding(innerPadding)

        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Route.Onboarding.path) {
                OnboardingScreen(
                    modifier = barPadding,
                    onFinished = {
                        navController.navigate(Route.Home.path) {
                            popUpTo(Route.Onboarding.path) { inclusive = true }
                        }
                    },
                )
            }
            composable(Route.Home.path) {
                HomeScreen(
                    modifier = barPadding,
                    onCalibrate = { navController.navigate(Route.Calibration.path) },
                    onOpenSettings = { navController.navigateToTab(Route.Settings) },
                )
            }
            composable(Route.Stats.path) {
                StatsScreen(modifier = barPadding)
            }
            composable(Route.Settings.path) {
                SettingsScreen(
                    modifier = barPadding,
                    onCalibrate = { navController.navigate(Route.Calibration.path) },
                    onReRunOnboarding = { navController.navigate(Route.Onboarding.path) },
                )
            }
            composable(Route.Calibration.path) {
                CalibrationScreen(onDone = { navController.popBackStack() })
            }
        }
    }
}

/** Tab switching: single instance, state preserved, no back stack of tabs. */
private fun NavHostController.navigateToTab(route: Route) {
    navigate(route.path) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
