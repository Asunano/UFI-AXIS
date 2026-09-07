package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 运行诊断页（2026-08-30）。
 *
 * 把 core 侧五个只读排障端点聚到一处：`/api/diagnose`、`/api/qos/status`、`/api/cache/stats`、
 * `/api/system/root-check`、`/api/shell/root`，另外挂上缓存的两个动作（清空 / 按规则失效）。
 *
 * 为什么值得单独一页而不是塞进「关于设备」：这些字段回答的是**「现在为什么不正常」**
 * （有没有 root、adbd 起没起、走的哪条特权通道、缓存是不是脏的、线程池有没有打满），
 * 和「设备是什么型号」是两类信息，混在一起两边都难找。
 *
 * 两条纪律：
 * - **默认不带 `fields=1`**。带上会让 core 逐分组向设备发查询（最多 10 组），只在用户点
 *   「检测字段覆盖率」时才开，并由 ViewModel 记住该选择。
 * - 读取失败不在页面顶部挂常驻错误条，只有动作（清缓存/失效）失败才弹 Toast ——
 *   排障页的价值在于「把还能读到的都显示出来」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnoseScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.diagnoseState.collectAsState()
    val palette = LocalResolvedPalette.current
    val scope = rememberCoroutineScope()
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showInvalidateDialog by remember { mutableStateOf(false) }
    var invalidatePattern by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.tools.loadDiagnostics() }

    UfiScreenScaffold(
        title = "运行诊断",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(
                onClick = { viewModel.tools.loadDiagnostics() },
                enabled = !state.isLoading
            ) { Icon(Icons.Default.Refresh, contentDescription = "刷新") }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.isLoading && state.diagnose == null && state.qos == null) {
                // 2026-09-03：首屏首次加载从 36dp 转圈改为骨架屏。
                // 触发条件是「正在加载且 diagnose / qos 都还没有数据」——此刻除标题栏外整页全空，
                // 转圈只表达「在等」，用户不知道会等出什么；骨架屏先把版式画出来，数据到位后
                // 内容原地落位，不会整块跳变。局部刷新（右上角刷新按钮再拉一次）不走这一支，
                // 因为那时 diagnose 已有值，页面继续显示旧数据。
                // cardCount = 3：本页共 5 张 DiagnoseCard（特权 shell / core 运行时 / 字段覆盖率 /
                // QoS 与线程池 / 响应缓存），首屏大约露出 3 张；linesPerCard = 4 对齐卡内 UfiInfoRow 条数。
                UfiSkeletonGroup(
                    modifier = Modifier.padding(top = Spacing.Medium),
                    cardCount = 3,
                    linesPerCard = 4
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Spacer(Modifier.height(8.dp))

                    // ═════ 特权 shell ═════
                    // 两个端点都读：root-check 只回布尔，shell/root 还带 uid 与 method
                    // （adb_shell / shell）—— 「有 root 但走的是哪条通道」只有后者能回答。
                    DiagnoseCard(title = "特权 shell") {
                        UfiInfoRow("Root 可用", boolLabel(state.rootCheck?.hasRoot))
                        state.shellRoot?.let { s ->
                            UfiInfoRow("uid", s.uid.ifBlank { "未知" })
                            UfiInfoRow("特权通道", s.method.ifBlank { "未知" })
                        }
                        state.diagnose?.let { d ->
                            // adbd / mobile_data 是 shell stdout 原文，取不到时 core 回 "unknown"
                            UfiInfoRow("adbd", d.adbd)
                            UfiInfoRow("移动数据开关", d.mobile_data)
                        }
                    }

                    // ═════ core 运行时 ═════
                    state.diagnose?.let { d ->
                        DiagnoseCard(title = "core 运行时") {
                            UfiInfoRow("服务器时间", FormatUtils.formatTimestamp(d.server_time))
                            // gateway 三级兜底后仍失败会是硬编码 192.168.0.1，不代表真探到了
                            UfiInfoRow("网关", d.gateway)
                            d.device_profile?.let { p ->
                                UfiInfoRow("设备 profile", profileStatusLabel(p.status))
                                UfiInfoRow("生效 profile", p.active.ifBlank { "无" })
                                if (p.status == "fallback") {
                                    Text(
                                        "配置的 profile「${p.configured}」不在注册表里，已回落默认 —— 型号大概率填错了。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.warning
                                    )
                                }
                            }
                        }
                    }

                    // ═════ 字段覆盖率（按需，会打设备） ═════
                    DiagnoseCard(title = "字段覆盖率") {
                        val coverage = state.diagnose?.field_coverage
                        if (coverage == null) {
                            Text(
                                "检测会逐分组向设备发查询（最多 10 组），比较慢，所以默认不做。",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary
                            )
                            UfiButton(
                                variant = UfiButtonVariant.Secondary,
                                text = "检测字段覆盖率",
                                onClick = { viewModel.tools.loadDiagnostics(withFieldCoverage = true) },
                                enabled = !state.isLoading,
                                loading = state.isLoading,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // 形状随 core 演进（失败时会退化成 {"error": "..."} 但仍 200），
                            // 所以原样展示而不是按固定字段解 —— 排障页要的是原文。
                            Text(
                                coverage.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary
                            )
                        }
                    }

                    // ═════ QoS / 线程池 ═════
                    state.qos?.let { q ->
                        DiagnoseCard(title = "QoS 与线程池") {
                            // cpu_temp 是毫摄氏度原始值，读失败为 0 —— 直接当摄氏度显示会得到「45000 ℃」
                            UfiInfoRow(
                                "CPU 温度",
                                q.cpuTempCelsius?.let { FormatUtils.formatTemperature(it) } ?: "不可用"
                            )
                            q.shell_qos?.root?.let {
                                UfiInfoRow("root 并发许可", "${it.available} / ${it.total}" +
                                    (it.target?.let { t -> " · 目标 $t" } ?: ""))
                            }
                            q.shell_qos?.normal?.let {
                                UfiInfoRow("普通并发许可", "${it.available} / ${it.total}")
                            }
                            q.shell_qos?.cache?.let {
                                UfiInfoRow("shell 缓存", "${it.entries} 条 · TTL ${it.ttl_ms}ms")
                            }
                            q.dynamic_pool?.let {
                                UfiInfoRow("线程池", "当前 ${it.current} · 核心 ${it.core} · 上限 ${it.max}")
                            }
                            // enabled 在 core 里是硬编码 true，不是真开关，所以这里不展示它
                            Text(
                                "QoS 总开关的真实值在「服务器配置」而不是本端点。",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // ═════ 响应缓存 ═════
                    state.cache?.let { c ->
                        DiagnoseCard(title = "响应缓存") {
                            UfiInfoRow("条目", "${c.count} / ${c.max_entries}")
                            // any_cache 是另一张表，不计入 count，也不含在体积估算里
                            UfiInfoRow("any 缓存", "${c.any_cache_count} 条（不计入上面）")
                            UfiInfoRow("体积估算", FormatUtils.formatBytes(c.total_bytes_estimate))
                            UfiInfoRow("是否可能过期", if (c.stale) "是 · WS 曾断开" else "否")
                            c.entries.firstOrNull()?.let {
                                UfiInfoRow("最旧条目", "${it.key} · ${it.age_ms / 1000}s")
                            }
                            UfiButton(
                                variant = UfiButtonVariant.Secondary,
                                text = "按规则失效",
                                onClick = { showInvalidateDialog = true },
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth()
                            )
                            UfiButton(
                                variant = UfiButtonVariant.Danger,
                                text = "清空全部缓存",
                                onClick = { showClearCacheConfirm = true },
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.Large))
                }
            }

            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }

    // 清空缓存：不是破坏性数据操作（不丢配置/历史），但之后一段时间所有页面都会真打设备、明显变慢
    UfiConfirmDialog(
        visible = showClearCacheConfirm,
        title = "清空响应缓存",
        text = "将清空 core 的全部响应缓存。配置与历史数据不受影响，但之后一段时间各页面都会重新向设备查询，会比平时慢。",
        confirmText = "清空",
        destructive = true,
        icon = rememberVectorPainter(Icons.Default.Storage),
        onConfirm = {
            showClearCacheConfirm = false
            scope.launch {
                val (ok, msg) = viewModel.tools.clearResponseCache()
                toastMessage = ToastMessage(msg, if (ok) ToastType.SUCCESS else ToastType.ERROR)
            }
        },
        onDismiss = { showClearCacheConfirm = false }
    )

    // 按规则失效：pattern 是 glob（`device:*`），不是正则
    UfiCustomDialog(
        visible = showInvalidateDialog,
        onDismiss = { showInvalidateDialog = false },
        title = "按规则失效缓存",
        icon = rememberVectorPainter(Icons.Default.Storage),
        showCloseButton = false
    ) {
        UfiDialogBody {
            Text(
                "填 glob 规则（不是正则），例如 device:* 只失效设备信息类缓存。留空会被拒绝 —— " +
                    "core 收不到规则时会回落 *，那等于清空全部。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            UfiDialogTextField(
                label = "key 规则",
                value = invalidatePattern,
                onValueChange = { invalidatePattern = it },
                placeholder = "device:*"
            )
        }
        UfiDialogActions(
            onDismiss = { showInvalidateDialog = false },
            onConfirm = {
                val p = invalidatePattern
                showInvalidateDialog = false
                scope.launch {
                    val (ok, msg) = viewModel.tools.invalidateResponseCache(p)
                    toastMessage = ToastMessage(msg, if (ok) ToastType.SUCCESS else ToastType.ERROR)
                }
            },
            confirmText = "失效",
            dismissText = "取消"
        )
    }
}

/** 诊断分区卡：统一「标题 + 卡片 + 竖排信息行」这套壳，省得每个区块重复写。 */
@Composable
private fun DiagnoseCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        UfiSectionHeader(title = title)
        Box(Modifier.fillMaxWidth().ufiStandardCard(elevation = 2.dp).padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/** 三态布尔：null = 还没读到（不能拿 false 冒充「没有 root」）。 */
private fun boolLabel(v: Boolean?): String = when (v) {
    true -> "是"
    false -> "否"
    null -> "未知（读取失败）"
}

/**
 * `device_profile.status` 四态中文化。
 * `fallback` 是「型号填错」的唯一线索 —— core 只在启动日志里打 WARN，不看这里就得翻日志。
 */
private fun profileStatusLabel(status: String): String = when (status) {
    "configured" -> "已配置且命中"
    "default" -> "未配置 · 用注册表默认"
    "fallback" -> "已回落（配置的 profile 不存在）"
    "disabled" -> "归一化已关闭"
    else -> status.ifBlank { "未知" }
}
