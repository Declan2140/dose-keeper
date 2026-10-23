package app.dosekeeper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                NavigationBar {
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
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding)) {
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
