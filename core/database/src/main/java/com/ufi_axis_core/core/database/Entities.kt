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
 *
 * 2026-08-23 P1 去重改造新增字段：
 * - count:      同 (type,level) 未确认累计次数（聚合更新替代反复 insert）
 * - firstSeenAt: 首次出现时间（保留最旧，列表展示"持续 N 次/自 X 起"）
 * - resolvedAt: 恢复时间（条件回到 normal 时标记，用于"已恢复"语义）
 */
@Serializable
@Entity(
    tableName = "alert_records",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["acknowledged"]),
        Index(value = ["type", "level"])
    ]
)
data class AlertRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,           // 告警类型 (temperature/battery/traffic/signal/connectivity)
    val level: String,          // 告警级别 (info/warning/critical)
    val message: String,        // 告警消息
    val value: String,          // 触发值
    val threshold: String,      // 阈值
    val acknowledged: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val count: Int = 1,
    val firstSeenAt: Long = timestamp,
    val resolvedAt: Long? = null
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

/**
 * 短信已读状态实体
 * 独立于设备 goform tag 字段，由软件本地管理已读状态。
 * msg_id 对应 goform 消息 ID（唯一标识一条短信）。
 */
@Serializable
@Entity(tableName = "sms_read_state")
data class SmsReadState(
    @PrimaryKey val msg_id: Long,
    val read: Boolean = true,
    val first_seen: Long = System.currentTimeMillis(),
    val phone: String = ""
)

/**
 * 短信验证码缓存实体
 * DataScheduler 实时扫描新短信提取验证码，结果持久化到此表。
 * 过期清理间隔由 smsCodeCleanupHours 配置控制，0 表示永不清理。
 *
 * [snippet] 与 [body] 是两份不同用途的正文：
 * - snippet：80 字截断预览，列表/通知里直接用，不用担心把长短信整段塞进 UI；
 * - body：**原短信全文**（v8 新增）。详情页要能看全内容，光有 snippet 做不到
 *   （曾经的做法是详情页再回查一次 /api/sms/list，多一次请求还会让弹窗内容闪一下）。
 *   v8 之前入库的旧数据该列为空串，读取方需回退到 snippet。
 */
@Serializable
@Entity(tableName = "sms_verification_codes")
data class SmsVerificationCode(
    @PrimaryKey val msg_id: Long,
    val code: String,
    val source: String,
    val snippet: String,
    val timestamp: Long,
    val keyword: String,
    val created_at: Long = System.currentTimeMillis(),
    val body: String = ""
)
