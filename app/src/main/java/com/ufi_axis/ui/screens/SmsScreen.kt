package com.ufi_axis.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.SmsContact
import com.ufi_axis.data.model.SmsRecord
import com.ufi_axis.ui.animation.blurEntrance
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val toolsState by viewModel.toolsState.collectAsState()
    var showSendDialog by remember { mutableStateOf(false) }
    // "grouped" = 按联系人分组, "timeline" = 时间线气泡
    var viewMode by remember { mutableStateOf("grouped") }

    LaunchedEffect(Unit) { viewModel.tools.loadSmsContacts() }

    if (showSendDialog) {
        SmsSendSheet(
            onDismiss = { showSendDialog = false },
            onSend = { phone, msg -> viewModel.tools.sendSms(phone, msg); showSendDialog = false }
        )
    }

    UfiScreenScaffold(
        title = "短信",
        navController = navController,
        showBack = true,
        actions = {
            // 切换视图: People图标=分组, Chat图标=时间线
            IconButton(onClick = { viewMode = if (viewMode == "grouped") "timeline" else "grouped" }) {
                Icon(
                    if (viewMode == "grouped") Icons.Default.Email else Icons.Default.Person,
                    contentDescription = "切换视图"
                )
            }
            IconButton(onClick = { viewModel.tools.loadSmsContacts() }) {
                Icon(Icons.Default.Refresh, null)
            }
            IconButton(onClick = { showSendDialog = true }) {
                Icon(Icons.Default.Add, null)
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = toolsState.isLoading,
            onRefresh = { viewModel.tools.loadSmsContacts() },
            modifier = Modifier.blurEntrance("sms").padding(padding).fillMaxSize()
        ) {
        when {
            toolsState.isLoading && toolsState.smsContacts.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    UfiLoadingBox(isLoading = true) {}
                }
            }
            toolsState.smsContacts.isEmpty() && toolsState.smsList.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MailOutline, null, modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(12.dp))
                        Text("暂无短信", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("点击右上角 + 发送短信", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
            else -> {
                if (viewMode == "grouped") {
                    SmsGroupedView(contacts = toolsState.smsContacts, viewModel = viewModel, modifier = Modifier.padding(padding))
                } else {
                    val sorted = toolsState.smsList.sortedByDescending { it.timestamp }
                    SmsTimelineView(smsList = sorted, viewModel = viewModel, modifier = Modifier.padding(padding))
                }
            }
        }
        }
    }
}

// ── 分组视图（使用后端预聚合的联系人列表） ──

@Composable
private fun SmsGroupedView(contacts: List<SmsContact>, viewModel: MainViewModel, modifier: Modifier) {
    val toolsState by viewModel.toolsState.collectAsState()
    var expandedContact by remember { mutableStateOf<String?>(null) }

    // 当 toolsState.conversationPhone 变化时同步 UI 状态
    LaunchedEffect(toolsState.conversationPhone) {
        if (toolsState.conversationPhone.isEmpty()) expandedContact = null
    }

    if (expandedContact != null) {
        SmsConversationView(
            phone = expandedContact!!,
            messages = toolsState.conversationMessages,
            isLoading = toolsState.conversationLoading,
            hasMore = toolsState.conversationHasMore,
            totalCount = toolsState.conversationTotal,
            deletingMessageIds = toolsState.deletingMessageIds,
            viewModel = viewModel,
            onBack = { viewModel.tools.closeConversation() },
            onLoadMore = { viewModel.tools.loadMoreConversation() },
            modifier = modifier
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.PagePadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(contacts, key = { it.phoneNumber }) { contact ->
                val phone = contact.phoneNumber
                val unreadCount = contact.unread
                val isReceived = contact.latestDirection == "received"

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = UfiCardDefaults.legacyShape,
                    colors = CardDefaults.cardColors(
                        containerColor = if (unreadCount > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface
                    ),
                    elevation = UfiCardDefaults.noElevation()
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            expandedContact = phone
                            viewModel.tools.openConversation(phone)
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 头像
                        Box(
                            modifier = Modifier.size(42.dp).clip(CircleShape).background(
                                when {
                                    isReceived -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(phone.takeLast(2), style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(phone, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Text(FormatUtils.formatTimestamp(contact.latestTimestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(contact.latestMsg.take(40) + if (contact.latestMsg.length > 40) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                if (contact.total > 1) {
                                    Spacer(Modifier.width(4.dp))
                                    Text("${contact.total}条", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (unreadCount > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                                        Text("$unreadCount", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                    }
                                }
                            }
                        }
                        // 箭头指示可点击展开
                        Icon(Icons.Default.ChevronRight, null,
                            modifier = Modifier.size(20.dp).padding(start = 8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

// ── 单个联系人对话视图（分页加载 + 上滑加载更早消息） ──

@Suppress("DEPRECATION")
@Composable
private fun SmsConversationView(
    phone: String,
    messages: List<SmsRecord>,
    isLoading: Boolean,
    hasMore: Boolean,
    totalCount: Int,
    deletingMessageIds: Set<Long>,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier
) {
    val dateFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val dayFormatter = remember { SimpleDateFormat("MM月dd日", Locale.getDefault()) }
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 消息已按 ASC 排序（由 ViewModel 保证）
    val listItems = messages

    // 标记是否已完成首次定位到底部
    var initialScrolled by remember { mutableStateOf(false) }

    // 首次加载时瞬间滚动到底部（无动画）
    LaunchedEffect(listItems.size) {
        if (listItems.isNotEmpty() && !initialScrolled) {
            listState.scrollToItem(listItems.size)
            initialScrolled = true
        }
    }

    // ── 加载更多时捕获锚点，数据更新后恢复位置 ──
    // 仅捕获 PREVIOUS（加载前）第一条可见消息的 key，避免 _000
    var pendingAnchorKey by remember { mutableStateOf<String?>(null) }
    var prevListSize by remember { mutableStateOf(0) }

    // 当列表大小增长（prepend了更早消息），恢复滚动位置
    LaunchedEffect(listItems.size) {
        if (initialScrolled && listItems.size > prevListSize && prevListSize > 0) {
            val anchorKey = pendingAnchorKey
            if (anchorKey != null) {
                val anchorIdx = listItems.indexOfFirst { "${it.id}-${it.timestamp}" == anchorKey }
                if (anchorIdx >= 0) {
                    // +1 补偿 LazyColumn 中的 header item，scrollOffset=0 将这个 item 放到顶部
                    listState.scrollToItem(anchorIdx + 1, 0)
                }
            }
            pendingAnchorKey = null
        }
        prevListSize = listItems.size
    }

    // 预计算日期分隔符
    val dateHeaders = remember(listItems) {
        val headers = mutableMapOf<Int, String>()
        var lastDate: Long? = null
        listItems.forEachIndexed { index, sms ->
            if (lastDate == null || (sms.timestamp - (lastDate ?: 0L)) > 3600_000L) {
                headers[index] = dayFormatter.format(Date(sms.timestamp))
                lastDate = sms.timestamp
            }
        }
        headers
    }

    // ── 首次加载遮罩：消息为空且正在加载时显示 ──
    val showMask = listItems.isEmpty() && isLoading

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部联系人标题栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(phone.takeLast(2), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(phone, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    // 显示实际总数（来自后端 total），回退到已加载数
                    val displayCount = totalCount.coerceAtLeast(listItems.size)
                    Text("${displayCount} 条消息", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = Spacing.PagePadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 顶部加载指示器 / 加载更多按钮
                if (isLoading) {
                    item(key = "loading_top") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                } else if (hasMore && listItems.isNotEmpty()) {
                    item(key = "load_more") {
                        TextButton(
                            onClick = {
                                // 点击前捕获第一条可见消息作为锚点
                                val visItems = listState.layoutInfo.visibleItemsInfo
                                val firstMsg = visItems.firstOrNull { info ->
                                    val k = info.key as? String
                                    k != null && k != "load_more" && k != "loading_top"
                                }
                                pendingAnchorKey = firstMsg?.key as? String
                                onLoadMore()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("加载更早的消息…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                itemsIndexed(listItems, key = { _, it -> "${it.id}-${it.timestamp}" }) { index, sms ->
                    val isReceived = sms.direction == "received"
                    val isBeingDeleted = sms.id in deletingMessageIds

                    AnimatedVisibility(
                        visible = !isBeingDeleted,
                        exit = fadeOut(animationSpec = tween(300)),
                        enter = fadeIn(animationSpec = tween(200))
                    ) {
                        Column {
                            dateHeaders[index]?.let { dateLabel ->
                                Text(
                                    dateLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isReceived) Arrangement.Start else Arrangement.End
                            ) {
                                if (isReceived) {
                                    SmsAvatar(phone, Modifier.padding(end = 6.dp))
                                }

                                Column(
                                    modifier = Modifier.widthIn(max = 280.dp),
                                    horizontalAlignment = if (isReceived) Alignment.Start else Alignment.End
                                ) {
                                    Surface(
                                        shape = UfiCardDefaults.chatBubbleShape(isReceived),
                                        color = if (isReceived) MaterialTheme.colorScheme.secondaryContainer
                                                else MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(sms.content, style = MaterialTheme.typography.bodyMedium)
                                            Spacer(Modifier.height(4.dp))
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    dateFormatter.format(Date(sms.timestamp)),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    if (!sms.read && isReceived) {
                                                        TextButton(
                                                            onClick = { viewModel.tools.markSmsRead(sms.id.toString()) },
                                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                            modifier = Modifier.height(28.dp)
                                                        ) {
                                                            Text("已读", style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    }
                                                    TextButton(
                                                        onClick = { viewModel.tools.deleteSms(sms.id.toString()) },
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) {
                                                        Text("删除", style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.error)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (!sms.read && isReceived) {
                                        Text("未读", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(start = 4.dp, top = 1.dp))
                                    }
                                }

                                if (!isReceived) {
                                    SmsAvatar("我", Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ── 底部发送输入框 ──
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text("输入短信…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    singleLine = false,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    shape = UfiCardDefaults.chatInputShape
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            viewModel.tools.sendSms(phone, messageText)
                            messageText = ""
                        }
                    },
                    enabled = messageText.isNotBlank(),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Send, "发送", modifier = Modifier.size(20.dp))
                }
            }
        }

        // ── 首次加载遮罩：消息为空 + 正在加载时覆盖 ──
        if (showMask) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                UfiLoadingIndicator(modifier = Modifier.size(36.dp))
            }
        }
    }
}

// ── 时间线视图（聊天气泡） ──

@Composable
private fun SmsTimelineView(smsList: List<SmsRecord>, viewModel: MainViewModel, modifier: Modifier) {
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val dateFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val dayFormatter = remember { SimpleDateFormat("MM月dd日", Locale.getDefault()) }
    var lastDate: Long? by remember { mutableStateOf(null) }
    val toolsState by viewModel.toolsState.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.PagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(smsList, key = { "${it.id}-${it.timestamp}" }) { sms ->
            val isReceived = sms.direction == "received"
            val isExpanded = expandedId == sms.id
            val isBeingDeleted = sms.id in toolsState.deletingMessageIds

            AnimatedVisibility(
                visible = !isBeingDeleted,
                exit = fadeOut(animationSpec = tween(300)),
                enter = fadeIn(animationSpec = tween(200))
            ) {
                Column {
                    // 日期分隔符：与前一条时间差 > 1小时时显示
                    val showDate = lastDate == null || (sms.timestamp - (lastDate ?: 0L)) > 3600_000L
                    if (showDate) {
                        lastDate = sms.timestamp
                        Text(
                            dayFormatter.format(Date(sms.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { expandedId = if (isExpanded) null else sms.id }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isReceived) Arrangement.Start else Arrangement.End
                        ) {
                            if (isReceived) {
                                SmsAvatar(sms.phoneNumber, Modifier.padding(end = 6.dp))
                            }

                            Column(horizontalAlignment = if (isReceived) Alignment.Start else Alignment.End) {
                                // 对方（接收）才显示号码
                                if (isReceived) {
                                    Text(sms.phoneNumber.take(15), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                                }
                                // 气泡
                                Surface(
                                    modifier = Modifier.widthIn(max = 280.dp),
                                    shape = UfiCardDefaults.chatBubbleShape(isReceived),
                                    color = if (isReceived) MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(sms.content, style = MaterialTheme.typography.bodyMedium)
                                        if (isExpanded) {
                                            Spacer(Modifier.height(6.dp))
                                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                            Spacer(Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(dateFormatter.format(Date(sms.timestamp)),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    modifier = Modifier.align(Alignment.CenterVertically))
                                                Spacer(Modifier.weight(1f))
                                                if (!sms.read && isReceived) {
                                                    TextButton(
                                                        onClick = { viewModel.tools.markSmsRead(sms.id.toString()) },
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) { Text("标为已读", style = MaterialTheme.typography.labelSmall) }
                                                }
                                                TextButton(
                                                    onClick = { viewModel.tools.deleteSms(sms.id.toString()) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) { Text("删除", style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error) }
                                            }
                                        }
                                    }
                                }
                            }

                            if (!isReceived) {
                                SmsAvatar("我", Modifier.padding(start = 6.dp))
                            }
                        }

                        // 未读标识
                        if (!sms.read && isReceived && !isExpanded) {
                            Text("未读", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 44.dp, top = 1.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmsAvatar(identifier: String, modifier: Modifier = Modifier) {
    val display = if (identifier == "我") "我" else identifier.takeLast(2)
    Box(
        modifier = modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(display, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── 发送短信弹窗 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmsSendSheet(
    onDismiss: () -> Unit,
    onSend: (phone: String, message: String) -> Unit
) {
    var phone by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val charCount = message.length

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = UfiCardDefaults.smsSheetTopShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            // 标题行
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("发送短信", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Surface(
                    color = if (charCount > 160) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    shape = UfiCardDefaults.inputShape
                ) {
                    Text("$charCount/160", style = MaterialTheme.typography.labelMedium,
                        color = if (charCount > 160) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // 收件人
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("收件人号码") },
                placeholder = { Text("如 10010") },
                leadingIcon = { Icon(Icons.Default.Phone, null, Modifier.size(20.dp)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                shape = UfiCardDefaults.legacyShape
            )

            Spacer(Modifier.height(10.dp))

            // 内容
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("短信内容") },
                placeholder = { Text("输入短信内容…") },
                minLines = 3,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
                shape = UfiCardDefaults.legacyShape
            )

            Spacer(Modifier.height(10.dp))

            // 快捷模板
            Text("快捷模板", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val templates = listOf("CXLL" to "查流量", "CXYE" to "查余额", "LLCX" to "流量查询")
                templates.forEach { (code, label) ->
                    SuggestionChip(
                        onClick = { message = code },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 按钮
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { onSend(phone, message) },
                    enabled = phone.isNotBlank() && message.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (charCount > 160) "发送(${(charCount + 159) / 160}条)" else "发送")
                }
            }
        }
    }
}
