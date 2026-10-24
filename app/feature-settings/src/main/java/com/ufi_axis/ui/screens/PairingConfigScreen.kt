package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.BlockedDeviceItem
import com.ufi_axis.viewmodel.state.PairedDeviceItem

/**
 * 配对与访问：本机配对信息、已配对设备列表、配对数量限制、解除全部配对。
 *
 * 配对密码是本页所有破坏性操作的门禁（配对确认、移除设备、解除全部配对都校验它），
 * 但**修改入口已于 2026-10-24 移到「服务器 → 服务器配置 → 连接配置」**（与设备后台
 * 同一套两页签布局），本页不再提供修改，只保留校验用途。
 *
 * 视觉与交互一律走 `ui/components/common` 的公共组件（2026-09-10 收敛：卡内标题、
 * 信息格、设备行、分隔线、进度条、按钮、弹窗按钮此前都是本页手搓）。
 */
@Composable
fun PairingConfigScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    showHeader: Boolean = true  // 2026-08-11：被外层 ServerScreen Tab 嵌套调用时传 false 避免多重标题栏
) {
    val palette = LocalResolvedPalette.current
    val pairingState by viewModel.network.pairingState.collectAsState()

    var pairingEnabled by remember { mutableStateOf(false) }
    var maxDevices by remember { mutableIntStateOf(0) }
    // 安全中心：批准策略与三个外部通知渠道（2026-10-15，默认全关）
    var approvalRequired by remember { mutableStateOf(false) }
    var notifySystem by remember { mutableStateOf(false) }
    var notifyEmail by remember { mutableStateOf(false) }
    var notifyWebhook by remember { mutableStateOf(false) }
    var showUnpairDialog by remember { mutableStateOf(false) }
    /** 打开「新设备需批准」时的系统通知引导（2026-10-21 决策 D5-3）。 */
    var showApprovalNotifyHint by remember { mutableStateOf(false) }
    var pendingRenameFp by remember { mutableStateOf<String?>(null) }
    var pendingRenameName by remember { mutableStateOf("") }
    var pendingRemoveFp by remember { mutableStateOf<String?>(null) }
    // ── 黑名单（2026-10-15）──
    var pendingBlockItem by remember { mutableStateOf<PairedDeviceItem?>(null) }
    var pendingUnblockItem by remember { mutableStateOf<BlockedDeviceItem?>(null) }
    var showClearBlacklistDialog by remember { mutableStateOf(false) }
    /**
     * 本机指纹：用来**隐藏"拉黑"按钮**。
     *
     * 服务端也会拒（`PairingManager.blockDevice` 的 `selfFingerprint` 判定），但 UI 不该
     * 给出这个选项 —— 拉黑本机等于把自己踢下线，而且解除拉黑需要已鉴权请求，
     * 没有任何设备能救它（只能清数据重配）。
     */
    // LocalContext.current 必须在 @Composable 上下文里读，不能塞进 remember 的 lambda
    //（remember 的 block 不是 composable）。
    val appContext = LocalContext.current
    val selfFingerprint = remember(appContext) { AppPreferences(appContext).appFingerprint }

    LaunchedEffect(Unit) {
        viewModel.network.loadPairingStatus()
        // 旧 core 没有这组端点 → 404 → state.blacklistAvailable=false → 下面整块隐藏。
        // 静默降级，不弹错误（用户没做错任何事，只是设备端版本旧）。
        viewModel.network.loadBlacklist()
        // 待批准请求（2026-10-15）：同款 404 降级（approvalAvailable=false）。
        viewModel.network.loadApprovals()
    }

    LaunchedEffect(pairingState) {
        if (!pairingState.isLoading) {
            pairingEnabled = pairingState.pairingEnabled
            maxDevices = pairingState.pairingMaxDevices
            approvalRequired = pairingState.approvalRequired
            notifySystem = pairingState.notifySystem
            notifyEmail = pairingState.notifyEmail
            notifyWebhook = pairingState.notifyWebhook
        }
    }

    UfiScreenScaffold(
        title = "安全中心",
        navController = navController,
        showBack = true,
        showHeader = showHeader
    ) { padding ->
        // 入场动画统一收敛到 MainNavGraph 根节点（"app-launch"，只在冷启动播一次）。
        // 二级页本来就有 NavHost 的 detailSharedAxisEnter 平移转场（无 fade），再叠一层淡入+上移是双重动画。
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            pairingState.errorMessage?.let { err ->
                UfiErrorBanner(message = err, onRetry = { viewModel.network.loadPairingStatus() })
            }

            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)
            ) {
                // ═══════════ 本机信息卡（2×2 紧凑网格） ═══════════
                UfiSettingsRowCard(contentPadding = PaddingValues(Spacing.XLarge)) {
                    UfiGroupHeader("本机信息")
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "设备名",
                                value = pairingState.deviceName.ifBlank { pairingState.deviceId }.ifEmpty { "—" }
                            )
                            UfiInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "设备标识",
                                value = pairingState.deviceId.ifEmpty { "—" }
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "配对状态",
                                value = if (pairingState.paired) "已配对" else "未配对"
                            )
                            UfiInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "已配对设备",
                                value = "${pairingState.pairedCount} 台"
                            )
                        }
                    }
                }

                // ═══════════ 已配对设备列表 ═══════════
                val deviceItems = if (pairingState.devices.isNotEmpty()) pairingState.devices
                else pairingState.pairedFingerprints.map { PairedDeviceItem(it, it, 0L, 0L) }
                if (deviceItems.isNotEmpty()) {
                    // 纵向留白交给行内 UfiSettingsItem（自带 10dp 上下），卡只给 8dp，合起来与其它设置卡同高
                    UfiSettingsRowCard(
                        contentPadding = PaddingValues(horizontal = Spacing.XLarge, vertical = Spacing.Medium)
                    ) {
                        UfiGroupHeader("已配对设备")
                        deviceItems.forEachIndexed { idx, item ->
                            if (idx > 0) UfiDivider()
                            UfiSettingsItem(
                                title = item.deviceName.ifBlank { item.fingerprint }
                                    .ifEmpty { "设备 #${idx + 1}" },
                                description = "${item.fingerprint} · ${formatEpochMillis(item.lastSeen)}",
                                icon = Icons.Default.Smartphone,
                                titleMaxLines = 1,
                                descriptionMaxLines = 1,
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
                                    ) {
                                        UfiButton(
                                            text = "编辑",
                                            onClick = {
                                                pendingRenameFp = item.fingerprint
                                                pendingRenameName =
                                                    item.deviceName.ifBlank { item.fingerprint }
                                            },
                                            variant = UfiButtonVariant.Subtle,
                                            size = UfiButtonSize.Small
                                        )
                                        // 拉黑（2026-10-15）：与"移除"是两件事 ——
                                        // 移除=撤销授权（对方拿密码还能回来）；拉黑=明确拒绝
                                        //（重新配对与密码登录都会被 core 的 L1 拦掉）。
                                        // 本机不显示：拉黑自己等于把自己踢下线且无人能解除。
                                        if (item.fingerprint != selfFingerprint) {
                                            UfiButton(
                                                text = "拉黑",
                                                onClick = { pendingBlockItem = item },
                                                variant = UfiButtonVariant.Danger,
                                                size = UfiButtonSize.Small
                                            )
                                        }
                                        UfiButton(
                                            text = "移除",
                                            onClick = { pendingRemoveFp = item.fingerprint },
                                            variant = UfiButtonVariant.Danger,
                                            size = UfiButtonSize.Small
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                // ═══════════ 黑名单（2026-10-15） ═══════════
                //
                // 只在"设备端支持"时渲染（旧 core 没有这组端点 → blacklistAvailable=false，
                // 见 NetworkModule.loadBlacklist 的 404 降级）。
                // 空名单不渲染整块：一张空卡只是噪音，而"怎么拉黑"的入口在每台设备行上。
                if (pairingState.blacklistAvailable && pairingState.blockedDevices.isNotEmpty()) {
                    UfiSettingsRowCard(
                        contentPadding = PaddingValues(horizontal = Spacing.XLarge, vertical = Spacing.Medium)
                    ) {
                        UfiGroupHeader("黑名单")
                        pairingState.blockedDevices.forEachIndexed { idx, item ->
                            if (idx > 0) UfiDivider()
                            UfiSettingsItem(
                                title = item.name.ifBlank { item.fingerprint },
                                description = buildString {
                                    append(item.fingerprint)
                                    if (item.blockedAt > 0) {
                                        append(" · ").append(formatEpochMillis(item.blockedAt))
                                    }
                                },
                                icon = Icons.Default.Block,
                                titleMaxLines = 1,
                                descriptionMaxLines = 1,
                                trailing = {
                                    UfiButton(
                                        text = "解除",
                                        onClick = { pendingUnblockItem = item },
                                        variant = UfiButtonVariant.Subtle,
                                        size = UfiButtonSize.Small
                                    )
                                }
                            )
                        }
                        if (pairingState.blockedDevices.size > 1) {
                            UfiDivider()
                            UfiSettingsItem(
                                title = "全部解除",
                                description = "清空黑名单，被拉黑的设备恢复可配对",
                                trailing = {
                                    UfiButton(
                                        text = "全部解除",
                                        onClick = { showClearBlacklistDialog = true },
                                        variant = UfiButtonVariant.Danger,
                                        size = UfiButtonSize.Small
                                    )
                                }
                            )
                        }
                    }
                }

                // ═══════════ 安全中心：批准策略（2026-10-15） ═══════════
                //
                // 批准默认关；三个通知渠道默认全关（用户 2026-10-15 决定）。
                // 通知子项**嵌在**批准之下：批准不开时它们没有任何意义（没有请求可通知），
                // 摆成孤儿开关只会让人以为"开了没用" —— 方案 §15.2 复盘的那条。
                UfiSettingsRowCard(
                    contentPadding = PaddingValues(horizontal = Spacing.XLarge, vertical = Spacing.Small)
                ) {
                    UfiSettingsToggle(
                        title = "新设备需在 App 上批准",
                        description = if (approvalRequired) {
                            "新设备配对前要在这里点「允许」，或输入通知里的配对码"
                        } else {
                            "关闭时新设备凭配对密码即可配对（与今天一致）"
                        },
                        checked = approvalRequired,
                        onCheckedChange = {
                            approvalRequired = it
                            viewModel.network.updatePairingConfig(
                                pairingEnabled, maxDevices, approvalRequired = it
                            )
                            // 2026-10-21（决策 D5-3）：批准弹窗是 App 内的 Dialog —— **App 不可见
                            // 就弹不出来**，所以机主手机在兜里时，Web 端点了登录要等他解锁才看得到。
                            // 系统通知是锁屏状态下唯一的快通道，而它默认关（"通知默认全关"是既有决定）。
                            // 不改默认值，但在这唯一一个"打开批准"的时刻把话说清。
                            if (it && !notifySystem) {
                                showApprovalNotifyHint = true
                            }
                        }
                    )
                    if (approvalRequired) {
                        UfiDivider()
                        UfiSettingsToggle(
                            title = "发本机系统通知",
                            description = "把配对码推到通知栏，方便直接读",
                            checked = notifySystem,
                            onCheckedChange = {
                                notifySystem = it
                                viewModel.network.updatePairingConfig(
                                    pairingEnabled, maxDevices, notifySystem = it
                                )
                            }
                        )
                        UfiDivider()
                        UfiSettingsToggle(
                            title = "发邮件",
                            description = "走「邮件通知渠道」配置",
                            checked = notifyEmail,
                            onCheckedChange = {
                                notifyEmail = it
                                viewModel.network.updatePairingConfig(
                                    pairingEnabled, maxDevices, notifyEmail = it
                                )
                            }
                        )
                        UfiDivider()
                        UfiSettingsToggle(
                            title = "发 Webhook",
                            description = "走「Webhook 通知渠道」配置",
                            checked = notifyWebhook,
                            onCheckedChange = {
                                notifyWebhook = it
                                viewModel.network.updatePairingConfig(
                                    pairingEnabled, maxDevices, notifyWebhook = it
                                )
                            }
                        )
                        UfiDivider()
                        Text(
                            "这三个渠道是额外闸门：还要过通知总开关与免打扰，所以打开不等于一定收到。" +
                                "配对码会明文外发（已做掩码：来源只留前两段、设备名只留前 2 字），" +
                                "请确认这些渠道只有你能看到。",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(vertical = Spacing.Small)
                        )
                    }
                }

                // ═══════════ 配对限制配置 ═══════════
                UfiSettingsRowCard(
                    contentPadding = PaddingValues(horizontal = Spacing.XLarge, vertical = Spacing.Small)
                ) {
                    UfiSettingsToggle(
                        title = "启用配对数量限制",
                        description = if (pairingEnabled) "开启后将限制可配对设备数量" else "关闭后允许任意数量设备配对",
                        checked = pairingEnabled,
                        onCheckedChange = {
                            pairingEnabled = it
                            viewModel.network.updatePairingConfig(it, maxDevices)
                        }
                    )

                    if (pairingEnabled) {
                        UfiDivider()
                        // remember(maxDevices)：远端回填新的上限后输入框要跟着复位
                        var inputText by remember(maxDevices) { mutableStateOf(maxDevices.toString()) }
                        UfiSettingsItem(
                            title = "最大配对设备数",
                            description = "0 表示不限制数量",
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 数字过滤交给 UfiDigitField；fillMaxWidth = false 保住行内窄宽
                                    UfiDigitField(
                                        value = inputText,
                                        onValueChange = { inputText = it },
                                        label = "数量",
                                        modifier = Modifier.width(Spacing.InlineDigitFieldWidth),
                                        fillMaxWidth = false
                                    )
                                    Spacer(Modifier.width(Spacing.Medium))
                                    UfiButton(
                                        size = UfiButtonSize.Small,
                                        text = "保存",
                                        onClick = {
                                            inputText.toIntOrNull()?.let {
                                                maxDevices = it
                                                viewModel.network.updatePairingConfig(pairingEnabled, it)
                                            }
                                        }
                                    )
                                }
                            }
                        )

                        if (maxDevices > 0 && pairingState.pairedCount > 0) {
                            UfiDivider()
                            val quotaFull = pairingState.pairedCount >= maxDevices
                            Column(modifier = Modifier.padding(vertical = Spacing.Large)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "已用配额",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.textSecondary
                                    )
                                    Text(
                                        "${pairingState.pairedCount} / $maxDevices",
                                        style = UfiTextStyles.noteEmphasis,
                                        color = if (quotaFull) palette.error else palette.textPrimary
                                    )
                                }
                                Spacer(Modifier.height(Spacing.Small))
                                UfiCompactProgressBar(
                                    progress = pairingState.pairedCount.toFloat() / maxDevices,
                                    color = if (quotaFull) palette.error else palette.accent,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // ═══════════ 解除全部配对 ═══════════
                if (pairingState.paired) {
                    UfiSettingsRowCard(contentPadding = PaddingValues(Spacing.XLarge)) {
                        UfiButton(
                            text = "解除全部配对",
                            onClick = { showUnpairDialog = true },
                            variant = UfiButtonVariant.Danger,
                            icon = Icons.Default.Delete
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }

    // ─ 重命名设备（阶段3：PATCH /api/pairing/devices/{fp}） ──
    pendingRenameFp?.let { fp ->
        UfiInputDialog(
            title = "重命名设备",
            initialValue = pendingRenameName,
            hint = "输入新的设备名（1-32 字符）",
            confirmText = "保存",
            onConfirm = { name ->
                if (name.isNotBlank()) viewModel.network.renameDevice(fp, name)
                pendingRenameFp = null
            },
            onDismiss = { pendingRenameFp = null }
        )
    }

    // ─ 移除单个设备（阶段3：需配对密码，DELETE /api/pairing/devices/{fp}） ──
    // 按钮走 actions 槽位而不是塞进 body：塞进 body 会让底部按钮参与内容滚动，
    // 且 footer 高度预算算不准（见 UfiScrollableDialog 的 FIX-24）。
    pendingRemoveFp?.let { fp ->
        var pwd by remember(fp) { mutableStateOf("") }
        UfiScrollableDialog(
            visible = true,
            onDismiss = { pendingRemoveFp = null },
            title = "移除设备",
            icon = rememberVectorPainter(Icons.Filled.Link),
            showCloseButton = false,
            actions = {
                UfiDialogActions(
                    onDismiss = { pendingRemoveFp = null },
                    onConfirm = {
                        viewModel.network.removeDevice(fp, pwd)
                        pendingRemoveFp = null
                    },
                    confirmText = "移除",
                    confirmDestructive = true,
                    enabled = pwd.isNotBlank()
                )
            }
        ) {
            UfiDialogBody {
                Text(
                    "移除后该客户端需要重新配对才能连接。请输入配对密码确认。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                UfiDialogPasswordField(
                    label = "配对密码",
                    value = pwd,
                    onValueChange = { pwd = it }
                )
            }
        }
    }

    // ─ 新设备批准弹窗：**已移到 App 根部**（MainActivity 的 UfiPairingApprovalDialog）──
    //
    // 2026-10-15：原来只在页面内自动弹。但批准请求可能在机主任何页面上到来，
    // 而"必须给出结论"的状态不该要求他先摸到这一页 —— 所以弹窗挂在根部全局生效。
    // 这里保留 `loadApprovals()`（进页面时刷一次，让下面的待批准提示与状态一致），
    // 但**不再**自己弹窗，避免同一个请求弹两次。

    // ─ 拉黑设备（2026-10-15：POST /api/pairing/blacklist，需配对密码） ──
    //
    // 与「移除设备」的区别写进正文了：用户最容易误解的就是"移除=拉黑"。
    // hwId 一并提交：指纹是权威键但清 App 数据就换，hwId 跨重装稳定 —— 两条一起才挡得住
    // "清数据再配对"。它可伪造，所以只算提高门槛。
    pendingBlockItem?.let { item ->
        var pwd by remember(item.fingerprint) { mutableStateOf("") }
        UfiScrollableDialog(
            visible = true,
            onDismiss = { pendingBlockItem = null },
            title = "拉黑设备",
            showCloseButton = false,
            actions = {
                UfiDialogActions(
                    onDismiss = { pendingBlockItem = null },
                    onConfirm = {
                        viewModel.network.blockDevice(
                            fingerprint = item.fingerprint,
                            name = item.deviceName,
                            hwId = item.hwId,
                            password = pwd
                        )
                        pendingBlockItem = null
                    },
                    confirmText = "拉黑",
                    confirmDestructive = true,
                    enabled = pwd.isNotBlank()
                )
            }
        ) {
            UfiDialogBody {
                Text(
                    "拉黑后这台设备不能重新配对、也不能用配对密码登录；若它当前在线会立刻掉线。" +
                        "即使它清除 App 数据换了密钥，也会被硬件标识挡住。\n\n" +
                        "与「移除设备」不同：移除只是撤销授权，对方拿密码还能回来；拉黑是明确拒绝。" +
                        "解除拉黑需要配对密码。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                UfiDialogPasswordField(
                    label = "配对密码",
                    value = pwd,
                    onValueChange = { pwd = it }
                )
            }
        }
    }

    // ─ 解除拉黑 / 全部解除（同一套密码门禁） ──
    pendingUnblockItem?.let { item ->
        var pwd by remember(item.fingerprint) { mutableStateOf("") }
        UfiScrollableDialog(
            visible = true,
            onDismiss = { pendingUnblockItem = null },
            title = "解除拉黑",
            showCloseButton = false,
            actions = {
                UfiDialogActions(
                    onDismiss = { pendingUnblockItem = null },
                    onConfirm = {
                        viewModel.network.unblockDevice(item.fingerprint, pwd)
                        pendingUnblockItem = null
                    },
                    confirmText = "解除",
                    enabled = pwd.isNotBlank()
                )
            }
        ) {
            UfiDialogBody {
                Text(
                    "解除后「${item.name.ifBlank { item.fingerprint }}」可以重新配对。" +
                        "若它曾被拉黑时正在线，需要重新配对一次才能恢复连接。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                UfiDialogPasswordField(
                    label = "配对密码",
                    value = pwd,
                    onValueChange = { pwd = it }
                )
            }
        }
    }

    if (showClearBlacklistDialog) {
        var pwd by remember { mutableStateOf("") }
        UfiScrollableDialog(
            visible = true,
            onDismiss = { showClearBlacklistDialog = false },
            title = "全部解除拉黑",
            showCloseButton = false,
            actions = {
                UfiDialogActions(
                    onDismiss = { showClearBlacklistDialog = false },
                    onConfirm = {
                        viewModel.network.clearBlacklist(pwd)
                        showClearBlacklistDialog = false
                    },
                    confirmText = "全部解除",
                    confirmDestructive = true,
                    enabled = pwd.isNotBlank()
                )
            }
        ) {
            UfiDialogBody {
                Text(
                    "将清空整份黑名单（${pairingState.blockedDevices.size} 台），之后它们都能重新配对。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                UfiDialogPasswordField(
                    label = "配对密码",
                    value = pwd,
                    onValueChange = { pwd = it }
                )
            }
        }
    }

    // 2026-10-24：修改配对密码的弹窗已搬到 `ServerConfigScreen` 的「连接配置 → 配对密码」
    // 页签（与设备后台同一套布局）。本页不再持有任何修改入口。


    /**
     * 打开「新设备需批准」时的系统通知引导（2026-10-21 决策 D5-3）。
     *
     * 批准弹窗是 App 内的 Dialog —— **App 不可见就弹不出来**（锁屏/后台时尤其明显）。
     * 系统通知是那种状态下唯一的快通道，而它默认关。不改默认值，但在这唯一一个
     * "用户主动打开批准"的时刻把利弊说清，并给一键开启。
     */
    if (showApprovalNotifyHint) {
        UfiScrollableDialog(
            visible = true,
            onDismiss = { showApprovalNotifyHint = false },
            title = "建议同时开启系统通知",
            showCloseButton = false,
            actions = {
                UfiDialogActions(
                    onDismiss = { showApprovalNotifyHint = false },
                    onConfirm = {
                        notifySystem = true
                        viewModel.network.updatePairingConfig(
                            pairingEnabled, maxDevices, notifySystem = true
                        )
                        showApprovalNotifyHint = false
                    },
                    confirmText = "同时开启",
                    dismissText = "暂不"
                )
            }
        ) {
            UfiDialogBody {
                Text(
                    "批准弹窗只在 App 可见时才会出现：手机锁屏或 App 在后台时，新设备会一直等到你打开 App。\n\n" +
                        "开启「发本机系统通知」后，配对码会同时推到通知栏，你可以在锁屏界面直接看到并输入。\n\n" +
                        "不开也能用，只是锁屏期间收不到提醒。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            }
        }
    }

    // ── 解除全部配对确认（2026-10-21：改为**必须输入配对密码**，审计 §B1） ──
    //
    // 之前这条是"点一下全部踢掉"，而「移除单台」反倒要密码 —— 破坏性最强的操作门槛最低，
    // 双因素等于被绕过。core 的两条 unpair 端点已同步收紧，这里补输入框。
    if (showUnpairDialog) {
        var pwd by remember { mutableStateOf("") }
        UfiScrollableDialog(
            visible = true,
            onDismiss = { showUnpairDialog = false },
            title = "解除全部配对",
            showCloseButton = false,
            actions = {
                UfiDialogActions(
                    onDismiss = { showUnpairDialog = false },
                    onConfirm = {
                        // 失败时保持弹窗打开（onResult 带错误文案），让用户改密码重试
                        viewModel.network.unpairAll(pwd) { err ->
                            if (err == null) showUnpairDialog = false
                        }
                    },
                    confirmText = "确定解除",
                    confirmDestructive = true,
                    enabled = pwd.isNotBlank()
                )
            }
        ) {
            UfiDialogBody {
                Text(
                    "确定解除全部 ${pairingState.pairedCount} 台设备的配对？" +
                        "解除后**所有**客户端（含本机）都需要重新配对。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                UfiDialogPasswordField(
                    label = "配对密码",
                    value = pwd,
                    onValueChange = { pwd = it }
                )
            }
        }
    }
}

/** 格式化 epoch millis 为 "yyyy-MM-dd HH:mm"（本地时区）；非法/0 返回 "—"。 */
private fun formatEpochMillis(ms: Long): String {
    if (ms <= 0L) return "—"
    return try {
        java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } catch (e: Exception) {
        "—"
    }
}
