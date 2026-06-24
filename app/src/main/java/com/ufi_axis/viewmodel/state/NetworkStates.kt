package com.ufi_axis.viewmodel.state

import com.google.gson.JsonElement
import com.ufi_axis.data.model.*

// ========== Network ==========

data class NetworkState(
    val signalInfo: SignalInfo? = null,
    val networkStatus: NetworkStatusResponse? = null,
    val simInfo: SimInfoResponse? = null,
    val wifiSettings: JsonElement? = null,
    val wifiClients: JsonElement? = null,
    val wifiEnabled: Boolean = false,
    val mobileDataEnabled: Boolean = false,
    val bandStatus: JsonElement? = null,
    val cellInfo: JsonElement? = null,
    val blacklistInfo: JsonElement? = null,
    val lanSettings: JsonElement? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** 每次加载递增，确保缓存返回相同 JsonElement 时 StateFlow 仍能发射 */
    val loadVersion: Long = 0L
)

// ========== Device Settings ==========

data class DeviceSettingsState(
    val settings: JsonElement? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** 每次加载递增，确保缓存返回相同 JsonElement 时 StateFlow 仍能发射 */
    val loadVersion: Long = 0L
)

// ========== Speed Test ==========

data class SpeedTestState(
    val isRunning: Boolean = false,
    val result: String? = null,
    val errorMessage: String? = null,
    val testType: String = "" // "internal" | "external"
)
