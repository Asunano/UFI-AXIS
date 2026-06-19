package com.ufi_axis.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ufi_axis.ui.screens.*
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

@Composable
fun MainNavGraph(viewModel: MainViewModel, onServerConfigChanged: () -> Unit) {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { BottomNavBar(navController) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(viewModel, navController) }
            composable(Screen.Network.route) { NetworkScreen(viewModel, navController) }
            composable(Screen.Tools.route) { ToolsScreen(viewModel, navController) }
            composable(Screen.Settings.route) { SettingsScreen(viewModel, onServerConfigChanged, navController) }
            composable("detail/device") { DeviceDetailScreen(viewModel, navController) }
            composable("detail/system") { SystemDetailScreen(viewModel, navController) }
            composable("detail/battery") { BatteryDetailScreen(viewModel, navController) }
            composable("detail/network") { NetworkDetailScreen(viewModel, navController) }
            composable("detail/traffic") { TrafficDetailScreen(viewModel, navController) }
            composable("detail/traffic-management") { TrafficManagementScreen(viewModel, navController) }
            composable("detail/sms") { SmsScreen(viewModel, navController) }
            composable("detail/apps") {
                val ctx = LocalContext.current; val prefs = remember { AppPreferences(ctx) }
                AppManagerScreen(viewModel, prefs, navController)
            }
            composable("detail/adb") { AdbScreen(viewModel, navController) }
            composable("detail/tasks") { TaskScreen(viewModel, navController) }
            composable("detail/sms-forward") { SmsForwardScreen(viewModel, navController) }
            composable("detail/files") { FileManagerScreen(viewModel, navController) }
            composable("detail/debug-log") { DebugLogScreen(viewModel, navController) }
            composable("detail/advanced") { AdvancedScreen(viewModel, navController) }
            composable("detail/monitor") { MonitorScreen(viewModel, navController) }
            composable("detail/cell-lock") { CellLockScreen(viewModel, navController) }
            composable("detail/downloads") { DownloadScreen(viewModel, navController) }

            // File viewer routes
            composable(
                route = "file/editor?path={path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path") ?: ""
                TextEditorScreen(viewModel, navController, java.net.URLDecoder.decode(path, "UTF-8"))
            }
            composable(
                route = "file/media?path={path}&type={type}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType; defaultValue = "" },
                    navArgument("type") { type = NavType.StringType; defaultValue = "video" }
                )
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path") ?: ""
                val type = backStackEntry.arguments?.getString("type") ?: "video"
                MediaScreen(viewModel, navController, java.net.URLDecoder.decode(path, "UTF-8"), type)
            }
            composable(
                route = "file/image?path={path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })
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
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        bottomNavItems.forEach { screen ->
            val selected = currentRoute == screen.route
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = if (selected) screen.icon else screen.icon,
                        contentDescription = screen.title,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                label = {
                    Text(
                        text = screen.title,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
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
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            )
        }
    }
}