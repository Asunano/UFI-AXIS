package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.model.UpdateStatusResponse
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
 * 1. 把两条链路的 state **聚合**成一个 [UpdatePromptState]（弹窗按需渲染其中一块或两块）；
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
    coreInstallStatus: StateFlow<UpdateStatusResponse?>
) {
    private val _visible = MutableStateFlow(false)
    private val _coreTriggered = MutableStateFlow(false)

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

    /** 聚合后的提示状态，供 MainActivity 与 AboutDeviceScreen **共用同一个槽**。 */
    val promptState: StateFlow<UpdatePromptState> = combine(
        appUpdate, coreUpdate, coreInstallStatus, _visible, _coreTriggered
    ) { app, core, install, visible, coreTriggered ->
        UpdatePromptState(
            visible = visible,
            app = app,
            coreHasUpdate = core.hasUpdate,
            coreCurrentVersion = core.serverVersion.orEmpty(),
            coreLatestVersion = core.latestVersion.orEmpty(),
            coreChangelog = core.changelog,
            coreTriggered = coreTriggered,
            // 没触发过就不看 updateDeviceState：那个 state 也被「手动推送 APK」链路复用
            coreStatus = if (coreTriggered) install else null
        )
    }.stateIn(scope, SharingStarted.Eagerly, UpdatePromptState())

    init {
        // 自动提示：等价于对 hasAnyUpdate 取上升沿。判据里带 `!s.visible` 是为了不跟
        // 手动打开抢 —— 手动 show() 也会把 autoPromptUsed 置真，后面不会再自动弹第二次。
        scope.launch {
            promptState.collect { s ->
                if (autoPromptUsed || s.visible || !s.hasAnyUpdate) return@collect
                autoPromptUsed = true
                _visible.value = true
            }
        }
    }

    /** 主动打开弹窗（关于页「检查更新」）。同时吃掉本次启动的自动提示额度。 */
    fun show() {
        autoPromptUsed = true
        _visible.value = true
    }

    /**
     * 关闭弹窗。core 更新已到终态（done/failed）或从未触发时，顺手把 [_coreTriggered] 清掉，
     * 否则下次打开还会看到一块已经结束的「core 更新中」。
     */
    fun dismiss() {
        val coreState = promptState.value.coreStatus?.state
        if (coreState == null || coreState == "done" || coreState == "failed") {
            _coreTriggered.value = false
        }
        _visible.value = false
    }

    /** 用户在弹窗里二次确认了「更新 Core」，此后才认 `updateDeviceState` 的进度。 */
    fun markCoreUpdateTriggered() {
        _coreTriggered.value = true
    }
}