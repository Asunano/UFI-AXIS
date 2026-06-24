package com.ufi_axis.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ufi_axis.ui.screens.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "首页", Icons.Default.Home)
    data object Network : Screen("network", "网络", Icons.Default.Wifi)
    data object Tools : Screen("tools", "工具", Icons.Default.Build)
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
}

val bottomNavItems = listOf(Screen.Dashboard, Screen.Network, Screen.Tools, Screen.Settings)
private val bottomNavRoutes = bottomNavItems.map { it.route }.toSet()

// --- Page transition helpers ---

// Tab page transitions (crossfade with subtle scale)
private val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.98f, animationSpec = tween(300))
}
private val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.98f, animationSpec = tween(200))
}

// Detail page transitions (horizontal slide)
private val detailEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(initialOffsetX = { it / 3 }, animationSpec = tween(350, easing = FastOutSlowInEasing)) +
            fadeIn(animationSpec = tween(350))
}
private val detailExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(250))
}
private val detailPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(250))
}
private val detailPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(targetOffsetX = { it / 3 }, animationSpec = tween(350, easing = FastOutSlowInEasing)) +
            fadeOut(animationSpec = tween(350))
}

@Composable
fun MainNavGraph(viewModel: MainViewModel, onServerConfigChanged: () -> Unit) {
    val navController = rememberNavController()
    val palette = LocalResolvedPalette.current
    Scaffold(
        bottomBar = { BottomNavBar(navController) },
        containerColor = palette.pageBg
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route,
                enterTransition = tabEnter, exitTransition = tabExit,
                popEnterTransition = tabEnter, popExitTransition = tabExit
            ) { DashboardScreen(viewModel, navController) }
            composable(Screen.Network.route,
                enterTransition = tabEnter, exitTransition = tabExit,
                popEnterTransition = tabEnter, popExitTransition = tabExit
            ) { NetworkScreen(viewModel, navController) }
            composable(Screen.Tools.route,
                enterTransition = tabEnter, exitTransition = tabExit,
                popEnterTransition = tabEnter, popExitTransition = tabExit
            ) { ToolsScreen(viewModel, navController) }
            composable(Screen.Settings.route,
                enterTransition = tabEnter, exitTransition = tabExit,
                popEnterTransition = tabEnter, popExitTransition = tabExit
            ) { SettingsScreen(viewModel, onServerConfigChanged, navController) }
            composable("detail/device",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { DeviceDetailScreen(viewModel, navController) }
            composable("detail/system",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { SystemDetailScreen(viewModel, navController) }
            composable("detail/battery",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { BatteryDetailScreen(viewModel, navController) }
            composable("detail/network",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { NetworkDetailScreen(viewModel, navController) }
            composable("detail/traffic",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { TrafficDetailScreen(viewModel, navController) }
            composable("detail/server-config",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { ServerConfigScreen(viewModel, onServerConfigChanged, navController) }
            composable("detail/tools-advanced",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { AdvancedConsoleScreen(viewModel, navController) }
            composable("detail/speed-test",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { SpeedTestScreen(viewModel, navController) }
            composable("detail/traffic-management",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { TrafficManagementScreen(viewModel, navController) }
            composable("detail/sms",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { SmsScreen(viewModel, navController) }
            composable("detail/apps",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) {
                val ctx = LocalContext.current; val prefs = remember { AppPreferences(ctx) }
                AppManagerScreen(viewModel, prefs, navController)
            }
            composable("detail/adb",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { AdbScreen(viewModel, navController) }
            composable("detail/tasks",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { TaskScreen(viewModel, navController) }
            composable("detail/sms-forward",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { SmsForwardScreen(viewModel, navController) }
            composable("detail/files",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { FileManagerScreen(viewModel, navController) }
            composable("detail/debug-log",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { DebugLogScreen(viewModel, navController) }
            composable("detail/advanced",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { AdvancedScreen(viewModel, navController) }
            composable("detail/monitor",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { MonitorScreen(viewModel, navController) }
            composable("detail/downloads",
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { DownloadScreen(viewModel, navController) }

            // File viewer routes
            composable(
                route = "file/editor?path={path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path") ?: ""
                TextEditorScreen(viewModel, navController, java.net.URLDecoder.decode(path, "UTF-8"))
            }
            composable(
                route = "file/media?path={path}&type={type}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType; defaultValue = "" },
                    navArgument("type") { type = NavType.StringType; defaultValue = "video" }
                ),
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path") ?: ""
                val type = backStackEntry.arguments?.getString("type") ?: "video"
                MediaScreen(viewModel, navController, java.net.URLDecoder.decode(path, "UTF-8"), type)
            }
            composable(
                route = "file/image?path={path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                enterTransition = detailEnter, exitTransition = detailExit,
                popEnterTransition = detailPopEnter, popExitTransition = detailPopExit
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path") ?: ""
                ImageViewerScreen(viewModel, navController, java.net.URLDecoder.decode(path, "UTF-8"))
            }
        }
    }
}

@Composable
fun BottomNavBar(navController: NavHostController) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val show = currentRoute in bottomNavRoutes
    if (!show) return
    val palette = LocalResolvedPalette.current
    Column {
        // Subtle top border
        HorizontalDivider(
            thickness = 1.dp,
            color = palette.divider.copy(alpha = 0.15f)
        )
        NavigationBar(
            containerColor = palette.cardBg,
            tonalElevation = 0.dp,
            modifier = Modifier.clip(
                RoundedCornerShape(
                    topStart = Spacing.NavBarCornerRadius,
                    topEnd = Spacing.NavBarCornerRadius
                )
            )
        ) {
            bottomNavItems.forEach { screen ->
                val selected = currentRoute == screen.route
                NavigationBarItem(
                    icon = {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = screen.title,
                            tint = if (selected) palette.accent
                                   else palette.textSecondary
                        )
                    },
                    label = {
                        Text(
                            text = screen.title,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) palette.accent
                                    else palette.textSecondary
                        )
                    },
                    selected = selected,
                    onClick = {
                        if (currentRoute != screen.route) {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        }
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = palette.navIndicator
                    )
                )
            }
        }
    }
}