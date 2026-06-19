package com.ufi_axis_core.core.scheduler

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
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

class TaskScheduler(private val context: Context) {
    private val tag = "TaskScheduler"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val tasks = ConcurrentHashMap<String, ScheduledTask>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val prefs = context.getSharedPreferences("scheduled_tasks", Context.MODE_PRIVATE)

    init {
        loadTasks()
        tasks.values.filter { it.enabled }.forEach { scheduleTask(it) }
    }

    fun list(): List<ScheduledTask> = tasks.values.sortedBy { it.createdAt }

    fun get(id: String): ScheduledTask? = tasks[id]

    fun add(task: ScheduledTask): Boolean {
        if (task.command.isBlank()) return false
        tasks[task.id] = task
        saveTasks()
        if (task.enabled) scheduleTask(task)
        AppLogger.i(tag, "Task added: ${task.name} (${task.id})")
        return true
    }

    fun remove(id: String): Boolean {
        tasks.remove(id) ?: return false
        runningJobs.remove(id)?.cancel()
        saveTasks()
        return true
    }

    fun update(task: ScheduledTask): Boolean {
        if (!tasks.containsKey(task.id)) return false
        runningJobs[task.id]?.cancel()
        tasks[task.id] = task
        if (task.enabled) scheduleTask(task)
        saveTasks()
        return true
    }

    fun clear() {
        tasks.clear()
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        saveTasks()
    }

    private fun scheduleTask(task: ScheduledTask) {
        runningJobs[task.id]?.cancel()
        runningJobs[task.id] = scope.launch {
            while (isActive) {
                val now = java.util.Calendar.getInstance()
                val target = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, task.hour)
                    set(java.util.Calendar.MINUTE, task.minute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                if (target.before(now)) target.add(java.util.Calendar.DAY_OF_MONTH, 1)
                val delayMs = target.timeInMillis - now.timeInMillis
                delay(delayMs)
                AppLogger.i(tag, "Executing scheduled task: ${task.name}")
                ShellExecutor.executeAsRoot(task.command, 120_000L)
                if (!task.repeatDaily) break
            }
        }
    }

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
        } catch (_: Exception) {}
    }

    fun stop() {
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        scope.cancel()
    }
}