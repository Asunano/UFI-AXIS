// 统一更新提示的聚合状态（2026-09-06）。
//
// 背景：App 端原本有两条互不知情的更新提示链路 —— MainActivity 按 DashboardModule.updateState
// 自动弹「core 有新版本」，AboutDeviceScreen 按 ToolsModule.frontendUpdateState 弹「App 有新版本」，
// 各自一个 `remember { mutableStateOf(false) }`。两者都是独立平台 Window，同时满足条件时
// 弹窗直接叠加（两层 scrim 叠在一起，返回键只关最上层）。
//
// 现在提示状态只有这一个槽（[com.ufi_axis.viewmodel.module.UpdatePromptModule]），
// 「App 项」和「Core 项」是同一个弹窗里的两块内容，从构造上不可能叠加。
package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.UpdateStatusResponse

/**
 * 统一更新提示弹窗的完整输入。
 *
 * @param visible 弹窗是否展示。唯一真源在 UpdatePromptModule，UI 侧**不要**再另起布尔开关。
 * @param app App 自更新状态机（原样透传 [FrontendUpdateState]，弹窗要靠它渲染下载进度）。
 * @param coreHasUpdate core 是否有新版本（来自 `GET /api/update/backend-info` 的 has_update）。
 * @param coreCurrentVersion core 当前版本；@param coreLatestVersion 清单里的最新版本。
 * @param coreChangelog core 新版本更新日志。
 * @param coreTriggered 用户是否已在本弹窗里确认过「更新 Core」（`POST /api/update/check` 已发出）。
 *   只有它为真时 [coreStatus] 才有意义 —— `updateDeviceState` 也被「手动推送 APK」链路复用，
 *   不加这道闸会把别人的上传进度显示成 core 自更新进度。
 * @param coreStatus core 更新进度快照（ToolsModule 每 2s 轮询 `GET /api/update/status` 的结果）。
 */
data class UpdatePromptState(
    val visible: Boolean = false,
    val app: FrontendUpdateState = FrontendUpdateState(),
    val coreHasUpdate: Boolean = false,
    val coreCurrentVersion: String = "",
    val coreLatestVersion: String = "",
    val coreChangelog: String = "",
    val coreTriggered: Boolean = false,
    val coreStatus: UpdateStatusResponse? = null
) {
    /** App 侧有事可做：有新版可下 / 正在下 / 已下待装 / 正在装。 */
    val appHasUpdate: Boolean get() = app.state in APP_ACTIONABLE_STATES

    /** App 侧检查或下载失败（只在弹窗已经打开时展示，不作为自动弹出的理由）。 */
    val appFailed: Boolean get() = app.state == "error"

    /** 是否有**任何**可提示的更新 —— 自动弹出的唯一判据。 */
    val hasAnyUpdate: Boolean get() = appHasUpdate || coreHasUpdate

    /** 弹窗里是否渲染「App」那一块。 */
    val showAppSection: Boolean get() = appHasUpdate || appFailed

    /** 弹窗里是否渲染「Core」那一块（已触发更新时即便 has_update 翻回 false 也要留着看进度）。 */
    val showCoreSection: Boolean get() = coreHasUpdate || coreTriggered

    companion object {
        /** App 侧「值得在弹窗里出现」的状态集合。 */
        val APP_ACTIONABLE_STATES = setOf("available", "downloading", "downloaded", "installing")
    }
}