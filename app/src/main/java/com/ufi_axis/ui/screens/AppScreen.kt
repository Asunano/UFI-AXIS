package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerScreen(viewModel: MainViewModel, prefs: Any? = null, navController: NavHostController) {
    val state by viewModel.appManageState.collectAsState()
    var showInstallDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.filter) { viewModel.loadAppList(state.filter) }

    UfiScreenScaffold(title = "应用管理", navController = navController, showBack = true,
        actions = {
            if (state.hasRoot) Icon(Icons.Default.VerifiedUser, null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { viewModel.loadAppList(state.filter) }) { Icon(Icons.Default.Refresh, null) }
            IconButton(onClick = { showInstallDialog = true }) { Icon(Icons.Default.Add, null) }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            UfiSingleChipSelector(
                options = listOf("user" to "用户", "system" to "系统", "all" to "全部"),
                selectedValue = state.filter,
                onSelect = { viewModel.loadAppList(it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (showInstallDialog) {
                InstallAppBottomSheet(
                    isLoading = state.installLoading,
                    onDismiss = { showInstallDialog = false },
                    onInstallUrl = { viewModel.installAppFromUrl(it); showInstallDialog = false },
                    onInstallPath = { viewModel.installAppFromPath(it); showInstallDialog = false }
                )
            }

            state.selectedApp?.let { detail ->
                AppDetailBottomSheet(detail,
                    onDismiss = { viewModel.dismissAppDetail() },
                    onAction = { viewModel.performAppAction(it, detail.packageName) })
            }

            state.errorMessage?.let { UfiErrorBanner(message = it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (state.apps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    UfiEmptyState(icon = Icons.Default.Android, message = "未找到应用")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.apps, key = { it.packageName }) { app ->
                        AppListItem(app = app, onClick = { viewModel.loadAppDetail(app.packageName) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallAppBottomSheet(isLoading: Boolean, onDismiss: () -> Unit, onInstallUrl: (String) -> Unit, onInstallPath: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var useUrl by remember { mutableStateOf(true) }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = Spacing.CardCorner, topEnd = Spacing.CardCorner)) {
        Column(Modifier.padding(Spacing.InnerPadding)) {
            Text("安装应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("来源: "); Spacer(Modifier.width(8.dp))
                FilterChip(selected = useUrl, onClick = { useUrl = true }, label = { Text("URL") })
                Spacer(Modifier.width(4.dp))
                FilterChip(selected = !useUrl, onClick = { useUrl = false }, label = { Text("本地路径") })
            }
            Spacer(Modifier.height(8.dp))
            if (useUrl) {
                UfiTextField(value = url, onValueChange = { url = it }, label = "APK 下载 URL", placeholder = "https://example.com/app.apk")
            } else {
                UfiTextField(value = path, onValueChange = { path = it }, label = "APK 路径", placeholder = "/data/local/tmp/app.apk")
            }
            UfiLinearLoading(isLoading = isLoading)
            Spacer(Modifier.height(12.dp))
            UfiButtonRow {
                UfiSecondaryButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                UfiPrimaryButton(text = "安装", onClick = { if (useUrl) onInstallUrl(url) else onInstallPath(path) },
                    enabled = ((useUrl && url.isNotBlank()) || (!useUrl && path.isNotBlank())) && !isLoading, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

@Composable
private fun AppListItem(app: AppItem, onClick: () -> Unit) {
    val statusColor = when {
        app.isFrozen -> MaterialTheme.colorScheme.tertiary
        !app.isEnabled -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val statusText = when {
        app.isFrozen -> "冻结"
        !app.isEnabled -> "禁用"
        else -> "启用"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(Spacing.CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Android, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(app.packageName.split(".").lastOrNull() ?: app.packageName,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (app.versionName.isNotBlank()) "v${app.versionName}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = statusColor.copy(alpha = 0.12f)) {
                        Text(" $statusText ", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = statusColor)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailBottomSheet(detail: AppDetailResponse, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = Spacing.CardCorner, topEnd = Spacing.CardCorner)) {
        Column(Modifier.padding(Spacing.InnerPadding)) {
            Text(detail.packageName.split(".").lastOrNull() ?: detail.packageName,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("版本: ${detail.versionName} (${detail.versionCode})", style = MaterialTheme.typography.bodyMedium)
            Text("路径: ${detail.apkPath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("状态: ${if (detail.isEnabled) "已启用" else "已禁用"} | ${if (detail.isSystem) "系统应用" else "用户应用"}",
                style = MaterialTheme.typography.bodySmall)
            detail.installer.takeIf { it.isNotBlank() }?.let { Text("来源: $it", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            UfiDivider()
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                val actions = listOf("enable" to "启用", "disable" to "禁用", "freeze" to "冻结", "clear" to "清数据", "force-stop" to "强停", "uninstall" to "卸载")
                actions.forEach { (key, label) ->
                    OutlinedButton(onClick = { onAction(key) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("禁用 = pm disable \u00B7 冻结 = pm disable-user",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}