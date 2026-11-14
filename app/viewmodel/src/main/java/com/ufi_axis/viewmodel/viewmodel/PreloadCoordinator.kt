package com.ufi_axis.viewmodel

import com.ufi_axis.data.monitor.MonitorMetricType
import com.ufi_axis.data.monitor.MonitorTimeRange
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.module.DashboardModule
import com.ufi_axis.viewmodel.module.NetworkModule
import com.ufi_axis.viewmodel.module.ToolsModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 首屏预加载的进度快照，供启动页展示。
 *
 * @param issued 已**发起**的步骤数。刻意不叫 `done` —— module 里那些 `loadXxx()` 是
 *   fire-and-forget（内部 `scope.launch`），协调器只能知道"已经喊出去了"，
 *   拿不到"回包已落地"。启动页真正的收尾条件由调用方再叠一个数据就绪判据
 *   （见 `MainViewModel.startupGate`），不能只看这个数。
 * @param total  步骤总数。
 * @param label  当前正在做什么，直接当启动页那行小字用。
 * @param finished 所有步骤都已发起。
 */
data class PreloadProgress(
    val issued: Int = 0,
    val total: Int = PreloadCoordinator.TOTAL_STEPS,
    val label: String = "正在连接设备",
    val finished: Boolean = false
)

/**
 * 首屏预加载协调器（2026-09-22）。
 *
 * ## 它解决的问题
 * 冷启动进仪表盘之后，切到网络 / 监控要现场发请求 —— 网络页尤其明显：6 个请求、零持久化，
 * 每次 `pageForeground` 翻 true 都重发一遍；监控页总览那 6 格要等 6 条曲线到齐。
 * 这里在启动页还挡着的时候就把三个 Tab 的数据拉好，页面侧的新鲜度闸门
 *（`NetworkModule.loadNetworkAll` / `DashboardModule.refreshDashboardIfStale` /
 * `loadMonitorTypes` 的 `loadedMonitorTypes` 记账）会认出"已经有数据"而直接渲染。
 *
 * ## 三条硬约束（都不是可选的）
 *
 * ### 1. 必须分批，不能一把梭
 * [com.ufi_axis.util.OkHttpClientProvider] 没有配 `Dispatcher`，走 OkHttp 默认
 * `maxRequestsPerHost = 5`，而 `/api` 全打同一 host。一次丢十几个请求进去只会排队，
 * `readTimeout` 是 120s，一个挂住的请求占一个槽两分钟。批间隔 [BATCH_GAP_MS] 给前一批留回包时间。
 *
 * 这里刻意**没有**真正"等上一批完成"：那些 `loadXxx()` 要等就得全改成 suspend。
 * `delay` 是明知故犯的近似，代价上限是两批重叠 —— 而重叠也不会超过那 5 个槽位。
 *
 * ### 2. 必须静默
 * 预加载发生在启动页背后。失败若写进 `networkState.errorMessage` /
 * `serviceState.errorMessage`，会经 [MainViewModel.rawGlobalError] 冒成全局 Toast ——
 * 表现为"刚开 app 就莫名弹一句加载失败"。所以走各 module 的 `silent = true` 变体，
 * 且每一步再套一层 [step] 吞异常。
 *
 * ### 3. 只在确证在线时启动
 * 这是最容易忽略、后果最难看的一条。`HealthModule` 的掉线判据是 3 秒内 2 次失败确证
 * （`CONFIRM_FAILURE_COUNT` / `CONFIRM_FAILURE_SPAN_MS`），而每个传输层失败都会驱动一次探活。
 * 设备不可达时丢出十来个必定失败的请求，**正好自己把"掉线"坐实**，于是开机就弹
 * 「无法连接后端服务」。所以调用方必须等 `connectivity` 翻 `ONLINE` 才 [start]。
 *
 * ## 为什么没有工具页与我的页
 * 工具页没有任何数据加载（静态入口网格）；我的页只有 `/api/service/status`，已经在批 1 里了。
 *
 * ## 为什么监控只预热总览那几类
 * 监控页是按**可见图块**拉指标的（`registerVisibleMonitorTypes`），图表 Tab 哪几块可见这里不知道。
 * 但总览 Tab 是默认落地页、它的 6 格用到的序列是确定的 —— 见 [OVERVIEW_METRIC_KEYS]。
 * `signal_sinr` / `battery` 总览不显示，省掉两个请求；图表 Tab 首次进入时各自补拉。
 */
internal class PreloadCoordinator(
    private val scope: CoroutineScope,
    private val dashboard: DashboardModule,
    private val network: NetworkModule,
    private val tools: ToolsModule
) {
    companion object {
        /** 步骤总数，与 [start] 里 [step] 的调用次数严格一致。 */
        const val TOTAL_STEPS = 7

        /**
         * 批间隔（ms）。见类注释「必须分批」。
         *
         * 取 800 而不是更长：这段时间用户正盯着启动页等，每多一批就多等一个间隔。
         * 800ms 已经够让上一批的 3~8 个请求在局域网里回包大半。
         */
        const val BATCH_GAP_MS = 800L

        /**
         * 总览 Tab 那 6 格真正读的序列。
         *
         * 依据：`MonitorOverview.computePeakMetrics` 只消费 cpu / memory / traffic_rx+tx /
         * signal_rsrp / temperature 五条（第 6 格「今日流量」来自 `dashboardState.trafficSummary`，
         * 不在 monitorState 里）。`loadMonitorTypes` 内部会再与 `settings.enabledTypes` 求交集，
         * 所以用户关掉的类型不会被强拉。
         */
        val OVERVIEW_METRIC_KEYS: List<String> = listOf(
            MonitorMetricType.CPU,
            MonitorMetricType.MEMORY,
            MonitorMetricType.TRAFFIC_RX,
            MonitorMetricType.TRAFFIC_TX,
            MonitorMetricType.SIGNAL_RSRP,
            MonitorMetricType.TEMPERATURE
        ).map { it.apiKey }

        private const val TAG = "Preload"
    }

    /**
     * 整个进程只跑一次。用 [AtomicBoolean] 而不是 `Boolean`：[start] 的调用方是
     * `connectivity` 的收集协程 + 启动页闸门，两者不保证在同一个线程上。
     */
    private val started = AtomicBoolean(false)

    /**
     * 与 [started] 同一理由用 `@Volatile`：本字段被 [start] 写、被 [reset] 读写，
     * 而这两个调用方（`connectivity` 收集协程 / 启动页闸门）不保证在同一个线程上。
     * 没有可见性保证时，[reset] 可能读到一个过期的（甚至 null）引用而漏掉 cancel。
     */
    @Volatile
    private var job: Job? = null

    /**
     * 轮次序号。用 [AtomicInteger]，强度与 [started] 的 [AtomicBoolean] 对齐 ——
     * 理由同上：写方（[reset]）与读方（[start] 开的那条协程）不保证同线程。
     *
     * 它解决的是**取消竞态**：[reset] 里 `job.cancel()` 是协作式的，而各步骤里多数
     * `loadXxx()` 是 fire-and-forget（无挂起点），旧轮次会一路跑到下一个挂起点才真正停，
     * 期间仍能把 [_progress] 写回 —— 包括那行 `finished = true`，
     * 而 `MainViewModel.startupGate` 正等着 `progress.first { it.finished }`，
     * 于是启动门可能被**旧轮**提前放行。
     *
     * 刻意**不用** `cancelAndJoin`：那些请求跑在各 module 自己的 scope 上，
     * join 协调器这条 job 拦不住已经飞出去的请求，只会把 [reset] 变成挂起函数，
     * 连带改掉 `MainViewModel` 那条同步调用链。所以改为「写之前先验轮次」。
     */
    private val generation = AtomicInteger(0)

    private val _progress = MutableStateFlow(PreloadProgress())

    /** 供启动页订阅的进度。 */
    val progress: StateFlow<PreloadProgress> = _progress.asStateFlow()

    /** 已经跑过（或正在跑）。 */
    val hasStarted: Boolean get() = started.get()

    /**
     * 启动预加载。重复调用无副作用（第二次起直接返回）。
     *
     * 调用前提由调用方保证：**已配对** + `connectivity` 为 `ONLINE`。
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        DebugLog.d(TAG, "首屏预加载开始")
        // 本轮的轮次号。之后每一次写 _progress 都先拿它与当前 generation 比，
        // 不等说明 reset() 已经换轮 —— 这一轮的写入一律丢弃（见 generation 的注释）。
        val gen = generation.get()
        job = scope.launch {
            // ── 批 1：全局态 + 首屏 ──
            // 服务状态决定各页要不要轮询（serviceEnabled 门），先拿它其余判断才成立。
            step(gen, "读取服务状态") { network.loadServiceStatus(silent = true) }
            // 仪表盘是落地页，它的数据最该在启动页期间就位。
            // IfStale 版不取消在飞的刷新（页面自己也会调），见 DashboardModule 的说明。
            step(gen, "读取设备概况") { dashboard.refreshDashboardIfStale() }

            delay(BATCH_GAP_MS)

            // ── 批 2：网络页 ──
            // loadNetworkAll 自带新鲜度闸门：若用户已经手动切到网络页、页面自己拉过了，这里是空操作。
            step(gen, "读取网络信息") { network.loadNetworkAll(silent = true) }
            step(gen, "读取流量配置") { tools.loadTrafficLimit() }

            delay(BATCH_GAP_MS)

            // ── 批 3：监控页 ──
            // 顺序有硬依赖：settings 决定 enabledTypes，enabledTypes 决定 loadMonitorTypes
            // 拉哪几类；`collectEnabled = false` 时 loadMonitorTypes 直接空转。
            // refreshMonitorSettingsFromBackend 是 suspend，所以这里的先后是真的先后。
            step(gen, "读取监控设置") {
                dashboard.refreshMonitorSettingsFromBackend()
                dashboard.selectMonitorRange(MonitorTimeRange.today())
                dashboard.loadStartupTime()
            }
            step(gen, "读取监控曲线") {
                // 复用 selectMonitorRange 刚写进 state 的那个实例，**不要**再 new 一个
                // MonitorTimeRange.today()：它的 endMs 按分钟向下取整，跨分钟就是另一个
                // 记账 key（`monitorRangeKey`），会让 MonitorScreen 进页面时把这 6 类重拉一遍。
                val range = dashboard.monitorState.value.selectedRange
                dashboard.loadMonitorTypes(
                    types = OVERVIEW_METRIC_KEYS,
                    range = range,
                    silent = true
                )
            }
            step(gen, "读取告警记录") { dashboard.loadAlerts(silent = true) }

            // ★ 这一行是竞态里最要命的写入：`MainViewModel.startupGate` 等的就是
            //   `progress.first { it.finished }`。旧轮次写到这里必须被拦掉，
            //   否则新一轮才刚开始、启动门就被旧轮放行了。
            //   校验与写入必须在**同一次原子更新**里：分成两步时 reset() 可能正好落在中间。
            _progress.update { cur ->
                if (gen != generation.get()) cur else cur.copy(label = "准备就绪", finished = true)
            }
            DebugLog.d(TAG, "首屏预加载结束")
        }
    }

    /**
     * 换设备 / 换地址后复位，让下一次 `ONLINE` 重新跑一轮。
     * 不复位就会出现"换了设备但新设备的数据没人预加载"。
     *
     * `job.cancel()` 只是协作式的，且**刻意不 join**（理由见 [generation]）。
     * 真正把旧轮次隔离掉的是这里的 `generation.incrementAndGet()`：
     * 递增之后，旧轮次剩下的每一次 [_progress] 写入都会被轮次判据拦下。
     * 顺序也重要 —— 先换轮，再把 [_progress] 复位，否则旧轮可能抢在复位之后写回脏值。
     */
    fun reset() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        _progress.value = PreloadProgress()
        started.set(false)
    }

    /**
     * 单步执行 + 吞异常 + 记进度。
     *
     * `CancellationException` 必须原样上抛，否则 [reset] 取消 [job] 时这里会把取消吃掉，
     * 协程继续往下跑完剩余批次。
     *
     * 进度先把 `label` 置成本步、跑完再 `issued++`：启动页上看到的就是"正在做的那件事"，
     * 而不是"刚做完的那件事"。
     *
     * @param gen 调用方 [start] 进来时捕获的轮次号。两次写 [_progress] 之前都要验一遍：
     *   不等于当前 [generation] 说明 [reset] 已经换轮，本轮的进度一律丢弃（直接 return）。
     */
    private suspend inline fun step(gen: Int, label: String, crossinline block: suspend () -> Unit) {
        if (gen != generation.get()) return
        _progress.value = _progress.value.copy(label = label)
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w(TAG, "预加载步骤「$label」失败（已静默）: ${e.message}")
        }
        if (gen != generation.get()) return
        _progress.value = _progress.value.let { it.copy(issued = it.issued + 1) }
    }

}
