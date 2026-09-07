package com.ufi_axis_core.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * CPU 历史 DAO
 */
@Dao
interface CpuHistoryDao {
    @Insert
    suspend fun insert(record: CpuHistoryRecord): Long

    @Insert
    suspend fun insertAll(records: List<CpuHistoryRecord>)

    @Query("SELECT * FROM cpu_history WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getRecordsSince(startTime: Long): List<CpuHistoryRecord>

    @Query("SELECT * FROM cpu_history WHERE timestamp >= :startTime ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecordsSinceLimited(startTime: Long, limit: Int): List<CpuHistoryRecord>

    @Query("SELECT timestamp, usagePercent, temperature FROM cpu_history WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getLightweightSince(startTime: Long): List<CpuHistoryLight>

    /**
     * 区间桶聚合（SQL 层降采样）：usagePercent
     * 闭区间 [startTime, endTime]，按 bucketMs 分桶直接产出 avg/min/max，
     * 避免全量拉取原始数据到内存再降采样（30 天 × 3s ≈ 86 万条）。
     */
    @Query("""
        SELECT (timestamp / :bucketMs) AS bucket, AVG(usagePercent) AS avgVal, MIN(usagePercent) AS minVal, MAX(usagePercent) AS maxVal
        FROM cpu_history WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY bucket ORDER BY bucket ASC
    """)
    suspend fun getAggregatedUsageBetween(startTime: Long, endTime: Long, bucketMs: Long): List<CpuAggregateBucket>

    /**
     * 区间桶聚合（SQL 层降采样）：temperature
     * 闭区间 [startTime, endTime]，按 bucketMs 分桶直接产出 avg/min/max。
     */
    @Query("""
        SELECT (timestamp / :bucketMs) AS bucket, AVG(temperature) AS avgVal, MIN(temperature) AS minVal, MAX(temperature) AS maxVal
        FROM cpu_history WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY bucket ORDER BY bucket ASC
    """)
    suspend fun getAggregatedTemperatureBetween(startTime: Long, endTime: Long, bucketMs: Long): List<CpuAggregateBucket>

    @Query("SELECT * FROM cpu_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRecords(limit: Int): List<CpuHistoryRecord>

    @Query("DELETE FROM cpu_history WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM cpu_history WHERE rowid IN (SELECT rowid FROM cpu_history WHERE timestamp < :cutoff LIMIT :limit)")
    suspend fun deleteOlderThanBatched(cutoff: Long, limit: Int = 1000): Int

    @Query("SELECT COUNT(*) FROM cpu_history")
    suspend fun getCount(): Int
}

/**
 * CPU 历史轻量查询结果
 */
data class CpuHistoryLight(
    val timestamp: Long,
    val usagePercent: Double,
    val temperature: Double
)

/**
 * CPU 区间桶聚合结果（SQL 层聚合，字段名统一 bucket/avgVal/minVal/maxVal）
 * 供 getAggregatedUsageBetween / getAggregatedTemperatureBetween 共用。
 */
data class CpuAggregateBucket(
    val bucket: Long,
    val avgVal: Double,
    val minVal: Double,
    val maxVal: Double
)

/**
 * 流量记录 DAO
 */
@Dao
interface TrafficDao {
    @Insert
    suspend fun insert(record: TrafficRecord): Long

    @Insert
    suspend fun insertAll(records: List<TrafficRecord>)

    @Query("SELECT * FROM traffic_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): TrafficRecord?

    @Query("SELECT * FROM traffic_records WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getRecordsSince(startTime: Long): List<TrafficRecord>

    @Query("SELECT * FROM traffic_records WHERE timestamp >= :startTime ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecordsSinceLimited(startTime: Long, limit: Int): List<TrafficRecord>

    @Query("SELECT timestamp, rxSpeed, txSpeed FROM traffic_records WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getLightweightSince(startTime: Long): List<TrafficLight>

    /**
     * 区间桶聚合（SQL 层降采样）：rxSpeed
     * 闭区间 [startTime, endTime]，按 bucketMs 分桶直接产出 avg/min/max。
     */
    @Query("""
        SELECT (timestamp / :bucketMs) AS bucket, AVG(rxSpeed) AS avgVal, MIN(rxSpeed) AS minVal, MAX(rxSpeed) AS maxVal
        FROM traffic_records WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY bucket ORDER BY bucket ASC
    """)
    suspend fun getAggregatedRxBetween(startTime: Long, endTime: Long, bucketMs: Long): List<TrafficAggregateBucket>

    /**
     * 区间桶聚合（SQL 层降采样）：txSpeed
     * 闭区间 [startTime, endTime]，按 bucketMs 分桶直接产出 avg/min/max。
     */
    @Query("""
        SELECT (timestamp / :bucketMs) AS bucket, AVG(txSpeed) AS avgVal, MIN(txSpeed) AS minVal, MAX(txSpeed) AS maxVal
        FROM traffic_records WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY bucket ORDER BY bucket ASC
    """)
    suspend fun getAggregatedTxBetween(startTime: Long, endTime: Long, bucketMs: Long): List<TrafficAggregateBucket>

    @Query("SELECT * FROM traffic_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRecords(limit: Int): List<TrafficRecord>

    @Query("DELETE FROM traffic_records WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM traffic_records WHERE rowid IN (SELECT rowid FROM traffic_records WHERE timestamp < :cutoff LIMIT :limit)")
    suspend fun deleteOlderThanBatched(cutoff: Long, limit: Int = 1000): Int

    @Query("SELECT COUNT(*) FROM traffic_records")
    suspend fun getCount(): Int
}

/**
 * 流量轻量查询结果
 */
data class TrafficLight(
    val timestamp: Long,
    val rxSpeed: Long,
    val txSpeed: Long
)

/**
 * 流量区间桶聚合结果（SQL 层聚合，字段名统一 bucket/avgVal/minVal/maxVal）
 * 供 getAggregatedRxBetween / getAggregatedTxBetween 共用。
 */
data class TrafficAggregateBucket(
    val bucket: Long,
    val avgVal: Double,
    val minVal: Double,
    val maxVal: Double
)

/**
 * 信号历史 DAO
 */
@Dao
interface SignalDao {
    @Insert
    suspend fun insert(record: SignalRecord): Long

    @Insert
    suspend fun insertAll(records: List<SignalRecord>)

    @Query("SELECT * FROM signal_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): SignalRecord?

    @Query("SELECT * FROM signal_history WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getRecordsSince(startTime: Long): List<SignalRecord>

    @Query("SELECT * FROM signal_history WHERE timestamp >= :startTime ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecordsSinceLimited(startTime: Long, limit: Int): List<SignalRecord>

    @Query("SELECT timestamp, rsrp, sinr FROM signal_history WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getLightweightSince(startTime: Long): List<SignalLight>

    /**
     * 区间桶聚合（SQL 层降采样）：rsrp
     * 闭区间 [startTime, endTime]，按 bucketMs 分桶直接产出 avg/min/max。
     * 剔除 rsrp = 0 哨兵值（无信号），与 hours 路径 filter { it.second != 0.0 } 语义一致。
     */
    @Query("""
        SELECT (timestamp / :bucketMs) AS bucket, AVG(rsrp) AS avgVal, MIN(rsrp) AS minVal, MAX(rsrp) AS maxVal
        FROM signal_history WHERE timestamp >= :startTime AND timestamp <= :endTime AND rsrp != 0
        GROUP BY bucket ORDER BY bucket ASC
    """)
    suspend fun getAggregatedRsrpBetween(startTime: Long, endTime: Long, bucketMs: Long): List<SignalAggregateBucket>

    /**
     * 区间桶聚合（SQL 层降采样）：sinr
     * 闭区间 [startTime, endTime]，按 bucketMs 分桶直接产出 avg/min/max。
     * 剔除 sinr = 0 哨兵值（无信号），与 hours 路径 filter { it.second != 0.0 } 语义一致。
     */
    @Query("""
        SELECT (timestamp / :bucketMs) AS bucket, AVG(sinr) AS avgVal, MIN(sinr) AS minVal, MAX(sinr) AS maxVal
        FROM signal_history WHERE timestamp >= :startTime AND timestamp <= :endTime AND sinr != 0
        GROUP BY bucket ORDER BY bucket ASC
    """)
    suspend fun getAggregatedSinrBetween(startTime: Long, endTime: Long, bucketMs: Long): List<SignalAggregateBucket>

    @Query("SELECT * FROM signal_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRecords(limit: Int): List<SignalRecord>

    @Query("DELETE FROM signal_history WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM signal_history WHERE rowid IN (SELECT rowid FROM signal_history WHERE timestamp < :cutoff LIMIT :limit)")
    suspend fun deleteOlderThanBatched(cutoff: Long, limit: Int = 1000): Int

    @Query("SELECT COUNT(*) FROM signal_history")
    suspend fun getCount(): Int
}

/**
 * 信号轻量查询结果
 */
data class SignalLight(
    val timestamp: Long,
    val rsrp: Int,
    val sinr: Int
)

/**
 * 信号区间桶聚合结果（SQL 层聚合，字段名统一 bucket/avgVal/minVal/maxVal）
 * 供 getAggregatedRsrpBetween / getAggregatedSinrBetween 共用。
 */
data class SignalAggregateBucket(
    val bucket: Long,
    val avgVal: Double,
    val minVal: Double,
    val maxVal: Double
)

/**
 * 告警记录 DAO
 */
@Dao
interface AlertDao {
    @Insert
    suspend fun insert(record: AlertRecord): Long

    @Update
    suspend fun update(record: AlertRecord)

    @Query("SELECT * FROM alert_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentAlerts(limit: Int): List<AlertRecord>

    /**
     * 按时间区间查询告警（闭区间 [startTime, endTime]，含端点）。
     * 供前端事件中心「按日期/范围查看」使用（后端 /api/alerts/list 的 start_time/end_time 参数）。
     * 告警发生频率远低于监控采样点，首期不加分页；如需大区间分页可后续加 LIMIT/OFFSET。
     */
    @Query("SELECT * FROM alert_records WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getAlertsBetween(startTime: Long, endTime: Long): List<AlertRecord>

    @Query("SELECT * FROM alert_records WHERE acknowledged = 0 ORDER BY timestamp DESC")
    suspend fun getUnacknowledgedAlerts(): List<AlertRecord>

    @Query("SELECT * FROM alert_records WHERE type = :type ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByType(type: String): AlertRecord?

    @Query("DELETE FROM alert_records WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("UPDATE alert_records SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    /**
     * 删除单条告警（事件中心「更多操作 → 删除」）。
     * 纯 DELETE 语句，不改表结构 → 无需 Room 迁移。
     *
     * @param id 告警主键
     * @return 实际删除的行数（0 = 该 id 不存在，调用方可据此提示）
     */
    @Query("DELETE FROM alert_records WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    // ===================== P1 去重改造（2026-08-23） =====================

    @Query("SELECT * FROM alert_records WHERE type = :type AND level = :level AND acknowledged = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getUnacknowledged(type: String, level: String): AlertRecord?

    /**
     * 聚合更新：同 (type,level) 且未确认的告警行累加 count，并刷新时间戳、清除恢复标记。
     * 返回受影响行数；0 = 无匹配行（调用方应改为 INSERT 新行）。
     * 配合 AlertEngine 边沿触发，避免稳态下反复 insert 同一告警。
     */
    @Query("""
        UPDATE alert_records
        SET count = count + 1, timestamp = :now, resolvedAt = NULL
        WHERE id IN (
            SELECT id FROM alert_records
            WHERE type = :type AND level = :level AND acknowledged = 0
            LIMIT 1
        )
    """)
    suspend fun bumpExisting(type: String, level: String, now: Long): Int

    /**
     * 恢复标记：将该 type 下最新一条未确认告警标记 resolvedAt（条件回到 normal 时调用）。
     * 返回受影响行数。
     */
    @Query("""
        UPDATE alert_records
        SET resolvedAt = :now
        WHERE id = (
            SELECT id FROM alert_records
            WHERE type = :type AND acknowledged = 0
            ORDER BY timestamp DESC LIMIT 1
        )
    """)
    suspend fun markResolved(type: String, now: Long): Int

    /**
     * 环形上限裁剪：仅保留最近 maxRows 条（按 timestamp DESC），其余淘汰。
     * 防止历史无限增长撑爆 DB。
     */
    @Query("""
        DELETE FROM alert_records
        WHERE id NOT IN (
            SELECT id FROM alert_records ORDER BY timestamp DESC LIMIT :maxRows
        )
    """)
    suspend fun trimTo(maxRows: Int)

    /**
     * 游标分页 + 多维过滤。
     * - cTs/cId 为上一页末项的 (timestamp,id)；首页传 null 走全量最新。
     * - 排序固定 timestamp DESC, id DESC，与游标 keyset 一致。
     */
    @Query("""
        SELECT * FROM alert_records
        WHERE (:level IS NULL OR level = :level)
          AND (:type IS NULL OR type = :type)
          AND (:unreadOnly = 0 OR acknowledged = 0)
          AND (:start IS NULL OR timestamp >= :start)
          AND (:end IS NULL OR timestamp <= :end)
          AND (:cTs IS NULL OR timestamp < :cTs OR (timestamp = :cTs AND id < :cId))
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getPaged(
        level: String?, type: String?, unreadOnly: Int,
        start: Long?, end: Long?, cTs: Long?, cId: Long?, limit: Int
    ): List<AlertRecord>

    /** 过滤后总数（不含分页）。 */
    @Query("""
        SELECT COUNT(*) FROM alert_records
        WHERE (:level IS NULL OR level = :level)
          AND (:type IS NULL OR type = :type)
          AND (:unreadOnly = 0 OR acknowledged = 0)
          AND (:start IS NULL OR timestamp >= :start)
          AND (:end IS NULL OR timestamp <= :end)
    """)
    suspend fun getCountFiltered(
        level: String?, type: String?, unreadOnly: Int, start: Long?, end: Long?
    ): Int

    /** 按类型分组计数（事件中心分类胶囊用）。 */
    @Query("SELECT type, COUNT(*) as cnt FROM alert_records GROUP BY type")
    suspend fun getCountByType(): List<TypeCount>

    /** 按级别分组未读计数（critical/warning/info）。 */
    @Query("SELECT level, COUNT(*) as cnt FROM alert_records WHERE acknowledged = 0 GROUP BY level")
    suspend fun getUnreadCountByLevel(): List<LevelCount>

    /** 未读总数。 */
    @Query("SELECT COUNT(*) FROM alert_records WHERE acknowledged = 0")
    suspend fun getUnreadCount(): Int

    /** 按 id 列表批量确认。 */
    @Query("UPDATE alert_records SET acknowledged = 1 WHERE id IN (:ids)")
    suspend fun ackByIds(ids: List<Long>)

    /** 按过滤条件批量确认（type/level/unreadOnly）。 */
    @Query("""
        UPDATE alert_records SET acknowledged = 1
        WHERE (:type IS NULL OR type = :type)
          AND (:level IS NULL OR level = :level)
          AND (:unreadOnly = 0 OR acknowledged = 0)
    """)
    suspend fun ackByFilter(type: String?, level: String?, unreadOnly: Int): Int

    /**
     * 确认已恢复的旧告警（resolvedAt 非空，且可选满足最小年龄）。
     * 供事件中心「一键清理已恢复」使用。
     */
    @Query("""
        UPDATE alert_records SET acknowledged = 1
        WHERE resolvedAt IS NOT NULL
          AND (:minAgeSec IS NULL OR (strftime('%s','now') - resolvedAt / 1000) >= :minAgeSec)
    """)
    suspend fun ackResolved(minAgeSec: Long?): Int
}

/** 聚合计数：按类型 */
data class TypeCount(
    val type: String,
    val cnt: Int
)

/** 聚合计数：按级别 */
data class LevelCount(
    val level: String,
    val cnt: Int
)

/**
 * 短信记录 DAO
 */
@Dao
interface SmsDao {
    @Insert
    suspend fun insert(record: SmsRecord): Long

    @Query("SELECT * FROM sms_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<SmsRecord>

    @Query("SELECT * FROM sms_records WHERE phoneNumber = :phone ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesByPhone(phone: String, limit: Int): List<SmsRecord>

    @Query("DELETE FROM sms_records WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

/**
 * 短信已读状态 DAO
 * 本地管理已读状态，不再依赖设备 goform tag 字段。
 */
@Dao
interface SmsReadStateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: SmsReadState)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<SmsReadState>)

    /** 批量查询已读状态（用于合并到 API 响应） */
    @Query("SELECT msg_id, `read` FROM sms_read_state WHERE msg_id IN (:ids)")
    suspend fun getReadStates(ids: List<Long>): List<ReadStatePair>

    /** 标记单条已读 */
    @Query("UPDATE sms_read_state SET `read` = 1 WHERE msg_id = :msgId")
    suspend fun markRead(msgId: Long)

    /** 标记单条未读 */
    @Query("UPDATE sms_read_state SET `read` = 0 WHERE msg_id = :msgId")
    suspend fun markUnread(msgId: Long)

    /** 按号码批量标记已读（打开对话时） */
    @Query("UPDATE sms_read_state SET `read` = 1 WHERE phone = :phone")
    suspend fun markConversationRead(phone: String)

    /** 全部标记已读 */
    @Query("UPDATE sms_read_state SET `read` = 1")
    suspend fun markAllRead()

    /** 未读总数 */
    @Query("SELECT COUNT(*) FROM sms_read_state WHERE `read` = 0")
    suspend fun getUnreadCount(): Int

    /** 按号码统计未读数（联系人列表用） */
    @Query("SELECT phone, COUNT(*) as cnt FROM sms_read_state WHERE `read` = 0 AND phone != '' GROUP BY phone")
    suspend fun getUnreadByPhone(): List<UnreadByPhone>

    /** 清理已读且超过指定时间的记录（可选维护） */
    @Query("DELETE FROM sms_read_state WHERE `read` = 1 AND first_seen < :cutoff")
    suspend fun deleteOldReadBefore(cutoff: Long): Int
}

/** 轻量查询：msg_id → read 映射 */
data class ReadStatePair(
    val msg_id: Long,
    val read: Boolean
)

/** 按号码未读计数 */
data class UnreadByPhone(
    val phone: String,
    val cnt: Int
)

/**
 * 短信验证码缓存 DAO
 * DataScheduler 实时扫描写入，API 读取缓存，cleanOldData 定期清理。
 */
@Dao
interface SmsVerificationCodeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: SmsVerificationCode)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<SmsVerificationCode>)

    /** 全部缓存结果（按时间倒序） */
    @Query("SELECT * FROM sms_verification_codes ORDER BY timestamp DESC")
    suspend fun getAll(): List<SmsVerificationCode>

    /** 最近 N 条 */
    @Query("SELECT * FROM sms_verification_codes ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SmsVerificationCode>

    /** 检查 msg_id 是否已存在（去重用） */
    @Query("SELECT COUNT(*) FROM sms_verification_codes WHERE msg_id = :msgId")
    suspend fun exists(msgId: Long): Int

    /** 按 msg_id 删除（短信删除级联） */
    @Query("DELETE FROM sms_verification_codes WHERE msg_id = :msgId")
    suspend fun deleteByMsgId(msgId: Long)

    /**
     * 回填 body（原短信全文）。
     *
     * v8 迁移只能给旧记录补 `''`，全文仍然缺失 —— `extractCode` 只对**新收到**的短信写 body。
     * 详情页要看全文，所以读取时按 msg_id 回查原短信并用本方法写回（一次性成本）。
     */
    @Query("UPDATE sms_verification_codes SET body = :body WHERE msg_id = :msgId")
    suspend fun updateBody(msgId: Long, body: String)

    /** 过期清理 */
    @Query("DELETE FROM sms_verification_codes WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** 总数（Tab 角标用） */
    @Query("SELECT COUNT(*) FROM sms_verification_codes")
    suspend fun getCount(): Int
}

/**
 * 内存历史 DAO
 */
@Dao
interface MemoryHistoryDao {
    @Insert
    suspend fun insert(record: MemoryHistoryRecord): Long

    @Insert
    suspend fun insertAll(records: List<MemoryHistoryRecord>)

    @Query("SELECT * FROM memory_history WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getRecordsSince(startTime: Long): List<MemoryHistoryRecord>

    @Query("SELECT * FROM memory_history WHERE timestamp >= :startTime ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecordsSinceLimited(startTime: Long, limit: Int): List<MemoryHistoryRecord>

    @Query("SELECT timestamp, usagePercent FROM memory_history WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getLightweightSince(startTime: Long): List<MemoryLight>

    /**
     * 区间桶聚合（SQL 层降采样）：usagePercent
     * 闭区间 [startTime, endTime]，按 bucketMs 分桶直接产出 avg/min/max。
     */
    @Query("""
        SELECT (timestamp / :bucketMs) AS bucket, AVG(usagePercent) AS avgVal, MIN(usagePercent) AS minVal, MAX(usagePercent) AS maxVal
        FROM memory_history WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY bucket ORDER BY bucket ASC
    """)
    suspend fun getAggregatedUsageBetween(startTime: Long, endTime: Long, bucketMs: Long): List<MemoryAggregateBucket>

    @Query("SELECT * FROM memory_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRecords(limit: Int): List<MemoryHistoryRecord>

    @Query("DELETE FROM memory_history WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM memory_history WHERE rowid IN (SELECT rowid FROM memory_history WHERE timestamp < :cutoff LIMIT :limit)")
    suspend fun deleteOlderThanBatched(cutoff: Long, limit: Int = 1000): Int

    @Query("SELECT COUNT(*) FROM memory_history")
    suspend fun getCount(): Int
}

/**
 * 内存轻量查询结果
 */
data class MemoryLight(
    val timestamp: Long,
    val usagePercent: Double
)

/**
 * 内存区间桶聚合结果（SQL 层聚合，字段名统一 bucket/avgVal/minVal/maxVal）
 */
data class MemoryAggregateBucket(
    val bucket: Long,
    val avgVal: Double,
    val minVal: Double,
    val maxVal: Double
)

/**
 * 电池历史 DAO
 */
@Dao
interface BatteryHistoryDao {
    @Insert
    suspend fun insert(record: BatteryHistoryRecord): Long

    @Insert
    suspend fun insertAll(records: List<BatteryHistoryRecord>)

    @Query("SELECT * FROM battery_history WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getRecordsSince(startTime: Long): List<BatteryHistoryRecord>

    @Query("SELECT * FROM battery_history WHERE timestamp >= :startTime ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecordsSinceLimited(startTime: Long, limit: Int): List<BatteryHistoryRecord>

    @Query("SELECT timestamp, level FROM battery_history WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getLightweightSince(startTime: Long): List<BatteryLight>

    /**
     * 区间桶聚合（SQL 层降采样）：level
     * 闭区间 [startTime, endTime]，按 bucketMs 分桶直接产出 avg/min/max。
     */
    @Query("""
        SELECT (timestamp / :bucketMs) AS bucket, AVG(level) AS avgVal, MIN(level) AS minVal, MAX(level) AS maxVal
        FROM battery_history WHERE timestamp >= :startTime AND timestamp <= :endTime
        GROUP BY bucket ORDER BY bucket ASC
    """)
    suspend fun getAggregatedLevelBetween(startTime: Long, endTime: Long, bucketMs: Long): List<BatteryAggregateBucket>

    @Query("SELECT * FROM battery_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRecords(limit: Int): List<BatteryHistoryRecord>

    @Query("DELETE FROM battery_history WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM battery_history WHERE rowid IN (SELECT rowid FROM battery_history WHERE timestamp < :cutoff LIMIT :limit)")
    suspend fun deleteOlderThanBatched(cutoff: Long, limit: Int = 1000): Int

    @Query("SELECT COUNT(*) FROM battery_history")
    suspend fun getCount(): Int
}

/**
 * 电池轻量查询结果
 */
data class BatteryLight(
    val timestamp: Long,
    val level: Int
)

/**
 * 电池区间桶聚合结果（SQL 层聚合，字段名统一 bucket/avgVal/minVal/maxVal）
 */
data class BatteryAggregateBucket(
    val bucket: Long,
    val avgVal: Double,
    val minVal: Double,
    val maxVal: Double
)
