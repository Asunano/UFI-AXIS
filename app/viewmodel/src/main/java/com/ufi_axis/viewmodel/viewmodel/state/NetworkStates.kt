package com.ufi_axis.viewmodel.state

import androidx.compose.runtime.Immutable
import com.ufi_axis.data.model.*

// ========== Network ==========

data class NetworkState(
    val signalInfo: SignalInfo? = null,
    val networkStatus: NetworkStatusResponse? = null,
    val wifiSettings: WifiSettingsResponse? = null,
    val wifiClients: WifiClientsResponse? = null,
    /**
     * WiFi 接入控制名单（拉黑）。null = 还没读过 / 读失败。
     *
     * core 的三个写端点写完都会回读设备并回同一形状，所以拉黑/解除后**直接用响应覆盖这里**，
     * 别本地推算名单：设备是整表替换，本地推算会和另一端（web / 设备自带 UI）的操作互相覆盖。
     */
    val wifiAcl: WifiAclResponse? = null,
    /**
     * 正在下发拉黑 / 解除的设备 MAC（小写）。
     * 只给那一行的按钮置灰用，不要用 [isLoading]（全页共享位，会让整页无故变灰）。
     */
    val aclPendingMac: String? = null,
    val wifiEnabled: Boolean = false,
    val mobileDataEnabled: Boolean = false,
    /**
     * 移动数据开关正在下发 / 等设备生效。
     *
     * 为什么不复用 [isLoading]：那是全页共享位，任何一次刷新都会置 true，
     * 拿它禁用开关会导致开关在页面刷新期间无故变灰（EmailNotifyScreen 踩过同样的坑）。
     */
    val mobileDataPending: Boolean = false,
    val bandStatus: BandStatusResponse? = null,
    val cellInfo: CellInfoResponse? = null,
    /**
     * `GET /api/network/neighbor-cells` 的**实时**邻区列表。
     * [cellInfo] 里那份 `neighbors` 走 core 缓存、可能是旧快照，所以基站页优先读这个，
     * 拉失败（null）才回落 [cellInfo]。
     */
    val neighborCells: NeighborCellsResponse? = null,
    val lanSettings: LanSettingsResponse? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** 每次加载递增，确保缓存返回相同内容时 StateFlow 仍能发射 */
    val loadVersion: Long = 0L
)

// ========== Service Control（2026-08-26） ==========

/**
 * 后端服务控制状态。
 * [enabled] 是"后台采集服务"开关（core 持久化真源），[collecting] 是采集循环的实际运行状态；
 * [loaded] = false 表示还没成功读到过状态，此时 UI 的开关必须置灰（不能拿默认值去写设备）。
 */
data class ServiceControlState(
    val loaded: Boolean = false,
    val enabled: Boolean = true,
    val collecting: Boolean = false,
    val uptimeMs: Long = 0L,
    val autoStartOnBoot: Boolean = true,
    val isBusy: Boolean = false,
    val restarting: Boolean = false,
    val errorMessage: String? = null
)

// ========== Web 控制面板资源（2026-08-27） ==========

/**
 * core 的 Web 控制面板资源状态（`GET /api/web/version`）。
 *
 * [isOverride] = 设备正在用上传的 override（`filesDir/web`）而不是 APK 内置那份。
 * 只有 override 模式下"恢复内置版本"才有意义 —— bundled 模式点了也没东西可删。
 * [bundledVersion] 在 bundled 模式下 core 不返回该字段，此时它与 [version] 同值。
 * [loaded] = false 表示还没成功读到过（可能 core 版本旧、没有 /api/web/version），
 * 此时不能拿默认值当"已是内置版本"展示。
 */
data class WebAssetState(
    val loaded: Boolean = false,
    val isOverride: Boolean = false,
    val version: String? = null,
    val bundledVersion: String? = null,
    val hasBackup: Boolean = false,
    val isBusy: Boolean = false,
    /**
     * `GET /api/web/status` 的自动更新状态；null = 还没读到过（UI 不画进度区）。
     *
     * 本组端点**没有 WS 推送**，进度只能轮询，所以这里保存的是最后一次轮询到的快照。
     */
    val updateStatus: WebUpdateStatusResponse? = null,
    val errorMessage: String? = null
)

// ========== Device Settings ==========

data class DeviceSettingsState(
    val settings: DeviceSettingsResponse? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** 每次加载递增，确保缓存返回相同内容时 StateFlow 仍能发射 */
    val loadVersion: Long = 0L
)

// ========== Speed Test ==========

/**
 * 测速阶段（仿 Speedtest 的阶段推进）。
 * CONNECTING = 正在开流、还没收到响应头；LATENCY = 正在跑延迟/抖动探针；
 * DOWNLOAD = 正在跑下行吞吐；UPLOAD = 正在跑上行吞吐；DONE = 出结果；ERROR = 失败。
 */
enum class SpeedTestPhase { IDLE, CONNECTING, LATENCY, DOWNLOAD, UPLOAD, DONE, ERROR }

/**
 * 测速状态。
 *
 * 2026-08-26 重构：原来只有 `isRunning` + 一句拼好的 `result` 文本，UI 拿不到任何数字，
 * 只能显示一个不确定态进度条。现在全部是可绘制的数值，仪表盘 / 实时曲线 / 结果区都从这里取数。
 *
 * 2026-08-26 第三轮：按 LibreSpeed 的测量方法论补齐 —— 多流并发（[streams]）、
 * 独立探针算出的抖动（[jitterMs]）、上行吞吐（[uploadMbps]）。
 */
@Immutable
data class SpeedTestState(
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    /** "internal" | "external"（空串 = 还没测过） */
    val testType: String = "",
    /** 瞬时速率（Mbps，采样窗口内、已做中位数平滑）——仪表盘指针读这个 */
    val currentMbps: Double = 0.0,
    /** 下行平均速率（Mbps，**已剔除起速爬坡段**）——结果区主数值 */
    val avgMbps: Double = 0.0,
    /** 峰值瞬时速率（Mbps，已剔除起速爬坡段） */
    val peakMbps: Double = 0.0,
    /** 上行平均速率（Mbps）；0 = 未测 */
    val uploadMbps: Double = 0.0,
    /**
     * 上行是否真的测了。外网节点的 `POST /upload` 是可选端点，没部署时客户端跳过上行阶段，
     * 此时 [uploadMbps] 的 0 表示"没测"而不是"测出来是 0"，UI 要靠这个标志区分文案。
     */
    val uploadMeasured: Boolean = false,
    /** 延迟（ms）：多次零负载探针的**中位数**；null = 还没测到 */
    val latencyMs: Double? = null,
    /** 抖动（ms）：相邻探针延迟差的平均绝对值；null = 还没测到 */
    val jitterMs: Double? = null,
    /** 并发流数（0 = 未测） */
    val streams: Int = 0,
    /** 已传输字节数（下行 + 上行） */
    val totalBytes: Long = 0L,
    /** 已用时（秒） */
    val elapsedSec: Double = 0.0,
    /** 总进度 0..1 */
    val progress: Float = 0f,
    /** 瞬时速率采样（Mbps），供仪表盘下方实时曲线用；最多保留 [SAMPLE_LIMIT] 条 */
    val samples: List<Float> = emptyList(),
    val errorMessage: String? = null
) {
    val isRunning: Boolean
        get() = phase == SpeedTestPhase.CONNECTING ||
            phase == SpeedTestPhase.LATENCY ||
            phase == SpeedTestPhase.DOWNLOAD ||
            phase == SpeedTestPhase.UPLOAD

    companion object {
        /** 实时曲线采样上限（约 120 × 150ms ≈ 18s 可视窗口） */
        const val SAMPLE_LIMIT = 120

        /**
         * 外网测速节点清单（固定，不允许用户改地址；UI 与 Module 共用一个真源）。
         *
         * 两类节点：
         *
         * **① 自建节点**（[SpeedTestNode.selfHosted]）：同一份 Pages Function 部署在不同平台，
         * 端点契约一致（见 `<host>/docs`）：
         *  - `GET /speedtest` 由边缘**实时生成**字节流，默认虚拟长度 4 GiB
         *    （`Content-Range: bytes 0-0/4294967296` 已实测），不是静态文件，所以不受
         *    Pages 单文件 25MB 上限影响；
         *  - 响应头 `Cache-Control: no-store` + `Content-Type: application/octet-stream`，
         *    既不会被缓存污染，也不会被 CDN 按文本类型 gzip（统计的是解压后字节，压缩会虚高）；
         *  - `Accept-Ranges: bytes`，延迟探针的 `Range: bytes=0-0` 会拿到 `206` + 1 字节；
         *  - `POST /upload` 是上行丢弃汇，**可选**：没部署时返回 404/405，客户端探到就跳过上行
         *    （见 `NetworkModule.probeExternalUpload`）。
         *
         * **② 第三方文件节点**（[SpeedTestNode.publicFile]）：不需要自建，直接拉别人 CDN 上的
         * 一个静态大文件。只能测下行。选文件的硬性要求见 [SpeedTestNode.publicFile]。
         *
         * 4 GiB / 25s ≈ 1.37 Gbps，自建节点千兆以内不会提前 EOF；文件节点靠
         * `NetworkModule.refillExternal` 反复重下。必须用 https：http 会被 302（实测）。
         */
        /**
         * 外网测速节点清单。
         *
         * 2026-09-06：移除「移动云」文件节点（`publicFile("mcloud", …)`）—— 那是个 1.09MB 的图片，
         * 高带宽下每条流反复重开请求、结果系统性偏低，作为"对照"反而误导。
         * [SpeedTestNode.publicFile] 工厂保留为扩展点（要加别人 CDN 上的大文件时按其 KDoc 的
         * 四条硬性要求选），但当前清单里**没有**文件节点，所以 UI 里"只测下行 / 该节点不测上行"
         * 那些分支现在走不到。
         */
        val EXTERNAL_NODES: List<SpeedTestNode> = listOf(
            SpeedTestNode.selfHosted("edgeone", "EdgeOne", "https://speedtestone.losn.cc")
        )

        /** 默认节点 = 清单第一项 */
        val DEFAULT_EXTERNAL_NODE: SpeedTestNode = EXTERNAL_NODES.first()

        /** 按 id 取节点；id 不认识（例如旧版本存下来的选择）就回落到默认节点。 */
        fun externalNode(id: String): SpeedTestNode =
            EXTERNAL_NODES.firstOrNull { it.id == id } ?: DEFAULT_EXTERNAL_NODE
    }
}

/**
 * 一个外网测速节点。用 [selfHosted] / [publicFile] 两个工厂构造，不要直接写主构造器
 * ——路径拼接和并发流数的取值都在工厂里，散出去就会不一致。
 */
@Immutable
data class SpeedTestNode(
    val id: String,
    /** UI chip 上显示的名字 */
    val label: String,
    /** 下行拉流地址（自建节点是 `/speedtest`，文件节点就是那个文件本身） */
    val downloadUrl: String,
    /** 上行丢弃汇地址；null = 该节点不支持上行（第三方文件节点一定是 null） */
    val uploadUrl: String?,
    /** 下行并发流数，见 [publicFile] 里关于小文件为什么要加流的说明 */
    val streams: Int
) {
    /** chip 下方提示里显示的主机名 */
    val host: String get() = downloadUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')

    companion object {
        /** 自建节点并发流数：4 GiB 虚拟流一次读不完，4 条足够压满千兆 */
        private const val SELF_HOSTED_STREAMS = 4

        /**
         * 文件节点并发流数：8 条。
         *
         * 单条流的上限是 `文件大小 / (传输耗时 + 重开 RTT)`——文件读到 EOF 就得重发一次请求
         * （[NetworkModule.refillExternal]），这一个 RTT 期间该流不搬字节。1MB 文件 + 30ms RTT
         * 时单条流大约到 130~140 Mbps 就压不上去了，靠加流让各条流的 RTT 空档错开。
         */
        private const val FILE_STREAMS = 8

        /**
         * 自建节点：[baseUrl] 不带尾斜杠，三个路径由它派生。
         */
        fun selfHosted(id: String, label: String, baseUrl: String) = SpeedTestNode(
            id = id,
            label = label,
            downloadUrl = "$baseUrl/speedtest",
            uploadUrl = "$baseUrl/upload",
            streams = SELF_HOSTED_STREAMS
        )

        /**
         * 第三方文件节点：直接拉别人 CDN 上的一个静态文件，不需要自建任何东西。**只能测下行。**
         *
         * 选文件的硬性要求（不满足会得到系统性偏差，不是"稍微不准"）：
         *  1. **必须是二进制且不可压缩**（png / jpg / zip / apk / mp4）。OkHttp 默认发
         *     `Accept-Encoding: gzip`，而我们统计的是**解压后**字节：拿 txt/json/html 测速，
         *     CDN 一压缩，测出来的数字会比链路真实带宽高好几倍。图片本身已压缩，CDN 不会再压。
         *  2. **响应带 `Content-Length` 且支持 `Accept-Ranges: bytes`**。延迟探针发
         *     `Range: bytes=0-0` 期待 `206` + 1 字节；不支持 Range 的服务器会回 `200` + 整个文件，
         *     探针只能立刻关连接（见 `NetworkModule.probeExternal`），延迟数据会偏大且浪费流量。
         *  3. **越大越好**。文件越小，每条流重开请求越频繁，高带宽下偏低越明显：
         *     1MB 文件在 30ms RTT 下单流约 130 Mbps 见顶（靠 [FILE_STREAMS] 加流缓解）。
         *     经验值：想稳测 500 Mbps 以上，单文件至少 20~50MB。
         *  4. 不要选会 302 到别的域、或带鉴权/防盗链（Referer 校验）的地址——重定向多一跳 RTT，
         *     防盗链会直接 403。
         *
         * 已选的移动云图片实测（2026-08-27）：`image/png`、`Content-Length: 1138179`（1.09MB）、
         * `Accept-Ranges: bytes`、`Range: bytes=0-0` → `206`、无 `Content-Encoding`。
         * 1.09MB 偏小，所以它测出来的高带宽结果偏保守；作为"不用自建也能量一把"的对照节点够用。
         *
         * 注意 `Cache-Control: max-age=86400` 是它自己的响应头，不影响我们：
         * `OkHttpClientProvider` 没配 `Cache`，客户端不会有本地缓存把结果做假。
         */
        fun publicFile(id: String, label: String, fileUrl: String) = SpeedTestNode(
            id = id,
            label = label,
            downloadUrl = fileUrl,
            uploadUrl = null,
            streams = FILE_STREAMS
        )
    }
}

// ========== Pairing ==========

/** 已配对设备记录（与 Core PairedDeviceStore 字段对齐；时间戳 epoch millis）。 */
data class PairedDeviceItem(
    val fingerprint: String,
    val deviceName: String,
    val lastSeen: Long,
    val createdAt: Long
)

data class PairingState(
    val paired: Boolean = false,
    val deviceId: String = "",
    val deviceName: String = "",
    val hasDefaultPassword: Boolean = true,
    val pairedFingerprints: List<String> = emptyList(),
    val pairedCount: Int = 0,
    val pairedAt: Long = 0,
    val pairingCode: String = "",
    val pairingEnabled: Boolean = false,
    val pairingMaxDevices: Int = 0,
    val devices: List<PairedDeviceItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
