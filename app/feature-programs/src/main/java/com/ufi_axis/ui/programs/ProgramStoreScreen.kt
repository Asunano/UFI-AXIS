package com.ufi_axis.ui.programs

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.ProgramItemDto
import com.ufi_axis.data.model.ProgramOpenDto
import com.ufi_axis.data.model.ProgramSettingsRequest
import com.ufi_axis.data.model.ProgramStatusDto
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.FormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * App 端「应用商店」。
 *
 * 页面只依赖 :app:data + :app:ui，状态自持；入口在工具页（Routes.DETAIL_PROGRAM_STORE）。
 * 与「应用管理」的 APK 是两件事：这里安装/启停的是 core 拉起的第三方 Linux 程序。
 *
 * 单槽约束与 Web 一致：同一时刻最多一个程序运行；其他程序的启动按钮禁用，
 * 页面顶部给出提示。写操作只要求已配对身份（Bearer + 设备签名）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgramStoreScreen(navController: NavHostController) {
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val api = remember(prefs) { RetrofitClient.getApiService(prefs) }
    val scope = rememberCoroutineScope()
    val palette = LocalResolvedPalette.current

    var programs by remember { mutableStateOf<List<ProgramItemDto>>(emptyList()) }
    var nativeExecOk by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<ProgramStatusDto?>(null) }
    // 本次 App 内发起的安装/更新任务：用于 completion/failure 只弹一次公共 toast。
    var pendingTaskId by remember { mutableStateOf<String?>(null) }
    var pendingTaskWasUpdate by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    var detailId by remember { mutableStateOf<String?>(null) }
    // 详情里的「占用」需要 core 递归扫目录，列表接口故意不给（每 10s 轮询，扫不起）。
    // 所以打开详情时单独拉一次 GET /api/programs/{id}，回来后就地替换这一项。
    var detailLoading by remember { mutableStateOf(false) }
    // 详情接口的磁盘占用**单独缓存**：整项替换会被 5s 一次的列表轮询覆盖回 0。
    var detailUsedBytes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var installTarget by remember { mutableStateOf<ProgramItemDto?>(null) }
    var installIsUpdate by remember { mutableStateOf(false) }
    var installMode by remember { mutableStateOf("direct") }
    // 内容目录：只有声明了 content_enabled 的程序才有这一项；volumes 由 core 枚举。
    var installVolumes by remember { mutableStateOf<List<com.ufi_axis.data.model.ProgramVolumeDto>>(emptyList()) }
    var installContentRoot by remember { mutableStateOf("") }
    var riskAck by remember { mutableStateOf(false) }

    var logsTarget by remember { mutableStateOf<ProgramItemDto?>(null) }
    var logsText by remember { mutableStateOf("") }
    var logsLoading by remember { mutableStateOf(false) }

    var secretTarget by remember { mutableStateOf<ProgramItemDto?>(null) }
    var secretValue by remember { mutableStateOf("") }
    var resetTarget by remember { mutableStateOf<ProgramItemDto?>(null) }

    var uninstallTarget by remember { mutableStateOf<ProgramItemDto?>(null) }
    /** 卸载确认框里「同时删除日志（彻底卸载）」的勾选状态。 */
    var uninstallPurgeLogs by remember { mutableStateOf(false) }

    // ── 程序设置（2026-11-10）：访问方式 / 端口 / 开机自启 ──
    // 只存 id 不存快照：列表每 5s 刷新，停止程序后弹窗里的字段要跟着解禁。
    var settingsId by remember { mutableStateOf<String?>(null) }
    var settingsMode by remember { mutableStateOf("direct") }
    var settingsInternal by remember { mutableStateOf("") }
    var settingsExternal by remember { mutableStateOf("") }
    var settingsAutostart by remember { mutableStateOf(false) }
    var settingsSaving by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }

    val detail = detailId?.let { id -> programs.firstOrNull { it.id == id } }
    // 多实例：每个程序各自判断是否在跑，不再有"全局唯一活动程序"的概念
    fun isActive(p: ProgramItemDto): Boolean =
        p.installed && (p.phase == "running" || p.phase == "starting" || p.phase == "stopping")
    /** 设置弹窗当前目标（按 id 从最新列表里取，停止后自动解禁端口字段）。 */
    val settingsTarget = settingsId?.let { id -> programs.firstOrNull { it.id == id } }
    val taskBusy = status?.busy == true
    // 只把「进行中」的任务交给对应卡片画进度条；done/failed 只走 toast，不再占页面顶部。
    val activeTask = status?.takeIf { it.id.isNotBlank() && it.busy }

    LaunchedEffect(detailId) {
        val id = detailId ?: return@LaunchedEffect
        if (detailUsedBytes.containsKey(id)) return@LaunchedEffect
        detailLoading = true
        runCatching { api.getProgram(id) }
            .onSuccess { item -> detailUsedBytes = detailUsedBytes + (id to item.usedBytes) }
            .onFailure { if (programs.none { it.id == id }) detailId = null }
        detailLoading = false
    }

    // 生命周期门控：退后台停轮询，回前台立刻续上。
    var resumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            resumed = false
        }
    }

    suspend fun loadOnce(showError: Boolean = true) {
        try {
            val list = api.listPrograms()
            programs = list.programs
            nativeExecOk = list.nativeExec
            if (showError) error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (showError || programs.isEmpty()) error = e.message ?: "读取程序列表失败"
        } finally {
            loading = false
        }
        // 列表接口的 used_bytes 恒为 0（它不扫目录）。已安装的程序补一次详情接口，
        // 把**整体占用**缓存下来；每个程序只补一次，之后由安装/卸载动作主动重置缓存。
        programs.filter { it.installed && !detailUsedBytes.containsKey(it.id) }
            .forEach { p ->
                runCatching { api.getProgram(p.id) }
                    .onSuccess { item -> detailUsedBytes = detailUsedBytes + (p.id to item.usedBytes) }
            }
        runCatching { api.getProgramStatus() }
            .onSuccess { status = it }
        if (detailId != null && programs.none { it.id == detailId }) detailId = null
    }

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            loadOnce()
            delay(if (status?.busy == true) 1200L else 5000L)
        }
    }

    fun toast(text: String, type: ToastType = ToastType.INFO) {
        toastMessage = ToastMessage(text, type)
    }

    /**
     * 运行中点击被锁定的配置项时的提示（2026-11-10）。
     *
     * 设置弹窗里运行中会把端口/访问方式置灰；只写一行静态 warning 用户仍会去点，
     * 点了没反应像坏了。这里在**被锁的具体区域**上盖一层透明点击层，点哪都弹同一条 toast。
     * 走 UfiToastHost（会挂到栈顶 Dialog 的 window），因此在弹窗里也看得见。
     */
    fun warnSettingsLocked() {
        toast("程序正在运行，请先停止程序再修改访问方式与端口", ToastType.WARNING)
    }

    // 安装/更新任务的终态：用公共 toast 提醒一次，不再在页面顶部画自定义 TaskCard。
    LaunchedEffect(status?.id, status?.phase, status?.message) {
        val t = status ?: return@LaunchedEffect
        if (t.id != pendingTaskId) return@LaunchedEffect
        if (t.phase != "done" && t.phase != "failed") return@LaunchedEffect
        val name = programs.firstOrNull { it.id == t.id }?.name ?: t.id
        if (t.phase == "failed") {
            val detail = t.message.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
            toast("安装失败：$name$detail", ToastType.ERROR)
        } else {
            val version = t.message.removePrefix("已安装").trim()
            val suffix = version.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            toast(
                if (pendingTaskWasUpdate) "更新完成：$name$suffix" else "安装完成：$name$suffix",
                ToastType.SUCCESS,
            )
        }
        pendingTaskId = null
        pendingTaskWasUpdate = false
        loadOnce(false)
    }

    fun actionError(e: Exception, fallback: String) {
        toast(e.message ?: fallback, ToastType.ERROR)
    }

    fun start(p: ProgramItemDto) {
        if (actionBusy || taskBusy) return
        if (isActive(p)) {
            toast("${p.name} 正在运行")
            return
        }
        scope.launch {
            actionBusy = true
            try {
                api.startProgram(p.id)
                toast("正在启动 ${p.name}")
                loadOnce(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError(e, "启动失败")
            } finally {
                actionBusy = false
            }
        }
    }

    fun stop(p: ProgramItemDto) {
        if (actionBusy) return
        scope.launch {
            actionBusy = true
            try {
                api.stopProgram(p.id)
                toast("已停止 ${p.name}", ToastType.SUCCESS)
                loadOnce(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError(e, "停止失败")
            } finally {
                actionBusy = false
            }
        }
    }

    fun openProgram(p: ProgramItemDto) {
        if (actionBusy) return
        scope.launch {
            actionBusy = true
            try {
                val info: ProgramOpenDto = api.openProgram(p.id)
                val host = prefs.effectiveHost
                // 经后台转发：core 返回一次性入口 /apps/{id}/enter?t=...。
                // 系统浏览器先访问 core（8088）换 Gateway cookie，再 302 到外部端口；
                // 不能直接把 gateway 端口丢给浏览器 —— 浏览器没有 App 的 OkHttp cookie，会 401。
                if (!info.direct) {
                    if (info.enterPath.isBlank()) {
                        toast("当前 Core 不支持在 App 内打开转发程序，请更新 Core", ToastType.ERROR)
                        return@launch
                    }
                    val url = "http://$host:${prefs.serverPort}${info.enterPath}"
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    return@launch
                }
                val url = "${info.scheme.ifBlank { "http" }}://$host:${info.port}${info.path.ifBlank { "/" }}"
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError(e, "打开失败")
            } finally {
                actionBusy = false
            }
        }
    }

    fun openInstall(p: ProgramItemDto, update: Boolean) {
        // 详情弹窗里点「更新/安装」时先收起详情：两个全屏 Dialog 叠在一起，
        // 返回栈和遮罩都会打架（2026-11-10 用户反馈）。
        if (detailId == p.id) detailId = null
        if (taskBusy) {
            toast("已有安装任务在进行")
            return
        }
        if (isActive(p)) {
            toast("${p.name} 正在运行：安装/更新前请先停止它")
            return
        }
        installTarget = p
        installIsUpdate = update
        installMode = p.accessMode.ifBlank { "direct" }
        installContentRoot = ""
        riskAck = false
        if (p.contentEnabled) {
            scope.launch {
                runCatching { api.getProgramVolumes() }
                    .onSuccess { installVolumes = it.volumes }
            }
        }
    }

    fun performInstall() {
        val p = installTarget ?: return
        if (!riskAck || actionBusy || taskBusy) return
        scope.launch {
            actionBusy = true
            // 清掉上一条 done/failed 快照：否则小安装包可能在第一次轮询前就完成，
            // phase 从旧 done 到新 done 没有可见跳变，completion toast 永远不触发。
            status = null
            pendingTaskId = p.id
            pendingTaskWasUpdate = installIsUpdate
            // 开始提示交给卡片下方进度条，不再弹 toast：安装弹窗正在关闭，
            // 此时弹 toast 会挂到将死的 Dialog 窗口（用户看不到）。完成/失败仍走公共 toast。
            try {
                api.installProgram(
                    p.id,
                    // 数据位置固定内部存储（两端都不再提供选择）
                    "internal",
                    if (installIsUpdate) p.accessMode else installMode,
                    // 只有新装有意义；更新时内容目录跟着已有的走
                    if (!installIsUpdate && p.contentEnabled) installContentRoot else null,
                )
                installTarget = null
                // 开始阶段只用卡片下方进度条表达；安装弹窗关闭时的 toast 容易挂到
                // 即将销毁的 Dialog 窗口，真机上会“动画在跑但看不见”。完成/失败再弹公共 toast。
                loadOnce(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 请求没被受理（409/网络失败）：不会有任务终态，清掉等待标记。
                pendingTaskId = null
                pendingTaskWasUpdate = false
                actionError(e, if (installIsUpdate) "更新失败" else "安装失败")
            } finally {
                actionBusy = false
            }
        }
    }

    fun toggleAccess(p: ProgramItemDto) {
        if (actionBusy || taskBusy) return
        scope.launch {
            actionBusy = true
            try {
                val mode = if (p.accessMode == "direct") "gateway" else "direct"
                api.setProgramAccessMode(p.id, mode)
                toast(if (mode == "gateway") "已切换为经后台转发，下次启动生效" else "已切换为直连，下次启动生效")
                loadOnce(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError(e, "切换访问方式失败")
            } finally {
                actionBusy = false
            }
        }
    }

    fun openSettings(p: ProgramItemDto) {
        if (detailId == p.id) detailId = null
        settingsId = p.id
        settingsMode = p.accessMode.ifBlank { "direct" }
        settingsInternal = p.internalPort.takeIf { it > 0 }?.toString().orEmpty()
        settingsExternal = p.externalPort.takeIf { it > 0 }?.toString().orEmpty()
        settingsAutostart = p.autostart
        settingsError = null
    }

    fun saveSettings() {
        val p = settingsTarget ?: return
        if (settingsSaving) return
        val internalPort = settingsInternal.toIntOrNull()
        if (internalPort == null || internalPort !in 1024..65535) {
            settingsError = "内部端口必须在 1024–65535"
            return
        }
        val externalPort = settingsExternal.toIntOrNull() ?: 0
        if (settingsMode == "gateway") {
            if (externalPort !in 1024..65535) {
                settingsError = "外部端口必须在 1024–65535"
                return
            }
            if (externalPort == internalPort) {
                settingsError = "外部端口不能与内部端口相同"
                return
            }
        }
        scope.launch {
            settingsSaving = true
            settingsError = null
            try {
                api.updateProgramSettings(
                    p.id,
                    ProgramSettingsRequest(
                        accessMode = settingsMode,
                        internalPort = internalPort,
                        externalPort = externalPort,
                        autostart = settingsAutostart,
                    )
                )
                settingsId = null
                // 等弹窗离场再弹 toast，避免挂在将死的 Dialog 窗口上（卸载/安装同款处理）。
                delay(300)
                toast("设置已保存，端口与访问方式下次启动生效", ToastType.SUCCESS)
                loadOnce(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                settingsError = e.message ?: "保存设置失败"
            } finally {
                settingsSaving = false
            }
        }
    }

    fun loadLogs(p: ProgramItemDto) {
        if (detailId == p.id) detailId = null
        logsTarget = p
        logsText = ""
        logsLoading = true
        scope.launch {
            try {
                val d = api.getProgramLogs(p.id, 200)
                logsText = listOf(
                    d.stderr.takeIf { it.isNotBlank() }?.let { "--- stderr ---\n$it" },
                    d.stdout.takeIf { it.isNotBlank() }?.let { "--- stdout ---\n$it" }
                ).filterNotNull().joinToString("\n\n").ifBlank { "（暂无日志）" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logsText = e.message ?: "读取日志失败"
            } finally {
                logsLoading = false
            }
        }
    }

    fun revealSecret(p: ProgramItemDto) {
        if (detailId == p.id) detailId = null
        scope.launch {
            try {
                val d = api.consumeProgramSecret(p.id)
                secretTarget = p
                secretValue = d.secret
                loadOnce(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError(e, "读取初始口令失败")
            }
        }
    }

    fun performResetSecret() {
        val p = resetTarget ?: return
        resetTarget = null
        scope.launch {
            try {
                val d = api.resetProgramSecret(p.id)
                secretTarget = p
                secretValue = d.secret
                loadOnce(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError(e, "重置初始口令失败")
            }
        }
    }

    fun performUninstall() {
        val p = uninstallTarget ?: return
        val purgeLogs = uninstallPurgeLogs
        uninstallTarget = null
        uninstallPurgeLogs = false
        scope.launch {
            try {
                val resp = api.uninstallProgram(p.id, deleteLogs = purgeLogs)
                val residual = resp.jsonObject["residual"]?.jsonArray?.size ?: 0
                // 卸载确认弹窗此时正在离场；等退场结束再弹，确保 toast 落在 Activity 窗口而不是将死的 Dialog。
                delay(300)
                when {
                    residual > 0 -> toast("已卸载，但有 $residual 个路径残留", ToastType.ERROR)
                    purgeLogs -> toast("已彻底卸载 ${p.name}", ToastType.SUCCESS)
                    else -> toast("已卸载 ${p.name}", ToastType.SUCCESS)
                }
                if (detailId == p.id) detailId = null
                loadOnce(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError(e, "卸载失败")
            }
        }
    }

    UfiScreenScaffold(
        title = "应用商店",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = { scope.launch { loadOnce() } }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = palette.textSecondary)
            }
        }
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            if (loading && programs.isEmpty()) {
                // UfiPageBackground 自身已经是 verticalScroll；这里不能再套一个滚动容器，
                // 否则会被测量到无限的 maxHeight 并直接崩（2026-11-01 真机 crash）。
                Box(
                    Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    UfiLoadingIndicator()
                }
                return@UfiPageBackground
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.Medium, bottom = Spacing.XLarge)
            ) {
                error?.let { message ->
                    UfiNoticeCard(
                        title = "读取失败",
                        message = message,
                        severity = UfiNoticeSeverity.WARNING,
                    )
                    Spacer(Modifier.height(Spacing.SettingsCardGap))
                }

                if (!nativeExecOk) {
                    UfiNoticeCard(
                        title = "本机型可能无法执行第三方程序",
                        message = "SELinux 拒绝了 core 的 exec 探测。可以安装，但启动可能报 EACCES。",
                        severity = UfiNoticeSeverity.WARNING,
                    )
                    Spacer(Modifier.height(Spacing.SettingsCardGap))
                }

                val installed = programs.filter { it.installed }
                    .sortedWith(compareBy({ rankOf(it) }, { it.name }))
                val available = programs.filter { !it.installed }.sortedBy { it.name }

                if (installed.isNotEmpty()) {
                    UfiSectionHeader(
                        title = "已安装",
                        // UfiSectionHeader 自带 start=4dp；补 12dp 让标题与卡片左缘（16dp）对齐。
                        modifier = Modifier.padding(start = 12.dp, end = Spacing.CardHorizontalMargin),
                        trailing = {
                            Text(
                                installed.size.toString(),
                                style = UfiTextStyles.note,
                                color = palette.textSecondary,
                            )
                        },
                    )
                    installed.forEach { p ->
                        ProgramCard(
                            p = p,
                            active = p.takeIf { isActive(it) },
                            usedBytes = detailUsedBytes[p.id],
                            actionBusy = actionBusy,
                            taskBusy = taskBusy,
                            task = activeTask?.takeIf { it.id == p.id },
                            onDetail = { detailId = p.id },
                            onStart = { start(p) },
                            onStop = { stop(p) },
                            onOpen = { openProgram(p) },
                            onInstall = { openInstall(p, false) },
                            onUpdate = { openInstall(p, true) },
                        )
                        Spacer(Modifier.height(Spacing.SettingsCardGap))
                    }
                }

                if (available.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.Small))
                    UfiSectionHeader(
                        title = "可安装",
                        modifier = Modifier.padding(start = 12.dp, end = Spacing.CardHorizontalMargin),
                        trailing = {
                            Text(
                                available.size.toString(),
                                style = UfiTextStyles.note,
                                color = palette.textSecondary,
                            )
                        },
                    )
                    available.forEach { p ->
                        ProgramCard(
                            p = p,
                            active = p.takeIf { isActive(it) },
                            usedBytes = detailUsedBytes[p.id],
                            actionBusy = actionBusy,
                            taskBusy = taskBusy,
                            task = activeTask?.takeIf { it.id == p.id },
                            onDetail = { detailId = p.id },
                            onStart = {},
                            onStop = {},
                            onOpen = {},
                            onInstall = { openInstall(p, false) },
                            onUpdate = {},
                        )
                        Spacer(Modifier.height(Spacing.SettingsCardGap))
                    }
                }

                if (programs.isEmpty() && !loading) {
                    UfiEmptyState(
                        icon = Icons.Default.List,
                        message = "应用商店暂时没有可用程序",
                        hint = "检查设备与更新源后刷新",
                    )
                }

            }
        }
    }

    // ── 安装 / 更新弹窗 ──
    // 保持挂载（visible 跟随状态），否则点「取消」时整个节点当帧移除，
    // UfiDialogShell 的离场动画根本没机会跑 —— 这就是"点取消直接消失"的原因。
    val installDialogTarget = rememberLastNonNull(installTarget)
    UfiCustomDialog(
        visible = installTarget != null,
        onDismiss = { installTarget = null },
        title = installDialogTarget?.let {
            if (installIsUpdate) "更新 ${it.name}" else "安装 ${it.name}"
        } ?: "",
            confirmButton = {
                UfiButton(
                    text = if (installIsUpdate) "开始更新" else "开始下载",
                    size = UfiButtonSize.Standard,
                    enabled = riskAck && !taskBusy && !actionBusy &&
                    !(installTarget?.let { isActive(it) } ?: false),
                    loading = actionBusy,
                    onClick = { performInstall() }
                )
            },
            dismissButton = {
                UfiButton(
                    text = "取消",
                    variant = UfiButtonVariant.Secondary,
                    size = UfiButtonSize.Standard,
                    onClick = { installTarget = null }
                )
            }
        ) {
            val target = installDialogTarget ?: return@UfiCustomDialog
            UfiDialogBody {
                Text(
                    "${versionLabel(target)} · ${FormatUtils.formatSize(target.downloadSize)}",
                    style = UfiTextStyles.body,
                    color = palette.textSecondary
                )
                if (installIsUpdate) {
                    UfiDialogNote("替换二进制并保留数据目录；数据位置与访问方式保持当前设置。")
                } else {
                    // 不要传 wrap=true：那是「选项多到放不下时折行」的模式，折行模式下
                    // 轨道是满宽实底、chip 按内容宽度靠左排，两个选项时右边会空出一大片，
                    // 看起来就像"分类选择坏了"。默认值（等宽两段铺满）才是二选一的正确形态。
                    if (target.contentEnabled) {
                        val contentOptions = installVolumes.map { v ->
                            (v.path ?: "") to
                                (v.label + if (v.freeBytes > 0) " · ${FormatUtils.formatSize(v.freeBytes)} 可用" else "")
                        }
                        if (contentOptions.isNotEmpty()) {
                            // 卷可能有 3–5 个、标签很长：这里该用下拉（与 Web 的 n-select 对应），
                            // 不是 chip 选择器（chip 一行放不下会折成一堆块，看着像列表）。
                            UfiDialogField("内容位置") {
                                UfiDropdown(
                                    selectedValue = installContentRoot,
                                    options = contentOptions.map { it.first },
                                    onValueSelected = { installContentRoot = it },
                                    fillWidth = true,
                                    optionLabel = { value ->
                                        contentOptions.firstOrNull { it.first == value }?.second ?: value
                                    },
                                )
                            }
                            UfiDialogNote(
                                "上传的文件、媒体库等大文件放这里；数据库与密钥始终在内部存储。" +
                                    "实际位置：" +
                                    (installContentRoot.ifBlank { "内部 programs/${target.id}/content" }
                                        .let { if (it.startsWith("/")) "$it/${target.id}" else it })
                            )
                        }
                    }
                    UfiDialogChipSelector(
                        label = "访问方式",
                        options = listOf("direct" to "直连端口", "gateway" to "经后台转发"),
                        selectedValue = installMode,
                        onSelect = { installMode = it },
                    )
                    UfiDialogNote(
                        if (installMode == "direct") {
                            "直连端口：浏览器直接打开 设备IP:${target.internalPort}。" +
                                "程序端口会暴露在局域网里，只有程序自己的登录口令把关。"
                        } else {
                            "经后台转发：程序只监听本机，由后台多占一个端口转发，" +
                                "进来先校验登录 cookie。局域网里看不到程序自己的端口。"
                        }
                    )
                }
                installTarget?.takeIf { isActive(it) }?.let {
                    UfiDialogWarning("${it.name} 正在运行：安装会替换它的二进制，请先停止。")
                }
                UfiDialogWarning(
                    "这个程序会以「后台服务」的身份运行（同一个系统账号），" +
                        "所以它能读到后台自己的私有文件 —— 包括已保存的运营商账号、设备令牌等。" +
                        "请只安装你信任来源的程序。"
                )
                UfiCheckbox(
                    checked = riskAck,
                    onCheckedChange = { riskAck = it },
                    label = "我已了解，只安装我信任来源的程序",
                    checkboxAtEnd = true,
                )
            }
        }

    // ── 详情 ──
    val dialogDetail = rememberLastNonNull(detail)
    UfiScrollableDialog(
        visible = detail != null,
        onDismiss = { detailId = null },
        title = dialogDetail?.name ?: "",
        contentEnter = true,
        subtitle = {
            dialogDetail?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UfiBadge(statusText(s), type = statusBadgeType(s))
                    Spacer(Modifier.width(8.dp))
                    Text(versionLabel(s), style = UfiTextStyles.note, color = palette.textSecondary)
                }
            }
        },
    ) {
        val p = dialogDetail ?: return@UfiScrollableDialog
        UfiDialogBody {
                if (p.summary.isNotBlank()) {
                    Text(p.summary, style = UfiTextStyles.body, color = palette.textSecondary)
                }
                UfiInfoRow("来源", sourceLabel(p))
                UfiInfoRow("存储", if (p.storage == "external") "外部存储" else "内部存储")
                UfiInfoRow("访问", if (p.accessMode == "gateway") "经后台转发" else "直连端口")
                if (p.internalPort > 0) UfiInfoRow("端口", p.internalPort.toString())
                val usedBytes = detailUsedBytes[p.id] ?: p.usedBytes.takeIf { it > 0 }
                UfiInfoRow(
                    "占用",
                    when {
                        usedBytes != null -> FormatUtils.formatSize(usedBytes)
                        detailLoading -> "统计中…"
                        else -> FormatUtils.formatSize(p.usedBytes)
                    },
                )
                p.lastError.takeIf { it.isNotBlank() }?.let { UfiInfoRow("最近错误", it) }
                if (p.phase == "running" && p.uptimeMs > 0) {
                    UfiInfoRow("运行时长", formatDuration(p.uptimeMs))
                }
                // 功能按钮：与底部操作区**同一规格** —— 两列等宽、Standard 高度。
                // 之前用 FlowRow + SpaceEvenly，按钮宽窄不一、行内留白不匀，看着散。
                val funcButtons = buildList<@Composable () -> Unit> {
                    if (p.installed) {
                        // 第一位是「卸载」（2026-11-08 用户指定）：与日志/口令同列，
                        // 但用 Danger 变体把它和普通功能区分开。
                        add {
                            UfiButton(
                                "卸载",
                                variant = UfiButtonVariant.Danger,
                                size = UfiButtonSize.Standard,
                                enabled = !isActive(p) && !taskBusy && !actionBusy,
                                onClick = {
                                    detailId = null
                                    uninstallPurgeLogs = false
                                    uninstallTarget = p
                                },
                            )
                        }
                        add {
                            UfiButton(
                                "设置",
                                variant = UfiButtonVariant.Secondary,
                                size = UfiButtonSize.Standard,
                                onClick = { openSettings(p) },
                            )
                        }
                        add {
                            UfiButton(
                                "日志",
                                variant = UfiButtonVariant.Secondary,
                                size = UfiButtonSize.Standard,
                                onClick = { loadLogs(p) },
                            )
                        }
                        if (p.phase != "running" && p.phase != "starting" && p.phase != "stopping") {
                            add {
                                UfiButton(
                                    "切换访问",
                                    variant = UfiButtonVariant.Secondary,
                                    size = UfiButtonSize.Standard,
                                    onClick = { toggleAccess(p) },
                                )
                            }
                        }
                        if (p.hasSecret) {
                            add {
                                UfiButton(
                                    "初始口令",
                                    variant = UfiButtonVariant.Subtle,
                                    size = UfiButtonSize.Standard,
                                    onClick = { revealSecret(p) },
                                )
                            }
                        }
                        add {
                            UfiButton(
                                "重置口令",
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Standard,
                                enabled = !isActive(p) && !taskBusy && !actionBusy,
                                onClick = {
                                    detailId = null
                                    resetTarget = p
                                },
                            )
                        }
                        if (updateAvailable(p)) {
                            add {
                                UfiButton(
                                    "更新",
                                    variant = UfiButtonVariant.Secondary,
                                    size = UfiButtonSize.Standard,
                                    enabled = !isActive(p) && !taskBusy && !actionBusy,
                                    onClick = { openInstall(p, true) },
                                )
                            }
                        }
                    }
                }
                // 固定一行 3 个（2026-11-08 用户指定）：不折行、不按内容宽度撑开，
                // 每个占 1/3，最后一行不足 3 个用等宽占位补上，列宽始终一致。
                funcButtons.chunked(3).forEach { rowButtons ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                    ) {
                        rowButtons.forEach { button ->
                            Box(Modifier.weight(1f)) { button() }
                        }
                        repeat(3 - rowButtons.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                // 第二行：运行控制 —— 运行中是 [停止][打开]，其余是单颗 [启动]/[重试]。
                if (p.installed) {
                    when {
                        p.phase == "running" -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                        ) {
                            Box(Modifier.weight(1f)) {
                                UfiButton(
                                    "停止",
                                    variant = UfiButtonVariant.Secondary,
                                    size = UfiButtonSize.Standard,
                                    enabled = !actionBusy,
                                    onClick = { stop(p) },
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                UfiButton(
                                    "打开",
                                    size = UfiButtonSize.Standard,
                                    enabled = !actionBusy,
                                    onClick = { openProgram(p) },
                                )
                            }
                        }
                        p.phase == "starting" || p.phase == "stopping" -> UfiButton(
                            text = if (p.phase == "starting") "启动中…" else "停止中…",
                            size = UfiButtonSize.Standard,
                            enabled = false,
                            onClick = {},
                        )
                        else -> UfiButton(
                            text = if (p.phase == "failed" || p.circuitOpen) "重试" else "启动",
                            size = UfiButtonSize.Standard,
                            enabled = p.storageReady && !taskBusy && !actionBusy && !isActive(p),
                            onClick = { start(p) },
                        )
                    }
                }
            }
        }

    // ── 程序设置（2026-11-10）──
    val settingsDialogTarget = rememberLastNonNull(settingsTarget)
    val settingsRunning = settingsDialogTarget?.let { isActive(it) } == true
    UfiCustomDialog(
        visible = settingsTarget != null,
        onDismiss = { if (!settingsSaving) settingsId = null },
        title = settingsDialogTarget?.let { "设置 · ${it.name}" } ?: "设置",
        confirmButton = {
            UfiButton(
                "保存",
                size = UfiButtonSize.Standard,
                // 运行中端口/访问方式被禁用，但「开机自启」仍可保存（core 允许）。
                enabled = !settingsSaving && settingsTarget != null,
                onClick = { saveSettings() },
            )
        },
        dismissButton = {
            UfiButton(
                "取消",
                variant = UfiButtonVariant.Secondary,
                size = UfiButtonSize.Standard,
                enabled = !settingsSaving,
                onClick = { settingsId = null },
            )
        },
    ) {
        UfiDialogBody {
            val p = settingsDialogTarget ?: return@UfiDialogBody
            // 透明点击层：运行中盖在具体配置区域上，点哪都提示"先停止"。
            // 不画水波纹（indication = null）——这些控件外观仍是禁用的，不该有可点反馈。
            val lockClick = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { warnSettingsLocked() }
            if (settingsRunning) {
                // 运行中整组禁用会把选中项的深色底 + disabled 文字叠成"看不清"，
                // 这里改成只读值；等停止后再显示可点的分段选择器。
                Box(Modifier.fillMaxWidth().then(lockClick)) {
                    UfiDialogInfoRow(
                        "访问方式",
                        if (settingsMode == "gateway") "经后台转发" else "直连端口",
                    )
                }
            } else {
                UfiDialogChipSelector(
                    label = "访问方式",
                    options = listOf("direct" to "直连端口", "gateway" to "经后台转发"),
                    selectedValue = settingsMode,
                    onSelect = { settingsMode = it },
                )
            }
            UfiDialogNote(
                if (settingsMode == "gateway") {
                    "程序只监听本机，由后台额外占一个端口转发；局域网无法绕过控制台直接访问。"
                } else {
                    "浏览器直接打开 设备IP:内部端口；程序端口会暴露在局域网，只有程序自己的登录口令把关。"
                }
            )
            Box(Modifier.fillMaxWidth()) {
                UfiDialogField("内部端口") {
                    UfiDigitField(
                        value = settingsInternal,
                        onValueChange = { settingsInternal = it.take(5) },
                        label = "",
                        placeholder = "1024–65535",
                        enabled = !settingsRunning,
                        maxLength = 5,
                    )
                }
                if (settingsRunning) Box(Modifier.matchParentSize().then(lockClick))
            }
            UfiDialogNote("程序实际监听的端口（直连时就是浏览器打开的端口），1024–65535；下次启动生效。")
            if (settingsMode == "gateway") {
                Box(Modifier.fillMaxWidth()) {
                    UfiDialogField("外部端口") {
                        UfiDigitField(
                            value = settingsExternal,
                            onValueChange = { settingsExternal = it.take(5) },
                            label = "",
                            placeholder = "1024–65535",
                            enabled = !settingsRunning,
                            maxLength = 5,
                        )
                    }
                    if (settingsRunning) Box(Modifier.matchParentSize().then(lockClick))
                }
                UfiDialogNote("经后台转发时浏览器打开的端口；被占用会自动顺延。下次启动生效。")
            }
            UfiDialogSwitchField(
                label = "开机自启",
                checked = settingsAutostart,
                enabled = !settingsSaving,
                onCheckedChange = { settingsAutostart = it },
            )
            UfiDialogNote("Core 启动后自动拉起该程序；与「启动 / 停止」是两件事。")
            UfiDialogInfoRow(
                "数据位置",
                if (p.storage == "external") "外部存储" else "内部存储",
            )
            UfiDialogNote("数据位置在安装时确定，暂不支持在线迁移；需要换位置请卸载后重装（数据不会自动搬迁）。")
            if (settingsRunning) {
                UfiDialogWarning("程序正在运行：访问方式与端口需要先停止程序才能修改；开机自启不受影响。")
                UfiButton(
                    "停止程序",
                    variant = UfiButtonVariant.Secondary,
                    size = UfiButtonSize.Standard,
                    enabled = !actionBusy,
                    onClick = { stop(p) },
                )
            }
            settingsError?.let { UfiDialogWarning(it) }
        }
    }

    // ── 日志 ──
    val dialogLogs = rememberLastNonNull(logsTarget)
    UfiScrollableDialog(
        visible = logsTarget != null,
        onDismiss = { logsTarget = null },
        title = "日志 · ${dialogLogs?.name ?: ""}",
        contentEnter = true,
    ) {
        UfiDialogBody {
            if (logsLoading) {
                UfiLoadingIndicator(Modifier.size(24.dp), strokeWidth = 2.5f)
            } else {
                Text(
                    logsText,
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    // ── 初始口令 ──
    val dialogSecret = rememberLastNonNull(secretTarget)
    UfiCustomDialog(
        visible = secretTarget != null,
        onDismiss = { secretTarget = null },
        title = "${dialogSecret?.name ?: ""} 初始口令",
        confirmButton = {
            UfiButton(
                text = "复制并关闭",
                size = UfiButtonSize.Standard,
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("初始口令", secretValue))
                    toast("已复制", ToastType.SUCCESS)
                    secretTarget = null
                }
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogWarning("只显示一次；关闭后 Core 重启前仍可重置，但旧口令立即失效。请立即复制保存。")
            Text(secretValue, style = UfiTextStyles.listItemTitle, color = palette.textPrimary, fontFamily = FontFamily.Monospace)
        }
    }

    // ── 重置口令确认 ──
    val dialogReset = rememberLastNonNull(resetTarget)
    UfiConfirmDialog(
        visible = resetTarget != null,
        title = "重置 ${dialogReset?.name ?: ""} 的管理员口令",
        text = "会重新执行程序的管理员初始化，旧口令立即失效。请先停止程序；仅用于丢失口令或旧数据没有管理员的情况。",
        confirmText = "重置",
        destructive = true,
        onConfirm = { performResetSecret() },
        onDismiss = { resetTarget = null }
    )

    // ── 卸载确认（默认保留日志；勾选后彻底删除 log/programs/<id>）──
    val dialogUninstall = rememberLastNonNull(uninstallTarget)
    UfiCustomDialog(
        visible = uninstallTarget != null,
        onDismiss = { uninstallTarget = null },
        title = "卸载 ${dialogUninstall?.name ?: ""}",
        confirmButton = {
            UfiButton(
                text = if (uninstallPurgeLogs) "彻底卸载" else "卸载",
                onClick = { performUninstall() },
                variant = UfiButtonVariant.Danger,
                size = UfiButtonSize.Standard,
            )
        },
        dismissButton = {
            UfiButton(
                text = "取消",
                onClick = { uninstallTarget = null },
                variant = UfiButtonVariant.Secondary,
                size = UfiButtonSize.Standard,
            )
        },
    ) {
        UfiDialogBody {
            Text(
                "会删除程序与它的数据目录。之后要重装需要重新下载。",
                style = UfiTextStyles.body,
                color = palette.textSecondary,
            )
            UfiCheckbox(
                checked = uninstallPurgeLogs,
                onCheckedChange = { uninstallPurgeLogs = it },
                label = "同时删除日志（彻底卸载）",
                description = "日志用于卸载后排障；删除后无法恢复。",
            )
        }
    }


    UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgramCard(
    p: ProgramItemDto,
    active: ProgramItemDto?,
    /** 详情接口拿到的整体占用；null = 还没取到。 */
    usedBytes: Long?,
    actionBusy: Boolean,
    taskBusy: Boolean,
    /** 当前程序正在进行的安装/更新任务；非空时在卡片底部画进度条。 */
    task: ProgramStatusDto?,
    onDetail: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
) {
    val palette = LocalResolvedPalette.current
    val blocked = active != null && active.id != p.id
    val live = p.phase == "running" || p.phase == "starting" || p.phase == "stopping"
    // 整卡点击 = 看详情（原来的「详情」按钮已去掉，减少一列固定宽度的小按钮）。
    // 纵向内距从默认 8dp 加到 12dp：卡片头是 40dp 的头像/首字母，8dp 时贴着上边框。
    UfiSettingsRowCard(
        onClick = onDetail,
        contentPadding = PaddingValues(
            horizontal = Spacing.XLarge,
            vertical = Spacing.Large,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(initials(p.name), style = UfiTextStyles.tagStrong, color = palette.accent)
            }
            Spacer(Modifier.width(Spacing.Medium))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name,
                        style = UfiTextStyles.listItemTitle,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    UfiBadge(statusText(p), type = statusBadgeType(p))
                }
                Text(
                    versionLabel(p) + if (p.requiresRoot) " · 需 root" else "",
                    style = UfiTextStyles.note,
                    color = palette.textSecondary
                )
            }
            // 主操作放右上角（2026-11-08 用户指定）：与标题同排，鼠标/拇指第一落点。
            if (p.installed && updateAvailable(p) && !live) {
                UfiButton(
                    "更新", variant = UfiButtonVariant.Secondary,
                    size = UfiButtonSize.Small, fillWidth = false,
                    enabled = !blocked && !actionBusy && !taskBusy,
                    onClick = onUpdate
                )
                Spacer(Modifier.width(Spacing.Small))
            }
            when {
                !p.installed -> UfiButton(
                    text = if (!p.available) "暂无版本" else "安装",
                    size = UfiButtonSize.Small,
                    fillWidth = false,
                    enabled = p.available && !blocked && !actionBusy && !taskBusy,
                    onClick = onInstall
                )
                p.phase == "running" -> Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 次操作在左、主操作在右（全站按钮规范）。
                    UfiButton(
                        "停止", variant = UfiButtonVariant.Secondary,
                        size = UfiButtonSize.Small, fillWidth = false,
                        enabled = !actionBusy, onClick = { onStop() }
                    )
                    UfiButton(
                        "打开",
                        size = UfiButtonSize.Small, fillWidth = false,
                        enabled = !actionBusy, onClick = { onOpen() }
                    )
                }
                p.phase == "starting" -> UfiButton(
                    "启动中…", size = UfiButtonSize.Small, fillWidth = false,
                    enabled = false, onClick = {}
                )
                p.phase == "stopping" -> UfiButton(
                    "停止中…", size = UfiButtonSize.Small, fillWidth = false,
                    enabled = false, onClick = {}
                )
                else -> UfiButton(
                    text = if (p.phase == "failed" || p.circuitOpen) "重试" else "启动",
                    size = UfiButtonSize.Small,
                    fillWidth = false,
                    enabled = p.storageReady && !blocked && !actionBusy && !taskBusy,
                    onClick = onStart
                )
            }
        }
        if (p.summary.isNotBlank()) {
            Spacer(Modifier.height(Spacing.Small))
            Text(
                p.summary,
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(Spacing.Small))
        Text(
            programMetrics(p, usedBytes),
            style = UfiTextStyles.caption,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(Spacing.Medium))
        if (blocked) {
            Spacer(Modifier.height(4.dp))
            Text("先停止 ${active?.name}", style = UfiTextStyles.caption, color = palette.warning)
        }
        if (task != null) {
            Spacer(Modifier.height(Spacing.Small))
            val total = task.totalBytes.takeIf { it > 0 } ?: 1
            val progress = (task.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
            UfiCompactProgressBar(progress = progress)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.message.ifBlank { "正在安装…" },
                    style = UfiTextStyles.caption,
                    color = palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (task.totalBytes > 0) {
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary,
                    )
                }
            }
        }
    }
}

private fun initials(name: String): String {
    val clean = name.replace(Regex("\\s+"), "")
    if (clean.isEmpty()) return "??"
    return clean.take(2).uppercase()
}

private fun statusText(p: ProgramItemDto): String {
    if (!p.installed) return if (p.available) "未安装" else "暂无版本"
    if (p.storage == "external" && !p.storageReady) return "外部存储不可用"
    if (p.circuitOpen) return "已熔断"
    return when (p.phase) {
        "running" -> "运行中"
        "starting" -> "启动中"
        "stopping" -> "停止中"
        "failed" -> "启动失败"
        else -> if (p.lastExitCode != null && p.lastExitCode != 0) "已停止 · 上次异常" else "已停止"
    }
}

private fun statusBadgeType(p: ProgramItemDto): UfiBadgeType = when {
    p.storage == "external" && !p.storageReady -> UfiBadgeType.ERROR
    p.circuitOpen || p.phase == "failed" -> UfiBadgeType.ERROR
    p.phase == "running" -> UfiBadgeType.SUCCESS
    p.phase == "starting" || p.phase == "stopping" -> UfiBadgeType.WARNING
    else -> UfiBadgeType.DEFAULT
}

private fun rankOf(p: ProgramItemDto): Int = when {
    p.circuitOpen || p.phase == "failed" || (p.storage == "external" && !p.storageReady) -> 0
    p.phase == "starting" || p.phase == "stopping" -> 1
    p.phase == "running" -> 2
    else -> 3
}

private fun programMetrics(p: ProgramItemDto, usedBytes: Long?): String {
    if (!p.installed) {
        return "下载 ${FormatUtils.formatSize(p.downloadSize)} · ${p.license.ifBlank { "—" }}"
    }
    val mem = if (p.rssMb > 0) "内存 ${p.rssMb} MB" else null
    val port = if (p.internalPort > 0) "端口 ${p.internalPort}" else null
    // 安装后要显示**整体占用**（bin + data + content），这个数只能从详情接口拿；
    // 还没取到时先写「统计中…」，不再退化成"安装包大小"（那是另一回事）。
    val size = when {
        usedBytes != null && usedBytes > 0 -> "占用 ${FormatUtils.formatSize(usedBytes)}"
        p.usedBytes > 0 -> "占用 ${FormatUtils.formatSize(p.usedBytes)}"
        else -> "占用 统计中…"
    }
    return listOfNotNull(mem, port, size).joinToString(" · ")
}

private fun versionLabel(p: ProgramItemDto): String {
    val v = (if (p.installed) p.installedVersion else p.latestVersion).trim().ifBlank { "—" }
    if (v == "—") return v
    return if (v.startsWith("v", ignoreCase = true)) v else "v$v"
}

private fun sourceLabel(p: ProgramItemDto): String = when {
    !p.installed -> "上游清单"
    p.source == "upload" -> "本地上传"
    p.source == "remote" -> "上游下载"
    else -> p.source.ifBlank { "—" }
}

private fun updateAvailable(p: ProgramItemDto): Boolean {
    if (!p.installed || p.installedVersion.isBlank() || p.latestVersion.isBlank()) return false
    return compareVersions(p.latestVersion, p.installedVersion) > 0
}

private fun compareVersions(a: String, b: String): Int {
    fun parse(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V")
        .substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val left = parse(a)
    val right = parse(b)
    val size = maxOf(left.size, right.size)
    for (i in 0 until size) {
        val l = left.getOrElse(i) { 0 }
        val r = right.getOrElse(i) { 0 }
        if (l != r) return if (l > r) 1 else -1
    }
    return 0
}

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    return when {
        totalMinutes < 60 -> "${totalMinutes} 分钟"
        totalMinutes < 24 * 60 -> "${totalMinutes / 60} 小时"
        else -> "${totalMinutes / (24 * 60)} 天"
    }
}
