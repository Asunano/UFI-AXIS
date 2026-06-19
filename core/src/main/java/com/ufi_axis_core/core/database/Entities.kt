package com.ufi_axis_core.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 流量记录实体
 * 记录每次采样的实时流量数据
 */
@Serializable
@Entity(tableName = "traffic_records", indices = [Index(value = ["timestamp"])])
data class TrafficRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rxBytes: Long,          // 接收字节数
    val txBytes: Long,          // 发送字节数
    val rxSpeed: Long,          // 接收速度 (bytes/s)
    val txSpeed: Long,          // 发送速度 (bytes/s)
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 信号历史实体
 * 记录信号质量变化历史
 */
@Serializable
@Entity(tableName = "signal_history", indices = [Index(value = ["timestamp"])])
data class SignalRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rsrp: Int,              // 参考信号接收功率 (dBm)
    val sinr: Int,              // 信噪比 (dB)
    val rsrq: Int,              // 参考信号接收质量 (dB)
    val rssi: Int,              // 接收信号强度 (dBm)
    val rat: String,            // 网络制式 (4G/5G)
    val operator: String,       // 运营商
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 告警记录实体
 */
@Serializable
@Entity(tableName = "alert_records")
data class AlertRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,           // 告警类型 (temperature/battery/traffic/signal)
    val level: String,          // 告警级别 (info/warning/critical)
    val message: String,        // 告警消息
    val value: String,          // 触发值
    val threshold: String,      // 阈值
    val acknowledged: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * CPU 历史实体
 * 记录 CPU 使用率和各核频率变化历史
 */
@Serializable
@Entity(tableName = "cpu_history", indices = [Index(value = ["timestamp"])])
data class CpuHistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val usagePercent: Double,
    val coreCount: Int,
    val maxFreqMhz: Double,
    val temperature: Double,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 短信记录实体
 */
@Serializable
@Entity(tableName = "sms_records")
data class SmsRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: String,      // sent / received
    val phoneNumber: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
