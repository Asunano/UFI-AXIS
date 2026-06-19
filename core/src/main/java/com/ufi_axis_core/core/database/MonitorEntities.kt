package com.ufi_axis_core.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 内存历史实体
 * 记录内存使用率变化历史
 */
@Serializable
@Entity(tableName = "memory_history", indices = [Index(value = ["timestamp"])])
data class MemoryHistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val total: Long,
    val used: Long,
    val available: Long,
    val usagePercent: Double,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 电池历史实体
 * 记录电池电量、温度、充电状态变化历史
 */
@Serializable
@Entity(tableName = "battery_history", indices = [Index(value = ["timestamp"])])
data class BatteryHistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val level: Int,
    val isCharging: Boolean,
    val temperature: Double,
    val voltage: Double,
    val timestamp: Long = System.currentTimeMillis()
)
