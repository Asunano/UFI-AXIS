// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改
package com.ufi_axis.ui.screens.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.api.StorageSourceInfo
import com.ufi_axis.data.api.StorageSourceRequest
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel

// ========== 新增/编辑：全屏引导式表单 ==========
//
// 2026-09-21：本文件原来还有一个 `StorageSourcesScreen`（独立的「外部存储」列表页：
// 列全部源 + 测连 + 进编辑），现已删除 —— 它列的东西和文件管理器的**存储列表**完全重复
// （那一层本来就在列"内部存储 + 各外部源"）。管理职责一并搬到那一层：长按一行给
// 「编辑 / 测试连接 / 删除」，末尾一行「添加外部存储」跳本页。
//
// 保留本页为独立路由、没有一起并进弹窗，理由见下方 KDoc 里记的那两条。

/**
 * 外部存储源的新增 / 编辑（全屏 Route 页面 · 引导式 4 步）。
 *
 * 路由：[com.ufi_axis.ui.navigation.Routes.DETAIL_STORAGE_SOURCE_EDIT]（`id` 空=新增）。
 *
 * 2026-09-21 从弹窗（`UfiScrollableDialog` + 一页 20 个字段的长表单）改成本页，理由两条：
 *   1. 弹窗只有 82% 屏高，字段一多底部「保存」就被裁掉 —— `detail/task-edit` 当初搬出弹窗
 *      是同一个原因；
 *   2. 原来 20 个字段一次铺开、靠 `if (protocol == …)` 显隐，用户得读完整张表才知道
 *      自己这个协议要填哪几项；而 `formValid` 是一个聚合判据，不满足时「保存」灰着但
 *      **不说哪一项不对**。
 * 现在按「协议 → 地址与凭据 → 协议专属选项 → 试连并确认」分四步，每步的 `validate`
 * 各自回答"能不能往下走"，拦下时给出具体原因（见 [UfiWizard]）。
 *
 * 「测试连接」放在确认页，且**不是**保存的前置条件：内网 NAS 临时关机、S3 临时限流
 * 这类情况下仍应允许把配置存下来。
 */
@Composable
fun StorageSourceEditScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    sourceId: String
) {
    val palette = LocalResolvedPalette.current
    val state by viewModel.storageSources.state.collectAsState()
    val isEdit = sourceId.isNotEmpty()
    val source = remember(sourceId, state.sources) {
        if (isEdit) state.sources.firstOrNull { it.id == sourceId } else null
    }

    LaunchedEffect(Unit) {
        // 深链 / 进程重建进来时列表可能还没拉回来（表单字段 remember(source) 会在拿到后重填）
        if (state.sources.isEmpty()) viewModel.storageSources.loadSources()
        // 上一次的试连结果属于上一个页面，别带进本页的确认步
        viewModel.storageSources.clearTestResult()
    }

    var currentStep by remember { mutableStateOf(0) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 表单字段（逐条沿用改造前的取值与注释）
    var protocol by remember(source) { mutableStateOf(source?.protocol ?: "ftp") }
    var label by remember(source) { mutableStateOf(source?.label ?: "") }
    var host by remember(source) { mutableStateOf(source?.host ?: "") }
    var port by remember(source) {
        mutableStateOf(
            source?.port?.takeIf { it > 0 }?.toString()
            // S3 的 port 合法值包含 0（走 scheme 默认端口），不能回落到 FTP 的 21，
            // 否则编辑一个 AWS 源再保存会凭空写进一个 21 端口。
                ?: if (source?.protocol == "s3") "" else "21"
        )
    }
    // 用户名回显：它不是秘密，不回显的话一保存就被清空（core 侧只对密码有
    // "********" 占位机制）。密码仍然不回显。
    var username by remember(source) { mutableStateOf(source?.username ?: "") }
    var password by remember(source) { mutableStateOf("") }  // 不回显

    var basePath by remember(source) { mutableStateOf(source?.basePath ?: "/") }
    var useTls by remember(source) { mutableStateOf(source?.useTls ?: false) }
    var passive by remember(source) { mutableStateOf(source?.passive ?: true) }
    var encoding by remember(source) { mutableStateOf(source?.encoding ?: "UTF-8") }
    var trustAllCerts by remember(source) { mutableStateOf(source?.trustAllCerts ?: false) }
    var domain by remember(source) { mutableStateOf(source?.domain ?: "") }
    var share by remember(source) { mutableStateOf(source?.share ?: "") }
    var bucket by remember(source) { mutableStateOf(source?.bucket ?: "") }
    var region by remember(source) { mutableStateOf(source?.region ?: "us-east-1") }
    var endpoint by remember(source) { mutableStateOf(source?.endpoint ?: "") }
    var pathStyle by remember(source) { mutableStateOf(source?.pathStyle ?: true) }
    var timeoutSec by remember(source) { mutableStateOf(source?.timeoutSec?.toString() ?: "15") }
    var enabled by remember(source) { mutableStateOf(source?.enabled ?: true) }

    val isS3 = protocol == "s3"

    // 协议切换时联动端口
    fun defaultPort(): String = when {
        protocol == "ftp" && useTls -> "990"
        protocol == "ftp" -> "21"
        protocol == "webdav" && useTls -> "443"
        protocol == "webdav" -> "80"
        protocol == "smb" -> "445"
        // S3 默认空端口：AWS / OSS / COS 走 scheme 默认端口，而 MinIO 通常在 9000，
        // 给不出一个对多数人都对的默认值，所以留空让用户自己填。
        protocol == "s3" -> ""
        else -> "21"
    }

    // 当协议或 TLS 开关变化时，如果端口是上一次的默认值则自动跟换
    var prevDefaultPort by remember { mutableStateOf(defaultPort()) }
    LaunchedEffect(protocol, useTls) {
        val newDefault = defaultPort()
        if (port == prevDefaultPort) port = newDefault
        prevDefaultPort = newDefault
    }

    // ── 逐步校验 ────────────────────────────────────────────────────────────
    // 改造前这些判据是一个聚合的 `formValid`，不满足时只能整体灰掉「保存」。
    // 现在按步拆开，各自回答"这一步能不能离开"，并把原因交给向导渲染出来。

    fun basicError(): String? =
        if (label.isBlank()) "请填一个标签，它是这个源在列表里显示的名字" else null

    fun addressError(): String? {
        val portInt = port.toIntOrNull()
        return when {
            // S3 的服务地址在 Endpoint 里（下一步），压根没有"主机"这一项。
            !isS3 && host.isBlank() -> "请填写主机（IP 或域名）"
            // S3 的端口允许留空（走 scheme 默认端口）；填了就得是合法端口。
            isS3 && port.isNotEmpty() && (portInt == null || portInt !in 1..65535) ->
                "端口需为 1-65535，或留空走默认端口"
            !isS3 && (portInt == null || portInt !in 1..65535) -> "端口需为 1-65535"
            !basePath.startsWith("/") -> "${if (isS3) "Key 前缀" else "远端路径"}必须以 / 开头"
            else -> null
        }
    }

    fun optionError(): String? = when {
        // SMB 的共享名是必填：没有它 core 侧直接 400。
        protocol == "smb" && share.isBlank() -> "SMB 必须填共享名（NAS 上那个共享文件夹的名字）"
        // 同理，S3 没有 Bucket 连不到任何东西。
        isS3 && bucket.isBlank() -> "S3 必须填 Bucket"
        else -> null
    }

    fun buildRequest(): StorageSourceRequest {
        val pw = if (isEdit && password.isEmpty()) "********" else password
        return StorageSourceRequest(
            label = label.trim(),
            protocol = protocol,
            host = host.trim(),
            // S3 留空端口时传 0，由 core 侧走 scheme 默认端口
            port = port.toIntOrNull() ?: if (isS3) 0 else 21,
            username = username.trim(),
            password = pw,
            basePath = basePath.trim(),
            useTls = useTls,
            passive = passive,
            encoding = encoding.trim().ifEmpty { "UTF-8" },
            trustAllCerts = trustAllCerts,
            domain = domain.trim(),
            share = share.trim(),
            bucket = bucket.trim(),
            region = region.trim().ifEmpty { "us-east-1" },
            endpoint = endpoint.trim(),
            pathStyle = pathStyle,
            timeoutSec = timeoutSec.toIntOrNull() ?: 15,
            enabled = enabled
        )
    }

    fun submit() {
        submitting = true
        val request = buildRequest()
        val onDone: (Boolean, String) -> Unit = { ok, msg ->
            submitting = false
            toastMessage = ToastMessage(msg, if (ok) ToastType.SUCCESS else ToastType.ERROR)
            if (ok) finished = true
        }
        if (isEdit) {
            viewModel.storageSources.updateSource(sourceId, request, onDone)
        } else {
            viewModel.storageSources.addSource(request, onDone)
        }
    }

    UfiScreenScaffold(
        title = if (isEdit) "编辑存储源" else "新增存储源",
        navController = navController,
        showBack = true,
        actions = {
            // 删除入口：改造前它借用的是弹窗底部「取消」那个位置（dismissText = "删除"），
            // 全屏页里没有那个槽位，搬到标题栏右上角。
            if (isEdit) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = palette.error)
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            UfiWizard(
                steps = listOf(
                    UfiWizardStep(
                        label = "协议",
                        heading = "先选存储协议",
                        description = "协议决定了后面要填哪些字段。",
                        validate = ::basicError
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiDialogChipSelector(
                                label = "协议",
                                options = listOf("ftp" to "FTP", "webdav" to "WebDAV", "smb" to "SMB", "s3" to "S3"),
                                selectedValue = protocol,
                                onSelect = { protocol = it }
                            )
                            UfiDialogTextField(
                                label = "标签",
                                value = label,
                                onValueChange = { label = it },
                                placeholder = "例如：NAS-FTP",
                                isError = label.isBlank() && label.isNotEmpty(),
                                errorMessage = null
                            )
                        }
                    },
                    UfiWizardStep(
                        label = "地址",
                        heading = "远端在哪、用什么账号",
                        description = when (protocol) {
                            "s3" -> "S3 的服务地址填在下一步的 Endpoint 里；这一步只要端口（可留空）和 Access Key。"
                            "webdav" -> "主机只填域名（如 pan.moe），URL 里的路径段填到「远端路径」。"
                            else -> "主机 + 端口 + 起始路径，再加上登录账号。"
                        },
                        validate = ::addressError
                    ) {
                        val error = addressError()
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            // S3 的服务地址完全由 Endpoint 决定，再放一个"主机"只会让人两处填、两处不一致。
                            if (!isS3) {
                                UfiDialogTextField(
                                    label = "主机",
                                    value = host,
                                    onValueChange = { host = it },
                                    // WebDAV 服务商给的是整条 URL（「地址：https://pan.moe/dav」），
                                    // 用户会照抄。core 侧已经会把协议头和路径段拆出来，但提示里仍然
                                    // 写清正确填法，省掉一次试错。
                                    placeholder = if (protocol == "webdav") "域名，如 pan.moe（路径填到下方）" else "IP 或域名",
                                    isError = host.isBlank() && error != null,
                                    errorMessage = null
                                )
                            }
                            UfiDialogTextField(
                                label = "端口",
                                value = port,
                                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                                placeholder = if (isS3) "留空用默认端口；MinIO 通常 9000" else defaultPort()
                            )
                            // S3 没有"用户名 / 密码"，但凭据的位置一一对应，所以只换标签、不新增字段。
                            // 用户名回显，所以 placeholder 不写"留空表示不修改"——那只对密码成立。
                            UfiDialogTextField(
                                label = if (isS3) "Access Key" else "用户名",
                                value = username,
                                onValueChange = { username = it },
                                placeholder = ""
                            )
                            UfiDialogPasswordField(
                                label = if (isS3) "Secret Key" else "密码",
                                value = password,
                                onValueChange = { password = it },
                                placeholder = if (isEdit) "留空表示不修改" else ""
                            )
                            UfiDialogTextField(
                                label = if (isS3) "Key 前缀" else "远端路径",
                                value = basePath,
                                onValueChange = { basePath = it },
                                placeholder = "/",
                                isError = !basePath.startsWith("/"),
                                errorMessage = if (!basePath.startsWith("/")) "必须以 / 开头" else null
                            )
                        }
                    },
                    UfiWizardStep(
                        label = "选项",
                        heading = "${protocol.uppercase()} 的专属设置",
                        description = "下面几项按协议不同，加上所有协议通用的连接选项。",
                        validate = ::optionError
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            // FTP 专有
                            if (protocol == "ftp") {
                                UfiDialogSwitchField(
                                    label = "被动模式",
                                    checked = passive,
                                    onCheckedChange = { passive = it }
                                )
                                UfiDialogTextField(
                                    label = "文件名编码",
                                    value = encoding,
                                    onValueChange = { encoding = it },
                                    placeholder = "UTF-8"
                                )
                            }
                            // WebDAV 专有
                            if (protocol == "webdav") {
                                UfiDialogSwitchField(
                                    label = "信任自签名证书",
                                    checked = trustAllCerts,
                                    onCheckedChange = { trustAllCerts = it }
                                )
                            }
                            // SMB 专有
                            if (protocol == "smb") {
                                UfiDialogTextField(
                                    label = "共享名",
                                    value = share,
                                    onValueChange = { share = it },
                                    placeholder = "NAS 上的共享文件夹名，例如 video",
                                    isError = share.isBlank(),
                                    errorMessage = if (share.isBlank()) "不能为空" else null
                                )
                                UfiDialogTextField(
                                    label = "域 / 工作组",
                                    value = domain,
                                    onValueChange = { domain = it },
                                    placeholder = "留空表示无域（家用 NAS 通常不填）"
                                )
                            }
                            // S3 专有
                            if (isS3) {
                                UfiDialogTextField(
                                    label = "Bucket",
                                    value = bucket,
                                    onValueChange = { bucket = it },
                                    placeholder = "必填",
                                    isError = bucket.isBlank(),
                                    errorMessage = if (bucket.isBlank()) "不能为空" else null
                                )
                                UfiDialogTextField(
                                    label = "Region",
                                    value = region,
                                    onValueChange = { region = it },
                                    placeholder = "us-east-1"
                                )
                                UfiDialogTextField(
                                    label = "Endpoint",
                                    value = endpoint,
                                    onValueChange = { endpoint = it },
                                    placeholder = "留空走 AWS 官方地址；MinIO/OSS/COS 填服务地址，不含 http://"
                                )
                                // 提示写在 label 里：UfiDialogSwitchField 是冻结签名的公共组件，没有副文案位。
                                UfiDialogSwitchField(
                                    label = "路径式寻址（MinIO 必须开启）",
                                    checked = pathStyle,
                                    onCheckedChange = { pathStyle = it }
                                )
                            }
                            // SMB 不给 TLS 开关：SMB3 的加密是会话协商出来的，没有"要不要 TLS"这回事，
                            // 留着就是个点了不起作用的假开关。S3 保留：本地 MinIO 跑纯 http 是正常用法。
                            if (protocol != "smb") {
                                UfiDialogSwitchField(
                                    label = "使用 TLS",
                                    checked = useTls,
                                    onCheckedChange = { useTls = it }
                                )
                            }
                            UfiDialogTextField(
                                label = "超时（秒）",
                                value = timeoutSec,
                                onValueChange = { timeoutSec = it.filter { c -> c.isDigit() }.take(3) },
                                placeholder = "15"
                            )
                            UfiDialogSwitchField(
                                label = "启用",
                                checked = enabled,
                                onCheckedChange = { enabled = it }
                            )
                        }
                    },
                    UfiWizardStep(
                        label = "确认",
                        heading = "核对一遍再保存",
                        description = "可以先测一下能不能连上（非必须，设备临时离线也应该允许把配置存下来）。"
                    ) {
                        val testResult = state.testResult
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiWizardReviewCard(
                                title = "存储源",
                                icon = when (protocol) {
                                    "smb" -> Icons.Filled.Dns
                                    "s3" -> Icons.Filled.CloudQueue
                                    else -> Icons.Filled.Cloud
                                },
                                rows = listOf(
                                    UfiWizardReviewRow("协议", protocol.uppercase()),
                                    UfiWizardReviewRow("标签", label.ifBlank { "未填写" }),
                                    UfiWizardReviewRow("状态", if (enabled) "启用" else "停用")
                                )
                            )
                            UfiWizardReviewCard(
                                title = "连接",
                                icon = Icons.Filled.NetworkCheck,
                                rows = buildList {
                                    if (isS3) {
                                        add(UfiWizardReviewRow("Endpoint", endpoint.ifBlank { "AWS 官方地址" }))
                                        add(UfiWizardReviewRow("Bucket", bucket.ifBlank { "未填写" }))
                                        add(UfiWizardReviewRow("Region", region.ifBlank { "us-east-1" }))
                                        add(UfiWizardReviewRow("路径式寻址", if (pathStyle) "开启" else "关闭"))
                                    } else {
                                        add(UfiWizardReviewRow("主机", host.ifBlank { "未填写" }))
                                    }
                                    add(UfiWizardReviewRow("端口", port.ifBlank { "默认端口" }))
                                    if (protocol == "smb") {
                                        add(UfiWizardReviewRow("共享名", share.ifBlank { "未填写" }))
                                        add(UfiWizardReviewRow("域 / 工作组", domain.ifBlank { "无域" }))
                                    }
                                    if (protocol == "ftp") {
                                        add(UfiWizardReviewRow("被动模式", if (passive) "开启" else "关闭"))
                                        add(UfiWizardReviewRow("文件名编码", encoding.ifBlank { "UTF-8" }))
                                    }
                                    if (protocol == "webdav") {
                                        add(UfiWizardReviewRow("自签名证书", if (trustAllCerts) "信任" else "不信任"))
                                    }
                                    add(UfiWizardReviewRow(if (isS3) "Key 前缀" else "远端路径", basePath))
                                    add(
                                        UfiWizardReviewRow(
                                            "TLS",
                                            if (protocol == "smb") "由 SMB3 会话协商"
                                            else if (useTls) "开启" else "关闭"
                                        )
                                    )
                                    add(UfiWizardReviewRow("超时", "${timeoutSec.ifBlank { "15" }} 秒"))
                                }
                            )
                            UfiWizardReviewCard(
                                title = "凭据",
                                icon = Icons.Filled.Lock,
                                rows = listOf(
                                    UfiWizardReviewRow(
                                        if (isS3) "Access Key" else "用户名",
                                        username.ifBlank { "未填写（匿名）" }
                                    ),
                                    UfiWizardReviewRow(
                                        if (isS3) "Secret Key" else "密码",
                                        when {
                                            password.isNotEmpty() -> "已填写"
                                            isEdit -> "沿用原密码"
                                            else -> "未填写"
                                        }
                                    )
                                )
                            )
                            UfiButton(
                                text = if (state.isTesting) "正在测试…" else "测试连接",
                                onClick = { viewModel.storageSources.testConfig(buildRequest()) },
                                size = UfiButtonSize.Small,
                                variant = UfiButtonVariant.Subtle,
                                enabled = !state.isTesting,
                                loading = state.isTesting
                            )
                            // sourceId == null = 测的是当前表单配置（列表页测已存源时是非 null）
                            if (testResult != null && testResult.sourceId == null) {
                                Text(
                                    text = if (testResult.success) "连接成功（${testResult.latencyMs}ms）"
                                    else "连接失败：${testResult.message}",
                                    style = UfiTextStyles.caption,
                                    color = if (testResult.success) palette.success else palette.error,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                ),
                currentStep = currentStep,
                onStepChange = { currentStep = it },
                onFinish = { submit() },
                finishText = if (isEdit) "保存修改" else "添加存储源",
                finishLoading = submitting,
                onStepBlocked = { toastMessage = ToastMessage(it, ToastType.WARNING) }
            )
            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
        // 先让 toast 挂出去再退页（toast 卡片挂在 Activity 的 decorView 上，不随本页销毁）
        LaunchedEffect(finished) { if (finished) navController.popBackStack() }
    }

    if (showDeleteConfirm) {
        UfiConfirmDialog(
            visible = true,
            title = "删除 ${label.ifBlank { "这个存储源" }}",
            text = "删除后文件管理器里该源的入口将一并消失，远端文件不受影响。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.storageSources.deleteSource(sourceId) { ok, msg ->
                    toastMessage = ToastMessage(msg, if (ok) ToastType.SUCCESS else ToastType.ERROR)
                    if (ok) finished = true
                }
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}
