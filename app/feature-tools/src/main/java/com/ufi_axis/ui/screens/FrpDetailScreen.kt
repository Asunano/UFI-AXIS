// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * FRP 隧道列表页（2026-08-26 重构）。
 *
 * 本页只负责"有哪些隧道"：右上角 + 进新建向导（[FrpNewChannelScreen]），点击条目进入
 * [FrpChannelScreen] 做具体配置（编辑 TOML / 查看日志 / 启停 / 删除）。
 * 通道列表以后端 configs 目录为唯一事实来源，删空后为空列表而不是 404。
 */
@Composable
fun FrpDetailScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tunnelState.collectAsState()
    val palette = LocalResolvedPalette.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(Unit) {
        // errorMessage 是隧道模块共享的：进页面先清一次，否则上一个页面的失败横幅会挂在这里
        viewModel.tunnel.clearError()
        viewModel.tunnel.loadStatus()
        viewModel.tunnel.loadFrpConfigs()
    }

    UfiScreenScaffold(
        title = "FRP",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = { navController.navigate(Routes.DETAIL_TUNNEL_FRP_NEW) }) {
                Icon(Icons.Default.Add, contentDescription = "新建隧道", tint = palette.accent)
            }
        }
    ) { padding ->
        UfiPageBackground(
            modifier = Modifier.padding(padding),
            contentHPadding = Spacing.PagePadding
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.Large)
            ) {
                state.errorMessage?.let { msg -> UfiErrorBanner(message = msg) }

                if (state.frpConfigItems.isEmpty()) {
                    UfiEmptyState(
                        icon = Icons.Default.Dns,
                        message = "暂无隧道",
                        hint = "点击右上角 + 新建一条隧道"
                    )
                } else {
                    state.frpConfigItems.forEach { item ->
                        UfiEntryCard(
                            title = item.name,
                            subtitle = buildString {
                                if (item.serverAddr.isBlank()) append("未配置服务端")
                                else append("${item.serverAddr}:${item.serverPort}")
                                append(" · ${item.proxyCount} 个代理")
                            },
                            icon = Icons.Default.Dns,
                            onClick = { navController.navigate(Routes.tunnelFrpChannel(item.name)) },
                            badgeText = if (item.running) "运行中" else null,
                            highlighted = item.name == state.frpActiveConfig,
                            iconTint = if (item.running) palette.accent else palette.textSecondary
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.Medium))
            }

            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }
}

// ============ 新建隧道向导（全屏 Route 页面 · 4 步） ============
// 2026-09-21：原来这里是个**单输入框弹窗**——只问隧道名，然后写一份 `minimalFrpToml()`
// 空值模板到后端。于是真正的 8 个必填项（serverAddr / serverPort / auth.token +
// proxy 的 name / type / localIP / localPort / remotePort）全落在详情页那个裸 TOML
// 编辑器里由用户手写：键名拼错、端口写成字符串、漏掉 [[proxies]] 段，都只能靠
// frpc 启动失败的日志倒推。列表页那句 `if (serverAddr.isBlank()) "未配置服务端"`
// 就是这个"创建完还没配好"中间态留下的痕迹。
//
// 现在把这 8 个字段结构化收齐再生成 TOML（[buildFrpToml]），语法不可能出错；
// 详情页的 TOML 编辑器保留不动，作为改高级选项（多代理、带宽限制、可见性…）的专家出口。

/**
 * FRP 新建隧道向导。
 *
 * 路由：[Routes.DETAIL_TUNNEL_FRP_NEW]。完成后写入配置并退回列表页
 * （`saveFrpConfigFile` 内部会顺手把新隧道设为选中项，与改造前一致）。
 */
@Composable
fun FrpNewChannelScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tunnelState.collectAsState()

    LaunchedEffect(Unit) {
        // 重名判据要用列表：深链进来时列表可能是空的
        if (state.frpConfigItems.isEmpty()) viewModel.tunnel.loadFrpConfigs()
    }

    var currentStep by remember { mutableStateOf(0) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var saved by remember { mutableStateOf(false) }

    // 第 1 步：隧道名
    var name by remember { mutableStateOf("") }
    // 第 2 步：frps 服务端。默认端口 7000 = frps 的默认 bindPort
    var serverAddr by remember { mutableStateOf("") }
    var serverPort by remember { mutableStateOf("7000") }
    var token by remember { mutableStateOf("") }
    // 第 3 步：代理。默认值与改造前的 minimalFrpToml() 模板逐项一致 ——
    // 一路点「下一步」得到的结果和以前那份模板相同，只是 serverAddr 现在必填。
    var proxyName by remember { mutableStateOf("web") }
    var proxyType by remember { mutableStateOf("tcp") }
    var localIp by remember { mutableStateOf("127.0.0.1") }
    var localPort by remember { mutableStateOf("8088") }
    var remotePort by remember { mutableStateOf("8088") }

    fun nameError(): String? {
        val n = name.trim()
        return when {
            n.isBlank() -> "请输入隧道名称"
            n.contains(ILLEGAL_NAME_CHARS) || n.contains("..") ->
                "名称不能包含 \\ / : * ? \" < > | 或 .."
            n.length > MAX_TUNNEL_NAME_LEN -> "名称不能超过 $MAX_TUNNEL_NAME_LEN 个字符"
            state.frpConfigItems.any { it.name.equals(n, true) } -> "隧道 [$n] 已存在"
            else -> null
        }
    }

    fun serverError(): String? {
        val p = serverPort.toIntOrNull()
        return when {
            serverAddr.isBlank() -> "请填写 frps 服务端地址"
            p == null || p !in 1..65535 -> "服务端端口需为 1-65535"
            else -> null
        }
    }

    fun proxyError(): String? {
        val lp = localPort.toIntOrNull()
        val rp = remotePort.toIntOrNull()
        return when {
            proxyName.isBlank() -> "请填写代理名称"
            proxyName.contains(ILLEGAL_NAME_CHARS) -> "代理名称不能包含 \\ / : * ? \" < > |"
            localIp.isBlank() -> "请填写本地地址"
            lp == null || lp !in 1..65535 -> "本地端口需为 1-65535"
            rp == null || rp !in 1..65535 -> "远端端口需为 1-65535"
            else -> null
        }
    }

    UfiScreenScaffold(
        title = "新建 FRP 隧道",
        navController = navController,
        showBack = true
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            UfiWizard(
                steps = listOf(
                    UfiWizardStep(
                        label = "名称",
                        heading = "给这条隧道起个名字",
                        description = "名称就是配置文件名，之后在列表与日志里都用它区分。",
                        validate = ::nameError
                    ) {
                        UfiDialogTextField(
                            label = "隧道名称",
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "如 家宽穿透 / my_frp"
                        )
                    },
                    UfiWizardStep(
                        label = "服务端",
                        heading = "frps 服务端在哪",
                        description = "这三项来自你部署的 frps：地址、bindPort、以及 frps.toml 里的 auth.token。",
                        validate = ::serverError
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiDialogTextField(
                                label = "服务端地址",
                                value = serverAddr,
                                onValueChange = { serverAddr = it },
                                placeholder = "IP 或域名，如 frp.example.com"
                            )
                            UfiDialogTextField(
                                label = "服务端端口",
                                value = serverPort,
                                onValueChange = { serverPort = it.filter { c -> c.isDigit() }.take(5) },
                                placeholder = "7000"
                            )
                            UfiDialogPasswordField(
                                label = "认证 token",
                                value = token,
                                onValueChange = { token = it },
                                placeholder = "frps 没配 auth.token 时留空"
                            )
                        }
                    },
                    UfiWizardStep(
                        label = "代理",
                        heading = "要把哪个本地端口穿出去",
                        description = "本地端口是这台设备上的服务，远端端口是在 frps 那边对外暴露的端口。",
                        validate = ::proxyError
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiDialogTextField(
                                label = "代理名称",
                                value = proxyName,
                                onValueChange = { proxyName = it },
                                placeholder = "web"
                            )
                            UfiDialogChipSelector(
                                label = "协议",
                                options = listOf("tcp" to "TCP", "udp" to "UDP"),
                                selectedValue = proxyType,
                                onSelect = { proxyType = it }
                            )
                            UfiDialogTextField(
                                label = "本地地址",
                                value = localIp,
                                onValueChange = { localIp = it },
                                placeholder = "127.0.0.1"
                            )
                            UfiDialogTextField(
                                label = "本地端口",
                                value = localPort,
                                onValueChange = { localPort = it.filter { c -> c.isDigit() }.take(5) },
                                placeholder = "8088"
                            )
                            UfiDialogTextField(
                                label = "远端端口",
                                value = remotePort,
                                onValueChange = { remotePort = it.filter { c -> c.isDigit() }.take(5) },
                                placeholder = "8088"
                            )
                        }
                    },
                    UfiWizardStep(
                        label = "确认",
                        heading = "核对一遍再创建",
                        description = "创建后可以在隧道详情页直接编辑 TOML，加多个代理或调高级选项。"
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiWizardReviewCard(
                                title = "服务端",
                                icon = Icons.Default.Dns,
                                rows = listOf(
                                    UfiWizardReviewRow("隧道名称", name.trim()),
                                    UfiWizardReviewRow("地址", "${serverAddr.trim()}:$serverPort"),
                                    UfiWizardReviewRow(
                                        "认证 token",
                                        if (token.isBlank()) "未设置" else "已设置"
                                    )
                                )
                            )
                            UfiWizardReviewCard(
                                title = "代理",
                                icon = Icons.Default.SwapHoriz,
                                rows = listOf(
                                    UfiWizardReviewRow("名称", proxyName.trim()),
                                    UfiWizardReviewRow("协议", proxyType.uppercase()),
                                    UfiWizardReviewRow("本地", "${localIp.trim()}:$localPort"),
                                    UfiWizardReviewRow("远端", "frps 的 $remotePort 端口")
                                )
                            )
                            UfiDialogNote("配置会写成 TOML 存到设备端；创建后不会自动启动，回列表页点进去手动启动。")
                        }
                    }
                ),
                currentStep = currentStep,
                onStepChange = { currentStep = it },
                onFinish = {
                    val n = name.trim()
                    viewModel.tunnel.saveFrpConfigFile(
                        n,
                        buildFrpToml(
                            serverAddr = serverAddr.trim(),
                            serverPort = serverPort.toIntOrNull() ?: 7000,
                            token = token,
                            proxyName = proxyName.trim(),
                            proxyType = proxyType,
                            localIp = localIp.trim(),
                            localPort = localPort.toIntOrNull() ?: 8088,
                            remotePort = remotePort.toIntOrNull() ?: 8088
                        )
                    )
                    toastMessage = ToastMessage("已创建隧道 [$n]", ToastType.SUCCESS)
                    saved = true
                },
                finishText = "创建隧道",
                onStepBlocked = { toastMessage = ToastMessage(it, ToastType.WARNING) }
            )
            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
        // 先让 toast 挂出去再退页（toast 挂在 Activity 的 decorView 上，不随本页销毁）
        LaunchedEffect(saved) { if (saved) navController.popBackStack() }
    }
}

/** 隧道名里不允许出现的字符（与后端 FrpEngine.isValidName 同规则，避免保存时被 400 拒绝） */
internal val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")

/** 隧道名最大长度（与后端 FrpEngine.sanitizeName 的 take(64) 一致，超长会被后端 400） */
internal const val MAX_TUNNEL_NAME_LEN = 64

/**
 * 由向导收集的字段生成 frpc 的 TOML 配置。
 *
 * 只生成「一个服务端 + 一个代理」这一最常见形态；多代理、带宽限制、stcp/xtcp 之类
 * 交给详情页的 TOML 编辑器 —— 那些选项再往向导里堆，向导就会变成第二个编辑器。
 *
 * 字符串值一律走 [tomlEscape]：域名里不会有引号，但 token 是用户从服务端粘过来的，
 * 里面出现 `"` 或 `\` 会直接把这份 TOML 变成语法错误，而报错要等到 frpc 启动才看到。
 */
internal fun buildFrpToml(
    serverAddr: String,
    serverPort: Int,
    token: String,
    proxyName: String,
    proxyType: String,
    localIp: String,
    localPort: Int,
    remotePort: Int
): String = buildString {
    appendLine("serverAddr = \"${tomlEscape(serverAddr)}\"")
    appendLine("serverPort = $serverPort")
    appendLine("auth.token = \"${tomlEscape(token)}\"")
    appendLine()
    appendLine("[[proxies]]")
    appendLine("name = \"${tomlEscape(proxyName)}\"")
    appendLine("type = \"${tomlEscape(proxyType)}\"")
    appendLine("localIP = \"${tomlEscape(localIp)}\"")
    appendLine("localPort = $localPort")
    appendLine("remotePort = $remotePort")
}

/** TOML 基本字符串的转义：反斜杠要先转，否则会把后一步引号转义出来的反斜杠再转一次。 */
private fun tomlEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
