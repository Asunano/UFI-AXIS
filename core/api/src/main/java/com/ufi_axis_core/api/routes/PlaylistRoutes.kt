package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.media.AddItemsOutcome
import com.ufi_axis_core.api.media.AudioItemLookup
import com.ufi_axis_core.api.media.Playlist
import com.ufi_axis_core.api.media.PlaylistFailure
import com.ufi_axis_core.api.media.PlaylistItem
import com.ufi_axis_core.api.media.PlaylistResult
import com.ufi_axis_core.api.media.PlaylistStore
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 音频歌单路由（`/api/playlists`，2026-09-21）。
 *
 * ## 为什么歌单在 core 而不是各端本地
 * 歌单是"用户整理出来的东西"，两端必须看到同一份：存在 app 本地的话，web 端点开是空的，
 * 换手机就没了。而它天然属于媒体域 —— core 已经是媒体库的唯一数据源。
 *
 * ## 曲目以路径为标识，回查在 core 做
 * 存储侧只记 `path` + 一份快照（见 `PlaylistItem`）。`GET /{id}/items` 返回的 item
 * 与 `/api/media/list` 的 `items[]` **形状完全一致**，两端的曲目列表组件可以零改动复用，
 * 也不必各写一份"路径 → 曲目"的拼装。回查能力由 [lookup]（`MediaRoutes`）提供。
 *
 * ## 顺序即播放顺序
 * `items` 的顺序由用户决定，**不套用音乐页的 sort/order**。app 侧 `queueItemsOf` 拿到
 * 歌单曲目后直接装队列，不再排序 —— 手排好的单子被"按修改时间倒序"重排是纯粹的破坏。
 *
 * ## 失败码一律复用通用码
 * 歌单不存在 → `NOT_FOUND`；名称空 → `BLANK_VALUE`；同名 → `ALREADY_EXISTS`；
 * 数量到顶 → `OUT_OF_RANGE`；媒体权限缺失 → 403（与 `/api/media/list` 同口径）。
 * 没有新增 `ErrorCode`，省掉一次 `web/src/api/contract.ts` 的镜像同步。
 */
class PlaylistRoutes(
    private val store: PlaylistStore,
    /**
     * 路径 → 曲目的回查。可空只为兼容尚未装配媒体路由的调用方；
     * 为 null 时所有需要回查的端点回 503（而不是回一份"全都失效"的假数据）。
     */
    private val lookup: AudioItemLookup? = null
) {

    fun register(route: Route) {
        route.route("/playlists") {

            /**
             * 歌单列表（不含曲目明细）。
             *
             * `cover_id` 是每个歌单**第一首可解析曲目**的 MediaStore id，给客户端画封面用；
             * 取不到时为 0 —— 与 `/api/media/groups` 的 `cover_id` 同一约定（`_ID` 从 1 开始，
             * 0 天然是"无效 id"，客户端判 `> 0` 即可，不必再处理 nullable）。
             *
             * 所有歌单的首曲**一次批量回查**：逐个查就是 N 次 MediaStore 查询。
             */
            get {
                val all = store.list()
                val firstPaths = all.mapNotNull { it.items.firstOrNull()?.path }
                val resolved = if (firstPaths.isEmpty()) emptyMap()
                else lookup?.audioItemsByPaths(firstPaths) ?: emptyMap<String, Map<String, Any?>>()
                call.respond(
                    toJsonElement(
                        mapOf(
                            "playlists" to all.map { it.toSummary(resolved) },
                            "count" to all.size
                        )
                    )
                )
            }

            /** 单个歌单的元信息（不含曲目明细，给"重命名"弹窗回填用）。 */
            get("/{id}") {
                val playlist = store.get(call.idParam()) ?: run {
                    call.respondPlaylistNotFound()
                    return@get
                }
                call.respond(toJsonElement(playlist.toSummary(emptyMap())))
            }

            /**
             * 歌单曲目。
             *
             * 查不到的路径不会被丢掉，而是带 `missing = true` 回去（字段取存储时的快照）：
             * 静默隐藏等于"歌自己没了"，用户无从判断是拔了卡、删了文件，还是接口坏了。
             * 客户端据此画"已失效"占位并允许移出，**不要**自动帮用户删 —— 拔一次卡就清空歌单
             * 是不可接受的。
             */
            get("/{id}/items") {
                val id = call.idParam()
                val playlist = store.get(id) ?: run {
                    call.respondPlaylistNotFound()
                    return@get
                }
                val resolver = lookup ?: run {
                    call.respondLookupUnavailable()
                    return@get
                }
                // 未授权时明确回 403，而不是一份全 missing 的列表（那是在骗用户"你的歌全丢了"）
                if (!resolver.audioReadable()) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN,
                        "媒体权限未授权，无法读取歌单曲目",
                        mapOf("type" to "audio")
                    )
                    return@get
                }
                val resolved = resolver.audioItemsByPaths(playlist.items.map { it.path })
                val items = playlist.items.map { it.toWire(resolved[it.path]) }
                call.respond(
                    toJsonElement(
                        mapOf(
                            "id" to playlist.id,
                            "name" to playlist.name,
                            "items" to items,
                            "total" to items.size,
                            "missing_count" to items.count { it["missing"] == true },
                            "created_at" to playlist.createdAt,
                            "updated_at" to playlist.updatedAt
                        )
                    )
                )
            }

            /** 新建歌单。id 由服务端生成，**不接受客户端传 id**（与 `/api/tasks` 同口径）。 */
            post {
                val p = call.receiveJsonObject()
                when (val result = store.create(p.str("name") ?: "")) {
                    is PlaylistResult.Ok ->
                        call.respond(
                            toJsonElement(
                                mapOf(
                                    "success" to true,
                                    "id" to result.value.id,
                                    "name" to result.value.name
                                )
                            )
                        )
                    is PlaylistResult.Failed -> call.respondFailure(result.failure)
                }
            }

            /** 重命名。只接受 `name` 一个字段 —— 曲目走 `/{id}/items` 那组端点。 */
            put("/{id}") {
                val p = call.receiveJsonObject()
                val name = p.str("name") ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BLANK_VALUE, "name 不能为空"
                    )
                    return@put
                }
                when (val result = store.rename(call.idParam(), name)) {
                    is PlaylistResult.Ok ->
                        call.respond(
                            toJsonElement(mapOf("success" to true, "name" to result.value.name))
                        )
                    is PlaylistResult.Failed -> call.respondFailure(result.failure)
                }
            }

            delete("/{id}") {
                val removed = store.delete(call.idParam())
                call.respond(
                    if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound,
                    toJsonElement(mapOf("success" to removed))
                )
            }

            /**
             * 批量加歌。body `{paths:[...], position?}`。
             *
             * ## 为什么要先回查再入库
             * `paths` 直接来自客户端。加之前用 [AudioItemLookup] 确认"这确实是媒体库里的一首歌"，
             * 一举做了三件事：挡掉任意路径（MediaStore 只索引外置存储的媒体，`/data/...` 查不出来）、
             * 顺手取到快照字段、把"这首歌不存在"变成响应里的 `skipped` 而不是一条日后才暴雷的坏数据。
             *
             * 响应里三个计数分别对应三种跳过原因，客户端可以给出准确文案：
             * `added` 真加进去的、`skipped` 已在歌单里或不在媒体库里的、`truncated` 撞到条数上限。
             */
            post("/{id}/items") {
                val id = call.idParam()
                val p = call.receiveJsonObject()
                val paths = p.pathList()
                if (paths.isEmpty()) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "paths 不能为空"
                    )
                    return@post
                }
                if (store.get(id) == null) {
                    call.respondPlaylistNotFound()
                    return@post
                }
                val resolver = lookup ?: run {
                    call.respondLookupUnavailable()
                    return@post
                }
                // 未授权时必须明说：否则回查一条都命中不了，响应会变成
                // "added 0, skipped N"，而那句文案说的是"已在歌单里或不在媒体库" —— 是假原因
                if (!resolver.audioReadable()) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN,
                        "媒体权限未授权，无法加入歌单",
                        mapOf("type" to "audio")
                    )
                    return@post
                }
                val resolved = resolver.audioItemsByPaths(paths)
                // 保持客户端给的顺序：多选加歌时用户看到的就是列表顺序
                val candidates = paths.mapNotNull { path ->
                    resolved[path]?.let { item ->
                        PlaylistItem(
                            path = path,
                            title = item["title"] as? String ?: "",
                            artist = item["artist"] as? String ?: "",
                            album = item["album"] as? String ?: "",
                            durationMs = (item["duration_ms"] as? Number)?.toLong() ?: 0L
                        )
                    }
                }
                val unresolved = paths.size - candidates.size
                when (val result = store.addItems(id, candidates, p.int("position"))) {
                    is PlaylistResult.Ok -> {
                        val outcome: AddItemsOutcome = result.value
                        call.respond(
                            toJsonElement(
                                mapOf(
                                    "success" to true,
                                    "added" to outcome.added,
                                    // 不在媒体库里的也算跳过：客户端只需要"有几条没进去"
                                    "skipped" to (outcome.skipped + unresolved),
                                    "truncated" to outcome.truncated,
                                    "total" to outcome.playlist.items.size
                                )
                            )
                        )
                    }
                    is PlaylistResult.Failed -> call.respondFailure(result.failure)
                }
            }

            /**
             * 按 path 移出。
             *
             * 同时接受 body `{paths:[...]}` 与查询串 `?path=`：DELETE 带 body 在部分代理与
             * HTTP 客户端上会被丢掉，只支持一种写法就会出现"某些环境下删不掉"的偶发故障。
             */
            delete("/{id}/items") {
                val id = call.idParam()
                val fromBody = call.receiveJsonObject().pathList()
                val fromQuery = call.request.queryParameters.getAll("path").orEmpty()
                    .filter { it.isNotBlank() }
                val paths = (fromBody + fromQuery)
                    .distinct()
                    .take(PlaylistStore.MAX_ITEMS_PER_PLAYLIST)
                if (paths.isEmpty()) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "paths 不能为空（body.paths 或 ?path= 至少给一个）"
                    )
                    return@delete
                }
                when (val result = store.removeItems(id, paths)) {
                    is PlaylistResult.Ok ->
                        call.respond(
                            toJsonElement(mapOf("success" to true, "removed" to result.value))
                        )
                    is PlaylistResult.Failed -> call.respondFailure(result.failure)
                }
            }

            /**
             * 整表重排。body `{paths:[...]}` = 新的完整顺序。
             *
             * 语义是「只调顺序，不增不删」（见 `PlaylistStore.reorder` 的说明）：不在歌单里的
             * path 被忽略，没提到的条目保留在尾部。用整表覆盖而不是"上移一位"，是因为拖拽一次
             * 可能跨越很多行，逐步操作要来回好几趟请求、中途失败还会留下半排好的顺序。
             */
            put("/{id}/items") {
                val p = call.receiveJsonObject()
                val paths = p.pathList()
                if (paths.isEmpty()) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "paths 不能为空"
                    )
                    return@put
                }
                when (val result = store.reorder(call.idParam(), paths)) {
                    is PlaylistResult.Ok ->
                        call.respond(
                            toJsonElement(
                                mapOf("success" to true, "total" to result.value.items.size)
                            )
                        )
                    is PlaylistResult.Failed -> call.respondFailure(result.failure)
                }
            }
        }
    }

    // ──────────── 形状转换 ────────────

    /**
     * 列表项形状。`count` 是曲目数（含已失效的）—— 用它显示"共 23 首"时不该因为拔了卡就变少。
     */
    private fun Playlist.toSummary(resolved: Map<String, Map<String, Any?>>): Map<String, Any?> {
        val coverPath = items.firstOrNull()?.path
        val coverId = coverPath?.let { (resolved[it]?.get("id") as? Number)?.toLong() } ?: 0L
        return mapOf(
            "id" to id,
            "name" to name,
            "count" to items.size,
            "cover_id" to coverId,
            "created_at" to createdAt,
            "updated_at" to updatedAt
        )
    }

    /**
     * 歌单条目 → 线上形状。
     *
     * 命中回查时**原样透传**媒体库那份（保证与 `/api/media/list` 逐字段一致，客户端的
     * 曲目组件不需要为歌单再写一套取值），只补一个 `missing = false`。
     * 没命中时用快照拼一份最小可展示的 item，`id = 0` 表示"没有媒体库行"，
     * 客户端据此不画封面、不允许播放。
     */
    private fun PlaylistItem.toWire(fresh: Map<String, Any?>?): Map<String, Any?> {
        if (fresh != null) return fresh + ("missing" to false)
        return mapOf(
            "id" to 0L,
            "name" to File(path).name,
            "path" to path,
            "size" to 0L,
            "date_modified" to 0L,
            "mime" to "",
            "duration_ms" to durationMs,
            "album" to album,
            "artist" to artist,
            "title" to title,
            "missing" to true
        )
    }

    // ──────────── 路由层小工具 ────────────

    private fun ApplicationCall.idParam(): String = parameters["id"] ?: ""

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    /** body 里的 `paths` 数组。非数组 / 缺失一律当空，由调用方决定要不要 400。条数截断到单歌单上限。 */
    private fun JsonObject.pathList(): List<String> = runCatching {
        this["paths"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(PlaylistStore.MAX_ITEMS_PER_PLAYLIST)
            ?: emptyList()
    }.getOrDefault(emptyList())

    private suspend fun ApplicationCall.respondPlaylistNotFound() =
        respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "歌单不存在")

    private suspend fun ApplicationCall.respondLookupUnavailable() =
        respondFail(
            HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
            "媒体库不可用，无法解析歌单曲目"
        )

    /** 存储层的拒因 → HTTP。四种拒因各有明确的下一步，所以不合并成一个 400。 */
    private suspend fun ApplicationCall.respondFailure(failure: PlaylistFailure) =
        when (failure) {
            PlaylistFailure.NOT_FOUND -> respondPlaylistNotFound()
            PlaylistFailure.BLANK_NAME ->
                respondFail(HttpStatusCode.BadRequest, ErrorCode.BLANK_VALUE, "歌单名不能为空")
            PlaylistFailure.DUPLICATE_NAME ->
                respondFail(HttpStatusCode.Conflict, ErrorCode.ALREADY_EXISTS, "已有同名歌单")
            PlaylistFailure.LIMIT_REACHED ->
                respondFail(
                    HttpStatusCode.BadRequest, ErrorCode.OUT_OF_RANGE,
                    "歌单数量已达上限 ${PlaylistStore.MAX_PLAYLISTS}",
                    mapOf("max" to PlaylistStore.MAX_PLAYLISTS)
                )
        }
}
