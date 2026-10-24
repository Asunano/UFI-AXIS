package com.ufi_axis_core.api.media

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * 媒体库索引缓存（2026-10-13）。
 *
 * ## 它解决的问题
 * 媒体中心的三个读端点（`/list`、`/groups`、`/browse`）原来**每次请求都现场查 MediaStore**：
 * `/groups` 要整表扫一遍几千行，`/browse` 一层最多再叠 200 次子目录 count 查询。用户滑动列表
 * 就是几十次跨进程查询打到 `android.process.media`。而这些数据变化极慢（人往卡里拷文件），
 * 现场查是纯浪费 —— 于是改成「后台取一次 → 存内存 → 用户读内存 → 定时刷新」。
 *
 * ## 与「缩略图解码」的区别（**关键**，别把两者混为一谈）
 * 内存暴涨到 471MB 的元凶是 `loadThumbnail` **解码**，不是 `query` 取元数据 ——
 * 解码会把产物留在 MediaProvider 进程里且不按访问淘汰。本类**只做 query，绝不做任何解码**，
 * 所以它降低的是跨进程调用次数与延迟，不是（也替代不了）缩略图那条路上的闸门与落盘缓存。
 * 两条路各自独立成立，缺一不可。
 *
 * ## 缓存的是「全量原始库」而不是「过滤后的结果」
 * 快照按 kind 存**未经扫描目录 / 排除名单过滤**的整库条目，过滤（扫描目录、排除名单、
 * album / artist / dir）全部在**读时**于内存里做。三个理由：
 *  1. 用户在设置里改扫描目录要**立刻**生效，不能等下一轮刷新（改了没反应像坏了）；
 *  2. 「从音乐库移除」的排除名单同理，且它本来就有 `data_changed` 广播；
 *  3. `PlaylistRoutes` 的按路径回查（[AudioItemLookup]）**刻意不受扫描目录约束**
 *     （见 `MediaRoutes.audioItemsByPaths` 的 KDoc），若快照存的是过滤后的结果，
 *     歌单里那些"在库外"的歌会全部被判成已失效。
 *
 * ## 竞态：三道防线（用户明确要求）
 * 并发来源有三处 —— 定时刷新、用户点「重新扫描」触发的追赶刷新、用户读请求。
 *  1. **单飞 [refreshMutex]**：三者共用一把锁，同一时刻只有一次跨进程查询在飞。
 *     多个并发请求不会各拉一遍（否则又变成几十次查询打过去）。
 *  2. **[generation] 代次校验**：写回快照前比对代次，慢的旧轮不会覆盖新轮的结果。
 *  3. **读永不阻塞在刷新上**：读的是 `@Volatile` 快照（不可变，读侧无锁）。有旧快照就立刻给
 *     旧快照并顺带触发后台补刷，**只有连一份旧快照都没有时**（进程刚起来 / 首次访问）
 *     才同步拉一次 —— 否则首屏永远是空的。
 *
 * ## 生命周期
 * - 定时刷新由 [startPeriodicRefresh] 用**外部传进来的 scope**（`BackendService.serviceScope`），
 *   服务停掉协程就死 —— 与 `AppManager` 同一口径。
 * - 「重新扫描」的追赶刷新用本类自持的 [ownScope]：它必须**活过**那次 HTTP 请求
 *   （沉降窗口 +15s/+45s 远超请求寿命），但不能挂到请求协程上。由 [stop] 显式收。
 */
class MediaIndexStore(
    /**
     * 快照 TTL（毫秒）。默认 [SNAPSHOT_TTL_MS]。
     *
     * 做成构造参数**只**为了让单测能传一个小值去验证过期路径 —— 生产装配
     *（`ComponentFactory`）永远用默认值。与 `AppManager.listLoader` 同一思路：
     * 把不可控的时间换成可注入的值，而不是让测试去 `Thread.sleep(10 分钟)`。
     */
    private val ttlMs: Long = SNAPSHOT_TTL_MS,
    /** 定时刷新周期（毫秒）。默认 [REFRESH_INTERVAL_MS]；同样是测试缝。 */
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS
) {

    companion object {
        private const val TAG = "MediaIndexStore"

        /**
         * 快照 TTL（10 分钟）与后台刷新周期（5 分钟）。
         *
         * 与 `AppManager.APP_LIST_CACHE_TTL_MS` 同一套取值逻辑：**周期 < TTL**，
         * 定时刷新每 5 分钟把 TTL 顶回去，于是用户进页面时几乎总是命中
         * （最坏数据年龄 = TTL − 周期 = 5 分钟）。若周期 ≥ TTL，就会出现"进页面恰好卡在
         * 过期点"而现场拉一次 —— 那正是这次要消掉的体感。
         *
         * 比应用列表（5 分钟 / 2 分钟）宽松，因为媒体库整表扫一遍比 4 条 shell 重：
         * 库大的设备上全量重建是秒级开销，不值得每 2 分钟来一次。
         */
        const val SNAPSHOT_TTL_MS = 10 * 60 * 1000L
        const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * 「重新扫描」提交后的追赶重建时刻（毫秒，相对提交时刻）。
         *
         * `MediaScannerConnection.scanFile` 是**异步 fire-and-forget**：它返回时文件还没入库，
         * 立刻重建索引拿到的必然是旧库（用户会觉得"点了没反应"）。所以按三次追赶覆盖入库延迟：
         *  · +3s   —— 小批量 / 已在页缓存里的文件，通常这时就好了；
         *  · +15s  —— 常规批量；
         *  · +45s  —— 大库 / 慢卡的最后兜底。
         *
         * 三次都只是**再查一次**（纯 query，无解码），代价可忽略；换来的是用户不必手动再点一次。
         */
        val CHASE_DELAYS_MS = listOf(3_000L, 15_000L, 45_000L)
    }

    /**
     * 一份不可变快照：`kindKey → 整库条目`（未按扫描目录 / 排除名单过滤）+ 写入时刻。
     *
     * 不可变是刻意的：读侧（HTTP 请求）无锁直接拿引用，不用 synchronized，
     * 也不会读到"改了一半"的列表。刷新是整体替换，不是就地修改。
     */
    class Snapshot(
        val items: Map<String, List<Map<String, Any?>>>,
        val timestampMs: Long
    ) {
        /** 距上次刷新是否已超过 TTL（按**写入时刻**算，不看访问时刻）。 */
        fun isExpired(nowMs: Long, ttlMs: Long = SNAPSHOT_TTL_MS): Boolean =
            nowMs - timestampMs >= ttlMs
        fun of(kindKey: String): List<Map<String, Any?>> = items[kindKey].orEmpty()

        /**
         * 快照里是否**包含**该 kind 的数据。
         *
         * 不含 ≠ 空列表：不含表示"建这份快照时这一类不可用"（没授权 / 查询失败且没有上一份），
         * 读路径据此改走实时查询，而不是把"没授权"当成"设备里没有音乐"回一个空列表
         * —— 后者会被客户端理解成"这台设备没有媒体"，是最误导人的一种失败。
         */
        fun covers(kindKey: String): Boolean = items.containsKey(kindKey)

        /** 全部 kind 的条目总数（日志与诊断用）。 */
        fun totalItems(): Int = items.values.sumOf { it.size }
    }

    /**
     * 拉取入口的**可替换缝**（与 `AppManager.listLoader` 同一做法）。
     *
     * 本类刻意不认识 MediaStore：`Kind` / projection / 游标解析全在 `MediaRoutes` 里，
     * 由它通过 [attachLoader] 注册进来。好处是不必把 `Kind` 提升成公共类型、
     * 也不必让 `ComponentFactory` 先建 `MediaRoutes` 再建本类（那会形成构造环）。
     *
     * @param keys 要拉取的 kind 键（`video` / `audio` / `image`）。
     * @param loader 拉一个 kind 的整库条目；**返回 null 表示这一类失败**（权限被拒 / 查询异常），
     *   返回空列表表示"这一类真的一个文件都没有" —— 两者必须区分开。
     */
    @Volatile
    private var loader: (suspend (String) -> List<Map<String, Any?>>?)? = null

    @Volatile
    private var keys: List<String> = emptyList()

    /** 当前快照。`@Volatile` + 整体替换：读侧无锁，刷新只需换引用。 */
    @Volatile
    private var snapshot: Snapshot? = null

    /**
     * 刷新互斥 —— **刷新与首次同步拉取共用**。
     *
     * 与 `AppManager.appListMutex` 同一理由：并发的第二个请求可能在第一个把快照放进缓存
     * **之前**读到 null，只靠锁外那次检查会让两个请求各拉一遍整库。
     * 「后台刷新」的价值在于把用户这次等待**前置**到定时轮次里，而不是让它变快。
     */
    private val refreshMutex = Mutex()

    /**
     * 代次计数。每轮刷新开始自增；[loadAll] 写回前比对，不等则**丢弃本轮结果**。
     *
     * 两处会自增：刷新开始（[loadAll] 的入参）、以及 [invalidate]。
     *
     * 为什么单靠 [refreshMutex] 不够：锁保证"两轮刷新不并发"，但保证不了
     * "在飞的那轮结果仍然有效"。真实场景是删除文件 —— `DELETE /api/files/delete`
     * 删完调 [invalidate]，而此时可能正好有一轮整库扫描在飞，它读到的是**删除前**的库。
     * 没有代次校验的话，那轮回来会把删掉的文件重新写进快照，用户刷新列表又看到它，
     * 而且这个错误状态会一直持续到下一次刷新（最长 5 分钟）——
     * 表现就是"删了但还在，过一会儿又自己没了"，极难复现也极难归因。
     */
    private val generation = AtomicLong(0)

    /** 追赶调度的代次：新的「重新扫描」会取消上一组追赶，避免连点 N 次叠出 3N 次刷新。 */
    private val chaseGeneration = AtomicLong(0)

    /** 本类自持的作用域：只承载「追赶刷新」（必须活过一次 HTTP 请求）。[stop] 时取消。 */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var chaseJob: Job? = null

    /** 定时刷新协程句柄。`null` = 未启动或已停。 */
    @Volatile
    private var periodicJob: Job? = null

    /** [startPeriodicRefresh] 记下的作用域，`setPeriodicRefreshEnabled(true)` 要靠它重新拉起。 */
    @Volatile
    private var periodicScope: CoroutineScope? = null

    /**
     * 注册拉取入口。由 `MediaRoutes` 在 `init` 里调用（它构造晚于本类，见 [loader] 的说明）。
     *
     * @param keys 要拉取的 kind 键，顺序即拉取顺序。
     */
    fun attachLoader(keys: List<String>, loader: suspend (String) -> List<Map<String, Any?>>?) {
        this.keys = keys
        this.loader = loader
    }

    // ─────────────────────────── 读路径 ───────────────────────────

    /** 取**未过期**快照；过期与"从没有过"都算未命中。 */
    fun freshSnapshot(): Snapshot? =
        snapshot?.takeIf { !it.isExpired(System.currentTimeMillis(), ttlMs) }

    /** 取任意快照（含过期）—— 供"读永不阻塞"那条路用。 */
    fun anySnapshot(): Snapshot? = snapshot

    /**
     * 读入口：**优先内存，绝不无谓地打 MediaProvider**。
     *
     * 三级：
     *  1. 有新鲜快照 → 直接给（绝大多数请求走这条，零跨进程调用）；
     *  2. 只有过期快照 → **立刻给旧快照**，同时丢一个后台补刷（不阻塞这次响应）；
     *  3. 连旧快照都没有（进程刚起来 / 首次访问）→ 同步拉一次，并发请求由 [refreshMutex] 合并。
     *
     * 第 3 条是刻意保留的：没有它首屏永远是空的。它只在**冷启动后的第一次**发生，
     * 之后都由定时刷新与第 2 条顶着。服务启动时也会主动预热一轮（见 `BackendService`），
     * 所以实际几乎不会走到这里。
     */
    suspend fun snapshotOrLoad(): Snapshot? {
        freshSnapshot()?.let { return it }
        anySnapshot()?.let { stale ->
            // 有旧数据就先用旧的：让用户这次请求立刻返回，刷新在后台做。
            requestBackgroundRefresh()
            return stale
        }
        // 冷启动：必须同步拉一次，否则首屏空着。二次检查放在锁内（见 refreshMutex 的说明）。
        return refreshMutex.withLock {
            freshSnapshot() ?: anySnapshot() ?: loadAll(generation.incrementAndGet())
        }
    }

    // ─────────────────────────── 刷新路径 ───────────────────────────

    /**
     * 拉一轮并写入快照。由定时循环与追赶调度调用。
     *
     * @param force true = 无视现有快照的 TTL 强制重拉（定时刷新与追赶刷新都用 true，
     *   因为它们的调用时机本身就是"该刷新了"）；false = 已有新鲜快照就直接复用。
     * @return 本轮结束后的快照（可能是旧的、也可能是 null）。
     */
    suspend fun refresh(force: Boolean = false): Snapshot? = refreshMutex.withLock {
        if (!force) freshSnapshot()?.let { return@withLock it }
        loadAll(generation.incrementAndGet()) ?: snapshot
    }

    /** 后台补刷（不阻塞调用方）。已有刷新在跑时由 [refreshMutex] 自然合并。 */
    private fun requestBackgroundRefresh() {
        ownScope.launch {
            runCatching { refresh(force = true) }
        }
    }

    /**
     * 真正的整库拉取。**只查元数据，不做任何解码。**
     *
     * 失败处理刻意宽容（这点与 `AppManager` 的"空结果一律不写"不同，因为它只有一路数据源）：
     *  · 单个 kind 失败（如 `READ_MEDIA_AUDIO` 没授权 → 查询抛异常）→ **沿用该 kind 的上一份数据**，
     *    不写空。否则"用户只授权了视频"会让整轮被判失败，缓存永远建不起来，
     *    于是每次读都退化成同步拉取 —— 正好把这次要消灭的实时调用又请回来了。
     *  · 全部 kind 都失败 → 不写快照（保留上一份好的）。
     *
     * @param gen 本轮开始时的代次；写回前比对，不等则丢弃。
     */
    private suspend fun loadAll(gen: Long): Snapshot? {
        val loader = this.loader ?: return null
        val keys = this.keys
        if (keys.isEmpty()) return null

        val previous = snapshot
        val items = HashMap<String, List<Map<String, Any?>>>(keys.size)
        var anySuccess = false
        var failedKeys = 0

        for (key in keys) {
            val rows = runCatching { loader(key) }.getOrElse { e ->
                AppLogger.w(TAG, "媒体索引拉取失败($key): ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (rows == null) {
                failedKeys++
                // 这一类当前不可用（未授权 / 查询失败）→ 沿用上一份；连上一份都没有就**不放这个 key**，
                // 读路径据此知道"快照里没有这一类"，改走实时查询（见 `MediaRoutes.indexItems`）。
                // 绝不写空：把"没授权"写成空列表，客户端会显示成"这台设备没有音乐"。
                previous?.items?.get(key)?.let { items[key] = it }
            } else {
                items[key] = rows
                anySuccess = true
            }
        }

        if (!anySuccess) {
            AppLogger.w(TAG, "媒体索引整轮拉取失败（$failedKeys 类全部失败），保留旧快照")
            return null
        }

        val fresh = Snapshot(items, System.currentTimeMillis())
        // 代次校验：本轮结果是否还有效。失效的来源是 [invalidate]（删除文件 / 排除名单变更）——
        // 那意味着本轮读到的很可能是改动**前**的库，写回会把刚删掉的文件又请回快照里。
        if (generation.get() != gen) {
            AppLogger.i(TAG, "媒体索引本轮结果在拉取期间已被作废（invalidate），丢弃不写")
            return null
        }
        snapshot = fresh
        AppLogger.i(
            TAG,
            "媒体索引已刷新：${fresh.totalItems()} 条" +
                keys.joinToString("", "（", "）") { "$it=${fresh.of(it).size}" } +
                if (failedKeys > 0) "，$failedKeys 类沿用旧数据" else ""
        )
        return fresh
    }

    /**
     * 失效快照（**只置 null + 作废在飞的刷新，不触发新刷新**）。
     *
     * 供"媒体库内容被外部改动"的写路径调用（文件删除、排除名单变更）：下一次读会走
     * [snapshotOrLoad] 的冷启动分支同步拉一次，保证用户马上看到变化。
     *
     * 自增 [generation] 是这里的关键：若此刻正有一轮刷新在飞，它读到的是**改动前**的库，
     * 写回就会把刚删掉的文件又请回快照里。自增之后那轮的结果会在写回前被丢弃。
     *
     * 刻意**不**顺带刷新：调用方（如 `DELETE /api/files/delete`）正在等自己的响应，
     * 紧接着串一次整库扫描只会让它变慢。与 `AppManager.invalidateAppListCache` 同一取舍。
     */
    fun invalidate() {
        generation.incrementAndGet()
        snapshot = null
        AppLogger.i(TAG, "媒体索引已失效（含作废在飞的一轮），下次读将重新拉取")
    }

    /**
     * 「重新扫描」提交后的追赶重建调度（见 [CHASE_DELAYS_MS]）。
     *
     * 竞态处理：每次调用自增 [chaseGeneration] 并**取消上一组**追赶协程 —— 用户连点 5 次
     * 「重新扫描」只会留下一组 3 次刷新，而不是 15 次。被取消的那组在每次 delay 之后
     * 复查代次，晚到的旧协程不会白跑一轮查询。
     */
    fun scheduleChaseRefresh(delaysMs: List<Long> = CHASE_DELAYS_MS) {
        val gen = chaseGeneration.incrementAndGet()
        chaseJob?.cancel()
        chaseJob = ownScope.launch {
            for (d in delaysMs) {
                delay(d)
                // 有更新的扫描请求进来 → 本组作废（它那组会覆盖同样的时刻）
                if (chaseGeneration.get() != gen) return@launch
                runCatching { refresh(force = true) }.onFailure {
                    AppLogger.w(TAG, "扫描追赶刷新失败: ${it.message}")
                }
            }
        }
        AppLogger.i(TAG, "已安排扫描追赶刷新：${delaysMs.joinToString("/") { "${it / 1000}s" }}")
    }

    // ─────────────────────────── 生命周期 ───────────────────────────

    /**
     * 启动定时主动刷新。
     *
     * @param scope 必须来自 `BackendService.serviceScope`（它在 `onDestroy` 里被 cancel），
     *   这样即便漏掉显式 stop，协程也会随服务一起死 —— 双保险。
     */
    fun startPeriodicRefresh(scope: CoroutineScope) {
        if (periodicJob?.isActive == true) return
        periodicScope = scope
        periodicJob = scope.launch(Dispatchers.IO) {
            // 先等一个周期再刷：core 刚起来时设备上还在做一堆初始化
            //（AT 通道、goform 登录、update 恢复…），立刻扫整库只是给启动期添乱。
            while (isActive) {
                delay(refreshIntervalMs)
                runCatching { refresh(force = true) }.onFailure {
                    // 定时循环里绝不能把异常放出去（会静默终止这条循环且无日志）
                    AppLogger.w(TAG, "媒体索引定时刷新失败: ${it.message}")
                }
            }
        }
        AppLogger.i(TAG, "媒体索引定时刷新已启动（间隔 ${refreshIntervalMs}ms）")
    }

    /**
     * 暂停/恢复定时刷新 —— 对齐 `BackendService` 的「停止服务 = 除 HTTP 外全停」语义。
     *
     * 不接这一层的话，用户点「停止服务」之后后台仍会每 5 分钟扫一遍整库。
     * 暂停 = 取消协程（而不是置 flag 空转）：设备上少一次整库扫描比多一次唤醒便宜。
     */
    fun setPeriodicRefreshEnabled(enabled: Boolean) {
        val scope = periodicScope
        if (!enabled) {
            periodicJob?.cancel()
            periodicJob = null
            AppLogger.i(TAG, "媒体索引定时刷新已暂停")
            return
        }
        // 恢复需要当初那个 scope；没有（从没启动过）就保持不启动 —— 宁可不刷，
        // 也别自己造一个与服务生命周期脱钩的 scope。
        if (scope != null) startPeriodicRefresh(scope)
    }

    /**
     * 终态收尾：取消追赶调度与自持作用域。由 `BackendService.stopAllComponents` 调用。
     *
     * 只停 [ownScope] 里的事（追赶刷新）；定时刷新挂在 serviceScope 上，由服务自己收。
     */
    fun stop() {
        chaseGeneration.incrementAndGet()   // 让在飞的追赶协程复查时自行退出
        chaseJob?.cancel()
        chaseJob = null
        ownScope.cancel()
        AppLogger.i(TAG, "媒体索引已停止")
    }

    /** 诊断信息（调试端点用）：快照年龄、条目数、定时刷新是否在跑。 */
    fun stats(): Map<String, Any?> {
        val snap = snapshot
        val now = System.currentTimeMillis()
        return mapOf(
            "has_snapshot" to (snap != null),
            "age_ms" to (snap?.let { now - it.timestampMs }),
            "total_items" to (snap?.totalItems() ?: 0),
            "per_kind" to (snap?.items?.mapValues { it.value.size } ?: emptyMap<String, Int>()),
            "ttl_ms" to ttlMs,
            "refresh_interval_ms" to refreshIntervalMs,
            "periodic_running" to (periodicJob?.isActive == true),
            "chase_running" to (chaseJob?.isActive == true)
        )
    }
}
