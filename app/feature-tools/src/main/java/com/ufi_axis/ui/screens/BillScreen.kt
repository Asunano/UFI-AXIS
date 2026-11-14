package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import kotlin.math.sin
import kotlin.math.cos
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.ui.theme.UfiCardDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.navigation.ufiDetailTransitionMs
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.navigation.Routes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.SizeTransform
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.ufi_axis.ui.components.common.UfiPopupOption
import com.ufi_axis.ui.components.common.UfiPopupAnchor
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ufi_axis.data.bill.BillAccessMethod
import com.ufi_axis.data.bill.BillPackage
import com.ufi_axis.data.bill.BillPackageKind
import com.ufi_axis.data.bill.BillSnapshot
import com.ufi_axis.data.model.CarrierBillSettingsState
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.BillUiState
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 话费账单（2026-10-31，方案 B「余额与到期」）。
 *
 * 数据来自 [com.ufi_axis.data.bill.BillRepository]：当前 `BillRepositoryProvider` 默认挂的是
 * 演示数据，接入真实营业厅数据时只换 provider，本页不动。
 *
 * 页面结构（与审核稿 `dev-docs/mockups/bill-ui/app/02-balance.html` 对应）：
 * 1. 余额卡：余额大字 + 上月/本期/积分 + 账号/套餐/机主；
 * 2. 流量卡：2×2 指标格（通用/定向/合计/本月累计），本月累计单独标黄；
 * 3. 即将到期：24 小时内到期的包，按到期时间排序；
 * 4. 全部流量包：chip 筛选通用/定向；
 *
 * 所有金额/GB 都用 `%.2f`；「今天/明天到期」按设备本地时间换算，查询时间之外会自然漂移。
 */
@Composable
fun BillScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    /** 非空 = 该账号的明细页（路由 `detail/bill/account/{accountId}`）。 */
    accountId: String? = null,
    /** true = 添加账号（路由 `detail/bill/add`）。 */
    isAdd: Boolean = false,
) {
    val accounts by viewModel.tools.carrierAccounts.collectAsState()
    val accountBills by viewModel.tools.accountBills.collectAsState()
    val activeId by viewModel.tools.activeCarrierAccountId.collectAsState()
    val state by viewModel.tools.billState.collectAsState()
    val billsAt by viewModel.tools.accountBillAt.collectAsState()
    val accountsLoading by viewModel.tools.carrierAccountsLoading.collectAsState()

    val detailId = accountId?.takeIf { it.isNotBlank() }
    val detailAccount = detailId?.let { id -> accounts.firstOrNull { it.id == id } }
    // 明细页必须读该账号自己的账单状态。用全局 billState 会拿到上一个活动账号的
    // 缓存账单 —— 截图里「136 移动号 + 联通流量包 + 移动登录卡」就是这么拼出来的。
    val detailState = detailId?.let { accountBills[it] ?: BillUiState.Loading } ?: state

    // 列表页拉账号 + 当前账号账单；明细页把目标账号设为"当前账号"（老接口语义）。
    LaunchedEffect(detailId, isAdd) {
        if (isAdd) return@LaunchedEffect
        if (detailId == null) {
            // 列表页：拉账号 + 当前账号账单 + 设备级自动登录摘要。
            viewModel.tools.loadCarrierAccounts()
            viewModel.tools.loadBill()
        } else {
            // 明细页**不再重拉账号列表**（2026-11-08）：点「去登录」时列表页还在退场，
            // 那时刷新会把卡片打成"读取中…"，看起来就是"点一下触发了加载"。
            // 账号已经在列表里加载过；只有深链直接进明细（accounts 为空）才补一次。
            if (accounts.isEmpty()) viewModel.tools.loadCarrierAccounts()
            if (detailId != activeId) viewModel.tools.activateCarrierAccount(detailId)
            // 明细页只刷新该账号的状态；不再用全局 loadBill 把别的账号数据带进来。
            viewModel.tools.loadAccountBill(detailId, force = true)
        }
    }

    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameInitial by remember { mutableStateOf("") }
    var deleteTargetId by remember { mutableStateOf<String?>(null) }

    when {
        // ── 添加账号：无顶栏，卡片垂直居中 ──────────────────────────────
        isAdd -> {
            UfiScreenScaffold(
                title = "添加账号",
                navController = navController,
                showHeader = true,
                showBack = true,
            ) { padding ->
                BillCenteredPage(modifier = Modifier.padding(padding)) {
                        BillLoginPanel(
                            viewModel = viewModel,
                            message = null,
                            isWizard = true,
                            onLoggedIn = {
                                viewModel.tools.refreshCarrierStatus()
                                viewModel.tools.loadCarrierAccounts()
                                viewModel.tools.loadBill(force = true)
                                navController.popBackStack()
                            },
                        )
                }
            }
        }

        // ── 账号明细：顶栏 + 账号操作 + 单账号登录卡 ────────────────────
        detailId != null -> {
            UfiScreenScaffold(
                // 顶栏同时有刷新/设置两个动作；用固定短标题，避免长手机号把刷新键压在下面。
                // 完整手机号在登录卡「已记录手机号」和账单内容里展示。
                title = "账号详情",
                navController = navController,
                showBack = true,
                actions = {
                    IconButton(
                        onClick = {
                            detailId?.let { viewModel.tools.loadAccountBill(it, force = true) }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(
                        onClick = { navController.ufiNavigateOnce(Routes.DETAIL_BILL_SETTINGS) }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "账单设置")
                    }
                },
            ) { padding ->
                BillCenteredPage(
                    modifier = Modifier.padding(padding),
                    // 需要登录时页面只剩一张登录卡：居中，不再让卡贴在顶部；
                    // 有账单/错误态时仍顶对齐，避免长内容被强行居中裁切。
                    center = detailState is BillUiState.NeedsLogin,
                ) {
                    BillActiveAccountBody(
                        state = detailState,
                        viewModel = viewModel,
                        accountId = detailId,
                        lockedCarrier = billCarrierIdFor(detailAccount),
                        accountPhone = detailAccount?.phone,
                        onRename = {
                            renameTargetId = detailId
                            renameInitial = detailAccount?.label.orEmpty()
                        },
                        onDelete = { deleteTargetId = detailId },
                        onOpenKeepAliveSettings = {
                            navController.ufiNavigateOnce(
                                Routes.DETAIL_BILL_SETTINGS_AUTO_LOGIN
                            )
                        },
                    )
                }
            }
        }

        // ── 默认页：账号卡片列表 ─────────────────────────────────────────
        else -> {
            UfiScreenScaffold(
                title = "话费账单",
                navController = navController,
                showBack = true,
                actions = {
                    // 添加上移到顶栏：列表里不再放一整行按钮（2026-11-07，方案 C）。
                    // 顶栏只保留「添加」与「设置」两个动作；下拉/卡片自己的刷新已足够，
                    // 三个动作会把＋挤到标题下面（2026-11-12 实测 880dp 宽）。
                    IconButton(onClick = { navController.ufiNavigateOnce(Routes.DETAIL_BILL_ADD) }) {
                        Icon(Icons.Default.Add, contentDescription = "添加账号")
                    }
                    IconButton(
                        onClick = { navController.ufiNavigateOnce(Routes.DETAIL_BILL_SETTINGS) }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "账单设置")
                    }
                },
            ) { padding ->
                UfiPageBackground(modifier = Modifier.padding(padding)) {
                    when {
                        accounts.isNotEmpty() -> BillAccountList(
                            accounts = accounts,
                            bills = accountBills,
                            billsAt = billsAt,
                            onOpen = { navController.ufiNavigateOnce(Routes.billAccountRoute(it)) },
                            onRefresh = { viewModel.tools.loadAccountBill(it, force = true) },
                            onRename = { id ->
                                renameTargetId = id
                                renameInitial = accounts.firstOrNull { it.id == id }?.label.orEmpty()
                            },
                            onDelete = { id -> deleteTargetId = id },
                        )

                        accountsLoading -> BillAccountsSkeleton()

                        else -> BillNoAccounts(
                            onAdd = { navController.ufiNavigateOnce(Routes.DETAIL_BILL_ADD) },
                        )
                    }
                }
            }
        }
    }

    renameTargetId?.let { id ->
        UfiInputDialog(
            title = "账号备注",
            initialValue = renameInitial,
            hint = "留空则显示手机号",
            confirmText = "保存",
            onConfirm = { value ->
                viewModel.tools.renameCarrierAccount(id, value.trim())
                renameTargetId = null
            },
            onDismiss = { renameTargetId = null },
        )
    }

    val dialogDeleteTargetId = rememberLastNonNull(deleteTargetId)
    if (dialogDeleteTargetId != null) {
        val id = dialogDeleteTargetId
        val title = accounts.firstOrNull { it.id == id }?.displayTitle ?: id
        UfiConfirmDialog(
        visible = deleteTargetId != null,
            title = "删除账号",
            text = "将删除「$title」在本机的会话与自动登录配置。" +
                "账号本身不受影响，之后可以重新登录。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                viewModel.tools.removeCarrierAccount(id)
                deleteTargetId = null
                // 列表页删除后必须留在列表；只有账号明细页删除才返回上一页。
                if (detailId != null) navController.popBackStack()
            },
            onDismiss = { deleteTargetId = null },
        )
    }

}

/**
 * 单个账号的完整内容：登录卡 / 账单 / 错误态。
 *
 * 明细页不走向导（运营商已锁定），所以这里只把 `accountId` 与 `lockedCarrier` 交给登录卡。
 */
@Composable
private fun BillActiveAccountBody(
    state: BillUiState,
    viewModel: MainViewModel,
    accountId: String? = null,
    lockedCarrier: String? = null,
    accountPhone: String? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onOpenKeepAliveSettings: (() -> Unit)? = null,
) {
    when (val current = state) {
        BillUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            UfiLoadingIndicator()
        }

        is BillUiState.NeedsLogin -> {
            // 账号明细页在会话失效时只显示这张登录卡；缓存账单只在账号列表卡片里展示。
            // 之前把缓存内容和登录卡拼在同一滚动页，登录卡会被看成“插在流量包下面”。
            BillLoginPanel(
                viewModel = viewModel,
                message = current.message,
                expired = true,
                accountId = accountId,
                accountPhone = accountPhone,
                lockedCarrier = lockedCarrier,
                onRename = onRename,
                onDelete = onDelete,
                onOpenKeepAliveSettings = onOpenKeepAliveSettings,
                onLoggedIn = {
                    viewModel.tools.refreshCarrierStatus()
                    if (accountId != null) viewModel.tools.loadAccountBill(accountId, force = true)
                    viewModel.tools.loadBill(force = true)
                },
            )
        }

        is BillUiState.Error -> BillErrorState(
            message = current.message,
            onRetry = {
                if (accountId != null) viewModel.tools.loadAccountBill(accountId, force = true)
                else viewModel.tools.loadBill(force = true)
            },
        )

        is BillUiState.Ready -> {
            if (current.bill.stale) {
                BillCachedNotice(
                    title = "实时刷新失败",
                    bill = current.bill,
                )
            }
            BillContent(current.bill)
            // 自动登录只在登录卡里提供，已登录账单页不再单独渲染一张卡。
            // 之前这里还有 BillAutoLoginControls(inCard = true)，导致明细页底部
            // 在流量包后面又出现「自动登录」卡（2026-11-12 用户反馈）。
        }
    }
}

/**
 * 卡片居中页：内容短时**垂直 + 水平**居中，长了照常滚动。
 *
 * 登录/配对页就是这种形态（一张卡浮在页面中间）。`UfiPageBackground` 是滚动 Column、
 * 内容顶对齐，做不出居中，所以这里自己搭一层 Box + 可滚动 Column。
 */
@Composable
private fun BillCenteredPage(
    modifier: Modifier = Modifier,
    /** 登录/添加页短内容居中；账号明细是长内容，必须顶对齐，否则上下各留一大块空白。 */
    center: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palettePageBg()),
        contentAlignment = if (center) Alignment.Center else Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * 「扫码登录 / 服务密码」切换的过渡：淡入 + 轻微放大，尺寸用 `SizeTransform` 平滑。
 *
 * 与向导步骤切换同一套手感，但更短（200/240ms）—— 这是同一张卡内的控件替换，
 * 不是换页，动效不该抢戏。
 */
private fun billMethodTransition(): androidx.compose.animation.ContentTransform =
    androidx.compose.animation.ContentTransform(
        targetContentEnter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.96f),
        initialContentExit = fadeOut(tween(140)),
        sizeTransform = SizeTransform(clip = false),
    )

/** 一行两个等宽按钮：主次并排，不再各占一整行。 */
@Composable
private fun BillButtonPair(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
    ) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
}

/** 没有账号时的空态：一句话 + 唯一动作。 */
/**
 * 没有账号时的空态：图标 + 一句标题 + 一句说明 + 唯一动作。
 *
 * 空态里保留按钮（即使顶栏也有 ＋）：用户第一眼看到的是页面中央，不该让他去找右上角。
 */
@Composable
private fun BillNoAccounts(onAdd: () -> Unit) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(Spacing.CornerLarge))
                .background(palette.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Text("¥", style = UfiTextStyles.panelTitle, color = palette.textSecondary)
        }
        Spacer(Modifier.height(Spacing.Medium))
        Text("还没有账号", style = UfiTextStyles.cardTitle, color = palette.textPrimary)
        Text(
            "添加运营商账号或物联卡后，可以查看余额、流量与套餐。\n登录状态保存在设备 core，App / Web 共用。",
            style = UfiTextStyles.note,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Medium))
        UfiButton(
            text = "＋ 添加账号",
            onClick = onAdd,
            size = UfiButtonSize.Standard,
            modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}

/** 账号列表：卡片之间留 12dp，没有"添加"整行按钮（入口在顶栏）。 */
@Composable
private fun BillAccountList(
    accounts: List<com.ufi_axis.data.model.CarrierAccountDto>,
    bills: Map<String, BillUiState>,
    billsAt: Map<String, Long>,
    onOpen: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 2026-11-09：不再按运营商/物联卡分组，账号按 core 返回顺序连续展示。
        // 2026-11-13：账单列表不显示自动登录摘要/入口；自动登录在「账单设置」独立页。
        accounts.forEach { account ->
            BillAccountCard(
                account = account,
                state = bills[account.id],
                fetchedAt = billsAt[account.id],
                onOpen = { onOpen(account.id) },
                onRefresh = { onRefresh(account.id) },
                onRename = { onRename(account.id) },
                onDelete = { onDelete(account.id) },
            )
        }
        Spacer(Modifier.height(Spacing.Large))
    }
}

/** 首屏骨架：账号列表还没回来时铺两张占位卡，避免空态一闪而过。 */
@Composable
private fun BillAccountsSkeleton() {
    val palette = LocalResolvedPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(2) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.CardHorizontalMargin)
                    .padding(bottom = Spacing.SettingsCardGap)
                    .ufiStandardCard()
                    .padding(Spacing.CardPadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.surfaceMuted)
                    )
                    Spacer(Modifier.width(Spacing.Small))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(width = 96.dp, height = 14.dp).skeleton(palette.surfaceMuted))
                        Box(Modifier.size(width = 150.dp, height = 10.dp).skeleton(palette.surfaceMuted))
                    }
                }
                Box(Modifier.size(width = 200.dp, height = 10.dp).skeleton(palette.surfaceMuted))
            }
        }
    }
}

private fun Modifier.skeleton(color: androidx.compose.ui.graphics.Color): Modifier =
    clip(RoundedCornerShape(6.dp)).background(color)

/**
 * 一张账号卡（2026-11-07 方案 C + L1/M2）。
 *
 * ```
 * [移] 主卡                    ¥23.82
 *      138****8000 · 中国移动     余额
 * ─────────────────────────────────────
 *  通用剩余   定向剩余   10 月账单
 *  34.0 G     12.5 G     ¥11.05
 * ─────────────────────────────────────
 *  10 分钟前更新                  ⋯
 * ```
 *
 * 余额标签居中放在数值下方（固定宽 86dp），两个账号的余额与标签永远对齐。
 */
@Composable
private fun BillAccountCard(
    account: com.ufi_axis.data.model.CarrierAccountDto,
    state: BillUiState?,
    fetchedAt: Long?,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalResolvedPalette.current
    val bill = when (state) {
        is BillUiState.Ready -> state.bill
        is BillUiState.NeedsLogin -> state.cachedBill
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin)
            .padding(bottom = Spacing.SettingsCardGap)
            .ufiStandardCard()
            .clickable(onClick = onOpen),
    ) {
        // ── 头部：身份 + 余额 ──
        Row(
            modifier = Modifier.padding(horizontal = Spacing.CardPadding, vertical = Spacing.Large),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.displayTitle,
                    style = UfiTextStyles.cardTitle,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (account.displaySubtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = account.displaySubtitle,
                        style = UfiTextStyles.captionTiny,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (account.isIotCard) {
                // 物联卡列表展示“卡状态”，不展示没有信息量的 ¥0.00 余额。
                // 会话失效时优先显示"需要登录"，即使缓存里的卡状态还是"正常"。
                val status = if (state is BillUiState.NeedsLogin) {
                    "需要登录"
                } else {
                    bill?.cloudCard?.statusText ?: "—"
                }
                Column(
                    modifier = Modifier.widthIn(min = 86.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = status,
                        style = UfiTextStyles.metricValueCompact,
                        color = when (status) {
                            "正常" -> palette.success
                            "需要登录", "—" -> palette.textSecondary
                            else -> palette.warning
                        },
                        maxLines = 1,
                    )
                    Text("状态", style = UfiTextStyles.captionTiny, color = palette.textSecondary)
                }
            } else {
                // 余额：数值在上、"余额"居中在下
                Column(
                    modifier = Modifier.widthIn(min = 86.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = bill?.balanceYuan?.let { "¥${"%.2f".format(it)}" } ?: "—",
                        style = UfiTextStyles.metricValueCompact,
                        color = if (bill != null) palette.textPrimary else palette.textSecondary,
                        maxLines = 1,
                    )
                    Text("余额", style = UfiTextStyles.captionTiny, color = palette.textSecondary)
                }
            }
        }

        when {
            bill != null -> {
                // ── 数据条：三栏 ──
                if (account.isIotCard) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.CardPadding),
                    ) {
                        BillPlainMetric(
                            value = "${"%.1f".format(bill.flow.generalUsedGb)} GB",
                            label = "已用",
                            sub = "虚量口径",
                            modifier = Modifier.weight(1f),
                        )
                        BillPlainMetric(
                            value = if (bill.flow.generalUnlimited) {
                                "不限量"
                            } else {
                                "${"%.1f".format(bill.flow.generalRemainingGb)} GB"
                            },
                            label = "剩余",
                            sub = if (bill.flow.generalUnlimited) "不限量" else "当前套餐",
                            modifier = Modifier.weight(1f),
                        )
                        val daysLeft = bill.cloudCard?.daysLeft
                        BillPlainMetric(
                            value = when {
                                daysLeft == null -> "—"
                                daysLeft < 0 -> "已过期"
                                else -> "$daysLeft 天"
                            },
                            label = "到期",
                            sub = bill.cloudCard?.expiresAt?.take(10) ?: "—",
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    // 与头部、底部同一档左右内边距：三栏不再顶到卡片边缘，
                    // 每栏的数值/标签/说明才能在同一条垂直中轴上居中。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.CardPadding)
                    ) {
                        BillPlainMetric(
                            value = "${"%.1f".format(bill.flow.generalRemainingGb)} GB",
                            label = "通用剩余",
                            // 三栏时列宽有限；去掉“共”和多余小数位，避免说明文字横向顶出列宽。
                            sub = "已用 ${gbCompact(bill.flow.generalUsedGb)}/${gbCompact(bill.flow.generalTotalGb)} GB",
                            modifier = Modifier.weight(1f),
                        )
                        BillPlainMetric(
                            value = "${"%.1f".format(bill.flow.directedRemainingGb)} GB",
                            label = "定向剩余",
                            sub = "已用 ${gbCompact(bill.flow.directedUsedGb)}/${gbCompact(bill.flow.directedTotalGb)} GB",
                            modifier = Modifier.weight(1f),
                        )
                        BillPlainMetric(
                            value = bill.currentBillYuan?.let { "¥${"%.2f".format(it)}" } ?: "—",
                            label = billPeriodLabel(bill.currentBillPeriod),
                            sub = bill.currentBillAsOf?.let { "截至 $it" } ?: "",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            state is BillUiState.NeedsLogin -> {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.CardPadding, vertical = Spacing.Large),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (!account.isIotCard) {
                            Text("需要登录", style = UfiTextStyles.caption, color = palette.warning)
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            if (account.isIotCard) "登录后可查看流量、到期与订单"
                            else "登录后可查看余额、流量与本期账单",
                            style = UfiTextStyles.captionTiny,
                            color = palette.textSecondary,
                        )
                    }
                    UfiButton(
                        text = "去登录",
                        onClick = onOpen,
                        size = UfiButtonSize.Small,
                        modifier = Modifier.widthIn(min = 76.dp),
                    )
                }
            }

            state is BillUiState.Error -> {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.CardPadding, vertical = Spacing.Large),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("拉取失败", style = UfiTextStyles.caption, color = palette.error)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            state.message,
                            style = UfiTextStyles.captionTiny,
                            color = palette.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    UfiButton(
                        text = "重试",
                        onClick = onRefresh,
                        variant = UfiButtonVariant.Secondary,
                        size = UfiButtonSize.Small,
                    )
                }
            }

            else -> {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.CardPadding, vertical = Spacing.Large),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("读取中…", style = UfiTextStyles.caption, color = palette.textSecondary)
                }
            }
        }

        // ── 底部：更新时间 + ⋯ ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.CardPadding, end = Spacing.Small, top = Spacing.Small)
                .heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    bill != null -> relativeUpdateText(fetchedAt)
                    // 卡片中部已经有「需要登录」；底部再写一次「未登录」是同义重复。
                    state is BillUiState.NeedsLogin -> "—"
                    state is BillUiState.Error -> "上次拉取失败"
                    else -> "读取中…"
                },
                style = UfiTextStyles.captionTiny,
                color = palette.textSecondary,
                modifier = Modifier.weight(1f),
            )
            BillAccountMenu(onRefresh = onRefresh, onRename = onRename, onDelete = onDelete)
        }
    }
}

/** 卡片上的 ⋯：刷新 / 改名 / 删除。 */
@Composable
private fun BillAccountMenu(onRefresh: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalResolvedPalette.current
    UfiPopupAnchor(
        options = listOf(
            UfiPopupOption(id = "refresh", label = "刷新这个账号", onClick = onRefresh),
            UfiPopupOption(id = "rename", label = "改名", onClick = onRename),
            UfiPopupOption(id = "delete", label = "删除账号", isDestructive = true, onClick = onDelete),
        ),
    ) { toggle ->
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Spacing.CornerSmall))
                .clickable(onClick = toggle),
            contentAlignment = Alignment.Center,
        ) {
            Text("⋯", style = UfiTextStyles.body, color = palette.textSecondary)
        }
    }
}

@Composable
private fun BillCardDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalResolvedPalette.current.divider)
    )
}

@Composable
private fun BillCardVDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(IntrinsicSize.Min)
            .fillMaxHeight()
            .background(LocalResolvedPalette.current.divider)
    )
}

/**
 * 「本期账单」的标签：直接带月份，例如 `202610` → 「10 月账单」。
 *
 * M2 方案（用户选的）：标签本身就是月份，不再额外用一行小字标账期 —— 一栏里只有
 * 标签和金额，最不容易看混。跨年时补上年份。
 */
private fun billPeriodLabel(period: String?): String {
    if (period.isNullOrBlank() || period.length < 6) return "本期账单"
    val year = period.take(4).toIntOrNull() ?: return "本期账单"
    val month = period.substring(4, 6).toIntOrNull()?.takeIf { it in 1..12 } ?: return "本期账单"
    val nowYear = java.time.LocalDate.now().year
    return if (year == nowYear) "$month 月账单" else "${year % 100} 年 $month 月账单"
}

/** 「x 分钟前更新」：只有拉到过账单才有时间。 */
private fun relativeUpdateText(at: Long?): String {
    if (at == null || at <= 0) return "尚未更新"
    val diff = System.currentTimeMillis() - at
    return when {
        diff < 60_000 -> "刚刚更新"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前更新"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前更新"
        else -> "${diff / 86_400_000} 天前更新"
    }
}

/** 页面底色（与 UfiPageBackground 同源，供自绘容器使用）。 */
@Composable
private fun palettePageBg(): androidx.compose.ui.graphics.Color =
    LocalResolvedPalette.current.pageBg

/** 小状态胶囊：不抢主信息，但能一眼分辨"当前 / 需要登录 / 异常"。 */
@Composable
private fun BillStatePill(text: String, accent: Boolean) {
    val palette = LocalResolvedPalette.current
    Text(
        text = text,
        style = UfiTextStyles.captionTiny,
        color = if (accent) palette.accent else palette.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (accent) palette.accent.copy(alpha = 0.10f) else palette.surfaceMuted)
            .padding(horizontal = Spacing.Small, vertical = 2.dp),
    )
}

/**
 * 登录卡里的「自动登录」状态行（2026-11-12）。
 *
 * 这里**只显示状态**，不再放开关/间隔/立即登录：那些都是设备级设置，
 * 统一在 `BillSettingsScreen` 里改。点整行进入设置页。
 *
 * 账号不支持（非移动 / SIM 不匹配 / 没有完整手机号）时**整行不渲染**；
 * 支持但未参与时作为进入自动登录设置的入口。
 */
@Composable
private fun BillKeepAliveStatus(
    viewModel: MainViewModel,
    accountId: String?,
    onOpenSettings: (() -> Unit)?,
) {
    if (accountId == null) return
    val settings by viewModel.tools.billSettings.collectAsState()
    LaunchedEffect(accountId) { viewModel.tools.loadBillSettings() }
    val current = settings ?: return
    val account = current.accounts.firstOrNull { it.id == accountId } ?: return
    // 不支持自动登录的账号不渲染这一行：显示「中国移动 · 不支持」只会制造噪音。
    if (!account.supported) return
    val description = when {
        !current.keepalive.enabled ->
            if (account.participating) "已参与 · 总开关未开" else "未参与 · 总开关未开"
        !account.participating -> "未参与 · 不会自动登录"
        else -> "已参与 · ${keepAlivePhaseLabel(account.phase)}"
    }
    UfiDivider()
    UfiSettingsItem(
        title = "自动登录",
        description = description,
        icon = Icons.Default.Autorenew,
        trailing = onOpenSettings?.let { { UfiSettingsChevron() } },
        onClick = onOpenSettings,
    )
}

@Composable
private fun BillLoginPanel(
    viewModel: MainViewModel,
    message: String?,
    /** 明细页 409 / loginRequired：明确告诉用户“会话已过期”，而不是只给一张登录表。 */
    expired: Boolean = false,
    onLoggedIn: () -> Unit,
    /** 目标账号；null = 添加账号（还没有账号）。 */
    accountId: String? = null,
    /** 明细页已缓存手机号的脱敏展示；非空时不再让用户重输手机号。 */
    accountPhone: String? = null,
    /**
     * 锁定运营商（账号明细页传入该账号的运营商）。
     *
     * 明细页是"给这个账号登录"，改运营商等于换账号 —— 那条路应该走「全部账号 → 添加账号」。
     */
    lockedCarrier: String? = null,
    /**
     * **步骤式向导**（运营商 → 手机号 → 验证码 / 扫码）。添加账号页传 true。
     *
     * 账号明细不走向导：运营商已锁定、只剩"重新登录"一件事，套步骤条反而多两层。
     */
    isWizard: Boolean = false,
    /** 明细页卡内的账号操作（2×2 按钮的后两个）。 */
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** 打开设备级「账单设置」页（登录卡里的自动登录状态行）。 */
    onOpenKeepAliveSettings: (() -> Unit)? = null,
) {
    val palette = LocalResolvedPalette.current
    val isAdding = accountId == null
    var carrier by rememberSaveable(lockedCarrier) {
        mutableStateOf(lockedCarrier ?: BILL_CARRIERS.first().id)
    }
    val current = BILL_CARRIERS.firstOrNull { it.id == carrier } ?: BILL_CARRIERS.first()
    val recordedPhone = accountPhone?.takeIf { accountId != null && it.isNotBlank() }
    /** 明细页点“更换”后允许改号；正常重登录不显示输入框。 */
    var editPhone by remember(accountId) { mutableStateOf(false) }
    val effectiveRecordedPhone = recordedPhone.takeIf { !editPhone }
    /** 向导步：0 = 运营商，1 = 手机号 / 扫码，2 = 验证码。明细页恒为 0（不用）。 */
    var step by rememberSaveable { mutableStateOf(0) }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    /** 物联卡站点的验证值（小鲸卡=绑定手机号后四位）。不落盘，只用于本次登录。 */
    var verify by rememberSaveable(lockedCarrier) { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(message) }
    /** 登录失败统一走 Toast，不再把 "Not Found" 这类错误留在表单底部。 */
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    LaunchedEffect(expired, message) {
        if (expired) {
            toastMessage = ToastMessage(
                text = message?.takeIf { it.isNotBlank() } ?: "登录已过期，请重新登录",
                type = ToastType.INFO,
            )
        }
    }
    var qrBase64 by remember { mutableStateOf<String?>(null) }
    var qrPolling by remember { mutableStateOf(false) }
    var qrMessage by remember { mutableStateOf<String?>(null) }
    /** 当前二维码的生成时刻：用于"快过期就自动换一张"。 */
    var qrGeneratedAt by remember { mutableStateOf(0L) }
    /** 联通登录方式：`qr`（扫码）或 `password`（服务密码）。 */
    var unicomMethod by rememberSaveable { mutableStateOf("qr") }
    var unicomPassword by rememberSaveable { mutableStateOf("") }

    fun fail(t: Throwable) {
        // 401/409/404 的业务说明在响应体里，直接 t.message 只会显示 "HTTP 401" / "Not Found"；
        // 登录失败统一走 Toast，表单底部只保留“验证码已发送”这类操作提示。
        hint = null
        toastMessage = ToastMessage(
            text = viewModel.tools.carrierErrorText(t),
            type = ToastType.ERROR,
        )
        busy = false
    }

    fun loginWithPassword() {
        busy = true
        hint = null
        viewModel.tools.loginUnicomWithPassword(accountId, phone, unicomPassword) { result ->
            busy = false
            result.onSuccess { onLoggedIn() }.onFailure { fail(it) }
        }
    }

    /** 云上卡：添加页先建账号再登录；明细页直接重新登录。 */
    fun loginCloudCard() {
        busy = true
        hint = null
        val callback: (Result<Unit>) -> Unit = { result ->
            busy = false
            result.onSuccess { onLoggedIn() }.onFailure { fail(it) }
        }
        val provider = current.iotProvider ?: "cloud"
        if (accountId == null) {
            viewModel.tools.addAndLoginIotCard(
                cardNo = phone,
                verify = verify,
                provider = provider,
                onResult = callback,
            )
        } else {
            viewModel.tools.loginCloudCardFor(accountId, phone, verify, callback)
        }
    }

    fun sendCode() {
        busy = true
        hint = null
        val callback: (Result<com.ufi_axis.data.model.CarrierStatusResponse>) -> Unit = { result ->
            busy = false
            result.onSuccess { status ->
                codeSent = true
                editPhone = false
                hint = "验证码已发送，5 分钟内有效"
                toastMessage = ToastMessage(
                    text = "验证码已发送到 ${status.phone ?: effectiveRecordedPhone ?: phone}",
                    type = ToastType.SUCCESS,
                )
                if (isWizard) step = 2
            }.onFailure { fail(it) }
        }
        if (accountId != null) {
            // phone 可以为空：core 用账号里缓存的完整手机号。
            viewModel.tools.requestCarrierCodeFor(accountId, phone, callback)
        } else {
            viewModel.tools.requestCarrierCode(phone, callback)
        }
    }

    fun verifyCode() {
        busy = true
        hint = null
        val callback: (Result<com.ufi_axis.data.model.CarrierStatusResponse>) -> Unit = { result ->
            busy = false
            result.onSuccess { onLoggedIn() }.onFailure { fail(it) }
        }
        if (accountId != null) {
            viewModel.tools.verifyCarrierCodeFor(accountId, code, callback)
        } else {
            viewModel.tools.verifyCarrierCode(code, callback)
        }
    }

    fun startQr(force: Boolean = false) {
        busy = true
        hint = null
        qrPolling = false
        val callback: (Result<com.ufi_axis.data.model.CarrierStatusResponse>) -> Unit = { result ->
            busy = false
            result.onSuccess { status ->
                qrBase64 = status.qrPngBase64
                qrGeneratedAt = System.currentTimeMillis()
                qrMessage = "等待扫码…"
                qrPolling = qrBase64 != null
            }.onFailure { fail(it) }
        }
        if (accountId != null) {
            viewModel.tools.startUnicomQrFor(accountId, force, callback)
        } else {
            viewModel.tools.startUnicomQr(force, callback)
        }
    }

    /**
     * 二维码自动换新：一张码只有 10 分钟有效期，8 分钟还没扫掉就主动换。
     *
     * 为什么必须做：用户可能把页面挂在后台，回来时看到的是一张**已经失效**的码；
     * 那时扫码只会得到"二维码已过期"，还得自己发现需要点刷新。
     */
    LaunchedEffect(qrBase64, qrGeneratedAt) {
        if (qrBase64 == null) return@LaunchedEffect
        delay(QR_AUTO_REFRESH_MS)
        if (qrBase64 != null && !busy) {
            startQr(force = true)
            hint = "二维码已自动刷新"
        }
    }

    /** 切运营商时把上一家的中间态清干净，避免二维码/验证码串台。 */
    fun pickCarrier(picked: BillCarrierOption) {
        if (picked.id == carrier) {
            if (isWizard) step = 1
            return
        }
        carrier = picked.id
        if (picked.flow == CarrierLoginFlow.CARD || current.flow == CarrierLoginFlow.CARD) {
            phone = ""
            verify = ""
        }
        codeSent = false
        code = ""
        qrBase64 = null
        qrPolling = false
        qrMessage = null
        hint = null
        if (isWizard) step = 1
    }

    // 联通扫码：页面在时每 2 秒轮询一次；页面关掉协程自然取消
    LaunchedEffect(qrPolling) {
        if (!qrPolling) return@LaunchedEffect
        while (qrPolling) {
            delay(2000)
            val onPoll: (Result<com.ufi_axis.data.model.CarrierStatusResponse>) -> Unit = { result ->
                result.onSuccess { status ->
                    qrMessage = when (status.state) {
                        "waiting" -> "等待扫码…"
                        "scanned" -> status.message ?: "已扫码，请在手机上确认"
                        "ready" -> "登录成功"
                        "expired" -> status.message ?: "二维码已过期，请刷新"
                        "error" -> status.message ?: "扫码失败"
                        else -> status.message
                    }
                    if (status.state == "ready") {
                        qrPolling = false
                        busy = false
                        onLoggedIn()
                    } else if (status.state == "expired") {
                        // 过期不是"让用户点刷新"，而是**立刻换一张**，页面始终有可扫的码。
                        qrPolling = false
                        busy = false
                        startQr(force = true)
                    } else if (status.state == "error") {
                        qrPolling = false
                        busy = false
                    }
                }.onFailure {
                    qrPolling = false
                    fail(it)
                }
            }
            if (accountId != null) {
                viewModel.tools.pollUnicomQrFor(accountId, onPoll)
            } else {
                viewModel.tools.pollUnicomQr(onPoll)
            }
        }
    }

    Column(
        // 外层 UfiPageBackground 已是 verticalScroll；这里再套滚动会让内层被测量成无限高。
        modifier = Modifier
            .fillMaxWidth()
            // 与登录/配对页一致：内容左右各 16dp。少了这一层，卡在 411dp 的屏上会贴边，
            // 观感就是"比登录页那张卡大一圈"。
            .padding(horizontal = Spacing.XLarge, vertical = Spacing.Medium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 平台层 Toast：不占布局，登录错误不再以表单内橙色文字呈现。
        UfiToastHost(
            toastMessage = toastMessage,
            onDismiss = { toastMessage = null },
        )

        Column(
            modifier = Modifier
                // 与登录/配对页同一张卡：宽上限 420、圆角 12、阴影 4dp、内距 24×26。
                //
                // ★ 用 `ufiStandardCard()` 而不是手写 shadow+clip+background：标准卡片自带
                //   1dp 描边（`palette.cardBorder`）。少了这条边，浅色主题下白卡压在
                //   近白的 pageBg 上就"没有卡片感"—— 2026-11-07 用户反馈的"卡片无背景阴影"
                //   就是这个（`ufiCardShadow` 的 spotColor 是 Transparent，只剩很淡的 ambient）。
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .ufiStandardCard(elevation = 4.dp, shape = UfiCardDefaults.largeShape)
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (isWizard) {
                val stepCount = if (current.flow == CarrierLoginFlow.SMS) 3 else 2
                BillStepDots(
                    count = stepCount,
                    index = step.coerceAtMost(2),
                    label = when {
                        step == 0 -> "1 / $stepCount · 接入方式"
                        current.flow == CarrierLoginFlow.SMS && step == 1 -> "2 / 3 · 手机号"
                        current.flow == CarrierLoginFlow.SMS -> "3 / 3 · 验证码"
                        current.flow == CarrierLoginFlow.CARD && step == 1 ->
                            if (current.requiresVerify) "2 / 2 · 充值号 + 尾号" else "2 / 2 · 充值号"
                        unicomMethod == "password" -> "2 / 2 · 服务密码"
                        else -> "2 / 2 · 扫码"
                    },
                )
            }

            // 步骤切换要有方向感：前进从右进、后退从左进，卡片高度用 SizeTransform 平滑。
            // 明细页（-1）不做步骤动画。
            AnimatedContent(
                targetState = if (isWizard) step else -1,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    if (targetState < 0 || initialState < 0) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        // 与 SetupScreen 的整卡过渡逐字对齐：淡入 + 0.92→1 缩放，
                        // 出场 0.96 收小。不做横向位移 —— 那是我上一版自己发明的，
                        // 和登录页的观感不一致。
                        (fadeIn(tween(220)) + scaleIn(tween(280), initialScale = 0.92f)) togetherWith
                            (fadeOut(tween(160)) + scaleOut(tween(180), targetScale = 0.96f)) using
                            SizeTransform(clip = false)
                    }
                },
                label = "billStep",
            ) { animatedStep ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
            when {
                // ── 明细页：运营商锁定 + 单卡登录 ──
                !isWizard -> {
                    val pwdMode = current.flow == CarrierLoginFlow.QR && unicomMethod == "password"
                    // 进入扫码模式就自动出码，不用用户再点一次。
                    // core 侧对同一账号的二维码有 TTL 缓存，来回切方式不会重新申请。
                    LaunchedEffect(pwdMode, current.flow) {
                        if (current.flow == CarrierLoginFlow.QR && !pwdMode &&
                            qrBase64 == null && !busy
                        ) {
                            // 首次进入/离开后回来：状态已清空，必须换新码，避免拿到旧缓存。
                            startQr(force = true)
                        }
                    }
                    if (current.flow == CarrierLoginFlow.QR) {
                        BillUnicomMethodSwitch(
                            method = unicomMethod,
                            onSelect = {
                                unicomMethod = it
                                hint = null
                            },
                        )
                    }
                    // 切换「扫码 / 服务密码」时，标题与字段一起淡入缩放，别硬切。
                    AnimatedContent(
                        targetState = pwdMode,
                        transitionSpec = { billMethodTransition() },
                        label = "billDetailMethod",
                    ) { pwd ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            BillStepHeading(
                                heading = when {
                                    expired -> "登录已过期"
                                    pwd -> "服务密码登录"
                                    current.flow == CarrierLoginFlow.CARD -> "登录${current.name}"
                                    else -> "登录运营商账号"
                                },
                                description = when {
                                    expired -> "会话已失效，请重新登录以继续查询账单。"
                                    pwd -> "用联通 App 的查询密码（服务密码）登录，不需要验证码。"
                                    current.flow == CarrierLoginFlow.CARD && current.requiresVerify ->
                                        "输入${current.name}充值号和绑定手机号后四位重新登录；会话保存在设备 core。"
                                    current.flow == CarrierLoginFlow.CARD ->
                                        "输入${current.name}充值号重新登录；会话保存在设备 core。"
                                    else -> "登录后会话保存在设备 core。"
                                },
                            )
                            if (pwd) {
                                BillUnicomPasswordFields(
                                    phone = phone,
                                    password = unicomPassword,
                                    busy = busy,
                                    recordedPhone = effectiveRecordedPhone,
                                    onEditPhone = effectiveRecordedPhone?.let {
                                        {
                                            editPhone = true
                                            phone = ""
                                        }
                                    },
                                    onPhoneChange = { phone = it },
                                    onPasswordChange = { unicomPassword = it },
                                )
                            } else {
                                BillLoginFields(
                                    flow = current.flow,
                                    phone = phone,
                                    code = code,
                                    codeSent = codeSent,
                                    busy = busy,
                                    qrBase64 = qrBase64,
                                    qrMessage = qrMessage,
                                    recordedPhone = effectiveRecordedPhone,
                                    onEditPhone = effectiveRecordedPhone?.let {
                                        {
                                            editPhone = true
                                            phone = ""
                                        }
                                    },
                                    onPhoneChange = { phone = it },
                                    onCodeChange = { code = it },
                                    onResend = { sendCode() },
                                    providerName = current.name,
                                    verify = verify,
                                    requiresVerify = current.requiresVerify,
                                    onVerifyChange = { verify = it },
                                )
                            }
                        }
                    }

                    val hasCachedPhone = accountId != null && effectiveRecordedPhone != null
                    val primaryEnabled = when {
                        current.flow == CarrierLoginFlow.SMS && !codeSent ->
                            !busy && (hasCachedPhone || phone.length == 11)
                        current.flow == CarrierLoginFlow.SMS -> !busy && code.length == 6
                        current.flow == CarrierLoginFlow.CARD ->
                            !busy && (hasCachedPhone || phone.length in 4..20) &&
                                (!current.requiresVerify || verify.length == 4)
                        pwdMode -> !busy && (hasCachedPhone || phone.length == 11) &&
                            unicomPassword.isNotBlank()
                        else -> !busy
                    }
                    // 自动登录放在账号操作按钮的**上方**：它是配置项，不该排在动作后面。
                    if (!isAdding && current.flow == CarrierLoginFlow.SMS) {
                        BillKeepAliveStatus(
                            viewModel = viewModel,
                            accountId = accountId,
                            onOpenSettings = onOpenKeepAliveSettings,
                        )
                    }
                    // 2×2（2026-11-07 用户指定顺序）：
                    //   第一行：删除账号 | 改名
                    //   第二行：退出登录 / 换号 | 发送验证码（发码后变「登录」）
                    BillButtonPair(
                        left = {
                            if (onDelete != null) {
                                UfiButton(
                                    text = "删除账号",
                                    onClick = onDelete,
                                    variant = UfiButtonVariant.Secondary,
                                    size = UfiButtonSize.Standard,
                                )
                            }
                        },
                        right = {
                            if (onRename != null) {
                                UfiButton(
                                    text = "改名",
                                    onClick = onRename,
                                    variant = UfiButtonVariant.Secondary,
                                    size = UfiButtonSize.Standard,
                                )
                            }
                        },
                    )
                    BillButtonPair(
                        left = {
                            UfiButton(
                                text = "退出登录",
                                onClick = {
                                    viewModel.tools.logoutCarrier()
                                    codeSent = false
                                    code = ""
                                    qrBase64 = null
                                    qrPolling = false
                                    qrMessage = null
                                    hint = null
                                },
                                variant = UfiButtonVariant.Secondary,
                                size = UfiButtonSize.Standard,
                                enabled = !busy,
                            )
                        },
                        right = {
                            UfiButton(
                                text = when {
                                    pwdMode -> "登录"
                                    current.flow == CarrierLoginFlow.CARD -> "登录"
                                    current.flow == CarrierLoginFlow.SMS && codeSent -> "登录"
                                    current.flow == CarrierLoginFlow.SMS -> "发送验证码"
                                    qrBase64 == null -> "生成登录二维码"
                                    else -> "刷新二维码"
                                },
                                onClick = {
                                    when {
                                        pwdMode -> loginWithPassword()
                                        current.flow == CarrierLoginFlow.CARD -> loginCloudCard()
                                        current.flow == CarrierLoginFlow.SMS && codeSent -> verifyCode()
                                        current.flow == CarrierLoginFlow.SMS -> sendCode()
                                        current.flow == CarrierLoginFlow.QR -> startQr(force = qrBase64 != null)
                                    }
                                },
                                enabled = primaryEnabled,
                                loading = busy,
                                size = UfiButtonSize.Standard,
                            )
                        },
                    )
                    BillHintAnimated(hint)
                }

                // ── 添加账号：第 0 步，运营商大列表 ──
                animatedStep == 0 -> {
                    BillStepHeading(heading = "选择接入方式", description = "运营商账号，或物联卡充值号")
                    // 用全站统一设置行（UfiSettingsItem）+ UfiDivider，和「小功能」页同一套；
                    // 不再自写列表行。
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BILL_CARRIERS.forEachIndexed { index, option ->
                            UfiSettingsItem(
                                title = option.name,
                                description = when {
                                    !option.available -> "即将支持"
                                    option.flow == CarrierLoginFlow.SMS -> "短信验证码 · 支持自动登录"
                                    option.flow == CarrierLoginFlow.CARD && option.requiresVerify ->
                                        "充值号 + 手机尾号 · 只读物联卡"
                                    option.flow == CarrierLoginFlow.CARD -> "充值号登录 · 只读物联卡"
                                    else -> "扫码登录"
                                },
                                enabled = option.available,
                                onClick = if (option.available) {
                                    { pickCarrier(option) }
                                } else {
                                    null
                                },
                                trailing = {
                                    Text(
                                        text = when {
                                            !option.available -> "—"
                                            option.id == carrier -> "✓"
                                            else -> "›"
                                        },
                                        style = UfiTextStyles.body,
                                        color = if (option.id == carrier) {
                                            LocalResolvedPalette.current.accent
                                        } else {
                                            LocalResolvedPalette.current.textSecondary
                                        },
                                    )
                                },
                            )
                            if (index != BILL_CARRIERS.lastIndex) UfiDivider()
                        }
                    }
                }

                // ── 第 1 步：物联卡充值号（小鲸卡还要手机尾号） ──
                animatedStep == 1 && current.flow == CarrierLoginFlow.CARD -> {
                    BillStepHeading(
                        heading = "输入${current.name}充值号",
                        description = if (current.requiresVerify) {
                            "需要充值号和绑定手机号后四位；登录后会话保存在设备 core。"
                        } else {
                            "物联卡只支持充值号登录；登录后会话保存在设备 core。"
                        },
                    )
                    BillLoginFields(
                        flow = CarrierLoginFlow.CARD,
                        phone = phone,
                        code = code,
                        codeSent = false,
                        busy = busy,
                        qrBase64 = null,
                        qrMessage = null,
                        onPhoneChange = { phone = it },
                        onCodeChange = {},
                        onResend = {},
                        providerName = current.name,
                        verify = verify,
                        requiresVerify = current.requiresVerify,
                        onVerifyChange = { verify = it },
                    )
                    BillButtonPair(
                        left = {
                            UfiButton(
                                text = "上一步",
                                onClick = {
                                    step = 0
                                    hint = null
                                },
                                variant = UfiButtonVariant.Secondary,
                                size = UfiButtonSize.Standard,
                            )
                        },
                        right = {
                            UfiButton(
                                text = "登录并读取",
                                onClick = { loginCloudCard() },
                                enabled = !busy && phone.length in 4..20 &&
                                    (!current.requiresVerify || verify.length == 4),
                                loading = busy,
                                size = UfiButtonSize.Standard,
                            )
                        },
                    )
                    BillHintAnimated(hint)
                }

                // ── 第 1 步：手机号 / 扫码 ──
                animatedStep == 1 && current.flow == CarrierLoginFlow.SMS -> {
                    BillStepHeading(
                        heading = "输入手机号",
                        description = "用于接收登录验证码，登录后会话保存在设备 core。",
                    )
                    UfiTextField(
                        value = phone,
                        onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                        label = "手机号",
                        placeholder = "输入要登录的移动手机号",
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    BillButtonPair(
                        left = {
                            UfiButton(
                                text = "上一步",
                                onClick = {
                                    step = 0
                                    hint = null
                                },
                                variant = UfiButtonVariant.Secondary,
                                size = UfiButtonSize.Standard,
                            )
                        },
                        right = {
                            UfiButton(
                                text = "发送验证码",
                                onClick = { sendCode() },
                                enabled = !busy && phone.length == 11,
                                loading = busy,
                                size = UfiButtonSize.Standard,
                            )
                        },
                    )
                    BillHintAnimated(hint)
                }

                // ── 第 1 步：扫码 ──
                animatedStep == 1 -> {
                    val pwdMode = current.flow == CarrierLoginFlow.QR && unicomMethod == "password"
                    // 只在**进入这一步**时出码：切「扫码/服务密码」不重申请（那是另一个 effect 的事）。
                    LaunchedEffect(animatedStep) {
                        if (current.flow == CarrierLoginFlow.QR && animatedStep == 1 &&
                            unicomMethod != "password" && !busy
                        ) {
                            startQr(force = true)
                        }
                    }
                    if (current.flow == CarrierLoginFlow.QR) {
                        BillUnicomMethodSwitch(
                            method = unicomMethod,
                            onSelect = {
                                unicomMethod = it
                                hint = null
                            },
                        )
                    }
                    AnimatedContent(
                        targetState = pwdMode,
                        transitionSpec = { billMethodTransition() },
                        label = "billWizardMethod",
                    ) { pwd ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            BillStepHeading(
                                heading = if (pwd) {
                                    "服务密码登录"
                                } else {
                                    "扫码登录${current.name.removePrefix("中国")}"
                                },
                                description = if (pwd) {
                                    "用联通 App 的查询密码（服务密码）登录，不需要验证码。"
                                } else {
                                    "用${current.name} App 扫描二维码完成登录。"
                                },
                            )
                            if (pwd) {
                                BillUnicomPasswordFields(
                                    phone = phone,
                                    password = unicomPassword,
                                    busy = busy,
                                    onPhoneChange = { phone = it },
                                    onPasswordChange = { unicomPassword = it },
                                )
                            } else {
                                BillLoginFields(
                                    flow = current.flow,
                                    phone = phone,
                                    code = code,
                                    codeSent = false,
                                    busy = busy,
                                    qrBase64 = qrBase64,
                                    qrMessage = qrMessage,
                                    onPhoneChange = {},
                                    onCodeChange = {},
                                    onResend = {},
                                )
                            }
                        }
                    }
                    // 次操作在左、主操作在右（与弹窗的「取消 / 确定」同向）。
                    BillButtonPair(
                        left = {
                            UfiButton(
                                text = "上一步",
                                onClick = {
                                    // 只停轮询，**不**清 qrBase64：清掉会让二维码块在同一帧
                                    // 触发自己的退场动画，和整步转场叠在一起，看起来像"没有动画"。
                                    // 再进来时会强制换新码（见上面的 LaunchedEffect）。
                                    step = 0
                                    qrPolling = false
                                    hint = null
                                },
                                variant = UfiButtonVariant.Secondary,
                                size = UfiButtonSize.Standard,
                            )
                        },
                        right = {
                            UfiButton(
                                text = if (pwdMode) {
                                    "登录"
                                } else if (qrBase64 == null) {
                                    "生成登录二维码"
                                } else {
                                    "刷新二维码"
                                },
                                onClick = {
                                    if (pwdMode) loginWithPassword()
                                    else startQr(force = qrBase64 != null)
                                },
                                enabled = if (pwdMode) {
                                    !busy && phone.length == 11 && unicomPassword.isNotBlank()
                                } else {
                                    !busy
                                },
                                loading = busy,
                                size = UfiButtonSize.Standard,
                            )
                        },
                    )
                    BillHintAnimated(hint)
                }

                // ── 第 2 步：验证码 ──
                else -> {
                    BillStepHeading(
                        heading = "输入验证码",
                        description = "验证码已发送至 ${maskBillPhone(phone)}，5 分钟内有效。",
                    )
                    UfiTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = "验证码",
                        placeholder = "6 位短信验证码",
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    TextButton(
                        onClick = { sendCode() },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = Spacing.Small, vertical = 0.dp),
                    ) {
                        Text(
                            "重新发送验证码",
                            style = UfiTextStyles.caption,
                            color = palette.textSecondary,
                        )
                    }
                    BillButtonPair(
                        left = {
                            UfiButton(
                                text = "上一步",
                                onClick = {
                                    step = 1
                                    code = ""
                                    hint = null
                                },
                                variant = UfiButtonVariant.Secondary,
                                size = UfiButtonSize.Standard,
                            )
                        },
                        right = {
                            UfiButton(
                                text = "登录",
                                onClick = { verifyCode() },
                                enabled = !busy && code.length == 6,
                                loading = busy,
                                size = UfiButtonSize.Standard,
                            )
                        },
                    )
                    BillHintAnimated(hint)
                }
            }
            }
            }
        }

    }
}

/** 卡内标题 + 说明（SetupStepText 同款：22 Bold / 15 secondary，居中）。 */
@Composable
private fun BillStepHeading(heading: String, description: String) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        Text(
            text = heading,
            style = UfiTextStyles.screenTitle,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = UfiTextStyles.onboardingNote,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 步骤点：当前步短胶囊，其余圆点；右侧「1 / 3 · 运营商」。 */
@Composable
private fun BillStepDots(count: Int, index: Int, label: String) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = if (i == index) 18.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (i == index) palette.accent else palette.textSecondary.copy(alpha = 0.22f)
                    ),
            )
        }
        Spacer(Modifier.width(Spacing.Medium))
        Text(label, style = UfiTextStyles.captionTiny, color = palette.textSecondary)
    }
}

/** 联通登录方式切换：扫码 / 服务密码（主切换器，比普通 chip 大一档）。 */
@Composable
private fun BillUnicomMethodSwitch(method: String, onSelect: (String) -> Unit) {
    UfiSingleChipSelector(
        options = listOf("qr" to "扫码登录", "password" to "服务密码"),
        selectedValue = method,
        onSelect = onSelect,
        wrapContent = true,
        // 这是页面里唯一的"模式切换"：高 44dp；宽度按实测原宽（约 60dp）再放 25% → 76dp。
        segmentHeight = 44.dp,
        segmentMinWidth = 76.dp,
    )
}

/** 服务密码登录的两个字段：手机号 + 服务密码（6 位查询密码）。 */
@Composable
private fun BillUnicomPasswordFields(
    phone: String,
    password: String,
    busy: Boolean,
    recordedPhone: String? = null,
    onEditPhone: (() -> Unit)? = null,
    onPhoneChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
) {
    if (recordedPhone != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已记录手机号 $recordedPhone",
                style = UfiTextStyles.bodyEmphasis,
                color = LocalResolvedPalette.current.textPrimary,
                modifier = Modifier.weight(1f),
            )
            onEditPhone?.let { edit ->
                TextButton(onClick = edit, enabled = !busy) {
                    Text(
                        "更换",
                        style = UfiTextStyles.caption,
                        color = LocalResolvedPalette.current.accent,
                    )
                }
            }
        }
    } else {
        UfiTextField(
            value = phone,
            onValueChange = { onPhoneChange(it.filter(Char::isDigit).take(11)) },
            label = "手机号",
            placeholder = "联通手机号",
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    UfiPasswordField(
        value = password,
        onValueChange = { onPasswordChange(it.take(32)) },
        label = "服务密码",
        placeholder = "联通 App 的 6 位查询密码",
        enabled = !busy,
    )
}

/** 登录字段区（手机号/验证码 或 二维码），明细页与向导共用。 */
@Composable
private fun BillLoginFields(
    flow: CarrierLoginFlow,
    phone: String,
    code: String,
    codeSent: Boolean,
    busy: Boolean,
    qrBase64: String?,
    qrMessage: String?,
    recordedPhone: String? = null,
    onEditPhone: (() -> Unit)? = null,
    onPhoneChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onResend: () -> Unit,
    /** 物联卡站点名（云上卡 / 小鲸卡），用于输入框文案。 */
    providerName: String = "云上卡",
    /** 小鲸卡的绑定手机号后四位；不落盘，只用于本次登录。 */
    verify: String = "",
    requiresVerify: Boolean = false,
    onVerifyChange: (String) -> Unit = {},
) {
    val palette = LocalResolvedPalette.current
    when (flow) {
        CarrierLoginFlow.CARD -> {
            if (recordedPhone != null) {
                Text(
                    "已记录充值号 $recordedPhone",
                    style = UfiTextStyles.bodyEmphasis,
                    color = palette.textPrimary,
                )
                Text(
                    if (requiresVerify) {
                        "直接登录即可，但需要补填绑定手机号后四位。"
                    } else {
                        "直接登录即可，不需要重新输入卡号。"
                    },
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                )
            } else {
                UfiTextField(
                    value = phone,
                    onValueChange = { onPhoneChange(it.filter(Char::isDigit).take(20)) },
                    label = "充值号",
                    placeholder = "输入${providerName}充值号（卡号）",
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    if (requiresVerify) {
                        "充值号 + 绑定手机号后四位；验证值只用于本次登录，不会落盘。"
                    } else {
                        "只需充值号，不需要短信验证码或密码。"
                    },
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                )
            }
            if (requiresVerify) {
                UfiTextField(
                    value = verify,
                    onValueChange = { onVerifyChange(it.filter(Char::isDigit).take(4)) },
                    label = "手机号后四位",
                    placeholder = "卡绑定手机号的后四位",
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
        CarrierLoginFlow.SMS -> {
            if (recordedPhone != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "已记录手机号 $recordedPhone",
                            style = UfiTextStyles.bodyEmphasis,
                            color = palette.textPrimary,
                        )
                        Text(
                            "验证码将发送到该号码，无需重新输入。",
                            style = UfiTextStyles.note,
                            color = palette.textSecondary,
                        )
                    }
                    onEditPhone?.let { edit ->
                        TextButton(onClick = edit, enabled = !busy) {
                            Text("更换", style = UfiTextStyles.caption, color = palette.accent)
                        }
                    }
                }
            } else {
                UfiTextField(
                    value = phone,
                    onValueChange = { onPhoneChange(it.filter(Char::isDigit).take(11)) },
                    label = "手机号",
                    placeholder = "输入要登录的移动手机号",
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            // 验证码块是"发码后才出现"的：必须自己带进场/退场，否则会硬蹦出来。
            AnimatedVisibility(
                visible = codeSent,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
            ) {
                // 「重新发送」贴着验证码输入框放，不再单独占一行（2026-11-07 用户要求）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UfiTextField(
                        value = code,
                        onValueChange = { onCodeChange(it.filter(Char::isDigit).take(6)) },
                        label = "验证码",
                        placeholder = "6 位短信验证码",
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onResend,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = 0.dp),
                    ) {
                        Text(
                            "重新发送",
                            style = UfiTextStyles.caption,
                            color = palette.textSecondary,
                        )
                    }
                }
            }
        }

        CarrierLoginFlow.QR -> {
            AnimatedVisibility(
                visible = qrBase64 != null,
                enter = fadeIn(tween(180)) + expandVertically(tween(240)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                ) {
            qrBase64?.let { encoded ->
                val bitmap = remember(encoded) {
                    runCatching {
                        val bytes = java.util.Base64.getDecoder().decode(encoded)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "登录二维码",
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(Spacing.CornerBase)),
                    )
                }
                qrMessage?.let {
                    Text(
                        it,
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
                }
            }
        }

        CarrierLoginFlow.NONE -> Text(
            "接入后这里会出现对应的登录方式。",
            style = UfiTextStyles.captionTiny,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 提示行：出现/消失都带过渡，避免卡片高度"跳一下"。 */
@Composable
private fun BillHintAnimated(text: String?) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(160)) + expandVertically(tween(200)),
        exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
    ) {
        text?.let { BillHint(it) }
    }
}

@Composable
private fun BillHint(text: String) {
    val palette = LocalResolvedPalette.current
    Text(
        text,
        style = UfiTextStyles.note,
        color = palette.warning,
        textAlign = TextAlign.Center,
    )
}

/** 138****8000：向导里回显收码的号码。 */
private fun maskBillPhone(phone: String): String =
    if (phone.length == 11) "${phone.take(3)}****${phone.takeLast(4)}" else phone

@Composable
private fun BillErrorState(message: String, onRetry: () -> Unit) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.CardHorizontalMargin),
        verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
    ) {
        Text("读取失败", style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
        Text(message, style = UfiTextStyles.note, color = palette.textSecondary)
        UfiButton(
            text = "重试",
            onClick = onRetry,
            variant = UfiButtonVariant.Secondary,
            size = UfiButtonSize.Small,
            fillWidth = false
        )
    }
}

/**
 * 缓存提示：会话失效 / 实时刷新失败时，在数据上方说明"这是最近一次数据"。
 *
 * 不隐藏数据（用户要求打开就能看到），只把新鲜度说清楚。
 */
@Composable
private fun BillCachedNotice(title: String, bill: BillSnapshot) {
    val palette = LocalResolvedPalette.current
    BillCard {
        Text(title, style = UfiTextStyles.cardTitle, color = palette.warning)
        Spacer(Modifier.height(Spacing.Small))
        Text(
            "以下为最近一次数据" + (bill.cachedAt?.let { " · ${relativeUpdateText(it)}" } ?: ""),
            style = UfiTextStyles.note,
            color = palette.textSecondary,
        )
    }
}

@Composable
private fun BillContent(bill: BillSnapshot) {
    val palette = LocalResolvedPalette.current
    // 到期文案在整页内用同一个“现在”，避免同一屏里两行的“今天/明天”判据不一致
    val now = remember(bill) { LocalDateTime.now() }
    val soonPackages = remember(bill, now) {
        bill.packages
            .filter { isExpiringSoon(it.validTo, now) }
            .sortedBy { it.validTo ?: "" }
    }
    var packageFilter by rememberSaveable { mutableStateOf("all") }
    val isCloud = bill.accessMethod == BillAccessMethod.IOT_CARD
    LaunchedEffect(isCloud) {
        if (isCloud && packageFilter !in setOf("all", "general")) packageFilter = "all"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
    ) {
        // ── 物联卡状态卡（云上卡）──
        if (bill.cloudCard != null) {
            BillCloudStatusCard(bill)
        }

        // ── 余额卡（物联卡把余额并进状态卡，减少空白卡）──
        if (!isCloud) {
        BillCard {
            Text("可用余额", style = UfiTextStyles.note, color = palette.textSecondary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "¥",
                    style = UfiTextStyles.metricValue.copy(fontSize = 17.sp),
                    color = palette.textPrimary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    money(bill.balanceYuan),
                    style = UfiTextStyles.metricValue.copy(fontSize = 34.sp),
                    color = palette.textPrimary
                )
            }
            Spacer(Modifier.height(Spacing.Small))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                bill.lastMonthBillYuan?.let { BillMeta("上月账单", "¥${money(it)}") }
                bill.currentBillYuan?.let { BillMeta("本期账单", "¥${money(it)}") }
                bill.points?.let { BillMeta("积分", it.toString()) }
            }
            Spacer(Modifier.height(Spacing.Small))
            HorizontalDivider(color = palette.divider)
            Spacer(Modifier.height(Spacing.Small))
            val period = bill.currentBillPeriod
            val asOf = bill.currentBillAsOf
            if (period != null || asOf != null) {
                Text(
                    listOfNotNull(
                        period?.let { "本期 $it" },
                        asOf?.let { "截至 $it" }
                    ).joinToString(" · "),
                    style = UfiTextStyles.note,
                    color = palette.textSecondary
                )
            }
            Text(
                listOfNotNull(bill.carrier, bill.phone, bill.ownerName).joinToString(" · "),
                style = UfiTextStyles.note,
                color = palette.textSecondary
            )
            bill.planName?.let {
                Text(
                    it,
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (bill.tenureMonths != null || bill.starLevel != null) {
                Text(
                    listOfNotNull(
                        bill.tenureMonths?.let { "网龄 ${tenureText(it)}" },
                        bill.starLevel?.let { "星级 ${starText(it)}" }
                    ).joinToString(" · "),
                    style = UfiTextStyles.note,
                    color = palette.textSecondary
                )
            }
        }
        }

        // ── 流量卡 ──
        if (isCloud) {
            BillCloudFlowCard(bill)
        } else {
            // ── 流量卡：2×2 ──
            BillCard {
                BillSectionHeader(
                    title = "流量",
                    subtitle = "剩余 ${gb(bill.flow.remainingGb)} GB"
                )
                Row(Modifier.fillMaxWidth()) {
                    BillMetricCell(
                        value = gb(bill.flow.generalRemainingGb),
                        label = "通用剩余",
                        sub = "已用 ${gb(bill.flow.generalUsedGb)} / 共 ${gb(bill.flow.generalTotalGb)} GB",
                        modifier = Modifier.weight(1f)
                    )
                    BillVerticalDivider()
                    BillMetricCell(
                        value = gb(bill.flow.directedRemainingGb),
                        label = "定向剩余",
                        sub = "已用 ${gb(bill.flow.directedUsedGb)} / 共 ${gb(bill.flow.directedTotalGb)} GB",
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider(color = palette.divider)
                Row(Modifier.fillMaxWidth()) {
                    BillMetricCell(
                        value = gb(bill.flow.remainingGb),
                        label = "合计剩余",
                        sub = "已用 ${gb(bill.flow.usedGb)} / 共 ${gb(bill.flow.totalGb)} GB",
                        modifier = Modifier.weight(1f)
                    )
                    BillVerticalDivider()
                    when {
                        bill.flow.regionalRemainingGb != null -> BillMetricCell(
                            value = gb(bill.flow.regionalRemainingGb),
                            label = "区域剩余",
                            sub = "已用 ${gb(bill.flow.regionalUsedGb)} / 共 ${gb(bill.flow.regionalTotalGb)} GB",
                            modifier = Modifier.weight(1f)
                        )
                        bill.monthlyUsedGb != null -> BillMetricCell(
                            value = gb(bill.monthlyUsedGb),
                            label = "本月累计",
                            sub = "含已过期流量包",
                            alert = true,
                            modifier = Modifier.weight(1f)
                        )
                        else -> BillMetricCell(
                            value = "—",
                            label = "本月累计",
                            sub = "运营商未返回",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ── 即将到期（24 小时内；物联卡没有流量包，不展示）──
        if (!isCloud && soonPackages.isNotEmpty()) {
            BillCard {
                BillSectionHeader(
                    title = "即将到期",
                    subtitle = "${soonPackages.size} 个包 · 24 小时内"
                )
                soonPackages.forEachIndexed { index, pkg ->
                    if (index > 0) HorizontalDivider(color = palette.divider)
                    BillPackageRow(pkg, now)
                }
            }
        }

        // ── 全部流量包 + 筛选（普通运营商）──
        if (!isCloud) {
            // ── 全部流量包 + 筛选 ──
            BillCard {
                BillSectionHeader(
                    title = "全部流量包",
                    subtitle = "${bill.packages.size} 个 · 剩余 ${gb(bill.packages.sumOf { it.remainingGb })} GB"
                )
                val generalCount = bill.packages.count { it.kind == BillPackageKind.GENERAL }
                val directedCount = bill.packages.count { it.kind == BillPackageKind.DIRECTED }
                val regionalCount = bill.packages.count { it.kind == BillPackageKind.REGIONAL }
                UfiSingleChipSelector(
                    options = buildList {
                        add("all" to "全部 ${bill.packages.size}")
                        add("general" to "通用 $generalCount")
                        if (!isCloud) {
                            add("directed" to "定向 $directedCount")
                            if (regionalCount > 0) add("regional" to "区域 $regionalCount")
                        }
                    },
                    selectedValue = packageFilter,
                    onSelect = { packageFilter = it },
                    wrapContent = true
                )
                Spacer(Modifier.height(Spacing.Small))
                val filtered = when (packageFilter) {
                    "general" -> bill.packages.filter { it.kind == BillPackageKind.GENERAL }
                    "directed" -> bill.packages.filter { it.kind == BillPackageKind.DIRECTED }
                    "regional" -> bill.packages.filter { it.kind == BillPackageKind.REGIONAL }
                    else -> bill.packages
                }
                filtered.forEachIndexed { index, pkg ->
                    if (index > 0) HorizontalDivider(color = palette.divider)
                    BillPackageRow(pkg, now)
                }
                Spacer(Modifier.height(Spacing.Small))
                Text(
                    "共 ${bill.packages.size} 个包，合计 ${gb(bill.packages.sumOf { it.totalGb })} GB / " +
                        "剩余 ${gb(bill.packages.sumOf { it.remainingGb })} GB。",
                    style = UfiTextStyles.note,
                    color = palette.textSecondary
                )
            }
        }

        // ── 物联卡订单详情（替代流量包）──
        if (isCloud) {
            BillCloudOrdersCard(bill)
        }

        Spacer(Modifier.height(Spacing.Large))
    }
}

/** 物联卡订单详情：套餐充值订单，替代普通运营商的“流量包”。 */
@Composable
private fun BillCloudOrdersCard(bill: BillSnapshot) {
    val palette = LocalResolvedPalette.current
    val orders = bill.cloudCard?.orders.orEmpty()
    val pageSize = 5
    val pageCount = ((orders.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    // 同一张卡刷新时保留页码；换卡/换账号自动回到第 1 页。
    var orderPage by remember(bill.cloudCard?.cardNo) { mutableStateOf(0) }
    val safePage = orderPage.coerceIn(0, pageCount - 1)
    val visibleOrders = orders.drop(safePage * pageSize).take(pageSize)
    LaunchedEffect(pageCount) {
        if (orderPage >= pageCount) orderPage = 0
    }

    BillCard {
        BillSectionHeader(
            title = "订单详情",
            subtitle = when {
                orders.isEmpty() -> "暂无订单"
                pageCount > 1 -> "最近 ${orders.size} 笔 · 第 ${safePage + 1}/$pageCount 页"
                else -> "最近 ${orders.size} 笔"
            },
        )
        if (orders.isEmpty()) {
            Text(
                "上游没有返回套餐充值订单。",
                style = UfiTextStyles.note,
                color = palette.textSecondary,
            )
        } else {
            visibleOrders.forEachIndexed { index, order ->
                if (index > 0) HorizontalDivider(color = palette.divider)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.Small),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            order.name,
                            style = UfiTextStyles.bodyEmphasis,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "¥${money(order.paidYuan ?: order.amountYuan)}",
                            style = UfiTextStyles.bodyEmphasis,
                            color = palette.textPrimary,
                        )
                    }
                    Text(
                        listOfNotNull(
                            order.statusText,
                            order.payTypeText,
                            order.createdAt,
                        ).joinToString(" · "),
                        style = UfiTextStyles.note,
                        color = palette.textSecondary,
                    )
                    order.expirationTime?.let {
                        Text(
                            "有效期至 $it",
                            style = UfiTextStyles.noteCompact,
                            color = palette.textSecondary,
                        )
                    }
                }
            }
            if (pageCount > 1) {
                Spacer(Modifier.height(Spacing.Small))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                ) {
                    UfiButton(
                        text = "上一页",
                        onClick = { orderPage = (safePage - 1).coerceAtLeast(0) },
                        enabled = safePage > 0,
                        variant = UfiButtonVariant.Secondary,
                        size = UfiButtonSize.Small,
                        fillWidth = false,
                    )
                    Text(
                        "第 ${safePage + 1} / $pageCount 页",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    UfiButton(
                        text = "下一页",
                        onClick = { orderPage = (safePage + 1).coerceAtMost(pageCount - 1) },
                        enabled = safePage < pageCount - 1,
                        variant = UfiButtonVariant.Secondary,
                        size = UfiButtonSize.Small,
                        fillWidth = false,
                    )
                }
            }
        }
    }
}

/** 云上卡状态卡：到期/剩余天数/状态/实名/ICCID，普通运营商不渲染。 */
@Composable
private fun BillCloudStatusCard(bill: BillSnapshot) {
    val cloud = bill.cloudCard ?: return
    val palette = LocalResolvedPalette.current
    val healthy = cloud.statusText == "正常" && cloud.realNameText == "已实名"
    BillCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    cloud.providerName.ifBlank { "物联卡" },
                    style = UfiTextStyles.cardTitle,
                    color = palette.accent,
                )
                cloud.seriesName?.let {
                    Text(
                        it,
                        style = UfiTextStyles.note,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                cloud.statusText ?: "状态未知",
                style = UfiTextStyles.note,
                color = if (healthy) palette.success else palette.warning,
            )
        }
        Spacer(Modifier.height(Spacing.Small))
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                BillMeta(
                    "到期",
                    cloud.expiresAt ?: "—",
                )
            }
            Box(Modifier.weight(1f)) {
                val days = cloud.daysLeft
                BillMeta(
                    "剩余",
                    when {
                        days == null -> "—"
                        days < 0 -> "已过期"
                        else -> "$days 天"
                    },
                )
            }
        }
        Spacer(Modifier.height(Spacing.Small))
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                BillMeta("实名", cloud.realNameText ?: "—")
            }
            Box(Modifier.weight(1f)) {
                BillMeta("余额", "¥${money(bill.balanceYuan)}")
            }
        }
        Spacer(Modifier.height(Spacing.Small))
        Text(
            "ICCID ${cloud.iccid ?: "—"}",
            style = UfiTextStyles.note,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        cloud.refreshedAt?.let {
            Text(
                "数据更新 $it",
                style = UfiTextStyles.noteCompact,
                color = palette.textSecondary,
            )
        }
    }
}

/** 云上卡流量卡：只有通用流量，不展示定向/区域/本月累计。 */
@Composable
private fun BillCloudFlowCard(bill: BillSnapshot) {
    val palette = LocalResolvedPalette.current
    val unlimited = bill.flow.generalUnlimited
    val total = bill.flow.generalTotalGb
    val used = bill.flow.generalUsedGb
    BillCard {
        BillSectionHeader(
            title = "流量",
            subtitle = if (unlimited) "不限量" else "剩余 ${gb(bill.flow.generalRemainingGb)} GB",
        )
        Row(Modifier.fillMaxWidth()) {
            BillPlainMetric(
                value = "${gb(used)} GB",
                label = "已用",
                modifier = Modifier.weight(1f),
            )
            BillCardVDivider()
            BillPlainMetric(
                value = if (unlimited) "不限量" else "${gb(bill.flow.generalRemainingGb)} GB",
                label = "剩余",
                modifier = Modifier.weight(1f),
            )
            BillCardVDivider()
            BillPlainMetric(
                value = "${gb(total)} GB",
                label = "总量",
                modifier = Modifier.weight(1f),
            )
        }
        val realUsed = bill.cloudCard?.realUsedGb
        // 小鲸卡 used == realUsed（无虚量），再画一遍“真实用量”只是重复数字；
        // 只有和展示用量有明显差异时才展示对账区块。
        if (realUsed != null && kotlin.math.abs(realUsed - used) > 0.01) {
            Spacer(Modifier.height(Spacing.Medium))
            HorizontalDivider(color = palette.divider)
            Spacer(Modifier.height(Spacing.Medium))
            Text(
                "真实用量",
                style = UfiTextStyles.captionStrong,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(Spacing.Small))
            Row(Modifier.fillMaxWidth()) {
                BillPlainMetric(
                    value = "${gb(realUsed)} GB",
                    label = "真实已用",
                    modifier = Modifier.weight(1f),
                )
                BillCardVDivider()
                BillPlainMetric(
                    value = bill.cloudCard?.realRemainingGb?.let { "${gb(it)} GB" } ?: "—",
                    label = "真实剩余",
                    modifier = Modifier.weight(1f),
                )
                BillCardVDivider()
                BillPlainMetric(
                    value = bill.cloudCard?.realTotalGb?.let { "${gb(it)} GB" } ?: "—",
                    label = "真实总量",
                    modifier = Modifier.weight(1f),
                )
            }
            val ratio = if (realUsed > 0.0) used / realUsed else null
            if (ratio != null) {
                Text(
                    "虚量倍率 ${"%.2f".format(ratio)}×",
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                )
            }
        }
        // 物联卡不再画进度条：虚量进度对用户没有决策价值，且短进度在深色背景下只剩一条细线。

    }
}

/** 不带固定 GB 单位的指标格：物联卡的“到期/剩余不限量”用。 */
@Composable
private fun BillPlainMetric(
    value: String,
    label: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier.padding(vertical = Spacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = UfiTextStyles.metricValueCompact,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            label,
            style = UfiTextStyles.note,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (!sub.isNullOrBlank()) {
            Text(
                sub,
                style = UfiTextStyles.noteCompact,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                // 撑满本栏后再居中：说明文字比列宽长时会在本栏内省略，
                // 而不是以自然宽度溢出到相邻栏，造成“整块没居中”的错觉。
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BillCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin)
            .ufiStandardCard()
            .padding(Spacing.CardPadding),
        content = content
    )
}

@Composable
private fun BillSectionHeader(title: String, subtitle: String) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, style = UfiTextStyles.cardTitle, color = palette.accent)
        Spacer(Modifier.weight(1f))
        Text(subtitle, style = UfiTextStyles.note, color = palette.textSecondary, maxLines = 1)
    }
    Spacer(Modifier.height(Spacing.Small))
}

@Composable
private fun BillMeta(label: String, value: String) {
    val palette = LocalResolvedPalette.current
    Column {
        Text(label, style = UfiTextStyles.note, color = palette.textSecondary)
        Text(value, style = UfiTextStyles.bodyEmphasis, color = palette.textPrimary)
    }
}

@Composable
private fun BillMetricCell(
    value: String,
    label: String,
    sub: String,
    modifier: Modifier = Modifier,
    alert: Boolean = false
) {
    val palette = LocalResolvedPalette.current
    val valueColor = if (alert) palette.warning else palette.textPrimary
    Column(
        modifier = modifier.padding(vertical = Spacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = UfiTextStyles.metricValueCompact,
                color = valueColor,
                textAlign = TextAlign.Center,
            )
            Text(
                "GB",
                style = UfiTextStyles.noteCompact,
                color = palette.textSecondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                textAlign = TextAlign.Center,
            )
        }
        Text(
            label,
            style = UfiTextStyles.note,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            sub,
            style = UfiTextStyles.noteCompact,
            color = if (alert) palette.warning else palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BillVerticalDivider() {
    val palette = LocalResolvedPalette.current
    Box(
        Modifier
            .padding(horizontal = Spacing.Small)
            .width(1.dp)
            .height(64.dp)
            .background(palette.divider)
    )
}

@Composable
private fun BillPackageRow(pkg: BillPackage, now: LocalDateTime) {
    val palette = LocalResolvedPalette.current
    val soon = isExpiringSoon(pkg.validTo, now)
    val label = expiryLabel(pkg.validTo, now)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            color = if (pkg.kind == BillPackageKind.GENERAL) {
                palette.accent.copy(alpha = 0.12f)
            } else {
                palette.divider.copy(alpha = 0.5f)
            },
            shape = RoundedCornerShape(Spacing.CornerTag)
        ) {
            Text(
                text = if (pkg.kind == BillPackageKind.GENERAL) "通用" else "定向",
                style = MaterialTheme.typography.labelSmall,
                color = if (pkg.kind == BillPackageKind.GENERAL) palette.accent else palette.textSecondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(Spacing.Small))
        Column(Modifier.weight(1f)) {
            Text(
                pkg.name,
                style = UfiTextStyles.body,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "剩余 ${gb(pkg.remainingGb)} / ${gb(pkg.totalGb)} GB",
                    style = UfiTextStyles.note,
                    color = palette.textSecondary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    label,
                    style = UfiTextStyles.note,
                    color = if (soon) palette.warning else palette.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/** 金额/GB 统一两位小数；null 显示「—」，不填 0。 */
private fun money(value: Double?): String =
    value?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

private fun gb(value: Double?): String =
    value?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

/** 三栏卡片里的紧凑流量：整数不带 .0，非整数保留一位，给说明行省出宽度。 */
private fun gbCompact(value: Double?): String = value?.let {
    if (it % 1.0 == 0.0) String.format(Locale.US, "%.0f", it)
    else String.format(Locale.US, "%.1f", it)
} ?: "—"

private fun tenureText(months: Int): String {
    val years = months / 12
    val rest = months % 12
    return when {
        years > 0 && rest > 0 -> "${years}年${rest}个月"
        years > 0 -> "${years}年"
        else -> "${rest}个月"
    }
}

private fun starText(star: Double): String =
    if (star % 1.0 == 0.0) star.toInt().toString() else star.toString()

private val EXPIRY_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val EXPIRY_DATE = DateTimeFormatter.ofPattern("MM-dd")

private fun parseEnd(iso: String?): LocalDateTime? =
    iso?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

/** 24 小时内到期（含今天/明天）；已过期的返回 false，避免出现在「即将到期」里。 */
private fun isExpiringSoon(iso: String?, now: LocalDateTime): Boolean {
    val end = parseEnd(iso) ?: return false
    if (!end.isAfter(now)) return false
    return Duration.between(now, end) <= Duration.ofHours(24)
}

/**
 * 到期文案：今天 14:16 / 明天 00:00 / 11-05 / 已过期 / 未标到期。
 *
 * 只做展示，不改数据；真实数据里没有 validTo 时回退「未标到期」，不编日期。
 */
private fun expiryLabel(iso: String?, now: LocalDateTime): String {
    val end = parseEnd(iso) ?: return "未标到期"
    if (!end.isAfter(now)) return "已过期"
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), end.toLocalDate())
    return when {
        days <= 0L -> "今天 ${EXPIRY_TIME.format(end)} 到期"
        days == 1L -> "明天 ${EXPIRY_TIME.format(end)} 到期"
        else -> "${EXPIRY_DATE.format(end)} 到期"
    }
}

// ─────────────────────── 多运营商描述（2026-11-05）───────────────────────

/** 登录方式。加新运营商时先在这里选一种，或新增一种。 */
private enum class CarrierLoginFlow { SMS, QR, NONE, CARD }

/**
 * 一条运营商描述。
 *
 * **布局读它、不读硬编码的 if**：加电信/广电只需在 [BILL_CARRIERS] 里加一条，
 * 表单区按 [flow] 渲染、自动登录按 [supportsAutoLogin] 决定是否给开关。
 *
 * @param available 登录协议是否已接入。false 时表单区显示"即将支持"占位。
 */
private data class BillCarrierOption(
    val id: String,
    val name: String,
    val short: String,
    val flow: CarrierLoginFlow,
    val supportsAutoLogin: Boolean,
    val available: Boolean,
    /** 物联卡站点：`cloud` / `xj`；普通运营商为 null。 */
    val iotProvider: String? = null,
    /** 小鲸卡这类站点登录需要绑定手机号后四位。 */
    val requiresVerify: Boolean = false,
)

/**
 * 账号 → 登录面板的 carrier id。
 *
 * 物联卡的 `carrier` 统一是 `"iot_card"`，不足以区分站点；小鲸卡必须落到 `iot_xj`，
 * 否则明细页会拿云上卡的表单（没有手机尾号输入框）。
 */
private fun billCarrierIdFor(account: com.ufi_axis.data.model.CarrierAccountDto?): String? {
    if (account == null) return null
    if (account.accessMethod == "iot_card") {
        return if (account.iotProvider == "xj") "iot_xj" else "iot_card"
    }
    return account.carrier
}

/**
 * 二维码自动换新的间隔：**2 分钟**。
 *
 * 联通的码官方有效期约 10 分钟，但那只是上限 —— 用户拿着一张放了很久的码去扫，
 * 中途被风控/超时作废的概率会上升。2 分钟换一张，页面上的码基本总是"新鲜"的。
 */
private const val QR_AUTO_REFRESH_MS = 2 * 60 * 1000L

private val BILL_CARRIERS = listOf(
    BillCarrierOption("mobile", "中国移动", "移", CarrierLoginFlow.SMS, supportsAutoLogin = true, available = true),
    BillCarrierOption("unicom", "中国联通", "联", CarrierLoginFlow.QR, supportsAutoLogin = false, available = true),
    BillCarrierOption("telecom", "中国电信", "电", CarrierLoginFlow.NONE, supportsAutoLogin = false, available = false),
    BillCarrierOption("broadnet", "中国广电", "广", CarrierLoginFlow.NONE, supportsAutoLogin = false, available = false),
    // 2026-11-08：物联卡维度。它不是运营商，单独一条接入方式；登录只需充值号。
    BillCarrierOption(
        "iot_card", "云上卡", "云", CarrierLoginFlow.CARD,
        supportsAutoLogin = false, available = true, iotProvider = "cloud",
    ),
    // 2026-11-09：小鲸卡与云上卡同一套接口，但登录要绑定手机号后四位。
    BillCarrierOption(
        "iot_xj", "小鲸卡", "鲸", CarrierLoginFlow.CARD,
        supportsAutoLogin = false, available = true, iotProvider = "xj", requiresVerify = true,
    ),
)


/** 非短信登录的运营商：自动登录只给一行灰字，不摆一个点不动的开关。 */
@Composable
private fun AutoLoginUnavailableRow(reason: String) {
    val palette = LocalResolvedPalette.current
    BillCard {
        Text("自动登录", style = UfiTextStyles.cardTitle, color = palette.textSecondary)
        Spacer(Modifier.height(Spacing.Small))
        Text(reason, style = UfiTextStyles.note, color = palette.textSecondary)
    }
}
