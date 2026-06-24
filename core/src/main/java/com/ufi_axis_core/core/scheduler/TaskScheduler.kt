package com.ufi_axis_core.core.scheduler

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ScheduledTask(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val name: String = "",
    val command: String = "",
    val hour: Int = 0,
    val minute: Int = 0,
    val repeatDaily: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
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
class TaskScheduler(private val context: Context) {
    private val tag = "TaskScheduler"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val tasks = ConcurrentHashMap<String, ScheduledTask>()
    private val triggeredToday = ConcurrentHashMap<String, String>() // taskId → dateStr
    private val prefs = context.getSharedPreferences("scheduled_tasks", Context.MODE_PRIVATE)

    private var mainJob: Job? = null
    private var pollJob: Job? = null
    private var lastScheduleDate: String? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        loadTasks()
        AppLogger.i(tag, "Loaded ${tasks.size} tasks, scheduling...")
        reschedule()
        startPolling()
    }

    fun list(): List<ScheduledTask> = tasks.values.sortedBy { it.createdAt }

    fun get(id: String): ScheduledTask? = tasks[id]

    fun add(task: ScheduledTask): Boolean {
        if (task.command.isBlank()) return false
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
        val now = Calendar.getInstance()
        return tasks.values
            .filter { it.enabled }
            .mapNotNull { task ->
                // 跳过非重复任务中已触发的
                if (!task.repeatDaily && triggeredToday.containsKey(task.id)) return@mapNotNull null

                val todayKey = dateFormat.format(Date(now.timeInMillis))
                if (task.repeatDaily && triggeredToday[task.id] == todayKey) return@mapNotNull null

                val target = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, task.hour)
                    set(Calendar.MINUTE, task.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (!target.after(now)) target.add(Calendar.DAY_OF_MONTH, 1)
                target.timeInMillis
            }
            .minOrNull()
    }

    private fun triggerMatchedTasks() {
        val now = Date()
        val nowTimeStr = timeFormat.format(now)
        val todayStr = dateFormat.format(now)
        var shouldPersist = false

        for ((_, task) in tasks) {
            if (!task.enabled) continue
            val taskTimeStr = "%02d:%02d".format(task.hour, task.minute)
            if (taskTimeStr != nowTimeStr) continue

            // 防止同一天内重复触发
            if (task.repeatDaily) {
                if (triggeredToday[task.id] == todayStr) continue
                triggeredToday[task.id] = todayStr
            } else {
                if (triggeredToday.containsKey(task.id)) continue
                triggeredToday[task.id] = todayStr
            }

            AppLogger.i(tag, "Triggering task: ${task.name} (${task.id})")
            scope.launch {
                try {
                    ShellExecutor.executeAsRoot(task.command, 120_000L)
                    AppLogger.i(tag, "Task completed: ${task.name}")
                } catch (e: Exception) {
                    AppLogger.e(tag, "Task failed: ${task.name}: ${e.message}")
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

    fun stop() {
        mainJob?.cancel()
        pollJob?.cancel()
        mainJob = null
        pollJob = null
        scope.cancel()
        AppLogger.i(tag, "Scheduler stopped")
    }
}