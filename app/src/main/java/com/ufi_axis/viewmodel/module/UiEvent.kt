package com.ufi_axis.viewmodel.module

/**
 * 跨模块 UI 事件总线。
 * Module 只发射事件（不关心谁处理），MainViewModel 统一收集并分发。
 */
sealed class UiEvent {
    data class ShowDashboardError(val message: String?) : UiEvent()
    data class ShowNetworkError(val message: String?) : UiEvent()
}