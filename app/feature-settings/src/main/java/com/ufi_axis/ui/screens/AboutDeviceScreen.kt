package com.ufi_axis.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.feature.settings.R
import com.ufi_axis.ui.components.ApkPushDialog
import com.ufi_axis.ui.components.UpdateSettingsDialog
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.UpdateSource
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ufi_axis.ui.navigation.ufiNavigateOnce

/**
 * 「关于」页。
 *
 * 结构：
 * - 顶部身份区：应用图标（取自桌面图标）、版本号文本（"Version x.x.x"）与一句话定位。
 *   版本号连续点击 5 次解锁调试入口（相邻点击间隔不超过 1500ms），解锁后再点击直接进入
 *   调试日志页（[Routes.DETAIL_DEBUG_LOG]）；
 * - 「更新」区：「更新设置」行 → UpdateSettingsDialog；「检查更新」主按钮 → 打开全局唯一的
 *   统一更新弹窗（UnifiedUpdateDialog 实例挂在 MainActivity，本页只触发检查并调用 show()）；
 * - 常显入口：「备份与恢复」「诊断中心」「资源中心」「赞助与感谢」；
 * - 「调试」组（仅解锁后可见）：调试模式开关、调试日志、组件画廊；
 * - 「项目」区：开源地址、作者博客、开源许可（第三方组件声明）。
 *
 * 调试入口的解锁状态存于 [AppPreferences.debugEntryUnlocked]，它是纯本机 UI 门禁，与
 * [AppPreferences.debugMode]（日志详细级别，真源在 core）是两个独立状态：解锁只控制入口
 * 显形，日志级别只在日志页修改并下发 core。两者合并会使解锁动作同时打开全量 DEBUG/INFO
 * 落盘（仅写本地、不下发，与 core 的值分叉），并在下一次 `refreshDeviceConfig()` 时被
 * core 的 false 覆写。
 *
 * 诊断中心在本页常显，且本页是它唯一的常显入口：其用途正是服务异常时的排查，而隐藏在
 * 需要解锁的组里会使其在最需要时不可达。该路由（[Routes.DETAIL_DIAGNOSE]）只有这一个页面。
 *
 * 赞赏只有一颗按钮，动作是打开 [DonateDialog]（支付宝 / 微信两张码）。本页不提供其他赞赏
 * 入口，也不存在独立的赞赏页：额外入口会成为同一批位图的第二个解码 / 渲染点，而多实例
 * 渲染是该大尺寸位图解码问题的复发条件（见 [rememberDonateQrBitmaps] 的三层设防）。
 *
 * 「资源中心」另有两处上下文入口（内网穿透设置、服务器配置），用途不同：那两处是
 * 「缺组件时就地安装」，本页这一颗与具体故障无关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutDeviceScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
    }
    val prefs = remember { AppPreferences(context) }

    // 自动获取应用图标（跟随桌面图标；加载失败回退占位 Info 图标）
    val appIcon = remember(context) { loadAppIconBitmap(context) }

    // ── 更新融合（About + Update 弹窗化）──
    val frontendState by viewModel.tools.frontendUpdateState.collectAsState()
    val updateDeviceState by viewModel.tools.updateDeviceState.collectAsState()
    // 后端 Core 版本：走 DashboardModule 的 /api/config/version（UpdateState.serverVersion）。
    // 不用 tools.updateDeviceState.current_version —— 本页的 LaunchedEffect 会把那个 state 重置成 null。
    val serverUpdateState by viewModel.updateState.collectAsState()
    // 更新弹窗的可见性唯一真源是 viewModel.updatePrompt（UpdatePromptModule），弹窗实例
    // 只有一个、挂在 MainActivity 上。本页的「检查更新」只做两件事：触发检查、调用 show()。
    // 本页不持有弹窗可见性布尔值，也不渲染弹窗实例：两处各自持有实例会叠加出两层遮罩。
    //
    // checkRequested 是「当前停留期间用户主动触发过检查」的闸，与弹窗可见性无关：
    // frontendUpdateState 属 ViewModel 级，上一次检查的结果会保留；缺少该闸时，进入本页的
    // 第一帧就会依据旧 state 弹 toast 或打开弹窗。
    var checkRequested by remember { mutableStateOf(false) }
    var showUpdateSettingsDialog by remember { mutableStateOf(false) }
    var showApkPushDialog by remember { mutableStateOf(false) }
    // 赞赏弹窗可见性：本页「赞赏」按钮唯一的动作。
    //
    // 它同时是解码闸门：`visible == false` 时不解码任何位图（见 [rememberDonateQrBitmaps]
    // 的 `enabled`）。两张 1260px 级位图合计 565K，页面组合时同步解码会拖慢首帧；进入本页
    // 的频率远高于打开赞赏弹窗，因此解码必须挂在弹窗可见性上。
    var showDonateDialog by remember { mutableStateOf(false) }
    var updateSourceMode by remember { mutableStateOf(prefs.updateSourceMode) }
    // 打开「更新设置」时的下载方式快照，用于关闭时判断是否确有变更。
    // 无条件下发会在每次打开后关闭时产生一次设备写请求与一次 toast；设备不在线时还会在
    // 用户未做任何修改的情况下报同步失败。
    var modeOnOpen by remember { mutableStateOf("") }

    // 「当前国家/地区」只做展示：真源在 core（启动时自动检测一次并落盘，见 `/api/geo`）。
    // 初值取本地缓存，是为了 core 不可达时也有东西可显示。
    var lastCountry by remember { mutableStateOf(prefs.lastCountry) }
    var autoCheckUpdate by remember { mutableStateOf(prefs.autoCheckUpdate) }


    // 打开「更新设置」时向 core 问一次当前国家（core 不可达就保持缓存值）。
    // 放在打开那一刻而不是进页面就拉：这一行只在弹窗的第二个页签里出现。
    LaunchedEffect(showUpdateSettingsDialog) {
        if (showUpdateSettingsDialog) {
            lastCountry = UpdateSource.countryForDisplay(prefs)
        }
    }

    // 全局 Toast 反馈（UfiToastHost；本 Screen 持有 state，SAF/同步回调闭包内赋值）
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // SAF 文件选择（兜底推送后端 APK 更新）
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                if (input != null) {
                    val tmp = java.io.File(context.cacheDir, "ufi-core-push.apk")
                    tmp.outputStream().use { out -> input.copyTo(out) }
                    // 打开推送进度弹窗（替代原 Toast 反馈）
                    showApkPushDialog = true
                    viewModel.tools.pushApkAndInstall(tmp)
                } else {
                    toastMessage = ToastMessage("无法读取所选文件", ToastType.ERROR)
                }
            } catch (e: Exception) {
                toastMessage = ToastMessage("读取 APK 失败: ${e.message}", ToastType.ERROR)
            }
        } else {
            toastMessage = ToastMessage("已取消选择", ToastType.INFO)
        }
    }

    // 调试模式激活状态 + 版本号点击计数（连续点击窗口 1500ms）
    var debugActivated by remember { mutableStateOf(prefs.debugEntryUnlocked) }
    var versionClickCount by remember { mutableIntStateOf(0) }
    var versionClickLastTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        // 更新期间不得执行以下两项操作：此时用户最可能停留在本页，重新进入会重跑本
        // LaunchedEffect。
        // - resetUpdateDeviceState() 会清除进度状态并停止轮询，而轮询是读取安装结果的唯一
        //   通道；停止后设备端状态机可能停留在 installing（见 core 的
        //   UpdateManager.selfHealStuckState）；
        // - checkForUpdate() 在更新期间必然失败，只会额外产生一条「检查更新失败」。
        if (viewModel.tools.coreUpdating.value) return@LaunchedEffect
        // 进入本页时重置推送状态，避免残留的 uploading / installing 使弹窗误显示。
        viewModel.tools.resetUpdateDeviceState()
        // 补拉一次后端版本：UpdateState 属 ViewModel 级，应用启动时若后端不可达，
        // 失败分支会把 serverVersion 置为 null 且不再自动重试。该请求为轻量 GET。
        viewModel.dashboard.checkForUpdate()
    }

    fun onVersionClick() {

        val now = System.currentTimeMillis()
        if (now - versionClickLastTime > 1500) versionClickCount = 0
        versionClickLastTime = now
        versionClickCount++
        when {
            debugActivated -> {
                // 调试模式已激活 → 直接进入日志页。
                // launchSingleTop：连续点击会逐次触发 navigate，缺此参数会在返回栈中堆积
                // 多个日志页实例，表现为重复进入、需要多次返回。
                versionClickCount = 0
                navController.ufiNavigateOnce(Routes.DETAIL_DEBUG_LOG) { launchSingleTop = true }
            }
            versionClickCount >= 5 -> {
                // 只解锁本机调试入口的显形，不修改日志开关（后者对应 core 的 debug_mode，
                // 需在日志页修改，那里会下发 core 并回读确认）。
                prefs.debugEntryUnlocked = true
                debugActivated = true
                versionClickCount = 0
                toastMessage = ToastMessage("调试入口已解锁，可查看调试日志与运行诊断", ToastType.SUCCESS)
            }
            versionClickCount >= 3 -> {
                toastMessage = ToastMessage("再点击 ${5 - versionClickCount} 次激活调试模式", ToastType.INFO)
            }
        }
    }

    fun openLink(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toastMessage = ToastMessage("无法打开链接: ${it.message}", ToastType.ERROR)
        }
    }

    UfiScreenScaffold(title = "关于", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            val palette = LocalResolvedPalette.current

            // ══════════ 顶部：App 图标（自动获取）+ 版本号（连续点击 5 次激活调试模式）══════════
            UfiSettingsGroup {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(Spacing.Medium))
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(UfiCardDefaults.iconTileShape)
                            .background(palette.accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = appIcon
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = "UFI-AXIS",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(UfiCardDefaults.iconTileShape)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = "UFI-AXIS",
                                tint = palette.accent,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.Medium))
                    Text(
                        text = "UFI-AXIS",
                        style = UfiTextStyles.panelTitleStrong,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Version ${appVersion ?: "未知"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        modifier = Modifier
                            .clip(UfiCardDefaults.microShape)
                            .clickable { onVersionClick() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    // 后端 Core 版本单独一行：前端与后端是独立发版的两个 APK（version.json
                    // 中 frontend / backend 各一个对象），版本号经常不一致，只显示前端版本
                    // 会造成误判。取不到时显示「未连接」而非隐藏：空白会被理解为该信息不存在。
                    Text(
                        text = "Core ${serverUpdateState.serverVersion ?: "未连接"}",
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary.copy(alpha = 0.7f)
                    )
                    // 一句话定位：与 README / 仓库 About 保持同一口径。
                    // 位于版本号下方而不单独成组：本页顶部是身份区（图标 + 名称 + 版本），
                    // 简介属于同一块信息，单独成组会使首屏出现两张都描述「这是什么」的卡片。
                    // 此处不列技术栈或模块清单：这类信息随迭代变化，只维护在仓库 README。
                    Spacer(Modifier.height(Spacing.Medium))
                    Text(
                        text = "为随身 WiFi / CPE 提供第三方管理端：后端服务运行在设备本身，" +
                            "手机端与网页面板共用同一套接口。除状态查看与网络设置外，" +
                            "还支持频段锁定、定时任务、测速、流量限额、文件管理、下载器、" +
                            "内网穿透与短信转邮件。",
                        style = UfiTextStyles.body,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Spacing.CardPadding)
                    )

                    // ── 赞赏入口 ──
                    // 使用公共 UfiButton 的 Donate 档（DonatePink 描边，几何同 Secondary），
                    // 不使用手写的 Row + Icon 伪按钮：本页只有一个可点操作，几何必须与全站
                    // 按钮一致，仅配色刻意例外。
                    //
                    // 动作是打开赞赏弹窗（[DonateDialog]，两张码：支付宝 / 微信）。本页不设
                    // 指向赞赏码的独立页面或其他行卡：该按钮即唯一的赞赏入口。
                    Spacer(Modifier.height(Spacing.Large))
                    UfiButton(
                        text = "赞赏",
                        icon = AppIconHeart,
                        variant = UfiButtonVariant.Donate,
                        size = UfiButtonSize.Small,
                        onClick = { showDonateDialog = true }
                    )
                    // 底部不追加 Spacer：UfiSettingsGroup 自带 Spacing.CardPadding（20dp）
                    // 内距，再加一个 Medium 会使卡片下缘留出 28dp 空白，身份区重心下移。
                }
            }


            // ══════════ 更新（融合 About + Update：检查更新弹窗 + 更新设置弹窗）══════════
            UfiSettingsGroup {
                UfiGroupHeader("更新")
                UfiSettingsItem(
                    title = "更新设置",
                    description = "镜像源 · 启动时自动检查更新",
                    trailing = {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "更新设置",
                            tint = palette.textSecondary
                        )
                    },
                    modifier = Modifier.clickable {
                        // 记下打开时的下载方式，关闭时据此判断是否需要下发（见 onDismiss）
                        modeOnOpen = prefs.updateSourceMode
                        showUpdateSettingsDialog = true
                    }

                )
                Spacer(Modifier.height(Spacing.Medium))
                // 「检查更新」置于卡片内部：作为卡片之间的独立按钮时，它同时受
                // CardHorizontalMargin 约束与卡片间距叠加，视觉上浮在两组之间。
                UfiButton(
                    text = if (frontendState.state == "checking") "正在检查更新…" else "检查更新",
                    enabled = frontendState.state !in setOf("checking", "downloading", "installing"),
                    loading = frontendState.state == "checking",
                    onClick = {
                        checkRequested = true
                        // 两侧一起查：弹窗是合并的，只查一侧会让另一侧的信息停留在上次结果。
                        viewModel.tools.checkFrontendUpdate()
                        viewModel.dashboard.checkForUpdate()
                    }
                )
            }
            // 检查完成后的分支：App 侧无更新→toast；有更新→打开统一更新弹窗唯一的那个槽
            //（弹窗实例在 MainActivity，本页只调用 show()，因此不会与自动提示叠加）。
            // 必须由 checkRequested 把闸：LaunchedEffect 首次组合即执行一次，
            // 上一次检查留下的 state（例如 no_update）会导致进入本页时立即弹出无关提示。
            //
            // no_update 走 toast 而非打开弹窗：若 core 侧有更新，自动提示已经给出；
            // 再弹一个「App 已最新 + Core 有更新」的弹窗属于重复提示。
            LaunchedEffect(frontendState.state, frontendState.latestVersion) {
                if (!checkRequested) return@LaunchedEffect
                when (frontendState.state) {
                    "no_update" -> {
                        checkRequested = false
                        toastMessage = ToastMessage("当前 ${frontendState.currentVersion} 已是最新版本", ToastType.SUCCESS)
                    }
                    "available", "downloading", "downloaded", "installing" -> {
                        viewModel.updatePrompt.show()
                    }
                    "error" -> {
                        checkRequested = false
                        toastMessage = ToastMessage(
                            frontendState.errorMessage?.takeIf { it.isNotBlank() } ?: "检查更新失败",
                            ToastType.ERROR
                        )
                    }
                }
            }

            // ══════════ 备份与恢复 ══════════
            // 该入口位于关于页而非设置一级页：它是对整机的维护动作，与设置页「修改某一项
            // 配置」的入口不同类，使用频率与更新相当。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Filled.Backup,
                    title = "备份与恢复",
                    description = "导出配置备份包 · 从备份文件恢复",
                    onClick = {
                        navController.ufiNavigateOnce(Routes.DETAIL_BACKUP_RESTORE) { launchSingleTop = true }
                    },
                    trailing = { UfiSettingsChevron() }
                )
            }

            // ══════════ 诊断中心（常显）══════════
            // 不置于仅解锁后可见的组内：该组需连续点击版本号 5 次才出现，而诊断中心正是服务
            // 异常时需要的功能，用户不会先行解锁。因此必须在不需解锁的位置有入口。
            // 路由复用既有的 Routes.DETAIL_DIAGNOSE，不存在第二个诊断页。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                    title = "诊断中心",
                    description = "设备权限 · 网络连通 · 后台服务 · 导出诊断包",
                    onClick = {
                        navController.ufiNavigateOnce(Routes.DETAIL_DIAGNOSE) { launchSingleTop = true }
                    },
                    trailing = { UfiSettingsChevron() }
                )
            }

            // ══════════ 资源中心 ══════════
            // 内网穿透设置与服务器配置中的入口都是「缺组件时就地安装」的上下文入口；
            // 若资源中心只有这两处入口，尚未遇到具体故障、只想查找可下载资源的用户
            // 在应用内即无常规入口。本行卡提供这条与具体故障无关的常规入口。
            //
            // 上述两处上下文入口保留：其价值是在对应页面内直接安装，与本行卡「查找可下载
            // 资源」的意图不同；资源中心是同一个页面，多入口不会产生两套内容。
            //
            // 描述逐项列出该页管理的内容（Web 前端 ZIP / frpc / cloudflared），使用户可按
            // 具体目的判断是否进入，而非复述页面标题。
            //
            // 图标复用仓内既有的 Icons.Filled.CloudDownload（工具页「下载管理」同款）：
            // 语义为「将文件取到设备上」，与该页动作一致，且不引入新图标依赖。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Filled.CloudDownload,
                    title = "资源中心",
                    description = "Web 前端资源 ZIP · frpc / cloudflared 的下载、安装与卸载",
                    onClick = {
                        navController.ufiNavigateOnce(Routes.DETAIL_RESOURCES) { launchSingleTop = true }
                    },
                    trailing = { UfiSettingsChevron() }
                )
            }

            // ══════════ 赞助与感谢 ══════════
            // 此处是赞助名单，不是赞赏码：赞赏码只在那颗「赞赏」按钮的弹窗里（[DonateDialog]）。
            // 本页不设指向赞赏码的行卡，名单入口因此只有这一颗。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Filled.FavoriteBorder,
                    title = "赞助与感谢",
                    description = "感谢名单",
                    onClick = {
                        navController.ufiNavigateOnce(Routes.DETAIL_SPONSORS) { launchSingleTop = true }
                    },
                    trailing = { UfiSettingsChevron() }
                )
            }

            // ══════════ 调试（隐藏组：仅在版本号连续点击 5 次、解锁调试入口后出现）══════════
            // 不采用「连续点击直接跳诊断页」：该手势已用于进入日志页，叠加第二个目的地无法
            // 区分意图。改为解锁后显形一组入口，常规界面不出现。
            if (debugActivated) {
                UfiSettingsGroup {
                    UfiGroupHeader("调试")
                    // 调试模式开关：解锁路径只有置 true，缺少关闭路径，解锁后调试组会
                    // 永久显形，只能通过清除应用数据恢复。开关连同整组一起收起是预期行为：
                    // 该组本就是隐藏入口，收起后可通过再次连续点击版本号 5 次解锁。
                    UfiSettingsItem(
                        title = "调试模式",
                        description = "关闭后收起本组调试入口；连点版本号 5 次可重新解锁",
                        trailing = {
                            UfiSwitch(
                                checked = debugActivated,
                                onCheckedChange = {
                                    prefs.debugEntryUnlocked = it
                                    debugActivated = it
                                    if (!it) {
                                        // 连点计数归零：关闭动作不计入「再点击 N 次解锁」，
                                        // 否则残留计数会使下一次解锁提前触发。
                                        versionClickCount = 0
                                        toastMessage = ToastMessage(
                                            "已退出调试模式，连点版本号 5 次可重新解锁",
                                            ToastType.INFO
                                        )
                                    }
                                }
                            )
                        }
                    )
                    UfiDivider()
                    UfiSettingsItem(
                        title = "调试日志",
                        description = "抓包记录 · 日志开关",
                        modifier = Modifier.clickable {
                            navController.ufiNavigateOnce(Routes.DETAIL_DEBUG_LOG) { launchSingleTop = true }
                        }
                    )
                    UfiDivider()
                    // 本组不设「运行诊断」：它与「诊断中心」指向同一页面。
                    // 组件画廊位于本组：它是开发用的组件预览页（按族铺开、明暗对照），
                    // 普通用户不需要，不应常驻外观设置。
                    UfiSettingsItem(
                        title = "组件画廊",
                        description = "预览全部共享组件 · 可切换明暗对照",
                        modifier = Modifier.clickable {
                            navController.ufiNavigateOnce(Routes.DETAIL_UI_GALLERY) { launchSingleTop = true }
                        }
                    )
                }
            }

            // ══════════ 项目（对齐设置页：每项一张独立行卡 + 图标 + 右侧 chevron）══════════
            // ══════════ 项目（与设置页一致：每项一张独立行卡 + 图标 + 右侧 chevron）══════════
            // 不使用分组标题 + 分隔线：这两项各自是跳转外链的独立入口，彼此无从属关系，
            // 加分组标题会使其看起来像一个设置类目。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = AppIconGitHub,
                    title = "UFI-AXIS",
                    description = "github.com/Asunano/UFI-AXIS",
                    onClick = { openLink("https://github.com/Asunano/UFI-AXIS") },
                    trailing = { UfiSettingsChevron() }
                )
            }
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Default.Language,
                    title = "作者博客",
                    description = "blog.losn.cc",
                    onClick = { openLink("https://blog.losn.cc/") },
                    trailing = { UfiSettingsChevron() }
                )
            }
            // 开源许可入口。
            // core APK 打包了 aria2c / socat 等 GPL 系预编译二进制
            //（core/src/main/assets/shell/），分发时须附许可证并提供对应源码，该义务必须在
            // 用户可达的位置兑现。app 与 core 是同一仓库的两个产物，因此两端「关于」都提供
            // 指向同一份声明的入口（web 侧见 AboutPanel.vue）。
            // 使用外链而非内置页面：声明文件在 core 的 assets 中，app 无法读取；该声明随
            // 二进制版本更新，GitHub 上的文件是唯一真源。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Default.Gavel,
                    title = "开源许可",
                    description = "第三方组件与许可证声明",
                    onClick = { openLink(THIRD_PARTY_NOTICES_URL) },
                    trailing = { UfiSettingsChevron() }
                )
            }

            // ══════════ 页脚署名 ══════════
            // 低对比度、居中、不可点击：它是署名而非入口，可点击会引导用户点击。
            Spacer(Modifier.height(Spacing.SectionSpacing))
            Text(
                text = "Made with ❤ for UFI devices",
                style = UfiTextStyles.caption,
                color = palette.textSecondary.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.SectionSpacing))

            // ══════════ 更新弹窗（设置 / 推送 APK 进度）══════════
            // 「检查结果」弹窗不在此处：它是全局唯一的 UnifiedUpdateDialog，挂在 MainActivity
            // 上，本页通过 viewModel.updatePrompt.show() 打开同一个槽。若在此再渲染一个实例，
            // 同一 visible 会同时点亮两个平台 Window，导致遮罩叠加。
            // 推送 APK 进度弹窗（监听 updateDeviceState 实时显示上传/安装阶段）
            ApkPushDialog(
                visible = showApkPushDialog,
                state = updateDeviceState,
                onDismiss = { showApkPushDialog = false },
                onRetry = {
                    // 重试：复用 SAF 选过的 APK（cacheDir 下的 tmp 文件）
                    viewModel.tools.pushApkAndInstall(java.io.File(context.cacheDir, "ufi-core-push.apk"))
                }
            )
            if (showUpdateSettingsDialog) {
                UpdateSettingsDialog(
                    visible = showUpdateSettingsDialog,
                    sourceMode = updateSourceMode,
                    onSourceModeChange = { mode ->
                        updateSourceMode = mode
                        prefs.updateSourceMode = mode
                    },
                    lastCountry = lastCountry,
                    autoCheck = autoCheckUpdate,
                    onAutoCheckChange = { checked ->
                        autoCheckUpdate = checked
                        prefs.autoCheckUpdate = checked
                    },
                    onPushApk = {
                        // 先关闭更新设置弹窗，让后续弹窗/反馈不被遮挡
                        showUpdateSettingsDialog = false
                        filePicker.launch(arrayOf("application/vnd.android.package-archive"))
                    },
                    // 关闭时仅在下载方式确有变化时下发。
                    // 无条件同步会使「打开后立即关闭」也发起一次设备写请求并弹一次 toast；
                    // 设备不在线时则在用户未做任何修改的情况下报同步失败。
                    onDismiss = {
                        showUpdateSettingsDialog = false
                        if (updateSourceMode != modeOnOpen) {
                            viewModel.tools.syncUpdateModeToDevice(updateSourceMode) { ok ->
                                toastMessage = ToastMessage(
                                    if (ok) "已同步更新源设置到设备" else "设备未连接，同步失败",
                                    if (ok) ToastType.SUCCESS else ToastType.ERROR
                                )
                                // 同步失败时不推进快照，否则下次关闭会被判定为「未修改」而不再重试
                                if (ok) modeOnOpen = updateSourceMode
                            }
                        }
                    }

                )
            }

            // ══════════ 赞赏弹窗（支付宝 / 微信两张码）══════════
            // 位置在 UfiPageBackground 内部只决定组合层级：弹窗渲染在自己的平台 Window 中
            //（见 UfiDialogShell），不受本页 verticalScroll 影响；反之弹窗内部的滚动也不得
            // 与本页滚动嵌套（见 [DonateDialog]）。
            DonateDialog(visible = showDonateDialog, onDismiss = { showDonateDialog = false })
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

/** 读取当前应用图标（自动跟随桌面图标；失败返回 null，调用方回退占位图标） */
private fun loadAppIconBitmap(context: Context): ImageBitmap? = runCatching {
    val drawable = context.packageManager.getApplicationIcon(context.packageName)
    val bitmap: Bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
        drawable.bitmap
    } else {
        // 矢量/自适应图标 intrinsic 可能为 -1 或过小：统一以 ≥144px 画布绘制，保证清晰
        val width = (drawable.intrinsicWidth.takeIf { it > 0 } ?: 144).coerceAtLeast(144)
        val height = (drawable.intrinsicHeight.takeIf { it > 0 } ?: 144).coerceAtLeast(144)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        bmp
    }
    bitmap.asImageBitmap()
}.getOrNull()

/**
 * 第三方组件许可声明的地址。
 *
 * 单一真源是 `core/src/main/assets/shell/THIRD-PARTY-NOTICES.md`（声明 core 打包的
 * 预编译二进制），此处只做引用：声明随二进制版本变化，复制进 app 必然产生偏差。
 */
private const val THIRD_PARTY_NOTICES_URL =
    "https://github.com/Asunano/UFI-AXIS/blob/main/core/src/main/assets/shell/THIRD-PARTY-NOTICES.md"

// ────────────────────────── 赞赏弹窗（两张赞赏码） ──────────────────────────
//
// `DONATE_QR_WIDTH_FRACTION` / `rememberDonateQrBitmaps` / `decodeDownsampledBitmap`
// 三个私有件只服务本文件中的 [DonateDialog]。全仓只有这一处解码与渲染那两张位图：
// 多实例渲染是大尺寸位图解码问题复发的条件（见 [rememberDonateQrBitmaps] 的三层设防）。

/**
 * 赞赏弹窗：两张赞赏码（支付宝在左、微信在右）+ 一句「赞赏完全自愿」的说明。
 *
 * ## 高度与滚动：只滚一层，且该层属于弹窗
 * [UfiScrollableDialog] 的 content 槽本身是一个可滚动的 `Column`：它先将内容
 * `heightIn(max = maxScrollH)` 限制到有限高度（`maxScrollH = 0.82×屏高 − 标题 − 页脚`，
 * 见 `UfiCustomDialog.kt`），再套一层 `verticalScroll`。因此：
 *  · 本弹窗内容中不含任何 `verticalScroll` / `LazyColumn`：纵向滚动嵌套纵向滚动会导致
 *    弹窗崩溃，且弹窗场景比整页更易触发；
 *  · 窄屏下两张竖版码上下堆叠、内容超出 maxScrollH 时，由弹窗那一层滚动；常规机型上
 *    两张并排时不会达到该上限。
 * 约束：只允许一层滚动，且该层由弹窗提供；本弹窗内不得添加任何滚动容器。
 *
 * ## 高度取值（两张图比例不同，此处最易出错）
 * 支付宝 1260×1890（≈ 1:1.50）、微信 1308×1781（≈ 1:1.36）。因此：
 *  · 并排时不得给同一个固定高度（如 `Modifier.height(200.dp)`）：固定高度会把其中一张
 *    拉伸或压扁，而压扁的二维码会从正方模块变为矩形，识别率显著下降；
 *  · 两张均使用 `fillMaxWidth()` + `ContentScale.FillWidth`：只约束宽度，高度按各自宽高比
 *    计算（等价于 web 侧 `width` + `height: auto`）。并排时两张高度不同是正确结果，
 *    不需要对齐。
 *  · `ContentScale.FillWidth` 在无界高度下是安全的：缩放只依赖宽度，高度由图片自身比例
 *    得出为有限值，不会像 `verticalScroll` 那样受 Infinity 约束影响。
 *
 * ## 并排与堆叠：判据只计算一次，同时用于排布与解码
 * 见 [DonateCodeLayout] 与 [donateQrTargetWidthPx]。两处各算一次时，在某个宽度区间会出现
 * 「按并排解码、按堆叠显示」，位图被放大两倍以上，二维码模块边缘模糊，同样无法识别。
 */
@Composable
private fun DonateDialog(visible: Boolean, onDismiss: () -> Unit) {
    val palette = LocalResolvedPalette.current

    // 排布判据：屏宽 ≥ [SIDE_BY_SIDE_MIN_WIDTH_DP] 时并排，否则上下堆叠。
    // 该判据同时决定排布与解码目标宽度（理由见上）。
    val sideBySide = LocalConfiguration.current.screenWidthDp >= SIDE_BY_SIDE_MIN_WIDTH_DP

    // 解码闸门：弹窗未打开时 enabled = false，不解码任何位图（见 [rememberDonateQrBitmaps]）。
    val codes = rememberDonateQrBitmaps(enabled = visible, sideBySide = sideBySide)

    UfiScrollableDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "赞赏"
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "如果你喜欢这个软件，可以考虑请我喝一杯柠檬水哟~（狗头）",
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Spacing.Small))
            // 自愿与「不影响功能」必须标注在码旁，而非仅写在仓库文档中：用户扫描前需要看到。
            Text(
                text = "赞赏完全自愿，不影响任何功能 —— 所有功能对所有用户都是完整开放的。",
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Spacing.Large))
            DonateCodeLayout(codes = codes, sideBySide = sideBySide)
        }
    }
}

/**
 * 一张赞赏码的静态描述。
 *
 * @param aspectRatio 宽 / 高。两张图不同（1260:1890 ≈ 0.667、1308:1781 ≈ 0.734），
 *   该值用于两处：① 位图解码完成前的占位框比例（避免先绘制方形再变为竖长条）；
 *   ② 解码目标宽度的换算（见 [donateQrTargetWidthPx]）。两处都必须逐张取值，不能共用。
 */
private data class DonateCodeSpec(
    val resId: Int,
    val label: String,
    val aspectRatio: Float,
)

/** 两张码：支付宝在左、微信在右（组合顺序即视觉顺序，窄屏改为上下堆叠时顺序不变）。 */
private val DONATE_CODES = listOf(
    DonateCodeSpec(resId = R.drawable.img_donate_alipay, label = "支付宝", aspectRatio = 1260f / 1890f),
    DonateCodeSpec(resId = R.drawable.img_donate_wechat, label = "微信", aspectRatio = 1308f / 1781f),
)

/**
 * 并排显示所需的最小屏宽（dp）；低于该值时改为上下堆叠（见 [DonateCodeLayout]）。
 *
 * 判据取屏宽而非卡片内容宽：解码目标宽度只能由屏宽推算（弹窗内无法获知内容区剩余宽度），
 * 而排布方式与解码尺寸必须使用同一判据（理由见 [DonateDialog]）。
 * 手机竖屏普遍为 360~412dp，落在堆叠一侧：弹窗内容区约 256~303dp，两张并排时各仅
 * 130~150dp，该尺寸在手机屏幕上难以扫描；堆叠后每张可获得整宽，由弹窗那一层滚动查看。
 */
private const val SIDE_BY_SIDE_MIN_WIDTH_DP = 420

/**
 * 两张码的排布：宽屏并排（支付宝左 / 微信右），窄屏上下堆叠、居中。
 *
 * 本函数不含任何滚动容器，只负责排布。滚动由外层 [UfiScrollableDialog] 承担（唯一一层），
 * 见 [DonateDialog] 的「高度与滚动」。
 *
 * @param sideBySide 由 [DonateDialog] 统一判定后传入，此处不重新计算
 *   （判据不一致会导致「按并排解码、按堆叠显示」，位图放大后二维码不可识别，见该处注释）。
 */
@Composable
private fun DonateCodeLayout(codes: List<ImageBitmap?>, sideBySide: Boolean) {
    if (sideBySide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
            // 顶对齐：两张高度不同（比例不同），居中会使其中一张看起来未对齐
            verticalAlignment = Alignment.Top
        ) {
            DONATE_CODES.forEachIndexed { index, spec ->
                DonateCodeColumn(
                    spec = spec,
                    bitmap = codes.getOrNull(index),
                    // 两张等宽：宽度由排布决定，高度由各自比例决定
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.Large),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DONATE_CODES.forEachIndexed { index, spec ->
                DonateCodeColumn(
                    spec = spec,
                    bitmap = codes.getOrNull(index),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 一张码 + 其下方的渠道名。
 *
 * 图片使用 `fillMaxWidth()` + [ContentScale.FillWidth]：只约束宽度，高度按该图自身比例
 * 计算（等价于 web 侧 `width` + `height: auto`）。不设置任何 `.height(...)`：
 * 两张比例不同，固定高度会压扁其中一张（见 [DonateDialog]）。
 */
@Composable
private fun DonateCodeColumn(spec: DonateCodeSpec, bitmap: ImageBitmap?, modifier: Modifier) {
    val palette = LocalResolvedPalette.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "${spec.label}赞赏码",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(UfiCardDefaults.shape)
            )
        } else {
            // 解码在 IO 线程执行，首帧为空；按该图自身比例先行占位
            //（两张比例不同，占位高度因此不同，见 DonateCodeSpec.aspectRatio）。
            // `aspectRatio` 在无界高度下安全：先按 maxWidth 定宽，宽度有界即可算出有限高度。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(spec.aspectRatio),
                contentAlignment = Alignment.Center
            ) {
                UfiLoadingIndicator()
            }
        }
        Spacer(Modifier.height(Spacing.Small))
        // 渠道名标注在每张码下方，而非顶部统一说明：并排时用户需能区分两张码
        Text(
            text = spec.label,
            style = UfiTextStyles.bodyEmphasis,
            color = palette.textPrimary
        )
    }
}

/**
 * 弹窗内容区宽度占屏宽的上界比例（仅用于计算解码目标宽度）。
 *
 * 弹窗内容宽 = `0.90 × 屏宽`（`UfiDialogShell` 的 `fillMaxWidth(0.90f)`）
 *            − `32dp`（shell 的 `padding(horizontal = 16.dp)`）
 *            − `36dp`（卡片内边距 `Spacing.DialogPaddingH = 18.dp`）
 *          = `0.90 × 屏宽 − 68dp`，在 360~480dp 的机型上约为屏宽的 0.71~0.74 倍。
 *
 * 此处取 0.78，刻意取上界，因为两个方向的代价不对称：
 *  · 偏大只增加解码内存，且解码尺寸 ≥ 显示尺寸，不会被二次缩放；
 *  · 偏小则位图小于显示尺寸，会被放大，二维码模块边缘模糊，无法扫描。
 * 该算式与 shell 的实际几何绑定：修改 `UfiDialogShell` 的宽度或内边距时必须同步核对
 *（本文件无法获取其测量值，只能以此常量表达）。
 */
private const val DONATE_QR_WIDTH_FRACTION = 0.78f

/**
 * 单列（上下堆叠）时允许的解码高度上限（占屏高的比例）。
 *
 * 堆叠时一张码的显示高度约为弹窗内容宽的 1.5 倍，长屏上会超过屏高的一半。该高度由弹窗
 * 滚动承载，但无需为此解码一张高于屏幕的位图：超过此上限时按屏高折回宽度。
 * 取值刻意宽松（0.9），常规机型不会触发，仅用于限制极端比例。
 */
private const val DONATE_QR_MAX_HEIGHT_FRACTION = 0.9f

/**
 * 将两张赞赏码解码为 [ImageBitmap]（全仓唯一解码它们的位置）。
 *
 * 不使用 `painterResource` 的原因：这两张图是 1260×1890 与 1308×1781 的 PNG。若放在普通
 * `drawable/` 下，aapt 按 mdpi 基线处理，`decodeResource` 会按设备密度放大 —— 3x 机型上
 * 变为 3780×5670 与 3924×5343，ARGB_8888 各约 80MB，单张即可超出一次 Activity 的位图
 * 预算。
 *
 * 三层设防（全部保留，缺一层该弹窗即恢复为崩溃源）：
 * 1. 资源放在 `res/drawable-nodpi/`，从源头禁止密度缩放；
 * 2. 按弹窗内容宽度降采样，并使用 `RGB_565`（赞赏码为纯黑白，565 无可见损失，内存减半）；
 * 3. 解码在 [Dispatchers.IO] 上执行。
 *
 * @param enabled 解码闸门：仅在弹窗确实打开时解码（false → 直接返回空列表）。
 *   两张图合计 565K，进入本页即同步解码两张 1260px 级位图会拖慢首帧；而进入本页的频率
 *   远高于打开赞赏弹窗。关闭弹窗时列表清空，位图随即被回收。
 * @param sideBySide 排布判据，由 [DonateDialog] 计算后传入（不在此处重算，理由见该处注释）。
 *   该参数进入 [produceState] 的 key：横竖屏切换导致排布变化时会重新解码。
 */
@Composable
private fun rememberDonateQrBitmaps(enabled: Boolean, sideBySide: Boolean): List<ImageBitmap?> {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    return produceState<List<ImageBitmap?>>(
        emptyList(),
        enabled,
        screenWidthDp,
        screenHeightDp,
        density,
        sideBySide
    ) {
        // 闸门位于这一层：未打开时不做任何事，也不持有任何位图
        if (!enabled) {
            value = emptyList()
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            DONATE_CODES.map { spec ->
                decodeDownsampledBitmap(
                    context = context,
                    resId = spec.resId,
                    targetWidth = donateQrTargetWidthPx(spec, screenWidthDp, screenHeightDp, density, sideBySide)
                )?.asImageBitmap()
            }
        }
    }.value
}

/**
 * 计算单张码在当前排布下的解码目标宽度（像素）。
 *
 * 宽度来自排布（并排 = 内容区的一半，堆叠 = 整宽），但必须逐张计算，因为两张图的宽高比
 * 不同：支付宝 1260:1890（≈ 1:1.50）、微信 1308:1781（≈ 1:1.36）。同一显示宽度下左侧
 * 那张高约 10%，因此由「高度上限」折回的宽度对两张是两个不同的值；共用一个值会使其中
 * 一张被二次缩放（模糊、识别率下降），或占用多余内存。同一目标宽度下，`inSampleSize`
 * 也按各自的源宽度（1260 / 1308）分别计算（见 [decodeDownsampledBitmap] 的循环）。
 *
 * 两个约束取较小值：
 *  · 显示宽度：保证解码尺寸 ≥ 显示尺寸（不被二次缩放的下限）；
 *  · 高度上限：堆叠时一张码的显示高度约为内容宽的 1.5 倍，超过
 *    [DONATE_QR_MAX_HEIGHT_FRACTION] 时按屏高折回宽度（仅限制极端比例，常规机型不触发）。
 */
private fun donateQrTargetWidthPx(
    spec: DonateCodeSpec,
    screenWidthDp: Int,
    screenHeightDp: Int,
    density: Float,
    sideBySide: Boolean,
): Int {
    val cardWidthPx = screenWidthDp * density * DONATE_QR_WIDTH_FRACTION
    // 并排时先扣除中间间距再对半。间距直接取 `Spacing.Large.value`：
    // DonateCodeLayout 使用 `Arrangement.spacedBy(Spacing.Large)`，另抄一个常量会与其不一致
    //（间距偏差会使解码宽度不足，导致显示放大）。
    val displayWidthPx =
        if (sideBySide) (cardWidthPx - Spacing.Large.value * density) / 2f else cardWidthPx
    // 宽度 → 高度使用该图自身比例（aspectRatio = 宽 / 高）
    val maxHeightPx = screenHeightDp * density * DONATE_QR_MAX_HEIGHT_FRACTION
    val heightCappedWidthPx = maxHeightPx * spec.aspectRatio
    return minOf(displayWidthPx, heightCappedWidthPx).toInt().coerceAtLeast(1)
}

/**
 * 降采样解码：先只读尺寸计算 `inSampleSize`（必须是 2 的幂），再正式解码。
 * `inScaled = false` 与 `openRawResource` 同时使用，跳过 `decodeResource` 的密度缩放。
 */
private fun decodeDownsampledBitmap(context: Context, resId: Int, targetWidth: Int): Bitmap? {
    return runCatching {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.resources.openRawResource(resId).use { BitmapFactory.decodeStream(it, null, probe) }
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null

        var sampleSize = 1
        while (probe.outWidth / (sampleSize * 2) >= targetWidth) sampleSize *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = false
        }
        context.resources.openRawResource(resId).use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()
}

// ───────────────────────────────────────────────────────────
// 手绘 SVG 矢量图标（与 AppearanceSettingsScreen 的 AppIcon* 同一风格）
// 单色 path（Color.Black 会被 Icon 的 tint 覆盖），24×24 viewport
// ───────────────────────────────────────────────────────────

/**
 * 赞赏：描边爱心。
 *
 * 使用 stroke 而非 fill，以与 Donate 档描边按钮保持同构：实心心形置于描边按钮内会明显
 * 重于文字，视觉上表现为两个不同层级的元素拼接。两瓣各以对称的三次贝塞尔起笔，收笔汇于
 * (12, 21) 的尖端。
 */
private val AppIconHeart: ImageVector = ImageVector.Builder(
    name = "AppIconHeart", defaultWidth = Spacing.IconCanvas, defaultHeight = Spacing.IconCanvas,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        moveTo(12f, 20.6f)
        // 左瓣：从尖端往上绕到顶部中线
        curveTo(6.6f, 15.9f, 3f, 12.8f, 3f, 8.9f)
        curveTo(3f, 6.1f, 5.1f, 4f, 7.7f, 4f)
        curveTo(9.4f, 4f, 11f, 4.9f, 12f, 6.4f)
        // 右瓣：镜像回到尖端
        curveTo(13f, 4.9f, 14.6f, 4f, 16.3f, 4f)
        curveTo(18.9f, 4f, 21f, 6.1f, 21f, 8.9f)
        curveTo(21f, 12.8f, 17.4f, 15.9f, 12f, 20.6f)
        close()
    }
}.build()

/**
 * GitHub 官方 mark 轮廓（实心）。
 *
 * 该图标不手绘：品牌标识绘制偏差比缺少图标更不利，因此直接使用官方 24×24 路径，经
 * [addPathNodes] 解析，而非手抄为 PathBuilder 调用（数十个控制点手工转写必然出错）。
 *
 * 源 SVG 带 `fill-rule="evenodd"`，因此必须显式传入 [PathFillType.EvenOdd]：
 * 默认的 NonZero 会填充猫身与尾巴之间的镂空，图标变为实心块。
 */
private val AppIconGitHub: ImageVector = ImageVector.Builder(
    name = "AppIconGitHub", defaultWidth = Spacing.IconCanvas, defaultHeight = Spacing.IconCanvas,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    addPath(
        pathData = addPathNodes(GITHUB_MARK_PATH),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color.Black)
    )
}.build()

// 官方 mark 原始 pathData。
//
// 由单行 705 字符折成 8 段字面量拼接：拆分只允许发生在原串已有的空格处，且空格保留在前
// 一段末尾，使 `+` 拼接结果与原串逐字节相同。`const val` 支持编译期常量拼接，因此它仍是
// 一个编译期常量，调用点取值不变。
//
// 不得在数字中间断行，也不得为对齐增删空白：一处错位会把坐标解析成另一个值，且结果仅为
// 图标形状异常，不会报错，难以回溯。
private const val GITHUB_MARK_PATH =
    "M12 0c6.63 0 12 5.276 12 11.79-.001 5.067-3.29 9.567-8.175 11.187-.6.118-.825-.25-.825-.56 " +
    "0-.398.015-1.665.015-3.242 0-1.105-.375-1.813-.81-2.181 2.67-.295 5.475-1.297 5.475-5.822 " +
    "0-1.297-.465-2.344-1.23-3.169.12-.295.54-1.503-.12-3.125 0 0-1.005-.324-3.3 1.209a11.32 11.32 0 " +
    "00-3-.398c-1.02 0-2.04.133-3 .398-2.295-1.518-3.3-1.209-3.3-1.209-.66 1.622-.24 2.83-.12 " +
    "3.125-.765.825-1.23 1.887-1.23 3.169 0 4.51 2.79 5.527 5.46 5.822-.345.294-.66.81-.765 " +
    "1.577-.69.31-2.415.81-3.495-.973-.225-.354-.9-1.223-1.845-1.209-1.005.015-.405.56.015.781.51.28 " +
    "1.095 1.327 1.23 1.666.24.663 1.02 1.93 4.035 1.385 0 .988.015 1.916.015 2.196 0 " +
    ".31-.225.664-.825.56C3.303 21.374-.003 16.867 0 11.791 0 5.276 5.37 0 12 0z"



