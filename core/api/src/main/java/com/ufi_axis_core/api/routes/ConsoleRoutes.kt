package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.core.database.ConsoleHistoryDao
import com.ufi_axis_core.core.database.ConsoleHistoryRecord
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 终端命令历史路由（见 [ConsoleHistoryRecord]）。
 *
 * - `GET    /api/console/history`         列表（keyset 游标分页）
 * - `DELETE /api/console/history`         清空（可按 channel）
 * - `DELETE /api/console/history/{id}`    删单条
 * - `POST   /api/console/history/import`  两端本地旧历史的一次性导入
 *
 * **本路由不执行命令**，只读写历史。写入由 `ShellRoutes` / `ATRoutes` 在执行完之后
 * 通过 [ConsoleHistoryRecorder] 完成 —— 历史的真源是「core 实际执行过什么」，
 * 不能让客户端自由往里塞行（否则审计价值归零）。唯一的例外是 `/import`，
 * 它是为了把升级前两端各自的本地历史搬进来，只在客户端首次升级时调用一次。
 *
 * 分页形态与 `/api/sms/blocked`、`/api/alerts/list` 一致：游标用 `cursor_ts` +
 * `cursor_id` 两个显式参数，不做 base64 编码（这组记录只有一种排序）。
 */
class ConsoleRoutes(
    private val dao: ConsoleHistoryDao,
    private val recorder: ConsoleHistoryRecorder
) {

    fun register(route: Route) {
        route.route("/console") {
            get("/history") {
                val channel = normalizeChannel(call.request.queryParameters["channel"])
                val limit = (call.request.queryParameters["limit"] ?: "50")
                    .toIntOrNull()?.coerceIn(1, 200) ?: 50
                val cursorTs = call.request.queryParameters["cursor_ts"]?.toLongOrNull()
                val cursorId = call.request.queryParameters["cursor_id"]?.toLongOrNull()
                val records = dao.getPaged(channel, cursorTs, cursorId, limit)
                val last = records.lastOrNull()
                call.respond(toJsonElement(mapOf(
                    "records" to records,
                    "count" to records.size,
                    "total" to dao.countBy(channel),
                    "next_cursor_ts" to last?.created_at,
                    "next_cursor_id" to last?.id,
                    "has_more" to (records.size >= limit)
                )))
            }

            delete("/history") {
                val channel = normalizeChannel(call.request.queryParameters["channel"])
                dao.deleteBy(channel)
                call.respond(toJsonElement(mapOf("success" to true, "channel" to channel)))
            }

            delete("/history/{id}") {
                val id = call.parameters["id"]?.toLongOrNull() ?: 0L
                if (id <= 0) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "invalid id")
                    return@delete
                }
                val deleted = dao.deleteById(id)
                // deleted=0 表示该 id 已不存在，对客户端仍算成功：目标状态「它不在列表里」已达成。
                call.respond(toJsonElement(mapOf("success" to true, "id" to id, "deleted" to deleted)))
            }

            /**
             * 一次性导入本地旧历史。
             *
             * **不做去重**：这张表没有能标识"同一条命令"的自然键（同一条命令可以合法地
             * 重复执行）。幂等性由客户端负责 —— 导入成功后把本地的「已迁移」标记置上、
             * 删掉本地历史文件，就不会再调第二次。重复调用的后果是历史里出现重复行，
             * 不会损坏任何数据。
             */
            post("/history/import") {
                // 必须走 receiveJsonObject() 而不是 call.receive<ImportBody>()：
                // ContentNegotiation 的反序列化要经 serializerForTypeInfo 反射查找序列化器，
                // 这条路径在 Android 运行时不稳（项目为此专门提供了 receiveJsonObject，
                // 见 CallJsonExt.kt 的说明），失败就是 500 —— 而两端升级后的历史导入只有
                // 一次机会，报 500 会让本地历史永远搬不过来。
                val body = try {
                    IMPORT_JSON.decodeFromJsonElement(ImportBody.serializer(), call.receiveJsonObject())
                } catch (e: Exception) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "invalid body")
                    return@post
                }
                val now = System.currentTimeMillis()
                val records = body.records
                    .asSequence()
                    .filter { it.command.isNotBlank() }
                    .filter { it.channel == ConsoleHistoryRecord.CHANNEL_SHELL ||
                        it.channel == ConsoleHistoryRecord.CHANNEL_AT }
                    // 每次导入的条数上限：两端本地上限都是 500/通道，1000 已经覆盖全量。
                    // 不设上限的话一个畸形请求就能把库塞满。
                    .take(MAX_IMPORT_ROWS)
                    .map { it.toRecord(now) }
                    .toList()
                val imported = recorder.importAll(records)
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "imported" to imported,
                    "received" to body.records.size
                )))
            }
        }
    }

    /** 未给或给了不认识的值 → null（不过滤，两个通道混排）。 */
    private fun normalizeChannel(raw: String?): String? = when (raw) {
        ConsoleHistoryRecord.CHANNEL_SHELL, ConsoleHistoryRecord.CHANNEL_AT -> raw
        else -> null
    }

    @Serializable
    private data class ImportBody(val records: List<ImportItem> = emptyList())

    @Serializable
    private data class ImportItem(
        val channel: String = "",
        val command: String = "",
        val as_root: Boolean = false,
        val exit_code: Int? = null,
        val stdout: String = "",
        val stderr: String = "",
        val ok: Boolean = false,
        val duration_ms: Long = 0,
        val source: String = ConsoleHistoryRecord.SOURCE_UNKNOWN,
        /** 客户端本地记录的时间戳，0 或缺省时用服务端当前时间。 */
        val created_at: Long = 0
    ) {
        fun toRecord(now: Long): ConsoleHistoryRecord {
            val (out, outCut) = ConsoleHistoryRecord.clip(stdout)
            val (err, errCut) = ConsoleHistoryRecord.clip(stderr)
            return ConsoleHistoryRecord(
                channel = channel,
                command = command,
                as_root = as_root,
                exit_code = exit_code,
                stdout = out,
                stderr = err,
                ok = ok,
                truncated = outCut || errCut,
                duration_ms = duration_ms,
                source = source,
                // 原样保留客户端时间戳，否则导入后全挤在同一刻、顺序就乱了
                created_at = if (created_at > 0) created_at else now
            )
        }
    }

    private companion object {
        const val MAX_IMPORT_ROWS = 1000

        /** 宽松解析：客户端可能来自更早或更新的版本，多带的字段时间应被忽略而不是 400。 */
        private val IMPORT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
