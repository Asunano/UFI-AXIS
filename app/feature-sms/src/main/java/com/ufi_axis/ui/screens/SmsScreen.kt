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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.ufi_axis.viewmodel.state.deviceUnsupportedNote
import com.ufi_axis_core.contract.Capability
import com.ufi_axis.ui.navigation.LocalPendingSmsPhone
import com.ufi_axis.ui.navigation.Routes
// 页内「列表 ⇄ 对话」转场与导航层二级界面同源，复用以下两个来源
// （时长换算与视差分母），避免同一应用内两类「二级界面」使用不同参数。
import com.ufi_axis.ui.navigation.SHARED_AXIS_X_PARALLAX
import com.ufi_axis.ui.navigation.ufiSharedAxisDurationMs
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.navigation.ufiNavigateOnce

// ════════════════════════════════════════════════════
// 短信主入口 — 标准手机短信界面风格
// ════════════════════════════════════════════════════

/** 会话列表静默自动刷新间隔（毫秒）。只在「消息」页签可见且没打开对话时跑。 */
private const val CONTACTS_AUTO_REFRESH_MS = 10_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val palette = LocalResolvedPalette.current
    val toolsState by viewModel.toolsState.collectAsState()

    // 设备能力集：core 仅在 `POST /api/sms/send` 上设门禁
    // （`RootSmsRoutes.kt:103`，判据为 profile.smsSpec()，短信不经由 SettingKey）。
    // 该接口是唯一「向外发送短信」的入口，因此本页仅置灰**发信**入口：
    // 新建短信 FAB + 对话中的输入栏 + 长按菜单的「重新发送」。
    //
    // 信箱的读取 / 删除 / 标已读 / 验证码 / 黑名单 / 转发规则**一律不置灰** —— 它们不属于该域，
    // 也不受门禁约束。「本设备不能发送短信」不等于「不能查看已收到的短信」。
    //
    // 能力集未拉取 / 拉取失败时 supports() 恒为 true，此时保持可发送状态。
    val capabilities by viewModel.network.capabilityState.collectAsState()
    val smsSupported = capabilities.supports(Capability.SMS)
    // 能力集自带「本进程只成功拉取一次」的闸门，重复调用不会每次进入页面都发请求。
    LaunchedEffect(Unit) { viewModel.network.loadDeviceCapabilities() }

    // ── lifecycle 门控 ──
    // 页面不可见时不发起 HTTP 请求；本页不是底部 Tab 页，无需 pageForeground。
    var lifecycleResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleResumed = false
        }
    }

    // 一次性动作（如「全部标为已读」）用它起协程等结果再弹 Toast，不进 ViewModel 状态。
    val scope = rememberCoroutineScope()
    var isComposing by remember { mutableStateOf(false) }
    var showOptInDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    // 点击验证码卡片后要展示详情的那一条（null = 不显示弹窗）。
    // 点击卡片不跳转到「消息」页签并打开对应号码的对话：该操作会使当前浏览位置丢失
    //（返回后需重新点回「通知」）。因此改为原地弹窗，跳转降级为弹窗内的一个按钮。
    var codeDetail by remember { mutableStateOf<VerificationCode?>(null) }
    // 会话行长按「加入黑名单」的二次确认对象（null = 不显示）。
    // 提到屏幕层持有而不是放在行内：确认结果需要弹 Toast，而 UfiToastHost 挂在该层；
    // 若放在行内，列表刷新（10s 静默轮询）重组时该行连同弹窗会被整体替换。
    var pendingBlacklistPhone by remember { mutableStateOf<String?>(null) }
    var pendingDeleteConversationPhone by remember { mutableStateOf<String?>(null) }
    // 「查看原对话」跳转前暂存的那条：对话关闭后原样恢复，使用户回到跳转前的状态。
    var reopenCodeAfterConversation by remember { mutableStateOf<VerificationCode?>(null) }
    // 两个列表的滚动位置提升到屏幕级持有。对话页是根 AnimatedContent 的另一层，
    // 切换时列表层整体卸载，其中的 remember LazyListState 会同时丢失，
    // 导致「滑到中间进入对话、返回后回到顶部」。提升到该层可跨转场保留。
    val contactsListState = rememberLazyListState()
    val codesListState = rememberLazyListState()
    val context = LocalContext.current

    // ── 首屏骨架的判据：「本次进入页面后，该数据尚未产生任何结果」──────────────
    // 不能直接使用 isLoading：
    //  1) 进入页面第一帧 isLoading 仍为 false（loadSmsContacts 由 LaunchedEffect 触发），
    //     直接使用会先闪现一帧「暂无短信」空态、再切换到骨架、再切换到列表；
    //  2) 手动刷新 / 重新拉取都会再次将 isLoading 置为 true，直接使用会使刷新也显示骨架，
    //     而刷新必须无动画。
    // 也不能检测 isLoading 的下降沿：见 rememberFirstLoadPending 的注释。
    // 因此统一读取 ViewModel 提供的「已完成一次加载尝试」标记，成功/失败/空结果均可结束骨架。
    val contactsFirstLoadPending = rememberFirstLoadPending { toolsState.smsContactsLoaded }
    val codesFirstLoadPending = rememberFirstLoadPending { toolsState.verificationCodesLoaded }

    // 未读数优先取 /count 的全局值，仅在未读到该值时回落为联系人求和（结果偏小，
    // 因为联系人列表只覆盖有会话记录的号码）。口径说明见 ToolsState.smsCount。
    // 该口径必须只有一份实现，不得在多处各算一版。
    val unreadTotal = toolsState.smsCount?.unread ?: toolsState.smsContacts.sumOf { it.unread }

    // 发送短信后需将会话列表滚动到顶部。
    // 列表 `items(contacts, key = { it.phoneNumber })` 带 key，而后端按最新消息时间重排，
    // 刚更新的会话会插入 index 0；带 key 的 LazyColumn 会将视口锚定在**原来的首个 key** 上，
    // 因此新置顶的条目被挤到视口上方，表现为「发送后列表无变化，需手动上滑才能看到」。
    // 使用待办标记并在列表真正刷新后滚动：sendSms 为异步操作，立即滚动只会作用于旧数据。
    var pendingScrollContactsTop by remember { mutableStateOf(false) }
    LaunchedEffect(toolsState.smsContacts, toolsState.conversationPhone) {
        // 仅在列表层可见时滚动：对话内发送消息同样会置该标记，此时列表尚未组合，
        // 滚动请求会作用于没有布局信息的 LazyListState 而失效，应等返回列表后再滚动。
        if (pendingScrollContactsTop && toolsState.conversationPhone.isEmpty() &&
            toolsState.smsContacts.isNotEmpty()) {
            pendingScrollContactsTop = false
            contactsListState.animateScrollToItem(0)
        }
    }

    // 会话列表自动刷新：设备收到新短信或短信从其它客户端发出时，不会主动推送给 app，
    // 不轮询则列表需等待用户下拉 / 重新进入页面才更新。刷新为静默模式，不显示下拉指示器。
    // 仅在「消息」页签且未打开对话时执行：对话层有独立刷新路径，页面离开时 LaunchedEffect 自动取消。
    LaunchedEffect(toolsState.smsTab, toolsState.conversationPhone, lifecycleResumed) {
        if (!lifecycleResumed) return@LaunchedEffect
        if (toolsState.smsTab != 0 || toolsState.conversationPhone.isNotEmpty()) return@LaunchedEffect
        while (true) {
            delay(CONTACTS_AUTO_REFRESH_MS)
            viewModel.tools.loadSmsContacts(silent = true)
        }
    }

    // 验证码（通知页签）实时刷新：
    // 自动复制验证码由 `:ufi_notify` 进程在后台触发（见 NotificationCenter），
    // 不依赖本页面的 UI 轮询。此处轮询仅用于「通知」页签列表刷新与未读角标计算。
    // 仅在「通知」页签且未打开对话时执行，复用联系人轮询的间隔与生命周期。
    LaunchedEffect(toolsState.smsTab, toolsState.conversationPhone, lifecycleResumed) {
        if (!lifecycleResumed) return@LaunchedEffect
        if (toolsState.smsTab != 1 || toolsState.conversationPhone.isNotEmpty()) return@LaunchedEffect
        while (true) {
            delay(CONTACTS_AUTO_REFRESH_MS)
            viewModel.tools.loadVerificationCodes()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.tools.loadSmsContacts()
        viewModel.tools.refreshDeviceConfig()
        // 设备短信条数 / 未读数（列表首行的信息行使用）。
        // 条数与「全部标为已读」位于消息 Tab，拉取也应在此发起；
        // 若留在设置页，不进入设置页时这两个值始终无法取得。
        viewModel.tools.loadSmsCount()
        // 进入页面时静默拉取一次验证码：否则停留在「消息」页签时 unread 恒为 0，
        // 「通知」角标需进入该页签才能计算（进入后立即清零），导致角标始终不显示。
        viewModel.tools.loadVerificationCodes()
    }
    rememberResumeRefresh {
        viewModel.tools.loadSmsContacts()
        viewModel.tools.loadSmsCount()
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
    // 注意此处**不切换页签** —— 对话是覆盖整屏的一层，底层停留在哪个页签不影响显示，
    // 关闭对话时会原样回到「通知」页签（若将 smsTab 置 0，返回后会落到消息列表）。
    LaunchedEffect(toolsState.navigateToPhone) {
        val phone = toolsState.navigateToPhone
        if (phone.isNotEmpty()) {
            // 带上目标消息 id：对话打开后直接滚到并高亮那一条（0 = 不定位）
            viewModel.tools.openConversation(phone, toolsState.navigateToMsgId)
            viewModel.tools.clearNavigateToPhone()
        }
    }

    // 对话关闭后，恢复跳转前暂存的验证码弹窗（配合不切页签与屏幕级滚动位置，
    // 「查看原对话后返回」可以完整回到跳转前的状态）。
    LaunchedEffect(toolsState.conversationPhone) {
        if (toolsState.conversationPhone.isEmpty()) {
            reopenCodeAfterConversation?.let {
                codeDetail = it
                reopenCodeAfterConversation = null
            }
        }
    }

    // 对话态：系统返回键优先关闭对话回到列表，而不是直接退出短信页。
    // 新建短信为弹窗形式，返回键由 UfiCustomDialog 消费；
    // 此处保留 `!isComposing` 仅作为显式优先级说明。
    BackHandler(enabled = !isComposing && toolsState.conversationPhone.isNotEmpty()) {
        viewModel.tools.closeConversation()
    }

    UfiScreenScaffold(
        title = "短信",
        navController = navController,
        showBack = true,
        // 底部输入法 inset 由外壳统一处理（navigationBars ∪ ime）。
        // 对话页输入栏贴底，键盘弹出时整块面板需上移；若在 SmsConversationView 的根
        // Column 上再挂 `imePadding()`，与外壳内容 Box 已应用的 navigationBars 叠加，
        // 输入栏会上移约一个导航栏高度。
        imeAwareBottomInset = true,
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
            // 顶栏不提供「刷新」按钮：会话列表与验证码列表各有 10s 静默轮询 + 重进页面刷新
            //（rememberResumeRefresh），手动刷新属于冗余；对话层仅支持分页加载，
            // 顶栏刷新容易被理解为刷新对话。
            // 齿轮入口指向独立的「短信设置」页面，不使用弹窗：
            // 拦截规则与拦截记录均为带列表的二级页，弹窗内无法容纳，
            // 且返回栈语义不清晰，因此不提供两个入口。
            IconButton(onClick = { navController.ufiNavigateOnce(Routes.DETAIL_SMS_SETTINGS) }) {
                Icon(Icons.Default.Settings, "设置", tint = palette.textSecondary)
            }
        }
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 「列表层 ⇄ 对话层」的转场在此层执行。
            // 两层均为铺满整屏的兄弟节点，列表层（含 Tab 栏）作为整体平移，
            // 转场期间没有节点增删与高度变化，因此不会出现布局跳动。
            // 若将 Tab 栏放在 `if (conversationPhone.isEmpty())` 内增删，转场第 1 帧
            // Tab 栏节点即被移除，内容区高度突变，动画中途整块内容跳动。
            // sizeTransform = null 同理：不让 AnimatedContent 补间容器尺寸。
            // 转场口径与**导航层二级界面**（TransitionType.DETAIL 的共享轴）完全同源。
            //
            // 必须遵守共享轴的约定：
            //   1. 共享轴两页**同时**平移，进出必须使用同一条曲线 —— 一边加速一边减速会使
            //      上下层的视差关系在中途偏移，表现为两层错位；`Navigation.kt` 的
            //      `sharedAxisSpec` 注释记录了这一约束；
            //   2. 视差取 1/6（与导航层一致），1/4 会使同一应用内两类「二级界面」层次不一致；
            //   3. 共享轴**不引入 alpha 淡入淡出**（见 Navigation.kt 顶部的口径）。
            //      整屏平移叠加淡入会使新页显得发虚，而不是实心覆盖。
            // 因此时长与视差均取自导航层来源，曲线使用同一条 `Easing.Standard`。
            val smsAxisSpec = tween<IntOffset>(
                ufiSharedAxisDurationMs(UfiMotion.Duration.Sweeping),
                easing = UfiMotion.Easing.Standard
            )
            AnimatedContent(
                targetState = toolsState.conversationPhone.ifEmpty { null },
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val enteringConversation = targetState != null
                    val transform = if (enteringConversation) {
                        // push：新页整幅宽度滑入压在上面，旧页走 1/6 屏做视差（与二级页一致）
                        slideInHorizontally(smsAxisSpec) { it } togetherWith
                            slideOutHorizontally(smsAxisSpec) { -it / SHARED_AXIS_X_PARALLAX }
                    } else {
                        // pop：下层（列表）自左侧视差滑回，上层（对话）整幅右滑退出
                        slideInHorizontally(smsAxisSpec) { -it / SHARED_AXIS_X_PARALLAX } togetherWith
                            slideOutHorizontally(smsAxisSpec) { it }
                    }
                    // 进入对话时新页在上（zIndex 1），返回时旧页在上覆盖新页 —— 符合 push/pop
                    // 的遮挡关系；若不设置，两页同层，表现为交叉淡入淡出。
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
                        // 错误不在此处渲染 —— ToolsState 的 errorMessage 由 Activity 级
                        // 全局错误浮层统一展示（MainActivity 读取 viewModel.globalError）。
                        // 若保留在此处，会与全局卡片位置重叠（两者均为屏幕顶部 48dp 的 Popup）。

                        // 顶部滑块 Tab 栏，与「自动化」页一致，统一全站 Tab 视觉。
                        // 角标使用公共组件的 badges 槽位；计数为**未读水位**（verificationCodesUnread），
                        // 不是缓存总条数 —— 后者在查看后不会变化，角标会长期存在。
                        UfiScrollableTabRow(
                            selectedTabIndex = toolsState.smsTab,
                            onTabSelected = { viewModel.tools.switchSmsTab(it) },
                            tabs = listOf("消息", "通知"),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            badges = listOf(null, toolsState.verificationCodesUnread)
                        )

                        // 置灰原因（仅在不支持时显示，且仅在「消息」页签 —— 「通知」页签
                        // 全部为本地解析的验证码，与能否发送短信无关）。
                        // 仅说明「设备不支持」：该设备不具备发信能力，不存在可开启的开关。
                        if (!smsSupported && toolsState.smsTab == 0) {
                            // UfiNoticeCard 内部已带 CardHorizontalMargin(16dp)；
                            // 此处再加 16dp 会使其比列表窄一圈。
                            UfiNoticeCard(
                                message = deviceUnsupportedNote("发送短信"),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        // 内容区。使用 BoxWithConstraints 以便 FAB 边距按**实际可用区域**
                        // 取百分比（见下方 fabEndPad / fabBottomPad）；固定 dp 在小屏上会贴边、
                        // 在平板上又离内容过远。
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            if (toolsState.smsTab == 0) {
                                // 不使用下拉刷新：`PullToRefreshBox(indicator = {})` 属于
                                // 「保留手势、不绘制指示器」的折中方案，该手势既无视觉反馈，
                                // 又会与列表滚动争用同一通道，容易误触且触发后无任何提示。
                                // 顶栏同样不提供刷新按钮；数据更新由 10s 静默轮询 + 重进页面负责。
                                Box(Modifier.fillMaxSize()) {
                                    when {
                                        toolsState.smsContacts.isNotEmpty() -> {
                                            SmsConversationList(
                                                contacts = toolsState.smsContacts,
                                                listState = contactsListState,
                                                viewModel = viewModel,
                                                // 设备短信条数：读不到（null）就整条不画。
                                                // 不给「—」占位 —— 一条常驻的空信息行正是要避免的东西。
                                                deviceSmsTotal = toolsState.smsCount?.total,
                                                unreadTotal = unreadTotal,
                                                onMarkAllRead = {
                                                    scope.launch {
                                                        // markAllSmsRead 成功后已在内部重新拉取联系人与 /count
                                                        //（ToolsModule.kt:850-861），此处无需再次拉取。
                                                        val ok = viewModel.tools.markAllSmsRead()
                                                        toastMessage = if (ok) ToastMessage("已全部标为已读", ToastType.SUCCESS)
                                                        else ToastMessage("操作失败，请重试", ToastType.ERROR)
                                                    }
                                                },
                                                onMarkRead = { phone ->
                                                    scope.launch {
                                                        val ok = viewModel.tools.markConversationRead(phone)
                                                        toastMessage = if (ok) ToastMessage("已标记为已读", ToastType.SUCCESS)
                                                        else ToastMessage("操作失败，请重试", ToastType.ERROR)
                                                    }
                                                },
                                                onMarkUnread = { phone ->
                                                    // 把该会话最新一条**收到的**短信标未读 —— 会话行的未读角标
                                                    // 数的就是收到方未读数，标发出方不会让角标亮起来。
                                                    val latest = toolsState.smsList
                                                        .filter { it.phoneNumber == phone && it.direction == "received" }
                                                        .maxByOrNull { it.timestamp }
                                                    if (latest != null) {
                                                        viewModel.tools.markSmsUnread(latest.id)
                                                    } else {
                                                        toastMessage = ToastMessage("没有可标记的短信", ToastType.INFO)
                                                    }
                                                },
                                                onRequestDeleteConversation = { pendingDeleteConversationPhone = it },
                                                onRequestBlacklist = { pendingBlacklistPhone = it }
                                            )
                                        }
                                        // 首屏骨架：从未加载成功且列表为空。
                                        // 判据为闩锁而不是 isLoading —— 后者会使每次刷新都重放骨架；
                                        // 已有数据时刷新不会进入本分支（上一分支先命中），
                                        // 空列表时刷新也不应播放动画。
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
                                            // fillMaxSize 是必须的：空态版式（96dp 圆底图标 + 两级文字）
                                            // 靠调用方给高度才能在可视区里居中，不给就只按内容高度贴在顶部。
                                            UfiEmptyState(
                                                icon = Icons.Default.ChatBubbleOutline,
                                                message = "暂无短信",
                                                hint = "点击右下角按钮发送新短信",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 通知 Tab：验证码面板（同样不提供下拉手势与顶栏刷新按钮，
                                // 数据由下方 10s 静默轮询更新）
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
                            // 边距按可用区域百分比计算：固定 dp 在小屏上会贴边压住最后一条会话行的
                            // 未读角标并侵入系统手势区，在平板上又离内容过远。
                            // coerceIn 限制极端长宽比下的取值，避免贴边或过度居中。
                            if (toolsState.smsTab == 0) {
                                val fabEndPad = (maxWidth * 0.07f).coerceIn(20.dp, 36.dp)
                                val fabBottomPad = (maxHeight * 0.07f).coerceIn(28.dp, 64.dp)
                                UfiFloatingActionButton(
                                    icon = Icons.Default.ChatBubbleOutline,
                                    onClick = { isComposing = true },
                                    contentDescription = "新建短信",
                                    enabled = smsSupported,
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
        UfiCustomDialog(
            visible = showOptInDialog,
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
                dismissText = "取消",
                topSpacing = Spacing.Large
            )
        }

    // ── 新建短信弹窗（原整屏写信页）──
    if (isComposing) {
        SmsComposerDialog(
            contacts = toolsState.smsContacts,
            onClose = { isComposing = false },
            onSend = { phone, msg ->
                viewModel.tools.sendSms(phone.trim(), msg)
                isComposing = false
                // 发送后切回「消息」页签并将列表滚到顶部：新会话或刚更新的会话位于 index 0，
                // 不滚动会停留在视口上方，造成未发送成功的误解（见 pendingScrollContactsTop 注释）。
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
        // 打开时不回查原文：core 自 DB v8 起将原短信全文一并存入验证码缓存
        // （snippet 仍为 80 字预览），列表取得的记录已包含全文，弹窗直接渲染，
        // 不会出现「先显示 snippet、请求返回后替换为全文」的闪烁。
        // body 为空 = 旧数据 / core 版本较旧 → 回退 snippet。
        val closeDialog = { codeDetail = null }
        // 使用 UfiScrollableDialog：短信正文需**完整显示**；若为正文单独套
        // `heightIn(220dp) + verticalScroll`，长短信会在矮框内内部滚动，表现为被截断。
        // 正文按内容自然铺开，超出弹窗预算时由弹窗整体滚动；
        // 动作区通过 actions 槽位固定在底部（放入 content 会导致底部留白）。
        UfiScrollableDialog(
            visible = true,
            onDismiss = closeDialog,
            title = "验证码详情",
            icon = rememberVectorPainter(Icons.Filled.VerifiedUser),
            // 不显示右上角关闭按钮：底部已有「确认」，两个等价的关闭入口属于冗余。
            showCloseButton = false,
            actions = {
                // 左「删除」右「确认」：使用公共 UfiDialogActions 的 dismissDestructive 槽位，
                // 间距 / 底边距 / 按钮样式均继承标准弹窗，不在此处自行组合 Row 与按钮。
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
                // 验证码本体：等宽大字 + 一键复制。置于顶部，因为查看验证码是打开该弹窗的主要目的。
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
                        // 复制按钮使用公共 UfiButton（Primary + Small），不使用 M3 原生 FilledTonalButton：
                        // 后者取色走 colorScheme 而非 palette，深浅主题下与卡片色系不一致。
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
                // 跳转按钮使用公共 UfiButton（variant = Secondary），不使用 M3 原生 TextButton：
                // 后者非公共组件，且点击区域仅限文字范围。
                //
                // 关闭动作交由 shell 排时序：离场 backdrop 需播放完毕后才卸载窗口，见 LocalUfiDialogClose。
                // 该 local 必须在弹窗自身的 lambda 内部读取，在外部读取得到的是「直接执行」的默认实现。
                val closeDetail = LocalUfiDialogClose.current
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    text = "查看原对话",
                    onClick = {
                        closeDetail {
                            // 暂存当前记录：对话关闭后弹窗自动恢复，不切换页签且保留滚动位置。
                            // 同时传递 msgId —— 对话打开后直接滚动到并高亮该验证码短信。
                            reopenCodeAfterConversation = vc
                            codeDetail = null
                            viewModel.tools.openVerificationCodeSource(vc.source, vc.msgId)
                        }
                    }
                )
            }
        }
    }

    // ── 会话行长按「加入黑名单」的二次确认 ──
    // 文案必须完整说明后果：该操作会「静默丢弃消息」，确认前需说明拦截范围，
    // 以及历史短信不受影响（core 不做追溯）。
    val dialogPendingBlacklistPhone = rememberLastNonNull(pendingBlacklistPhone)
    if (dialogPendingBlacklistPhone != null) {
        val phone = dialogPendingBlacklistPhone
        UfiConfirmDialog(
            visible = pendingBlacklistPhone != null,
            title = "加入黑名单",
            text = "该号码之后的短信不再提醒、不再转发，也不会出现在列表里，" +
                "可在「短信设置 → 已拦截」中查看。已收到的历史短信不受影响。",
            confirmText = "加入黑名单",
            destructive = true,
            onDismiss = { pendingBlacklistPhone = null },
            onConfirm = {
                pendingBlacklistPhone = null
                scope.launch {
                    // 规则形态固定为 scope=sender / match=equals，由 ViewModel 负责；
                    // app 层仅处理「用户已确认」这一动作。
                    val ok = viewModel.tools.addSmsSenderBlacklist(phone)
                    toastMessage = if (ok) ToastMessage("已把 $phone 加入黑名单", ToastType.SUCCESS)
                    else ToastMessage("加入黑名单失败，请重试", ToastType.ERROR)
                }
            }
        )
    }

    // 「短信设置」（验证码开关 / 清理间隔 / 设备短信条数 / 全部标为已读）位于独立页面
    // SmsSettingsScreen，由齿轮入口导航进入，不使用弹窗。
    // 同一设置不得保留两个入口：两者会出现状态不一致。

    // ── 会话行长按「删除整段会话」的二次确认 ──
    val dialogPendingDeleteConversationPhone = rememberLastNonNull(pendingDeleteConversationPhone)
    if (dialogPendingDeleteConversationPhone != null) {
        val phone = dialogPendingDeleteConversationPhone
        UfiConfirmDialog(
            visible = pendingDeleteConversationPhone != null,
            title = "删除整段会话",
            text = "将删除与 $phone 的全部短信记录，此操作不可恢复。",
            confirmText = "删除全部",
            destructive = true,
            onDismiss = { pendingDeleteConversationPhone = null },
            onConfirm = {
                pendingDeleteConversationPhone = null
                viewModel.tools.deleteConversation(phone)
            }
        )
    }
}

// ═══════════════════════════════════════════════
// 会话列表（纯列表；列表态 ⇄ 对话态的转场已上提到 SmsScreen 顶层）
// ═══════════════════════════════════════════════

/**
 * 会话列表。
 *
 * 该组件只负责列表。切换由 SmsScreen 根部的 AnimatedContent 统一处理：对话页是覆盖整屏的
 * 另一层，列表层（含 Tab 栏）作为整体平移，中途没有节点增删、高度恒定。
 * 若将 Tab 栏放在 `if (conversationPhone.isEmpty())` 内增删，转场第 1 帧 Tab 栏即被移除，
 * 内容区高度突变，动画会表现为跳动。
 *
 * @param deviceSmsTotal 设备短信总条数。**为 null 时整条信息行不渲染** —— 读不到时不占位，
 *        也不在列表上方保留一条常驻空行。
 * @param unreadTotal 未读总数（口径见调用点）。> 0 时信息行才显示「全部标为已读」动作。
 */
@Composable
private fun SmsConversationList(
    contacts: List<SmsContact>,
    listState: LazyListState,
    viewModel: MainViewModel,
    deviceSmsTotal: Int?,
    unreadTotal: Int,
    /** 信息行的「全部标为已读」：结果由屏幕层弹 Toast。 */
    onMarkAllRead: () -> Unit,
    /** 长按菜单「标记已读」：整段会话标已读，结果由屏幕层弹 Toast。 */
    onMarkRead: (String) -> Unit,
    /** 长按菜单「标记未读」：将该会话最新一条收到的短信标为未读。 */
    onMarkUnread: (String) -> Unit,
    /** 长按菜单「删除整段会话」：仅上报意图，二次确认与删除在屏幕层执行。 */
    onRequestDeleteConversation: (String) -> Unit,
    /** 长按菜单「加入黑名单」：只上报意图，二次确认与写入在屏幕层做。 */
    onRequestBlacklist: (String) -> Unit
) {
    // ── 懒加载 = 渐进渲染（不是真分页）────────────────────────────────────────────
    // 依据：会话列表来自 `GET /api/sms/contacts`（RootSmsRoutes.kt），后端按号码聚合后
    // 一次性返回全量，无 offset/limit；app 侧 `getSmsContacts()` 同样无参。
    //
    // 注意：**对话内部（SmsConversationView）使用真分页**，`GET /api/sms/list` 支持
    // limit/offset，ToolsModule 的 openConversation / loadMoreConversation 已在使用。
    // 两处机制不同源于接口能力不同，并非实现不一致。
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
        // ── 设备短信信息行 ─────────────────────────────────────────────────────────
        // 作为 LazyColumn 的**第一个 item**，而不是列表外的固定行：
        // 下翻会话时该行随内容滚出视口，回到顶部时重新可见。
        // 固定在列表外会永久占用一条高度，不符合该信息行的定位。
        //
        // 使用 [UfiStatusCard] 而不是 [UfiSectionHeader]：前者为「单行状态 + trailing 动作槽」——
        // 条数进入状态徽标、未读进入副文案、动作进入 trailing，三者各有位置；
        // UfiSectionHeader 只有标题 + trailing，条数与未读需挤进同一标题字符串。
        // 也不自行组合 Surface + Row：那会改变 checkLiteralBaseline 的 dp / m3 类别基线。
        if (deviceSmsTotal != null) {
            item(key = "device-sms-info") {
                UfiStatusCard(
                    // 本 LazyColumn 已通过 contentPadding 提供 16dp 横向内距
                    //（与下方 SmsConversationRow 共用同一基线），而 UfiStatusCard 自带
                    // 相同的 CardHorizontalMargin；两者同时生效会形成 32dp 双重内距，
                    // 使卡片比会话行窄一圈。因此此处显式关闭组件自带的横向内距。
                    horizontalMargin = 0.dp,
                    title = "设备短信",
                    statusText = "$deviceSmsTotal 条",
                    // 存在未读时使用 WARNING 使徽标高亮；全部已读为常态，常态不使用强调色。
                    statusType = if (unreadTotal > 0) UfiBadgeType.WARNING else UfiBadgeType.DEFAULT,
                    subtitle = if (unreadTotal > 0) "$unreadTotal 条未读" else "没有未读短信",
                    // 「全部标为已读」仅在存在未读时显示。不采用「常驻但置灰」：
                    // 无法点击的按钮比不显示该按钮更容易造成困惑。
                    trailing = if (unreadTotal > 0) {
                        {
                            UfiButton(
                                text = "全部已读",
                                onClick = onMarkAllRead,
                                variant = UfiButtonVariant.Secondary,
                                size = UfiButtonSize.Small
                            )
                        }
                    } else null
                )
            }
        }
        items(rendered, key = { it.phoneNumber }) { contact ->
            SmsConversationRow(
                contact = contact,
                onClick = { viewModel.tools.openConversation(contact.phoneNumber) },
                onMarkRead = { onMarkRead(contact.phoneNumber) },
                onMarkUnread = { onMarkUnread(contact.phoneNumber) },
                onDeleteConversation = { onRequestDeleteConversation(contact.phoneNumber) },
                onRequestBlacklist = { onRequestBlacklist(contact.phoneNumber) }
            )
        }
        // 不使用尾部 `item("render-footer") { UfiSkeletonListItem() }`：
        // 渐进渲染为纯本地 `contacts.take(renderLimit)`，下一批可在同一帧绘制，不等待 IO。
        // 添加 shimmer 骨架等同于底部加载动画，而本页仅应在进入时播放动画。
    }
}

// ═══════════════════════════════════════════════
// 单条会话行 — 扁平设计，参考 Google Messages
// ═══════════════════════════════════════════════

/**
 * 单条会话行。
 *
 * 长按菜单复用消息气泡的实现方式（见 [ChatBubbleRow]）：
 * `combinedClickable` 获取长按 + `onGloballyPositioned { boundsInWindow() }` 采集锚点 +
 * [UfiPopupMenu]。不得改用 M3 原生 `DropdownMenu`：它在部分机型不继承自定义 colorScheme，
 * 会出现白底，这也是 UfiPopupMenu 存在的原因。
 *
 * 仓库中没有 swipe-to-action 组件，因此行内动作使用长按菜单而不是侧滑。
 *
 * 菜单中**不含「删除会话」**：core 仅有 `POST /api/sms/delete`（按单条 id 删除），
 * 没有按号码整段删除的端点。在 app 侧先拉取全部消息再逐条删除属于业务编排，
 * 按「core 负责业务、app 只负责展示」的分工不应实现在此处。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmsConversationRow(
    contact: SmsContact,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDeleteConversation: () -> Unit,
    onRequestBlacklist: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val hasUnread = contact.unread > 0
    val cardShape = UfiCardDefaults.dialogShape
    var showActions by remember { mutableStateOf(false) }
    // UfiPopupMenu 的定位需要锚点在窗口中的矩形；长按点用整行的几何中心
    // （combinedClickable 不暴露长按 offset，气泡与文件管理器 FileRowCard 也是这么取的）
    var rowBounds by remember { mutableStateOf(IntRect.Zero) }
    var longPressPoint by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            val r = coords.boundsInWindow()
            rowBounds = IntRect(
                r.left.roundToInt(), r.top.roundToInt(),
                r.right.roundToInt(), r.bottom.roundToInt()
            )
        }
    ) {
    Surface(
        shape = cardShape,
        color = palette.cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .ufiCardShadow(elevation = 3.dp, shape = cardShape)
            .border(1.dp, palette.divider.copy(alpha = 0.4f), cardShape)
            // 使用 combinedClickable 而不是 `Surface(onClick = …)`：后者仅支持单击，
            // 无法挂载长按。点击行为保持不变（打开会话）。
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    longPressPoint = IntOffset(
                        (rowBounds.left + rowBounds.right) / 2,
                        (rowBounds.top + rowBounds.bottom) / 2
                    )
                    showActions = true
                }
            )
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
                                // 使用 onAccent 而不是写死 `Color.White`：徽标底色为
                                // `palette.accent`（不是 error），若某主题的 accent 为浅色，
                                // 写死白字会与圆片同色而不可见。
                                // ChatBubbleRow 的发出方内容色同样使用 onAccent。
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

        UfiPopupMenu(
            visible = showActions,
            onDismiss = { showActions = false },
            anchorBounds = rowBounds,
            anchorPoint = longPressPoint,
            options = buildList {
                add(
                    UfiPopupOption(
                        id = "open",
                        label = "打开会话",
                        icon = Icons.Default.ChatBubbleOutline,
                        onClick = {
                            onClick()
                            showActions = false
                        }
                    )
                )
                // 没有未读时不给「标记已读」；反过来有未读时不给「标记未读」
                if (hasUnread) {
                    add(
                        UfiPopupOption(
                            id = "read",
                            label = "标记已读",
                            icon = Icons.Default.DoneAll,
                            onClick = {
                                onMarkRead()
                                showActions = false
                            }
                        )
                    )
                } else {
                    add(
                        UfiPopupOption(
                            id = "unread",
                            label = "标记未读",
                            icon = Icons.Default.MarkEmailUnread,
                            onClick = {
                                onMarkUnread()
                                showActions = false
                            }
                        )
                    )
                }
                add(UfiPopupOption.divider())
                add(
                    UfiPopupOption(
                        id = "delete-conversation",
                        label = "删除整段会话",
                        icon = Icons.Default.DeleteOutline,
                        isDestructive = true,
                        onClick = {
                            onDeleteConversation()
                            showActions = false
                        }
                    )
                )
                add(
                    UfiPopupOption(
                        id = "blacklist",
                        label = "加入黑名单",
                        icon = Icons.Default.Block,
                        isDestructive = true,
                        onClick = {
                            // 只上报意图：拦截是「静默丢消息」的操作，必须先过二次确认，
                            // 确认弹窗与写入都在屏幕层（见 pendingBlacklistPhone）。
                            onRequestBlacklist()
                            showActions = false
                        }
                    )
                )
            }
        )
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
    // 目标不在已加载页中时向上补页的次数上限（每页 100 条；设上限以避免目标已被设备删除时无限翻页）。
    var seekAttempts by remember(targetMsgId) { mutableIntStateOf(0) }
    // 列表首项可能是「查看更早的消息」按钮，消息下标需整体后移一位。
    // 判据为 `hasMore && messages.isNotEmpty()`：加载指示器已移除（见下方 LazyColumn），
    // isLoading 不再影响首项；首屏骨架仅在 messages 为空时存在，该场景不需要此 offset。
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
                // 使用与首项条件一致的 headerOffset，而不是固定 +1：
                // 最后一页加载完成、hasMore 变为 false 且 header 消失时，滚动位置不会偏移一行。
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

    // 此处**不使用 `imePadding()`**。
    //
    // 若外壳已应用 navigationBars，本页再调用 imePadding()，而 Android 的 IME inset
    // 本身覆盖导航栏段，底部 inset 会被计算两次，输入栏会悬浮在键盘上方约一个导航栏高度。
    // 底部 inset 由 `UfiScreenScaffold(imeAwareBottomInset = true)` 在外壳层统一应用
    // （`navigationBars ∪ ime`，取较大值而非相加）；本页通过 weight(1f) 让出高度，
    // 父级让出空间后列表自动压缩，「最后一条消息 + 输入栏」始终同时可见。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.pageBg)
    ) {
        // 顶栏直接置于 pageBg 上，不使用 `Surface(cardBg, tonalElevation=2, ufiCardShadow)`：
        // 该层在浅色主题下表现为「浅灰底 + 底边阴影」，与下方 pageBg 亮度接近，
        // 视觉上形成边界杂色而非层次。移除后顶栏与聊天区同底，由字号/字重区分层级。
        ConversationTopBar(phone = phone, total = totalCount, onBack = onBack)

        // 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 进入对话不显示首屏骨架：数据到达前列表为空，到位后一次性铺出。
            //
            // 不使用 `ConversationEntrySkeleton()`（6 行交替气泡占位 + shimmer）：
            //   ① 本页只需懒加载，数据到位直接出现即可；
            //   ② 骨架为固定 44dp 高的灰块，与真实气泡高度不等，数据到达时整块被替换，
            //      形态与高度同时变化，会产生一次可见跳动。
            //
            // 「查看更早的消息」按钮不受影响（要求 messages 非空）。
            // 该按钮在存在更早消息时始终显示，**不因 isLoading 切换为加载指示器**：
            // 两者互斥会导致每次翻页都闪一次版式，且加载指示器在「messages 为空 + loading」
            // 时同样出现，造成进入对话时的可见闪烁。翻页期间视觉状态保持不变。
            if (hasMore && messages.isNotEmpty()) {
                item(key = "load_more") {
                    TextButton(
                        onClick = {
                            // 锚点 = 当前第一个可见的**消息**项（跳过本按钮自己）。
                            // 骨架屏已移除，无需再排除「entry-skeleton」项。
                            listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { (it.key as? String)?.let { k -> k != "load_more" } == true }
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

        // 输入栏：直接置于 pageBg 上，**不包裹 Surface**。
        // ChatInputBar 内部的卡片为 palette.cardBg；若外层再套
        // `Surface(color = palette.cardBg, tonalElevation = 4.dp)`，同色叠加会吞掉
        // 卡片轮廓与描边，视觉上仍是平底。控制台页的输入栏外层没有任何容器，
        // 卡片直接浮于 pageBg 之上，层次清晰；此处保持一致。
        ChatInputBar(
            value = messageText,
            onValueChange = { messageText = it },
            // 发信能力。判据与 SmsScreen 一致，均读取 network.capabilityState。
            enabled = viewModel.network.capabilityState.collectAsState().value
                .supports(Capability.SMS),
            onSend = {
                if (messageText.isNotBlank()) {
                    viewModel.tools.sendSms(phone, messageText)
                    messageText = ""
                    onSent()
                }
            }
        )
    }
    // 不使用全屏「加载遮罩」（`if (messages.isEmpty() && isLoading) Box(fillMaxSize, pageBg 60%) { ... }`）：
    // 该遮罩是 Column 的兄弟节点且最后绘制，会覆盖整页。openConversation 会先清空
    // conversationMessages 并置 conversationLoading=true，因此每次进入对话
    //（含通知深链接直接进入）第一帧必然满足条件，以 36dp 加载指示器覆盖全屏；
    // 发送短信后重新拉取对话也会再次覆盖。首屏状态由列表内的骨架 item 表达，遮罩无必要。
}

// 不提供进入动画（原 `ConversationEntrySkeleton()` 与 `CONVERSATION_SKELETON_WIDTHS` 已移除）：
// 进入对话只需懒加载 —— 数据到位直接铺出即可。
// 该骨架为固定 44dp 高度，与真实气泡高度不等，数据到达时会产生可见跳动，
// 详见对话页 LazyColumn 中调用处的注释。

/**
 * 「首屏是否尚未产生结果」闩锁：返回 true = 应显示骨架；false = 已落地一次，之后不再显示。
 *
 * [loaded] 以 lambda 传入（而不是 Boolean 值）：本函数仅在首次组合时启动一个
 * `LaunchedEffect(Unit)`，lambda 内读取的是 collectAsState 的稳定 State 实例，
 * 因此协程中始终取得最新值，不会被首帧的值固定。
 *
 * 判据为 ViewModel 提供的「该数据已完成一次加载尝试」（成功 / 失败均计入），
 * **不检测** loading 的 true→false 边沿。
 * 边沿判断依赖 `loading / hasData / failed` 三个信号同时变化；若某份数据的 loading
 * 从未置为 true、失败又被静默吞掉，`first {}` 将永不返回、`settled` 恒为 false，
 * 页面会永久停留在骨架状态，对应空态不可达。落地信号单调（仅 false→true），
 * 不存在「边沿被 snapshot 合并」的问题。
 *
 * `remember` 的初值直接读取一次 [loaded]：重进页面时 VM 中数据仍在，
 * 不应为等待一帧 LaunchedEffect 而再次显示骨架。
 *
 * 可见性为 `internal`：同包的 [SmsFilterRulesScreen] 与 [SmsBlockedScreen] 也需要使用
 * （各自一份闩锁）。Kotlin 的 `private` 为文件级可见性，同包不同文件无法调用；
 * 复制实现会使「骨架判据」出现第二种版本。
 */
@Composable
internal fun rememberFirstLoadPending(loaded: () -> Boolean): Boolean {
    var settled by remember { mutableStateOf(loaded()) }
    LaunchedEffect(Unit) {
        snapshotFlow { loaded() }.first { it }
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
 * 配色约定：头像使用**实心 accent + onAccent 首字**（accent 面上一律使用 onAccent，
 * 不写死白色）。`accent@15%` 淡色圆底 + accent 文字在浅色主题下对比度不足，深色主题下层次不清。
 * 返回箭头显式指定 tint（palette.textPrimary），不依赖 LocalContentColor，
 * 避免在自定义 palette 下偏色。副标题使用 textSecondary 以区分层级。
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
 * 配色与布局约定：
 * - **收到方**气泡使用 `cardBg` + 1dp 细描边，浮于 pageBg 之上，与全站卡片语言一致。
 *   `divider` 是为分割线设计的极低对比色，作为气泡底色在浅色主题下与页面几乎同色。
 * - **发出方**内容色使用 `palette.onAccent`，不写死 `Color.White`
 *（自定义主题下 accent 可能为浅色，白字不可见）。
 * - **时间戳置于气泡外**：若放在气泡内独立一行且靠右，单字消息（如「好」）也会被撑满整行宽；
 *   移出后气泡贴合文字，且无需在 accent 面上另选弱化色。
 * - **长按菜单**使用公共组件 [UfiPopupMenu]（与文件管理器长按菜单一致），菜单中心对齐长按点，
 *   点击外部关闭；选项为复制 / 重发（仅发出方）/ 删除，均无需新增接口：重发复用 sendSms。
 *   不得改用 M3 原生 DropdownMenu：它在部分机型不继承自定义 colorScheme，会出现白底，
 *   这也是 UfiPopupMenu 存在的原因。
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
                    // 使用 Duration.Fluid（250ms），与原有 250ms 一致，观感不变。
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

                // 「重新发送」同样走 POST /api/sms/send，因此受 Capability.SMS 门禁约束。
                // 判据必须在此处获取而不是在下方 buildList 中 —— 该 lambda 不是 @Composable，
                // 在其中调用 collectAsState() 无法通过编译。
                val smsSupported by viewModel.network.capabilityState.collectAsState()
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
                        // 不支持发信时**不添加该项**，而不是显示为置灰：长按菜单不是设置面板，
                        // 没有显示原因文案的位置；置灰而不说明原因会导致反复尝试。
                        // 原因由对话输入栏的占位文字与消息列表页顶部的说明卡呈现。
                        if (!isReceived && smsSupported.supports(Capability.SMS)) {
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
 * 吸底输入栏 —— 与「高级控制台」的 ConsoleInputBar 一致：卡片容器 + BasicTextField + 方形发送键。
 *
 * **取色**：卡片使用 `palette.cardBg`，且外层不再包裹
 * `Surface(color = palette.cardBg, tonalElevation = 4.dp)` —— 同色叠加会吞掉轮廓与描边。
 * 卡片直接浮于 pageBg 之上；描边随聚焦在 `divider@30%` ⇄ `accent@55%` 之间过渡，
 * 阴影同步 2dp ⇄ 6dp，深浅色两套主题均由 palette 推导，无硬编码色值。
 *
 * **键盘避让**：本组件只保留 `navigationBarsPadding()` 处理无键盘时的导航栏避让；
 * 输入法 inset 由 `UfiScreenScaffold(imeAwareBottomInset = true)` 在外壳层统一应用，
 * 此处不得再挂 `imePadding()`（会导致底部 inset 重复计算）。
 *
 * **动画**：
 * - 聚焦：描边色与阴影高度通过 `animateColorAsState`/`animateDpAsState` 过渡（220ms），
 *   提供输入框激活的反馈；
 * - 发送键：`canSend` 翻转时执行 0.8→1 的弹性缩放（spring，带轻微回弹）+ 背景色过渡，
 *   输入首个字符时展开，清空时收回。
 */
@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    /**
     * `false` = 本设备不支持发送短信。输入框不可编辑、发送键置灰，
     * 占位文字替换为原因 —— 输入栏即「在此输入并发送」的承诺，
     * 应在输入前明确告知不可发送，而不是在用户完成输入后才提示。
     */
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    val canSend = value.isNotBlank() && enabled
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 卡壳与发送键使用公共 UfiInputBarCard / UfiSendButton
        //（阴影 6/2dp、禁用底色 0.35、贴底对齐沿用本页原值，观感不变）
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
                enabled = enabled,
                cursorBrush = SolidColor(palette.accent),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                // 短信是多行正文，Enter 必须换行，所以这里不像控制台那样绑 ImeAction.Send
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = if (enabled) "输入短信\u2026" else deviceUnsupportedNote("发送短信"),
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
        // 「功能未开启」引导也走 [UfiEmptyState]（action 槽位放开启按钮）：
        // 它与下面的「暂无验证码通知」是同一个 Tab 的两种空，版式不一致会让人以为进错了页面。
        UfiEmptyState(
            icon = Icons.Default.VerifiedUser,
            message = "验证码自动解析",
            hint = "开启后，新收到的验证码短信将自动识别并显示在这里，方便快速复制。",
            modifier = Modifier.fillMaxSize(),
            action = {
                FilledTonalButton(onClick = onEnable) {
                    Text("开启功能")
                }
            }
        )
        return
    }

    // 首屏：尚未产生结果且列表为空 → 显示骨架。
    // 不使用「保持空白一瞬」（`if (isLoading) return`）：该写法用于避免
    //「加载指示器 → 空态 → 列表」的连续跳变。骨架本身预示列表版式，
    // 数据到位时原地替换而非切换形态，比空白更能表达加载状态。
    // 判据使用 firstLoadPending（闩锁）而不是 isLoading：否则「空列表 + 顶栏刷新」
    // 会再次显示骨架，而刷新必须无动画。
    if (codes.isEmpty()) {
        if (firstLoadPending) {
            UfiSkeletonList(rows = 5, modifier = Modifier.padding(Spacing.Large))
            return
        }
        UfiEmptyState(
            icon = Icons.Default.NotificationsNone,
            message = "暂无验证码通知",
            hint = "新收到的验证码短信将自动显示在这里",
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    // ── 懒加载 = 渐进渲染（不是真分页）────────────────────────────────────────────
    // 依据：core 的 `GET /api/sms/verification-codes`（RootSmsRoutes.kt）返回整份解析缓存，
    // 无 offset/limit；app 侧 `getVerificationCodes()` 同样无参。因此由客户端分批渲染。
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
 * 该界面的主体为**验证码本身**：验证码使用 headlineMedium 等宽字体，复制按钮为可直接点击的图标；
 * 正文降级为一行「智能摘要」。直接展示 2 行 snippet 时，界面大部分被
 * 「请勿泄露给他人…」类模板文案占据，压缩了验证码的显示空间。
 * 摘要规则见 [smsDigest]；`【】` 签名单独作为来源标签（见 [smsSignature]），
 * 比原始号码更易识别。完整正文在详情弹窗中查看。
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
 * 一行摘要：只做**去签名 + 压缩空白**，不裁剪文本。
 *
 * 不使用「截取到第一个句末标点」或「30 字上限」等按内容计算的规则：
 * 有的短信首个句号出现在第 6 个字符（「验证码123456。」），有的整条无句号而占满 30 字，
 * 导致每张卡片的截断长度不一致。
 * 文本原样输出，由 `maxLines = 1` + `TextOverflow.Ellipsis` 按**实际可用宽度**
 * 截为一行并加省略号，短文本完整显示，各卡片的截断位置自然对齐。
 *
 * 仍需移除 `【】` 签名：该签名已作为来源标签单独显示在上一行，重复出现只占用宽度。
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
// 新建短信 — 弹窗
// ═══════════════════════════════════════════════

/**
 * 新建短信弹窗。
 *
 * 使用弹窗而不是整屏写信页：写一条短信仅需「号码 + 正文」两个字段，
 * 整页会顶掉列表上下文（发送后还需返回），且需要为撑满一屏额外放置顶栏、模板区、字数行等部件。
 * 弹窗形态保留列表可见，并使各部件直接复用公共弹窗组件。
 *
 * 使用 [UfiScrollableDialog] 而不是 UfiCustomDialog：正文框最多 6 行 + 联想列表最多 4 条，
 * 小屏（尤其键盘弹起时）总高会超出弹窗预算，需要内容区可滚动、动作区固定在底部。
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
    // 以输入的数字匹配短信列表中出现过的号码；候选项使用公共 UfiPopupMenu
    //（项目唯一菜单实现）。M3 DropdownMenu 在部分机型不继承自定义 colorScheme，会出现白底，禁止使用。
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
            // 发送键与 canSend 联动：号码或正文为空时置灰，避免点击后无反馈。
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
