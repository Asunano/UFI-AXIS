package com.ufi_axis.viewmodel.module

/**
 * 跨模块 UI 事件总线。
 * Module 只发射事件（不关心谁处理），MainViewModel 统一收集并分发。
 */
sealed class UiEvent {
    data class ShowDashboardError(val message: String?) : UiEvent()
    data class ShowNetworkError(val message: String?) : UiEvent()
    /** 单条事件删除成功（ViewModel → UI 的成功 Toast 信号；乐观删除后对账成功才发射） */
    data class AlertDeleted(val id: Long) : UiEvent()

    /**
     * 写操作成功（2026-09-22）。与全局错误通道**对称**的「成功」通道。
     *
     * 为什么要有它：在此之前设置类写操作只有失败才提示、成功是静默的 —— 用户点完「保存」
     * 无法确认到底落地没有。而若让每个页面自己 `var toastMessage`，就会重演全局错误收敛前
     * 的老问题：同一段渲染代码抄 N 遍，漏一个页面就静默（见 MainViewModel 全局错误那段注释）。
     *
     * [message] 要说清**是哪一项**落地了（「Samba 已开启」而不是「保存成功」）：
     * 一个界面上往往有多个写操作，泛化文案让用户分不清生效的是哪个。
     * [subtitle] 用来补后果说明（例如「热点正在重启，连接会中断约 10 秒」）。
     */
    data class ShowWriteNotice(
        val message: String,
        val subtitle: String? = null,
        /**
         * 语义类型。默认 SUCCESS 保持既有行为（30+ 调用点不用改）。
         *
         * 为什么需要它：写操作的结果不都是"成功"——「未保存：需要配对密码」既不是错误
         * （门按设计工作），也不该配一个绿色对勾。类型交给 UI 决定图标与配色。
         */
        val type: WriteNoticeType = WriteNoticeType.SUCCESS
    ) : UiEvent()
}

/**
 * 写操作提示的语义类型。
 *
 * 与 `:app:ui` 的 [ToastType] 一一对应，但**不能直接复用**：`:app:viewmodel` 不依赖 `:app:ui`
 * （依赖方向是 ui → viewmodel），在这里引用会把依赖反过来。UI 层负责映射。
 */
enum class WriteNoticeType {
    /** 写操作落地 */
    SUCCESS,

    /** 中性说明（保存了，但还差一步） */
    INFO,

    /** 受保护/被拦下：门按设计工作，不是错误 */
    WARNING,

    /** 真的失败（网络、服务端拒绝） */
    ERROR,
}
