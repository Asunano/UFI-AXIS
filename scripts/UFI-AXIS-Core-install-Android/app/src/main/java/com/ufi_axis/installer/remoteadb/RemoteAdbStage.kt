package com.ufi_axis.installer.remoteadb

/**
 * 开启远程 ADB 引导流程的阶段。
 * - 仅 [WIRE_CONFIRM] 为弹窗（交互），不显示环形进度。
 * - [DONE] / [FAILED] 为终态。
 */
enum class RemoteAdbStage(
    val title: String,
    val runningText: String,
) {
    IDLE("待开始", "准备就绪"),
    WIRE_CONFIRM("连接前提醒", "请确认有线连接"),
    LOGIN("登录设备后台", "正在登录设备后台…"),
    USB_SETTING("开启 USB/ADB 端口", "正在下发端口开启命令…"),
    DETECT("检测 ADB 端口", "正在检测 5555 端口…"),
    REBOOT("重启设备", "正在发送重启命令…"),
    DETECT_AFTER_REBOOT("重启后检测", "设备重启中，正在等待并重试连接…"),
    RELINK("重新登录", "正在重新登录设备后台…"),
    DONE("完成", "远程 ADB 已开启"),
    FAILED("失败", "开启失败");

    /** 是否处于“执行中”状态（需要显示环形进度、隐藏确认弹窗）。 */
    val isBusy: Boolean
        get() = this != IDLE && this != DONE && this != FAILED && this != WIRE_CONFIRM
}
