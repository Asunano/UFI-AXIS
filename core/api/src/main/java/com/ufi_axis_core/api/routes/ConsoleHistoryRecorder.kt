package com.ufi_axis_core.api.routes

import com.ufi_axis_core.core.database.ConsoleHistoryDao
import com.ufi_axis_core.core.database.ConsoleHistoryRecord
import com.ufi_axis_core.util.AppLogger

/**
 * 终端命令历史的唯一写入口（见 [ConsoleHistoryRecord]）。
 *
 * 两端（app / web）此前各存一份本地历史，互相看不见。现在 core 是唯一真源：
 * `POST /api/shell/exec` 与 `POST /api/at/command` 执行完就在这里落一行，
 * 客户端只负责读 `GET /api/console/history`。
 *
 * ## 三条纪律
 *
 * 1. **同步写、不 fire-and-forget**：客户端拿到命令响应后会立刻拉最新一页，
 *    异步写会出现「响应回来了但历史里还没有这条」的竞态。insert 很小，
 *    Room 的 suspend 已经在 IO 上，不值得为它引入一个 scope。
 * 2. **绝不让记账失败影响命令本身**：整段包 try/catch，DB 出问题只落一条 WARN。
 *    终端的价值是执行命令，不是记账。
 * 3. **推送只发信号、不发内容**：走既有的 `data_changed` 通道（两端都已接好，
 *    见 `WebSocketRepository.dataChanged` / `wsStore.on('data_changed')`），
 *    payload 是常量 `console:shell` / `console:at`。这样即便撞上 `broadcast()`
 *    的 100ms 同类型缓存，客户端拿到的也是正确的信号 —— 如果推的是记录本身，
 *    100ms 内的第二条命令会拿到第一条的 payload。
 */
class ConsoleHistoryRecorder(
    private val dao: ConsoleHistoryDao,
    /**
     * 新记录落库后的信号回调，入参是 `data_changed` 的 `changed` 值
     * （`console:shell` / `console:at`）。
     *
     * 用回调而不是直接持 `WebSocketManager`：`core:api` 刻意不依赖 `core:websocket`
     * （见本模块 build.gradle.kts 顶部的分层说明），装配层（`ComponentFactory`）注入
     * `{ wsManager.broadcastDataChanged(it) }` 即可。
     */
    private val onChanged: (suspend (String) -> Unit)? = null
) {

    /**
     * 落一行历史并推送信号。
     *
     * @param exitCode AT 通道与「被安全策略拦下」的记录传 null —— 别用 0 顶替，
     *                 0 是「执行成功」的真值。
     * @return 落库后的记录（带自增 id），失败返回 null。
     */
    suspend fun record(
        channel: String,
        command: String,
        ok: Boolean,
        asRoot: Boolean = false,
        exitCode: Int? = null,
        stdout: String = "",
        stderr: String = "",
        durationMs: Long = 0,
        source: String = ConsoleHistoryRecord.SOURCE_UNKNOWN
    ): ConsoleHistoryRecord? {
        return try {
            val (clippedOut, outCut) = ConsoleHistoryRecord.clip(stdout)
            val (clippedErr, errCut) = ConsoleHistoryRecord.clip(stderr)
            val record = ConsoleHistoryRecord(
                channel = channel,
                command = command,
                as_root = asRoot,
                exit_code = exitCode,
                stdout = clippedOut,
                stderr = clippedErr,
                ok = ok,
                truncated = outCut || errCut,
                duration_ms = durationMs,
                source = normalizeSource(source)
            )
            val id = dao.insert(record)
            dao.trimChannelTo(channel, ConsoleHistoryRecord.MAX_ROWS_PER_CHANNEL)
            onChanged?.invoke("console:$channel")
            record.copy(id = id)
        } catch (e: Exception) {
            AppLogger.w(TAG, "终端历史写入失败（不影响命令执行）: ${e.message}")
            null
        }
    }

    /**
     * 两端本地旧历史的一次性导入。
     *
     * 客户端自报的 `created_at` 原样保留（否则导入后全挤在同一时刻，顺序就乱了），
     * 但仍按通道裁剪到上限。导入不推送信号：这是一次批量搬迁，不是新事件。
     *
     * @return 实际写入条数
     */
    suspend fun importAll(records: List<ConsoleHistoryRecord>): Int {
        if (records.isEmpty()) return 0
        return try {
            dao.insertAll(records)
            records.map { it.channel }.distinct().forEach {
                dao.trimChannelTo(it, ConsoleHistoryRecord.MAX_ROWS_PER_CHANNEL)
            }
            records.size
        } catch (e: Exception) {
            AppLogger.w(TAG, "终端历史导入失败: ${e.message}")
            0
        }
    }

    private fun normalizeSource(source: String): String = when (source) {
        ConsoleHistoryRecord.SOURCE_APP, ConsoleHistoryRecord.SOURCE_WEB -> source
        // 客户端自报的字段，不认识就归一到 unknown：它只用于展示，
        // 原样落库会让「来源」这一列变成任意字符串的垃圾桶。
        else -> ConsoleHistoryRecord.SOURCE_UNKNOWN
    }

    private companion object {
        const val TAG = "ConsoleHistory"
    }
}
