package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.*
import com.ufi_axis.data.monitor.*
import com.ufi_axis.data.monitor.MonitorSettings
import androidx.compose.runtime.Immutable

// ========== Dashboard ==========

// @Immutable：两个 State 都是全 val 的 data class，只通过 copy() 产出新实例，实例创建后内容不再改变。
// 不加注解时 Compose 会因为里面的 List 字段把整个类判为 unstable —— 强跳过模式下这意味着
// 每次重组都要对参数做一次 equals（MonitorState 有 9 个 List，最坏情况深比较 8×720 个点）。
// 标为 Immutable 后改走引用比较，监控页/仪表盘页的重组判定成本降为常数。
@Immutable
data class DashboardState(
    val deviceInfo: DeviceInfoResponse? = null,
    val cpuInfo: CpuInfo? = null,
    val memoryInfo: MemoryInfo? = null,
    val batteryInfo: BatteryInfo? = null,
    val storageInfo: StorageInfo? = null,
    val uptimeInfo: UptimeInfo? = null,
    val trafficRealtime: TrafficRealtime? = null,
    val trafficSummary: TrafficSummary? = null,
    val trafficLimitConfig: TrafficLimitConfig? = null,
    val networkStatus: NetworkStatusResponse? = null,
    val signalInfo: SignalInfo? = null,
    val cpuHistory: List<CpuHistoryRecord> = emptyList(),
    val signalHistory: List<SignalHistoryRecord> = emptyList(),
    val deviceVersion: DeviceVersionResponse? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isOffline: Boolean = false,
    val lastUpdated: Long? = null
)

// ========== Monitor ==========

@Immutable
data class MonitorState(
    val selectedHours: Int = 24,
    val cpuHistory: List<DownsampledPoint> = emptyList(),
    val memoryHistory: List<DownsampledPoint> = emptyList(),
    val trafficRxHistory: List<DownsampledPoint> = emptyList(),
    val trafficTxHistory: List<DownsampledPoint> = emptyList(),
    val signalRsrpHistory: List<DownsampledPoint> = emptyList(),
    val signalSinrHistory: List<DownsampledPoint> = emptyList(),
    val batteryHistory: List<DownsampledPoint> = emptyList(),
    val temperatureHistory: List<DownsampledPoint> = emptyList(),
    val storageInfo: MonitorStorageResponse? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val cleanMessage: String? = null,
    // ── 后端软件开启记录的日期（UTC epoch ms）── 2026-08-08 12:09 新增：监控页"自定义时间范围"对话框 minDateMs 来源
    val startupTimeMs: Long? = null,
    // ── P2 数据层扩展：时间范围 / 对比基线 / 阈值 ──
    val selectedRange: MonitorTimeRange = MonitorTimeRange.Preset(24), // 当前时间范围（Preset/Custom）
    // 事件中心告警日期范围（复用 MonitorTimeRange 模型，与图表页时间筛选同源）。
    // 默认当天（00:00 ~ now，isRealTime=true → 告警轮询）；选历史/自定义区间 → 区间查询后端、停止轮询。
    val alertRange: MonitorTimeRange = MonitorTimeRange.today(),
    val thresholds: Map<String, MetricThreshold> = emptyMap(), // 阈值配置（key=apiKey）
    // 增量游标不在这里：懒加载按类型分批拉取，全局一个游标会让晚加载的类型跳过区间，
    // 见 DashboardModule.monitorTypeCursorMs（每类各自一个游标）。
    val settings: MonitorSettings = MonitorSettings(), // 监控设置（总开关/单指标/个性化 7 项）
    /**
     * 服务端本轮实际生效的桶宽（毫秒，2026-09-03 精度重写新增；0 = 未知）。
     *
     * 图表要靠它区分「相邻两点本来就该挨着」和「中间隔了一堆空桶」——空桶被服务端跳过后，
     * 若不断线就会把几小时的数据空洞画成一条直线。8 类共用一个值：同一区间同一 points，
     * 服务端算出来的桶宽必然相同。
     */
    val bucketMs: Long = 0L,
    val alerts: List<AlertRecord> = emptyList()   // 最近告警事件（来自后端 getAlertList，总览面板用）
)

// ========== Update ==========

/**
 * 设备端 core 的版本 / 更新状态。
 *
 * 2026-09-06：[hasUpdate] 的判据从「core 自报版本 > 字符串 "1.0"」改成
 * `GET /api/update/backend-info` 的 `has_update` —— 原判据语义不成立（拿 core 版本跟一个
 * 写死的常量比），core 版本一旦跨过 1.0 就会每次冷启动都提示更新。版本比对交给 core 自己做。
 *
 * @param serverVersion core 当前运行版本（backend-info 的 current_version，取不到时回退
 *   `GET /api/config/version` 的 version）；关于页顶部「Core x.y.z」也读这个字段。
 * @param latestVersion 清单里 core 的最新版本（backend-info 的 latest_version）；无更新/未知为 null。
 * @param changelog core 新版本的更新日志（backend-info 的 changelog）。
 * @param updateUrl `GET /api/config/version` 的 update_url，仅作兜底展示/跳转用。
 */
data class UpdateState(
    val hasUpdate: Boolean = false,
    val serverVersion: String? = null,
    val latestVersion: String? = null,
    val changelog: String = "",
    val updateUrl: String? = null,
    val checking: Boolean = false,
    val errorMessage: String? = null
)
