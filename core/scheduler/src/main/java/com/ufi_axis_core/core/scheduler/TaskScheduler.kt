package com.ufi_axis_core.core.scheduler

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.CronParser
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ExecutionLog(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val taskId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean,
    val output: String = ""
)

@Serializable
data class ScheduledTask(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val name: String = "",
    val actionType: String = "custom_shell",
    val params: Map<String, kotlinx.serialization.json.JsonPrimitive> = emptyMap(),
    val command: String = "",
    val hour: Int = 0,
    val minute: Int = 0,
    val repeatDaily: Boolean = true,
    // ── 时间触发扩展（v9 · 8 preset + 自定义 cron，向后兼容）──
    val triggerMode: String? = null,
    val scheduleType: String? = null,
    val cron: String? = null,
    val scheduleParams: Map<String, kotlinx.serialization.json.JsonPrimitive> = emptyMap(),
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val logs: List<ExecutionLog> = emptyList()
)

/**
 * 定时任务调度器
 *
 * 采用轮询+集中调度模式（参考 UFI-TOOLS-REF TaskScheduler）：
 * - 统一计算最近一个到期任务的时间，只维护一个 delay 协程
 * - 每 5 分钟自动 reschedule 校准，防止因系统休眠/时钟漂移导致的偏差
 * - 每日任务通过 hasTriggered 标记防止同一天内重复触发
 * - 跨日自动重置 hasTriggered 标志
 */
class TaskScheduler(
    private val context: Context,
    private val actionExecutor: ActionExecutor
) {
    private val tag = "TaskScheduler"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val tasks = ConcurrentHashMap<String, ScheduledTask>()
    private val triggeredToday = ConcurrentHashMap<String, String>() // taskId → dateStr
    private val prefs = context.getSharedPreferences("scheduled_tasks", Context.MODE_PRIVATE)

    private var mainJob: Job? = null
    private var pollJob: Job? = null
    private var lastScheduleDate: String? = null

    /**
     * 后台总闸（「停止服务」）是否已把调度暂停。
     *
     * 与 [stop] 的区别：[stop] 会 `scope.cancel()`，之后所有 launch 都是 no-op，只能重建实例；
     * [pause] 只 cancel 两个 Job，因此 [resume] 能原地恢复。
     * 置位期间 [reschedule] / [startPolling] 直接返回，否则增删改任务会把调度偷偷启回来。
     */
    @Volatile
    private var paused: Boolean = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val minuteFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    init {
        loadTasks()
        AppLogger.i(tag, "Loaded ${tasks.size} tasks, scheduling...")
        reschedule()
        startPolling()
    }

    fun list(): List<ScheduledTask> = tasks.values.sortedBy { it.createdAt }

    fun get(id: String): ScheduledTask? = tasks[id]

    fun getLogs(taskId: String): List<ExecutionLog> = tasks[taskId]?.logs ?: emptyList()

    fun add(task: ScheduledTask): Boolean {
        // 自定义 shell 需要 command 非空，API 动作不需要
        if (task.actionType == "custom_shell" && task.command.isBlank()) return false
        tasks[task.id] = task
        saveTasks()
        reschedule()
        AppLogger.i(tag, "Task added: ${task.name} (${task.id}) at ${"%02d".format(task.hour)}:${"%02d".format(task.minute)}")
        return true
    }

    fun remove(id: String): Boolean {
        tasks.remove(id) ?: return false
        triggeredToday.remove(id)
        saveTasks()
        reschedule()
        AppLogger.i(tag, "Task removed: $id")
        return true
    }

    fun update(task: ScheduledTask): Boolean {
        if (!tasks.containsKey(task.id)) return false
        tasks[task.id] = task
        triggeredToday.remove(task.id)
        saveTasks()
        reschedule()
        return true
    }

    fun clear() {
        tasks.clear()
        triggeredToday.clear()
        saveTasks()
        reschedule()
        AppLogger.i(tag, "All tasks cleared")
    }

    // ──────────── 调度核心 ────────────

    /**
     * 计算下一个触发时间并启动 delay 协程。
     * 只维护一个主 Job，取消旧 Job 后启动新 Job。
     */
    fun reschedule() {
        mainJob?.cancel()
        if (paused) return

        // 检查是否跨日，如是则重置 hasTriggered
        val todayStr = dateFormat.format(Date())
        if (lastScheduleDate != todayStr) {
            lastScheduleDate = todayStr
            triggeredToday.clear()
            AppLogger.d(tag, "New day ($todayStr), reset daily triggers")
        }

        val nextTriggerMs = getNextTriggerTimeMillis() ?: run {
            AppLogger.d(tag, "No upcoming triggers, idle")
            return
        }

        val delayMs = (nextTriggerMs - System.currentTimeMillis()).coerceAtLeast(0)
        val triggerTimeStr = timeFormat.format(Date(nextTriggerMs))
        AppLogger.i(tag, "Next trigger: $triggerTimeStr (delay=${delayMs / 1000}s)")

        mainJob = scope.launch {
            delay(delayMs)
            triggerMatchedTasks()
            reschedule() // 递归调度下一个
        }
    }

    /**
     * 每 5 分钟轮询校准，防止长时间 delay 因系统休眠偏
     */
    private fun startPolling() {
        pollJob?.cancel()
        if (paused) return
        pollJob = scope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L)
                AppLogger.d(tag, "Poll-triggered reschedule check")
                reschedule()
            }
        }
    }

    // ──────────── 内部逻辑 ────────────

    private fun getNextTriggerTimeMillis(): Long? {
        val nowMillis = System.currentTimeMillis()
        return tasks.values
            .filter { it.enabled && it.scheduleType != "every_n_seconds" }
            .mapNotNull { task ->
                if (task.cron != null) {
                    // ★ 优先 cron 路径（v9 时间触发扩展）
                    // 秒级定时后端不支持，无 cron，跳过（由 every_n_seconds 守卫保证不会进此分支）
                    CronParser.nextAfter(task.cron, nowMillis)
                } else {
                    // 旧路径：repeatDaily + hour:minute（向后兼容）
                    if (!task.repeatDaily && triggeredToday.containsKey(task.id)) return@mapNotNull null
                    val todayKey = dateFormat.format(Date(nowMillis))
                    if (task.repeatDaily && triggeredToday[task.id] == todayKey) return@mapNotNull null
                    val nowCal = Calendar.getInstance()
                    val target = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, task.hour)
                        set(Calendar.MINUTE, task.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (!target.after(nowCal)) target.add(Calendar.DAY_OF_MONTH, 1)
                    target.timeInMillis
                }
            }
            .minOrNull()
    }

    private fun triggerMatchedTasks() {
        val now = Date()
        val nowTimeStr = timeFormat.format(now)
        val todayStr = dateFormat.format(now)
        var shouldPersist = false

        val nowMillis = System.currentTimeMillis()
        val minuteBucket = minuteFormat.format(Date(nowMillis))
        for ((_, task) in tasks) {
            if (!task.enabled) continue
            // 秒级定时后端暂不支持，直接跳过（避免以旧路径误触发每日任务）
            if (task.scheduleType == "every_n_seconds") continue

            // 匹配：cron 优先，否则旧 repeatDaily + 时分路径
            val matched = if (task.cron != null) {
                CronParser.matches(task.cron, nowMillis)
            } else {
                val taskTimeStr = "%02d:%02d".format(task.hour, task.minute)
                taskTimeStr == nowTimeStr
            }
            if (!matched) continue

            // 防止重复触发
            if (task.cron != null) {
                // cron 周期任务按「分钟桶」去重（同分钟只触发一次）
                val bucketKey = "${task.id}@$minuteBucket"
                if (triggeredToday[task.id] == bucketKey) continue
                triggeredToday[task.id] = bucketKey
            } else {
                if (task.repeatDaily) {
                    if (triggeredToday[task.id] == todayStr) continue
                    triggeredToday[task.id] = todayStr
                } else {
                    if (triggeredToday.containsKey(task.id)) continue
                    triggeredToday[task.id] = todayStr
                }
            }

            AppLogger.i(tag, "Triggering task: ${task.name} (${task.id})")
            scope.launch {
                try {
                    val result = if (task.actionType == "custom_shell") {
                        // 向后兼容：shell 命令路径
                        val shellResult = ShellExecutor.executeAsRoot(task.command, 120_000L)
                        ActionResult(shellResult.isSuccess, shellResult.stdout.take(500))
                    } else {
                        // 新路径：API 动作
                        actionExecutor.execute(task.actionType, task.params)
                    }

                    val log = ExecutionLog(
                        taskId = task.id,
                        success = result.success,
                        output = result.message
                    )
                    val updatedLogs = (tasks[task.id]?.logs ?: emptyList()) + log
                    tasks[task.id] = task.copy(logs = updatedLogs.takeLast(50))
                    saveTasks()
                    AppLogger.i(tag, "Task '${task.name}' ${if (result.success) "succeeded" else "failed"}: ${result.message}")
                } catch (e: Exception) {
                    val log = ExecutionLog(
                        taskId = task.id,
                        success = false,
                        output = (e.message ?: "Unknown error").take(500)
                    )
                    val updatedLogs = (tasks[task.id]?.logs ?: emptyList()) + log
                    tasks[task.id] = task.copy(logs = updatedLogs.takeLast(50))
                    saveTasks()
                    AppLogger.e(tag, "Task '${task.name}' exception: ${e.message}")
                }
            }

            // 非重复任务触发后自动禁用
            if (!task.repeatDaily) {
                tasks[task.id] = task.copy(enabled = false)
                AppLogger.i(tag, "One-shot task '${task.name}' disabled after trigger")
            }
            shouldPersist = true
        }

        if (shouldPersist) saveTasks()
    }

    // ──────────── 持久化 ────────────

    private fun saveTasks() {
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ScheduledTask.serializer()),
            tasks.values.toList()
        )
        prefs.edit().putString("tasks_json", json).apply()
    }

    private fun loadTasks() {
        val json = prefs.getString("tasks_json", null) ?: return
        try {
            val list = kotlinx.serialization.json.Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ScheduledTask.serializer()),
                json
            )
            list.forEach { tasks[it.id] = it }
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to load tasks: ${e.message}")
        }
    }

    /**
     * 后台总闸关闭（用户点了「停止服务」）：暂停定时任务调度。
     *
     * 只 cancel 两个 Job，不动 scope —— 用户再点「启动服务」时 [resume] 能原地恢复。
     * 已在执行中的单次任务（`scope.launch` 出去的那批）不打断，让它自己收尾。
     */
    fun pause() {
        if (paused) return
        paused = true
        mainJob?.cancel()
        pollJob?.cancel()
        mainJob = null
        pollJob = null
        AppLogger.i(tag, "Scheduler paused (background services stopped)")
    }

    /** 后台总闸开启：重新计算下一个到期任务并恢复 5 分钟校准轮询。 */
    fun resume() {
        if (!paused) return
        paused = false
        reschedule()
        startPolling()
        AppLogger.i(tag, "Scheduler resumed")
    }

    fun stop() {
        mainJob?.cancel()
        pollJob?.cancel()
        mainJob = null
        pollJob = null
        scope.cancel()
        AppLogger.i(tag, "Scheduler stopped")
    }
}