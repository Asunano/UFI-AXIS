package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.*

// ========== App Management ==========

data class AppManageState(
    val apps: List<AppItem> = emptyList(),
    val selectedApp: AppDetailResponse? = null,
    val filter: String = "user",
    val hasRoot: Boolean = false,
    val isLoading: Boolean = false,
    val installLoading: Boolean = false,
    val errorMessage: String? = null
)
