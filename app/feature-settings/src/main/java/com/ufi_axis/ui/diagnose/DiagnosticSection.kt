package com.ufi_axis.ui.diagnose
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiBadge
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiExpandSection
import com.ufi_axis.ui.components.common.UfiInfoRow
import com.ufi_axis.ui.components.common.UfiSectionGroupTitle
import com.ufi_axis.ui.components.common.UfiTextField
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.util.NetworkErrorClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断中心 —— **注册表驱动的渲染区**（2026-10-14）。
 *
 * 本文件对"有哪些检查"**一无所知**：它只做四件事 ——
 * ① 遍历 [DiagnosticRegistry.all]、② 把 AUTO 项跑一遍、③ 把用户动作转给描述符、
 * ④ 把结果按 [DiagnosticGroup] 分三段画出来。所以"再加一项检查"不需要动本文件一行。
 *
 * ## 硬约束（与 `feature-resources` 的 `ResourcesScreen` 同一条）
 * 本文件里**不允许**出现 `if (id == "xxx")` 这类分支。所有差异只走描述符的字段：
 * 要不要运行按钮看 [DiagnosticItem.mode]，要不要输入框看 [DiagnosticItem.input]，
 * 要不要画跳转按钮看 [DiagnosticItem.action]，颜色看 [DiagnosticResult.status]，
 * **角标文案看 [DiagnosticItem.scope]**（core / 本端，2026-10-14 补）。
 *
 * ## AUTO 批为什么仍是**并发**（2026-10-14 复核后维持原样）
 * web 侧把自动批改成了串行队列（`useDiagnosticRun.ts` 决定二），理由是那批里有
 * `cpu-probe`（20+ 条 shell、5~15 秒）与 `full-snapshot`（4 条 root shell）会互相抢
 * core 的 root 通道许可，并发只会把彼此拖到超时，表现成"进页面一片红"。
 * 本页**不跟改**，因为两边的自动批不是一回事：
 *  · 本页的 AUTO 只有 15 条，且**重活（字段覆盖率 / CPU 探针）压根不在 AUTO 组里**
 *    （CPU 探针 2026-10-14 进了登记表，但标的是 MANUAL；字段覆盖率仍在下半的高级诊断区，
 *    各自独立按钮独立 loading）—— 真正打满设备的那两条不在；
 *  · 剩下会碰 root 通道的只有 `env.shell`（1~2 条）与 `service.snapshot`（4 条），
 *    都是秒级返回的只读命令；
 *  · 串行的代价是**确定的**（手机上每次进页面都要多等几秒），而并发的风险是**不确定的**
 *    （要设备正忙时才显形）。在没有实测数据证明这 9 条会互相拖垮之前，不拿确定的体验
 *    去换一个假想的故障。
 * ⚠ 如果以后往 AUTO 组里加"几十条 shell"级别的检查，必须**同时**把这里改成串行队列，
 * 否则会重演 web 侧那个"进页面一片红"。
 *
 * ## 为什么要折叠
 * 一次 AUTO 全跑就是十五条结论。全展开的话"哪一条不对劲"要靠滚三屏去找；
 * 折叠后每条只留**一行结论 + 上次运行时刻 + 一个状态点**，扫一眼就能定位，再展开看明细与原文。
 * 所以 [DiagnosticResult.summary] 必须自洽到"不展开也读得懂"（见它的 KDoc），
 * 而时刻只到秒 —— 它回答的是"这份结论多旧了"，不是"什么时候测的"。
 *
 * ## 为什么异常在这里兜、而不是在描述符里
 * 一处兜底胜过十四处：描述符只管"怎么读、怎么解释"，任何异常（连不上 core、404、
 * 反序列化失败）统一变成 FAIL + 一句人话。这样也保证**一条坏不会带走整页**。
 *
 * ## 导出诊断包
 * `/api/diagnose` 全量 + 各检查项结果（含**跑完时间**，2026-10-14 补）+ 最近日志拼成一个文本文件，
 * 走 SAF（[ActivityResultContracts.CreateDocument]）让用户自己选落点 —— 与 `AboutDeviceScreen`
 * 选 APK 同一个手法，不新增权限、不写死目录。
 *
 * @param refreshTick 由页面顶栏的刷新按钮自增。用 tick 而不是回调：本区自己有加载逻辑，
 *   页面只需要说"该重跑了"，不必知道怎么跑（`DiagnosticSection` 也就不必向上暴露状态）。
 */
@Composable
fun DiagnosticSection(
    api: DiagnosticApi,
    navController: NavHostController,
    refreshTick: Int,
    onToast: (ToastMessage) -> Unit,
    /** 只渲染这一个分组（2026-10-20：诊断中心改成"按类型进入"，进入某组只看该组）。 */
    onlyGroup: DiagnosticGroup? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 输入框内容：键是 item.id。放页面而不是描述符里（见 DiagInput 的 KDoc）。
    var inputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 描述符按当前输入重建：描述符是冻结数据，值通过构造参数注入（这也是 inputs 变化时
    // 唯一需要重建的东西 —— 结果与展开态都按 id 存在下面两个 map 里，不受影响）。
    // context 只给"本机状态"类检查用（`env.notifypermission` 要读系统通知权限与渠道），
    // 它是稳定引用，不必进 remember 的 key。
    val items = remember(api, inputs) { DiagnosticRegistry.all(api, inputs, context) }

    var results by remember { mutableStateOf<Map<String, DiagnosticResult>>(emptyMap()) }
    var running by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    /**
     * 每一项**跑完的时刻**（epoch ms），键是 [DiagnosticItem.id]（2026-10-14 补）。
     *
     * 为什么必须有：这一页的结论是要被**截图、贴进 issue、写进诊断包**的，
     * 而"什么时候测的"决定了它还算不算数（设备刚重启过、网刚断过，同一项检查的结论会完全不同）。
     * web 侧一直有这个（卡片头上的 `dc-time` 与包里的"跑完时间"），app 侧此前没有 ——
     * 于是同一张截图在两边读起来的信息量不一样。**只记完成时刻、不记耗时**：
     * "花了多久"对排障没有决策价值，"多旧"才有。
     */
    var finishedAt by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    var exportPending by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    /** 跑一条并把异常翻成 FAIL。**只有这里 catch**（理由见文件头）。 */
    suspend fun runOne(target: DiagnosticItem) {
        running = running + target.id
        val result = try {
            target.run()
        } catch (e: CancellationException) {
            // 离页会取消在飞的请求：取消不是失败，必须原样抛出（否则会渲染一条假 FAIL）
            throw e
        } catch (e: Exception) {
            DiagnosticResult(DiagStatus.FAIL, "执行失败：${describeFailure(e)}")
        }
        results = results + (target.id to result)
        // 2026-10-24（G1）：失败项自动展开原始输出，用户不必再点一次「查看」。
        if (result.status == DiagStatus.FAIL) expanded = expanded + target.id
        // 时刻与结果同一次写回：分两次写会让卡片出现"有结论但没有时间"的中间帧。
        // 取消路径（上面那条 rethrow）刻意**不**记时刻 —— 那一项这次没有结论，记了反而是假信息。
        finishedAt = finishedAt + (target.id to System.currentTimeMillis())
        running = running - target.id
    }

    /**
     * 手动的"开始检测"（2026-10-20，用户要求：**进入页面不再自动检测**）。
     *
     * 只跑 AUTO 项 —— MANUAL 项会打设备或等网络超时，必须逐项手动触发。
     * 不再用 `LaunchedEffect(refreshTick)` 自动跑：那会让"点进诊断中心"变成一次隐式的
     * 全量探测（用户明确不希望进页面就检测）。
     */
    fun runAutoFor(groups: Set<DiagnosticGroup>) {
        val autos = items.filter { it.mode == RunMode.AUTO && it.group in groups }
        if (autos.isEmpty()) return
        scope.launch {
            running = running + autos.map { it.id }
            val fresh = coroutineScope {
                autos.map { descriptor ->
                    async {
                        Triple(descriptor.id, runSafely(descriptor), System.currentTimeMillis())
                    }
                }.awaitAll()
            }
            results = results + fresh.associate { it.first to it.second }
            finishedAt = finishedAt + fresh.associate { it.first to it.third }
            running = running - autos.map { it.id }.toSet()
        }
    }

    // SAF：让用户选诊断包的落点。文本在点按钮时就拼好（见 startExport），
    // 因为选择器返回时协程已经结束了。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = exportPending
        exportPending = null
        if (uri == null) {
            onToast(ToastMessage("已取消导出", ToastType.INFO))
            return@rememberLauncherForActivityResult
        }
        if (text == null) return@rememberLauncherForActivityResult
        val failure = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("无法打开所选位置")
        }.exceptionOrNull()
        onToast(
            if (failure == null) ToastMessage("诊断包已导出", ToastType.SUCCESS)
            else ToastMessage("写入失败：${failure.message ?: "未知原因"}", ToastType.ERROR)
        )
    }

    fun startExport() {
        scope.launch {
            exporting = true
            // 最近日志：读不到也要导出（其余部分仍有价值），所以这里把失败原因**写进包里**
            // 而不是中断导出 —— 一份缺了日志的诊断包远好过一句"导出失败"。
            val logs = runCatching { api.service.getDebugLogs(limit = 200).logs }
                .getOrElse { listOf("（读取失败：${it.message ?: "未知原因"}）") }
            exportPending = buildDiagnosticBundle(context, api, items, results, finishedAt, logs)
            exporting = false
            exportLauncher.launch(defaultExportFileName())
        }
    }

    Column(modifier = modifier) {
        // 归属角标的图例（2026-10-14 补）。**必须有这一句**：角标只有 "core" / "本端" 两个词，
        // 不解释一句没人知道它回答的是"**另一端有没有这一项**"（而不是"这一项重不重要"）。
        // 放在三段之前只出现一次；写在每张卡上重复二十次只会变成噪声。
        Text(
            text = "卡片上的角标说明这一项的归属：core = 查设备 / core 自己的状态，" +
                "App 与网页端都有；本端 = 只在 App 才有（本机通知授权）。",
            style = UfiTextStyles.note,
            color = LocalResolvedPalette.current.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.CardHorizontalMargin, vertical = Spacing.Small),
        )
        for (group in DiagnosticGroup.entries.filter { onlyGroup == null || it == onlyGroup }) {
            val groupItems = items.filter { it.group == group }
            if (groupItems.isEmpty()) continue
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.CardHorizontalMargin)
            ) {
                UfiSectionGroupTitle(group.title, group.subtitle)
            // 2026-10-24（G1）：组结论条 —— 通过/失败/总数 + 「重跑本组」。
            // 原来是一个孤零零的「开始检测」按钮，进页面看不出这一组整体怎么样。
            val failed = groupItems.count { results[it.id]?.status == DiagStatus.FAIL }
            val passed = groupItems.count { results[it.id]?.status == DiagStatus.OK }
            val autoRunning = groupItems.any { it.mode == RunMode.AUTO && it.id in running }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val palette = LocalResolvedPalette.current
                Text(
                    text = buildString {
                        append("$passed 通过 · ${groupItems.size} 项")
                        if (failed > 0) append(" · $failed 失败")
                    },
                    style = UfiTextStyles.note,
                    color = if (failed > 0) palette.error else palette.textSecondary
                )
                Spacer(Modifier.weight(1f))
                UfiButton(
                    text = if (autoRunning) "检测中…" else "重跑本组",
                    onClick = { runAutoFor(setOf(group)) },
                    enabled = !autoRunning && groupItems.any { it.mode == RunMode.AUTO },
                    variant = UfiButtonVariant.Primary,
                    size = UfiButtonSize.Small
                )
            }

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {
                    for (descriptor in groupItems) {
                        DiagnosticCard(
                            item = descriptor,
                            result = results[descriptor.id],
                            running = descriptor.id in running,
                            expanded = descriptor.id in expanded,
                            finishedAt = finishedAt[descriptor.id] ?: 0L,
                            onToggle = {
                                expanded = if (descriptor.id in expanded) {
                                    expanded - descriptor.id
                                } else {
                                    expanded + descriptor.id
                                }
                            },
                            onRun = { scope.launch { runOne(descriptor) } },
                            onInputChange = { inputs = inputs + (descriptor.id to it) },
                            onOpenRoute = { route ->
                                navController.navigate(route) { launchSingleTop = true }
                            },
                            onToast = onToast,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.SectionSpacing))
            }
        }
}
}


    // 2026-10-24：导出移到诊断中心入口页（DiagnosticExportCard），分组页不再渲染 ——
    // 原来三个分组页各有一份，而 results 是各页自己的 state，导出的"本次结果"互相不一致。

/**
 * 单条检查的卡片。
 *
 * 结构固定为「状态点 + **归属角标** + 标题 + 一行结论（常显）+ 上次运行时刻」+「参数输入（若有）」+
 * 「折叠区：说明 / 明细 / 原文」。所有差异都来自 [item] 的字段，没有一处按 id 分支。
 *
 * @param finishedAt 这一项最近一次跑完的时刻（epoch ms）；0 = 从没跑过。
}

 *   与 [result] 分开传而不是塞进 [DiagnosticResult]：它是**页面的运行时状态**，
 *   不是 core 给的结论 —— 混进结果类型会让导出、缓存、比较三处都要带着一个与内容无关的字段。
 */
@Composable
private fun DiagnosticCard(
    item: DiagnosticItem,
    result: DiagnosticResult?,
    running: Boolean,
    expanded: Boolean,
    finishedAt: Long,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onInputChange: (String) -> Unit,
    onOpenRoute: (String) -> Unit,
    onToast: (ToastMessage) -> Unit,
) {
    val palette = LocalResolvedPalette.current
    val context = LocalContext.current
    var showRaw by remember(item.id) { mutableStateOf(false) }
    // ── 写动作的两次状态（2026-10-14）──
    // `pendingWrite` 非空 = 确认框正开着。用"待执行的动作"本身当可见性开关（而不是另开一个
    // Boolean）：两者分开放就会出现"框还开着、动作已经换了"的中间态。
    var pendingWrite by remember(item.id) { mutableStateOf<DiagWriteAction?>(null) }
    var writing by remember(item.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val statusColor = when (result?.status) {
        DiagStatus.OK -> palette.success
        DiagStatus.WARN -> palette.warning
        DiagStatus.FAIL -> palette.error
        DiagStatus.INFO, null -> palette.textSecondary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .ufiStandardCard(elevation = 2.dp)
            .padding(Spacing.XLarge)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态点：折叠时它是"哪一条不对劲"的唯一线索，所以颜色之外不给它更多含义。
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(Spacing.Large))
                // 归属角标（2026-10-14 补）：回答"这一项**另一端有没有**"，文案来自描述符的
                // [DiagnosticItem.scope] —— **不认 id、也不认 group**（跨端自检脚本核对的
                // 正是同一个字段）。两档**刻意同色**：颜色在本页已经全部给了状态点与结论行，
                // 再给角标一套配色，会让人以为角标也是状态。
                UfiBadge(text = item.scope.label)
                Spacer(Modifier.width(Spacing.Large))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = UfiTextStyles.listItemTitle,
                        color = palette.textPrimary,
                    )
                    Text(
                        // 折叠时只显示这一行 —— 所以它在 result 为空时也要说人话，
                        // 而不是留白（留白会让人以为"这一项不存在"）。
                        text = when {
                            running -> "正在检查…"
                            result != null -> result.summary
                            else -> "还没跑过"
                        },
                        style = UfiTextStyles.note,
                        color = if (result == null && !running) palette.textSecondary else statusColor,
                    )
                    // 上次运行时刻（2026-10-14 补）：截图 / 贴 issue 时它是"这份结论算不算数"的唯一线索。
                    // 只在跑过之后出现；**折叠态也显示** —— 藏进折叠区就等于没有（见上面的理由）。
                    if (finishedAt > 0) {
                        Text(
                            text = "上次运行 ${cardTime(finishedAt)}",
                            style = UfiTextStyles.caption,
                            color = palette.textSecondary,
                        )
                    }
                }
                // 行尾最多三颗按钮，各自独立：
                //  · 跳转入口（描述符的 [DiagnosticItem.action]，如"去日志页"）——
                //    它与"这一项是不是检查"无关，检查照跑、入口照给；
                //  · 写入动作（**结果**里的 [DiagnosticResult.write]）—— 只在这次结论说
                //    "有事可做"时才存在（如"缺 3 项权限 → 补全"），补完之后它会自己消失；
                //  · 手动项的运行按钮（自动项不给：它已经跑过了，重跑由页面顶栏的刷新统一负责）。
                // 刻意不是 `when`：三者可以同时存在（"要填参数 + 有去处 + 有得补"是合理组合）。
                item.action?.let { action ->
                    Spacer(Modifier.width(Spacing.Large))
                    UfiButton(
                        text = action.label,
                        variant = UfiButtonVariant.Subtle,
                        size = UfiButtonSize.Small,
                        onClick = { onOpenRoute(action.route) },
                    )
                }
                result?.write?.let { write ->
                    Spacer(Modifier.width(Spacing.Large))
                    UfiButton(
                        text = write.label,
                        // destructive 用 Secondary + 警示色文案已由按钮组件承担不了，
                        // 这里统一用 Subtle/Secondary 两级：写动作靠**确认框**而不是颜色来减速。
                        variant = if (write.destructive) UfiButtonVariant.Secondary else UfiButtonVariant.Subtle,
                        size = UfiButtonSize.Small,
                        enabled = !writing,
                        loading = writing,
                        onClick = { pendingWrite = write },
                    )
                }
                if (item.mode == RunMode.MANUAL) {
                    Spacer(Modifier.width(Spacing.Large))
                    UfiButton(
                        text = if (result == null) "运行" else "重跑",
                        variant = UfiButtonVariant.Subtle,
                        size = UfiButtonSize.Small,
                        // 需要参数的项：没填就**禁用**，而不是跑一次再报错
                        enabled = !running && item.input?.value?.isBlank() != true,
                        loading = running,
                        onClick = onRun,
                    )
                }
            }

            // 参数输入：常显（不折叠）。填参数是运行的前提，藏进折叠区会让
            // "运行按钮为什么是灰的"变成一个要用户自己猜的问题。
            item.input?.let { input ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    Text(
                        text = input.label,
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary,
                    )
                    UfiTextField(
                        value = input.value,
                        onValueChange = onInputChange,
                        label = input.label,
                        placeholder = input.placeholder,
                    )
                }
            }

            UfiExpandSection(expanded = expanded, spacing = Spacing.Medium) {
                Text(
                    text = item.desc,
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                )
                result?.rows?.forEach { (label, value) -> UfiInfoRow(label, value) }
                val raw = result?.raw
                if (raw != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                        UfiButton(
                            text = "复制原始输出",
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small,
                            onClick = {
                                // 复用 app 里既有的剪贴板写法（ClipboardManager + ClipData，
                                // 同 DeliveryHistoryScreen / DebugLogScreen），不引第二套
                                runCatching {
                                    val cm = context
                                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText(item.id, raw))
                                    onToast(ToastMessage("已复制 ${item.name} 的原始输出", ToastType.SUCCESS))
                                }.onFailure {
                                    onToast(ToastMessage("复制失败", ToastType.ERROR))
                                }
                            },
                        )
                        UfiButton(
                            text = if (showRaw) "收起原文" else "展开原文",
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small,
                            onClick = { showRaw = !showRaw },
                        )
                    }
                    UfiExpandSection(expanded = showRaw, spacing = Spacing.Medium) {
                        // 等宽 + 可横向滚动的原文。用 Text 而不是可滚动容器：
                        // 这一页本身在 verticalScroll 里，嵌套一个 verticalScroll 会与外层
                        // 抢手势，而且 core 对 stdout 已有 64 行上限，不会长到失控。
                        Text(
                            text = raw,
                            style = UfiTextStyles.monoNote,
                            color = palette.textSecondary,
                        )
                    }
                }
            }
        }
    }

    // ── 写动作的二次确认（2026-10-14）──
    //
    // 为什么确认框放在卡片内部而不是页面级：它是**这一条结论**的动作，触发点也在这张卡上；
    // 提到页面级就要把"当前待确认的是哪一条"变成一个页面状态，而页面本来对具体检查一无所知
    //（见文件头的硬约束）。
    //
    // 三件事的顺序不能换：
    //  ① 先锁按钮（`writing`）—— 连点两次会发两条 `pm grant`，第二次的提示会盖掉第一次的；
    //  ② `run()` 的结果**必须弹出来**（成功与失败都要有话说）：写动作改的是设备状态，
    //     只关掉弹窗等于让用户自己去猜成没成；
    //  ③ 成功之后**重跑这一条**：卡片上那份结论是写之前的，不重跑就会看到
    //     "权限已补全"旁边还挂着"缺 3 项权限"（同一个数字两种说法）。
    pendingWrite?.let { write ->
        UfiConfirmDialog(
            title = write.confirmTitle,
            text = write.confirmBody,
            confirmText = if (write.destructive) "确认执行" else "确认",
            destructive = write.destructive,
            onDismiss = { if (!writing) pendingWrite = null },
            onConfirm = {
                pendingWrite = null
                scope.launch {
                    writing = true
                    val message = try {
                        ToastMessage(write.run(), ToastType.SUCCESS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ToastMessage("执行失败：${describeFailure(e)}", ToastType.ERROR)
                    }
                    writing = false
                    onToast(message)
                    onRun()
                }
            },
        )
    }
}

// ────────────────────────── 执行与导出 ──────────────────────────

/**
 * 跑一条并吞掉异常。
 *
 * 与 [DiagnosticCard] 里那个 `runOne` 分开的原因：这里返回结果而不是写 state，
 * 供 `LaunchedEffect` 里并发跑 AUTO 项时用（并发收集完再一次性写回，避免十四条各自触发重组）。
 */
private suspend fun runSafely(item: DiagnosticItem): DiagnosticResult = try {
    item.run()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    DiagnosticResult(DiagStatus.FAIL, "执行失败：${describeFailure(e)}")
}

/**
 * 把一次异常翻成"能读懂的一句话"。
 *
 * `retrofit2.HttpException` **不在本模块的编译类路径上**（`:app:data` 以 `implementation`
 * 声明 retrofit，不透传给下游），而本仓禁止为了一条错误分支新增依赖 —— 所以只按类名认它，
 * 取 `code()` 这一个稳定公开成员。判定顺序：传输层 → HTTP 状态码 → 兜底。
 * （与 `feature-resources` 的 `describeFailure` 同一手法，只是那边读的是响应体里的中文原因。）
 */
private fun describeFailure(e: Throwable): String {
    NetworkErrorClassifier.describe(e)?.let { return it }
    if (e.javaClass.name == "retrofit2.HttpException") {
        val code = runCatching { e.javaClass.getMethod("code").invoke(e) as? Int }.getOrNull()
        return "HTTP ${code ?: "?"} —— core 拒绝了这个请求（版本过旧时常见）"
    }
    return e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
}

/**
 * 拼诊断包文本。
 *
 * ## 为什么顺序是"检查结果 → 全量快照 → 日志"而不是反过来
 * 拿到一份诊断包的人第一眼要看的是"结论"（哪些项 FAIL），而不是几千行原文；
 * 原文放在后面用于逐字对账，日志放最后（最长、最少有人逐行读）。
 *
 * ## 为什么快照要**再拉一次**而不是复用检查项里那一份
 * 导出是"把现场带走"的动作，那一刻的 `/api/diagnose` 才是现场。复用上面那份会让
 * "点导出"与"跑检查"之间隔的时间变成数据空洞 —— 而排障时那几分钟往往正是关键区间。
 *
 * ## 为什么每一项都写"跑完时间"（2026-10-14 补）
 * 收包的人拿到的是**一张纸**，看不到屏幕。同一份结论在"刚跑完"与"半小时前跑完"下
 * 含义完全不同（设备中途重启过、网中途断过），所以每条的时效必须跟着结论一起走 ——
 * web 侧的诊断包一直有这一行（`diagnosticExport.ts` 的"跑完时间"），两端对齐。
 */
internal suspend fun buildDiagnosticBundle(
    context: Context,
    api: DiagnosticApi,
    items: List<DiagnosticItem>,
    results: Map<String, DiagnosticResult>,
    finishedAt: Map<String, Long>,
    logs: List<String>,
): String {
    val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "未知"
    val now = bundleTime(System.currentTimeMillis())
    val snapshot = runCatching { api.rawGetJson("/api/diagnose") }.getOrNull()

    return buildString {
        appendLine("UFI-AXIS 诊断包")
        appendLine("导出时间：$now")
        appendLine("App 版本：$appVersion")
        appendLine("设备地址：${api.prefs.baseUrl}")
        appendLine("检查项：${items.size} 项（已跑 ${results.size} 项）")
        appendLine()

        appendLine("════════ 检查项结果 ════════")
        for (item in items) {
            val result = results[item.id]
            appendLine()
            appendLine("── [${item.group.title}] ${item.name} ──")
            if (result == null) {
                // 没跑过的项也要出现：否则"少了一条"与"这一项不存在"在包里分不出来
                appendLine("状态：未运行")
                continue
            }
            appendLine("状态：${result.status}")
            // 时刻与结论成对出现；理论上两者同一次写回（见 runOne），这里仍分开取值 ——
            // 真出现"有结论没时刻"时宁可少一行，也不要编一个时刻出来。
            finishedAt[item.id]?.let { appendLine("跑完时间：${bundleTime(it)}") }
            appendLine("结论：${result.summary}")
            for ((label, value) in result.rows) appendLine("· $label: $value")
            result.raw?.let {
                appendLine("原始输出：")
                appendLine(it)
            }
        }

        appendLine()
        appendLine("════════ /api/diagnose 全量快照 ════════")
        appendLine(prettyOf(snapshot) ?: "（读取失败）")

        appendLine()
        appendLine("════════ 最近日志（core /api/debug-logs?limit=200）════════")
        if (logs.isEmpty()) appendLine("（core 没有返回任何日志）") else logs.forEach { appendLine(it) }
    }
}

/**
 * 卡片上那个"上次运行"时刻。**只到秒、不带日期** —— 屏幕上要回答的是"刚刚 / 几分钟前"，
 * 日期会把标题行挤到换行，而真需要日期的场景是诊断包（见 [bundleTime]）。
 *
 * 每次现建一个 `SimpleDateFormat`（而不是缓存一个）：本仓既有写法如此（本文件下面那两处），
 * 而 `SimpleDateFormat` 不是线程安全的 —— 为省一次对象创建去共享它，是拿并发正确性换性能。
 */
private fun cardTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

/** 诊断包里的时刻：**带日期**。包是要发给别人看的，"14:32:05" 脱离日期没有意义。 */
private fun bundleTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

/** 默认文件名（用户可在 SAF 选择器里改）：带时间戳，多次导出不会互相覆盖。 */
internal fun defaultExportFileName(): String =
    "ufi-axis-diagnose-" +
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date()) + ".txt"
