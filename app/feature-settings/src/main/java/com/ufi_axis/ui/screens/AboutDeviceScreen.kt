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
import com.ufi_axis.util.GeoDetector
import com.ufi_axis.util.UpdateSource
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.ToolsModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * 运行诊断（Routes.DETAIL_DIAGNOSE）只经这条隐藏入口进入，设置页不再放常规入口。
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
    var showDonateDialog by remember { mutableStateOf(false) }
    // 赞赏码位图的"已请求过"闸门：一旦点开过就保持 true。
    // 不直接用 showDonateDialog 当解码开关 —— 关闭弹窗时它先变 false，位图随之置空，
    // 而弹窗的退场动画仍在渲染 content，于是关闭瞬间会闪一下加载动画。
    var donateQrRequested by remember { mutableStateOf(false) }
    var updateSourceMode by remember { mutableStateOf(prefs.updateSourceMode) }
    // 打开「更新设置」那一刻的 mirrorBase 快照，用于关闭时判断是否真的改过。
    // 之前是无条件同步：每次点开再关就打一次设备写请求，还弹一次 toast；设备不在线时
    // 更是每次都弹「同步失败」，而用户其实什么都没改。
    var mirrorBaseOnOpen by remember { mutableStateOf("") }
    var lastCountry by remember { mutableStateOf(prefs.lastCountry) }
    var detectingCountry by remember { mutableStateOf(false) }
    var autoCheckUpdate by remember { mutableStateOf(prefs.autoCheckUpdate) }
    val scope = rememberCoroutineScope()

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
        // P0：进入关于页时重置推送状态，防止残留 uploading/installing 导致弹窗误显示
        viewModel.tools.resetUpdateDeviceState()
        // 补拉一次后端版本：UpdateState 是 ViewModel 级的，App 启动那次若后端不可达，
        // catch 分支会把 serverVersion 重置为 null 且不再自动重试。只是一个轻量 GET。
        viewModel.dashboard.checkForUpdate()
    }

    /** 当前生效的镜像前缀（同步给设备的 `update_mirror_base`）；直连模式为空串。 */
    fun currentMirrorBase(mode: String): String =
        if (mode == UpdateSource.MODE_DIRECT) "" else (UpdateSource.selectedMirrorPrefix(prefs) ?: "")


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
                    Spacer(Modifier.height(Spacing.Large))
                    UfiButton(
                        text = "赞赏",
                        icon = AppIconHeart,
                        variant = UfiButtonVariant.Donate,
                        size = UfiButtonSize.Small,
                        onClick = {
                            donateQrRequested = true
                            showDonateDialog = true
                        }
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
                        // 记下打开时的镜像前缀，关闭时据此判断是否需要下发（见 onDismiss）
                        mirrorBaseOnOpen = currentMirrorBase(updateSourceMode)
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

            // ══════════ 调试（隐藏组：仅在版本号连点 5 次激活调试模式后出现）══════════
            // 为什么不做成「连点直接跳诊断页」：连点这一个手势已经被「进日志页」占了，
            // 再叠一个目的地就没法区分意图。改成激活后显形一组入口，常规界面依旧看不到。
            if (debugActivated) {
                UfiSettingsGroup {
                    UfiGroupHeader("调试")
                    UfiSettingsItem(
                        title = "调试日志",
                        description = "抓包记录 · 日志开关",
                        modifier = Modifier.clickable {
                            navController.navigate(Routes.DETAIL_DEBUG_LOG) { launchSingleTop = true }
                        }
                    )
                    UfiDivider()
                    UfiSettingsItem(
                        title = "运行诊断",
                        description = "特权 shell · 字段覆盖 · QoS · 响应缓存",
                        modifier = Modifier.clickable {
                            navController.navigate(Routes.DETAIL_DIAGNOSE) { launchSingleTop = true }
                        }
                    )
                    UfiDivider()
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

            // ══════════ 赞赏弹窗（微信 / 支付宝合图，滚动查看）══════════
            // 位图跟 donateQrRequested 走：首次点开才解码，之后一直留着直到离开关于页 ——
            // 若跟 visible 走，关闭时位图先变 null 而退场动画还在渲染 content，会闪一下加载动画。
            val donateQr = rememberDonateQrBitmap(donateQrRequested)
            UfiScrollableDialog(
                visible = showDonateDialog,
                onDismiss = { showDonateDialog = false },
                title = "赞赏"
            ) {
                Text(
                    text = "如果你喜欢这个软件，可以考虑请我喝一杯柠檬水哟~（狗头）下滑支持微信 / 支付宝 💰",
                    style = UfiTextStyles.note,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(Spacing.Large))
                if (donateQr != null) {
                    Image(
                        bitmap = donateQr,
                        contentDescription = "赞赏码",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(UfiCardDefaults.shape)
                    )
                } else {
                    // 解码在 IO 线程，首帧会空一下；居中放个指示器避免弹窗内容左上角空一块
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        UfiLoadingIndicator()
                    }
                }
                Spacer(Modifier.height(Spacing.Medium))
                Text(
                    text = "微信 / 支付宝 扫一扫",
                    style = UfiTextStyles.caption,
                    color = palette.textSecondary.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

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
                    detectingCountry = detectingCountry,
                    onRedetectCountry = {
                        detectingCountry = true
                        scope.launch {
                            GeoDetector.detectCountry()?.let { c ->
                                lastCountry = c
                                prefs.lastCountry = c
                            }
                            detectingCountry = false
                        }
                    },
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
                    // 关闭时**仅在更新源确有变化**才下发（2026-09-05）。
                    // 原来是无条件同步：点开看一眼再关也打一次设备写请求 + 弹一次 toast；
                    // 设备不在线时更是每次都弹「同步失败」，而用户其实什么都没改。
                    onDismiss = {
                        showUpdateSettingsDialog = false
                        val mirrorBase = currentMirrorBase(updateSourceMode)
                        if (mirrorBase != mirrorBaseOnOpen) {
                            viewModel.tools.syncUpdateSourceToDevice(
                                updateUrl = ToolsModule.RAW_VERSION_URL,
                                mirrorBase = mirrorBase
                            ) { ok ->
                                toastMessage = ToastMessage(
                                    if (ok) "已同步更新源设置到设备" else "设备未连接，同步失败",
                                    if (ok) ToastType.SUCCESS else ToastType.ERROR
                                )
                                // 同步失败时不要把快照推进到新值，否则下次关闭会被判成"没改过"而永远不再重试
                                if (ok) mirrorBaseOnOpen = mirrorBase
                            }
                        }
                    }
                )
            }
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

/** 赞赏码目标宽度占屏宽的比例（弹窗内容区约七成屏宽，再宽也只是白边） */
private const val DONATE_QR_WIDTH_FRACTION = 0.72f

/**
 * 第三方组件许可声明的地址。
 *
 * 单一真源在 `core/src/main/assets/shell/THIRD-PARTY-NOTICES.md`（声明的是 core 打包的
 * 那几个预编译二进制），这里只指过去 —— 声明会随二进制版本变化，抄一份进 app 必然漂移。
 */
private const val THIRD_PARTY_NOTICES_URL =
    "https://github.com/Asunano/UFI-AXIS/blob/main/core/src/main/assets/shell/THIRD-PARTY-NOTICES.md"

/**
 * 首次点开赞赏后才把赞赏码解码成 [ImageBitmap]，之后保留到离开关于页。
 *
 * `requested` 是"点过一次就一直为 true"的闸门，而不是弹窗的 visible：
 * 若跟 visible 走，关闭时位图先变 null，而弹窗退场动画仍在渲染 content，会闪一下加载动画。
 *
 * **为什么不能直接 `painterResource`**：这张图是 600×1716 的 JPEG。若放在普通 `drawable/` 下，
 * aapt 按 mdpi 基线对待，`decodeResource` 会按设备密度放大 —— 3x 机型上变成 1800×5148，
 * ARGB_8888 约 37MB，一张图就能把一次 Activity 的位图预算吃穿（旧项目遇到的
 * 「打开赞赏就崩」正是这个成因）。
 *
 * 三层设防：
 * 1. 资源放在 `res/drawable-nodpi/` —— 从源头禁止密度缩放；
 * 2. 按弹窗实际宽度降采样，并用 `RGB_565`（赞赏码是纯黑白，565 无可见损失，内存砍半）；
 * 3. 解码放到 [Dispatchers.IO]，且**只在用户真的点过赞赏后**才做，不随进页面预热。
 */
@Composable
private fun rememberDonateQrBitmap(requested: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return produceState<ImageBitmap?>(null, requested, screenWidthDp, density) {
        if (!requested) {
            value = null
            return@produceState
        }
        val targetPx = (screenWidthDp * density * DONATE_QR_WIDTH_FRACTION).toInt().coerceAtLeast(1)
        value = withContext(Dispatchers.IO) {
            decodeDownsampledBitmap(context, R.drawable.img_donate_qr, targetPx)?.asImageBitmap()
        }
    }.value
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



