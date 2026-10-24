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

/**
 * 关于页（2026-08-12 重构，对齐旧项目 UFITOOLS-Widget About 界面；2026-08-13 融合「关于 + 更新」）。
 *
 * - 顶部：App 图标（自动获取桌面图标）+ 版本号文本（"Version x.x.x"）+ 一句话简介——
 *   版本号连续点击 5 次激活调试模式，激活后再点击直接跳转 DebugLog 页（Routes.DETAIL_DEBUG_LOG）；
 * - 「更新」区：「更新设置」行 → UpdateSettingsDialog；「检查更新」主按钮 → 打开**全局唯一**的
 *   统一更新弹窗（UnifiedUpdateDialog，由 MainActivity 渲染，本页只负责触发检查并 show()）；
 * - 「项目」卡片组：开源地址 / 作者博客（blog.losn.cc）。
 *
 * 2026-08-27：
 * - 删除「设备信息」组（固件/基带/型号）——固件与基带首页 HomeDeviceInfoCard 已展示，设备型号是本机
 *   Build.MODEL 而非 UFI 设备，放在「关于 UFI-AXIS」里语义错位；
 * - 删除「工具 → 日志管理」入口，日志页只保留版本号连点这一条隐藏入口（调试日志开关随之迁进日志页本身）。
 *
 * 调试入口的解锁状态用 [AppPreferences.debugEntryUnlocked]（**纯本机 UI 门禁**）。
 * 2026-09-04：此前这里复用的是 [AppPreferences.debugMode] —— 那是「日志详细级别」，真源在 core。
 * 结果连点解锁会顺手把全量 DEBUG/INFO 落盘打开（只写本地、不下发，与 core 分叉），
 * 而下一次 `refreshDeviceConfig()` 又会用 core 的 false 把解锁状态清掉。
 * 现在两者彻底分开：解锁只是本机的显形开关，日志开关只在日志页里改并下发给 core。
 *
 * 2026-08-30：调试模式激活后额外显形「调试」卡片组（调试日志 / 运行诊断）。
 * 2026-10-14：诊断中心在**本页**浮到常显（见下），"运行诊断只经隐藏调试入口进入"那句口径
 *   就此作废。它**只有本页这一个常显入口** —— 中途一度在设置页（Hero 卡正下方）补过一颗
 *   浅入口，同日按用户要求撤回（原话：「诊断中心入口放在关于界面，**不要在设置界面
 *   （Hero 卡正下方）出现**」）。调试组里那条「运行诊断」保留（同一个页面，不是第二个诊断页）。
 * 2026-09-26：该组首行补了「调试模式」开关 —— 解锁此前是**单向**的（只有 `= true`），
 * 关不掉，用户只能清数据才能把调试入口收回去。
 *
 * 2026-10-14：新增两颗**常显**行卡 —— 「诊断中心」（→ [Routes.DETAIL_DIAGNOSE]）与
 * 「赞助与感谢」（→ [Routes.DETAIL_SPONSORS]）。
 *  · 诊断中心从隐藏调试组里"浮"到常显：那一组要连点版本号 5 次才出现，而"服务不对劲"
 *    正是最需要它的时候 —— 用户不会先去解锁一个隐藏入口。
 *  · 「赞助与感谢」= 赞助名单（→ [Routes.DETAIL_SPONSORS]），与赞赏码是两件事。
 *
 * 2026-10-14（同日收口）：**赞赏恢复成"按钮 + 弹窗"，而且只有那一颗按钮**。
 * 这一块反复过一轮，结论钉在这里，别再改回去：
 *  · 中途曾把赞赏码拆成独立整页 `DonateScreen`（路由 `detail/donate`），本页再加一颗
 *    「赞赏」行卡作为入口；
 *  · 用户随后报障并明确要求：「赞赏按钮打开闪退，app 端恢复之前的赞赏按钮修改，
 *    **不需要额外的赞赏入口，只需要按钮即可**」—— 于是：
 *      - 删掉本页那颗「赞赏」行卡（就是"额外的赞赏入口"）；
 *      - 那颗 `UfiButtonVariant.Donate` 按钮改回**打开赞赏弹窗**（两张码：支付宝 / 微信），
 *        见 [DonateDialog]；
 *      - `DonateScreen.kt` / `Routes.DETAIL_DONATE` / `appRoutes` 条目 / `AppScreens` 登记
 *        **四处一并删除**。按钮不再指向它，留着就是一条没有任何入口的死路由；更要紧的是
 *        它会成为同一批 1260px 位图的**第二个解码 / 渲染点**，而"打开赞赏就崩"正是那种
 *        多点渲染才复发的问题（见下面那三个私有件的注释）。
 *  · 全仓**只有** [DonateDialog] 一处渲染那两张赞赏码位图。
 *
 * 2026-10-14（同日）：再补一颗常显行卡「资源中心」（→ [Routes.DETAIL_RESOURCES]）。
 * 该页此前**只**挂在内网穿透设置与服务器配置里，那两处都是"缺了组件 / 面板坏了，顺路去装"
 * 的上下文入口 —— 缺一条**与具体故障无关**的常规入口，用户不点进内网穿透就看不到这一页。
 * 内网穿透 / 服务器配置那两处上下文入口**保留不动**（用途不同：那两处是"正好在这儿，顺手装"，
 * 这里这颗是"我要找可下载的资源"）。详见下方行卡处的注释。
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
    // 2026-09-06：本页不再持有「更新弹窗是否可见」的布尔开关，也不再自己渲染更新弹窗 ——
    // 可见性的唯一真源是 viewModel.updatePrompt（UpdatePromptModule），弹窗实例只有一个，
    // 挂在 MainActivity 上。本页点「检查更新」只做两件事：触发检查、调 show() 打开那个槽。
    // 原来这里是 `var showUpdateCheckDialog by remember`，与 MainActivity 的
    // `var showUpdateDialog by remember` 互不知情，两个弹窗会叠加成两层 scrim。
    //
    // checkRequested 保留：它不是弹窗开关，而是"本次停留里用户主动点过检查"的闸 ——
    // frontendUpdateState 是 ViewModel 级的，上次检查的结果会留在里面，
    // 没有这个闸，进关于页的第一帧就会因为旧 state 弹 toast / 打开弹窗。
    var checkRequested by remember { mutableStateOf(false) }
    var showUpdateSettingsDialog by remember { mutableStateOf(false) }
    var showApkPushDialog by remember { mutableStateOf(false) }
    // 赞赏弹窗（2026-10-14 恢复）：本页那颗「赞赏」按钮**唯一**的动作。
    //
    // ★ 它同时是解码闸门：`visible == false` 时一张位图都不解（见 [rememberDonateQrBitmaps]
    //   的 `enabled`）。两张 1260px 级的码合计 565K，进关于页就同步解会在首帧顿一下 ——
    //   而"进关于页"比"点赞赏"频繁得多，所以解码必须挂在弹窗可见性上，不能挂在页面组合上。
    var showDonateDialog by remember { mutableStateOf(false) }
    var updateSourceMode by remember { mutableStateOf(prefs.updateSourceMode) }
    // 打开「更新设置」那一刻的下载方式快照，用于关闭时判断是否真的改过。
    // 之前是无条件同步：每次点开再关就打一次设备写请求，还弹一次 toast；设备不在线时
    // 更是每次都弹「同步失败」，而用户其实什么都没改。
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

    // 调试模式激活状态 + 版本号点击计数（对齐旧项目 AboutActivity：1.5s 内连续点击）
    var debugActivated by remember { mutableStateOf(prefs.debugEntryUnlocked) }
    var versionClickCount by remember { mutableIntStateOf(0) }
    var versionClickLastTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        // 2026-09-14：更新期间这两件事都不能做。
        // 用户在等设备升级时最可能停留的就是本页，而返回再进来会重跑这个 LaunchedEffect：
        // - resetUpdateDeviceState() 会清掉进度状态并**停掉轮询** —— 那是「读到安装结果」的
        //   唯一通道，掐掉之后设备端状态机就再没人能把它从 installing 里领出来
        //   （详见 core 的 UpdateManager.selfHealStuckState）；
        // - checkForUpdate() 此刻必然失败，只会多冒一句「检查更新失败」。
        if (viewModel.tools.coreUpdating.value) return@LaunchedEffect
        // P0：进入关于页时重置推送状态，防止残留 uploading/installing 导致弹窗误显示
        viewModel.tools.resetUpdateDeviceState()
        // 补拉一次后端版本：UpdateState 是 ViewModel 级的，App 启动那次若后端不可达，
        // catch 分支会把 serverVersion 重置为 null 且不再自动重试。只是一个轻量 GET。
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
                // launchSingleTop：连点版本号会一次点一次 navigate，不加就会往返回栈里堆
                // 多个日志页实例（表现为「重复进入」，退出要按好几次返回）。
                versionClickCount = 0
                navController.navigate(Routes.DETAIL_DEBUG_LOG) { launchSingleTop = true }
            }
            versionClickCount >= 5 -> {
                // 只解锁本机的调试入口显形；**不动**日志开关（那是 core 的 debug_mode，
                // 要改请去日志页，那里会下发给 core 并回读确认）
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
                    // 后端 Core 版本单独一行：前端与后端是两个独立发版的 APK（version.json 里
                    // frontend / backend 各一个对象），版本号经常不一致，只显示前端的会让人误判。
                    // 拿不到时显示「未连接」而不是隐藏 —— 空着会让人以为这一行不存在。
                    Text(
                        text = "Core ${serverUpdateState.serverVersion ?: "未连接"}",
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary.copy(alpha = 0.7f)
                    )
                    // 折合简介：与 README / 仓库 About 同一口径的一句话定位。
                    // 放在版本号下方而不是单独开一组——「关于」页顶部本来就是身份区（图标 + 名称 + 版本），
                    // 简介属于同一块信息，单独成组会让首屏出现两个都在讲「这是什么」的卡片。
                    // 注意：不要在这段里塞技术栈或模块清单，那些在仓库 README 里，写进 App 只会随迭代过期。
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

                    // ── 赞赏入口（对齐旧项目 activity_about 的 btn_donate）──
                    // 走公共 UfiButton 的 Donate 档（DonatePink 描边，几何同 Secondary），
                    // 而不是手搓一个 Row+Icon 的伪按钮：关于页只有这一个可点操作，
                    // 几何必须和全站按钮一致，只有配色是刻意例外。
                    //
                    // 2026-10-14：动作回到**打开赞赏弹窗**（[DonateDialog]，两张码：支付宝 / 微信）。
                    // 中途改成跳 `detail/donate` 整页的那一版已随该页一起删除 —— 用户要的是
                    // 「不需要额外的赞赏入口，只需要按钮即可」，所以这里也是全页唯一的赞赏入口
                    //（原来下方还有一颗同样指向赞赏码的「赞赏」行卡，已删）。
                    // 按钮本身、Donate 档与 DonatePink 配色都不动。
                    Spacer(Modifier.height(Spacing.Large))
                    UfiButton(
                        text = "赞赏",
                        icon = AppIconHeart,
                        variant = UfiButtonVariant.Donate,
                        size = UfiButtonSize.Small,
                        onClick = { showDonateDialog = true }
                    )
                    // 底部不再补 Spacer：UfiSettingsGroup 自身已有 Spacing.CardPadding（20dp）内距，
                    // 再加一个 Medium 会让卡片下缘空出 28dp，整张身份卡看着重心下坠。
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
                // 「检查更新」放进卡片内部：原来它是卡片之间的一个独立按钮，
                // 左右靠 CardHorizontalMargin 对齐但上下间距与卡片间距叠加，看着像浮在两组之间。
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
            // 检查完成决策：App 侧无更新→toast；有更新→打开**统一更新弹窗**那个唯一的槽
            //（弹窗实例在 MainActivity；本页只 show()，所以不可能与自动提示叠加）。
            // 必须由 checkRequested 把闸——LaunchedEffect 首次组合就会跑一遍，
            // 上次检查留下的 state（例如 no_update）会让人一进关于页就弹一个莫名其妙的提示。
            //
            // no_update 走 toast 而不是打开弹窗：core 那侧若有更新，自动提示已经弹过一次了，
            // 这里再开一个"App 已最新 + Core 有更新"的弹窗属于重复打扰。
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
            // 放在关于页而不是设置一级页：它是整机维护动作，与设置页那些"改某一项配置"的入口
            // 不是同一类；和更新一样，一年用不到几次。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Filled.Backup,
                    title = "备份与恢复",
                    description = "导出配置备份包 · 从备份文件恢复",
                    onClick = {
                        navController.navigate(Routes.DETAIL_BACKUP_RESTORE) { launchSingleTop = true }
                    },
                    trailing = { UfiSettingsChevron() }
                )
            }

            // ══════════ 诊断中心（2026-10-14 从隐藏调试组浮到常显）══════════
            // 为什么不继续只留隐藏入口：那一组要连点版本号 5 次才出现，而"服务不对劲"正是
            // 最需要这一页的时候 —— 用户不会先去解锁一个隐藏入口。诊断中心的意义就是
            // 服务不对劲时用它，所以它必须在**不需要解锁**的地方有一个入口。
            // （路由复用既有的 Routes.DETAIL_DIAGNOSE，没有第二个诊断页。）
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                    title = "诊断中心",
                    description = "设备权限 · 网络连通 · 后台服务 · 导出诊断包",
                    onClick = {
                        navController.navigate(Routes.DETAIL_DIAGNOSE) { launchSingleTop = true }
                    },
                    trailing = { UfiSettingsChevron() }
                )
            }

            // ══════════ 资源中心（2026-10-14）══════════
            // 为什么在这里补一颗：资源中心此前**只**挂在内网穿透设置与服务器配置里，
            // 那两处都是"缺了 frpc / 面板坏了，顺路去装"的上下文入口 —— 也就是说，
            // 一个还没有具体故障、只是想找"能下载安装的东西在哪"的用户，在 App 里
            // 没有任何一条入口可走（用户报障："资源中心等新界面在 App 端没有入口"）。
            // 这一颗就是那条**与具体故障无关**的常规入口。
            //
            // 那两处上下文入口**不删**：它们的价值是"正好在这一页，顺手就能装"，
            // 与本颗"我要找可下载的资源"是两个不同的进入意图；资源中心是同一个页面，
            // 多入口不会造成两个页面各说一套（本仓忌讳的是同一件事拆成两个页面）。
            //
            // 描述必须自己说清这一页是干什么的：用户是带着"我要装 frpc"或"面板要换回
            // 内置版"这样的具体目的来的，所以逐项列出它管的东西（Web 前端 ZIP /
            // frpc / cloudflared），而不是写成"资源中心"四个字的同义反复。
            //
            // 图标复用仓内已有的 Icons.Filled.CloudDownload（工具页「下载管理」同款）：
            // 语义是"把东西取到设备上"，正是这一页的动作；不引入新图标依赖。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Filled.CloudDownload,
                    title = "资源中心",
                    description = "Web 前端资源 ZIP · frpc / cloudflared 的下载、安装与卸载",
                    onClick = {
                        navController.navigate(Routes.DETAIL_RESOURCES) { launchSingleTop = true }
                    },
                    trailing = { UfiSettingsChevron() }
                )
            }

            // ══════════ 赞助与感谢（2026-10-14）══════════
            // 这里是**名单**，不是赞赏码 —— 码在那颗「赞赏」按钮的弹窗里（[DonateDialog]）。
            // 2026-10-14：原来它上面还有一颗同样指向赞赏码的「赞赏」行卡，已按用户要求删除
            //（「不需要额外的赞赏入口，只需要按钮即可」）。本页因此只剩这一颗名单入口。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Filled.FavoriteBorder,
                    title = "赞助与感谢",
                    description = "感谢名单",
                    onClick = {
                        navController.navigate(Routes.DETAIL_SPONSORS) { launchSingleTop = true }
                    },
                    trailing = { UfiSettingsChevron() }
                )
            }

            // ══════════ 调试（隐藏组：仅在版本号连点 5 次激活调试模式后出现）══════════
            // 为什么不做成「连点直接跳诊断页」：连点这一个手势已经被「进日志页」占了，
            // 再叠一个目的地就没法区分意图。改成激活后显形一组入口，常规界面依旧看不到。
            if (debugActivated) {
                UfiSettingsGroup {
                    UfiGroupHeader("调试")
                    // 2026-09-26：补一个能**关**的开关。此前 `debugEntryUnlocked` 全仓只有
                    // 连点解锁那一处 `= true`，没有任何 set false 路径 —— 一旦解锁，调试组就
                    // 永久显形，用户只能清数据才收得回去。
                    // 开关连同整组一起收起是预期的：这一组本来就是隐藏入口，藏起来之后靠
                    // 连点版本号 5 次重新解锁，不需要再给它留一个常显容器。
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
                                        // 归零连点计数：关掉的这一下不该算进"再点 N 次解锁"里，
                                        // 否则用户手上残留的计数会让下一次解锁提前触发
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
                            navController.navigate(Routes.DETAIL_DEBUG_LOG) { launchSingleTop = true }
                        }
                    )
                    UfiDivider()
                    // 2026-10-20：删除「运行诊断」入口 —— 与「诊断中心」重复（同一个页面）。
                    // 2026-09-05 从「设置 → 外观」搬来：组件画廊是开发自用的组件预览页
                    //（按族铺开 + 明暗对照），普通用户用不到，不该常驻在外观设置里。
                    UfiSettingsItem(
                        title = "组件画廊",
                        description = "预览全部共享组件 · 可切换明暗对照",
                        modifier = Modifier.clickable {
                            navController.navigate(Routes.DETAIL_UI_GALLERY) { launchSingleTop = true }
                        }
                    )
                }
            }

            // ══════════ 项目（对齐设置页：每项一张独立行卡 + 图标 + 右侧 chevron）══════════
            // 原来是「UfiSettingsGroup + UfiGroupHeader("项目") + 两行 + 分隔线」，
            // 但这两项各自是一个跳外链的独立入口、彼此无从属关系，套一个分组标题反而像"项目"是个设置类目。
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
            // 2026-09-06 开源许可入口。
            // 为什么必须有：core APK 打包了 aria2c / socat 等 GPL 系预编译二进制
            //（core/src/main/assets/shell/），分发时有"附许可证 + 提供对应源码"的义务，
            // 而这个义务最终要在**用户能到达的地方**兑现。app 与 core 是同一仓库的两个产物，
            // 所以两端的「关于」都放一个入口指向同一份声明（web 侧见 AboutPanel.vue）。
            // 走外链而不是内置页面：声明文件在 core 的 assets 里，app 读不到；
            // 而它需要随二进制版本更新，放 GitHub 上是唯一真源。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Default.Gavel,
                    title = "开源许可",
                    description = "第三方组件与许可证声明",
                    onClick = { openLink(THIRD_PARTY_NOTICES_URL) },
                    trailing = { UfiSettingsChevron() }
                )
            }

            // ══════════ 页脚署名（对齐旧项目 UFITOOLS-Widget activity_about 底部那行）══════════
            // 低对比度、居中、不可点击——它是签名不是入口，做成可点会引导用户去点。
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
            // 「检查结果」弹窗不在这里 —— 它是全局唯一的 UnifiedUpdateDialog，挂在 MainActivity 上，
            // 本页通过 viewModel.updatePrompt.show() 打开同一个槽。若在这里再渲染一个实例，
            // 同一个 visible 会同时点亮两个平台 Window，等于把叠加问题原样搬回来。
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
                    // 关闭时**仅在下载方式确有变化**才下发（2026-09-05）。
                    // 原来是无条件同步：点开看一眼再关也打一次设备写请求 + 弹一次 toast；
                    // 设备不在线时更是每次都弹「同步失败」，而用户其实什么都没改。
                    onDismiss = {
                        showUpdateSettingsDialog = false
                        if (updateSourceMode != modeOnOpen) {
                            viewModel.tools.syncUpdateModeToDevice(updateSourceMode) { ok ->
                                toastMessage = ToastMessage(
                                    if (ok) "已同步更新源设置到设备" else "设备未连接，同步失败",
                                    if (ok) ToastType.SUCCESS else ToastType.ERROR
                                )
                                // 同步失败时不要把快照推进到新值，否则下次关闭会被判成"没改过"而永远不再重试
                                if (ok) modeOnOpen = updateSourceMode
                            }
                        }
                    }

                )
            }

            // ══════════ 赞赏弹窗（支付宝 / 微信两张码）══════════
            // 挂在 UfiPageBackground 内部只是"跟着页面组合"的位置选择 —— 弹窗渲染在**自己的
            // 平台 Window** 里（见 UfiDialogShell），所以它不受本页那一层 verticalScroll 影响；
            // 反过来说，弹窗**内部**的滚动也绝不能与本页那一层发生嵌套（见 [DonateDialog]）。
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
 * 单一真源在 `core/src/main/assets/shell/THIRD-PARTY-NOTICES.md`（声明的是 core 打包的
 * 那几个预编译二进制），这里只指过去 —— 声明会随二进制版本变化，抄一份进 app 必然漂移。
 */
private const val THIRD_PARTY_NOTICES_URL =
    "https://github.com/Asunano/UFI-AXIS/blob/main/core/src/main/assets/shell/THIRD-PARTY-NOTICES.md"

// ────────────────────────── 赞赏弹窗（两张赞赏码） ──────────────────────────
//
// 2026-10-14：`DONATE_QR_WIDTH_FRACTION` / `rememberDonateQrBitmaps` / `decodeDownsampledBitmap`
// 三个私有件回到本文件 —— 它们中途随赞赏码搬去过 `SponsorsScreen.kt`，再搬去独立的
// `DonateScreen.kt`；用户要求「不需要额外的赞赏入口，只需要按钮即可」之后那一页被删除，
// 于是又搬回来，服务本文件里的 [DonateDialog]。**全仓只有这一处解码与渲染那两张位图** ——
// 多点渲染正是「打开赞赏就崩」会复发的成因（见 [rememberDonateQrBitmaps] 的三层设防）。

/**
 * 赞赏弹窗：两张赞赏码（**左支付宝、右微信**）+ 一句"赞赏完全自愿"的说明。
 *
 * ## ★ 高度与滚动：这里只滚一层，而且是弹窗自带的那一层
 * [UfiScrollableDialog] 的 content 槽**本身**就是一个可滚动的 `Column`：它先把内容
 * `heightIn(max = maxScrollH)` 夹到有限高度（`maxScrollH = 0.82×屏高 − 标题 − 页脚`，
 * 见 `UfiCustomDialog.kt`），再套一层 `verticalScroll`。所以：
 *  · 本弹窗的内容里**一个 `verticalScroll` / `LazyColumn` 都没有** —— 有的话就是
 *    「纵向可滚动套纵向可滚动」，正是本次崩溃（2026-10-14）的形态，而弹窗比整页更容易犯；
 *  · 窄屏上下堆叠两张竖版码、内容超出 maxScrollH 时，由**弹窗那一层**滚；
 *    实测排布见下，正常机型上两张并排时根本到不了这个上限。
 * 结论：**永远只滚一层，且那一层属于弹窗**。不要为了"更好看"在这里加任何滚动容器。
 *
 * ## 高度怎么给（两张图比例不同，这是本弹窗最容易写错的地方）
 * 支付宝 1260×1890（≈ 1:1.50）、微信 1308×1781（≈ 1:1.36）。所以：
 *  · 并排时**绝不**给同一个固定高度（如 `Modifier.height(200.dp)`）—— 固定高度只会把其中
 *    一张拉伸/压扁，而**压扁的二维码扫不出来**（模块从正方变成矩形，识别率断崖式下降）；
 *  · 两张都走 `fillMaxWidth()` + `ContentScale.FillWidth`：只给宽度，高度由**这张图自己的**
 *    宽高比算出来（= web 侧 `width` + `height: auto` 的等价写法）。并排时两张高度因此本来就
 *    不一样，这是正确结果，**不要去"对齐"它们**。
 *  · `ContentScale.FillWidth` 在无界高度下也是安全的：它算缩放只用到宽度，高度由图片自身
 *    比例得出（有限值），所以它不会像 `verticalScroll` 那样被 Infinity 约束打崩。
 *
 * ## 并排还是上下堆叠：判据只算一次，同时喂给排布与解码
 * 见 [DonateCodeLayout] 与 [donateQrTargetWidthPx]。两处各算一次的话，某个宽度区间会出现
 * "按并排解、按堆叠显示"—— 位图被放大到两倍以上，二维码模块边缘发虚，同样是扫不出来的糊。
 */
@Composable
private fun DonateDialog(visible: Boolean, onDismiss: () -> Unit) {
    val palette = LocalResolvedPalette.current

    // 排布判据：屏宽 ≥ [SIDE_BY_SIDE_MIN_WIDTH_DP] 才并排，否则上下堆叠。
    // 它**同时**决定排布与解码目标宽度（理由见上面那段注释）。
    val sideBySide = LocalConfiguration.current.screenWidthDp >= SIDE_BY_SIDE_MIN_WIDTH_DP

    // ★ 解码闸门：弹窗没打开时 `enabled = false`，一张位图都不解（见 [rememberDonateQrBitmaps]）。
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
            // 自愿与"不影响功能"必须写在码旁边，而不是只写在仓库文档里：用户扫之前要能看见。
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
 * @param aspectRatio **宽 / 高**。两张图不同（1260:1890 ≈ 0.667、1308:1781 ≈ 0.734）——
 *   它同时被用在两处：① 图未解码完时的占位框比例（避免先画一个方框再跳成竖长条）；
 *   ② 解码目标宽度的换算（见 [donateQrTargetWidthPx]）。两处都必须逐张取，不能共用。
 */
private data class DonateCodeSpec(
    val resId: Int,
    val label: String,
    val aspectRatio: Float,
)

/** 两张码：**支付宝在左、微信在右**（组合顺序即视觉顺序，窄屏改成上下堆叠时顺序不变）。 */
private val DONATE_CODES = listOf(
    DonateCodeSpec(resId = R.drawable.img_donate_alipay, label = "支付宝", aspectRatio = 1260f / 1890f),
    DonateCodeSpec(resId = R.drawable.img_donate_wechat, label = "微信", aspectRatio = 1308f / 1781f),
)

/**
 * 并排显示所需的最小屏宽（dp）；低于它就改成上下堆叠（见 [DonateCodeLayout]）。
 *
 * 判据取**屏宽**而不是卡片内容宽：解码目标宽度只能从屏宽推（弹窗里拿不到"内容区到底还剩
 * 多少"），而"怎么排"与"解多大"必须是同一个判据（理由见 [DonateDialog]）。
 * 手机竖屏普遍在 360~412dp，落在堆叠一侧 —— 弹窗内容区约 256~303dp，两张并排各只有
 * 130~150dp，那个尺寸在手机屏幕上不好扫；堆叠后每张能拿到整宽，由弹窗那一层滚动来看。
 */
private const val SIDE_BY_SIDE_MIN_WIDTH_DP = 420

/**
 * 两张码的排布：宽屏并排（支付宝左 / 微信右），窄屏上下堆叠、居中。
 *
 * ★ 本函数**不含任何滚动容器**：它只负责排布。滚不滚由外层的 [UfiScrollableDialog] 决定
 *   （唯一一层），见 [DonateDialog] 的「高度与滚动」。
 *
 * @param sideBySide 由 [DonateDialog] 统一判定后传进来 —— 这里**不再自己算一次**
 *   （两处判据一旦漂移，就会出现"按并排解、按堆叠显示"的糊码，见那边的注释）。
 */
@Composable
private fun DonateCodeLayout(codes: List<ImageBitmap?>, sideBySide: Boolean) {
    if (sideBySide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
            // 顶对齐：两张的高度不同（比例不同），居中会让其中一张看起来"没对齐"
            verticalAlignment = Alignment.Top
        ) {
            DONATE_CODES.forEachIndexed { index, spec ->
                DonateCodeColumn(
                    spec = spec,
                    bitmap = codes.getOrNull(index),
                    // 两张**等宽**：宽度是排布决定的，高度才由各自的比例决定
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
 * 一张码 + 它下面的渠道名。
 *
 * 图用 `fillMaxWidth()` + [ContentScale.FillWidth]：**只给宽度，高度按这张图自己的比例出来**
 * （等价于 web 侧的 `width` + `height: auto`）。刻意不写任何 `.height(...)` ——
 * 两张比例不同，一个固定高度就是压扁其中一张（见 [DonateDialog]）。
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
            // 解码在 IO 线程，首帧会空一下；按**这张图自己的**比例先占住位置
            // （两张比例不同，占位高度也就不同 —— 这不是 bug，见 DonateCodeSpec.aspectRatio）。
            // `aspectRatio` 在无界高度下安全：它先试"按 maxWidth 定宽"，宽度有界就能算出有限高度。
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
        // 渠道名标在**每张码下面**，而不是顶部统一说明：并排时用户要能一眼看出哪个是哪个
        Text(
            text = spec.label,
            style = UfiTextStyles.bodyEmphasis,
            color = palette.textPrimary
        )
    }
}

/**
 * 弹窗内容区宽度占屏宽的**上界**比例（只用于算解码目标宽度）。
 *
 * 弹窗内容宽 = `0.90 × 屏宽`（`UfiDialogShell` 的 `fillMaxWidth(0.90f)`）
 *            − `32dp`（shell 的 `padding(horizontal = 16.dp)`）
 *            − `36dp`（卡片内边距 `Spacing.DialogPaddingH = 18.dp`）
 *          = `0.90 × 屏宽 − 68dp`，在 360~480dp 的机型上约是屏宽的 0.71~0.74 倍。
 *
 * 这里取 0.78 —— 刻意取**上界**，因为两个方向的代价不对称：
 *  · 取大了只是多解几百 KB（解码尺寸 ≥ 显示尺寸，不会被二次缩放）；
 *  · 取小了位图小于显示尺寸，会被放大 —— 二维码模块边缘发虚，**扫不出来**。
 * 宁可偏大。这段算式与 shell 的实际几何绑在一起，改 `UfiDialogShell` 的宽度或内边距时
 * 必须回来核对（本文件拿不到它的测量值，只能这样表达）。
 */
private const val DONATE_QR_WIDTH_FRACTION = 0.78f

/**
 * 单列（上下堆叠）时允许的解码高度上限（占屏高的比例）。
 *
 * 堆叠时一张码的显示高度约是弹窗内容宽的 1.5 倍，长屏上会到屏高的一半以上。它由弹窗滚动
 * 承载，但没必要为此解一张比屏幕还高的位图 —— 超过这个上限就按屏高折回宽度。
 * 取值刻意宽松（0.9），正常机型上不会触发，它只挡极端比例。
 */
private const val DONATE_QR_MAX_HEIGHT_FRACTION = 0.9f

/**
 * 把两张赞赏码解码成 [ImageBitmap]（全仓**唯一**解码它们的地方）。
 *
 * **为什么不能直接 `painterResource`**：这两张图是 1260×1890 与 1308×1781 的 PNG。若放在普通
 * `drawable/` 下，aapt 按 mdpi 基线对待，`decodeResource` 会按设备密度放大 —— 3x 机型上
 * 变成 3780×5670 与 3924×5343，ARGB_8888 各约 80MB，一张图就能把一次 Activity 的位图预算
 * 吃穿（旧项目遇到的「打开赞赏就崩」正是这个成因）。
 *
 * 三层设防（**全部保留**，缺一层这个弹窗就会重新变成崩溃源）：
 * 1. 资源放在 `res/drawable-nodpi/` —— 从源头禁止密度缩放；
 * 2. 按弹窗内容宽度降采样，并用 `RGB_565`（赞赏码是纯黑白，565 无可见损失，内存砍半）；
 * 3. 解码放到 [Dispatchers.IO]。
 *
 * @param enabled **解码闸门**：只有弹窗真的打开时才解（`false` → 直接回空列表）。
 *   两张图合计 565K，进关于页就同步解两张 1260px 级的位图会让首帧顿一下；而"进关于页"
 *   比"点赞赏"频繁得多。关掉弹窗时列表被清空，位图随即可回收。
 * @param sideBySide 排布判据，由 [DonateDialog] 算好后传进来（**不要**在这里重算一次，
 *   理由见那边的注释）。它进 [produceState] 的 key：横竖屏切换导致排布变化时会重新解码。
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
        // 闸门在这一层：没打开就什么都不做，也不持有任何位图
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
 * 算出**这一张**码在当前排布下的解码目标宽度（像素）。
 *
 * 宽度来自排布（并排 = 内容区的一半，堆叠 = 整宽），但**逐张**算，因为两张图的宽高比不同：
 * 支付宝 1260:1890（≈ 1:1.50）、微信 1308:1781（≈ 1:1.36）。同一个显示宽度下左边那张要高 10%，
 * 所以"高度上限"折回来的宽度对两张是**两个不同的数**；共用一个数会让其中一张要么被二次缩放
 * （糊，扫描识别率下降），要么白占内存。同一个目标宽度下，`inSampleSize` 也会按各自的源宽度
 * （1260 / 1308）分别算出来（见 [decodeDownsampledBitmap] 的循环）。
 *
 * 两个约束取小：
 *  · **显示宽度**：保证解码尺寸 ≥ 显示尺寸（这是"不被二次缩放"的下限）；
 *  · **高度上限**：堆叠时一张码的显示高度会到内容宽的 1.5 倍上下，超过
 *    [DONATE_QR_MAX_HEIGHT_FRACTION] 就按屏高折回宽度（只挡极端比例，正常机型不触发）。
 */
private fun donateQrTargetWidthPx(
    spec: DonateCodeSpec,
    screenWidthDp: Int,
    screenHeightDp: Int,
    density: Float,
    sideBySide: Boolean,
): Int {
    val cardWidthPx = screenWidthDp * density * DONATE_QR_WIDTH_FRACTION
    // 并排时先扣掉中间那条间距再对半。间距直接取 `Spacing.Large.value` ——
    // DonateCodeLayout 用的就是 `Arrangement.spacedBy(Spacing.Large)`，
    // 抄一个常量过来迟早会和它分叉（差一点点就开始糊）。
    val displayWidthPx =
        if (sideBySide) (cardWidthPx - Spacing.Large.value * density) / 2f else cardWidthPx
    // 宽度 → 高度用**这张图自己的**比例（aspectRatio = 宽 / 高）
    val maxHeightPx = screenHeightDp * density * DONATE_QR_MAX_HEIGHT_FRACTION
    val heightCappedWidthPx = maxHeightPx * spec.aspectRatio
    return minOf(displayWidthPx, heightCappedWidthPx).toInt().coerceAtLeast(1)
}

/**
 * 降采样解码：先只读尺寸算 `inSampleSize`（必须是 2 的幂），再正式解码。
 * `inScaled = false` + `openRawResource` 一起用，跳过 `decodeResource` 的密度缩放。
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
 * 用 stroke 而非 fill，是为了跟 Donate 档那个描边按钮同构 —— 实心心形塞在描边按钮里
 * 会比文字重一截，视觉上像两个不同层级的元素拼在一起。
 * 两瓣用对称的三次贝塞尔起手，收笔汇到 (12, 21) 的尖端。
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
 * 这一个**不手绘**：品牌标识画歪了比不画更糟，所以直接用官方 24×24 路径，
 * 经 [addPathNodes] 解析而不是手抄成 PathBuilder 调用（几十个控制点手抄必错）。
 *
 * 源 SVG 带 `fill-rule="evenodd"`，所以这里必须显式传 [PathFillType.EvenOdd] ——
 * 用默认的 NonZero 会把猫身与尾巴之间的镂空填实，图标变成一个糊掉的实心块。
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
// 2026-09-05（P3 超长行）：由单行 705 字符折成 8 段字面量拼接。
// **拆分只允许发生在原串已有的空格处，且空格留在前一段的末尾** —— 这样 `+` 拼回来的结果
// 与原串逐字节相同（改完当场做过 round-trip 比对）。`const val` 允许编译期常量拼接，
// 所以这仍是一个编译期常量，调用点拿到的东西没变。
//
// ⚠ 不要在数字中间断行、不要为了对齐加/删空白：一处错位会把坐标解析成另一个值，
// 而结果只是"图标看着有点怪"，不会报错，极难回溯。
private const val GITHUB_MARK_PATH =
    "M12 0c6.63 0 12 5.276 12 11.79-.001 5.067-3.29 9.567-8.175 11.187-.6.118-.825-.25-.825-.56 " +
    "0-.398.015-1.665.015-3.242 0-1.105-.375-1.813-.81-2.181 2.67-.295 5.475-1.297 5.475-5.822 " +
    "0-1.297-.465-2.344-1.23-3.169.12-.295.54-1.503-.12-3.125 0 0-1.005-.324-3.3 1.209a11.32 11.32 0 " +
    "00-3-.398c-1.02 0-2.04.133-3 .398-2.295-1.518-3.3-1.209-3.3-1.209-.66 1.622-.24 2.83-.12 " +
    "3.125-.765.825-1.23 1.887-1.23 3.169 0 4.51 2.79 5.527 5.46 5.822-.345.294-.66.81-.765 " +
    "1.577-.69.31-2.415.81-3.495-.973-.225-.354-.9-1.223-1.845-1.209-1.005.015-.405.56.015.781.51.28 " +
    "1.095 1.327 1.23 1.666.24.663 1.02 1.93 4.035 1.385 0 .988.015 1.916.015 2.196 0 " +
    ".31-.225.664-.825.56C3.303 21.374-.003 16.867 0 11.791 0 5.276 5.37 0 12 0z"



