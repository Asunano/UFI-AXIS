package com.ufi_axis.data.notification

import android.content.Context
import androidx.room.*
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知历史：每一条状态栏通知的**结果**，包括被拦下的那些与拦下的原因。
 *
 * 为什么要留这张表：拆分闸门之后"为什么没收到通知"有 5 个可能答案（全局关、分类关、
 * 系统没授权、去重、限频）。之前这些分支全是静默 `return false`，用户报"收不到"时
 * 除了猜没有别的办法 —— 日志在 release 只留 WARN/ERROR，也看不到正常的丢弃。
 *
 * 【为什么不复用 `CacheDatabase`】那是 `fallbackToDestructiveMigration` 的缓存库，
 * 每次加字段都会清空；历史是用户会去翻的数据，不该被别的表的 schema 变动带走。
 *
 * 【多进程】状态栏通知只由 `:ufi_notify` 发射（[NotifyPrefs.isNotifyProcess]），
 * 所以**逐条写入只发生在那个进程**；主进程只读，外加用户在历史页点「清空」时的一次整表删除。
 * Room 不跨进程共享失效通知，因此：
 * - 建库时开 [RoomDatabase.Builder.enableMultiInstanceInvalidation]，让另一进程的写入能
 *   使自己的查询缓存失效；
 * - UI 侧**进页面时查询一次**，不要用常驻 Flow 等推送（跨进程失效不可靠）。
 */
@Entity(tableName = "notify_history")
data class NotifyHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 发生时刻（毫秒）。倒序展示、按天分组都用它。 */
    val ts: Long,
    /** [NotifyScene.sceneId]，用于在 UI 上归类与筛选。 */
    val sceneId: String,
    val title: String,
    val message: String,
    /** true = 真的发到状态栏了；false = 被拦下，原因见 [blockedBy]。 */
    val delivered: Boolean,
    /** 拦截原因常量（见 [NotifyHistoryStore] 的 REASON_*）；[delivered] 为 true 时是 null。 */
    val blockedBy: String? = null
)

@Dao
interface NotifyHistoryDao {
    @Insert
    suspend fun insert(entity: NotifyHistoryEntity)

    /**
     * 倒序分页：**keyset 游标**，没有 OFFSET。
     *
     * - `cTs/cId` 为上一页末项的 `(ts,id)`；首页两个都传 null。
     * - `delivered` 为 null 时不过滤结果，true/false 分别只看已送达 / 被拦下。
     *
     * 为什么不是 `LIMIT/OFFSET`：写入方是另一个进程（`:ufi_notify`），翻页途中随时可能有新记录
     * 插到表头，OFFSET 会因此漏行；而且三种筛选写成三条几乎相同的 SQL 只会让改动漏掉某一条。
     * 全仓 DAO 层都没有 OFFSET（见 core 的 `SmsBlockedLogDao.getPaged`），这里对齐。
     */
    @Query(
        """
        SELECT * FROM notify_history
        WHERE (:cTs IS NULL OR ts < :cTs OR (ts = :cTs AND id < :cId))
          AND (:delivered IS NULL OR delivered = :delivered)
        ORDER BY ts DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getPaged(cTs: Long?, cId: Long?, delivered: Boolean?, limit: Int): List<NotifyHistoryEntity>

    @Query("SELECT COUNT(*) FROM notify_history")
    suspend fun count(): Int

    /**
     * 环形保留：只留最近 [keep] 条。
     *
     * 用「id 不在最新 keep 个 id 里就删」而不是 `LIMIT -1 OFFSET keep` 那种写法 ——
     * 后者在 SQLite 的 DELETE 里不被支持，必须借子查询。
     */
    @Query(
        "DELETE FROM notify_history WHERE id NOT IN " +
            "(SELECT id FROM notify_history ORDER BY ts DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    /**
     * 按时间清理（与 [trimTo] 是「先到者生效」的两道上限）。
     *
     * 两道上限都由设置项决定（`NotificationConfig` 的 `history_max_rows` / `history_max_age_days`），
     * 代码里没有第三条隐藏规则 —— 2026-09-08 之前这里挂着一条写死的 7 天，界面上看不见，
     * 用户把条数调大也留不住。
     */
    @Query("DELETE FROM notify_history WHERE ts < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM notify_history")
    suspend fun clear()
}

@Database(entities = [NotifyHistoryEntity::class], version = 1, exportSchema = false)
abstract class NotifyHistoryDatabase : RoomDatabase() {
    abstract fun dao(): NotifyHistoryDao

    companion object {
        @Volatile
        private var instance: NotifyHistoryDatabase? = null

        fun getInstance(context: Context): NotifyHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotifyHistoryDatabase::class.java,
                    "ufi_axis_notify_history.db"
                )
                    // 写在 `:ufi_notify`、读在主进程，两个进程各有一个 Room 实例
                    .enableMultiInstanceInvalidation()
                    // 历史是诊断数据、有容量上限，schema 变动时清空可接受；
                    // 但**不要**把它塞回 CacheDatabase —— 那样每次改缓存字段都会连它一起清。
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}

/**
 * 历史写入口。**只允许 [NotificationCenter.notify] 调用**（护栏 `NotifyGateGuardTest` 守住），
 * 这样"每条通知的结果都有记录"不依赖各调用点自觉。
 */
object NotifyHistoryStore {

    /** 全局通知总闸关闭。 */
    const val REASON_MASTER = "master"

    /** 该类型的分类开关关闭。 */
    const val REASON_CATEGORY = "category"

    /** 系统未授予通知权限，或在系统设置里关闭了本应用通知。 */
    const val REASON_PERMISSION = "permission"

    /** 同一内容已通知过（去重）。 */
    const val REASON_DEDUP = "dedup"

    /** 同场景在限频窗口内已发过一条。 */
    const val REASON_RATE_LIMIT = "rate_limit"

    /**
     * 每写入多少条做一次裁剪。逐条裁剪是纯浪费（一次 DELETE 扫全表）。
     *
     * 注意这意味着条数会在上限之上多出最多 [TRIM_EVERY] - 1 条才被裁掉。
     * 保留条数是给用户看"大概留多少"的量级，不是精确水位。
     */
    private const val TRIM_EVERY = 20

    /** 天 → 毫秒。用 Long：365 天的毫秒数早就溢出 Int 了。 */
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * 落库用的独立作用域：通知投递在主线程/服务线程上同步走完，历史写入不能拖慢它，
     * 也不能因为一次 IO 异常把通知链路带崩（[CoroutineExceptionHandler] 兜住）。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            DebugLog.w("NotifyHistory", "写历史失败：${e.message}")
        }
    )

    /**
     * 已写入多少条未裁剪。
     *
     * 用 [AtomicInteger] 而不是 `@Volatile Int`：[record] 每次都往 [Dispatchers.IO] 上 launch，
     * `++` 是读-改-写，告警爆发时增量会丢，`>= TRIM_EVERY` 迟迟不成立 → 条数长期超过用户设定的上限。
     */
    private val sinceTrim = AtomicInteger(0)

    /**
     * 记一条结果。fire-and-forget：调用方不等它。
     *
     * @param delivered 是否真的发到了状态栏
     * @param blockedBy 被拦下的原因（REASON_*）；[delivered] 为 true 时传 null
     */
    fun record(
        context: Context,
        sceneId: String,
        title: String,
        message: String,
        delivered: Boolean,
        blockedBy: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        val app = context.applicationContext
        scope.launch {
            val dao = NotifyHistoryDatabase.getInstance(app).dao()
            dao.insert(
                NotifyHistoryEntity(
                    ts = now,
                    sceneId = sceneId,
                    title = title,
                    message = message,
                    delivered = delivered,
                    blockedBy = blockedBy
                )
            )
            if (sinceTrim.incrementAndGet() >= TRIM_EVERY) {
                sinceTrim.set(0)
                dao.trimTo(maxRows(app))
                // 天数 0 = 不按时间清理（合法值，不是"立刻全删"）。
                val days = maxAgeDays(app)
                if (days > 0) dao.deleteOlderThan(now - days * DAY_MS)
            }
        }
    }

    /**
     * 当前保留条数上限。
     *
     * 真源是 core 的 `NotificationConfig.history_max_rows`，本机镜像在
     * [NotificationCenter.KEY_HISTORY_MAX_ROWS] —— 走 [NotifyPrefs.switchInt] 而不是直读共享
     * prefs：裁剪发生在 `:ufi_notify`（状态栏通知的唯一发射方），那个进程只能读镜像。
     *
     * 钳到允许区间：0 或负数会让 `trimTo` 把整张表清空。
     */
    private fun maxRows(context: Context): Int = NotifyPrefs.switchInt(
        context, NotificationCenter.KEY_HISTORY_MAX_ROWS, NotificationCenter.DEFAULT_HISTORY_MAX_ROWS
    ).coerceIn(NotificationCenter.MIN_HISTORY_MAX_ROWS, NotificationCenter.MAX_HISTORY_MAX_ROWS)

    /**
     * 当前保留天数上限（**0 = 不按时间清理**）。取值口径同 [maxRows]。
     *
     * 与条数是「先到者生效」的两道上限，两道都是设置项 —— 这里不再有写死的时间常量。
     */
    private fun maxAgeDays(context: Context): Int = NotifyPrefs.switchInt(
        context,
        NotificationCenter.KEY_HISTORY_MAX_AGE_DAYS,
        NotificationCenter.DEFAULT_HISTORY_MAX_AGE_DAYS
    ).coerceIn(
        NotificationCenter.MIN_HISTORY_MAX_AGE_DAYS,
        NotificationCenter.MAX_HISTORY_MAX_AGE_DAYS
    )

    /**
     * 读一页历史（keyset 游标，形态与 core 的 `SmsBlockedLogDao.getPaged` 一致）。
     *
     * 按 KDoc 的约定由 UI 在**进页面 / ON_RESUME 时各查一次**，不要用 Flow ——
     * 写入方是另一个进程，Room 的跨进程失效通知不可靠。
     *
     * @param cursorTs 上一页末项的 `ts`；首页传 null（与 [cursorId] 成对，缺一个就当首页）
     */
    suspend fun list(
        context: Context,
        filter: Filter,
        cursorTs: Long?,
        cursorId: Long?,
        limit: Int
    ): List<NotifyHistoryEntity> {
        val dao = NotifyHistoryDatabase.getInstance(context.applicationContext).dao()
        // 游标必须成对：只给 ts 会让 `id < NULL` 恒为 NULL，同一毫秒的边界行被整段跳过。
        val paired = cursorTs != null && cursorId != null
        return dao.getPaged(
            cTs = if (paired) cursorTs else null,
            cId = if (paired) cursorId else null,
            delivered = filter.delivered,
            limit = limit
        )
    }

    /** 历史总条数（列表页顶部的"共 N 条"）。 */
    suspend fun count(context: Context): Int =
        NotifyHistoryDatabase.getInstance(context.applicationContext).dao().count()

    /**
     * 清空整表。
     *
     * 这是**主进程唯一的写入动作**（用户在通知历史页点「清空」）；与 `:ufi_notify` 正在进行的
     * insert 并发时，清空之后可能仍落进一两条新记录 —— 可接受：那些是清空之后才发生的通知。
     */
    suspend fun clear(context: Context) {
        NotifyHistoryDatabase.getInstance(context.applicationContext).dao().clear()
    }

    /** 列表页的结果过滤。 */
    enum class Filter { ALL, DELIVERED, BLOCKED }

    /** 筛选 → SQL 里的 `delivered` 参数（null = 不过滤）。 */
    private val Filter.delivered: Boolean?
        get() = when (this) {
            Filter.ALL -> null
            Filter.DELIVERED -> true
            Filter.BLOCKED -> false
        }
}
