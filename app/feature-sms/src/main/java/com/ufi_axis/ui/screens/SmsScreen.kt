package com.ufi_axis.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.BackHandler
import androidx.navigation.NavHostController
import androidx.compose.ui.platform.LocalContext
import com.ufi_axis.data.model.SmsContact
import com.ufi_axis.data.model.SmsRecord
import com.ufi_axis.data.model.VerificationCode
import com.ufi_axis.ui.animation.blurEntrance
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.ui.navigation.LocalPendingSmsPhone
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

// ════════════════════════════════════════════════════
// 短信主入口 — 标准手机短信界面风格
// ════════════════════════════════════════════════════

/** 会话列表静默自动刷新间隔（毫秒）。只在「消息」页签可见且没打开对话时跑。 */
private const val CONTACTS_AUTO_REFRESH_MS = 10_000L

/** 验证码缓存自动清理间隔档位（小时；"0" = 永不清理）。短信设置弹窗内联双栏 grid 用。 */
private val SMS_CODE_CLEANUP_OPTIONS = listOf(
    "0" to "永不清理",
    "1" to "1 小时",
    "6" to "6 小时",
    "24" to "24 小时",
    "72" to "3 天",
    "168" to "7 天",
    "720" to "30 天"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val palette = LocalResolvedPalette.current
    val toolsState by viewModel.toolsState.collectAsState()
    // 一次性动作（如「全部标为已读」）用它起协程等结果再弹 Toast，不进 ViewModel 状态。
    val scope = rememberCoroutineScope()
    var isComposing by remember { mutableStateOf(false) }
    var showOptInDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    // 点击验证码卡片后要展示详情的那一条（null = 不显示弹窗）。
    // 2026-08-29：原来点卡片是 openVerificationCodeSource(source) → 切到「消息」页签并打开该号码的
    // 对话。但用户点验证码卡片的意图基本是"看清完整内容 / 再确认一遍码"，被弹到另一个页签反而
    // 丢失了当前浏览位置（回来还要再点回「通知」）。改成原地弹窗，跳转降级为弹窗里的一个按钮。
    var codeDetail by remember { mutableStateOf<VerificationCode?>(null) }
    // 「查看原对话」跳走前暂存的那条：对话关闭后原样弹回来，让用户回到点跳转之前的状态
    var reopenCodeAfterConversation by remember { mutableStateOf<VerificationCode?>(null) }
    // 两个列表的滚动位置提到屏幕级持有。对话页是根 AnimatedContent 的另一层，
    // 切过去时列表层整块被卸载，remember 在里面的 LazyListState 会一起没掉 ——
    // 于是"滑到中间点进对话、返回后回到顶部"。挂在这里就能跨转场保留。
    val contactsListState = rememberLazyListState()
    val codesListState = rememberLazyListState()
    val context = LocalContext.current

    // ── 首屏骨架的判据：「本次进入页面，这份数据还没出过任何结果」──────────────────
    // 为什么不能直接看 isLoading（上一轮就是这么写的）：
    //  1) 进页面第一帧 isLoading 还是 false（loadSmsContacts 要等 LaunchedEffect 才发出去），
    //     直接看它会先闪一帧「暂无短信」空态、再跳骨架、再跳列表 —— 连跳两次；
    //  2) isLoading 之后每次手动刷新 / 重新拉取都会再翻 true，直接看它等于"刷新也放骨架"，
    //     而按需求刷新必须完全无动画。
    // 闩锁只认第一次落地（拿到数据 / 报错 / 观察到一次 loading 结束），置位后不再回落。
    val contactsFirstLoadPending = rememberFirstLoadPending(
        loading = { toolsState.isLoading },
        hasData = { toolsState.smsContacts.isNotEmpty() },
        failed = { toolsState.errorMessage != null }
    )
    val codesFirstLoadPending = rememberFirstLoadPending(
        loading = { toolsState.verificationCodesLoading },
        hasData = { toolsState.verificationCodes.isNotEmpty() },
        failed = { toolsState.errorMessage != null }
    )

    // 发完短信后要把会话列表拉回顶部。
    // 列表 `items(contacts, key = { it.phoneNumber })` 是带 key 的，而后端按最新消息时间重排，
    // 刚更新的会话会插到 index 0；带 key 的 LazyColumn 会把视口锚定在**原来的首个 key** 上，
    // 于是新置顶的那条被挤到视口上方 —— 表现就是"发完列表没变化，得手动往上划才看得到"。
    // 用一个待办标记 + 等列表真正刷新后再滚：sendSms 是异步的，立刻滚只会滚到旧数据上。
    var pendingScrollContactsTop by remember { mutableStateOf(false) }
    LaunchedEffect(toolsState.smsContacts, toolsState.conversationPhone) {
        // 只在列表层真正可见时滚：对话里发完消息也会置这个标记，那时列表还没组合，
        // 滚动请求会挂在没有布局信息的 LazyListState 上白丢一次 —— 等返回列表再滚。
        if (pendingScrollContactsTop && toolsState.conversationPhone.isEmpty() &&
            toolsState.smsContacts.isNotEmpty()) {
            pendingScrollContactsTop = false
            contactsListState.animateScrollToItem(0)
        }
    }

    // 会话列表自动刷新：设备收到新短信、或短信从别处发出，都不会主动推给 app ——
    // 不轮询的话列表要等用户下拉 / 重进页面才更新。静默刷新，不点亮下拉指示器。
    // 只在「消息」页签且没打开对话时跑：对话层有自己的刷新路径，页面离开时 LaunchedEffect 自动取消。
    LaunchedEffect(toolsState.smsTab, toolsState.conversationPhone) {
        if (toolsState.smsTab != 0 || toolsState.conversationPhone.isNotEmpty()) return@LaunchedEffect
        while (true) {
            delay(CONTACTS_AUTO_REFRESH_MS)
            viewModel.tools.loadSmsContacts(silent = true)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.tools.loadSmsContacts()
        viewModel.tools.refreshDeviceConfig()
        // 进入页面时也静默拉一次验证码：否则停在「消息」页签时 unread 恒为 0，
        // 「通知」角标要等用户点进去才算得出来（点进去又立刻清零）→ 角标永远不亮。
        viewModel.tools.loadVerificationCodes()
    }
    rememberResumeRefresh {
        viewModel.tools.loadSmsContacts()
        viewModel.tools.loadVerificationCodes()
    }

    // 通知深链接：点击短信/验证码通知跳转到此界面后，自动打开对应对话。
    // 深链接来源时切换到通知 Tab，关闭对话后回到通知界面而非消息列表
    val pendingSmsPhone = LocalPendingSmsPhone.current
    var isFromDeepLink by remember { mutableStateOf(false) }
    LaunchedEffect(pendingSmsPhone.value) {
        val phone = pendingSmsPhone.value
        if (!phone.isNullOrEmpty()) {
            viewModel.tools.switchSmsTab(1)  // 切到通知 Tab
            isFromDeepLink = true
            viewModel.tools.openConversation(phone)
            pendingSmsPhone.value = null
        }
    }

    // 首次切到通知 Tab 时弹出引导
    LaunchedEffect(toolsState.smsTab) {
        if (toolsState.smsTab == 1 && !toolsState.smsCodeEnabled && !toolsState.smsCodeOptInShown) {
            showOptInDialog = true
        }
    }

    // ViewModel 内部发起的跳转（如验证码弹窗里的「查看原对话」）：打开对话层。
    // 注意这里**不切页签** —— 对话是覆盖整屏的一层，底下停在哪个页签不影响显示，
    // 而关掉对话时正好原样落回「通知」页签（旧实现顺手把 smsTab 置 0，返回就掉到消息列表了）。
    LaunchedEffect(toolsState.navigateToPhone) {
        val phone = toolsState.navigateToPhone
        if (phone.isNotEmpty()) {
            // 带上目标消息 id：对话打开后直接滚到并高亮那一条（0 = 不定位）
            viewModel.tools.openConversation(phone, toolsState.navigateToMsgId)
            viewModel.tools.clearNavigateToPhone()
        }
    }

    // 对话关闭后，把跳转前暂存的验证码弹窗恢复出来（配合上面不切页签 + 列表滚动位置提到屏幕级，
    // 「看一眼原对话再返回」能完整回到点跳转之前的状态）
    LaunchedEffect(toolsState.conversationPhone) {
        if (toolsState.conversationPhone.isEmpty()) {
            reopenCodeAfterConversation?.let {
                codeDetail = it
                reopenCodeAfterConversation = null
            }
        }
    }

    // 对话态：系统返回键先关对话回到列表，而不是直接退出短信页。
    // 新建短信 2026-08-29 由整页改成弹窗，返回键由 UfiCustomDialog 自己吃掉，
    // 这里只保留 `!isComposing` 作为显式优先级说明。
    BackHandler(enabled = !isComposing && toolsState.conversationPhone.isNotEmpty()) {
        viewModel.tools.closeConversation()
    }

    UfiScreenScaffold(
        title = "短信",
        navController = navController,
        showBack = true,
        navigationIcon = {
            // 上下文感知返回按钮：对话打开时关闭对话，否则退出短信页面
            val hasConversation = toolsState.conversationPhone.isNotEmpty()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (hasConversation) {
                                viewModel.tools.closeConversation()
                                // 深链接打开的对话：关闭后留在通知 Tab（isFromDeepLink 保持 true）
                                // 再次按返回键退出短信页面
                            } else {
                                if (isFromDeepLink) {
                                    // 从深链接对话返回列表后，再按一次返回键退出短信页
                                    isFromDeepLink = false
                                }
                                navController.popBackStack()
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (hasConversation) "返回列表" else "返回",
                    tint = palette.accent,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        actions = {
            // 手动刷新：下拉手势删掉后的唯一主动刷新入口。
            // 只在列表层出现 —— 对话层自己有分页加载，顶栏再给一个"刷新"会让人以为是刷对话。
            // 按当前页签分派，不是无脑全刷：两个页签的数据源不同（联系人聚合 / 验证码缓存），
            // 全刷会白发一个请求。
            if (toolsState.conversationPhone.isEmpty()) {
                IconButton(
                    onClick = {
                        if (toolsState.smsTab == 0) viewModel.tools.loadSmsContacts()
                        else viewModel.tools.loadVerificationCodes()
                    }
                ) {
                    Icon(Icons.Default.Refresh, "刷新", tint = palette.textSecondary)
                }
            }
            IconButton(onClick = { showSettingsDialog = true }) {
                Icon(Icons.Default.Settings, "设置", tint = palette.textSecondary)
            }
        }
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 「列表层 ⇄ 对话层」的转场提到这里做，是修"切换抽搐"的关键。
            // 旧结构：Tab 栏靠 `if (conversationPhone.isEmpty())` 增删，转场在 Tab 栏**下方**的内容
            // 区里跑 —— 转场第 1 帧 Tab 栏节点就被移除，内容区高度瞬间抽高一截，动画中途整块内容
            // 跳一下。现在两层都是铺满整屏的兄弟节点，列表层（含 Tab 栏）作为一个整体平移出去，
            // 转场期间没有任何节点增删、没有高度变化，所以不会抽。
            // sizeTransform = null 同理：不让 AnimatedContent 去补间容器尺寸。
            val emphasizedDecelerate = UfiMotion.Easing.EmphasizedIn
            val emphasizedAccelerate = UfiMotion.Easing.EmphasizedOut
            AnimatedContent(
                targetState = toolsState.conversationPhone.ifEmpty { null },
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val enteringConversation = targetState != null
                    // 2026-09-04（P2b）：下面两处 `fadeOut(tween(240))` → Duration.Fluid（250，+10ms，
                    // 在吸附容差内）。240 与梯度上的 250 是同一种「离场比进场快半拍」的手感，
                    // 全库不该保留两个数；顺带与弹窗离场（UfiDialogAnim.ExitDuration）走了同一档。
                    val transform = if (enteringConversation) {
                        // push：新页整幅宽度滑入压在上面，旧页只走 1/4 宽做视差（M3 的做法，
                        // 两页等速对滑会像"翻牌"，视差才有层级）
                        (slideInHorizontally(tween(UfiMotion.Duration.Deliberate, easing = emphasizedDecelerate)) { it } +
                            fadeIn(tween(UfiMotion.Duration.Quick))) togetherWith
                            (slideOutHorizontally(tween(UfiMotion.Duration.Deliberate, easing = emphasizedAccelerate)) { -it / 4 } +
                                fadeOut(tween(UfiMotion.Duration.Fluid)))
                    } else {
                        (slideInHorizontally(tween(UfiMotion.Duration.Deliberate, easing = emphasizedDecelerate)) { -it / 4 } +
                            fadeIn(tween(UfiMotion.Duration.Quick))) togetherWith
                            (slideOutHorizontally(tween(UfiMotion.Duration.Deliberate, easing = emphasizedAccelerate)) { it } +
                                fadeOut(tween(UfiMotion.Duration.Fluid)))
                    }
                    // 进入对话时新页在上（zIndex 1），返回时旧页在上盖住新页 —— 这才是 push/pop
                    // 该有的遮挡关系；不设的话两页同层，看着就是个交叉淡入淡出
                    transform.targetContentZIndex = if (enteringConversation) 1f else 0f
                    transform using null
                },
                label = "smsRoot"
            ) { conversationPhone ->
                if (conversationPhone != null) {
                    SmsConversationView(
                        phone = conversationPhone,
                        messages = toolsState.conversationMessages,
                        isLoading = toolsState.conversationLoading,
                        hasMore = toolsState.conversationHasMore,
                        totalCount = toolsState.conversationTotal,
                        deletingMessageIds = toolsState.deletingMessageIds,
                        targetMsgId = toolsState.conversationTargetMsgId,
                        viewModel = viewModel,
                        onBack = { viewModel.tools.closeConversation() },
                        onLoadMore = { viewModel.tools.loadMoreConversation() },
                        onSent = { pendingScrollContactsTop = true }
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        // 2026-09-05：错误横幅移除 —— ToolsState 的 errorMessage 现在由 Activity 级
                        // 全局错误浮层统一展示（MainActivity 读 viewModel.globalError）。
                        // 留在这里会和全局那张卡同位重叠（两者都是屏幕顶部 48dp 的 Popup）。

                        // 顶部滑块 Tab 栏 —— 与「自动化」页同款，统一全站 Tab 视觉。
                        // 角标走公共组件的 badges 槽位；计数是**未读水位**（verificationCodesUnread），
                        // 不是缓存总条数 —— 后者看过也不会变，角标会永久挂着。
                        UfiScrollableTabRow(
                            selectedTabIndex = toolsState.smsTab,
                            onTabSelected = { viewModel.tools.switchSmsTab(it) },
                            tabs = listOf("消息", "通知"),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            badges = listOf(null, toolsState.verificationCodesUnread)
                        )

                        // 内容区。用 BoxWithConstraints 是为了让 FAB 的边距按**实际可用区域**
                        // 取百分比（见下方 fabEndPad / fabBottomPad），而不是写死 dp ——
                        // 写死值在小屏上顶边、在平板上又离内容太远。
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            if (toolsState.smsTab == 0) {
                                // 2026-09-04 去掉下拉刷新：原来这里是 `PullToRefreshBox(indicator = {})`，
                                // 即「保留手势、不画指示器」的折中版。现在连手势一起删掉 ——
                                // 一个既不给任何视觉反馈、又会和列表滚动抢通道的手势，用户只会误触，
                                // 触发了也不知道发生了什么。主动刷新改走顶栏刷新按钮（见 actions）。
                                Box(Modifier.fillMaxSize()) {
                                    when {
                                        toolsState.smsContacts.isNotEmpty() -> {
                                            SmsConversationList(
                                                contacts = toolsState.smsContacts,
                                                listState = contactsListState,
                                                viewModel = viewModel
                                            )
                                        }
                                        // 首屏骨架：从未加载过且列表为空。
                                        // 判据是闩锁而不是 isLoading —— 后者会让每次刷新都重放骨架，
                                        // 而"已有数据时刷新"根本走不到这里（上面那个分支先命中），
                                        // "空列表时刷新"也不该再放动画。
                                        contactsFirstLoadPending -> {
                                            UfiSkeletonList(
                                                modifier = Modifier.padding(
                                                    horizontal = Spacing.CardHorizontalMargin,
                                                    vertical = 12.dp
                                                )
                                            )
                                        }
                                        // smsList 非空但 contacts 为空：数据源不一致的过渡态，
                                        // 不画空态也不画骨架，等聚合结果到位（原逻辑保留）。
                                        toolsState.smsList.isNotEmpty() -> Unit
                                        else -> {
                                            UfiEmptyState(
                                                icon = Icons.Default.ChatBubbleOutline,
                                                message = "暂无短信",
                                                hint = "点击右下角按钮发送新短信"
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 通知 Tab：验证码面板（下拉手势同样删除，刷新走顶栏按钮）
                                Box(Modifier.fillMaxSize()) {
                                    VerificationCodePanel(
                                        codes = toolsState.verificationCodes,
                                        firstLoadPending = codesFirstLoadPending,
                                        enabled = toolsState.smsCodeEnabled,
                                        listState = codesListState,
                                        onCopy = { code ->
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("验证码", code))
                                            toastMessage = ToastMessage("已复制 $code", ToastType.SUCCESS)
                                        },
                                        onCardClick = { vc -> codeDetail = vc },
                                        onEnable = { viewModel.tools.setSmsCodeEnabled(true) }
                                    )
                                }
                            }

                            // FAB（仅消息 Tab）：公共 UfiFloatingActionButton（带按压弹性缩放）。
                            // 边距按可用区域百分比给（原来是写死的四边 12dp / 后来 24+40dp）：
                            // 固定 dp 在小屏上贴边压住最后一条会话行的未读角标、也顶到系统手势区，
                            // 在平板上又离内容太远。coerceIn 兜住极端长宽比，不会缩到贴边或飘到屏幕中间。
                            if (toolsState.smsTab == 0) {
                                val fabEndPad = (maxWidth * 0.07f).coerceIn(20.dp, 36.dp)
                                val fabBottomPad = (maxHeight * 0.07f).coerceIn(28.dp, 64.dp)
                                UfiFloatingActionButton(
                                    icon = Icons.Default.ChatBubbleOutline,
                                    onClick = { isComposing = true },
                                    contentDescription = "新建短信",
                                    modifier = Modifier.align(Alignment.BottomEnd)
                                        .padding(end = fabEndPad, bottom = fabBottomPad)
                                )
                            }
                        }
                    }
                }
            }
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }

    // ── 引导对话框 ──
    if (showOptInDialog) {
        UfiCustomDialog(
            visible = true,
            onDismiss = {
                showOptInDialog = false
                viewModel.tools.switchSmsTab(0)
                viewModel.tools.setSmsCodeEnabled(false)
            },
            title = "短信验证码解析",
            icon = rememberVectorPainter(Icons.Filled.Sms),
            showCloseButton = false
        ) {
            UfiDialogBody {
                Text(
                    "开启后将自动解析新收到的验证码短信，方便快速复制使用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary
                )
                Text("· 完全在本地运行，不上传任何数据", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                Text("· 仅解析接收方向的短信", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                Text("· 识别有概率出错，缓存可配置自动清理", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
            }
            UfiDialogActions(
                onDismiss = {
                    showOptInDialog = false
                    viewModel.tools.switchSmsTab(0)
                },
                onConfirm = {
                    showOptInDialog = false
                    viewModel.tools.setSmsCodeEnabled(true)
                },
                confirmText = "开启",
                dismissText = "取消"
            )
        }
    }

    // ── 新建短信弹窗（原整屏写信页）──
    if (isComposing) {
        SmsComposerDialog(
            contacts = toolsState.smsContacts,
            onClose = { isComposing = false },
            onSend = { phone, msg ->
                viewModel.tools.sendSms(phone.trim(), msg)
                isComposing = false
                // 发完落回「消息」页签并把列表滚到顶部：新会话/刚更新的会话就在 index 0，
                // 不滚的话它在视口上方，用户以为没发出去（见 pendingScrollContactsTop 注释）
                viewModel.tools.switchSmsTab(0)
                pendingScrollContactsTop = true
            }
        )
    }

    // ── 验证码详情对话框 ──
    codeDetail?.let { vc ->
        val fullTime = remember(vc.timestamp) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(vc.timestamp))
        }
        // 打开时不再回查原文：core 从 DB v8 起把原短信全文一并存进验证码缓存
        // （snippet 仍是 80 字预览），列表拿到的那一条就已经带全文，弹窗直接渲染，
        // 不会像"先显示 snippet、请求回来再换成全文"那样闪一下。
        // body 为空 = 旧数据 / core 版本较旧 → 回退 snippet。
        val closeDialog = { codeDetail = null }
        // 改用 UfiScrollableDialog：短信正文要**完整显示**，原来给正文单独套
        // `heightIn(220dp) + verticalScroll` —— 长短信被压在一个矮框里内部滚动，
        // 看着就像被截断了。现在正文按内容自然铺开，超出弹窗预算时由弹窗整体滚动，
        // 动作区走 actions 槽位固定在底部（塞进 content 会导致底部留白，见 FIX-24）。
        UfiScrollableDialog(
            visible = true,
            onDismiss = closeDialog,
            title = "验证码详情",
            icon = rememberVectorPainter(Icons.Filled.VerifiedUser),
            // 右上角关闭按钮去掉：底部已有「确认」，两个等价的关闭入口是噪音
            showCloseButton = false,
            actions = {
                // 左「删除」右「确认」：走公共 UfiDialogActions 的 dismissDestructive 槽位，
                // 间距 / 底边距 / 按钮样式全部继承标准弹窗，不在这里手搓 Row + 两个按钮。
                UfiDialogActions(
                    onDismiss = {
                        viewModel.tools.deleteVerificationCode(vc.msgId)
                        closeDialog()
                    },
                    onConfirm = closeDialog,
                    confirmText = "确认",
                    dismissText = "删除",
                    dismissDestructive = true
                )
            }
        ) {
            UfiDialogBody {
                // 验证码本体：等宽大字 + 一键复制。放在最上面，因为"再看一眼码"是点进来的主要动机
                Surface(
                    shape = UfiCardDefaults.dialogShape,
                    color = palette.accent.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = vc.code,
                            style = UfiTextStyles.monoCode,
                            color = palette.textPrimary
                        )
                        // 复制走公共 UfiButton（Primary + Small）：原来是 M3 原生 FilledTonalButton，
                        // 取色走 colorScheme 而不是 palette，深浅主题下和卡片同色系对不上
                        UfiButton(
                            size = UfiButtonSize.Small,
                            text = "复制",
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("验证码", vc.code))
                                toastMessage = ToastMessage("已复制 ${vc.code}", ToastType.SUCCESS)
                            },
                            modifier = Modifier.width(84.dp)
                        )
                    }
                }

                CodeDetailRow(label = "来源号码", value = vc.source)
                CodeDetailRow(label = "命中关键词", value = vc.keyword.ifBlank { "—" })
                CodeDetailRow(label = "接收时间", value = fullTime)

                // 短信正文：core 从 DB v8 起把全文一并存进验证码缓存，直接完整渲染，不裁字、
                // 也不再给正文单独套滚动框（长短信整段铺开，超出部分由弹窗自身滚动）。
                Text("短信内容", style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
                Surface(
                    shape = UfiCardDefaults.shape,
                    color = palette.divider.copy(alpha = if (palette.isDark) 0.35f else 0.18f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = vc.body.ifBlank { vc.snippet }.ifBlank { "（无正文）" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                // 跳转走公共 UfiButton（variant = Secondary）：原来是 M3 原生 TextButton 挤在标题行右侧，
                // 既不是公共组件、点击区域也只有文字那么大。
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    text = "查看原对话",
                    onClick = {
                        // 暂存当前这条：对话关掉后弹窗自动回来，不切页签也不丢滚动位置。
                        // 同时把 msgId 带过去 —— 对话打开后直接滚到并高亮这条验证码短信。
                        reopenCodeAfterConversation = vc
                        codeDetail = null
                        viewModel.tools.openVerificationCodeSource(vc.source, vc.msgId)
                    }
                )
            }
        }
    }

    // ── SMS 设置对话框 ──
    // 容量只在这个弹窗里显示，所以打开时才拉 —— 放页面级 LaunchedEffect 等于每次进短信页白跑一次。
    LaunchedEffect(showSettingsDialog) {
        if (showSettingsDialog) viewModel.tools.loadSmsCount()
    }
    if (showSettingsDialog) {
        UfiCustomDialog(
            visible = true,
            onDismiss = { showSettingsDialog = false },
            title = "短信设置",
            icon = rememberVectorPainter(Icons.Filled.Settings),
            showCloseButton = false
        ) {
            UfiDialogBody {
                // 弹窗内一律用 UfiDialog* 家族。原来这里混用了 UfiSettingsToggle / UfiSettingsValue /
                // UfiInfoRow —— 那三个是「设置页卡片行」组件（bodyLarge 标题 + 24dp 前置图标 +
                // KeyboardArrowRight），塞进弹窗后字号比同窗其它字段大一号，右箭头还会误导成
                // 「点进下一页」（实际是就地改值 / 就地执行）。
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    UfiDialogSwitchField(
                        label = "验证码自动解析",
                        checked = toolsState.smsCodeEnabled,
                        onCheckedChange = { viewModel.tools.setSmsCodeEnabled(it) }
                    )
                    Text(
                        "自动识别新收到的验证码短信",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
                if (toolsState.smsCodeEnabled) {
                    // 原来是「一行 UfiSettingsValue 再叠一层 UfiSelectionDialog」——弹窗套弹窗。
                    // 改成弹窗内联的 UfiOptionGrid（公共组件），与 DeviceControlScreen
                    // 「WiFi 休眠」七档双栏同款，少一层窗口也少一次误触关错窗的机会。
                    UfiDialogField(label = "自动清理间隔") {
                        UfiOptionGrid(
                            options = SMS_CODE_CLEANUP_OPTIONS.map { (value, label) ->
                                UfiOptionItem(value = value, label = label)
                            },
                            selectedValue = toolsState.smsCodeCleanupHours.toString(),
                            onSelect = { viewModel.tools.setSmsCodeCleanupHours(it.toInt()) },
                            columns = 2
                        )
                    }
                }
                // 「短信转发」入口已迁到 设置 → 通知与守护 → 邮件通知
                // （功能范围从"只转短信"扩到"转所有通知场景"，不再只属于短信页）。

                // 设备短信容量（GET /api/sms/count）。只在读到过时画：读失败不留占位，
                // 更不能拿联系人未读求和去凑一个偏小的数字冒充全局口径。
                toolsState.smsCount?.let { c ->
                    UfiDialogInfoRow("设备短信条数", "${c.total} 条 · ${c.unread} 条未读")
                }

                // 一次性动作，不是开关也不是跳转：走按钮而不是「行 + 右箭头」。
                // 未读为 0 时禁用 —— 没有未读还发一次请求纯属白跑。
                // 未读数优先取 /count 的全局值，它没读到才回落联系人求和（会偏小）。
                val unreadTotal = toolsState.smsCount?.unread
                    ?: toolsState.smsContacts.sumOf { it.unread }
                UfiDialogField(label = "未读短信") {
                    UfiButton(
                        variant = UfiButtonVariant.Secondary,
                        text = if (unreadTotal > 0) "全部标为已读（$unreadTotal 条）" else "无未读短信",
                        enabled = unreadTotal > 0,
                        onClick = {
                            showSettingsDialog = false
                            scope.launch {
                                val ok = viewModel.tools.markAllSmsRead()
                                toastMessage = if (ok) ToastMessage("已全部标为已读", ToastType.SUCCESS)
                                else ToastMessage("操作失败，请重试", ToastType.ERROR)
                            }
                        }
                    )
                }
            }
            // 右上角关闭按钮换成底部标准动作条：与验证码详情弹窗一致，全部走公共组件。
            UfiDialogActions(
                onDismiss = { showSettingsDialog = false },
                onConfirm = { showSettingsDialog = false },
                confirmText = "完成",
                dismissText = null
            )
        }
    }
}

// ═══════════════════════════════════════════════
// 会话列表（纯列表；列表态 ⇄ 对话态的转场已上提到 SmsScreen 顶层）
// ═══════════════════════════════════════════════

/**
 * 会话列表。
 *
 * 2026-08-29：这里**只剩列表**。原来它同时承担「列表 ⇄ 对话」的切换，转场发生在 Tab 栏
 * 下方的内容区里，而 Tab 栏本身又靠 `if (conversationPhone.isEmpty())` 增删 ——
 * 转场一开始 Tab 栏就被移除，内容区高度突然抽高一截，于是看到"抽搐"。
 * 现在切换整体上提到 SmsScreen 的根 AnimatedContent：对话页是覆盖整屏的另一层，
 * 列表层（含 Tab 栏）作为一个整体平移出去，中途没有任何节点增删，高度恒定。
 */
@Composable
private fun SmsConversationList(
    contacts: List<SmsContact>,
    listState: LazyListState,
    viewModel: MainViewModel
) {
    // ── 懒加载 = 渐进渲染（不是真分页）────────────────────────────────────────────
    // 依据：会话列表来自 `GET /api/sms/contacts`（RootSmsRoutes.kt），后端按号码聚合后
    // 一次性回全量，没有 offset/limit；app 侧 `getSmsContacts()` 也是无参。不动 core。
    //
    // 注意：**对话内部（SmsConversationView）走的是真分页**，`GET /api/sms/list` 支持
    // limit/offset，ToolsModule 的 openConversation / loadMoreConversation 已经在用。
    // 两处机制不同是因为接口能力不同，不是实现不一致。
    var renderLimit by remember { mutableStateOf(SMS_RENDER_PAGE) }
    val rendered = contacts.take(renderLimit)
    LaunchedEffect(listState, contacts.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= 0 &&
                    lastVisible >= renderLimit - SMS_RENDER_LOAD_AHEAD &&
                    renderLimit < contacts.size
                ) {
                    renderLimit = (renderLimit + SMS_RENDER_PAGE).coerceAtMost(contacts.size)
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.CardHorizontalMargin, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(rendered, key = { it.phoneNumber }) { contact ->
            SmsConversationRow(
                contact = contact,
                onClick = { viewModel.tools.openConversation(contact.phoneNumber) }
            )
        }
        // 2026-09-04 删掉尾部 `item("render-footer") { UfiSkeletonListItem() }`：
        // 渐进渲染是纯本地 `contacts.take(renderLimit)`，下一批在同一帧就能画出来，不等任何 IO。
        // 给它挂一条 shimmer 骨架，本质就是"底部加载动画" —— 而这一页要的是"只有进入时有动画"。
    }
}

// ═══════════════════════════════════════════════
// 单条会话行 — 扁平设计，参考 Google Messages
// ═══════════════════════════════════════════════

@Composable
private fun SmsConversationRow(
    contact: SmsContact,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val hasUnread = contact.unread > 0
    val cardShape = UfiCardDefaults.dialogShape

    Surface(
        onClick = onClick,
        shape = cardShape,
        color = palette.cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .ufiCardShadow(elevation = 3.dp, shape = cardShape)
            .border(1.dp, palette.divider.copy(alpha = 0.4f), cardShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像：如果是接收且未读，加一个呼吸边框
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasUnread) palette.accent.copy(alpha = 0.15f)
                        else palette.divider.copy(alpha = 0.1f)
                    )
                    .border(
                        width = if (hasUnread) 1.5.dp else 0.dp,
                        color = if (hasUnread) palette.accent.copy(alpha = 0.3f) else Color.Transparent,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.phoneNumber.takeLast(2),
                    style = UfiTextStyles.panelTitle.copy(fontWeight = UfiWeight.Hero),
                    color = if (hasUnread) palette.accent else palette.textSecondary
                )
            }

            Spacer(Modifier.width(14.dp))

            // 信息区
            Column(Modifier.weight(1f)) {
                // 第一行：号码 + 时间
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = contact.phoneNumber,
                        style = UfiTextStyles.bodyLead.copy(
                            fontWeight = if (hasUnread) UfiWeight.Strong else UfiWeight.Emphasis
                        ),
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = FormatUtils.formatRelativeTime(contact.latestTimestamp),
                        style = UfiTextStyles.caption.copy(
                            fontWeight = if (hasUnread) UfiWeight.Medium else UfiWeight.Regular
                        ),
                        color = if (hasUnread) palette.accent else palette.textSecondary
                    )
                }

                Spacer(Modifier.height(4.dp))

                // 第二行：预览 + 未读角标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val preview = contact.latestMsg.let {
                        if (it.length > 60) it.take(60) + "\u2026" else it
                    }
                    Text(
                        text = preview,
                        style = UfiTextStyles.note.copy(
                            fontWeight = if (hasUnread) UfiWeight.Medium else UfiWeight.Regular
                        ),
                        color = if (hasUnread) palette.textPrimary
                                 else palette.textSecondary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (hasUnread) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .height(18.dp)
                                .widthIn(min = 18.dp)
                                .clip(CircleShape)
                                .background(palette.accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (contact.unread > 99) "99+" else "${contact.unread}",
                                style = UfiTextStyles.caption.copy(fontWeight = UfiWeight.Hero),
                                // 2026-09-03（P1c）：原为写死 `Color.White`。徽标底色是上一行的
                                // `palette.accent`（不是 error），所以配 onAccent —— 换配色时若某主题
                                // 的 accent 是浅色，写死白字会直接在圆片上消失，未读数就看不见了。
                                // 同页 ChatBubbleRow 的发出方内容色 2026-08-29 已按同样理由改成 onAccent。
                                color = palette.onAccent,
                                modifier = Modifier.padding(horizontal = 5.dp)
                            )
                        }
                    } else if (contact.total > 1) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${contact.total}条",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════
// 对话视图 — 标准聊天气泡 + 输入栏
// ═══════════════════════════════════════════════

@Suppress("DEPRECATION")
@Composable
private fun SmsConversationView(
    phone: String,
    messages: List<SmsRecord>,
    isLoading: Boolean,
    hasMore: Boolean,
    totalCount: Int,
    deletingMessageIds: Set<Long>,
    targetMsgId: Long,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    /** 在对话里发出消息后回调：屏幕层据此在返回列表时把会话列表拉回顶部 */
    onSent: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dayFormatter = remember { SimpleDateFormat("MM月dd日", Locale.getDefault()) }
    val palette = LocalResolvedPalette.current
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    var initialScrolled by remember { mutableStateOf(false) }
    // ── 定位到指定消息（验证码「查看原对话」）──
    // 只带号码过来的话对话只会停在最新一条，用户还得自己往上翻 —— 这里滚到目标并短暂高亮。
    var targetScrolled by remember(targetMsgId) { mutableStateOf(false) }
    var highlightId by remember(targetMsgId) { mutableStateOf<Long?>(null) }
    // 目标不在已加载页里时向上补页的次数上限（每页 100 条；封顶避免"目标已被设备删掉"时无限翻页）
    var seekAttempts by remember(targetMsgId) { mutableIntStateOf(0) }
    // 列表首项可能是「查看更早的消息」按钮，消息下标要整体后移一位。
    // 2026-09-04：原判据是 `isLoading || (hasMore && messages.isNotEmpty())` —— 那时首项还可能是
    // 「加载中」转圈 item。转圈删掉后（见下方 LazyColumn），isLoading 不再影响首项，判据同步收窄；
    // 首屏骨架只在 messages 为空时存在，那种情况下这个 offset 用不上。
    val headerOffset = if (hasMore && messages.isNotEmpty()) 1 else 0

    LaunchedEffect(messages, targetMsgId, isLoading) {
        if (messages.isEmpty()) {
            // 列表被清空 = openConversation 正在重新拉取（发完短信就会走这条路）。
            // 这里必须把首屏定位标记复位：不复位的话重新填充后 LazyColumn 停在 index 0，
            // 用户看到的是这个会话**最早**的历史，得自己往下划才能看到刚发出去的那条。
            initialScrolled = false
            return@LaunchedEffect
        }
        // 先保证有内容可看：无论有没有定位目标，首次都落到最新一条
        if (!initialScrolled) {
            listState.scrollToItem(messages.size)
            initialScrolled = true
        }
        if (targetMsgId == 0L || targetScrolled) return@LaunchedEffect

        val idx = messages.indexOfFirst { it.id == targetMsgId }
        if (idx >= 0) {
            targetScrolled = true
            listState.animateScrollToItem((idx + headerOffset).coerceAtLeast(0))
            highlightId = targetMsgId
        } else if (hasMore && !isLoading && seekAttempts < 5) {
            // 目标还在更早的页里：继续向上取，下一次 messages 变化会再进来找一遍
            seekAttempts++
            onLoadMore()
        }
    }

    // 闪烁窗口和 ChatBubbleRow 里的动画时长对齐（8 × 250ms = 2s）：
    // 单独挂一个 effect，跟在上面那个里 delay 的话，列表一变（轮询 / 加载更多）
    // 协程就被取消，`highlightId = null` 永远执行不到，气泡会一直闪。
    LaunchedEffect(highlightId) {
        if (highlightId != null) {
            delay(2000)
            highlightId = null
        }
    }

    // 加载更多锚点恢复
    var pendingAnchorKey by remember { mutableStateOf<String?>(null) }
    var prevListSize by remember { mutableStateOf(0) }

    LaunchedEffect(messages.size) {
        if (initialScrolled && messages.size > prevListSize && prevListSize > 0) {
            pendingAnchorKey?.let { key ->
                val idx = messages.indexOfFirst { "${it.id}-${it.timestamp}" == key }
                // 原来写死 +1（假设首项一定有 header）。首项条件已经收窄成 headerOffset，
                // 这里跟着用同一个值：最后一页拉完 hasMore 变 false、header 消失时不会错位一格。
                if (idx >= 0) listState.scrollToItem(idx + headerOffset, 0)
            }
            pendingAnchorKey = null
        }
        prevListSize = messages.size
    }

    // 日期分隔符
    val dateHeaders = remember(messages) {
        val headers = mutableMapOf<Int, String>()
        var lastDate: Long? = null
        messages.forEachIndexed { index, sms ->
            if (lastDate == null || (sms.timestamp - (lastDate ?: 0L)) > 3600_000L) {
                headers[index] = dayFormatter.format(Date(sms.timestamp))
                lastDate = sms.timestamp
            }
        }
        headers
    }

    // imePadding()：键盘弹出时整个对话面板上移，输入栏浮在输入法之上。
    // 这是「控制台同款」里最关键、上一版漏掉的一项 —— 控制台在 AdvancedConsoleScreen.kt:117
    // 就是 `Column(Modifier.fillMaxSize().imePadding())`。缺了它输入栏会被输入法直接盖住。
    // 放在根 Column 而不是只给输入栏：消息列表用 weight(1f)，父级让出高度后列表自动压缩，
    // 于是"最后一条消息 + 输入栏"始终同时可见；只给输入栏加会把它顶到列表上方遮住内容。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(palette.pageBg)
    ) {
        // 顶栏直接坐在 pageBg 上：不再包 `Surface(cardBg, tonalElevation=2, ufiCardShadow)`。
        // 那一层在浅色主题下是"浅灰底 + 底边阴影"，和下面的 pageBg 只差一点点亮度，
        // 看起来就是一道脏边而不是层次。去掉之后顶栏与聊天区同底，靠字号/字重区分层级。
        ConversationTopBar(phone = phone, total = totalCount, onBack = onBack)

        // 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 进入对话的首屏骨架（本页唯一的加载动画，且只在"进入"时出现）。
            // 条件 = 一条消息都还没有 + 正在拉第一页；openConversation 每次都是先清空再拉，
            // 所以这个条件天然只在"进入/重新进入某个对话"时成立，翻页与轮询都不会命中。
            // 放在 LazyColumn **里面**而不是替换整棵子树：容器恒定挂载，数据到位时只是这个 item
            // 消失、消息 item 插入，没有"两棵子树互换"那一下闪。
            if (messages.isEmpty() && isLoading) {
                item(key = "entry-skeleton") { ConversationEntrySkeleton() }
            }
            // 「查看更早的消息」：只要还有更早的就常驻，**不会因为 isLoading 换成转圈**。
            // 2026-09-04 删掉了这里的 `if (isLoading) 圆圈 item else if (hasMore) 按钮`：
            // 两者互斥意味着点一下按钮就被圆圈顶掉、加载完又换回来 —— 每次翻页闪一次版式，
            // 而且这个圆圈在"进入对话"那一瞬间也会出现（messages 空 + loading），
            // 正是用户看到的那个圆圈之一。现在按钮原地不动，翻页期间视觉上什么都不发生。
            if (hasMore && messages.isNotEmpty()) {
                item(key = "load_more") {
                    TextButton(
                        onClick = {
                            listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { (it.key as? String)?.let { k -> k != "load_more" && k != "entry-skeleton" } == true }
                                ?.key?.let { pendingAnchorKey = it as? String }
                            onLoadMore()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("查看更早的消息", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                    }
                }
            }

            itemsIndexed(messages, key = { _, it -> "${it.id}-${it.timestamp}" }) { index, sms ->
                val isReceived = sms.direction == "received"
                val isBeingDeleted = sms.id in deletingMessageIds

                AnimatedVisibility(
                    visible = !isBeingDeleted,
                    exit = fadeOut(animationSpec = tween(UfiMotion.Duration.Smooth)),
                    enter = fadeIn(animationSpec = tween(UfiMotion.Duration.Base))
                ) {
                    Column {
                        dateHeaders[index]?.let { label ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    color = palette.divider.copy(alpha = 0.3f),
                                    shape = UfiCardDefaults.dialogShape
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        ChatBubbleRow(
                            sms = sms,
                            isReceived = isReceived,
                            phone = phone,
                            timeFormatter = timeFormatter,
                            highlighted = highlightId == sms.id,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }

        // 输入栏：直接坐在 pageBg 上，**不再包 Surface**。
        // 上一版取色仍然不对的根因就在这里：ChatInputBar 内部的卡片是 palette.cardBg，
        // 外面又套了 `Surface(color = palette.cardBg, tonalElevation = 4.dp)` —— 同色贴同色，
        // 卡片轮廓和描边全被吃掉，看起来还是一片平的底。控制台那边输入栏外面没有任何容器，
        // 卡片直接浮在 pageBg 上，才有"悬浮卡"的层次。这里对齐它。
        ChatInputBar(
            value = messageText,
            onValueChange = { messageText = it },
            onSend = {
                if (messageText.isNotBlank()) {
                    viewModel.tools.sendSms(phone, messageText)
                    messageText = ""
                    onSent()
                }
            }
        )
    }
    // 2026-09-04 删掉「加载遮罩」：原来这里是
    //   `if (messages.isEmpty() && isLoading) Box(fillMaxSize, pageBg 60%) { UfiLoadingIndicator(36dp) }`
    // 它是 Column 的兄弟节点、画在最后 ⇒ 盖在整页之上。**这就是用户说的"进入瞬间那个圆圈"**：
    // openConversation 先把 conversationMessages 清空并置 conversationLoading=true，
    // 所以每次点进对话（含通知深链接直接进对话）第一帧必然满足条件，一个 36dp 转圈盖住全屏；
    // 发完短信重拉对话也会再盖一次。首屏改由列表内的骨架 item 表达，遮罩没有存在意义。
}

/**
 * 进入对话时的首屏骨架：交替左右的气泡占位块。
 *
 * 为什么不用公共的 [UfiSkeletonList]：那支是"左圆图标 + 两行文字"的**列表行**版式，
 * 对话页真实内容是左右交替的气泡，形状差太多，数据到位时会有一次明显的形态跳变。
 * 这里用同一支公共构件 [UfiSkeletonBlock]（共用 shimmer）拼出气泡轮廓，
 * 高度/圆角贴近真实气泡，落位时只是"灰块变成文字"。
 *
 * 宽度刻意不等：真实对话就是长短交错，等宽会像一堵灰墙而不是一段聊天。
 */
@Composable
private fun ConversationEntrySkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CONVERSATION_SKELETON_WIDTHS.forEachIndexed { index, fraction ->
            // 偶数行当作"收到"（左），奇数行当作"发出"（右）
            val received = index % 2 == 0
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (received) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                UfiSkeletonBlock(
                    modifier = Modifier.fillMaxWidth(fraction),
                    height = 44.dp,
                    cornerRadius = 18.dp
                )
            }
        }
    }
}

/** 对话骨架的 6 行气泡宽度占比（长短交错，见 [ConversationEntrySkeleton]）。 */
private val CONVERSATION_SKELETON_WIDTHS = listOf(0.62f, 0.45f, 0.70f, 0.50f, 0.58f, 0.40f)

/**
 * 「首屏是否还没出过结果」闩锁：返回 true = 该画骨架；false = 已经落地过一次，永久不再画。
 *
 * 三个信号都用 lambda 传进来（而不是传 Boolean 值）：本函数只在首次组合时启动一个
 * `LaunchedEffect(Unit)`，lambda 里读的是 collectAsState 那个稳定 State 实例，
 * 所以协程里能一直拿到最新值，而不是被首帧的值冻住。
 *
 * 判据：拿到数据 / 报错 / 观察到一次 loading 由 true 落回 false —— 任一成立即视为"出过结果"。
 * 三条一起判是为了不依赖单一信号：轮询/缓存命中可能让 loading 那一下快到观察不到，
 * 那时 hasData 会兜住；空结果则由 loading 边沿兜住。
 */
@Composable
private fun rememberFirstLoadPending(
    loading: () -> Boolean,
    hasData: () -> Boolean,
    failed: () -> Boolean
): Boolean {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var sawLoading = false
        snapshotFlow { Triple(loading(), hasData(), failed()) }
            .first { (isLoading, ok, err) ->
                if (isLoading) sawLoading = true
                ok || err || (sawLoading && !isLoading)
            }
        settled = true
    }
    return !settled
}

// ═══════════════════════════════════════════════
// 对话顶栏
// ═══════════════════════════════════════════════

/**
 * 对话顶栏。
 *
 * 2026-08-29 重做配色：原来头像是 `accent@15%` 的淡色圆底 + accent 文字 —— 浅色主题下几乎看不见
 * 底，深色下又糊成一团；返回箭头连 tint 都没给，跟着 LocalContentColor 走，在自定义 palette 下
 * 经常偏色。现在头像用**实心 accent + onAccent 首字**（accent 面上一律用 onAccent，不写死白色），
 * 副标题降到 textSecondary 做层级。
 */
@Composable
private fun ConversationTopBar(phone: String, total: Int, onBack: () -> Unit) {
    val palette = LocalResolvedPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回列表",
                tint = palette.textPrimary,
                modifier = Modifier.size(26.dp)
            )
        }
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(palette.accent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = phone.filter { it.isDigit() }.takeLast(2).ifEmpty { "?" },
                style = UfiTextStyles.panelTitleStrong,
                color = palette.onAccent
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = phone,
                style = UfiTextStyles.screenTitleEmphasis,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "共 ${total.coerceAtLeast(0)} 条消息",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }
    }
}

// ═══════════════════════════════════════════════
// 单条气泡行（含长按操作）
// ═══════════════════════════════════════════════

/**
 * 单条消息气泡。
 *
 * 2026-08-29 重做：
 * - **收到方**气泡原来是 `divider@35%` —— divider 本来是给分割线用的极低对比色，拿来当气泡底
 *   在浅色主题下几乎和页面同色，看着像没有气泡。改成 `cardBg` + 1dp 细描边，浮在 pageBg 上，
 *   和全站卡片语言一致。
 * - **发出方**内容色从写死的 `Color.White` 改成 `palette.onAccent`（自定义主题下 accent 可能是
 *   浅色，白字会看不见）。
 * - **时间戳移到气泡外**：原来它占气泡内独立一行且靠右，"好"这种一个字的消息也被撑成一整行宽；
 *   移出去之后气泡完全贴合文字，且不用再在 accent 面上找一个能看清的弱化色。
 * - **长按菜单**换成项目公共组件 [UfiPopupMenu]（文件管理器长按菜单同一套），菜单中心对齐长按点
 *   出现、点外部关闭，并从只有「删除」扩展到复制 / 重发（仅发出方）/ 删除 —— 都不需要新接口：
 *   重发直接复用 sendSms。不要退回 M3 原生 DropdownMenu：它在部分机型不继承自定义 colorScheme
 *   会出现白底（这也是 UfiPopupMenu 存在的原因）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubbleRow(
    sms: SmsRecord,
    isReceived: Boolean,
    phone: String,
    timeFormatter: SimpleDateFormat,
    highlighted: Boolean,
    viewModel: MainViewModel
) {
    val palette = LocalResolvedPalette.current
    val context = LocalContext.current
    var showActions by remember { mutableStateOf(false) }
    // UfiPopupMenu 的定位需要锚点在窗口中的矩形；长按点用气泡几何中心
    // （combinedClickable 不暴露长按 offset，文件管理器 FileRowCard 也是这么取的）
    var bubbleBounds by remember { mutableStateOf(IntRect.Zero) }
    var longPressPoint by remember { mutableStateOf(IntOffset.Zero) }

    // 「查看原对话」定位到的那条：闪烁 2s（8 × 250ms = 4 次明暗）提示，
    // 比静态描边更容易在满屏气泡里被注意到。
    // 用描边而不是换底色 —— 底色是区分收/发方向的语义，动它会让人以为方向变了。
    val highlightAlpha = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            highlightAlpha.animateTo(
                targetValue = 1f,
                animationSpec = repeatable(
                    iterations = 8,
                    // 2026-09-04（P2b）：250 → Duration.Fluid（同为 250，零观感变化）。
                    animation = tween(
                        durationMillis = UfiMotion.Duration.Fluid,
                        easing = UfiMotion.Easing.Linear,
                    ),
                    repeatMode = RepeatMode.Reverse
                )
            )
        }
        // 偶数次 Reverse 本来就收在 0，这里再 snap 一次兜住"动画被取消在半亮位置"
        highlightAlpha.snapTo(0f)
    }

    val bubbleShape = RoundedCornerShape(
        topStart = if (isReceived) 6.dp else 18.dp,
        topEnd = if (isReceived) 18.dp else 6.dp,
        bottomStart = 18.dp,
        bottomEnd = 18.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isReceived) Arrangement.Start else Arrangement.End
    ) {
        if (isReceived) {
            SmsAvatarMini(phone, Modifier.padding(end = 8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 285.dp),
            horizontalAlignment = if (isReceived) Alignment.Start else Alignment.End
        ) {
            Box(
                modifier = Modifier.onGloballyPositioned { coords ->
                    val r = coords.boundsInWindow()
                    bubbleBounds = IntRect(
                        r.left.roundToInt(), r.top.roundToInt(),
                        r.right.roundToInt(), r.bottom.roundToInt()
                    )
                }
            ) {
                Surface(
                    shape = bubbleShape,
                    color = if (isReceived) palette.cardBg else palette.accent,
                    border = when {
                        // 高亮描边：收方气泡是 cardBg，用 accent 才看得见；发方气泡本身就是
                        // accent 底，只有 onAccent 描边能显出来
                        highlightAlpha.value > 0.01f -> BorderStroke(
                            2.dp,
                            (if (isReceived) palette.accent else palette.onAccent)
                                .copy(alpha = highlightAlpha.value)
                        )
                        isReceived -> BorderStroke(1.dp, palette.divider.copy(alpha = 0.45f))
                        else -> null
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = { },
                        onLongClick = {
                            longPressPoint = IntOffset(
                                (bubbleBounds.left + bubbleBounds.right) / 2,
                                (bubbleBounds.top + bubbleBounds.bottom) / 2
                            )
                            showActions = true
                        }
                    )
                ) {
                    Text(
                        text = sms.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isReceived) palette.textPrimary else palette.onAccent,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }

                UfiPopupMenu(
                    visible = showActions,
                    onDismiss = { showActions = false },
                    anchorBounds = bubbleBounds,
                    anchorPoint = longPressPoint,
                    options = buildList {
                        add(
                            UfiPopupOption(
                                id = "copy",
                                label = "复制内容",
                                icon = Icons.Default.ContentCopy,
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("短信", sms.content))
                                    showActions = false
                                }
                            )
                        )
                        if (!isReceived) {
                            add(
                                UfiPopupOption(
                                    id = "resend",
                                    label = "重新发送",
                                    icon = Icons.Default.Refresh,
                                    onClick = {
                                        viewModel.tools.sendSms(phone, sms.content)
                                        showActions = false
                                    }
                                )
                            )
                        }
                        add(UfiPopupOption.divider())
                        add(
                            UfiPopupOption(
                                id = "delete",
                                label = "删除",
                                icon = Icons.Default.DeleteOutline,
                                isDestructive = true,
                                onClick = {
                                    viewModel.tools.deleteSms(sms.id.toString())
                                    showActions = false
                                }
                            )
                        )
                    }
                )
            }

            // 时间戳 + 未读点：放在气泡外面，气泡就能贴合文字宽度
            Row(
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (!sms.read && isReceived) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(palette.accent))
                }
                Text(
                    text = timeFormatter.format(Date(sms.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary.copy(alpha = 0.75f)
                )
            }
        }

        if (!isReceived) {
            SmsAvatarMini("我", Modifier.padding(start = 8.dp))
        }
    }
}

// ═══════════════════════════════════════════════
// 迷你头像（对话气泡旁）
// ═══════════════════════════════════════════════

@Composable
private fun SmsAvatarMini(identifier: String, modifier: Modifier = Modifier) {
    val palette = LocalResolvedPalette.current
    val display = if (identifier == "我") "我" else identifier.takeLast(2)
    Box(
        modifier = modifier.size(32.dp).clip(CircleShape)
            .background(palette.accent.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Text(display, style = UfiTextStyles.captionStrong,
            color = palette.accent)
    }
}

// ═══════════════════════════════════════════════
// 输入栏
// ═══════════════════════════════════════════════

/**
 * 吸底输入栏 — 与「高级控制台」的 ConsoleInputBar 同款：卡片容器 + BasicTextField + 方形发送键。
 *
 * 2026-08-29 二次调整，补上第一版漏掉的三件事：
 *
 * **取色**：第一版只把卡片色从写死的 `divider@15%` 换成 `palette.cardBg`，但调用处外面还套着
 * `Surface(color = palette.cardBg, tonalElevation = 4.dp)` —— cardBg 卡片贴在 cardBg 底上，
 * 轮廓和描边全被吃掉，看着还是一片平的。这版把外层 Surface 去掉（见 SmsConversationView），
 * 卡片直接浮在 pageBg 上；描边随聚焦在 `divider@30%` ⇄ `accent@55%` 之间过渡，
 * 阴影同步 2dp ⇄ 6dp，深浅色两套主题都由 palette 推导，无硬编码色值。
 *
 * **上移**：`imePadding()` 加在 SmsConversationView 的根 Column 上（控制台同样做法），
 * 键盘弹出时整个面板上移；本组件只保留 `navigationBarsPadding()` 处理无键盘时的导航栏避让。
 *
 * **动画**：
 * - 聚焦：描边色 + 阴影高度用 `animateColorAsState`/`animateDpAsState` 过渡（220ms），
 *   给出"这个框激活了"的反馈，取代第一版毫无状态变化的静态框；
 * - 发送键：`canSend` 翻转时做 0.8→1 的弹性缩放（spring，带轻微回弹）+ 背景色过渡，
 *   输入第一个字符时按钮"弹出来"，清空时缩回去。
 */
@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val canSend = value.isNotBlank()
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 2026-08-31：卡壳与发送键改用公共 UfiInputBarCard / UfiSendButton
        //（阴影 6/2dp、禁用底色 0.35、贴底对齐都保持本页原值，观感不变）
        UfiInputBarCard(
            focused = isFocused,
            focusedElevation = 6.dp,
            restingElevation = 2.dp,
            verticalAlignment = Alignment.Bottom
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                // 最多 5 行后内部滚动：长短信不会把发送键顶出屏幕
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 120.dp)
                    .padding(vertical = 8.dp)
                    .onFocusChanged { isFocused = it.isFocused },
                maxLines = 5,
                cursorBrush = SolidColor(palette.accent),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                // 短信是多行正文，Enter 必须换行，所以这里不像控制台那样绑 ImeAction.Send
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = "输入短信\u2026",
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.textSecondary
                            )
                        }
                        innerTextField()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            UfiSendButton(
                onClick = onSend,
                enabled = canSend,
                disabledAlpha = 0.35f
            )
        }
    }
}

// ═══════════════════════════════════════════════
// 验证码通知面板 — 独立卡片风格，左侧 accent 竖条
// ═══════════════════════════════════════════════

@Composable
private fun VerificationCodePanel(
    codes: List<VerificationCode>,
    /** true = 本次进入页面还没出过结果（画骨架）。刻意不是 isLoading：刷新不能有动画。 */
    firstLoadPending: Boolean,
    enabled: Boolean,
    listState: LazyListState,
    onCopy: (String) -> Unit,
    onCardClick: (VerificationCode) -> Unit,
    onEnable: () -> Unit
) {
    val palette = LocalResolvedPalette.current

    if (!enabled) {
        // 功能未开启：引导卡片
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    Icons.Default.VerifiedUser,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = palette.accent.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(Spacing.Large))
                Text("验证码自动解析", style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
                Spacer(Modifier.height(Spacing.Medium))
                Text(
                    "开启后，新收到的验证码短信将自动识别并显示在这里，方便快速复制。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.XLarge))
                FilledTonalButton(onClick = onEnable) {
                    Text("开启功能")
                }
            }
        }
        return
    }

    // 首屏：还没出过结果且一条都没有 → 骨架屏。
    // 2026-09-04 改动：原来这里是「保持空白一瞬」（`if (isLoading) return`），当初那么写是为了
    // 躲开"转圈 → 空态 → 列表"连跳两次。骨架屏没有这个毛病 —— 它本身就预示了列表版式，
    // 数据到位是原地落位而不是换一种形态，所以可以放心画出来，比空白更能说明"在加载"。
    // 判据换成 firstLoadPending（闩锁）而不是 isLoading：否则"空列表 + 顶栏点刷新"会再放一次骨架，
    // 而刷新按需求必须完全无动画。
    if (codes.isEmpty()) {
        if (firstLoadPending) {
            UfiSkeletonList(rows = 5, modifier = Modifier.padding(Spacing.Large))
            return
        }
        UfiEmptyState(
            icon = Icons.Default.NotificationsNone,
            message = "暂无验证码通知",
            hint = "新收到的验证码短信将自动显示在这里"
        )
        return
    }

    // ── 懒加载 = 渐进渲染（不是真分页）────────────────────────────────────────────
    // 依据：core 的 `GET /api/sms/verification-codes`（RootSmsRoutes.kt）回的是整份解析缓存，
    // 没有 offset/limit；app 侧 `getVerificationCodes()` 同样无参。不动 core，故客户端分批渲染。
    var renderLimit by remember { mutableStateOf(SMS_RENDER_PAGE) }
    val renderedCodes = codes.take(renderLimit)
    LaunchedEffect(listState, codes.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= 0 &&
                    lastVisible >= renderLimit - SMS_RENDER_LOAD_AHEAD &&
                    renderLimit < codes.size
                ) {
                    renderLimit = (renderLimit + SMS_RENDER_PAGE).coerceAtMost(codes.size)
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.Large),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(renderedCodes, key = { it.msgId }) { vc ->
            VerificationCodeCard(
                code = vc,
                onCopy = { onCopy(vc.code) },
                onClick = { onCardClick(vc) }
            )
        }
        // 同上：尾部 shimmer 占位删掉，渐进渲染不需要"底部加载动画"。
    }
}

/** 渐进渲染批大小（短信页的会话行 / 验证码卡都是中等高度，30 条约三屏）。 */
private const val SMS_RENDER_PAGE = 30

/** 距尾部还剩几条就追加下一批。 */
private const val SMS_RENDER_LOAD_AHEAD = 5

/**
 * 验证码卡片。
 *
 * 2026-08-29 重做：这个界面的唯一主角是**验证码本身**，所以码放大到 headlineMedium 等宽，
 * 复制做成一眼可点的图标按钮；正文降级为一行"智能摘要"——
 * 原来直接铺 2 行 snippet，屏幕上全是「请勿泄露给他人，如非本人操作请忽略」这类模板话，
 * 反而把码挤小了。摘要规则见 [smsDigest]；`【】` 签名单独抽出来当来源标签（见 [smsSignature]），
 * 比裸号码好认。完整正文点开弹窗看。
 */
@Composable
private fun VerificationCodeCard(
    code: VerificationCode,
    onCopy: () -> Unit,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val timeText = remember(code.timestamp) {
        val age = System.currentTimeMillis() - code.timestamp
        when {
            age < 60_000 -> "刚刚"
            age < 3600_000 -> "${age / 60_000} 分钟前"
            age < 86400_000 -> "${age / 3600_000} 小时前"
            else -> SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(code.timestamp))
        }
    }
    // 全文优先（body），旧数据回退 snippet
    val rawText = remember(code.body, code.snippet) { code.body.ifBlank { code.snippet } }
    val sender = remember(rawText, code.source) { smsSignature(rawText) ?: code.source }
    val digest = remember(rawText) { smsDigest(rawText) }

    val cardShape = UfiCardDefaults.mediumShape
    Surface(
        onClick = onClick,
        shape = cardShape,
        color = palette.cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .ufiCardShadow(elevation = 3.dp, shape = cardShape)
            .border(1.dp, palette.divider.copy(alpha = 0.4f), cardShape)
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.Large, top = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧 accent 竖条：与卡片等高，标记"这是一条验证码"
            Box(
                Modifier
                    .width(4.dp)
                    .height(52.dp)
                    .background(palette.accent, UfiCardDefaults.pillShape)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = code.code,
                    style = UfiTextStyles.monoCode,
                    color = palette.textPrimary,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$sender · $timeText",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (digest.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = digest,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // 复制：44dp 圆形 accent 淡底图标键。列表里最高频的动作，给足点击面积
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = 0.12f))
                    .clickable(onClick = onCopy),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制验证码",
                    tint = palette.accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 短信签名：取首尾的 `【…】`（运营商/服务商名），没有返回 null。 */
private fun smsSignature(text: String): String? {
    val t = text.trim()
    return Regex("""^【(.{1,20}?)】""").find(t)?.groupValues?.get(1)
        ?: Regex("""【(.{1,20}?)】\s*$""").find(t)?.groupValues?.get(1)
}

/**
 * 一行摘要：只做**去签名 + 压空白**，不裁字。
 *
 * 2026-08-29 改：原实现先"切到第一个句末标点"再"30 字封顶"，两条规则都跟内容走 ——
 * 有的短信第一个句号在第 6 个字（"验证码123456。"），有的整条没有句号直接吃满 30 字，
 * 于是列表里每张卡截断长度都不一样，看着像 bug。现在交给 Compose：
 * 文本原样给出去，`maxLines = 1` + `TextOverflow.Ellipsis` 让它按**实际可用宽度**
 * 截一行加省略号，短的就完整显示 —— 所有卡片的截断位置天然对齐。
 *
 * 仍然去掉 `【】` 签名：它已经作为来源标签单独显示在上一行，重复出现纯占宽度。
 */
private fun smsDigest(text: String): String =
    text.trim()
        .replace(Regex("""^【.{1,20}?】"""), "")
        .replace(Regex("""【.{1,20}?】\s*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

/** 验证码详情弹窗里的一行「标签 — 值」。值靠右并可换行，长号码不会被挤掉。 */
@Composable
private fun CodeDetailRow(label: String, value: String) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

// ═══════════════════════════════════════════════
// 新建短信 — 弹窗（2026-08-29 由整屏写信页改造）
// ═══════════════════════════════════════════════

/**
 * 新建短信弹窗。
 *
 * 2026-08-29 从整屏 `SmsComposerScreen` 改成弹窗：写一条短信只有"号码 + 正文"两个字段，
 * 整页会顶掉列表上下文（发完还得再返回），而且为了撑满一屏塞了顶栏、模板区、字数行等
 * 一堆自绘部件。弹窗形态既保留列表可见，也让所有部件直接落到公共弹窗组件上。
 *
 * 走 [UfiScrollableDialog] 而不是 UfiCustomDialog：正文框最多 6 行 + 联想列表最多 4 条，
 * 小屏（尤其键盘弹起）时总高会超出弹窗预算，需要内容区可滚动、动作区固定在底部。
 */
@Composable
private fun SmsComposerDialog(
    contacts: List<SmsContact>,
    onClose: () -> Unit,
    onSend: (phone: String, message: String) -> Unit
) {
    val palette = LocalResolvedPalette.current
    var phone by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val charCount = message.length
    val canSend = phone.isNotBlank() && message.isNotBlank()

    // ── 收件人智能补全 ──
    // 用输入的数字去匹配短信列表里出现过的号码；候选走公共 UfiPopupMenu（项目唯一菜单实现，
    // M3 DropdownMenu 在部分机型不继承自定义 colorScheme 会出白底，明令禁用）。
    var fieldBounds by remember { mutableStateOf(IntRect.Zero) }
    // 手动关掉候选后不再自动弹回来，直到输入再变化
    var suggestDismissed by remember { mutableStateOf(false) }
    val suggestions = remember(phone, contacts) {
        if (phone.isBlank()) emptyList()
        // 号码里可能带 +86 / 空格，比较时统一只看数字
        else {
            val q = phone.filter { it.isDigit() }
            if (q.isEmpty()) emptyList()
            else contacts.filter { it.phoneNumber.filter { c -> c.isDigit() }.contains(q) && it.phoneNumber != phone }
                .take(5)
        }
    }
    val showSuggestions = suggestions.isNotEmpty() && !suggestDismissed

    UfiScrollableDialog(
        visible = true,
        onDismiss = onClose,
        title = "新建短信",
        icon = rememberVectorPainter(Icons.Filled.ChatBubbleOutline),
        showCloseButton = false,
        actions = {
            // 发送键跟 canSend 联动：号码或正文为空时置灰，比整页时代"点了没反应"清楚
            UfiDialogActions(
                onDismiss = onClose,
                onConfirm = { if (canSend) onSend(phone, message) },
                confirmText = "发送",
                dismissText = "取消",
                enabled = canSend
            )
        }
    ) {
        UfiDialogBody {
            // Box 既是锚点采集容器，也是 Popup 的父容器 —— UfiPopupMenu 的 offset 是相对
            // 父容器窗口左上角算的，两者必须是同一个 Box，否则菜单会整体平移出屏幕。
            Box(
                modifier = Modifier.onGloballyPositioned { coords ->
                    val r = coords.boundsInWindow()
                    fieldBounds = IntRect(
                        r.left.roundToInt(), r.top.roundToInt(),
                        r.right.roundToInt(), r.bottom.roundToInt()
                    )
                }
            ) {
                UfiDialogTextField(
                    label = "收件人",
                    value = phone,
                    onValueChange = { phone = it; suggestDismissed = false },
                    placeholder = "手机号码"
                )

                UfiPopupMenu(
                    visible = showSuggestions,
                    onDismiss = { suggestDismissed = true },
                    anchorBounds = fieldBounds,
                    // 联想菜单不能抢焦点：否则输入框失焦、键盘收起，没法边打字边看候选
                    focusable = false,
                    options = suggestions.map { c ->
                        UfiPopupOption(
                            id = c.phoneNumber,
                            label = "${c.phoneNumber}  ·  ${c.total} 条",
                            icon = Icons.Default.Person,
                            onClick = {
                                phone = c.phoneNumber
                                suggestDismissed = true
                            }
                        )
                    }
                )
            }

            UfiDialogField(label = "短信内容") {
                Column {
                    UfiTextField(
                        value = message,
                        onValueChange = { message = it },
                        // label 传空：标签已由外层 UfiDialogField 提供（同 UfiDialogTextField 的做法）
                        label = "",
                        placeholder = "输入短信内容…",
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6
                    )
                    Text(
                        text = "$charCount/160",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (charCount > 160) palette.error else palette.textSecondary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.Small)
                    )
                }
            }
        }
    }
}
