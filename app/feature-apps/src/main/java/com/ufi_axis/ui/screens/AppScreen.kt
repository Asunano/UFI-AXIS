package com.ufi_axis.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerScreen(viewModel: MainViewModel, prefs: Any? = null, navController: NavHostController) {
    val state by viewModel.appManageState.collectAsState()
    var showInstallDialog by remember { mutableStateOf(false) }
    var cachedDetail by remember { mutableStateOf<AppDetailResponse?>(null) }
    // 「授予全部权限」是一次性动作：结果只弹 Toast，不进 AppManageState（免得错误挂成常驻横幅）
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var grantAllRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.filter) { viewModel.apps.loadAppList(state.filter) }
    LaunchedEffect(state.selectedApp) { if (state.selectedApp != null) cachedDetail = state.selectedApp }

    // 筛选页签（复用公共 UfiScrollableTabRow，与下载管理「全部/未完成/已完成」同款胶囊滑块）
    val filterValues = listOf("user", "system", "all")
    val filterTabs = listOf("用户", "系统", "全部")
    val filterIndex = filterValues.indexOf(state.filter).coerceAtLeast(0)

    UfiScreenScaffold(title = "应用管理", navController = navController, showBack = true,
        actions = {
            if (state.hasRoot) Icon(Icons.Default.VerifiedUser, null, Modifier.padding(end = 8.dp), tint = LocalResolvedPalette.current.accent)
            IconButton(onClick = { viewModel.apps.loadAppList(state.filter) }) { Icon(Icons.Default.Refresh, null) }
            IconButton(onClick = { showInstallDialog = true }) { Icon(Icons.Default.Add, null) }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            UfiScrollableTabRow(
                selectedTabIndex = filterIndex,
                onTabSelected = { viewModel.apps.loadAppList(filterValues[it]) },
                tabs = filterTabs,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            InstallAppDialog(
                visible = showInstallDialog,
                isLoading = state.installLoading,
                onDismiss = { showInstallDialog = false },
                onInstallUrl = { viewModel.apps.installAppFromUrl(it); showInstallDialog = false },
                onInstallPath = { viewModel.apps.installAppFromPath(it); showInstallDialog = false }
            )

            AppDetailDialog(
                detail = cachedDetail,
                visible = state.selectedApp != null,
                hasRoot = state.hasRoot,
                // v18：从 list 缓存查 iconBase64（避免响应体带 4-5KB base64 拖慢响应）
                iconBase64 = state.apps.find { it.packageName == cachedDetail?.packageName }?.iconBase64.orEmpty(),
                grantAllRunning = grantAllRunning,
                onDismiss = { viewModel.apps.dismissAppDetail() },
                onAction = { viewModel.apps.performAppAction(it, cachedDetail?.packageName.orEmpty()) },
                onGrantAllPermissions = {
                    val pkg = cachedDetail?.packageName.orEmpty()
                    if (pkg.isNotEmpty()) {
                        scope.launch {
                            grantAllRunning = true
                            val (ok, msg) = viewModel.apps.grantAllPermissions(pkg)
                            grantAllRunning = false
                            // 成功时 core 的 message 是「已授予 N 项权限」这类人话，直接回显；
                            // 失败时它是 shell 原始报错，所以自己兜一句前缀再附上原文。
                            toastMessage = if (ok) ToastMessage(msg.ifBlank { "已授予全部权限" }, ToastType.SUCCESS)
                            else ToastMessage("授予失败：$msg", ToastType.ERROR)
                        }
                    }
                }
            )

            state.errorMessage?.let { UfiErrorBanner(message = it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

            if (state.isLoading) {
                // 2026-09-03：应用列表首次加载从居中转圈改为骨架屏。
                // 这一支会把整块内容区（搜索/筛选栏以下全部）替换掉，加载时页面是全空的；
                // 设备上装了几百个包，`pm list` + 逐包取 label/图标要等好几秒，
                // 期间一个转圈什么都不预示。骨架屏画出「图标 + 应用名 + 包名」的行版式，
                // 用户第一帧就知道接下来是一份列表。列表内的单项动作（安装/卸载/授权）仍用转圈。
                // cardCount = 6 ≈ 首屏可见行数；showAvatar 对齐每行左侧应用图标；
                // linesPerCard = 2 对齐「应用名 + 包名」两行文本。
                UfiSkeletonGroup(
                    modifier = Modifier.padding(top = Spacing.Medium),
                    cardCount = 6,
                    linesPerCard = 2,
                    showAvatar = true
                )
            } else if (state.apps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    UfiEmptyState(icon = Icons.Default.Android, message = "未找到应用")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.apps, key = { it.packageName }) { app ->
                        AppListItem(app = app, onClick = { viewModel.apps.loadAppDetail(app.packageName) })
                    }
                }
            }
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallAppDialog(
    visible: Boolean,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onInstallUrl: (String) -> Unit,
    onInstallPath: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var useUrl by remember { mutableStateOf(true) }

    UfiScrollableDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "安装应用",
        showCloseButton = false,
        confirmButton = {
            UfiButton(
                text = "安装",
                enabled = !isLoading && ((useUrl && url.isNotBlank()) || (!useUrl && path.isNotBlank())),
                onClick = {
                    if (useUrl) onInstallUrl(url) else onInstallPath(path)
                }
            )
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            // 来源选择（v15：UfiScrollableTabRow 滑块，与 AppManagerScreen 顶部筛选同款）
            val sourceValues = listOf("url", "path")
            val sourceTabs = listOf("URL", "本地路径")
            val sourceIndex = sourceValues.indexOf(if (useUrl) "url" else "path").coerceAtLeast(0)
            Text(
                text = "来源",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalResolvedPalette.current.textPrimary
            )
            Spacer(Modifier.height(6.dp))
            UfiScrollableTabRow(
                selectedTabIndex = sourceIndex,
                onTabSelected = { useUrl = sourceValues[it] == "url" },
                tabs = sourceTabs
            )
            Spacer(Modifier.height(12.dp))
            if (useUrl) {
                UfiDialogTextField(
                    label = "APK 下载 URL",
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "https://example.com/app.apk"
                )
            } else {
                UfiDialogTextField(
                    label = "APK 路径",
                    value = path,
                    onValueChange = { path = it },
                    placeholder = "/data/local/tmp/app.apk"
                )
            }
            UfiLinearLoading(isLoading = isLoading)
        }
    }
}

@Composable
private fun AppListItem(app: AppItem, onClick: () -> Unit) {
    val palette = LocalResolvedPalette.current
    val statusColor = when {
        app.isFrozen -> palette.accentSecondary
        !app.isEnabled -> palette.error
        else -> palette.accent
    }
    val statusText = when {
        app.isFrozen -> "冻结"
        !app.isEnabled -> "禁用"
        else -> "运行中"
    }
    val appIcon = remember(app.iconBase64) { decodeBase64Icon(app.iconBase64) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .ufiCardShadow(elevation = 4.dp, shape = UfiCardDefaults.widgetShape)
            .clip(UfiCardDefaults.widgetShape)
            .background(palette.cardBg, UfiCardDefaults.widgetShape)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = palette.accent.copy(alpha = 0.1f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Android, null, Modifier.size(24.dp), tint = palette.accent)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(app.packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: app.packageName,
                    style = UfiTextStyles.bodyLeadStrong)
                Spacer(Modifier.height(2.dp))
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Surface(shape = UfiCardDefaults.chipShape, color = statusColor.copy(alpha = 0.12f)) {
                    Text(" $statusText ", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = UfiTextStyles.captionStrong, color = statusColor)
                }
                if (app.versionName.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("v${app.versionName}", style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailDialog(
    detail: AppDetailResponse?,
    visible: Boolean = true,
    hasRoot: Boolean = false,
    iconBase64: String = "", // v18：从 list 缓存复用（避免响应体 4-5KB base64）
    /** 「授予全部权限」是否正在跑（按钮置灰用）。真实调用与结果提示由调用方负责。 */
    grantAllRunning: Boolean = false,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    onGrantAllPermissions: () -> Unit = {}
) {
    // 危险操作（卸载/禁用/清数据/强停）需二次确认；安全操作（启用/冻结）直接执行（UID-004）。
    if (detail == null) return
    var pendingAction by remember { mutableStateOf<String?>(null) }
    // 「授予全部权限」自己一套确认态：它不属于 pendingAction 那组（文案与风险等级都不同）
    var showGrantAllConfirm by remember { mutableStateOf(false) }
    // v18：icon 优先用外部传入的 list 缓存 iconBase64，detail 自带的作为 fallback（兼容老逻辑）
    val effectiveIcon = iconBase64.ifBlank { detail.iconBase64 }
    val detailIcon = remember(effectiveIcon) { decodeBase64Icon(effectiveIcon) }
    val palette = LocalResolvedPalette.current
    UfiScrollableDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = null, // v16：去掉弹窗标题，把"应用名"放进头部 Row 显眼展示
        showCloseButton = false,
        // v17：去掉全宽"关闭"按钮（突兀），改用头部 Row 右上角 X（IconButton）
        dismissButton = null
    ) {
        UfiDialogBody {
            // 头部：应用图标 + 应用名（label/titleLarge） + 完整包名（labelSmall 灰字）+ 右上角 X 关闭
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (detailIcon != null) {
                    Image(
                        bitmap = detailIcon,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(UfiCardDefaults.mediumShape)
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    // v16：应用名（applicationLabel），如"GMS" / "Google Play 服务"。空时回退包名段
                    val displayName = detail.applicationLabel.ifBlank {
                        detail.packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: detail.packageName
                    }
                    Text(
                        text = displayName,
                        style = UfiTextStyles.screenTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    // 完整包名（小字灰）
                    Text(
                        text = detail.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    detail.installer.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "来源: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // v17：右上角 X 关闭（替代突兀的全宽"关闭"按钮）；v18：主题 accent 着色
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = palette.accent
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            // 版本：v15 优化 — versionName 为空时仅显示 versionCode
            val versionLabel = if (detail.versionName.isBlank()) "${detail.versionCode}" else "${detail.versionName} (${detail.versionCode})"
            UfiDialogInfoRow("版本", versionLabel)
            // 路径：v16 优化 — AnnotatedString 高亮包名段（前缀灰 + 包名深色加粗 + 后缀灰），不再"挤成一团"
            HighlightedPathInfoRow("路径", detail.apkPath, detail.packageName, palette)
            UfiDialogInfoRow("状态", "${if (detail.isEnabled) "已启用" else "已禁用"} | ${if (detail.isSystem) "系统应用" else "用户应用"}")
            UfiDivider()
            // 操作按钮 2×3 网格（v16 优化：高度 48dp、间距 8dp、图标 18dp、危险按钮 error 色描边）
            val actions = listOf(
                ActionSpec("enable", "启用", Icons.Filled.CheckCircle, false),
                ActionSpec("disable", "禁用", Icons.Filled.Block, true),
                ActionSpec("freeze", "冻结", Icons.Filled.AcUnit, false),
                ActionSpec("clear", "清数据", Icons.Filled.DeleteSweep, true),
                ActionSpec("force-stop", "强停", Icons.Filled.StopCircle, true),
                ActionSpec("uninstall", "卸载", Icons.Filled.Delete, true)
            )
            val dangerousKeys = remember { setOf("disable", "clear", "force-stop", "uninstall") }
            actions.chunked(3).forEach { rowActions ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowActions.forEach { spec ->
                        val borderColor = if (spec.dangerous) palette.error else palette.textSecondary.copy(alpha = 0.4f)
                        val iconTint = if (spec.dangerous) palette.error else palette.textPrimary
                        OutlinedButton(
                            onClick = {
                                if (spec.key in dangerousKeys) pendingAction = spec.key else onAction(spec.key)
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = UfiCardDefaults.dialogShape,
                            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Icon(spec.icon, null, Modifier.size(18.dp), tint = iconTint)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                spec.label,
                                style = UfiTextStyles.tag.copy(fontWeight = UfiWeight.Emphasis),
                                color = iconTint
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // v18：底部说明居中 + 与弹窗边框保持上下间隔（之前贴边偏左）
            Text("禁用 = pm disable \u00B7 冻结 = pm disable-user",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp))

            // ── 授予全部权限（2026-08-30，POST /api/apps/grant-all-permissions） ──
            // 不放进上面的 2×3 网格：那六个都是「装/停/删」这类可逆或用户熟悉的动作，
            // 而这一个是一次性把定位/短信/通话记录等全部打开且**不可撤销**，必须单独、显眼、标红。
            UfiDivider()
            UfiButton(
                variant = UfiButtonVariant.Danger,
                text = "授予全部权限",
                onClick = { showGrantAllConfirm = true },
                enabled = hasRoot && !grantAllRunning,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (hasRoot) "高风险：一次授予该应用声明的所有运行时权限，无法批量撤销。"
                else "需要特权 shell（Root）才能授予权限。",
                style = MaterialTheme.typography.labelSmall,
                color = if (hasRoot) palette.error
                        else palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)
            )

            // ── 危险操作二次确认弹窗（卸载/禁用/清数据/强停，UID-004） ──
            val packageName = detail.packageName
            val isSystemApp = detail.isSystem

            if (showGrantAllConfirm) {
                UfiConfirmDialog(
                    title = "授予全部权限",
                    text = buildString {
                        append("将一次性授予 $packageName 声明的全部运行时权限，")
                        append("可能包含定位、短信、通话记录、存储等敏感权限。\n\n")
                        append("此操作没有「一键撤销」，之后只能逐条手动收回。")
                        if (isSystemApp) append("\n\n⚠ 该应用为系统应用，放开全部权限的影响面更大。")
                    },
                    confirmText = "确认授予",
                    dismissText = "取消",
                    destructive = true,
                    onConfirm = { showGrantAllConfirm = false; onGrantAllPermissions() },
                    onDismiss = { showGrantAllConfirm = false }
                )
            }

            pendingAction?.let { actionKey ->
                val actionLabel = actions.firstOrNull { it.key == actionKey }?.label ?: actionKey
                val riskText = buildString {
                    append("确定要对 $packageName 执行「$actionLabel」吗？\n")
                    when (actionKey) {
                        "uninstall" -> append("应用将被卸载，数据不可恢复。")
                        "clear" -> append("应用数据将被清除。")
                        "disable" -> append("应用将被禁用，需重新启用后方可使用。")
                        "force-stop" -> append("应用将被强制停止。")
                    }
                    if (isSystemApp && hasRoot) {
                        append("\n⚠ 该应用为系统应用且当前处于 Root 模式，操作可能导致设备功能异常。")
                    }
                }
                UfiConfirmDialog(
                    title = "确认$actionLabel",
                    text = riskText,
                    confirmText = "确认$actionLabel",
                    dismissText = "取消",
                    destructive = true,
                    onConfirm = { onAction(actionKey); pendingAction = null },
                    onDismiss = { pendingAction = null }
                )
            }
        }
    }
}
/** base64（PNG）→ Compose ImageBitmap；空/非法返回 null（前端显示 fallback 默认图标）。 */
private fun decodeBase64Icon(base64: String): ImageBitmap? = try {
    if (base64.isBlank()) null
    else {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
} catch (e: Exception) {
    null
}

/** v16：应用详情弹窗中的"操作按钮"规格（图标 + 文字 + 是否危险）。 */
private data class ActionSpec(
    val key: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val dangerous: Boolean
)

/**
 * v16：路径行 — 自动高亮包名段（前缀灰 + 包名深色加粗 + 后缀灰），单行 maxLines + Ellipsis，
 * 避免长路径"挤成一团"的问题。
 */
@Composable
private fun HighlightedPathInfoRow(label: String, path: String, pkgName: String, palette: com.ufi_axis.ui.theme.ResolvedPalette) {
    val dim = palette.textSecondary
    val bright = palette.textPrimary
    val annotated = buildAnnotatedString {
        val idx = path.indexOf(pkgName)
        if (idx >= 0) {
            withStyle(SpanStyle(color = dim)) { append(path.substring(0, idx)) }
            withStyle(SpanStyle(color = bright, fontWeight = UfiWeight.Strong)) { append(pkgName) }
            val tail = path.substring(idx + pkgName.length)
            withStyle(SpanStyle(color = dim)) { append(tail) }
        } else {
            withStyle(SpanStyle(color = bright)) { append(path) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
        Spacer(Modifier.height(2.dp))
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
