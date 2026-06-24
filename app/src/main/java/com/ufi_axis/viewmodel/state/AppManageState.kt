package com.ufi_axis.viewmodel.state

import com.google.gson.JsonElement
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

// ========== Advanced Tools ==========

data class AdvancedState(
    val cpuCores: JsonElement? = null,
    val ttydRunning: Boolean = false,
    val iperf3Running: Boolean = false,
    val fotaStatus: JsonElement? = null,
    val bandwidthEnabled: Boolean = false,
    val bandwidthMbit: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val operationMessage: String? = null
)
