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

    @Query("SELECT * FROM alert_records WHERE acknowledged = 0 ORDER BY timestamp DESC")
    suspend fun getUnacknowledgedAlerts(): List<AlertRecord>

    @Query("SELECT * FROM alert_records WHERE type = :type ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByType(type: String): AlertRecord?

    @Query("DELETE FROM alert_records WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("UPDATE alert_records SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)
}

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
