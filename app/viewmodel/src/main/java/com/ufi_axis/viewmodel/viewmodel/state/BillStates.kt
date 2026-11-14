package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.bill.BillSnapshot

/** 话费账单页状态（2026-10-31）。 */
sealed interface BillUiState {
    data object Loading : BillUiState
    /**
     * 未登录运营商账号：页面显示登录卡，而不是「加载失败」。
     *
     * [cachedBill] 非空 = core 返回了最近一次缓存；页面要在登录卡上方同时展示这份数据，
     * 并打上「需要登录」标签（2026-11-09）。
     */
    data class NeedsLogin(
        val message: String? = null,
        val cachedBill: BillSnapshot? = null,
    ) : BillUiState
    data class Ready(val bill: BillSnapshot) : BillUiState
    data class Error(val message: String) : BillUiState
}
