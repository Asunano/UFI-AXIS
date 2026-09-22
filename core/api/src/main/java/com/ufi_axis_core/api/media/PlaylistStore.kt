package com.ufi_axis_core.api.media

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 歌单里的一条曲目。
 *
 * ## 为什么以 `path` 为标识，而不是 MediaStore 的 `_ID`
 * `_ID` 是**媒体库的行号**，不是文件的身份：重扫、换卡、`.nomedia` 反复开关之后同一个文件
 * 会拿到新的 id，旧 id 则指向别的文件或干脆不存在。歌单要活过这些事件，只能用路径 ——
 * 而路径也正是整条播放链路（`/api/files/stream-ticket` → `/media/stream`）的既有标识，
 * 不需要在两种标识之间来回翻译。
 *
 * ## 快照字段为什么要存
 * [title] / [artist] / [album] / [durationMs] 是加入歌单那一刻从媒体库取到的值，**只用于
 * 文件已经不在媒体库时的占位展示**（回给客户端时那条带 `missing = true`）。正常情况一律以
 * 回查结果为准 —— 快照会过期（用户改了标签、补了封面），拿它当真源会让歌单和音乐页显示得不一样。
 */
@Serializable
data class PlaylistItem(
    val path: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * 一份歌单。
 *
 * [items] 的顺序**就是播放顺序**，由用户决定（追加 / 插入 / 整表重排），
 * 不套用音乐页的 sort/order —— 那是"浏览偏好"，会把手排好的顺序覆盖掉。
 */
@Serializable
data class Playlist(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String = "",
    val items: List<PlaylistItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 写操作被拒的原因。路由层据此选 HTTP 状态码与 `ErrorCode`，本层不认识 HTTP。 */
enum class PlaylistFailure {
    /** 歌单名去空白后为空。 */
    BLANK_NAME,

    /** 已有同名歌单（忽略大小写与首尾空白）。 */
    DUPLICATE_NAME,

    /** 歌单数量已达 [PlaylistStore.MAX_PLAYLISTS]。 */
    LIMIT_REACHED,

    /** 目标歌单不存在。 */
    NOT_FOUND
}

/** 写操作结果。比 `Pair<T?, Failure?>` 明确，也不必为四种拒因各造一个异常类。 */
sealed interface PlaylistResult<out T> {
    data class Ok<T>(val value: T) : PlaylistResult<T>
    data class Failed(val failure: PlaylistFailure) : PlaylistResult<Nothing>
}

/** [PlaylistStore.addItems] 的结果：一次批量加歌里有多少真的进去了。 */
data class AddItemsOutcome(
    /** 实际加入的条数。 */
    val added: Int,
    /** 因为已在本歌单里而跳过的条数（按 path 去重）。 */
    val skipped: Int,
    /** 是否因为撞到 [PlaylistStore.MAX_ITEMS_PER_PLAYLIST] 而截断。 */
    val truncated: Boolean,
    /** 写入后的歌单。 */
    val playlist: Playlist
)

/**
 * 音频歌单的持久化。
 *
 * ## 存储形态：SharedPreferences 单键存整份 JSON
 * 与 `TaskScheduler` / `ConditionEngine` 同一范式（各自一个 prefs 文件 + 一个 `*_json` 键）。
 * 不建 Room 表：媒体域刻意不落库（见 `MediaRoutes` 类注释），为一个"用户手排的几十条路径"
 * 开一张表、配一套迁移，代价远大于收益。
 *
 * ## 为什么补了 Mutex（`TaskScheduler` 没有）
 * 那两个调度器的写入只来自"用户点一下按钮"，并发几乎不存在，所以只靠 `ConcurrentHashMap`
 * 的弱一致快照就够。歌单不一样：「把这张专辑 300 首全加进来」是一次调用里连续改同一份列表，
 * 而两端（app + web）可能同时在加 —— 读-改-写之间没有锁，后写会整份覆盖前写，用户看到的
 * 就是"加进去的歌莫名少了一半"。所有读写都在 [mutex] 内完成。
 *
 * ## 序列化
 * 显式传 `ListSerializer(Playlist.serializer())`，不用 `encodeToString<T>` 的反射重载 ——
 * Android 运行时的序列化器反射查找会失败（同 `CallJsonExt` 里记的那个坑）。
 * 解析失败按"空集合"处理并记 WARN，不崩：一份坏了的 prefs 不该让整个 HTTP 服务起不来。
 */
class PlaylistStore(context: Context) {

    companion object {
        private const val TAG = "PlaylistStore"

        private const val PREFS_NAME = "audio_playlists"
        private const val KEY_JSON = "playlists_json"

        /** 歌单数量上限。这是"几个自己整理的单子"，不是用来当第二套媒体库。 */
        const val MAX_PLAYLISTS = 100

        /**
         * 单个歌单的曲目上限。
         *
         * 与 app 侧 `MediaModule.ALL_ITEMS_LIMIT` 同一个数量级：队列装载要一次拉齐，
         * 上万条的"歌单"在客户端就是一次几 MB 的 JSON + 一个滚不动的列表。
         */
        const val MAX_ITEMS_PER_PLAYLIST = 2000

        /** 歌单名长度上限（字符数）。超长部分截断而不是报错 —— 名字长短不是失败理由。 */
        const val MAX_NAME_LENGTH = 64
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val mutex = Mutex()

    /** 内存态是唯一真源，落盘只是持久化。key = 歌单 id。 */
    private val playlists = LinkedHashMap<String, Playlist>()

    /**
     * 「歌单集合变了」的推送回调，由 [attachChangeBroadcaster] 注入。
     *
     * 与 `TaskScheduler.attachChangeBroadcaster` 同样的处境与理由：`core:api` 刻意不依赖
     * `core:websocket`，装配层注入 `{ wsManager.broadcastDataChanged(...) }` 即可。
     * null = 不推（尚未装配），增删改照常生效。
     */
    @Volatile
    private var onChanged: (suspend () -> Unit)? = null

    fun attachChangeBroadcaster(broadcaster: suspend () -> Unit) {
        onChanged = broadcaster
    }

    init {
        load()
    }

    // ──────────── 读 ────────────

    /** 按创建时间排序（与 `TaskScheduler.list()` 同口径：新建的排在后面，位置稳定）。 */
    suspend fun list(): List<Playlist> = mutex.withLock {
        playlists.values.sortedBy { it.createdAt }
    }

    suspend fun get(id: String): Playlist? = mutex.withLock { playlists[id] }

    // ──────────── 写 ────────────

    suspend fun create(rawName: String): PlaylistResult<Playlist> = mutate {
        val name = normalizeName(rawName)
        when {
            name.isEmpty() -> PlaylistResult.Failed(PlaylistFailure.BLANK_NAME) to false
            // 重名排在容量之前：重名是输入本身的问题，换个名字就行；
            // 排后面的话用户会看到"数量到上限"，去删歌单也解决不了（名字还是重的）
            nameTaken(name, exceptId = null) ->
                PlaylistResult.Failed(PlaylistFailure.DUPLICATE_NAME) to false
            playlists.size >= MAX_PLAYLISTS ->
                PlaylistResult.Failed(PlaylistFailure.LIMIT_REACHED) to false
            else -> {
                val created = Playlist(name = name)
                playlists[created.id] = created
                AppLogger.i(TAG, "歌单已创建: $name (${created.id})")
                PlaylistResult.Ok(created) to true
            }
        }
    }

    suspend fun rename(id: String, rawName: String): PlaylistResult<Playlist> = mutate {
        val existing = playlists[id]
        val name = normalizeName(rawName)
        when {
            existing == null -> PlaylistResult.Failed(PlaylistFailure.NOT_FOUND) to false
            name.isEmpty() -> PlaylistResult.Failed(PlaylistFailure.BLANK_NAME) to false
            nameTaken(name, exceptId = id) ->
                PlaylistResult.Failed(PlaylistFailure.DUPLICATE_NAME) to false
            // 名字没变时不写盘、不推送：编辑弹窗按原样提交是常态
            name == existing.name -> PlaylistResult.Ok(existing) to false
            else -> {
                val updated = existing.copy(name = name, updatedAt = System.currentTimeMillis())
                playlists[id] = updated
                PlaylistResult.Ok(updated) to true
            }
        }
    }

    suspend fun delete(id: String): Boolean {
        val (removed, changed) = mutex.withLock {
            val gone = playlists.remove(id) != null
            if (gone) {
                save()
                AppLogger.i(TAG, "歌单已删除: $id")
            }
            gone to gone
        }
        if (changed) notifyChanged()
        return removed
    }

    /**
     * 批量加歌。
     *
     * @param position 插入位置（0 = 队首）。null 或越界时追加到尾部。
     *   位置按**插入前**的列表算：一次调用里的多条保持给定顺序整段插入。
     * @param candidates 已由路由层校验过"确实在媒体库里"的条目（快照也是那时取的）。
     *   本层只管顺序与去重 —— 把 MediaStore 查询放进来会让存储层依赖 ContentResolver。
     */
    suspend fun addItems(
        id: String,
        candidates: List<PlaylistItem>,
        position: Int? = null
    ): PlaylistResult<AddItemsOutcome> = mutate {
        val existing = playlists[id]
            ?: return@mutate PlaylistResult.Failed(PlaylistFailure.NOT_FOUND) to false

        val present = existing.items.mapTo(HashSet()) { it.path }
        // 同一次请求里也可能重复（客户端多选时勾了两遍），所以边过滤边登记
        val fresh = candidates.filter { present.add(it.path) }
        val skipped = candidates.size - fresh.size

        val room = (MAX_ITEMS_PER_PLAYLIST - existing.items.size).coerceAtLeast(0)
        val accepted = fresh.take(room)
        val truncated = accepted.size < fresh.size

        if (accepted.isEmpty()) {
            // 一条都没进：不写盘也不推送，否则两端会为一次无效操作各重拉一遍
            return@mutate PlaylistResult.Ok(
                AddItemsOutcome(0, skipped, truncated, existing)
            ) to false
        }

        val at = position?.coerceIn(0, existing.items.size) ?: existing.items.size
        val merged = existing.items.toMutableList().apply { addAll(at, accepted) }
        val updated = existing.copy(items = merged, updatedAt = System.currentTimeMillis())
        playlists[id] = updated
        PlaylistResult.Ok(
            AddItemsOutcome(accepted.size, skipped, truncated, updated)
        ) to true
    }

    /** 按 path 移出。返回实际移除条数；一条都没命中时不写盘。 */
    suspend fun removeItems(id: String, paths: List<String>): PlaylistResult<Int> = mutate {
        val existing = playlists[id]
            ?: return@mutate PlaylistResult.Failed(PlaylistFailure.NOT_FOUND) to false
        val doomed = paths.toHashSet()
        val kept = existing.items.filterNot { it.path in doomed }
        val removed = existing.items.size - kept.size
        if (removed == 0) {
            PlaylistResult.Ok(0) to false
        } else {
            playlists[id] = existing.copy(items = kept, updatedAt = System.currentTimeMillis())
            PlaylistResult.Ok(removed) to true
        }
    }

    /**
     * 整表重排。
     *
     * 语义刻意定成「**只调顺序，不增不删**」：
     * - 传进来但不在歌单里的 path 一律忽略 —— 重排接口能顺手加歌的话，客户端一次误传
     *   （比如把整库列表当成歌单提交）就会把几千首灌进来；
     * - 歌单里有、但本次没提及的条目**保留并追加到尾部**，不是删掉 —— 拖拽 UI 常常只提交
     *   可见的那一屏，按"没提到就删"处理等于滚动一下就丢歌。
     *
     * 真要删就调 [removeItems]，那是一个有明确名字的操作。
     */
    suspend fun reorder(id: String, paths: List<String>): PlaylistResult<Playlist> = mutate {
        val existing = playlists[id]
            ?: return@mutate PlaylistResult.Failed(PlaylistFailure.NOT_FOUND) to false
        val byPath = existing.items.associateBy { it.path }
        val seen = LinkedHashSet<String>()
        val ordered = mutableListOf<PlaylistItem>()
        for (p in paths) {
            val item = byPath[p] ?: continue
            if (seen.add(p)) ordered += item
        }
        existing.items.forEach { if (it.path !in seen) ordered += it }

        if (ordered.map { it.path } == existing.items.map { it.path }) {
            PlaylistResult.Ok(existing) to false
        } else {
            val updated = existing.copy(items = ordered, updatedAt = System.currentTimeMillis())
            playlists[id] = updated
            PlaylistResult.Ok(updated) to true
        }
    }

    // ──────────── 内部 ────────────

    /**
     * 写操作的统一外壳：锁内改内存 + 落盘，锁外推送。
     *
     * ## 落盘为什么**留在锁内**
     * 把序列化挪到锁外（锁内只取快照）看起来更"轻"，但那样两个并发写会各自持有一份快照、
     * 以不确定的顺序落盘 —— 后写的可能是**较旧**的那份，内存对、磁盘错，重启后用户的
     * 修改凭空消失。歌单的写入频率是"用户点一下按钮"，锁内多花一次 O(N) 序列化远比
     * 这种只在重启后才暴露的丢数据划算。
     *
     * ## 推送为什么必须在锁外
     * `broadcastDataChanged` 会遍历 WS 连接逐个 `send`，放进锁里意味着一个卡住的客户端
     * 能让所有歌单读写一起阻塞。
     *
     * @param block 返回「响应结果 → 是否真的改了数据」。第二个值为 false 时既不落盘也不推送 ——
     *   校验失败与"改了个一模一样的名字"都属于这一类，不该让两端为此各重拉一遍。
     */
    private suspend fun <T> mutate(
        block: () -> Pair<PlaylistResult<T>, Boolean>
    ): PlaylistResult<T> {
        val (result, changed) = mutex.withLock {
            val outcome = block()
            if (outcome.second) save()
            outcome
        }
        if (changed) notifyChanged()
        return result
    }

    private suspend fun notifyChanged() {
        val cb = onChanged ?: return
        runCatching { cb() }.onFailure {
            AppLogger.w(TAG, "歌单变更推送失败: ${it.message}")
        }
    }

    /** 必须在 [mutex] 内调用（见 [mutate] 关于落盘顺序的说明）。 */
    private fun save() {
        val json = runCatching {
            Json.encodeToString(
                ListSerializer(Playlist.serializer()),
                playlists.values.toList()
            )
        }.getOrElse {
            AppLogger.w(TAG, "歌单序列化失败，本次不落盘: ${it.message}")
            return
        }
        prefs.edit().putString(KEY_JSON, json).apply()
    }

    private fun normalizeName(raw: String): String = raw.trim().take(MAX_NAME_LENGTH)

    /** 同名判定忽略大小写：「我的歌单」和「我的歌单 」在用户眼里是同一个。 */
    private fun nameTaken(name: String, exceptId: String?): Boolean =
        playlists.values.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }

    private fun load() {
        val json = prefs.getString(KEY_JSON, null) ?: return
        val loaded = runCatching {
            Json.decodeFromString(ListSerializer(Playlist.serializer()), json)
        }.getOrElse {
            AppLogger.w(TAG, "歌单配置解析失败，按空处理: ${it.message}")
            return
        }
        loaded.forEach { playlists[it.id] = it }
        AppLogger.i(TAG, "已载入 ${playlists.size} 个歌单")
    }
}
