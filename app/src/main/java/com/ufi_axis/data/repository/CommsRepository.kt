package com.ufi_axis.data.repository

import com.google.gson.JsonElement
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.*

/** 短信 · AT · 告警 · 定时任务 · 短信转发 · 调试日志 · ADB */
class CommsRepository(private val api: UfiAxisApi) {
    // SMS
    suspend fun sendSms(phone: String, message: String) = api.sendSms(SmsSendRequest(phone, message))
    suspend fun getSmsList(limit: Int = 100, offset: Int = 0, phone: String? = null) = api.getSmsList(limit, offset, phone)
    suspend fun getSmsContacts() = api.getSmsContacts()
    suspend fun deleteSms(id: String) = api.deleteSms(mapOf("id" to id))
    suspend fun markSmsRead(id: String) = api.markSmsRead(mapOf("id" to id))

    // Shell
    suspend fun shellExec(command: String, asRoot: Boolean = true, timeout: Int = 30) =
        api.shellExec(ShellExecRequest(command, asRoot, timeout))

    // AT
    suspend fun sendAtCommand(command: String) = api.sendAtCommand(AtCommandRequest(command))
    suspend fun getAtStatus() = api.getAtStatus()

    // Alerts
    suspend fun getAlertConfig() = api.getAlertConfig()
    suspend fun updateAlertConfig(config: AlertConfig) = api.updateAlertConfig(config)
    suspend fun getAlertList(limit: Int = 20) = api.getAlertList(limit)
    suspend fun ackAlert(id: Long) = api.ackAlert(AckRequest(id))

    // Scheduled Tasks
    suspend fun getTaskList() = api.getTaskList()
    suspend fun getTask(id: String) = api.getTask(id)
    suspend fun createTask(task: ScheduledTask) = api.createTask(task)
    suspend fun updateTask(id: String, task: ScheduledTask) = api.updateTask(id, task)
    suspend fun deleteTask(id: String) = api.deleteTask(id)
    suspend fun clearTasks() = api.clearTasks()

    // SMS Forward
    suspend fun getSmsForwardConfig() = api.getSmsForwardConfig()
    suspend fun saveSmsForwardConfig(config: SmsForwardConfig) = api.saveSmsForwardConfig(config)
    suspend fun testSmsForward(): JsonElement = api.testSmsForward()

    // Debug Logs
    suspend fun getDebugLogs(level: String? = null, limit: Int = 200): JsonElement = api.getDebugLogs(level, limit)
    suspend fun clearDebugLogs(): JsonElement = api.clearDebugLogs()

    // ADB
    suspend fun getAdbStatus() = api.getAdbStatus()
    suspend fun startAdb(port: Int = 5555) = api.startAdb(mapOf("port" to port))
    suspend fun stopAdb() = api.stopAdb()
    suspend fun getAdbAutoStart(): Boolean {
        val resp = api.getAdbAutoStart()
        return resp.asJsonObject.get("auto_start_on_boot")?.asBoolean ?: false
    }
    suspend fun setAdbAutoStart(enabled: Boolean) = api.setAdbAutoStart(mapOf("enabled" to enabled))

    // Config (used by ToolsModule for debug/gateway config sync)
    suspend fun getConfig() = api.getConfig()
    suspend fun updateConfig(config: Map<String, @JvmSuppressWildcards Any>) = api.updateConfig(config)
}
