package com.ufi_axis.installer.state

/**
 * 安装引擎向界面暴露的唯一状态快照。
 *
 * 界面只读这个对象，不持有任何 ADB 引用 —— 这是把 UI 和协议层解耦的关键：
 * 协议层出问题不会让界面崩溃，界面重建也不会影响正在跑的安装。
 */
/**
 * 当前需要用户在界面上回应的交互类型。
 * 引擎在挂起等待用户输入前把它置为对应值，界面据此弹出对应的对话框；
 * 用户回应后引擎把它复位为 [NONE]。
 */
enum class InstallInteraction { NONE, CONFIRM, CONNECT_DECISION, ADDRESS_CHANGE, MANUAL_PACKAGE }

data class InstallState(
    val stage: InstallStage = InstallStage.IDLE,

    /** 需要用户在界面上回应的交互类型，NONE 表示无需回应 */
    val interaction: InstallInteraction = InstallInteraction.NONE,

    /** 进度条：0..100；-1 表示不确定进度（转圈） */
    val progress: Int = -1,

    /** 显示给用户的一句话状态 */
    val statusText: String = "准备就绪",

    /** 设备地址，如 192.168.0.1:5555 */
    val address: String = "",

    /** 解析出的包名 */
    val packageName: String = "",

    /** 需要用户确认时展示的 APK 文件名 */
    val pendingApkName: String = "",

    /** 需要用户确认时展示的 APK 大小（字节） */
    val pendingApkSize: Long = 0L,

    /** 权限授予进度：已成功 / 总数 */
    val grantedCount: Int = 0,
    val totalPermissions: Int = 0,

    /** 健康检查进度：第几次 / 总次数 */
    val healthAttempt: Int = 0,
    val healthMaxAttempts: Int = 0,

    /** 最终失败原因 */
    val errorMessage: String = "",

    /** 是否会话已结束（成功或失败） */
    val finished: Boolean = false,

    /** 结束时是否成功 */
    val success: Boolean = false,
) {
    /** 是否需要弹出「是否开始安装」的确认框 */
    val awaitingConfirm: Boolean get() = stage == InstallStage.CONFIRM

    /** 是否处于运行中（可以显示取消按钮、进度动画） */
    val running: Boolean get() = stage.isBusy

    /** 是否已完成且正在等待用户查看结果（用于展示重试/分享按钮） */
    val showResultActions: Boolean get() = stage == InstallStage.DONE || stage == InstallStage.FAILED
}
