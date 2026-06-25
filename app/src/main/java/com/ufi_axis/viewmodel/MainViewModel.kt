package com.ufi_axis.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.BatteryInfo
import com.ufi_axis.data.model.CpuInfo
import com.ufi_axis.data.model.DeviceVersionResponse
import com.ufi_axis.data.model.MemoryInfo
import com.ufi_axis.data.model.SignalInfo
import com.ufi_axis.data.model.TrafficRealtime
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.util.BackgroundManager
import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.NetworkMonitor
import com.ufi_axis.viewmodel.module.*
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class MainViewModel(
    private val api: UfiAxisApi,
    private val webSocketRepository: WebSocketRepository,
    private val networkMonitor: NetworkMonitor,
    private val appContext: Context
) : ViewModel() {

    // ── Modules ──
    val dashboard = DashboardModule(api, webSocketRepository, networkMonitor, appContext, viewModelScope)
    val network = NetworkModule(api, appContext, viewModelScope)
    val tools = ToolsModule(api, appContext, viewModelScope)
    val files = FileManagerModule(api, appContext, viewModelScope)
    val apps = AppManagerModule(api, appContext, viewModelScope)
    val advanced = AdvancedModule(api, appContext, viewModelScope)
    val downloads = DownloadModule(appContext, viewModelScope)

    val backgroundManager = BackgroundManager(appContext)

    // ── StateFlows (backward-compatible) ──
    val dashboardState: StateFlow<DashboardState> get() = dashboard.dashboardState
    // ── 按字段派生的 StateFlow（各 screen 按需订阅，避免全量重组） ──
    val dashboardCpuInfo: StateFlow<CpuInfo?> get() = dashboard.cpuInfoState
    val dashboardMemoryInfo: StateFlow<MemoryInfo?> get() = dashboard.memoryInfoState
    val dashboardTrafficRealtime: StateFlow<TrafficRealtime?> get() = dashboard.trafficRealtimeState
    val dashboardSignalInfo: StateFlow<SignalInfo?> get() = dashboard.signalInfoState
    val dashboardBatteryInfo: StateFlow<BatteryInfo?> get() = dashboard.batteryInfoState
    val dashboardDeviceVersion: StateFlow<DeviceVersionResponse?> get() = dashboard.deviceVersionState
    val dashboardIsLoading: StateFlow<Boolean> get() = dashboard.isLoadingState
    val networkState: StateFlow<NetworkState> get() = network.networkState
    val toolsState: StateFlow<ToolsState> get() = tools.toolsState
    val alertsState: StateFlow<AlertsState> get() = tools.alertsState
    val updateState: StateFlow<UpdateState> get() = dashboard.updateState
    val adbState: StateFlow<AdbState> get() = tools.adbState
    val tasksState: StateFlow<TasksState> get() = tools.tasksState
    val smsForwardState: StateFlow<SmsForwardState> get() = tools.smsForwardState
    val appManageState: StateFlow<AppManageState> get() = apps.state
    val deviceSettingsState: StateFlow<DeviceSettingsState> get() = network.deviceSettingsState
    val speedTestState: StateFlow<SpeedTestState> get() = network.speedTestState
    val fileManagerState: StateFlow<FileManagerState> get() = files.state
    val debugLogState: StateFlow<DebugLogState> get() = tools.debugLogState
    val trafficManagementState: StateFlow<TrafficManagementState> get() = tools.trafficManagementState
    val qosConfigState: StateFlow<QosConfigState> get() = tools.qosConfigState
    val advancedState: StateFlow<AdvancedState> get() = advanced.state
    val monitorState: StateFlow<MonitorState> get() = dashboard.monitorState
    val downloadState: StateFlow<DownloadState> get() = downloads.state

    // ── Init & Cleanup ──
    init {
        // Start modules
        dashboard.init()

        // 收集跨模块事件，统一分发
        collectCrossModuleEvents()

        // data_changed 事件 → 精准增量刷新
        collectDataChangedEvents()

        // sms_contacts WS 推送 → 工具模块联系人列表
        collectSmsContactsFromWs()
    }

    /** 收集跨模块 UiEvent，统一分发到目标 Module */
    private fun collectCrossModuleEvents() {
        viewModelScope.launch {
            merge(network.events, tools.events).collect { event ->
                when (event) {
                    is UiEvent.ShowDashboardError -> dashboard.setDashboardError(event.message)
                    is UiEvent.ShowNetworkError -> network.setError(event.message)
                }
            }
        }
    }

    // ── Smart Refresh: 订阅 data_changed 事件，精准增量刷新 ──
    private fun collectDataChangedEvents() {
        viewModelScope.launch {
            webSocketRepository.dataChanged.collect { changedType ->
                when {
                    // Dashboard: 设备信息类变更
                    changedType.startsWith("device:") -> {
                        dashboard.smartRefresh(changedType)
                        network.smartRefresh(changedType)
                        tools.smartRefresh(changedType)
                    }
                    // Network: WiFi / 网络 / SIM 类变更
                    changedType.startsWith("wifi:") ||
                    changedType.startsWith("network:") ||
                    changedType == "sim:*" -> {
                        network.smartRefresh(changedType)
                    }
                }
            }
        }
    }

    /** sms_contacts WS 推送 → 直接更新联系人列表（免 HTTP 请求） */
    private fun collectSmsContactsFromWs() {
        viewModelScope.launch {
            webSocketRepository.messages.collect { message ->
                if (message.type == "sms_contacts") {
                    try {
                        val contacts = webSocketRepository.sharedGson.fromJson(
                            message.data,
                            com.ufi_axis.data.model.SmsContactListResponse::class.java
                        )
                        tools.updateSmsContactsFromWs(contacts.contacts)
                    } catch (e: Exception) {
                        DebugLog.w("MainViewModel", "Failed to parse sms_contacts from WebSocket: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        dashboard.onCleared()
    }

    // ── Common ──
    fun clearError() {
        dashboard.clearError()
        network.clearError()
        tools.clearError()
    }

    // ==================== Factory ====================
    companion object {
        fun provideFactory(
            api: UfiAxisApi,
            webSocketRepository: WebSocketRepository,
            networkMonitor: NetworkMonitor,
            appContext: Context
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(
                        api,
                        webSocketRepository,
                        networkMonitor,
                        appContext
                    ) as T
                }
            }
        }
    }
}