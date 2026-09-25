package com.ufi_axis.data.download

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 「把设备上的视频下载到手机」的任务队列与历史（2026-09-16）。
 *
 * ## 为什么另起一份，而不是复用文件管理器那条下载
 * `FileManagerModule.downloadFileToPhone` 是**单任务**模型：一次一个、进度存在页面状态里、
 * ViewModel 作用域一销毁就断。媒体库要的是"勾几个视频、退出 App 也继续下、下完落到
 * 固定目录"，两者的生命周期与并发语义都不一样。共用一份只会让那条链路长出一堆开关。
 *
 * ## 持久化在 prefs，不建数据库
 * 队列条目就是几个字段、上限几十条，写 Room 要付一张表 + 迁移的长期成本。
 * 关键是**必须落盘**：进程被杀后 WorkManager 会把任务重新排起来，那时队列得还在
 * （见 [MediaDownloadWorker]），否则"自动继续"只是一句空话。
 *
 * ## 落点
 * `Download/UFI-AXIS/Movies`（[RELATIVE_DIR]），源目录结构原样带上：
 * 设备上是 `.../电影/某系列/a.mp4`，从 `某系列` 那一层开始镜像成子目录 ——
 * 全平铺到一个目录里，几十个 `01.mp4` 会互相覆盖。
 */
object MediaDownloadQueue {

    /** 公共下载目录下的相对路径。用户明确要求放这里，不是 Download 根。 */
    const val RELATIVE_DIR = "Download/UFI-AXIS/Movies"

    /** 队列与历史各自的条数上限：这是"最近在下什么"，不是账本。 */
    private const val QUEUE_LIMIT = 200
    private const val HISTORY_LIMIT = 100

    private const val PREFS_NAME = "ufi_axis_media_download"
    private const val KEY_QUEUE = "queue"
    private const val KEY_HISTORY = "history"

    /**
     * 一个下载任务。
     *
     * @param path 设备上的绝对路径（也是唯一键：同一个文件不重复入队）。
     * @param subDir 相对 [RELATIVE_DIR] 的子目录（空 = 直接放在 Movies 下）。
     * @param status pending / running / done / error / paused
     */
    data class Task(
        val path: String,
        val name: String,
        val size: Long = 0,
        val subDir: String = "",
        val status: String = STATUS_PENDING,
        val received: Long = 0,
        val total: Long = 0,
        val error: String = ""
    ) {
        val progress: Float
            get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** 已完成/失败的一条记录（设置页的「下载历史」）。 */
    data class HistoryItem(
        val name: String,
        val path: String,
        val size: Long,
        val at: Long,
        val ok: Boolean,
        val message: String = ""
    )

    const val STATUS_PENDING = "pending"
    const val STATUS_RUNNING = "running"
    const val STATUS_ERROR = "error"

    private val _queue = MutableStateFlow<List<Task>>(emptyList())
    val queue: StateFlow<List<Task>> = _queue.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private var prefs: SharedPreferences? = null

    /**
     * 队列/历史写入的唯一一把锁。
     *
     * 写方同时来自 Worker 的 IO 线程与 UI 主线程。`_queue.update {}` 只保证**单次**读改写原子，
     * 而每个写入口都是"改内存 + 落盘"两步；不串行化就会出现「用户清空的条目被 Worker
     * 的进度写回复活」这类丢更新。[ensureLoaded] 用的也是这把锁。
     */
    private val lock = Any()

    /**
     * 首次使用时从磁盘装载。
     *
     * 幂等：多处入口（媒体库、设置页、Worker）都会调，只有第一次真读盘。
     */
    fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val p = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            _queue.value = decodeTasks(p.getString(KEY_QUEUE, "").orEmpty())
            _history.value = decodeHistory(p.getString(KEY_HISTORY, "").orEmpty())
        }
    }

    /** 入队（已存在的路径跳过），返回真正新增的条数。 */
    fun enqueue(context: Context, tasks: List<Task>): Int {
        ensureLoaded(context)
        var added = 0
        synchronized(lock) {
            _queue.update { current ->
                val existing = current.mapTo(HashSet()) { it.path }
                val fresh = tasks.filter { it.path.isNotBlank() && it.path !in existing }
                added = fresh.size
                if (fresh.isEmpty()) current else (current + fresh).take(QUEUE_LIMIT)
            }
            if (added > 0) persistQueue()
        }
        return added
    }

    /** 取下一个待处理任务（Worker 用；顺序即入队顺序）。 */
    fun nextPending(): Task? = _queue.value.firstOrNull { it.status != STATUS_ERROR }

    fun update(path: String, transform: (Task) -> Task) {
        synchronized(lock) {
            var hit = false
            _queue.update { current ->
                val index = current.indexOfFirst { it.path == path }
                if (index < 0) {
                    current
                } else {
                    hit = true
                    current.toMutableList().also { it[index] = transform(it[index]) }
                }
            }
            if (hit) persistQueue()
        }
    }

    /** 完成（成功或失败）：从队列摘掉，写一条历史。失败的留在队列里等重试则传 keep=true。 */
    fun finish(context: Context, task: Task, ok: Boolean, message: String = "") {
        ensureLoaded(context)
        synchronized(lock) {
            _queue.update { current -> current.filterNot { it.path == task.path } }
            _history.update { current ->
                (listOf(
                    HistoryItem(
                        name = task.name,
                        path = task.path,
                        size = if (task.total > 0) task.total else task.size,
                        at = System.currentTimeMillis(),
                        ok = ok,
                        message = message
                    )
                ) + current).take(HISTORY_LIMIT)
            }
            persistQueue()
            persistHistory()
        }
    }

    /** 把失败的那条标成 error 并留在队列里（用户可以手动重试或删除）。 */
    fun markError(path: String, message: String) {
        update(path) { it.copy(status = STATUS_ERROR, error = message) }
    }

    fun retry(path: String) {
        update(path) { it.copy(status = STATUS_PENDING, error = "") }
    }

    fun remove(path: String) {
        synchronized(lock) {
            _queue.update { current -> current.filterNot { it.path == path } }
            persistQueue()
        }
    }

    fun clearQueue() {
        synchronized(lock) {
            _queue.value = emptyList()
            persistQueue()
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            _history.value = emptyList()
            persistHistory()
        }
    }

    // ── 序列化：制表符分隔的行，字段里出现制表符的概率远低于 `|` 和逗号 ──

    private fun persistQueue() {
        prefs?.edit()?.putString(KEY_QUEUE, _queue.value.joinToString("\n") {
            listOf(
                it.path, it.name, it.size.toString(), it.subDir,
                it.status, it.received.toString(), it.total.toString()
            ).joinToString("\t") { field -> field.replace('\t', ' ').replace('\n', ' ') }
        })?.apply()
    }

    private fun persistHistory() {
        prefs?.edit()?.putString(KEY_HISTORY, _history.value.joinToString("\n") {
            listOf(
                it.name, it.path, it.size.toString(), it.at.toString(),
                if (it.ok) "1" else "0", it.message
            ).joinToString("\t") { field -> field.replace('\t', ' ').replace('\n', ' ') }
        })?.apply()
    }

    private fun decodeTasks(raw: String): List<Task> = raw.split('\n')
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            Task(
                path = f[0],
                name = f[1],
                size = f[2].toLongOrNull() ?: 0,
                subDir = f[3],
                // 装载时把 running 归位成 pending：那是上次进程被杀留下的中间态
                status = if (f[4] == STATUS_RUNNING) STATUS_PENDING else f[4],
                received = f[5].toLongOrNull() ?: 0,
                total = f[6].toLongOrNull() ?: 0
            )
        }

    private fun decodeHistory(raw: String): List<HistoryItem> = raw.split('\n')
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 5) return@mapNotNull null
            HistoryItem(
                name = f[0],
                path = f[1],
                size = f[2].toLongOrNull() ?: 0,
                at = f[3].toLongOrNull() ?: 0,
                ok = f[4] == "1",
                message = f.getOrElse(5) { "" }
            )
        }
}
