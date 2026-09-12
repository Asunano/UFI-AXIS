package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.model.UpdateStatusResponse
import com.ufi_axis.data.update.CoreUpdatePersistence
import com.ufi_axis.viewmodel.state.FrontendUpdateState
import com.ufi_axis.viewmodel.state.UpdatePromptState
import com.ufi_axis.viewmodel.state.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 更新提示的**唯一状态槽**（2026-09-06）。
 *
 * 解决的问题：App 自更新（ToolsModule.frontendUpdateState → AboutDeviceScreen）与
 * core 版本提示（DashboardModule.updateState → MainActivity）是两条互不知情的链路，
 * 各自持有一个 `remember { mutableStateOf(false) }`。两个弹窗都是独立平台 Window，
 * 同时命中就直接叠加：两层 scrim 叠在一起、返回键只关最上层。
 *
 * 本模块不发任何网络请求，也不持有 api —— 它只做三件事：
 * 1. 把两条链路的 state **聚合**成一个 [UpdatePromptState]，并按阶段**顺序**展示
 *    （core 优先；core 完成或跳过后再弹 app，两者不再同屏）；
 * 2. 持有唯一的 `visible`（[show] / [dismiss]），所以"同一时刻只有一个更新弹窗"是结构保证，
 *    不是靠调用方互相谦让；
 * 3. 自动提示的节流（见 [autoPromptUsed]）。
 *
 * 真正的动作仍在原模块：App 下载/安装走 `ToolsModule.downloadFrontendApk/installFrontendApk`，
 * core 更新走 `ToolsModule.triggerDeviceUpdate()`（`POST /api/update/check`）。
 */
class UpdatePromptModule(
    private val scope: CoroutineScope,
    appUpdate: StateFlow<FrontendUpdateState>,
    coreUpdate: StateFlow<UpdateState>,
    coreInstallStatus: StateFlow<UpdateStatusResponse?>,
    private val coreUpdatePersistence: CoreUpdatePersistence,
    /** 冷启动接管：若持久化标记仍在「进行中」，由调用方恢复后端状态轮询（[ToolsModule.startDeviceUpdatePolling]）。 */
    private val onResumeCorePolling: (() -> Unit)? = null
) {
    private val _visible = MutableStateFlow(false)
    /**
     * 冷启动接管（2026-09-13）：以持久化标记 seed。若上一次进程在 Core 自更新中途被杀，
     * 标记仍为 true → 这里直接置 true，使 [UpdatePromptState.coreStatus] 能读取轮询到的
     * 后端真实进度（否则 coreStatus 恒为 null，只会显示「更新 Core」按钮而非进度）。
     */
    private val _coreTriggered = MutableStateFlow(coreUpdatePersistence.isCoreUpdating())
    /** 顺序阶段：core 优先；双方都有更新时先弹 core，core 完成/跳过后再弹 app。 */
    private val _phase = MutableStateFlow("core")

    /**
     * 本次进程内是否已经自动弹过一次。
     *
     * 节流语义：**本次启动的上升沿只弹一次**，用户关掉之后本次启动内不再自动重弹
     * （手动点「检查更新」仍可打开，见 [show]）。刻意不做持久化的「忽略此版本」——
     * 那是另一档需求，需要按版本号记住用户选择。
     *
     * 放在 ViewModel 级而不是 Composable 的 remember 里：旋屏/切页会重建 Composable，
     * 用 remember 就等于"每次回到那个页面又是一次上升沿"。
     */
    private var autoPromptUsed = false

    /** core 是否仍待处理：有新版可更，或已触发且尚未到终态（done/failed）。 */
    private fun corePending(coreHasUpdate: Boolean, coreTriggered: Boolean, status: UpdateStatusResponse?): Boolean =
        coreHasUpdate || (coreTriggered && status?.state !in setOf("done", "failed"))

    /** app 是否仍待处理：有可下/装状态，或失败待重试。 */
    private fun appPending(app: FrontendUpdateState): Boolean =
        app.state in UpdatePromptState.APP_ACTIONABLE_STATES || app.state == "error"

    /** 可见性 + core 是否已触发 + 当前阶段，合并成一个流。
     * 注意：kotlinx 的 combine 只到 5 个显式类型参数，第 6 个会退化成
     * `combine(vararg, (Array<Any?>)->R)` 重载，lambda 收不到具体类型 —— 所以这里先把这三个
     * 标志聚合成 [PromptFlags]，外层继续用 4 参数的 combine。 */
    private val flags: StateFlow<PromptFlags> = combine(_visible, _coreTriggered, _phase) { visible, coreTriggered, phase ->
        PromptFlags(visible, coreTriggered, phase)
    }.stateIn(scope, SharingStarted.Eagerly, PromptFlags())

    /** 聚合后的提示状态，供 MainActivity 与 AboutDeviceScreen **共用同一个槽**，按阶段顺序展示。 */
    val promptState: StateFlow<UpdatePromptState> = combine(
        appUpdate, coreUpdate, coreInstallStatus, flags
    ) { app, core, install, f ->
        val showCore = f.phase == "core" && corePending(core.hasUpdate, f.coreTriggered, install)
        val showApp = f.phase == "app" && appPending(app)
        UpdatePromptState(
            visible = f.visible,
            app = app,
            coreHasUpdate = core.hasUpdate,
            coreCurrentVersion = core.serverVersion.orEmpty(),
            coreLatestVersion = core.latestVersion.orEmpty(),
            coreChangelog = core.changelog,
            coreTriggered = f.coreTriggered,
            // 没触发过就不看 updateDeviceState：那个 state 也被「手动推送 APK」链路复用
            coreStatus = if (f.coreTriggered) install else null,
            showCoreSection = showCore,
            showAppSection = showApp
        )
    }.stateIn(scope, SharingStarted.Eagerly, UpdatePromptState())

    /** 顺序展示的内部标志：可见性 / core 是否已触发 / 当前阶段（core 优先）。 */
    private data class PromptFlags(
        val visible: Boolean = false,
        val coreTriggered: Boolean = false,
        val phase: String = "core"
    )

    init {
        // 冷启动接管（2026-09-13，前端重连接管）：若持久化标记仍为「进行中」，
        // 说明上一次进程在 Core 自更新中途被杀、后端其实还在更。恢复轮询以接管进度显示
        // （[onResumeCorePolling] 指向 ToolsModule.startDeviceUpdatePolling）。
        // 不放进 collect 是因为这是一次性的冷启动动作，与后续状态变化无关。
        if (coreUpdatePersistence.isCoreUpdating()) {
            onResumeCorePolling?.invoke()
        }

        // 自动提示：等价于对 hasAnyUpdate 取上升沿。判据里带 `!s.visible` 是为了不跟
        // 手动打开抢 —— 手动 show() 也会把 autoPromptUsed 置真，后面不会再自动弹第二次。
        // 另含 Core→App 的自动推进：core 阶段已无可展示（更新完成或用户跳过）而 app 仍待更新时，
        // 切到 app 阶段、弹窗保持打开、不整体关闭。
        scope.launch {
            promptState.collect { s ->
                // 终态清标记：core 更新到 done/failed，前端重连接管的使命结束，清回 false。
                // 与 ToolsModule.triggerDeviceUpdate 的置位配对，保证下次冷启动不会误判「在更」。
                if (s.coreTriggered && s.coreStatus?.state in setOf("done", "failed")) {
                    coreUpdatePersistence.setCoreUpdating(false)
                }
                if (_phase.value == "core" && !corePending(s.coreHasUpdate, s.coreTriggered, s.coreStatus) && appPending(s.app)) {
                    _phase.value = "app"
                    return@collect
                }
                if (autoPromptUsed || s.visible || !s.hasAnyUpdate) return@collect
                autoPromptUsed = true
                // 起始阶段：core 有更新则先弹 core，否则直接弹 app
                _phase.value = if (s.coreHasUpdate) "core" else "app"
                _visible.value = true
            }
        }
    }

    /** 主动打开弹窗（关于页「检查更新」）。同时吃掉本次启动的自动提示额度。 */
    fun show() {
        autoPromptUsed = true
        _phase.value = if (promptState.value.coreHasUpdate) "core" else "app"
        _visible.value = true
    }

    /**
     * 关闭弹窗。顺序语义：当前是 core 阶段且 app 仍有更新时，关掉 core 弹窗后**继续弹 app**
     * （两者独立、顺序展示），而不是整体关闭；只有 app 阶段或 app 无更新时才真正关闭。
     * core 更新已到终态（done/failed）或从未触发时，顺手把 [_coreTriggered] 清掉。
     */
    fun dismiss() {
        val s = promptState.value
        if (s.showCoreSection && appPending(s.app)) {
            _phase.value = "app"
            return
        }
        val coreState = s.coreStatus?.state
        if (coreState == null || coreState == "done" || coreState == "failed") {
            _coreTriggered.value = false
        }
        _phase.value = "core"
        _visible.value = false
    }

    /** 用户在弹窗里二次确认了「更新 Core」，此后才认 `updateDeviceState` 的进度。 */
    fun markCoreUpdateTriggered() {
        _coreTriggered.value = true
    }
}