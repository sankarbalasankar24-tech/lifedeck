package com.lifedeck.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lifedeck.app.LifeDeckApp
import com.lifedeck.app.MainActivity
import com.lifedeck.app.data.repo.AppSettings
import com.lifedeck.app.ui.components.CelebrationOverlay
import com.lifedeck.app.ui.components.LocalUndo
import com.lifedeck.app.ui.components.UndoController
import com.lifedeck.app.ui.quickadd.QuickAddSheet
import com.lifedeck.app.ui.screens.CalendarScreen
import com.lifedeck.app.ui.screens.EveningReviewScreen
import com.lifedeck.app.ui.screens.GoalDetailScreen
import com.lifedeck.app.ui.screens.HistoryScreen
import com.lifedeck.app.ui.screens.InboxScreen
import com.lifedeck.app.ui.screens.MemoryDetailScreen
import com.lifedeck.app.ui.screens.ProjectDetailScreen
import com.lifedeck.app.ui.screens.ProjectsScreen
import com.lifedeck.app.ui.screens.SettingsScreen
import com.lifedeck.app.ui.screens.ShoppingScreen
import com.lifedeck.app.ui.screens.TaskDetailScreen
import com.lifedeck.app.ui.screens.TodayScreen
import com.lifedeck.app.ui.screens.TripDetailScreen
import com.lifedeck.app.ui.theme.LifeDeckTheme
import com.lifedeck.app.util.Celebration
import com.lifedeck.app.util.CelebrationBus
import kotlinx.coroutines.flow.collectLatest

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.TODAY, "Today", Icons.Default.WbSunny),
    Tab(Routes.INBOX, "Inbox", Icons.Default.Inbox),
    Tab(Routes.PROJECTS, "Goals", Icons.Default.TrackChanges),
    Tab(Routes.SHOPPING, "Shop", Icons.Default.ShoppingCart),
    Tab(Routes.CALENDAR, "Calendar", Icons.Default.CalendarMonth),
    Tab(Routes.HISTORY, "History", Icons.Default.History),
)

/**
 * The whole app shell: theme, navigation, persistent Quick Add button,
 * permission prompts, undo snackbar host and the celebration overlay.
 */
@Composable
fun LifeDeckRoot(initialSharedText: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as LifeDeckApp
    val container = remember { app.container }
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())

    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undo = remember { UndoController(scope, snackbarHostState) }

    var quickAddVisible by rememberSaveable { mutableStateOf(false) }
    var quickAddText by remember { mutableStateOf(initialSharedText ?: "") }
    var celebration by remember { mutableStateOf<Celebration?>(null) }
    var notifPermissionAsked by rememberSaveable { mutableStateOf(false) }

    // Collect achievement events from anywhere in the app.
    LaunchedEffect(Unit) {
        CelebrationBus.events.collectLatest { event ->
            if (settings.celebrateMilestones) celebration = event
        }
    }

    // Ask for notification permission once on Android 13+.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(notifPermissionAsked) {
        if (!notifPermissionAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionAsked = true
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // React to notification deep links (task opened from a reminder).
    LaunchedEffect(Unit) {
        MainActivity.deepLinks.collectLatest { link ->
            when {
                link.taskId != null -> navController.navigate(Routes.task(link.taskId))
                link.sharedText != null -> {
                    quickAddText = link.sharedText
                    quickAddVisible = true
                }
                link.screen == "inbox" -> navigateTab(navController, Routes.INBOX)
                link.screen == "memories" -> navigateTab(navController, Routes.HISTORY)
                link.screen == "today" || link.screen != null -> navigateTab(navController, Routes.TODAY)
            }
        }
    }

    val darkMode = when (settings.themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }

    val density = LocalDensity.current
    val scaledDensity = androidx.compose.ui.unit.Density(
        density.density,
        density.fontScale * settings.textScale,
    )

    LifeDeckTheme(darkTheme = darkMode) {
        CompositionLocalProvider(
            LocalAppContainer provides container,
            LocalUndo provides undo,
            LocalDensity provides scaledDensity,
        ) {
            undo.undoWindowSeconds = settings.undoSeconds

            val backStack by navController.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            val showBottomBar = currentRoute in Routes.bottomTabs

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentRoute == tab.route,
                                    onClick = { navigateTab(navController, tab.route) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                },
                floatingActionButton = {
                    if (showBottomBar) {
                        FloatingActionButton(
                            onClick = { quickAddText = ""; quickAddVisible = true },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp),
                            elevation = FloatingActionButtonDefaults.elevation(
                                defaultElevation = 3.dp,
                                pressedElevation = 6.dp,
                            ),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Quick Add",
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                },
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = Routes.TODAY,
                    modifier = Modifier.padding(padding),
                ) {
                    composable(Routes.TODAY) { TodayScreen(navController) }
                    composable(Routes.INBOX) { InboxScreen(navController) }
                    composable(Routes.PROJECTS) { ProjectsScreen(navController) }
                    composable(Routes.SHOPPING) { ShoppingScreen(navController) }
                    composable(Routes.CALENDAR) { CalendarScreen(navController) }
                    composable(Routes.HISTORY) { HistoryScreen(navController) }
                    composable(Routes.SETTINGS) { SettingsScreen(navController) }
                    composable(Routes.EVENING) { EveningReviewScreen(navController) }
                    composable(
                        Routes.TASK,
                        arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
                    ) { entry ->
                        TaskDetailScreen(entry.arguments?.getString("taskId") ?: "", navController)
                    }
                    composable(
                        Routes.GOAL,
                        arguments = listOf(navArgument("goalId") { type = NavType.StringType }),
                    ) { entry ->
                        GoalDetailScreen(entry.arguments?.getString("goalId") ?: "", navController)
                    }
                    composable(
                        Routes.PROJECT,
                        arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                    ) { entry ->
                        ProjectDetailScreen(entry.arguments?.getString("projectId") ?: "", navController)
                    }
                    composable(
                        Routes.TRIP,
                        arguments = listOf(navArgument("tripId") { type = NavType.StringType }),
                    ) { entry ->
                        TripDetailScreen(entry.arguments?.getString("tripId") ?: "", navController)
                    }
                    composable(
                        Routes.MEMORY,
                        arguments = listOf(navArgument("memoryId") { type = NavType.StringType }),
                    ) { entry ->
                        MemoryDetailScreen(entry.arguments?.getString("memoryId") ?: "", navController)
                    }
                }
            }

            QuickAddSheet(
                visible = quickAddVisible,
                initialText = quickAddText,
                onDismiss = { quickAddVisible = false },
            )

            CelebrationOverlay(
                celebration = celebration,
                onDismiss = { celebration = null },
                confettiEnabled = settings.celebrateConfetti,
            )
        }
    }
}

private fun navigateTab(navController: androidx.navigation.NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
