package com.ufi_axis.ui.resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.ui.components.common.LocalUfiDialogClose
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiDialogActions
import com.ufi_axis.ui.components.common.UfiLoadingBox
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiScrollableDialog
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * 资源中心 —— **页面 / 渲染层**（2026-10-14；2026-10-24 按四个协作类的契约重写）。
 *
 * ## 分层位置（本文件只做"取数编排 + 页面骨架"，不碰其余三层的判断）
 *  · [ResourceRegistry] —— 有哪些资源、每个资源怎么读写；
 *  · [ResourceDescriptor] —— 一个资源长什么样、按钮存不存在、状态怎么翻成人话；
 *  · [ResourceTaskController] —— 下发之后的轮询与终态；
 *  · [ResourceCard] —— 弹窗内那张卡的版式与破坏性二次确认。
 *
 * 因此本文件**不认识任何资源 id**（硬约束见 [ResourceDescriptor] 文件头）：
 * 遍历 [ResourceRegistry.all]，行摘要只读 [ResourceSnapshot] 的三个公共字段，
 * 动作一律转成 [ResourceOp] 交给 runner。新增一类资源不需要动这里一个字。
 *
 * ## 两条读取路径，分工不能合并
 * 1. **全量读取**（本文件的 [LaunchedEffect]）：进页面、回到前台、以及
 *    [ResourceChangeBus] 的 tick 变化时读一遍全部描述符。它回答"每张卡现在是什么状态"。
 * 2. **存量轮询**：`busyIds` = "服务端自报还在跑（`task.active` / `manifestLoading`）、
 *    且本 app 没有在飞 runner 任务"的那些资源，每 [POLL_INTERVAL_MS] 回读一次。
 *    后者是把"从 Web 面板/上次会话发起的任务"也跟到底 —— runner 只管自己下发的那些，
 *    两者的轮询不能重叠（重叠等于同一秒打两次同样的请求）。
 *
 * 两条都带 lifecycle 门控（退到后台就停、回前台自己续上），形态抄自
 * `TunnelSettingsComponentsScreen.kt` 的既有正确写法；进程级 scope 只保证"任务不被页面
 * 销毁打断"，**不保证"app 在后台时还在打网络"**，所以门控仍然必须写在这一层。
 *
 * ## 为什么读取失败要用 Toast 说一次
 * 行摘要只能承载"失败了"这个结论（`读取失败（原因已用提示告知）`），而原因往往很长
 * （core 未连接 / 更新源不可用 / HTTP 原因）。弹窗里再重复一次会与卡片内的警告叠成两条。
 * 所以失败原因统一走页面级 Toast，并按键（资源 id）去重：同一句错误只弹一次。
 *
 * ## 弹窗里为什么要抹掉 `snapshot.error`
 * 行摘要与 Toast 已经说过"读取失败 + 原因"；卡片再画一条同样的警告属于重复。
 * 但 `manifestError` **保留** —— 它是"更新源不可用"，卡片要据此引导用户走本地上传兜底。
 */
@Composable
fun ResourcesScreen(navController: NavHostController) {
    val context = LocalContext.current
    // 每次进页面现建即可：持的是 AppPreferences，很轻（理由见 ResourceTaskController.submit）。
    val prefs = remember(context) { AppPreferences(context) }
    val api = remember(prefs) { ResourceApi(RetrofitClient.getApiService(prefs), prefs) }

    // runner 的任务表是进程级单例：离开页面再回来仍能看到还在跑的任务。
    val tasks by ResourceTaskController.shared.tasks.collectAsState()
    // "刚才动了某个资源"的进程内广播：别的页面（或本页另一张卡）改了资源后要重读。
    val tick by ResourceChangeBus.tick.collectAsState()

    // 按资源 id 分槽：两张卡同时读、互不覆盖。
    var snapshots by remember { mutableStateOf<Map<String, ResourceSnapshot>>(emptyMap()) }
    // 首次全量读取是否完成。false 时只画加载指示，避免"读取失败"在首帧闪一下。
    var loadedOnce by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var openDescriptorId by remember { mutableStateOf<String?>(null) }
    // 已经用 Toast 提示过「正在检查更新…」的资源 id：只用于防重复（tasks 因别的资源变化而重组）。
    var refreshingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 已经提示过的错误（资源 id → 错误原文）。不是 UI 状态，只用于去重。
    val reportedErrors = remember { mutableMapOf<String, String>() }

    // ── 页面级 lifecycle 门控（2026-09-21 的既有形态）──
    // ON_RESUME 以外的任何事件都视为"不在前台"：退后台就别再打网络。
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

    /*
     * 全量读取：首次进入 + 回到前台 + 任何资源发生变化（tick）。
     *
     * 描述符之间并发读（`async` + `awaitAll`）：三类资源走三个端点，串行会让首屏等
     * 三倍的最慢耗时。单个描述符失败不会炸掉整轮 —— [readSnapshot] 已经把它折成
     * 带 `error` 的快照，另一张卡照常显示。
     *
     * 不把 loadedOnce 置回 false：刷新期间保留上一轮内容（避免整页闪成 spinner），
     * 这也让"回到前台时先看到旧值、再被新值替换"的观感比空白更稳。
     */
    LaunchedEffect(tick, lifecycleResumed) {
        if (!lifecycleResumed) return@LaunchedEffect
        val fresh = coroutineScope {
            ResourceRegistry.all.map { descriptor ->
                async { descriptor.id to readSnapshot(api, descriptor) }
            }.awaitAll()
        }
        snapshots = fresh.toMap()
        loadedOnce = true
    }

    /*
     * 读取失败提示：每个资源只提示**新出现的**那句错误。
     *
     * 不能"每次重组都弹"：core 的报错（"Core 未连接时不可用"这类）是恒定字符串，
     * 页面每次因 tick / 任务进度重组都会重放一遍，Toast 会连成一片。
     * 读成功时把该资源的记录删掉，下次再坏才会重新提示。
     */
    LaunchedEffect(snapshots) {
        ResourceRegistry.all.forEach { descriptor ->
            val snapshot = snapshots[descriptor.id] ?: return@forEach
            val error = snapshot.error?.takeIf { it.isNotBlank() }
                ?: snapshot.manifestError.takeIf { it.isNotBlank() }
            if (error == null) {
                reportedErrors.remove(descriptor.id)
            } else if (reportedErrors[descriptor.id] != error) {
                reportedErrors[descriptor.id] = error
                toastMessage = ToastMessage("${descriptor.name}：$error", ToastType.ERROR)
            }
        }
    }

    /*
     * 「检查更新」的 Toast 通道。
     *
     * 这个动作约 6 秒（AWAIT_START 等 5 个 idle tick）。历史实现把进度画在 ResourceCard 里：
     * 弹窗会先被「检查更新中…」撑高、结束后再收回去。现在改为：
     *  · 检测到 Refresh 任务在跑 → Toast「正在检查更新…」；
     *  · 卡内进度区由调用方传 showTaskProgress = false 关闭；
     *  · 落终态 → Toast 结果，并 **dismiss** 该任务（卡内「知道了」已经被隐藏）。
     *
     * 为什么按 `task.label == ResourceOp.Refresh.label` 认领：页面重进时本地的集合会重置，
     * 但 runner 任务还在（进程级 scope），label 是这条动作跨重组 / 重进都不变的标识。
     * `refreshingIds` 只负责防重复：`tasks` 因别的资源变化而重组时，同一任务不再弹第二次。
     */
    LaunchedEffect(tasks) {
        tasks.values
            .filter { it.label == ResourceOp.Refresh.label }
            .forEach { task ->
                val id = task.resourceId
                if (task.running) {
                    if (id !in refreshingIds) {
                        refreshingIds = refreshingIds + id
                        // 覆盖 AWAIT_START 的 5 个 idle tick（约 6s）+ 网络余量；落终态时
                        // 结果 Toast 会替换掉它（UfiToastOverlay.show 会先 dismissActive）。
                        toastMessage = ToastMessage(
                            text = "正在检查更新…",
                            type = ToastType.INFO,
                            durationMs = 8000L
                        )
                    }
                    return@forEach
                }

                val name = ResourceRegistry.all.firstOrNull { it.id == id }?.name ?: id
                when (task.phase) {
                    ResourceTaskController.Task.Phase.SUCCESS -> {
                        toastMessage = ToastMessage(
                            "$name：${task.message.ifBlank { "检查更新完成" }}",
                            ToastType.SUCCESS
                        )
                    }

                    ResourceTaskController.Task.Phase.FAILED -> {
                        toastMessage = ToastMessage(
                            "$name：${task.message.ifBlank { "检查更新失败" }}",
                            ToastType.ERROR
                        )
                    }

                    // IDLE 不该出现（submit 首帧就置 RUNNING）；RUNNING 上面已提前返回。
                    else -> return@forEach
                }
                refreshingIds = refreshingIds - id
                ResourceTaskController.shared.dismiss(id)
            }
    }

    /*
     * 发起一次「检查更新」：只负责提交。Toast 统一由上面的 effect 发（页面重进 / 继承任务
     * 也走同一条路），避免两个地方各弹一次。
     */
    val startRefresh: (ResourceDescriptor) -> Unit = { descriptor ->
        if (ResourceTaskController.shared.tasks.value[descriptor.id]?.running != true) {
            ResourceTaskController.shared.submit(api, descriptor, ResourceOp.Refresh)
        }
    }

    /*
     * 存量轮询的对象：服务端还在跑、但**不是本 app 的 runner 在下发的那一个**。
     *
     * 排除 `tasks[id].running` 是防重复：runner 自己就在按 1s 回读同一个端点，
     * 页面再跟一份只是把请求数翻倍；且 runner 落终态会经 `onFinished` → tick 触发全量重读。
     *
     * `manifestLoading` 也要跟：组件清单由 core 在后台拉，用户不该手动点「检查更新」
     * 才看到内容（原 `TunnelSettingsComponentsScreen` 的懒加载轮询，语义原样保留）。
     */
    val busyIds = snapshots
        .filterValues { it.task.active || it.manifestLoading }
        .keys
        .filterNot { tasks[it]?.running == true }
        .toSet()

    LaunchedEffect(busyIds, lifecycleResumed) {
        if (!lifecycleResumed || busyIds.isEmpty()) return@LaunchedEffect
        /*
         * 条件只需在**进入**时判一次：busyIds 是 effect 的 key，快照更新后会重组并带上
         * 新的集合重新启动本 effect；集合空了就走到上面的 return。lifecycleResumed 同理，
         * 退后台时 key 变化会取消这个协程。
         */
        while (true) {
            delay(POLL_INTERVAL_MS)
            // 只回读还在忙的那些。描述符自己知道要读写哪个端点，这里不拼 URL。
            val pending = ResourceRegistry.all.filter { it.id in busyIds }
            val fresh = pending.associate { it.id to readSnapshot(api, it) }
            // 合并而不是替换：本轮只读了子集，替换会把其它资源的快照抹掉。
            snapshots = snapshots + fresh
        }
    }

    UfiScreenScaffold(
        title = "资源中心",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 首屏只画加载指示；读完后（哪怕全是错误快照）再一次性铺开，避免状态闪烁。
            UfiLoadingBox(isLoading = !loadedOnce) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {
                    ResourceRegistry.all.forEach { descriptor ->
                        val snapshot = snapshots[descriptor.id]
                        val task = tasks[descriptor.id]
                        // 一项一张行卡（与网络设置页同构）：整行可点，进详情弹窗。
                        UfiSettingsRowCard {
                            UfiSettingsItem(
                                title = descriptor.name,
                                description = rowSummary(snapshot = snapshot, task = task),
                                onClick = { openDescriptorId = descriptor.id },
                                trailing = { UfiSettingsChevron() }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.Medium))
        }
    }

    /*
     * 详情弹窗。`openDescriptorId` 是**单一状态**而不是每张卡一个布尔：
     * 同一时刻只可能有一个弹窗，留 N 个布尔就迟早出现"关了一个另一个还开着"。
     */
    val openDescriptor = ResourceRegistry.all.firstOrNull { it.id == openDescriptorId }
    if (openDescriptor != null) {
        val openTask = tasks[openDescriptor.id]

        /*
         * 「检查更新」按钮**由描述符决定存不存在**（[ResourceDescriptor.refresh] 为 null
         * 就不画），与卡片里其它按钮同一口径 —— 不渲染一颗点了只会报"本资源不支持"的按钮。
         * 没有 actions 时 [UfiScrollableDialog] 会自动退回右上角 × 关闭，不会关不掉。
         */
        val dialogActions: (@Composable () -> Unit)? =
            if (openDescriptor.refresh != null) {
                {
                    /*
                     * ⚠ UfiDialogActions 把**两颗**按钮都当作"请求关闭"：点击后经
                     * LocalUfiDialogClose → UfiDialogShell.requestClose，先播背景离场 ramp
                     * （模糊/变暗归零、卡片淡出），再执行动作；若动作没真的关窗，壳子再把
                     * ramp 倒放回来。对「检查更新」这种**不关弹窗**的动作，表现就是背景
                     * 先清晰一下再重新模糊 —— 用户报的"弹窗背景异常"。
                     *
                     * 这里只给确认键绕开该时序：把 LocalUfiDialogClose 换成默认的直通实现，
                     * 而「关闭」仍显式调用外层捕获的 closeDialog，保留离场动画。
                     */
                    val closeDialog = LocalUfiDialogClose.current
                    val runDirectly: (() -> Unit) -> Unit = { action -> action() }
                    CompositionLocalProvider(LocalUfiDialogClose provides runDirectly) {
                        UfiDialogActions(
                            onDismiss = { closeDialog { openDescriptorId = null } },
                            onConfirm = { startRefresh(openDescriptor) },
                            confirmText = "检查更新",
                            dismissText = "关闭",
                            // 同一资源已有任务在飞时不再放行：runner 侧也会忽略，但按钮该先置灰。
                            enabled = openTask?.running != true
                        )
                    }
                }
            } else {
                null
            }

        UfiScrollableDialog(
            visible = true,
            onDismiss = { openDescriptorId = null },
            title = openDescriptor.name,
            actions = dialogActions
        ) {
            ResourceCard(
                descriptor = openDescriptor,
                // 读取失败的原因已由行摘要 + Toast 说过；弹窗内不再重复同一条。
                // manifestError 保留：它对应"更新源不可用"，卡片要靠它提示本地上传兜底。
                snapshot = snapshots[openDescriptor.id]?.copy(error = null),
                task = openTask,
                // onRefresh 目前由弹窗底部的 actions 承担；仍按契约传一份，
                // 卡片以后把「检查更新」挪回卡内时不需要改本文件。
                onRefresh = { startRefresh(openDescriptor) },
                onInstall = { item ->
                    // 按钮文案（下载安装 / 更新 / 在线更新）由描述符给，操作标签跟着它走，
                    // 否则用户点「在线更新」，进度行却写"下载安装中…"。
                    ResourceTaskController.shared.submit(
                        api,
                        openDescriptor,
                        ResourceOp.Install(item.id, item.actionLabel ?: "下载安装")
                    )
                },
                onUpload = { file -> submit(api, openDescriptor, ResourceOp.Upload(file)) },
                onUninstall = { itemId -> submit(api, openDescriptor, ResourceOp.Uninstall(itemId)) },
                onRollback = { submit(api, openDescriptor, ResourceOp.Rollback) },
                onClear = { submit(api, openDescriptor, ResourceOp.Clear) },
                // 失败提示由卡片自己挂着（成功态 1.5s 后自灭），用户点「知道了」才收掉。
                onDismissTask = { ResourceTaskController.shared.dismiss(openDescriptor.id) },
                // 「检查更新」走页面级 Toast，卡内进度区整段不画（避免弹窗被撑高/收起）。
                // 用 task.label 认领而不是本地的 refreshingIds：页面重进时集合会重置，
                // 但 runner 任务仍在（进程级 scope），label 跨重进不变。
                // 其它任务（安装/上传/卸载/回滚/恢复内置）保持卡内进度。
                showTaskProgress = openTask?.label != ResourceOp.Refresh.label
            )
        }
    }

    UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
}

/**
 * 行摘要：一句话说清"这张卡现在能不能点、在干什么"。
 *
 * 五个状态的顺序不能换：
 *  · 有本 app 的任务在跑 → 先说任务（它比"读到几条"更要紧）；
 *  · 任务失败 → 失败提示会一直挂在 runner 里直到用户点「知道了」，行里必须能看见，
 *    否则用户关掉弹窗后就再也不知道刚才那次安装失败了；
 *  · 还没读到 → 读取中；
 *  · 读失败 → 只给结论，原因在 Toast 里（见文件头）；
 *  · 读到了 → 条目数 + 更新源状态。
 *
 * `manifestError` 单独一档：清单拉取失败时条目数可能仍然正确（已安装项来自本地状态），
 * 但"更新源可用"是假的 —— 这行不能与同一份快照上的"更新源不可用"警告互相打脸。
 */
private fun rowSummary(
    snapshot: ResourceSnapshot?,
    task: ResourceTaskController.Task?
): String = when {
    task?.running == true -> "任务进行中…"
    task?.phase == ResourceTaskController.Task.Phase.FAILED -> "任务失败，点开查看原因"
    snapshot == null -> "读取中…"
    !snapshot.error.isNullOrBlank() -> "读取失败（原因已用提示告知）"
    snapshot.manifestError.isNotBlank() -> "${snapshot.items.size} 个可下载 · 更新源不可用"
    else -> "${snapshot.items.size} 个可下载 · 更新源" +
        if (snapshot.manifestLoading) "获取中…" else "可用"
}

/**
 * 读一次资源快照，把异常折成**带 error 的快照**。
 *
 * 为什么在这里兜住而不是让页面自己 try：描述符回调约定"返回 null = 成功、字符串 = 失败原因"，
 * 但传输层（Retrofit / OkHttp）仍会抛。统一在这一处兜底之后，两条读取路径
 * （全量 / 存量轮询）都不需要再写一遍 catch。
 *
 * `CancellationException` 必须原样抛出：换页 / 退后台都会取消在飞请求，取消不是失败，
 * 吞掉它既会把"取消"显示成一条错误，也会破坏协程的取消语义。
 */
private suspend fun readSnapshot(
    api: ResourceApi,
    descriptor: ResourceDescriptor
): ResourceSnapshot = try {
    descriptor.fetch(api)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ResourceSnapshot(error = describeFailure(e))
}

/** 把一次操作交给 runner；页面本身不发请求、不轮询。 */
private fun submit(api: ResourceApi, descriptor: ResourceDescriptor, op: ResourceOp) {
    ResourceTaskController.shared.submit(api, descriptor, op)
}

/**
 * 存量轮询间隔：与 `ResourceTaskController` 的轮询间隔同值同义。
 *
 * 不跨类引用那个常量：它是 runner 的私有节奏（轮询上限按它算），本页的懒加载轮询
 * 是另一条独立预算（只要还在忙就一直跟），两者将来调参的理由不同。
 */
private const val POLL_INTERVAL_MS = 1000L
