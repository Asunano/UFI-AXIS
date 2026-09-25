package com.ufi_axis.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServerConfigScreen(
    viewModel: MainViewModel,
    onServerConfigChanged: () -> Unit,
    navController: NavHostController,
    showHeader: Boolean = true  // 2026-08-11：被外层 ServerScreen Tab 嵌套调用时传 false 避免多重标题栏
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val palette = LocalResolvedPalette.current

    // ── 页面级 Toast 反馈（网关保存成功后提示，替代弹窗内文字） ──
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // ── Dialog visibility states ──
    var showConnectionDialog by remember { mutableStateOf(false) }
    var showGatewayPwdDialog by remember { mutableStateOf(false) }
    var showWebResetConfirm by remember { mutableStateOf(false) }
    var showWebPanelDialog by remember { mutableStateOf(false) }
    var showWebRollbackConfirm by remember { mutableStateOf(false) }

    // ── Web 控制面板资源（2026-08-27）──
    // 上传坏产物后面板本身打不开，面板里那条恢复入口等于没有，所以恢复入口必须放在 app 侧。
    val webAsset by viewModel.network.webAssetState.collectAsState()
    LaunchedEffect(Unit) { viewModel.network.loadWebAssetInfo() }
    // 面板管理弹窗打开时读一次更新状态：本组端点没有 WS 推送，不主动读就永远是 null
    LaunchedEffect(showWebPanelDialog) {
        if (showWebPanelDialog) viewModel.network.loadWebUpdateStatus()
    }

    val scope = rememberCoroutineScope()
    // SAF 选 ZIP → 拷到 cacheDir → multipart 上传（core 只认字段名 "file"）
    val webZipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val tmp = withContext(Dispatchers.IO) {
                runCatching {
                    val f = java.io.File(context.cacheDir, "ufi-web-panel.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    } ?: return@runCatching null
                    f
                }.getOrNull()
            }
            if (tmp == null) {
                toastMessage = ToastMessage("无法读取所选文件", ToastType.ERROR)
                return@launch
            }
            toastMessage = ToastMessage("正在上传面板…", ToastType.INFO)
            val (ok, msg) = viewModel.network.uploadWebAssets(tmp)
            tmp.delete()
            toastMessage = ToastMessage(msg, if (ok) ToastType.SUCCESS else ToastType.ERROR)
        }
    }



    // ── Live display values for entry descriptions ──
    var displayIp by remember { mutableStateOf(prefs.serverIp) }
    var displayPort by remember { mutableStateOf(prefs.serverPort.toString()) }
    var displayGwAddress by remember {
        mutableStateOf("${prefs.gatewayIp}:${prefs.goformPort}")
    }

    // ═══════════ PAGE BODY — 3 entries only ═══════════
    UfiScreenScaffold(title = "服务器配置", navController = navController, showBack = true, showHeader = showHeader) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            UfiPageBackground(modifier = Modifier.fillMaxSize()) {

                // 对齐网络主页布局：每个入口都是独立的行卡（公共 UfiSettingsRowCard），10dp 间距
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── 1. 连接配置（IP / 端口） ──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.SettingsEthernet,
                        title = "连接配置",
                        description = "$displayIp : $displayPort",
                        onClick = { showConnectionDialog = true },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // ── 2. 设备后台（网关地址 + 后台密码 合一） ──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Router,
                        title = "设备后台",
                        description = "$displayGwAddress · Web 管理 API",
                        onClick = { showGatewayPwdDialog = true },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // ── 3. Web 面板（版本 / 检查更新 / 上传 / 回滚 / 恢复内置） ──
                // 2026-08-30：原来这一行只能「恢复内置」，且 bundled 模式下整行不可点。
                // 现在它是面板管理入口，只要读到过版本信息就能点开（进去再按能力禁用各个动作）。
                val webRowEnabled = webAsset.loaded && !webAsset.isBusy
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Web,
                        title = "Web 面板",
                        description = when {
                            !webAsset.loaded -> "读取中…（Core 未连接时不可用）"
                            webAsset.isBusy -> webAsset.updateStatus?.message?.takeIf { it.isNotBlank() }
                                ?: "正在处理…"
                            webAsset.isOverride ->
                                "已上传 ${webAsset.version ?: "未知版本"} · 内置 ${webAsset.bundledVersion ?: "未知"}"
                            else -> "内置版本 ${webAsset.version ?: "未知"}"
                        },
                        // 不可用时既不给点击也不给箭头（与改造前的 clickable(enabled=…) 行为一致）
                        onClick = if (webRowEnabled) ({ showWebPanelDialog = true }) else null,
                        trailing = if (webRowEnabled) ({ UfiSettingsChevron() }) else null
                    )
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

    // ═══════════ DIALOG 1: 连接配置（IP + Port） ═══════════
    // Token 输入框已删除：token 现在是「每设备独占」凭据，由 /pairing/confirm 签发并与本机
    // Keystore 私钥绑定，手填别处的 token 只会因验签失败被判 444。要换凭据只能走「重新配对」。
    var connIp by remember(displayIp) { mutableStateOf(displayIp) }
    var connPort by remember(displayPort) { mutableStateOf(displayPort) }
    var connError by remember { mutableStateOf<String?>(null) }

    UfiCustomDialog(
        visible = showConnectionDialog,
        onDismiss = { showConnectionDialog = false; connError = null },
        title = "连接配置",
        icon = rememberVectorPainter(Icons.Filled.Dns),
        showCloseButton = false
    ) {
        UfiDialogBody {
            Text(
                "配置 Core 后端的连接参数。\n换到另一台设备请先「切换设备 / 退出」再配对，" +
                    "只改 IP 无法带上旧设备的登录凭据。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            UfiDialogTextField(
                label = "Core IP",
                value = connIp,
                onValueChange = { connIp = it; connError = null },
                placeholder = "192.168.11.19"
            )
            UfiDigitField(
                value = connPort,
                onValueChange = { connPort = it; connError = null },
                label = "端口",
                modifier = Modifier.fillMaxWidth()
            )
            connError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = palette.error)
            }
        }
        UfiDialogActions(
            confirmText = "保存连接",
            topSpacing = Spacing.Large,
            onConfirm = {
                val p = connPort.toIntOrNull()
                when {
                    connIp.isBlank() -> connError = "IP 不能为空"
                    !connIp.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) ->
                        connError = "IP 格式不正确"
                    connPort.isBlank() || p == null || p !in 1..65535 ->
                        connError = "端口范围 1-65535"
                    else -> {
                        displayIp = connIp; prefs.serverIp = connIp
                        displayPort = connPort; prefs.serverPort = p
                        // P2：onServerConfigChanged 内部按「IP:端口是否变化」分流
                        // （换机清缓存并重连，同机只轻刷新）。
                        onServerConfigChanged()
                        showConnectionDialog = false
                        // 2026-09-22（阶段 3.5）：补成功提示。这一项**纯本地**（写
                        // AppPreferences，没有请求可失败），所以走 notifyLocalWriteSucceeded
                        // 而不是 module；提示仍落在全局宿主，不在本页另写一份。
                        viewModel.notifyLocalWriteSucceeded(
                            "Core 连接地址已保存",
                            subtitle = "$connIp:$p"
                        )
                    }
                }
            },
            onDismiss = { showConnectionDialog = false; connError = null }
        )
    }

    // ═══════════ DIALOG 2: 设备后台（网关地址 + 后台密码修改） ═══════════
    var gwAddr by remember(displayGwAddress) { mutableStateOf(displayGwAddress) }
    var gwSelectedTab by remember { mutableIntStateOf(0) }
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }
    var gwPwdError by remember { mutableStateOf<String?>(null) }
    // 2026-09-22（阶段 3.2）：请求在飞期间的闸门。之前这里是"点保存 → 立刻关窗 + 弹绿色
    // 「设备后台配置已保存」"，两个请求都还在飞 —— core 没起来、地址被拒、旧密码填错，
    // 界面照样报成功。现在等结果，成功才关窗；失败留在弹窗里（原因由全局错误 Toast 给出）。
    var gwSaving by remember { mutableStateOf(false) }

    UfiScrollableDialog(
        visible = showGatewayPwdDialog,
        onDismiss = {
            showGatewayPwdDialog = false
            gwSelectedTab = 0
            oldPwd = ""; newPwd = ""; confirmPwd = ""; gwPwdError = null
        },
        title = "设备后台",
        icon = rememberVectorPainter(Icons.Filled.Router),
        showCloseButton = false,
        actions = {
            UfiDialogActions(
                confirmText = if (gwSaving) "保存中…" else "保存",
                // loading 只是视觉，重复提交要靠 enabled 挡住（§4.3）
                enabled = !gwSaving,
                loading = gwSaving,
                onConfirm = {
                    // 1) 前置校验（纯本地，不发请求）
                    when {
                        gwAddr.isBlank() -> { gwPwdError = "网关地址不能为空"; return@UfiDialogActions }
                        !gwAddr.contains(":") -> { gwPwdError = "需包含端口号（如 :8080）"; return@UfiDialogActions }
                    }
                    val parts = gwAddr.split(":")
                    val gip = parts.getOrElse(0) { "192.168.0.1" }.ifBlank { "192.168.0.1" }
                    val gport = parts.getOrNull(1)?.toIntOrNull() ?: 8080
                    val wantPasswordChange =
                        oldPwd.isNotEmpty() || newPwd.isNotEmpty() || confirmPwd.isNotEmpty()
                    if (wantPasswordChange) {
                        when {
                            newPwd != confirmPwd -> { gwPwdError = "两次密码不一致"; return@UfiDialogActions }
                            oldPwd.isEmpty() || newPwd.isEmpty() -> { gwPwdError = "密码不能为空"; return@UfiDialogActions }
                        }
                    }

                    gwPwdError = null
                    gwSaving = true
                    scope.launch {
                        // 2) 两个写分属**不同 module**（地址在 tools / 密码在 network），
                        //    各自等自己的结果、各自报自己的成功提示与失败原因。
                        //    串行而不是并发：地址错了就没必要再拿错地址去改密码。
                        val addrOk = viewModel.tools.syncGatewayConfig(gip, gport)
                        val pwdOk = if (addrOk && wantPasswordChange) {
                            viewModel.network.changePassword(oldPwd, newPwd)
                        } else true

                        gwSaving = false
                        if (!addrOk || !pwdOk) return@launch

                        // 3) 只有 core 真的收下之后才落本地缓存与界面摘要 ——
                        //    先写会留下"本地显示新地址、core 还是旧的"这种更难查的分叉。
                        prefs.gatewayIp = gip
                        prefs.goformPort = gport
                        displayGwAddress = gwAddr
                        if (wantPasswordChange) prefs.goformPasswordSet = true

                        showGatewayPwdDialog = false
                        gwSelectedTab = 0
                        oldPwd = ""; newPwd = ""; confirmPwd = ""
                    }
                },
                onDismiss = {
                    showGatewayPwdDialog = false
                    gwSelectedTab = 0
                    oldPwd = ""; newPwd = ""; confirmPwd = ""; gwPwdError = null
                }
            )
        }
    ) {
        UfiDialogBody {
            // 滑块分栏：网关地址 / 后台密码，缩短单屏长度
            UfiScrollableTabRow(
                selectedTabIndex = gwSelectedTab,
                onTabSelected = { gwSelectedTab = it },
                tabs = listOf("网关地址", "后台密码")
            )
            // 间距统一到 UfiDialogBody（12dp）

            // 页签内容切换动画（方向感知：复用公共组件 UfiAnimatedTabContent，参考「关于-更新设置」弹窗）
            UfiAnimatedTabContent(targetState = gwSelectedTab) { tab ->
            when (tab) {
                0 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                        Text("网关地址", style = MaterialTheme.typography.titleSmall,
                            color = palette.accent)
                        Text("设备自带网页后台的地址，用于下发控制指令",
                            style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                    }
                    UfiDialogTextField(
                        label = "网关地址",
                        value = gwAddr,
                        onValueChange = { gwAddr = it; gwPwdError = null },
                        placeholder = "192.168.0.1:8080"
                    )
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                        Text("设备后台密码", style = MaterialTheme.typography.titleSmall,
                            color = palette.accent)
                        Text("随身 WiFi 自带网页后台的登录密码",
                            style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                    }
                    UfiDialogField(label = "当前密码") {
                        UfiPasswordField(
                            value = oldPwd,
                            onValueChange = { oldPwd = it; gwPwdError = null },
                            label = "",
                            showToggle = false
                        )
                    }
                    UfiDialogField(label = "新密码") {
                        UfiPasswordField(
                            value = newPwd,
                            onValueChange = { newPwd = it; gwPwdError = null },
                            label = "",
                            showToggle = false
                        )
                    }
                    UfiDialogField(label = "确认新密码") {
                        UfiPasswordField(
                            value = confirmPwd,
                            onValueChange = { confirmPwd = it; gwPwdError = null },
                            label = "",
                            showToggle = false,
                            isError = confirmPwd.isNotEmpty() && confirmPwd != newPwd,
                            errorMessage = if (confirmPwd.isNotEmpty() && confirmPwd != newPwd) "密码不一致" else null
                        )
                    }
                }
            }
            }

            gwPwdError?.let {
                if (!it.startsWith("已同步")) {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("不一致") || it.contains("不能为空"))
                            palette.error else palette.accent)
                } else {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = palette.accent)
                }
            }
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }

    // ═══════════ DIALOG 3: Web 面板管理（版本 / 检查更新 / 上传 / 回滚 / 恢复内置） ═══════════
    // 为什么这些动作要在 app 里也有一份：它们改的正是「面板本身」，上传了坏产物之后面板打不开，
    // 面板里的同名入口就等于不存在 —— app 是唯一还能救回来的通道。
    UfiCustomDialog(
        visible = showWebPanelDialog,
        onDismiss = { showWebPanelDialog = false },
        title = "Web 面板",
        icon = rememberVectorPainter(Icons.Filled.Web),
        showCloseButton = false
    ) {
        // 关闭动作交给 shell 排时序：离场 backdrop（逐渐清晰）要播完才卸载窗口，
        // 见 LocalUfiDialogClose。这里在 content 顶部读一次，给下方两个"关本弹窗再开二次确认"
        // 的按钮用；「检查更新」「上传面板 ZIP」不关弹窗，故不包。
        val close = LocalUfiDialogClose.current
        UfiDialogBody {
            UfiInfoRow("当前来源", if (webAsset.isOverride) "已上传（override）" else "APK 内置")
            UfiInfoRow("当前版本", webAsset.version ?: "未知")
            UfiInfoRow("内置版本", webAsset.bundledVersion ?: "未知")
            UfiInfoRow("可回滚备份", if (webAsset.hasBackup) "有" else "无")

            // 更新状态区：只在读到过、且不是空闲态时画（idle 时一行「空闲」是噪音）
            webAsset.updateStatus?.takeIf { it.state != "idle" }?.let { st ->
                UfiDivider()
                UfiInfoRow("更新状态", webUpdateStateLabel(st.state))
                if (st.isBusy) UfiInfoRow("进度", "${st.progress}%")
                st.latest_version?.takeIf { it.isNotBlank() }?.let { UfiInfoRow("最新版本", it) }
                st.message.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                }
            }

            UfiDivider()

            // 自动更新：core 从 version.json 的 web 对象自拉取。POST /check 回的是状态快照，
            // 已经在跑时它不报错也不重开一轮，所以按钮只在 isBusy 时置灰即可。
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "检查更新",
                onClick = { viewModel.network.checkWebAssetUpdate() },
                enabled = !webAsset.isBusy,
                loading = webAsset.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "上传面板 ZIP",
                onClick = {
                    // MIME 给两个：部分文件管理器把 zip 报成 application/octet-stream
                    webZipPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                enabled = !webAsset.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "ZIP 根目录须含 index.html 与 version.json，≤50MB。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary.copy(alpha = 0.7f)
            )
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "回滚上一版",
                onClick = { close { showWebPanelDialog = false; showWebRollbackConfirm = true } },
                enabled = webAsset.hasBackup && !webAsset.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            UfiButton(
                variant = UfiButtonVariant.Danger,
                text = "恢复内置版本",
                onClick = { close { showWebPanelDialog = false; showWebResetConfirm = true } },
                enabled = webAsset.isOverride && !webAsset.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
        }
        UfiDialogActions(
            onDismiss = { showWebPanelDialog = false },
            onConfirm = { showWebPanelDialog = false },
            confirmText = "完成",
            dismissText = null,
            topSpacing = Spacing.Large
        )
    }

    // ═══════════ DIALOG 4: 回滚到上一版面板（二次确认） ═══════════
    // 回滚是一次性的：成功之后备份就没了，所以要让用户知道「这一步用掉了唯一的后路」。
    UfiConfirmDialog(
        visible = showWebRollbackConfirm,
        title = "回滚 Web 面板",
        text = "将把设备上的 Web 面板换回上一个版本。\n" +
               "当前版本：${webAsset.version ?: "未知"}\n\n" +
               "备份只有一份，回滚后即被消耗，无法再回滚。",
        confirmText = "回滚",
        destructive = true,
        icon = rememberVectorPainter(Icons.Filled.Web),
        onConfirm = {
            showWebRollbackConfirm = false
            viewModel.network.rollbackWebAssets()
            toastMessage = ToastMessage("正在回滚 Web 面板…", ToastType.INFO)
        },
        onDismiss = { showWebRollbackConfirm = false }
    )

    // ═══════════ DIALOG 5: Web 端资源恢复内置版本（二次确认） ═══════════
    // 复用公共 UfiConfirmDialog(destructive) —— 危险操作的统一样式，不另造布局。
    UfiConfirmDialog(
        visible = showWebResetConfirm,
        title = "恢复内置 Web 面板",
        text = "将删除设备上已上传的 Web 面板资源（含备份），改用 APK 内置版本。\n" +
               "已上传版本：${webAsset.version ?: "未知"}\n" +
               "内置版本：${webAsset.bundledVersion ?: "未知"}\n\n" +
               "删除后无法回滚到已上传版本，需要时请重新上传。",
        confirmText = "恢复内置",
        destructive = true,
        icon = rememberVectorPainter(Icons.Filled.Web),
        onConfirm = {
            showWebResetConfirm = false
            viewModel.network.resetWebAssets()
            toastMessage = ToastMessage("正在恢复内置 Web 面板…", ToastType.INFO)
        },
        onDismiss = { showWebResetConfirm = false }
    )

    // 恢复失败（core 删不干净会返回 500）必须让用户看到，否则会误以为已经恢复好了
    LaunchedEffect(webAsset.errorMessage) {
        webAsset.errorMessage?.let { toastMessage = ToastMessage(it, ToastType.ERROR) }
    }
}

/**
 * `GET /api/web/status` 的 `state` 中文化。
 *
 * 取值来自 core `WebUpdateManager` 的枚举名小写，**比 `/api/update/status` 少一个 `uploading`**
 * （面板 ZIP 是一次性 multipart，core 不为它维护上传态）。认不出的值原样显示 —— core 加了新态时
 * 宁可露出英文，也不要静默显示成「空闲」误导用户。
 */
private fun webUpdateStateLabel(state: String): String = when (state) {
    "idle" -> "空闲"
    "checking" -> "检查中"
    "downloading" -> "下载中"
    "verifying" -> "校验中"
    "installing" -> "安装中"
    "done" -> "更新完成"
    "failed" -> "更新失败"
    "need_push" -> "需手动上传"
    else -> state
}

