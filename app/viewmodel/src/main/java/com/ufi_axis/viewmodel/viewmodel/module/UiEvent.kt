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
}