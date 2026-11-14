package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiTextField
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 真 PTY 终端（app 端原生，2026-10-10）。
 *
 * ## 与既有终端页的分工
 * - [AdvancedConsoleScreen]（进阶工具 → 高级控制台）：**逐条命令**执行（`POST /api/shell/exec`），
 *   有历史、结构化 stdout/stderr，适合「跑一条命令看结果」。
 * - 本页：**真 PTY 会话**（ttyd + `/ws/terminal` 反代），有 shell 提示符、能 cd、能管后台，
 *   适合「进去干活」。app 此前完全没有这个入口（仅 web 端有）。
 *
 * ## 能力边界（明确写清，避免误期待）
 * - **不做 ANSI/全屏渲染**：输出剥掉转义序列后按纯文本行显示；`vim`/`top` 这类全屏
 *   程序会显示成乱序文本 —— 本页不假装支持。
 * - 输入是**单行输入框 + 发送**，不是全键盘直传。控制键只给常用的几个（Ctrl-C、Tab）。
 *
 * ## 开关前置条件
 * core 侧 `ttyd_enabled` 默认**关**（安全边界，见 TtydRoutes KDoc）。本页进页即检查，
 * 未开时给明确指引（设置 → 设备控制 → 真 PTY 终端开关），而不是甩一个 403。
 */
@Composable
fun PtyTerminalScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
) {
    val palette = LocalResolvedPalette.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val repo = remember(viewModel) { viewModel.ptySession() }
    val connected by repo.connected.collectAsState()
    val lines by repo.lines.collectAsState()
    val error by repo.error.collectAsState()

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // 进页自动连接：先换票据（走标准头部鉴权）再握手。
    LaunchedEffect(Unit) {
        busy = true
        val ok = viewModel.tools.openPtySession { ticket -> repo.connect(scope, ticket) }
        busy = false
        if (!ok) {
            // openPtySession 已把原因写进 repo.error（403=开关未开 / 其它=启动失败）
            DebugLog.d("PTY", "auto connect failed")
        }
    }
    // 离页断开：终端是页面级会话，离开就释放（core 侧 ttyd 进程惰性保留，下次连接复用）
    DisposableEffect(Unit) {
        onDispose { repo.disconnect() }
    }

    // 输出增长时贴底
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    UfiScreenScaffold(title = "真 PTY 终端", navController = navController, showBack = true) { padding ->
        UfiPageBackground(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().padding(Spacing.Medium)) {
                // 状态条
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = if (connected) palette.success else palette.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = when {
                            busy -> "正在连接…"
                            connected -> "已连接设备 shell"
                            error != null -> error!!
                            else -> "未连接"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (connected) palette.success else palette.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(
                            onClick = { repo.clearScreen() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "清屏",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // 输出区（等宽字体；纯文本行）
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = Spacing.Small),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            color = palette.textPrimary,
                            softWrap = true
                        )
                    }
                    if (lines.isEmpty() && !busy) {
                        item {
                            Text(
                                text = error ?: "（无输出）连接成功后在上方输入命令。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textSecondary
                            )
                        }
                    }
                }

                // 输入区：单行输入框 + 发送 + 两个控制键
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    UfiTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = "",
                        placeholder = if (connected) "输入命令…" else "未连接",
                        enabled = connected,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val line = input
                            if (line.isNotBlank()) {
                                repo.sendLine(scope, line)
                                input = ""
                            }
                        },
                        enabled = connected && input.isNotBlank(),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "发送",
                            tint = if (connected && input.isNotBlank()) palette.accent else palette.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                // 控制键：Ctrl-C（中断前台命令）/ Tab（补全）/ 方向键上（history 不支持，说明在 tooltip 文案）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.Small),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    PtyControlButton("Ctrl-C") { repo.sendRaw("\u0003") }
                    PtyControlButton("Tab") { repo.sendRaw("\t") }
                    PtyControlButton("Ctrl-D") { repo.sendRaw("\u0004") }
                    PtyControlButton("滚到底") { scope.launch { listState.animateScrollToItem(maxOf(0, lines.size - 1)) } }
                }
                if (!connected && error == null) {
                    Text(
                        text = "提示：真 PTY 默认关闭，请到 设置 → 设备控制 → 打开「真 PTY 终端（ttyd）」开关",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = Spacing.Small)
                    )
                }
            }
        }
    }
}

/** 终端控制键（小号描边按钮，等宽字体）。 */
@Composable
private fun PtyControlButton(label: String, onClick: () -> Unit) {
    val palette = LocalResolvedPalette.current
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = Spacing.Medium)
    ) {
        Text(
            text = label,
            style = UfiTextStyles.label.copy(fontFamily = FontFamily.Monospace),
            color = palette.textSecondary
        )
    }
}