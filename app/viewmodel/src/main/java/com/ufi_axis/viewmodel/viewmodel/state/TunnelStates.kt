package com.ufi_axis.viewmodel.state

data class TunnelState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,

    // ── FRP（多实例：可同时跑多条通道）──
    val frpVersion: String = "",
    /** 每条有运行期状态的通道（来自 /status 的 frp.instances），是"谁在跑/谁失败了"的唯一真源 */
    val frpInstances: List<TunnelInstanceInfo> = emptyList(),
    val frpConfigItems: List<FrpChannelInfo> = emptyList(), // 通道列表元数据（列表页展示）
    val frpActiveConfig: String = "",              // 当前"选中"的通道名（≠ 正在运行）
    val frpConfigText: String = "",                // 已加载的 TOML 文本（编辑用）
    val frpConfigTextName: String = "",            // frpConfigText 属于哪个通道（防止详情页串台）
    /**
     * 各通道日志，按通道名隔离。
     * 不能只留"一份日志 + 它属于谁"：请求跑在 viewModelScope 里、不随页面销毁取消，
     * 从通道 A 退出再进 B 时，A 的迟到响应会把那份共享日志改成 A 的，B 页面随即变成空白。
     */
    val frpLogs: Map<String, String> = emptyMap(),

    // ── Cloudflare Tunnel（多实例 / 仅 token）──
    val cfVersion: String = "",
    val cfInstances: List<TunnelInstanceInfo> = emptyList(),
    val cfTunnelItems: List<CfTunnelInfo> = emptyList(), // 隧道列表（列表页展示）
    val cfActiveTunnel: String = "",                     // 当前"选中"的隧道名（≠ 正在运行）
    val cfTokenText: String = "",                        // 已加载的 token（详情页编辑用）
    val cfTokenName: String = "",                        // cfTokenText 属于哪条隧道（防止串台）
    /** 各隧道日志，按隧道名隔离（理由同 frpLogs） */
    val cfLogs: Map<String, String> = emptyMap(),

    // 本地端口
    val localPort: Int = 8088,

    // ── 看护设置（后端 AppSettings 是唯一真源，见 GET/PUT /api/tunnel/settings）──
    /** 启动失败 / 意外退出时是否发系统通知（由 app 端读取后发） */
    val tunnelNotifyOnFailure: Boolean = true,
    /** 断开自动重连 + 后端重启后恢复上次在跑的实例 */
    val tunnelAutoReconnect: Boolean = false,
    /** 看护巡检间隔（秒），10..120 */
    val tunnelReconnectIntervalSec: Int = 30,
    /** 同一实例连续重连失败上限（后端常量，只用于文案） */
    val tunnelMaxReconnectAttempts: Int = 3,
    /** 后端"期望在跑"的 FRP 通道名（看护依据） */
    val tunnelGuardedFrp: List<String> = emptyList(),
    /** 后端"期望在跑"的 CF 隧道名 */
    val tunnelGuardedCf: List<String> = emptyList(),

    // ── 可选二进制组件（2026-09-01：frpc / cloudflared 按需下载）──
    /**
     * frpc 组件是否已安装。默认 true 是刻意的：老版本 core 的 /status 里没有 installed 字段，
     * 默认 false 会让所有旧后端都显示"未安装"。
     */
    val frpInstalled: Boolean = true,
    val cfInstalled: Boolean = true,
    /** 组件列表（来自 GET /api/components） */
    val components: List<ComponentInfo> = emptyList(),
    /** 当前安装任务进度；state=idle 表示没有任务 */
    val componentTask: ComponentTask = ComponentTask(),
    /** 组件清单拉取失败原因（更新源不可达等）；空串表示正常 */
    val componentManifestError: String = "",
    /**
     * 远端组件清单正在 core 侧后台拉取（2026-09-03）。
     * true 期间「最新版本 / 可下载」这些字段还没到位，界面显示加载态并稍后回读，
     * 不需要用户手动点「检查更新」。
     */
    val componentManifestLoading: Boolean = false
) {
    /** 某条 FRP 通道的运行期状态（没有记录 = 从未启动过） */
    fun frpInstance(name: String): TunnelInstanceInfo? = frpInstances.firstOrNull { it.name == name }

    /** 某条 CF 隧道的运行期状态 */
    fun cfInstance(name: String): TunnelInstanceInfo? = cfInstances.firstOrNull { it.name == name }

    /** 某条 FRP 通道已加载的日志（没取过 = 空） */
    fun frpLogOf(name: String): String = frpLogs[name] ?: ""

    /** 某条 CF 隧道已加载的日志 */
    fun cfLogOf(name: String): String = cfLogs[name] ?: ""

    /** 正在运行的 FRP 通道数 */
    val frpRunningCount: Int get() = frpInstances.count { it.running }

    /** 正在运行的 CF 隧道数 */
    val cfRunningCount: Int get() = cfInstances.count { it.running }

    /** 是否有任何隧道实例在运行（决定"停止全部"是否可见） */
    val anyRunning: Boolean get() = frpRunningCount > 0 || cfRunningCount > 0
}

/**
 * 单条隧道实例的运行期状态（FRP 通道 / CF 隧道通用）。
 * 多实例下"运行中/失败原因"必须按名字隔离，否则一条失败会显示到别的通道页上。
 */
data class TunnelInstanceInfo(
    val name: String,
    val running: Boolean = false,
    /** Stopped / Running / Error */
    val status: String = "Stopped",
    /** 最近一次失败原因（启动失败或强制停止失败）；成功启动/停止会被后端清空 */
    val lastError: String = ""
)

/** FRP 通道列表项（来自 GET /api/tunnel/frp/configs 的 items） */
data class FrpChannelInfo(
    val name: String,
    val serverAddr: String = "",
    val serverPort: Int = 0,
    val proxyCount: Int = 0,
    val running: Boolean = false
)

/** CF 隧道列表项（来自 GET /api/tunnel/cf/tunnels 的 items；token 本体不在列表里回传） */
data class CfTunnelInfo(
    val name: String,
    val tokenSet: Boolean = false,
    val running: Boolean = false
)

/** 可选组件列表项（来自 GET /api/components 的 components） */
data class ComponentInfo(
    val id: String,
    val name: String = "",
    val description: String = "",
    val installed: Boolean = false,
    val installedVersion: String = "",
    val installedSize: Long = 0,
    /** remote / manual / legacy；空串 = 未安装 */
    val source: String = "",
    val latestVersion: String = "",
    val downloadSize: Long = 0,
    /** 远端清单里有可用直链才能点"下载安装" */
    val available: Boolean = false,
    val updateAvailable: Boolean = false,
    val upstream: String = ""
)

/** 组件安装任务进度（来自 GET /api/components/status） */
data class ComponentTask(
    val id: String = "",
    /** idle / downloading / verifying / extracting / installing / done / failed */
    val state: String = "idle",
    val percent: Int = 0,
    val message: String = ""
) {
    /** 进行中：此时要禁掉所有安装/卸载入口（core 侧同一时刻只允许一个任务） */
    val active: Boolean get() = state in ACTIVE_STATES

    private companion object {
        val ACTIVE_STATES = setOf("downloading", "verifying", "extracting", "installing")
    }
}
