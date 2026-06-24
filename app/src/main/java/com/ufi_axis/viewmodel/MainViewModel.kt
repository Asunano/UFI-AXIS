package com.ufi_axis.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.repository.CommsRepository
import com.ufi_axis.data.repository.DeviceRepository
import com.ufi_axis.data.repository.FileAppRepository
import com.ufi_axis.data.repository.NetworkRepository
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.data.repository.WifiLanRepository
import com.ufi_axis.util.BackgroundManager
import com.ufi_axis.util.NetworkMonitor
import com.ufi_axis.viewmodel.module.*
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val deviceRepo: DeviceRepository,
    private val networkRepo: NetworkRepository,
    private val wifiLanRepo: WifiLanRepository,
    private val commsRepo: CommsRepository,
    private val fileAppRepo: FileAppRepository,
    private val webSocketRepository: WebSocketRepository,
    private val networkMonitor: NetworkMonitor,
    private val appContext: Context
) : ViewModel() {

    // ── Modules ──
    val dashboard = DashboardModule(deviceRepo, networkRepo, fileAppRepo, webSocketRepository, networkMonitor, appContext, viewModelScope)
    val network = NetworkModule(networkRepo, wifiLanRepo, appContext, viewModelScope)
    val tools = ToolsModule(commsRepo, networkRepo, appContext, viewModelScope)
    val files = FileManagerModule(fileAppRepo, appContext, viewModelScope)
    val apps = AppManagerModule(fileAppRepo, appContext, viewModelScope)
    val advanced = AdvancedModule(fileAppRepo, appContext, viewModelScope)
    val downloads = DownloadModule(appContext, viewModelScope)

    val backgroundManager = BackgroundManager(appContext)

    // ── StateFlows (backward-compatible) ──
    val dashboardState: StateFlow<DashboardState> get() = dashboard.dashboardState
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
        // Wire cross-module callbacks
        network.onDashboardRefresh = { dashboard.refreshDashboard() }
        network.onDashboardError = { msg -> dashboard.setDashboardError(msg) }
        tools.onNetworkError = { msg -> network.setError(msg) }

        // Start modules
        dashboard.init()

        // data_changed 事件 → 精准增量刷新
        collectDataChangedEvents()

        // sms_contacts WS 推送 → 工具模块联系人列表
        collectSmsContactsFromWs()
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
                        val contacts = com.google.gson.Gson().fromJson(
                            message.data,
                            com.ufi_axis.data.model.SmsContactListResponse::class.java
                        )
                        tools.updateSmsContactsFromWs(contacts.contacts)
                    } catch (_: Exception) {}
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
                        DeviceRepository(api),
                        NetworkRepository(api),
                        WifiLanRepository(api),
                        CommsRepository(api),
                        FileAppRepository(api),
                        webSocketRepository,
                        networkMonitor,
                        appContext
                    ) as T
                }
            }
        }
    }
}
