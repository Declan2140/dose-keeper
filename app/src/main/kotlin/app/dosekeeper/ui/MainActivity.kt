package app.dosekeeper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.dosekeeper.app

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DoseKeeperTheme {
                var onboarded by remember { mutableStateOf(app.settings.onboardingDone) }
                var justOnboarded by remember { mutableStateOf(false) }
                if (!onboarded) {
                    Onboarding(onFinished = {
                        app.settings.onboardingDone = true
                        justOnboarded = true
                        onboarded = true
                    })
                } else {
                    AppNav(startOnAdd = justOnboarded)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.unlock.connect()
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Today", Icons.Filled.Today),
    MEDICINES("medicines", "Medicines", Icons.Filled.Medication),
    HISTORY("history", "History", Icons.Filled.History),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/** Tabs in bar order, with every screen opened from a tab counted as further right. */
private fun depth(entry: NavBackStackEntry): Int =
    Tab.entries.indexOfFirst { it.route == entry.destination.route }.takeIf { it >= 0 } ?: Tab.entries.size

/** Slides toward a tab or screen to the right, and back the other way. Going back always slides right. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.slideDirection(back: Boolean = false): SlideDirection {
    val target = depth(targetState)
    val initial = depth(initialState)
    return if (target > initial || (target == initial && !back)) SlideDirection.Left else SlideDirection.Right
}

private val slide = tween<IntOffset>(300)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppNav(startOnAdd: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == route }
    val subTitle = when {
        route == null -> null
        route == "reliability" -> "Reliability checklist"
        route.startsWith("profiles") -> "People and pets"
        else -> null
    }

    Scaffold(
        topBar = {
            if (subTitle != null) {
                TopAppBar(
                    title = { Text(subTitle) },
                    navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            }
        },
        bottomBar = {
            if (tab != null) {
                Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        )
                    }
                }
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = "today",
            modifier = Modifier.padding(padding),
            enterTransition = { slideIntoContainer(slideDirection(), slide) },
            exitTransition = { slideOutOfContainer(slideDirection(), slide) },
            popEnterTransition = { slideIntoContainer(slideDirection(back = true), slide) },
            popExitTransition = { slideOutOfContainer(slideDirection(back = true), slide) },
        ) {
            composable("today") {
                TodayScreen(onAddMedicine = { nav.navigate("edit/0") }, onOpenReliability = { nav.navigate("reliability") })
            }
            composable("medicines") { MedicinesScreen(onEdit = { nav.navigate("edit/$it") }) }
            composable("history") { HistoryScreen() }
            composable("settings") {
                SettingsScreen(onOpenProfiles = { nav.navigate("profiles?add=false") }, onOpenReliability = { nav.navigate("reliability") })
            }
            composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                EditMedicineScreen(
                    medicineId = e.arguments?.getLong("id") ?: 0L,
                    onDone = { nav.popBackStack() },
                    onAddProfile = { nav.navigate("profiles?add=true") },
                )
            }
            composable("profiles?add={add}", arguments = listOf(navArgument("add") { type = NavType.BoolType; defaultValue = false })) { e ->
                ProfilesScreen(startAdding = e.arguments?.getBoolean("add") ?: false)
            }
            composable("reliability") { ReliabilityScreen() }
        }
    }

    androidx.compose.runtime.LaunchedEffect(startOnAdd) {
        if (startOnAdd) nav.navigate("edit/0")
    }
}
