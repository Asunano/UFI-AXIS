package com.ufi_axis.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.navigation.NavHostController
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.ConsoleMessage
import com.ufi_axis.viewmodel.state.ConsoleRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 高级控制台 — 聊天记录式 AT 指令 + Shell 命令工具。
 *
 * 命令与基带/Socket 返回以对话气泡形式依次排列，输入框吸底；
 * 参考 HTML 原型（advanced-console-chat.html）：空状态居中提示 +
 * 橙/白气泡 + 白色输入卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedConsoleScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.toolsState.collectAsState()

    // 输入状态提升到父级，切换 Tab 时不丢失已输入内容
    var selectedTab by remember { mutableStateOf(0) }
    var atCommand by remember { mutableStateOf("") }
    var shellCommand by remember { mutableStateOf("") }
    // Shell Root 默认不勾选 —— 普通 shell 已够用，避免误用高危特权
    var isRoot by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    UfiScreenScaffold(
        title = "高级控制台",
        navController = navController,
        showBack = true,
        actions = {
            val palette = LocalResolvedPalette.current
            val currentMessages = if (selectedTab == 0) state.atMessages else state.shellMessages
            val hasHistory = currentMessages.isNotEmpty()
            TextButton(
                onClick = { showClearConfirm = true },
                enabled = hasHistory,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "清除对话",
                    tint = if (hasHistory) palette.accent else palette.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("清除", color = if (hasHistory) palette.accent else palette.textSecondary)
            }
        }
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {
                UfiScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    tabs = listOf("AT 指令", "Shell 命令"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )

                ChatConsoleContent(
                    atMessages = state.atMessages,
                    shellMessages = state.shellMessages,
                    isShell = selectedTab == 1,
                    command = if (selectedTab == 0) atCommand else shellCommand,
                    onCommandChange = { if (selectedTab == 0) atCommand = it else shellCommand = it },
                    onSend = {
                        if (selectedTab == 0) {
                            if (atCommand.isNotBlank()) {
                                viewModel.tools.sendAtCommand(atCommand)
                                atCommand = ""
                            }
                        } else {
                            if (shellCommand.isNotBlank()) {
                                viewModel.tools.executeShell(shellCommand, isRoot)
                                shellCommand = ""
                            }
                        }
                    },
                    isLoading = state.isLoading,
                    placeholder = if (selectedTab == 0) "输入 AT 指令..." else "输入 Shell 命令...",
                    isRoot = isRoot,
                    onRootChange = { isRoot = it },
                    showRoot = selectedTab == 1,
                    quickCommands = if (selectedTab == 0) listOf(
                        "信号强度" to "AT+CSQ",
                        "设备信息" to "ATI",
                        "网络注册" to "AT+CREG?",
                        "运营商" to "AT+COPS?",
                        "IMEI" to "AT+CGSN"
                    ) else listOf(
                        "网络" to "ifconfig",
                        "存储" to "df -h",
                        "进程" to "ps",
                        "内存" to "free"
                    ),
                    onQuickSend = { cmd ->
                        if (selectedTab == 0) viewModel.tools.sendAtCommand(cmd)
                        else viewModel.tools.executeShell(cmd, isRoot)
                    }
                )
            }
        }
    }

    if (showClearConfirm) {
        val tabName = if (selectedTab == 0) "AT 指令" else "Shell 命令"
        UfiConfirmDialog(
            title = "清除对话",
            text = "确定要清空当前「$tabName」的对话记录吗？此操作不可撤销。",
            confirmText = "清除",
            dismissText = "取消",
            destructive = true,
            onConfirm = {
                viewModel.tools.clearConsole(if (selectedTab == 0) "at" else "shell")
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }
}

/**
 * 聊天会话主区域：消息气泡列表 + 固定底部输入栏。
 * 使用 Column 上下分区（列表 weight=1f，输入栏自然排布在下方），从根本上避免输入栏遮挡消息：
 * - 输入栏始终可见，无需滑到底即可点击；
 * - 弹键盘时父级 imePadding 使整个面板上移，输入栏位于输入法上方；
 * - 列表区高度 = 父级剩余空间，切 Tab / 快捷命令显隐都不会再造成遮挡或无法滚到底。
 * AT / Shell 切换时仅消息列表做横向滑动 + 淡入淡出过渡。
 */
@Composable
private fun ChatConsoleContent(
    atMessages: List<ConsoleMessage>,
    shellMessages: List<ConsoleMessage>,
    isShell: Boolean,
    command: String,
    onCommandChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    placeholder: String,
    isRoot: Boolean,
    onRootChange: (Boolean) -> Unit,
    showRoot: Boolean,
    quickCommands: List<Pair<String, String>> = emptyList(),
    onQuickSend: (String) -> Unit = {}
) {
    val palette = LocalResolvedPalette.current
    var isInputFocused by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // 消息列表区域：用 weight(1f) 框住，确保下方输入栏永远有 wrap 高度的渲染空间
        // （直接放 fillMaxSize 的列表会把整列高度吃完，把输入栏挤到 0 高度）
        Box(Modifier.weight(1f)) {
            ConsoleMessageList(
                atMessages = atMessages,
                shellMessages = shellMessages,
                isShell = isShell,
                isLoading = isLoading
            )
        }

        // 固定底部输入栏（Shell Tab 在输入框左侧注入 Root 胶囊切换；不再用突兀的系统 Checkbox）
        Column(modifier = Modifier.fillMaxWidth()) {
            AnimatedVisibility(
                visible = isInputFocused && quickCommands.isNotEmpty(),
                enter = fadeIn(animationSpec = tween(UfiMotion.Duration.Base)) +
                    slideInVertically(initialOffsetY = { it }, animationSpec = tween(UfiMotion.Duration.Base)),
                // 2026-09-04（P2b）：离场原为裸 `tween(150)` ×2 → Duration.Swift（160，+10ms，
                // 在吸附容差内；150 与梯度上的 160 是同一种"轻量"手感，不该存在两个数）。
                exit = fadeOut(animationSpec = tween(UfiMotion.Duration.Swift)) +
                    slideOutVertically(targetOffsetY = { it }, animationSpec = tween(UfiMotion.Duration.Swift))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickCommands.forEach { (label, cmd) ->
                        SuggestionChip(
                            onClick = { onQuickSend(cmd) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            shape = UfiCardDefaults.shape,
                            border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.55f)),
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = palette.accent.copy(alpha = 0.18f),
                                labelColor = palette.accent
                            )
                        )
                    }
                }
            }
            ConsoleInputBar(
                command = command,
                onCommandChange = onCommandChange,
                onSend = onSend,
                isLoading = isLoading,
                placeholder = placeholder,
                onFocusedChange = { isInputFocused = it },
                hasLeading = showRoot,
                leadingContent = if (showRoot) {
                    { RootToggle(isRoot = isRoot, onToggle = { onRootChange(!isRoot) }) }
                } else { {} }
            )
        }
    }
}

/**
 * 消息列表（独立函数，从 ChatConsoleContent 抽出）：
 * - 独立作用域，避免外层 ColumnScope 与 LazyItemScope 在解析 AnimatedVisibility 时发生歧义；
 * - AT / Shell 切换时做横向滑动 + 淡入淡出过渡；
 * - 新消息 / 切 Tab 时滚动到底部，并对超过视口高度的超长单条消息继续滚到其底部。
 */
@Composable
private fun ConsoleMessageList(
    atMessages: List<ConsoleMessage>,
    shellMessages: List<ConsoleMessage>,
    isShell: Boolean,
    isLoading: Boolean
) {
    AnimatedContent(
        targetState = isShell,
        transitionSpec = {
            val dir = if (targetState) 1 else -1
            (slideInHorizontally(
                initialOffsetX = { dir * it / 3 },
                animationSpec = tween(UfiMotion.Duration.Gentle)
            ) + fadeIn(tween(UfiMotion.Duration.Gentle))) togetherWith
                (slideOutHorizontally(
                    targetOffsetX = { -dir * it / 3 },
                    animationSpec = tween(UfiMotion.Duration.Gentle)
                ) + fadeOut(tween(UfiMotion.Duration.Gentle)))
        },
        modifier = Modifier.fillMaxSize(),
        label = "consoleList"
    ) { shell ->
        val msgs = if (shell) shellMessages else atMessages
        val listState = rememberLazyListState()
        // 新消息到达 / 切 Tab 时滚动到底部；对超长单条消息会进一步滚到其底部
        LaunchedEffect(msgs.size) {
            if (msgs.isEmpty()) return@LaunchedEffect
            // 先把最后一条消息的顶部对齐到视口顶部
            listState.animateScrollToItem(msgs.lastIndex)
            // 若最后一条消息高度超过视口，继续向下滚动，确保其底部也可见
            val lastItem = listState.layoutInfo.visibleItemsInfo
                .find { it.index == msgs.lastIndex }
            if (lastItem != null) {
                val itemBottom = lastItem.offset + lastItem.size
                val viewportBottom = listState.layoutInfo.viewportEndOffset
                val overflow = itemBottom - viewportBottom
                if (overflow > 0) {
                    listState.animateScrollBy(overflow.toFloat())
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (msgs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        ConsoleEmptyState(Modifier.fillMaxWidth())
                    }
                }
            } else {
                items(msgs, key = { it.id }) { msg ->
                    AnimatedVisibility(
                        visible = true,
                        // 2026-09-04（P2b）：原为裸 `tween(250)` ×2 → Duration.Fluid（同为 250，零观感变化）。
                        enter = fadeIn(animationSpec = tween(UfiMotion.Duration.Fluid)) +
                            slideInVertically(initialOffsetY = { it / 3 }, animationSpec = tween(UfiMotion.Duration.Fluid))
                    ) {
                        ConsoleMessageBubble(message = msg, isShell = shell)
                    }
                }
            }
            if (isLoading) {
                item { ConsoleTypingIndicator() }
            }
        }
    }
}

/**
 * Shell 页 Root 权限胶囊切换：
 * - OFF：accent 浅底 + accent 文字，显示「Shell」
 * - ON：accent 实底 + 反色文字，显示「Root」
 * 内嵌在输入栏左侧，比原系统 Checkbox 更柔和、位置更紧凑。
 */
@Composable
private fun RootToggle(isRoot: Boolean, onToggle: () -> Unit) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = Modifier
            .clip(UfiCardDefaults.shape)
            .background(if (isRoot) palette.accent else palette.accent.copy(alpha = 0.12f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = if (isRoot) "Root 权限" else "普通 Shell",
                tint = if (isRoot) palette.onAccent else palette.accent,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = if (isRoot) "Root" else "Shell",
                style = MaterialTheme.typography.labelSmall,
                color = if (isRoot) palette.onAccent else palette.accent
            )
        }
    }
}

/**
 * 单条消息气泡：
 * - USER：右对齐、accent 橙底、白字、圆角右下收窄、底部「HH:mm · 已发送」
 * - ASSISTANT：左对齐、卡面底（cardBg，2026-09-03 P1c 由写死白改为跟随主题）、正文色字、圆角左下收窄、底部显示时间
 * - ERROR：同 ASSISTANT 布局、error 浅底、error 红字
 * 正文一律等宽字体（bodySmall 作为基准样式）。
 */
@Composable
private fun ConsoleMessageBubble(message: ConsoleMessage, isShell: Boolean) {
    val palette = LocalResolvedPalette.current
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val context = LocalContext.current

    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("console", text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    val isUser = message.role == ConsoleRole.USER
    val bubbleColor = when (message.role) {
        ConsoleRole.USER -> palette.accent
        // 2026-09-03（P1c）：原为写死 `Color.White`。这是**收到方气泡的容器色**，浮在页面底
        // （pageBg）上、文字取 palette.textPrimary —— 换配色时白底不跟着变，会与同页其它
        // 卡片/输入栏（都已是 cardBg）不同色，成为上一版主题的残影。
        // 改法照本文件 ConsoleInputBar 的先例（见其 KDoc「2026-08-29：容器色由写死的
        // Color.White 改为 palette.cardBg」）与短信 ChatBubbleRow 的收到方气泡，全站一致。
        ConsoleRole.ASSISTANT -> palette.cardBg
        ConsoleRole.ERROR -> palette.error.copy(alpha = 0.08f)
    }
    val contentColor = when (message.role) {
        ConsoleRole.USER -> palette.onAccent
        ConsoleRole.ASSISTANT -> palette.textPrimary
        ConsoleRole.ERROR -> palette.error
    }
    val bubbleShape = when (message.role) {
        ConsoleRole.USER -> UfiCardDefaults.consoleBubbleShape(isUser = true)
        else -> UfiCardDefaults.consoleBubbleShape(isUser = false)
    }
    val maxWidthFraction = if (isUser) 0.78f else 0.80f
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * maxWidthFraction).dp

    val label = if (isUser) {
        "${timeFmt.format(Date(message.timestamp))} · 已发送"
    } else {
        timeFmt.format(Date(message.timestamp))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .wrapContentWidth(align = if (isUser) Alignment.End else Alignment.Start)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { copyText(message.text) }
                )
                .background(bubbleColor, bubbleShape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                style = UfiTextStyles.monoNote,
                color = contentColor
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp)
        )
    }
}

/**
 * 空状态：居中图标盒 + 主副提示文案（参考 HTML 原型）。
 */
@Composable
private fun ConsoleEmptyState(modifier: Modifier = Modifier) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(palette.accent.copy(alpha = 0.12f), UfiCardDefaults.iconTileShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "开始发送命令",
            style = UfiTextStyles.bodyLead.copy(fontWeight = UfiWeight.Emphasis),
            color = palette.textPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "命令和返回结果会像对话一样依次显示在这里",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 200.dp)
        )
    }
}

/**
 * 加载反馈：等待基带 / Shell 返回时，在列表底部显示三段式打字指示器，
 * 明确告知用户「正在处理」，避免网络延迟造成无响应的假象。
 */
@Composable
private fun ConsoleTypingIndicator() {
    val palette = LocalResolvedPalette.current
    val transition = rememberInfiniteTransition()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(palette.accent.copy(alpha = 0.12f), UfiCardDefaults.consoleBubbleShape(isUser = false))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { i ->
                    val alpha by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = UfiMotion.Duration.Deliberate, delayMillis = i * 150),
                            repeatMode = RepeatMode.Reverse
                        )
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(palette.accent.copy(alpha = alpha), CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * 吸底输入栏：圆角卡 + 等宽输入框 + 发送按钮（加载中显示进度环）。
 * [leadingContent] 是输入框左侧的插槽（默认空），Shell Tab 通过它注入 [RootToggle] 胶囊切换。
 * [hasLeading] 控制是否插入 leading 与文本框之间的间隔，避免空插槽多出空白。
 *
 * 2026-08-29：容器色由写死的 `Color.White` 改为 `palette.cardBg` —— 深色主题下白底卡片
 * 在深色页面上是一块刺眼白板，且与输入文字色（textPrimary，深色主题下接近白）撞色到几乎看不见字。
 *
 * 2026-08-29 二次：动效与短信对话页的 ChatInputBar 对齐（两处输入栏是同一套设计，
 * 之前只有短信那边做了动效，控制台还是一块静态卡）：
 * - 聚焦：描边 `divider@30%` ⇄ `accent@55%`、阴影 4dp ⇄ 8dp，220ms 过渡；
 * - 发送键：`canSend` 翻转时 0.8→1 弹性缩放 + 背景色过渡，输入首字符时"弹出来"；
 * - 图标/进度环 tint 由写死 `Color.White` 改为 `palette.onAccent`（自定义主题 accent 可能是浅色）。
 */
@Composable
private fun ConsoleInputBar(
    command: String,
    onCommandChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    placeholder: String,
    onFocusedChange: (Boolean) -> Unit = {},
    hasLeading: Boolean = false,
    leadingContent: @Composable () -> Unit = {}
) {
    val palette = LocalResolvedPalette.current
    val canSend = command.isNotBlank() && !isLoading
    var isFocused by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // 2026-08-31：卡壳与发送键改用公共 UfiInputBarCard / UfiSendButton
        //（阴影 8/4dp、禁用底色 0.45 都保持本页原值，观感不变）
        UfiInputBarCard(
            focused = isFocused,
            focusedElevation = 8.dp,
            restingElevation = 4.dp,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasLeading) {
                leadingContent()
                Spacer(Modifier.width(6.dp))
            }
            BasicTextField(
                value = command,
                onValueChange = onCommandChange,
                modifier = Modifier
                    .weight(1f)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    }
                    .onFocusChanged {
                        isFocused = it.isFocused
                        onFocusedChange(it.isFocused)
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                textStyle = UfiTextStyles.monoReadout.copy(color = palette.textPrimary),
                decorationBox = { innerTextField ->
                    Box {
                        if (command.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
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
                loading = isLoading,
                disabledAlpha = 0.45f
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}
