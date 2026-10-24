package com.ufi_axis.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.diagnose.DiagnosticApi
import com.ufi_axis.ui.diagnose.DiagnosticExportCard
import com.ufi_axis.ui.diagnose.DiagnosticRegistry
import com.ufi_axis.ui.diagnose.DiagnosticSection
import com.ufi_axis.ui.diagnose.prettyOf
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * **诊断中心**（2026-08-30 建页；2026-10-14 改造为注册表驱动）。
 *
 * ## 这一页现在分两半，判据是"跑一次有没有结论"
 * ① 上半 = [DiagnosticSection]：14 项**检查**，全部由 `DiagnosticRegistry` 登记
 *    （ENV 6 项 / NETWORK 4 项 / SERVICE 4 项）。页面这一半对"有哪些检查"一无所知，
 *    再加一项检查不用动本文件一行。
 * ② 下半 = 「高级诊断」：原页的四张卡**原样保留** —— 字段归一化开关 / 字段覆盖率 /
 *    CPU 控制能力探测 / 响应缓存（含清空与按规则失效）。
 *
 * 为什么下半没有一起搬进注册表（**这是一次刻意的取舍，不是漏做**）：
 *  · 字段覆盖率与 CPU 探测**会向设备发 shell**（前者最多 10 组查询、后者 20+ 条 shell，
 *    实测 5~15 秒），各有独立的按钮级 loading 与"改造前后各抓一份逐字比对"的用法；
 *    塞进 AUTO 组会让每次进页面都慢一截，塞进 MANUAL 组又会丢掉它们**按分组/按 policy
 *    分块渲染**的结果形态（`DiagnosticResult.rows` 是平铺的标签值行，装不下那种嵌套）；
 *  · 字段归一化是**开关**、响应缓存带**写入动作**（清空 / 失效），都不是"检查" ——
 *    `DiagnosticItem` 的契约是"跑一次 → 一个结论"，没有"改一个值"这一档。
 * 所以这一半留在页面里，但它**没有任何 id 分支**，与上半共用同一套页面骨架与 Toast。
 *
 * ## 数据加载：不再调 `loadDiagnostics()`
 * 上半的取数由描述符自己经 `RetrofitClient` 完成（`DiagnosticApi`），下半只要两份：
 * `loadFieldNormalizationSwitch()`（归一化开关真值）与 `loadCacheStats()`（缓存统计）。
 * 原来那次五端点并发拉齐的 `loadDiagnostics()` 因此**不再被本页调用** —— 它的产物
 * （diagnose / qos / root / shell）已经由上半的检查项覆盖，重复拉一遍纯属白打网络。
 * 唯一的例外是「字段覆盖率」那颗按钮：它走 `loadDiagnostics(withFieldCoverage = true)`，
 * 因为 `field_coverage` 只有带 `fields=1` 的那条路径才回，且必须由 ViewModel 记住该选择。
 *
 * ## 两条旧纪律继续有效
 * - **默认不带 `fields=1`**（见上）；
 * - 读取失败不挂常驻错误条，只有动作（清缓存 / 失效 / 下发开关）失败才弹 Toast ——
 *   排障页的价值在于"把还能读到的都显示出来"。
 *
 * 2026-09-22：字段覆盖率那张卡从「一行 `toString()`」改成「分组摘要 + 复制原始 JSON」。
 * 动机是它现在是设备适配改造的验收工具（计划书 §14.3 要求改造前后各抓一份逐字比对）。
 * 2026-10-14：页首那层骨架屏去掉 —— 上半每条检查自己就有"正在检查…"状态，
 * 再盖一层整页骨架反而会把"哪些项已经出结果"一起藏起来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnoseScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    /** 2026-10-20：分组路由的 {group}；null = 入口列表页。env / network / service / advanced */
    groupKey: String? = null
) {
    val state by viewModel.diagnoseState.collectAsState()
    val palette = LocalResolvedPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    // 检查项的描述符层需要强类型接口 + baseUrl；两者都是页面级对象，不放进 ViewModel
    // （与资源中心 / 流量报表同一取向：这份数据只有这一页消费）。
    val api = remember(prefs) { DiagnosticApi(RetrofitClient.getApiService(prefs), prefs) }

    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showInvalidateDialog by remember { mutableStateOf(false) }
    var invalidatePattern by remember { mutableStateOf("") }
    // 顶栏刷新：自增这个 tick 让上半重跑 AUTO 检查项，同时把下半那两份重读一遍。
    // 用 tick 而不是把上半的状态提到页面：上半自己知道"怎么跑"，页面只需要说"该重跑了"。
    var refreshTick by remember { mutableIntStateOf(0) }
    // 2026-10-20（用户要求）：诊断中心改成 4 个**独立路由页**（与 设置→关于 同一种整页切换），
    // 本函数既当入口列表页，也当分组页 —— 由 groupKey 决定，返回键天然回列表。
    val activeGroup: com.ufi_axis.ui.diagnose.DiagnosticGroup? = when (groupKey) {
        "env" -> com.ufi_axis.ui.diagnose.DiagnosticGroup.ENV
        "network" -> com.ufi_axis.ui.diagnose.DiagnosticGroup.NETWORK
        "service" -> com.ufi_axis.ui.diagnose.DiagnosticGroup.SERVICE
        else -> null
    }
    val showAdvanced = groupKey == "advanced"

    // 进页面读下半要的两份（理由见文件头「不再调 loadDiagnostics()」）
    LaunchedEffect(Unit) {
        viewModel.tools.loadFieldNormalizationSwitch()
        viewModel.tools.loadCacheStats()
    }

    // 开关回读 / 下发失败统一走 toast，并立刻清掉 state 里的错误，否则每次重组都会再弹一次
    // （与 DebugLogScreen 对日志开关的处理同一写法）。
    LaunchedEffect(state.fieldNormalizationError) {
        state.fieldNormalizationError?.let {
            toastMessage = ToastMessage(it, ToastType.ERROR)
            viewModel.tools.clearFieldNormalizationError()
        }
    }

    UfiScreenScaffold(
        title = activeGroup?.title ?: if (showAdvanced) "高级诊断" else "诊断中心",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(
                onClick = {
                    refreshTick++
                    // 下半那两份也一起重读：否则页面上「配置值」永远停在进页面那一刻的快照，
                    // 用户在 web 端改完回来点刷新会以为没生效。
                    viewModel.tools.loadFieldNormalizationSwitch()
                    viewModel.tools.loadCacheStats()
                }
                // 刻意不加 `enabled = !state.isLoading`：那个标志只反映「字段覆盖率」那一次
                // 慢查询，用它禁用整页刷新会让"覆盖率在跑"时连检查项都刷不了。
            ) { Icon(Icons.Default.Refresh, contentDescription = "刷新") }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                // ═════ 上半：注册表驱动的检查项（ENV / NETWORK / SERVICE）+ 导出诊断包 ═════
                if (activeGroup == null && !showAdvanced) {
                    // 2026-10-20：先按类型给入口，避免一进页面就把 20 项检查摊在面前。
                    // 版式照搬设置子页：**组标题**单独一层带横向边距，卡片直接挂页面 Column
                    //（UfiSettingsRowCard 自己带左右边距，不能再套一层横向 padding，
                    //  否则左右各多一倍 —— 之前"间距异常叠加"就是这个原因）。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.CardHorizontalMargin)
                    ) {
                        UfiSectionGroupTitle("选择诊断类型", "进入后不会自动检测，需手动开始")
                    }
                    for (group in com.ufi_axis.ui.diagnose.DiagnosticGroup.entries) {
                        UfiSettingsRowCard {
                            UfiSettingsItem(
                                title = group.title,
                                description = group.subtitle,
                                onClick = {
                                    val key = when (group) {
                                        com.ufi_axis.ui.diagnose.DiagnosticGroup.ENV -> "env"
                                        com.ufi_axis.ui.diagnose.DiagnosticGroup.NETWORK -> "network"
                                        else -> "service"
                                    }
                                    navController.navigate(Routes.diagnoseGroupRoute(key)) {
                                        launchSingleTop = true
                                    }
                                },
                                trailing = { UfiSettingsChevron() }
                            )
                        }
                    }
                    // 高级诊断是第 4 个入口：之前被误写进上面的 for 循环里，会重复渲染 3 次。
                    UfiSettingsRowCard {
                        UfiSettingsItem(
                            title = "高级诊断",
                            description = "字段归一化 / 字段覆盖率 / 响应缓存（会打设备或带写入动作）",
                            onClick = {
                                navController.navigate(Routes.diagnoseGroupRoute("advanced")) {
                                    launchSingleTop = true
                                }
                            },
                            trailing = { UfiSettingsChevron() }
                        )
                    }
            // 2026-10-24：导出只放在入口页（分组页不再有导出按钮）。
            // 入口页没有运行结果，results 传空 —— 包里会写"已跑 0 项"。
            Spacer(Modifier.height(Spacing.SectionSpacing))
            DiagnosticExportCard(
                api = api,
                items = remember(api) { DiagnosticRegistry.all(api, emptyMap(), context) },
                results = emptyMap(),
                onToast = { toastMessage = it }
            )
                } else if (activeGroup != null) {
                    DiagnosticSection(
                        api = api,
                        navController = navController,
                        refreshTick = refreshTick,
                        onToast = { toastMessage = it },
                        onlyGroup = activeGroup
                    )
                }

                // ═════ 下半：高级诊断（会打设备 / 带写入动作，全部手动触发）═════
                // 2026-10-20：收进「高级诊断」入口后只在选中时渲染（用户要求：下方也要归类成入口）。
                if (showAdvanced) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardHorizontalMargin)
                ) {
                    UfiSectionGroupTitle("高级诊断", "会打设备或带写入动作 · 手动触发")
                }

                // ═════ 字段归一化开关（2026-09-22）═════
                // 刻意紧贴在「字段覆盖率」**上方**：两张卡是同一个排障动作的两半 ——
                // 覆盖率看「归一化后命中了什么」，这个开关用来关掉归一化、回到设备原始字段名做对照。
                //
                // 三件事必须同时说清，否则它就是个误导用户的开关：
                // 1. 配置值（GET /api/config）与**运行时实际值**（/api/diagnose 的 device_profile）
                //    是两回事 —— core 只在构造组件图时读一次，改完到重启之间两者不同；
                // 2. 生效条件（重启后台服务）必须写在界面上。PUT 响应的 needs_restart
                //    **不含**这个键（那份清单只覆盖认证/端口），靠它提示等于不提示；
                // 3. 老 core 的 GET 里没有这个键 → 开关禁用并说明原因，不给一个拖了没用的假开关。
                DiagnoseCard(title = "字段归一化") {
                    val normConfig = state.fieldNormalizationEnabled
                    val normRuntime = state.diagnose?.device_profile?.normalization_enabled
                    // 可写 = 读到过真值 且 没有 PUT 在飞。两个条件缺一个都会产生假开关：
                    // 前者是"拖了 core 收不到"，后者是"连点产生互相覆盖的并发 PUT"。
                    val normWritable = normConfig != null && !state.fieldNormalizationSaving
                    UfiSettingsItem(
                        title = "启用字段归一化",
                        description = when {
                            normConfig == null && !state.fieldNormalizationRead ->
                                "还没从设备读到这一项（正在读，或连不上 core）。" +
                                    "开关位置不代表设备状态，此时不可修改。"
                            normConfig == null ->
                                "当前 core 不在 GET /api/config 里返回 field_normalization_enabled，" +
                                    "因此读不到也改不动（需要升级 core）。" +
                                    "下面一行是 /api/diagnose 报的运行时实际状态。"
                            else ->
                                // 下发中只**追加**一句，不替换整段：把说明换成「正在下发…」会让
                                // 那条重启提示在用户刚点完开关、最需要看到它的那一刻消失。
                                "关掉后 core 读侧不再按 profile 归一化，原样透出设备原始字段名，" +
                                    "用来与下面的覆盖率结果做对照排障。" +
                                    "需重启后台服务生效 —— core 只在启动时读一次这一项。" +
                                    if (state.fieldNormalizationSaving) "（正在下发…）" else ""
                        },
                        enabled = normWritable,
                        trailing = {
                            UfiSwitch(
                                // 读不到配置值时退一步显示**运行时实际值**（两者都没有才是 false，
                                // 那一支的说明文案已明说"位置不代表设备状态"）。
                                // 绝不写 `?: true` 拿 core 的默认值冒充：开关可拖但改不动 = 假开关。
                                checked = normConfig ?: normRuntime ?: false,
                                enabled = normWritable,
                                onCheckedChange = { viewModel.tools.setFieldNormalizationEnabled(it) }
                            )
                        }
                    )
                    if (normRuntime != null) {
                        UfiInfoRow(
                            "运行时实际状态",
                            if (normRuntime) "归一化生效中" else "已关闭 · 原样透传设备字段"
                        )
                    }
                    // 配置值与运行时不一致 = 用户已经改过、但还没重启。这一条是"改了为什么没反应"
                    // 的唯一答案，比上面那句通用提示更要紧，所以用 warning 色单独一行。
                    if (normConfig != null && normRuntime != null && normConfig != normRuntime) {
                        Text(
                            "配置已是「${normSwitchLabel(normConfig)}」，当前运行的组件图仍是" +
                                "「${normSwitchLabel(normRuntime)}」—— 重启后台服务后才会生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.warning
                        )
                    }
                }

                // ═════ 字段覆盖率（按需，会打设备） ═════
                // 这一块是「设备适配改造」的验收工具（计划书 §14.3）：改造前后各抓一份逐字比对，
                // 就能证明读侧行为没变。所以摘要（给眼睛看）与**原始 JSON**（给比对用）两者都要有 ——
                // 只有摘要的话，贴进文档对账时会因为排版差异误判成「行为变了」。
                DiagnoseCard(title = "字段覆盖率") {
                    val coverage = state.diagnose?.field_coverage
                    Text(
                        "检测会逐分组向设备发查询（最多 10 组），比较慢，所以默认不做；" +
                            "只在做设备适配、需要前后对账时点一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                    if (coverage != null) FieldCoverageDetail(coverage)
                    UfiButton(
                        variant = UfiButtonVariant.Secondary,
                        text = if (coverage == null) "检测字段覆盖率" else "重新检测（再打一次设备）",
                        onClick = { viewModel.tools.loadDiagnostics(withFieldCoverage = true) },
                        enabled = !state.isLoading,
                        loading = state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (coverage != null) {
                        UfiButton(
                            variant = UfiButtonVariant.Subtle,
                            text = "复制原始 JSON",
                            onClick = {
                                // 复用 app 里既有的剪贴板写法（ClipboardManager + ClipData，同
                                // DeliveryHistoryScreen / DebugLogScreen），不引第二套；
                                // 复制的是**缩进后的 field_coverage 原文**而不是屏幕上的摘要，
                                // 摘要漏掉了 queried 等字段，逐字比对会对不上。
                                runCatching {
                                    val cm = context
                                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "field_coverage",
                                            prettyOf(coverage) ?: coverage.toString()
                                        )
                                    )
                                    toastMessage = ToastMessage("已复制 field_coverage", ToastType.SUCCESS)
                                }.onFailure {
                                    toastMessage = ToastMessage("复制失败", ToastType.ERROR)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ════ CPU 控制能力探测已归诊断表 ════
                // 2026-10-14：这张卡原样保留了一段时间，而上半的诊断表里现在有一条
                // `env.cpuprobe`（core 类，两端都有）跑的是同一个端点。同一个数字在同一页出现两次，
                // 只会让人怀疑哪个才是真的 —— 与下面“QoS 不重复画”同一条判断。
                // 探测入口、结果渲染、复制原始 JSON 全部已在诊断表里，
                // 并且那边两端共用同一套判据（见 `DiagnosticRegistry.cpuProbeItem`）。
                // `ToolsModule.runCpuProbe()` 与 `DiagnoseState.cpuProbe` 保留（公开 API，不在本次改动范围），但本页不再消费。

                // ═════ QoS / 线程池 ═════
                // 上半的「QoS 与线程池」检查项已经在跑同一个端点，这里**不再重复画一份** ——
                // 同一个数字在同一页出现两次，只会让人怀疑哪个才是真的。
                // 这一段因此只剩"缓存维护"这个写入类动作。

                // ═════ 响应缓存 ═════
                state.cache?.let { c ->
                    DiagnoseCard(title = "响应缓存") {
                        UfiInfoRow("条目", "${c.count} / ${c.max_entries}")
                        // any_cache 是另一张表，不计入 count，也不含在体积估算里
                        UfiInfoRow("any 缓存", "${c.any_cache_count} 条（不计入上面）")
                        UfiInfoRow("体积估算", FormatUtils.formatBytes(c.total_bytes_estimate))
                        // 2026-10-05 P1 收尾：过期滞留观测（expired_count），健康态应远小于 count
                        UfiInfoRow("过期滞留", "${c.expired_count} / ${c.count}")
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

                }

                Spacer(Modifier.height(Spacing.Large))
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
            dismissText = "取消",
            topSpacing = Spacing.Large
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

/**
 * 归一化开关的「开启 / 关闭」措辞。
 *
 * 不复用是/否（`DiagnosticRegistry` 的 boolLabel 同款）：那条文案对比的是**两个开关位置**
 * （配置值 vs 运行时实际值），写成「配置已是『是』」根本读不出说的是开还是关。
 */
private fun normSwitchLabel(enabled: Boolean): String = if (enabled) "开启" else "关闭"

/**
 * `field_coverage` 的逐分组摘要（2026-09-22）。
 *
 * 为什么手写 JSON 遍历而不给它建 data class：这一块的形状**按 core 版本漂移**，而且有三种
 * 退化形态（见下），强类型解析一旦遇到新键就整块空白，正好在排障时最不该空白。这里只认
 * 「能认出来的键」，认不出的原样保留在「复制原始 JSON」里 —— 摘要给眼睛，原文给对账。
 *
 * 三种形态都必须如实显示，不能统一渲染成「没有数据」：
 * - `{"error": "..."}` —— 统计自身失败，HTTP 仍是 200；
 * - `{"normalization_enabled": false, "hint": "..."}` —— 归一化总开关关了，core 压根没查设备；
 * - 正常形态 `{normalization_enabled, profile_id, profile_name, groups{...}}`。
 */
@Composable
private fun FieldCoverageDetail(coverage: JsonElement) {
    val palette = LocalResolvedPalette.current
    val obj = coverage as? JsonObject
    if (obj == null) {
        // core 只会回对象；真回了别的（数组 / 裸串）也照原文贴出来，不吞掉。
        Text(coverage.toString(), style = UfiTextStyles.monoNote, color = palette.textSecondary)
        return
    }

    obj.coverageString("error")?.let {
        Text("统计失败：$it", style = UfiTextStyles.note, color = palette.error)
        return
    }

    // 关掉归一化时 core 只回一句 hint。那不是「检测失败」也不是「没有数据」，
    // 而是「没有登记表可比对」—— 原文显示出来，用户才知道要去打开 field_normalization_enabled。
    val enabled = (obj["normalization_enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
    if (!enabled) {
        Text(
            obj.coverageString("hint") ?: "core 回了 normalization_enabled=false，但没带 hint。",
            style = UfiTextStyles.note,
            color = palette.warning
        )
        return
    }

    UfiInfoRow("生效 profile", obj.coverageString("profile_id") ?: "未知")
    obj.coverageString("profile_name")?.let { UfiInfoRow("profile 名称", it) }

    val groups = obj["groups"] as? JsonObject
    if (groups.isNullOrEmpty()) {
        Text(
            "core 回了 normalization_enabled=true 但没有 groups —— core 版本可能比 app 旧。",
            style = UfiTextStyles.note,
            color = palette.warning
        )
        return
    }

    for ((name, raw) in groups) {
        val group = raw as? JsonObject ?: continue
        val registered = group.coverageInt("registered") ?: 0
        val hit = group.coverageInt("hit") ?: 0
        // queried=false = 这个 profile 没给该分组登记命令表，core 一次设备查询都没发；
        // 它与「查了但 0 命中」是两件事，混成同一句会把「表没写」误判成「设备不支持」。
        val queried = (group["queried"] as? JsonPrimitive)?.booleanOrNull ?: false
        val missing = (group["missing"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val hitSource = (group["hit_source"] as? JsonObject)
            ?.mapNotNull { (canonical, source) ->
                (source as? JsonPrimitive)?.contentOrNull?.let { "$canonical ← $it" }
            }
            .orEmpty()

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            UfiInfoRow(name, if (queried) "命中 $hit / 登记 $registered" else "未登记命令")
            if (missing.isNotEmpty()) {
                // missing 就是适配新设备时的 TODO 清单，所以用 warning 色单独一行列出来。
                Text(
                    "未命中：${missing.joinToString("、")}",
                    style = UfiTextStyles.monoNote,
                    color = palette.warning
                )
            }
            if (hitSource.isNotEmpty()) {
                Text(
                    "命中来源：${hitSource.joinToString("、")}",
                    style = UfiTextStyles.monoNote,
                    color = palette.textSecondary
                )
            }
        }
    }
}

/** 只认字符串型的键：core 把数字/布尔放进来时不要拿它的字面量当文案显示。 */
private fun JsonObject.coverageString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

/** 只认数字型的键：缺键 / 类型不对时回 null，由调用点决定显示成什么，不静默当 0。 */
private fun JsonObject.coverageInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull


private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

/** kHz 原始值转 MHz 展示；空值/非数字原样回 "?"。 */
private fun JsonObject.freqMHz(key: String): String {
    val raw = str(key)
    val khz = raw.toLongOrNull() ?: return if (raw.isBlank()) "?" else raw
    return if (khz >= 1_000_000) "%.2f GHz".format(khz / 1_000_000.0) else "${khz / 1000} MHz"
}
