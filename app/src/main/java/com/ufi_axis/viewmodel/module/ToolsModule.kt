package com.ufi_axis.viewmodel.module

import android.content.Context
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.*
import com.ufi_axis.util.AppGson
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ToolsModule(
    private val api: UfiAxisApi,
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val gson = AppGson.instance
    // ── State ──
    private val _toolsState = MutableStateFlow(ToolsState())
    val toolsState: StateFlow<ToolsState> = _toolsState.asStateFlow()

    private val _alertsState = MutableStateFlow(AlertsState())
    val alertsState: StateFlow<AlertsState> = _alertsState.asStateFlow()

    private val _adbState = MutableStateFlow(AdbState())
    val adbState: StateFlow<AdbState> = _adbState.asStateFlow()

    private val _tasksState = MutableStateFlow(TasksState())
    val tasksState: StateFlow<TasksState> = _tasksState.asStateFlow()

    private val _smsForwardState = MutableStateFlow(SmsForwardState())
    val smsForwardState: StateFlow<SmsForwardState> = _smsForwardState.asStateFlow()

    private val _debugLogState = MutableStateFlow(DebugLogState())
    val debugLogState: StateFlow<DebugLogState> = _debugLogState.asStateFlow()

    private val _trafficManagementState = MutableStateFlow(TrafficManagementState())
    val trafficManagementState: StateFlow<TrafficManagementState> = _trafficManagementState.asStateFlow()

    private val _qosConfigState = MutableStateFlow(QosConfigState())
    val qosConfigState: StateFlow<QosConfigState> = _qosConfigState.asStateFlow()

    // ── Cross-module Events ──
    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private fun emitNetworkError(msg: String?) {
        _events.tryEmit(UiEvent.ShowNetworkError(msg))
    }

    // ── AT Command ──
    fun sendAtCommand(command: String) {
        scope.launch {
            _toolsState.value = _toolsState.value.copy(isLoading = true, errorMessage = null)
            try {
                val response = api.sendAtCommand(AtCommandRequest(command))
                val historyItem = "> ${response.command}\n${response.response}"
                _toolsState.value = _toolsState.value.copy(atResponse = response.response,
                    atHistory = listOf(historyItem) + _toolsState.value.atHistory, isLoading = false)
            } catch (e: Exception) {
                _toolsState.value = _toolsState.value.copy(atResponse = "AT 通道未连接或指令执行失败", isLoading = false, errorMessage = "AT 指令失败: ${e.message}")
            }
        }
    }

    // ── SMS ──
    fun sendSms(phone: String, message: String) {
        scope.launch {
            _toolsState.value = _toolsState.value.copy(isLoading = true, errorMessage = null)
            try {
                val response = api.sendSms(SmsSendRequest(phone, message))
                _toolsState.value = _toolsState.value.copy(isLoading = false, errorMessage = if (!response.success) "短信发送失败" else null)
                if (response.success) {
                    loadSmsList()
                    loadSmsContacts()
                    // 如果正在对话中，刷新对话
                    if (_toolsState.value.conversationPhone.isNotEmpty()) {
                        openConversation(_toolsState.value.conversationPhone)
                    }
                }
            } catch (e: Exception) { _toolsState.value = _toolsState.value.copy(isLoading = false, errorMessage = "发送失败: ${e.message}") }
        }
    }

    /** 加载联系人列表（后端已按号码聚合，含总数、未读数、最新消息） */
    fun loadSmsContacts() {
        scope.launch {
            _toolsState.value = _toolsState.value.copy(isLoading = true)
            try {
                val resp = api.getSmsContacts()
                _toolsState.value = _toolsState.value.copy(smsContacts = resp.contacts, isLoading = false)
            } catch (e: Exception) {
                _toolsState.value = _toolsState.value.copy(isLoading = false, errorMessage = "短信联系人加载失败: ${e.message}")
            }
        }
    }

    /** WS 推送更新联系人列表（免 HTTP 请求，实时性高） */
    fun updateSmsContactsFromWs(contacts: List<SmsContact>) {
        _toolsState.value = _toolsState.value.copy(smsContacts = contacts)
    }

    fun loadSmsList() {
        scope.launch {
            try { _toolsState.value = _toolsState.value.copy(smsList = api.getSmsList().messages) }
            catch (e: Exception) { _toolsState.value = _toolsState.value.copy(errorMessage = "短信列表加载失败: ${e.message}") }
        }
    }

    /** 打开联系人对话：加载最近一页消息（按号码过滤） */
    fun openConversation(phone: String) {
        scope.launch {
            _toolsState.update {
                it.copy(conversationPhone = phone, conversationMessages = emptyList(),
                    conversationOffset = 0, conversationHasMore = true,
                    conversationLoading = true, conversationTotal = 0)
            }
            try {
                val resp = api.getSmsList(limit = CONVERSATION_PAGE_SIZE, offset = 0, phone = phone)
                // 后端返回 DESC（最新在前），转为 ASC 用于对话展示
                val msgs = resp.messages.sortedBy { it.timestamp }
                val total = resp.total.coerceAtLeast(msgs.size.coerceAtLeast(resp.count))
                _toolsState.update {
                    it.copy(conversationMessages = msgs,
                        conversationOffset = msgs.size,
                        conversationHasMore = msgs.size < total,
                        conversationLoading = false,
                        conversationTotal = total)
                }
            } catch (e: Exception) {
                _toolsState.update { it.copy(conversationLoading = false, errorMessage = "对话加载失败: ${e.message}") }
            }
        }
    }

    /** 加载更早的消息（向上滚动触发），按号码过滤 */
    fun loadMoreConversation() {
        val phone = _toolsState.value.conversationPhone
        if (phone.isEmpty() || _toolsState.value.conversationLoading || !_toolsState.value.conversationHasMore) return
        scope.launch {
            val offset = _toolsState.value.conversationOffset
            _toolsState.update { it.copy(conversationLoading = true) }
            try {
                val resp = api.getSmsList(limit = CONVERSATION_PAGE_SIZE, offset = offset, phone = phone)
                val older = resp.messages.sortedBy { it.timestamp }
                val total = resp.total.coerceAtLeast(
                    (older.size + _toolsState.value.conversationMessages.size).coerceAtLeast(resp.count))
                val newOffset = offset + older.size
                _toolsState.update {
                    it.copy(
                        conversationMessages = older + it.conversationMessages,
                        conversationOffset = newOffset,
                        conversationHasMore = newOffset < total,
                        conversationLoading = false,
                        conversationTotal = total
                    )
                }
            } catch (e: Exception) {
                _toolsState.update { it.copy(conversationLoading = false, errorMessage = "加载更多消息失败: ${e.message}") }
            }
        }
    }

    /** 关闭对话，清理分页状态 */
    fun closeConversation() {
        _toolsState.update {
            it.copy(conversationPhone = "", conversationMessages = emptyList(),
                conversationOffset = 0, conversationHasMore = true,
                conversationLoading = false, conversationTotal = 0)
        }
    }

    companion object {
        private const val CONVERSATION_PAGE_SIZE = 100
    }

    // ── Shell Exec ──
    fun executeShell(command: String, asRoot: Boolean = true) {
        scope.launch {
            _toolsState.value = _toolsState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = api.shellExec(ShellExecRequest(command, asRoot, 30))
                val output = buildString {
                    if (result.stdout.isNotBlank()) append(result.stdout)
                    if (result.stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("[stderr]\n").append(result.stderr)
                    }
                    append("\n[exit: ${result.exit_code}]")
                }
                val historyItem = "> ${if (asRoot) "# " else "$ "}$command\n$output"
                _toolsState.value = _toolsState.value.copy(
                    shellResponse = output,
                    shellHistory = listOf(historyItem) + _toolsState.value.shellHistory,
                    isLoading = false
                )
            } catch (e: Exception) {
                _toolsState.value = _toolsState.value.copy(
                    shellResponse = "Shell 执行失败: ${e.message}",
                    isLoading = false,
                    errorMessage = "Shell 失败: ${e.message}"
                )
            }
        }
    }

    fun deleteSms(id: String) {
        val idLong = id.toLongOrNull() ?: return
        scope.launch {
            try {
                // 1. 立即标记为"删除中"，触发 UI 渐出动画
                _toolsState.update { it.copy(deletingMessageIds = it.deletingMessageIds + idLong) }

                // 2. 调用后端删除（goform + ContentResolver）
                api.deleteSms(mapOf("id" to id))

                // 3. 等待动画播放完成（350ms fadeOut）
                delay(350L)

                // 4. 从对话消息列表 + 全局短信列表移除，更新 offset/total
                _toolsState.update { state ->
                    state.copy(
                        smsList = state.smsList.filter { it.id != idLong },
                        conversationMessages = state.conversationMessages.filter { m -> m.id != idLong },
                        conversationTotal = (state.conversationTotal - 1).coerceAtLeast(0),
                        conversationOffset = (state.conversationOffset - 1).coerceAtLeast(0),
                        deletingMessageIds = state.deletingMessageIds - idLong
                    )
                }
                // 5. WS 短信缓存（3s 轮询 + 5条快速 + 500条保底）会自动推送最新联系人列表
            }
            catch (e: Exception) {
                // 失败：撤销动画状态
                _toolsState.update { it.copy(deletingMessageIds = it.deletingMessageIds - idLong, errorMessage = "删除失败: ${e.message}") }
            }
        }
    }

    fun markSmsRead(id: String) {
        scope.launch {
            try {
                api.markSmsRead(mapOf("id" to id))
                loadSmsList()
                loadSmsContacts()
                // 如果正在对话中，更新消息已读状态
                val idLong = id.toLongOrNull()
                if (idLong != null) {
                    _toolsState.update {
                        it.copy(conversationMessages = it.conversationMessages.map { m ->
                            if (m.id == idLong) m.copy(read = true) else m
                        })
                    }
                }
            }
            catch (e: Exception) { _toolsState.value = _toolsState.value.copy(errorMessage = "操作失败: ${e.message}") }
        }
    }

    fun switchSimSlot(slot: Int) {
        scope.launch {
            try { api.switchSimSlot(mapOf("slot" to slot)) }
            catch (e: Exception) { emitNetworkError("切换卡槽失败: ${e.message}") }
        }
    }

    // ── Alerts ──
    fun loadAlerts() {
        scope.launch {
            _alertsState.value = _alertsState.value.copy(isLoading = true, errorMessage = null)
            try {
                val config = async { runCatching { api.getAlertConfig() } }
                val list = async { runCatching { api.getAlertList(50) } }
                val rConfig = config.await(); val rList = list.await()

                val failures = listOfNotNull(
                    "配置" to rConfig, "列表" to rList
                ).filter { it.second.isFailure }.joinToString("; ") { (n, r) ->
                    "$n: ${r.exceptionOrNull()?.message ?: "未知"}"
                }.ifEmpty { null }

                _alertsState.value = AlertsState(
                    config = rConfig.getOrNull(),
                    alerts = rList.getOrNull()?.alerts ?: emptyList(),
                    isLoading = false,
                    errorMessage = failures
                )
            } catch (e: Exception) { _alertsState.value = _alertsState.value.copy(isLoading = false, errorMessage = "加载告警失败: ${e.message}") }
        }
    }

    fun updateAlertConfig(config: AlertConfig) {
        scope.launch {
            try { api.updateAlertConfig(config); loadAlerts() }
            catch (e: Exception) { _alertsState.value = _alertsState.value.copy(errorMessage = "更新告警配置失败: ${e.message}") }
        }
    }

    fun ackAlert(id: Long) {
        scope.launch {
            try { api.ackAlert(AckRequest(id)); loadAlerts() }
            catch (e: Exception) { _alertsState.value = _alertsState.value.copy(errorMessage = "确认告警失败: ${e.message}") }
        }
    }

    // ── ADB ──
    fun refreshAdbStatus() {
        scope.launch {
            _adbState.value = _adbState.value.copy(isLoading = true)
            try {
                val status = api.getAdbStatus()
                val autoStart = try {
                    val resp = api.getAdbAutoStart()
                    resp.getAsJsonObject().get("auto_start_on_boot")?.asBoolean ?: false
                } catch (e: Exception) { DebugLog.w("Tools", "refreshAdbStatus: getAdbAutoStart failed", e); _adbState.value.autoStartOnBoot }  // 网络失败保留旧值
                _adbState.value = AdbState(status = status, autoStartOnBoot = autoStart)
            }
            catch (e: Exception) { _adbState.value = AdbState(errorMessage = "获取ADB状态失败: ${e.message}") }
        }
    }

    fun startAdb(port: Int = 5555) {
        scope.launch {
            _adbState.value = _adbState.value.copy(isLoading = true, errorMessage = null)
            try {
                val resp = api.startAdb(mapOf("port" to port))
                if (!resp.success) _adbState.value = _adbState.value.copy(errorMessage = "启动失败", isLoading = false)
                else refreshAdbStatus()
            } catch (e: Exception) { _adbState.value = _adbState.value.copy(errorMessage = "启动失败: ${e.message}", isLoading = false) }
        }
    }

    fun stopAdb() {
        scope.launch {
            _adbState.value = _adbState.value.copy(isLoading = true, errorMessage = null)
            try {
                val resp = api.stopAdb()
                if (!resp.success) _adbState.value = _adbState.value.copy(errorMessage = "停止失败", isLoading = false)
                else refreshAdbStatus()
            } catch (e: Exception) { _adbState.value = _adbState.value.copy(errorMessage = "停止失败: ${e.message}", isLoading = false) }
        }
    }

    fun setAdbAutoStart(enabled: Boolean) {
        scope.launch {
            try {
                api.setAdbAutoStart(mapOf("enabled" to enabled))
                _adbState.value = _adbState.value.copy(autoStartOnBoot = enabled)
            } catch (e: Exception) {
                _adbState.value = _adbState.value.copy(errorMessage = "设置失败: ${e.message}")
            }
        }
    }

    // ── SMS Forward ──
    fun loadSmsForwardConfig() {
        scope.launch {
            _smsForwardState.value = _smsForwardState.value.copy(isLoading = true)
            try { _smsForwardState.value = SmsForwardState(config = api.getSmsForwardConfig()) }
            catch (e: Exception) { _smsForwardState.value = SmsForwardState(errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun saveSmsForwardConfig(config: SmsForwardConfig) {
        scope.launch {
            _smsForwardState.value = _smsForwardState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = api.saveSmsForwardConfig(config)
                if (result.success) { _smsForwardState.value = _smsForwardState.value.copy(isLoading = false); loadSmsForwardConfig() }
                else _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = "保存失败：服务器返回失败")
            } catch (e: Exception) { _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = "保存失败: ${e.message}") }
        }
    }

    fun testSmsForward() {
        scope.launch {
            _smsForwardState.value = _smsForwardState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = api.testSmsForward()
                val obj = result.getAsJsonObject()
                val success = obj.get("success")?.getAsBoolean() ?: false
                val error = obj.get("error")?.getAsString()
                _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = if (success) null else (error ?: "测试发送失败"))
            } catch (e: Exception) { _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = "测试失败: ${e.message}") }
        }
    }

    // ── Scheduled Tasks ──
    fun loadTaskList() {
        scope.launch {
            _tasksState.value = _tasksState.value.copy(isLoading = true)
            try { _tasksState.value = TasksState(tasks = api.getTaskList().tasks) }
            catch (e: Exception) { _tasksState.value = TasksState(errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun createTask(task: ScheduledTask) {
        scope.launch {
            try { api.createTask(task); loadTaskList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "创建失败: ${e.message}") }
        }
    }

    fun updateTask(id: String, task: ScheduledTask) {
        scope.launch {
            try { api.updateTask(id, task); loadTaskList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "更新失败: ${e.message}") }
        }
    }

    fun deleteTask(id: String) {
        scope.launch {
            try { api.deleteTask(id); loadTaskList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "删除失败: ${e.message}") }
        }
    }

    fun clearTasks() {
        scope.launch {
            try { api.clearTasks(); loadTaskList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "清除失败: ${e.message}") }
        }
    }

    // ── Debug Logs ──
    fun loadDebugLogs(level: String? = _debugLogState.value.filterLevel) {
        scope.launch {
            _debugLogState.value = _debugLogState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = api.getDebugLogs(level, 300)
                val obj = result.getAsJsonObject()
                val logsArray = obj.getAsJsonArray("logs")
                val logs = logsArray?.map { it.asString } ?: emptyList()
                _debugLogState.value = _debugLogState.value.copy(logs = logs, isLoading = false, filterLevel = level)
            } catch (e: Exception) { _debugLogState.value = _debugLogState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun clearDebugLogs() {
        scope.launch {
            try {
                api.clearDebugLogs()
                _debugLogState.value = DebugLogState(filterLevel = _debugLogState.value.filterLevel)
            } catch (e: Exception) { _debugLogState.value = _debugLogState.value.copy(errorMessage = "清除失败: ${e.message}") }
        }
    }

    fun setDebugLogFilter(level: String?) {
        _debugLogState.value = _debugLogState.value.copy(filterLevel = level)
        loadDebugLogs(level)
    }

    fun toggleDebugLogAutoRefresh(enabled: Boolean) {
        _debugLogState.value = _debugLogState.value.copy(autoRefresh = enabled)
    }

    fun syncDebugMode(enabled: Boolean) {
        scope.launch {
            try { api.updateConfig(mapOf("debug_mode" to enabled)) }
            catch (e: Exception) { DebugLog.w("Tools", "syncDebugMode failed: ${e.message}") }
        }
    }

    // ── Traffic Management ──
    fun loadTrafficLimit() {
        scope.launch {
            _trafficManagementState.value = _trafficManagementState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            try {
                val result = api.getTrafficLimit()
                // 检查响应中是否包含错误字段
                val jsonObj = result.asJsonObject
                if (jsonObj.has("error")) {
                    _trafficManagementState.value = _trafficManagementState.value.copy(
                        isLoading = false,
                        errorMessage = jsonObj.get("error")?.asString ?: "查询失败"
                    )
                    return@launch
                }
                val config = gson.fromJson(result, TrafficLimitConfig::class.java)
                _trafficManagementState.value = _trafficManagementState.value.copy(limitConfig = config, isLoading = false)
            } catch (e: Exception) {
                _trafficManagementState.value = _trafficManagementState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
            }
        }
    }

    fun saveDataLimit(enabled: Boolean, limitSize: String, limitUnit: String,
                      alertPercent: String, autoClear: Boolean, clearDate: String) {
        scope.launch {
            _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = true, errorMessage = null, successMessage = null)
            try {
                val resp = api.setDataLimit(mapOf(
                    "enabled" to enabled, "limit_size" to limitSize, "limit_unit" to limitUnit,
                    "alert_percent" to alertPercent, "auto_clear" to autoClear, "clear_date" to clearDate))
                _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = false,
                    successMessage = if (resp.success) "设置已保存" else null,
                    errorMessage = if (!resp.success) "保存失败" else null)
                if (resp.success) loadTrafficLimit()
            } catch (e: Exception) { _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = false, errorMessage = "保存失败: ${e.message}") }
        }
    }

    fun calibrateFlow(way: String, data: String, time: String = "0") {
        scope.launch {
            _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = true, errorMessage = null, successMessage = null)
            try {
                val resp = api.calibrateFlow(mapOf("way" to way, "data" to data, "time" to time))
                _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = false,
                    successMessage = if (resp.success) "校准成功" else null,
                    errorMessage = if (!resp.success) "校准失败" else null)
                if (resp.success) loadTrafficLimit()
            } catch (e: Exception) { _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = false, errorMessage = "校准失败: ${e.message}") }
        }
    }

    fun clearTrafficMessage() {
        _trafficManagementState.value = _trafficManagementState.value.copy(errorMessage = null, successMessage = null)
    }

    // ── Config Sync ──
    fun syncGatewayConfig(ip: String, password: String, port: Int = 8080) {
        scope.launch {
            try {
                DebugLog.d("Config", "syncing goform: ip=$ip port=$port")
                api.updateConfig(mapOf("goform_ip" to ip, "goform_port" to port, "goform_password" to password))
            } catch (e: Exception) { DebugLog.w("Config", "syncGatewayConfig failed", e) }
        }
    }

    // ── QoS Config ──
    fun loadQosConfig() {
        scope.launch {
            _qosConfigState.value = _qosConfigState.value.copy(isLoading = true, errorMessage = null)
            try {
                val cfg = api.getConfig()
                _qosConfigState.value = _qosConfigState.value.copy(
                    config = QosConfig(
                        enabled = cfg.qos_enabled,
                        shellMaxConcurrent = cfg.qos_shell_max_concurrent,
                        cacheTtlMs = cfg.qos_cache_ttl_ms,
                        goformQueryMax = cfg.qos_goform_query_max,
                        goformSetMax = cfg.qos_goform_set_max
                    ),
                    isLoading = false
                )
            } catch (e: Exception) {
                _qosConfigState.value = _qosConfigState.value.copy(
                    isLoading = false, errorMessage = "加载QoS配置失败: ${e.message}"
                )
            }
        }
    }

    fun saveQosConfig(enabled: Boolean, shellMax: Int, cacheTtl: Int, goformQuery: Int, goformSet: Int) {
        scope.launch {
            _qosConfigState.value = _qosConfigState.value.copy(isSaving = true, errorMessage = null)
            try {
                api.updateConfig(mapOf(
                    "qos_enabled" to enabled,
                    "qos_shell_max_concurrent" to shellMax,
                    "qos_cache_ttl_ms" to cacheTtl,
                    "qos_goform_query_max" to goformQuery,
                    "qos_goform_set_max" to goformSet
                ))
                _qosConfigState.value = _qosConfigState.value.copy(
                    config = QosConfig(enabled, shellMax, cacheTtl, goformQuery, goformSet),
                    isSaving = false
                )
            } catch (e: Exception) {
                _qosConfigState.value = _qosConfigState.value.copy(
                    isSaving = false, errorMessage = "保存QoS配置失败: ${e.message}"
                )
            }
        }
    }

    fun clearError() { _toolsState.value = _toolsState.value.copy(errorMessage = null) }

    // ── Smart Refresh (data_changed 精准增量刷新) ──
    fun smartRefresh(changedType: String) {
        when {
            changedType == "device:traffic-limit" -> loadTrafficLimit()
        }
    }
}