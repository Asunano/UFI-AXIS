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
    data class ShowWriteNotice(val message: String, val subtitle: String? = null) : UiEvent()
}
