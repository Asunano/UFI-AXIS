package com.ufi_axis_core.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 测速历史 DAO（定时测速，2026-10-06）。查询形态与 SignalDao 一致（时间窗 + 行数上限）。
 */
@Dao
interface SpeedTestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SpeedTestRecord)

    /** 最近 N 条（时间倒序），历史列表首屏。 */
    @Query("SELECT * FROM speedtest_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<SpeedTestRecord>

    /** 时间窗内记录（图表用），升序。 */
    @Query("SELECT * FROM speedtest_history WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getSince(sinceMs: Long): List<SpeedTestRecord>

    @Query("DELETE FROM speedtest_history WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM speedtest_history")
    suspend fun getCount(): Int
}
