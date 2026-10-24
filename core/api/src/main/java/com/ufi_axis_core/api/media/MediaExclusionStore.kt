package com.ufi_axis_core.api.media

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * 「从音乐库移除」的排除名单。
 *
 * ## 它解决的问题
 * 用户想让某一首歌不再出现在媒体库里，但**不想删文件**。扫描目录（`MediaScanScopeSection`）
 * 是目录级的，管不了单首；MediaStore 本身也没有"忽略这一条"的概念。所以维护一份路径名单，
 * 在列表查询时用 SQL 排掉。
 *
 * ## 为什么键是路径
 * MediaStore 的 `_ID` 会在重新扫描后变（同一个文件可能拿到新 id），路径不会。
 * 代价是文件被移动之后这条排除就失效、那首歌会以新路径重新出现 ——
 * 这在语义上也说得过去（"你换了个位置放它"），而且管理页里能看到失效项并清理。
 *
 * ## 为什么读是同步的
 * 消费者是 `MediaRoutes.filterListItems`，那是个非 suspend 的私有函数，跑在
 * `Dispatchers.IO` 里对内存快照做过滤。为它把整条读链改成 suspend 不值得，
 * 所以这里维护一份内存快照：写入时更新，读取直接拿。
 *
 * ## 为什么有上限
 * **历史原因**：排除名单原来会变成 SQL 的 `_data NOT IN (?,?,…)`，每一条占一个绑定变量，
 * 而 SQLite 的上限是 999（`MediaRoutes.listSelection` 里还有扫描目录 / 专辑 / 艺术家 /
 * 目录过滤在共用它）。2026-10-13 过滤改到内存里做之后，这条**技术约束已经不存在**了。
 *
 * 上限本身仍然保留（[MAX_EXCLUSIONS] = 300）：它现在是一条**产品约束** ——
 * 管理页不该无限膨胀、用户也不该把整个库一首首"移除"掉（那是扫描目录该干的事）。
 * 换句话说，别再拿"SQLite 会抛异常"去解释这个数字，那条理由已经过期了。
 */
class MediaExclusionStore(context: Context) {

    companion object {
        /** 名单上限。见类注释：技术约束已随内存过滤消失，这里保留的是产品约束。 */
        const val MAX_EXCLUSIONS = 300

        private const val PREFS_NAME = "media_exclusions"
        private const val KEY_PATHS = "excluded_paths_json"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 写入互斥。加它是因为"读-改-写"：两个客户端同时排除不同的歌，
     * 没有锁就会有一份改动被另一份的全量覆盖吃掉。
     */
    private val mutex = Mutex()

    /** 内存快照。见类注释「为什么读是同步的」。 */
    @Volatile
    private var cache: Set<String> = load()

    private var onChanged: (suspend () -> Unit)? = null

    /** 当前排除的路径集合。给 `MediaRoutes.filterListItems` 同步调用。 */
    fun excluded(): Set<String> = cache

    /** 名单是否已满。UI 据此提示用户，而不是让他点了之后发现没生效。 */
    fun isFull(): Boolean = cache.size >= MAX_EXCLUSIONS

    /**
     * 加入排除名单。
     *
     * @return 实际新增的条数（已经在名单里的不重复计）。超出 [MAX_EXCLUSIONS] 的部分被丢弃，
     *   调用方可以用「请求数 vs 返回数」判断有没有被截断。
     */
    suspend fun add(paths: List<String>): Int = mutex.withLock {
        val before = cache
        val merged = LinkedHashSet(before)
        var added = 0
        for (p in paths) {
            val path = p.trim()
            if (path.isEmpty() || path in merged) continue
            if (merged.size >= MAX_EXCLUSIONS) break
            merged += path
            added++
        }
        if (added > 0) persist(merged)
        added
    }

    /** 移出排除名单（= 撤销）。@return 实际移除的条数。 */
    suspend fun remove(paths: List<String>): Int = mutex.withLock {
        val merged = LinkedHashSet(cache)
        var removed = 0
        for (p in paths) {
            if (merged.remove(p.trim())) removed++
        }
        if (removed > 0) persist(merged)
        removed
    }

    /** 清空名单。 */
    suspend fun clear(): Int = mutex.withLock {
        val size = cache.size
        if (size > 0) persist(emptySet())
        size
    }

    /**
     * 名单变化时的广播钩子（WebSocket `data_changed`）。
     * 与 `PlaylistStore.attachChangeBroadcaster` 同一套做法：store 不认识 wsManager，
     * 由组装层把回调塞进来。
     *
     * 回调是 **suspend** 的：`WsManager.broadcastDataChanged` 本身是挂起函数。
     */
    fun attachChangeBroadcaster(block: suspend () -> Unit) {
        onChanged = block
    }

    /**
     * 落盘 + 刷新快照 + 广播。
     *
     * 写盘在锁内（与 `PlaylistStore` 同一条理由）：挪到锁外之后，两个并发写各自持有
     * 自己那份快照，后落盘的会把先落盘的改动覆盖掉 —— 内存是对的、磁盘是错的。
     */
    private suspend fun persist(value: Set<String>) {
        val arr = JSONArray()
        value.forEach(arr::put)
        prefs.edit().putString(KEY_PATHS, arr.toString()).apply()
        cache = value
        onChanged?.invoke()
    }

    private fun load(): Set<String> {
        val raw = prefs.getString(KEY_PATHS, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                .take(MAX_EXCLUSIONS)
                .toCollection(LinkedHashSet())
        }.getOrDefault(emptySet())
    }
}
