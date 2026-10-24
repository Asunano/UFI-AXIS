package com.ufi_axis.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.ufi_axis.viewmodel.module.APP_LIST_FRESH_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerScreen(viewModel: MainViewModel, prefs: Any? = null, navController: NavHostController) {
    val state by viewModel.appManageState.collectAsState()
    var showInstallDialog by remember { mutableStateOf(false) }
    var cachedDetail by remember { mutableStateOf<AppDetailResponse?>(null) }
    // 「授予全部权限」是一次性动作：结果只弹 Toast，不进 AppManageState（免得错误挂成常驻横幅）
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    // 静默轮询上线后（见下方轮询的说明），同一句失败文案会每隔一轮重新落到 errorMessage 上
    // （Module 每次 load 先把 errorMessage 置 null）。记住"上一次已经提示过的文案"去重，
    // 免得设备一直连不上时每 30s 弹一次同样的 Toast —— 只提示第一次，内容变了再提示。
    var lastErrorToast by remember { mutableStateOf<String?>(null) }
    var grantAllRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 进页面：force = false —— 30s 新鲜窗口（APP_LIST_FRESH_MS）内复用内存里那份列表，一个请求都不发。
    // 首次进入 / 超出窗口时照旧真打一次，而 core 侧自己有 5 分钟进程内缓存
    // （AppManager.listApps），所以那次"真打"在 core 上也是毫秒级而不是 4 条特权 shell。
    LaunchedEffect(state.filter) { viewModel.apps.loadAppList(state.filter) }
    LaunchedEffect(state.selectedApp) { if (state.selectedApp != null) cachedDetail = state.selectedApp }

    // ── 静默轮询（2026-10-21）────────────────────────────────────────────────
    // 顶栏刷新按钮删除后，本页只剩「进页面 30s 新鲜窗口 + 切页签 force + 写后回读」三条出口，
    // 别处装 / 卸 / 冻结的应用不会自己冒出来（这一页也没有 WS 推送），所以补一条静默轮询兜住。
    //
    // 为什么不用 `force = true`：force 绕过的是**客户端**那道 30s 新鲜窗口，语义是
    // "用户显式动作必须立刻真打一次"。轮询是自动路径，绕过窗口等于每轮白付一次 core 的
    // `listApps`（4 条特权 shell，设备上是秒级）；非 force 路径在窗口内直接 return，
    // 命中时一个请求都不发 —— 这也是 core 那 5 分钟缓存之外的第二层省。
    //
    // 为什么轮询不会闪骨架（关键依据，别改成 force / 别顺手把 isLoading 写回去）：
    // `AppManagerModule.loadAppList` 只有在 `loadedFilter != filter` 时才写 `isLoading = true`，
    // 轮询带的是**当前已加载的那个 filter**，于是 isLoading 恒不被翻，
    // 内容区不会换成 UfiSkeletonGroup —— 列表子树、LazyListState、renderLimit 全部原地保留。
    // 更彻底的是：`errorMessage = null` + `filter` 未变时那次 copy 与原状态结构相等，
    // StateFlow 根本不发射，连重组都没有。
    //
    // 可见性判据与短信页 / 下载页同款：只在 ON_RESUME 期间跑（切后台立刻停，回前台重新起）。
    // 本页是 DETAIL 路由，退出即整棵组合销毁、LaunchedEffect 自动取消，故不必再叠页面前台判据。
    var lifecycleResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleResumed = false
        }
    }
    LaunchedEffect(lifecycleResumed) {
        if (!lifecycleResumed) return@LaunchedEffect
        while (true) {
            delay(APP_LIST_POLL_MS)
            // 每轮现读 state.filter：用户切了页签就跟着刷当前那份列表。
            // 刻意**不**把 filter 放进 LaunchedEffect 的 key —— 切页签本身已经 force 拉过一次，
            // 再重置定时器只会让"切来切去"永远等不到下一轮。
            viewModel.apps.loadAppList(state.filter)
        }
    }

    // 筛选页签（复用公共 UfiScrollableTabRow，与下载管理「全部/未完成/已完成」同款胶囊滑块）
    val filterValues = listOf("user", "system", "all")
    val filterTabs = listOf("用户", "系统", "全部")
    val filterIndex = filterValues.indexOf(state.filter).coerceAtLeast(0)

    UfiScreenScaffold(title = "应用管理", navController = navController, showBack = true,
        actions = {
            // 2026-10-21（用户要求）顶栏两处删除：
            // ① 刷新左边的 Root 徽标（`Icons.Default.VerifiedUser`，由 `state.hasRoot` 控制）——
            //    它只说明"当前有特权 shell"，而这已经从「授予全部权限」按钮的可点状态上体现，
            //    留在顶栏是废弃的重复标识；
            // ② 刷新按钮本身 —— 列表在进页面时（`LaunchedEffect(state.filter)`，带 30s 新鲜窗口）
            //    与切页签时都会真拉，安装/卸载后也有写后回读，手工重拉没有额外价值。
            // `state.hasRoot` 仍被详情弹窗使用（见下方 AppDetailDialog），不要一并删掉。
            IconButton(onClick = { showInstallDialog = true }) { Icon(Icons.Default.Add, null) }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            UfiScrollableTabRow(
                selectedTabIndex = filterIndex,
                // 页签切换必须真拉（force = true）：内存里那份是**另一个 filter** 的，
                // 闸门在 AppManagerModule 里已按 loadedFilter 放行，这里 force 只是把语义写在调用点。
                onTabSelected = { viewModel.apps.loadAppList(filterValues[it], force = true) },
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

            // 2026-10-19：应用管理的错误提示改用 **Toast**（用户要求），不再用常驻错误横幅。
            // 横幅的问题：它占一行、不会自己消失，而"卸载失败/安装失败"是一次性反馈。
            LaunchedEffect(state.errorMessage) {
                val msg = state.errorMessage ?: return@LaunchedEffect
                if (msg == lastErrorToast) return@LaunchedEffect
                lastErrorToast = msg
                toastMessage = ToastMessage(msg, ToastType.ERROR)
            }

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
                // ── 懒加载 = 渐进渲染（不是真分页）────────────────────────────────────
                // 依据：core 的 `/api/apps/list`（AppManager.listApps）按 filter 一次性回全量，
                // 没有 offset/limit；app 侧 `getAppList(filter)` 同样没有分页参数。要做真分页得改
                // 服务端契约，本次不动 core，故与下载管理 / 短信同款：客户端分批挂载同一份数据。
                // 本页尤其需要：设备上动辄两三百个包，每行还要解一次 base64 图标，
                // 首帧少挂一批行就等于少解一批图标。
                var renderLimit by remember(state.filter) { mutableStateOf(APP_RENDER_PAGE) }
                val renderedApps = state.apps.take(renderLimit)
                val listState = rememberLazyListState()
                AppNearEndEffect(listState, renderedApps.size) {
                    renderLimit = (renderLimit + APP_RENDER_PAGE).coerceAtMost(state.apps.size)
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(renderedApps, key = { it.packageName }) { app ->
                        AppListItem(app = app, onClick = { viewModel.apps.loadAppDetail(app.packageName) })
                    }
                }
            }
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }
}

/**
 * 渐进渲染批大小。取 30（与短信页一致）：应用行是「48dp 图标 + 两行文字」的中等高度，
 * 30 行已经超过两屏，再多只是让首帧多解一批 base64 图标。
 */
private const val APP_RENDER_PAGE = 30

/** 距尾部还剩几条就追加下一批（提前量，滚到底才追加会看到停顿）。 */
private const val APP_RENDER_LOAD_AHEAD = 5

/**
 * 应用列表静默轮询间隔（ms）。
 *
 * 取「新鲜窗口 + 余量」而不是裸 30s，理由很具体：`loadAppList` 的非 force 路径在
 * [APP_LIST_FRESH_MS] 窗口内直接 return，而窗口的起点是**请求成功那一刻**，
 * 比本轮 tick 的起点晚几十毫秒到几秒（core 侧一次 `listApps` 要串 4 条特权 shell）。
 * 若间隔恰好等于窗口，每个 tick 看到的 age 都是「30s − 那段延迟」< 30s，
 * 全被闸门吃掉 ⇒ 实际刷新节奏退化成两轮一次（≈60s）。
 * 留 5s 余量后节奏稳定在 ~30s，同时仍是**非 force** 调用：闸门与 core 的 5 分钟缓存都照旧生效。
 */
private const val APP_LIST_POLL_MS = APP_LIST_FRESH_MS + 5_000L

/**
 * 列表滚到接近末尾就回调一次 —— 与 `:app:feature-media` 的 `MediaNearEndEffect` 同构。
 *
 * 为什么不直接复用那个公共件：本模块（`:app:feature-apps`）依赖不到 `:app:feature-media`，
 * 为十几行工具函数新增一条跨模块依赖不划算，所以在模块内放一份同构实现。
 * 判定包在 `derivedStateOf` 里：`visibleItemsInfo` 每帧都在变，直接在组合里读会导致
 * 每滚一像素就重组一次；这里只有越过阈值那一次布尔翻转会触发 [LaunchedEffect]。
 */
@Composable
private fun AppNearEndEffect(
    listState: LazyListState,
    itemCount: Int,
    onNearEnd: () -> Unit
) {
    val nearEnd by remember(itemCount) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            itemCount > 0 && last >= itemCount - APP_RENDER_LOAD_AHEAD
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) onNearEnd() }
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
            // 关闭动作交给 shell 排时序：离场 backdrop 要播完才卸载窗口，见 LocalUfiDialogClose。
            // local 必须在弹窗自己的 slot 内部读，在弹窗外面读会拿到"直接执行"的默认实现。
            val close = LocalUfiDialogClose.current
            UfiButton(
                text = "安装",
                enabled = !isLoading && ((useUrl && url.isNotBlank()) || (!useUrl && path.isNotBlank())),
                onClick = {
                    close { if (useUrl) onInstallUrl(url) else onInstallPath(path) }
                }
            )
        },
        dismissButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { close(onDismiss) })
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
            // 间距统一到 UfiDialogBody（12dp）
            UfiScrollableTabRow(
                selectedTabIndex = sourceIndex,
                onTabSelected = { useUrl = sourceValues[it] == "url" },
                tabs = sourceTabs
            )
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
    // 2026-10-22：确认框改走公共二次确认设施（UfiDialogSuspendState + UfiConfirmHost）。
    // 本页两个确认都是**从详情弹窗内部**触发的，父弹窗必须先在确认框出现时收起 ——
    // 否则两个 Dialog 窗口叠着开：backdrop 丢模糊 / 闪烁 / 确认框被前一个窗口带走。
    val confirmSuspend = rememberUfiDialogSuspendState()
    var confirmRequest by remember { mutableStateOf<UfiConfirmRequest?>(null) }
    // v18：icon 优先用外部传入的 list 缓存 iconBase64，detail 自带的作为 fallback（兼容老逻辑）
    val effectiveIcon = iconBase64.ifBlank { detail.iconBase64 }
    val detailIcon = remember(effectiveIcon) { decodeBase64Icon(effectiveIcon) }
    val palette = LocalResolvedPalette.current
    UfiScrollableDialog(
        visible = confirmSuspend.onScreen(visible),
        onDismiss = {
            if (confirmSuspend.consumeProgrammaticDismiss()) return@UfiScrollableDialog
            onDismiss()
        },
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
                // 关闭动作交给 shell 排时序：离场 backdrop 要播完才卸载窗口，见 LocalUfiDialogClose。
                val closeDetail = LocalUfiDialogClose.current
                IconButton(onClick = { closeDetail(onDismiss) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = palette.accent
                    )
                }
            }
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
            // 确认文案需要的两个事实：原先在确认块里声明，确认块改走宿主后仍要用，提前取出。
            val packageName = detail.packageName
            val isSystemApp = detail.isSystem

            fun showDangerConfirm(actionKey: String, actionLabel: String) {
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
                confirmSuspend.suspend()
                confirmRequest = UfiConfirmRequest(
                    title = "确认$actionLabel",
                    text = riskText,
                    confirmText = "确认$actionLabel",
                    destructive = true,
                    onConfirm = {
                        confirmRequest = null
                        confirmSuspend.resume()
                        onAction(actionKey)
                    },
                    onDismiss = {
                        confirmRequest = null
                        confirmSuspend.resume()
                    }
                )
            }
            actions.chunked(3).forEach { rowActions ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowActions.forEach { spec ->
                        val borderColor = if (spec.dangerous) palette.error else palette.textSecondary.copy(alpha = 0.4f)
                        val iconTint = if (spec.dangerous) palette.error else palette.textPrimary
                        OutlinedButton(
                            onClick = {
                                if (spec.key in dangerousKeys) {
                                    showDangerConfirm(spec.key, spec.label)
                                } else onAction(spec.key)
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
                onClick = {
                    confirmSuspend.suspend()
                    confirmRequest = UfiConfirmRequest(
                        title = "授予全部权限",
                        text = buildString {
                            append("将一次性授予 $packageName 声明的全部运行时权限，")
                            append("可能包含定位、短信、通话记录、存储等敏感权限。\n\n")
                            append("此操作没有「一键撤销」，之后只能逐条手动收回。")
                            if (isSystemApp) append("\n\n⚠ 该应用为系统应用，放开全部权限的影响面更大。")
                        },
                        confirmText = "确认授予",
                        destructive = true,
                        onConfirm = {
                            confirmRequest = null
                            confirmSuspend.resume()
                            onGrantAllPermissions()
                        },
                        onDismiss = {
                            confirmRequest = null
                            confirmSuspend.resume()
                        }
                    )
                },
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

        }
    }

    // 确认框宿主：request 为空时不渲染任何窗口；
    // 两个确认（授予全部权限 / 危险操作）都从详情弹窗内部触发，父弹窗已在 suspend() 时收起。
    UfiConfirmHost(confirmRequest)
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
