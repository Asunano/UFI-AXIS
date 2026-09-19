package com.ufi_axis.installer.remoteadb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI 需要响应的两种确认弹窗。 */
enum class RemoteAdbInteraction { NONE, WIRE_CONFIRM, REBOOT_CONFIRM }

/**
 * 开启远程 ADB 的快照状态，由 [RemoteAdbEngine] 通过 StateFlow 暴露给 UI。
 */
data class RemoteAdbState(
    val stage: RemoteAdbStage = RemoteAdbStage.IDLE,
    val interaction: RemoteAdbInteraction = RemoteAdbInteraction.NONE,
    /** 0..100 为确定进度；-1 表示不确定（环形转圈）。 */
    val progress: Int = -1,
    val statusText: String = "准备就绪",
    /** 设备后台地址（IP:端口，goform 用）。 */
    val address: String = "",
    /** 供用户使用的 adb 连接地址（IP:5555）。 */
    val adbAddress: String = "",
    val errorMessage: String = "",
    val finished: Boolean = false,
    val success: Boolean = false,
) {
    val running: Boolean get() = stage.isBusy
    val showResult: Boolean get() = stage == RemoteAdbStage.DONE || stage == RemoteAdbStage.FAILED
    val awaitingWireConfirm: Boolean get() = interaction == RemoteAdbInteraction.WIRE_CONFIRM
    val awaitingRebootConfirm: Boolean get() = interaction == RemoteAdbInteraction.REBOOT_CONFIRM
}
