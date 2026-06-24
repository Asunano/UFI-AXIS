package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.*

// ========== Tools ==========

data class ToolsState(
    val atResponse: String = "",
    val atHistory: List<String> = emptyList(),
    val smsList: List<SmsRecord> = emptyList(),
    val smsContacts: List<SmsContact> = emptyList(),
    val shellResponse: String = "",
    val shellHistory: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // ── 短信对话分页 ──
    val conversationPhone: String = "",
    val conversationMessages: List<SmsRecord> = emptyList(),
    val conversationOffset: Int = 0,
    val conversationHasMore: Boolean = true,
    val conversationLoading: Boolean = false,
    val conversationTotal: Int = 0,
    // ── 删除动画 ──
    val deletingMessageIds: Set<Long> = emptySet()
)

// ========== Alerts ==========

data class AlertsState(
    val config: AlertConfig? = null,
    val alerts: List<AlertRecord> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

// ========== ADB ==========

data class AdbState(
    val status: AdbStatus? = null,
    val autoStartOnBoot: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

// ========== Tasks ==========

data class TasksState(
    val tasks: List<ScheduledTask> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

// ========== SMS Forward ==========

data class SmsForwardState(
    val config: SmsForwardConfig? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

// ========== Debug Log ==========

data class DebugLogState(
    val logs: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val filterLevel: String? = null,
    val autoRefresh: Boolean = true,
    val errorMessage: String? = null
)

// ========== Traffic Management ==========

data class TrafficManagementState(
    val limitConfig: TrafficLimitConfig? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

// ========== QoS Config ==========

data class QosConfig(
    val enabled: Boolean = true,
    val shellMaxConcurrent: Int = 3,
    val cacheTtlMs: Int = 2000,
    val goformQueryMax: Int = 4,
    val goformSetMax: Int = 2
)

data class QosConfigState(
    val config: QosConfig? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)
