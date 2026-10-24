package com.ufi_axis.ui.screens

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
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

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

    /*
     * 配对密码修改失败只会写 `pairingState.errorMessage`（原来由安全中心的错误横幅渲染）。
     * 2026-10-24 入口搬到本页后，本页必须自己把这条错误浮出来 —— 否则失败完全静默，
     * 用户只会看到弹窗关掉、密码却没变。成功提示由 NetworkModule.emitWriteNotice 统一发。
     */
    val pairingState by viewModel.network.pairingState.collectAsState()
    LaunchedEffect(pairingState.errorMessage) {
        // 只转密码修改相关的错误：本页不负责配对状态加载/黑名单等其他配对动作。
        pairingState.errorMessage?.takeIf { it.contains("配对密码") }?.let {
            toastMessage = ToastMessage(it, ToastType.ERROR)
        }
    }

    // ── Dialog visibility states ──
    var showConnectionDialog by remember { mutableStateOf(false) }
    var showGatewayPwdDialog by remember { mutableStateOf(false) }

    /*
     * ── 2026-10-14：Web 面板管理整块（行卡 + 三个弹窗 + SAF 选 ZIP）已迁到**资源中心** ──
     *
     * 迁走的理由：那一整套（读版本 / 检查更新 / 上传 ZIP / 回滚 / 恢复内置）与
     * frpc / cloudflared 的「查状态 → 下载安装 → 卸载」是同一套形状，只多一档状态
     * （`need_push`）与一层上传。收进 `Routes.DETAIL_RESOURCES` 之后，新增一类可下载资源
     * 只写一条 `ResourceDescriptor`（见 `:app:feature-resources`），不必再在设置页里
     * 长出一个新弹窗 —— 那正是本次"注册表驱动"要消灭的重复。
     *
     * ⚠ 「恢复入口必须留在 App 侧」这条 2026-08-27 的结论**依然成立**，只是换了位置：
     * 上传坏产物之后 Web 面板自己打不开，所以恢复入口现在在资源中心里；而且
     * `Routes.DETAIL_RESOURCES` 进了 `ServiceGate.SERVICE_INDEPENDENT_ROUTES` ——
     * 停服状态下也进得去，比原来那颗"只在读到过版本信息时才可点"的行卡更硬。
     *
     * `NetworkModule` 的 web 面板那组（`loadWebAssetInfo` / `loadWebUpdateStatus` /
     * `checkWebAssetUpdate` / `rollbackWebAssets` / `uploadWebAssets` / `resetWebAssets`
     * 以及 `webAssetState`）**在 app 侧已无消费者** —— grep 全仓确认本文件是唯一调用点
     * （另一处命中是 `ServiceEndpointContractTest`，它只校验 Retrofit 注解路径，不经 module）。
     * 资源中心走的是自己的描述符层（`ResourceApi` + `UfiAxisApi` 上的同一批注解）。
     * `NetworkModule` 不在本次改动范围内，**不动它**，只在此标注待清理。
     */
    val scope = rememberCoroutineScope()

    // ── Live display values for entry descriptions ──
    var displayIp by remember { mutableStateOf(prefs.serverIp) }
    var displayPort by remember { mutableStateOf(prefs.serverPort.toString()) }
    var displayGwAddress by remember {
        mutableStateOf("${prefs.gatewayIp}:${prefs.goformPort}")
    }

    // ═══════════ PAGE BODY — 2 entries only ═══════════
    UfiScreenScaffold(title = "服务器配置", navController = navController, showBack = true, showHeader = showHeader) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            UfiPageBackground(modifier = Modifier.fillMaxSize()) {

                // 对齐网络主页布局：每个入口都是独立的行卡（公共 UfiSettingsRowCard），10dp 间距
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── 1. 连接配置（连接地址 + 配对密码，两页签） ──
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

                // ── 3. 资源中心入口已于 2026-10-20 按用户要求移除 ──
                // 资源中心仍有「设置 → 关于设备」与内网穿透设置里的入口，可达性不受影响。

                Spacer(Modifier.height(Spacing.Large))
            }
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

    // ═══════════ DIALOG 1: 连接配置（连接地址 / 配对密码） ═══════════
    // Token 输入框已删除：token 现在是「每设备独占」凭据，由 /pairing/confirm 签发并与本机
    // Keystore 私钥绑定，手填别处的 token 只会因验签失败被判 444。要换凭据只能走「重新配对」。
    //
    // 2026-10-24：配对密码的修改入口从「安全中心」搬到本弹窗，布局对齐下方的「设备后台」——
    // 同样是 UfiScrollableTabRow 两页签（连接地址 / 配对密码），底部一颗动作按钮。
    var connSelectedTab by remember { mutableIntStateOf(0) }
    var connIp by remember(displayIp) { mutableStateOf(displayIp) }
    var connPort by remember(displayPort) { mutableStateOf(displayPort) }
    var connError by remember { mutableStateOf<String?>(null) }
    var pairOldPwd by remember { mutableStateOf("") }
    var pairNewPwd by remember { mutableStateOf("") }
    var pairConfirmPwd by remember { mutableStateOf("") }
    val pairCanSubmit = pairOldPwd.isNotBlank() &&
        pairNewPwd.length in 4..64 &&
        pairNewPwd == pairConfirmPwd
    val resetConnectionDialog: () -> Unit = {
        connSelectedTab = 0
        connError = null
        pairOldPwd = ""
        pairNewPwd = ""
        pairConfirmPwd = ""
    }

    UfiScrollableDialog(
        visible = showConnectionDialog,
        onDismiss = {
            showConnectionDialog = false
            resetConnectionDialog()
        },
        title = "连接配置",
        icon = rememberVectorPainter(Icons.Filled.Dns),
        showCloseButton = false,
        actions = {
            UfiDialogActions(
                confirmText = if (connSelectedTab == 0) "保存连接" else "确认修改",
                // 连接页的校验放在 onConfirm 里（要按字段给出具体原因）；密码页在按钮上
                // 直接挡住空/过短/两次不一致，与设备后台的「后台密码」页同一口径。
                enabled = connSelectedTab == 0 || pairCanSubmit,
                onConfirm = {
                    if (connSelectedTab == 0) {
                        // 2026-10-14：这里原先自带一条 `^\d{1,3}(\.\d{1,3}){3}$` 正则（只认 IPv4，
                        // 且没有 0–255 分段校验）。放行域名之后必须换成同一条 isValidHost ——
                        // 否则配对页能填域名、进来这里却改不了，等于给用户留了一个改不回去的地址。
                        // 现在判定整条收进 checkServerEndpoint（ServerAddress.kt，与配对页共用）：
                        // 它先 trim 再判 —— 首尾空格会拼出 `http://nas.local :8088/`，那种地址
                        // 只在运行期失败，所以必须在校验层就吃掉；中间的空格仍然报格式错误。
                        when (val r = checkServerEndpoint(connIp, connPort)) {
                            is ServerEndpoint.Valid -> {
                                displayIp = r.host; prefs.serverIp = r.host
                                displayPort = r.port.toString(); prefs.serverPort = r.port
                                // P2：onServerConfigChanged 内部按「IP:端口是否变化」分流
                                // （换机清缓存并重连，同机只轻刷新）。
                                onServerConfigChanged()
                                showConnectionDialog = false
                                resetConnectionDialog()
                                // 2026-09-22（阶段 3.5）：补成功提示。这一项**纯本地**（写
                                // AppPreferences，没有请求可失败），所以走 notifyLocalWriteSucceeded
                                // 而不是 module；提示仍落在全局宿主，不在本页另写一份。
                                viewModel.notifyLocalWriteSucceeded(
                                    "Core 连接地址已保存",
                                    subtitle = "${r.host}:${r.port}"
                                )
                            }
                            ServerEndpoint.BlankHost -> connError = "地址不能为空"
                            ServerEndpoint.InvalidHost ->
                                connError = "地址格式不正确（IP 或域名，例如 192.168.0.1 / nas.local）"
                            ServerEndpoint.InvalidPort -> connError = "端口范围 1-65535"
                        }
                    } else {
                        // 与安全中心原来的「修改配对密码」完全同一入口：成功提示由
                        // NetworkModule.emitWriteNotice 统一发；失败写 pairingState.errorMessage，
                        // 本页顶部的 LaunchedEffect 会把它转成 Toast（不然这次失败是静默的）。
                        viewModel.network.changeDevicePassword(pairOldPwd, pairNewPwd)
                        showConnectionDialog = false
                        resetConnectionDialog()
                    }
                },
                onDismiss = {
                    showConnectionDialog = false
                    resetConnectionDialog()
                }
            )
        }
    ) {
        UfiDialogBody {
            UfiScrollableTabRow(
                selectedTabIndex = connSelectedTab,
                onTabSelected = { connSelectedTab = it },
                tabs = listOf("连接地址", "配对密码")
            )
            UfiAnimatedTabContent(targetState = connSelectedTab) { tab ->
                when (tab) {
                    0 -> {
                        Text(
                            "配置 Core 后端的连接参数。\n换到另一台设备请先「切换设备 / 退出」再配对，" +
                                "只改地址无法带上旧设备的登录凭据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary
                        )
                        UfiDialogTextField(
                            // 2026-10-14：与配对页同口径，放行域名（Core 地址可以是 IP 或主机名）
                            label = "Core 地址（IP 或域名）",
                            value = connIp,
                            onValueChange = { connIp = it; connError = null },
                            placeholder = "192.168.11.19 或 nas.local"
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

                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                            Text("配对密码", style = MaterialTheme.typography.titleSmall,
                                color = palette.accent)
                            Text(
                                "客户端连接本服务时验证的密码；修改后旧密码立即失效，" +
                                    "已配对的客户端下次验证需使用新密码。",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary
                            )
                        }
                        UfiDialogField(label = "当前配对密码") {
                            UfiPasswordField(
                                value = pairOldPwd,
                                onValueChange = { pairOldPwd = it },
                                label = "",
                                showToggle = false
                            )
                        }
                        UfiDialogField(label = "新密码（4-64 位）") {
                            UfiPasswordField(
                                value = pairNewPwd,
                                onValueChange = { pairNewPwd = it },
                                label = "",
                                showToggle = false
                            )
                        }
                        UfiDialogField(label = "确认新密码") {
                            UfiPasswordField(
                                value = pairConfirmPwd,
                                onValueChange = { pairConfirmPwd = it },
                                label = "",
                                showToggle = false,
                                isError = pairConfirmPwd.isNotEmpty() && pairConfirmPwd != pairNewPwd,
                                errorMessage = if (pairConfirmPwd.isNotEmpty() && pairConfirmPwd != pairNewPwd) {
                                    "密码不一致"
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }
        }
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
                    // 2026-10-14：整串先 trim。这一栏与上面「连接配置」不同，它只要求带端口、
                    // 不校验主机形状（core 的 goform_ip 只做 `^[a-zA-Z0-9.-]+$` 形状检查，
                    // 域名与 IP 同一口径），所以 trim 是这里唯一需要做的归一。
                    val gw = gwAddr.trim()
                    // 1) 前置校验（纯本地，不发请求）
                    when {
                        gw.isBlank() -> { gwPwdError = "网关地址不能为空"; return@UfiDialogActions }
                        !gw.contains(":") -> { gwPwdError = "需包含端口号（如 :8080）"; return@UfiDialogActions }
                    }
                    val parts = gw.split(":")
                    val gip = parts.getOrElse(0) { "" }.trim().ifBlank { "192.168.0.1" }
                    // 端口段**不再**静默回落 8080：旧写法 `?: 8080` 会让 "router.local:80 "
                    // 这种带尾空格的输入把端口解析成 null 后悄悄存成 8080 —— 用户填 80、
                    // 界面还显示 "router.local:80"，而 core 从此按 8080 去连后台，全链路 503。
                    // 端口是这一栏唯一的硬要求，解析不出来就该当场报错。
                    val gport = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (gport == null || gport !in 1..65535) {
                        gwPwdError = "端口需为 1-65535（示例：192.168.0.1:8080 或 router.local:80）"
                        return@UfiDialogActions
                    }
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
                        // 摘要用 trim 后的整串：本地存的是 gip/gport，显示也该是同一份，
                        // 否则下次进弹窗会看到一个带尾空格、与 prefs 不一致的地址。
                        displayGwAddress = "$gip:$gport"
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
                        // 2026-10-14：这一栏本来就不校验 IP 形状（只要求带端口），
                        // 占位符同步给出域名示例，免得用户以为只能填 IP。
                        label = "网关地址",
                        value = gwAddr,
                        onValueChange = { gwAddr = it; gwPwdError = null },
                        placeholder = "192.168.0.1:8080 或 router.local:80"
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

    /*
     * ═══════════ 2026-10-14：DIALOG 3/4/5（Web 面板管理 / 回滚确认 / 恢复内置确认）已删除 ═══════════
     *
     * 三个弹窗连同 `showWebPanelDialog` / `showWebRollbackConfirm` / `showWebResetConfirm`
     * 三个状态、以及 SAF 选 ZIP 的 `webZipPicker` 一起搬到资源中心：
     *  - 只读信息行（当前来源 / 当前版本 / 内置版本 / 可回滚备份）→ `ResourceRegistry.webZip`
     *    的 `extraRows`；
     *  - 「检查更新 / 上传面板 ZIP / 回滚上一版 / 恢复内置版本」四颗按钮 → 描述符上的
     *    `refresh` / `upload` / `rollback` / `clear` 四个可选回调，卡片按"有没有这个能力"决定画不画；
     *  - 二次确认（回滚一次性、恢复内置不可逆）→ `ResourceCard` 里统一的 `UfiConfirmDialog`。
     * 两个确认弹窗的文案在搬移时保留原意（"备份只有一份，回滚后即被消耗"等）。
     *
     * `webUpdateStateLabel` 也随之删除：状态中文改由资源中心统一的
     * `DEFAULT_STATE_META` 承担（两张状态机的并集，含组件独有的 `extracting`），
     * 认不出的值同样原样显示 —— 口径与原函数逐字一致。
     */
}

