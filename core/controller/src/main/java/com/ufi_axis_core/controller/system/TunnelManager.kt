package com.ufi_axis_core.controller.system

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 内网穿透统一管理器（**多实例 + 看护**）
 *
 * 设计要点：
 * - FRP 与 CF 是两个完全独立的引擎，可以同时运行；每个引擎内部又可以按 name 同时跑多条实例。
 * - 磁盘是列表的唯一事实来源（frp/configs/<name>.toml、cloudflared/tunnels/<name>.token）；
 *   prefs 只记住"选中了哪个"与"期望在跑哪些"，指向已删除项时自愈。
 * - "谁在运行"只能来自进程本身（引擎的 slot），不能来自 prefs。
 * - 所有涉及进程的复合操作（停并删）都下沉到引擎里、在**实例锁内**完成，这里不做锁外的
 *   check-then-act —— 锁外判断会与启动的存活探测窗口竞态，产生 /status 看不见也停不掉的孤儿进程。
 * - 看护协程只认"期望运行集合"（用户点过启动、且没有主动停止的实例），因此用户主动停掉的实例
 *   永远不会被偷偷拉起来。
 */
class TunnelManager(private val appContext: android.content.Context) {

    companion object {
        private const val TAG = "TunnelManager"

        /** 服务刚起来时先等一会再做第一次恢复：此时网络/DNS 往往还没就绪，立刻拉起大概率白失败一次 */
        private const val FIRST_PASS_DELAY_MS = 5_000L

        /** 同一实例连续重连失败上限；到达后停手并保留失败原因，等用户处理（否则会无限重启刷日志） */
        private const val MAX_RECONNECT_FAILURES = 3
    }

    val frpEngine = FrpEngine(appContext)
    val cfEngine = CloudflareTunnelEngine(appContext)

    private val settings by lazy { AppSettings.getInstance(appContext) }

    private val guardScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var guardJob: Job? = null

    /** name → 连续重连失败次数；用户手动启动会清零 */
    private val frpFailures = ConcurrentHashMap<String, Int>()
    private val cfFailures = ConcurrentHashMap<String, Int>()

    // ── 通知钩子（由 ComponentFactory 接到 NotificationDispatcher::emit）──
    // 隧道是 core 的能力，"看护重连到达上限、放弃"这个事实只有 core 知道。此前隧道异常邮件靠
    // app 轮询 /api/tunnel/status 发现 Error 后回传，app 不在线 = 没有邮件；推送则是另一个
    // 单独的 attachPushService 钩子 —— 同一件事两个出口，加第三个渠道要再缝一遍。
    // 2026-09-08 合成一个：投给哪些渠道由分发器的注册表决定。
    @Volatile
    private var notifier: Notifier? = null

    /** 装配通知钩子；传 null 解除。 */
    fun attachNotifier(n: Notifier?) {
        notifier = n
    }

    /** 看护放弃时通知一次（fire-and-forget）：走分发器，失败只落日志。 */
    private fun notifyGiveUp(kind: String, name: String, attempts: Int, lastError: String) {
        // 设备端要不要推的真源在这里（`tunnel_notify_on_failure`，隧道设置页那个开关写的就是它）：
        // 关掉就既不推也不发。这不是"第二道闸门" —— 分发器管的是全局总闸与免打扰，
        // 而这一位管的是"隧道这类事件本身要不要通报"，两者串联。
        if (!settings.tunnelNotifyOnFailure) return
        val emit = notifier ?: return
        val title = "隧道异常: $kind [$name]"
        val body = buildString {
            appendLine("隧道: $kind [$name]")
            appendLine("连续重连失败: $attempts 次，已停止重试")
            if (lastError.isNotBlank()) appendLine("最后错误: $lastError")
        }.trimEnd()

        guardScope.launch {
            try {
                emit(
                    NotifyEvent(
                        scene = NotifyScenes.TUNNEL,
                        level = NotifyLevel.WARNING,
                        title = title,
                        body = body,
                        // extra 逐字沿用原 PushNotification.extra：app 侧
                        // `NotifyService` 读 kind / name 拼通知，改一个键它就拿不到了。
                        extra = mapOf(
                            "kind" to kind,
                            "name" to name,
                            "status" to "error",
                            "attempts" to attempts.toString(),
                            "error" to lastError
                        )
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "tunnel notify failed: ${e.message}")
            }
        }
    }

    init {
        // 后端进程若被系统杀掉（LMK / 强停服务），frpc/cloudflared 会被 init 收养继续运行，而句柄与
        // 槽位随进程消失，此后 /status 显示"未运行"、停止键也不出现，隧道却仍在对外暴露。
        // 因此服务重新起来时先按"我们自己的配置路径 / token"精确清一遍孤儿，再由看护协程按期望集合拉起。
        try { frpEngine.reapOrphans() } catch (e: Exception) { AppLogger.w(TAG, "reap frp orphans failed: ${e.message}") }
        try { cfEngine.reapOrphans() } catch (e: Exception) { AppLogger.w(TAG, "reap cf orphans failed: ${e.message}") }
        startGuard()
    }

    // ── 期望运行集合（看护的唯一依据，落盘以便后端重启后恢复）──

    private fun parseDesired(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun frpDesired(): List<String> = parseDesired(settings.tunnelFrpDesired)

    private fun cfDesired(): List<String> = parseDesired(settings.tunnelCfDesired)

    private fun addFrpDesired(name: String) {
        val list = frpDesired()
        if (list.contains(name)) return
        settings.tunnelFrpDesired = (list + name).joinToString(",")
    }

    private fun removeFrpDesired(name: String) {
        val list = frpDesired()
        if (!list.contains(name)) return
        settings.tunnelFrpDesired = list.filter { it != name }.joinToString(",")
    }

    private fun addCfDesired(name: String) {
        val list = cfDesired()
        if (list.contains(name)) return
        settings.tunnelCfDesired = (list + name).joinToString(",")
    }

    private fun removeCfDesired(name: String) {
        val list = cfDesired()
        if (!list.contains(name)) return
        settings.tunnelCfDesired = list.filter { it != name }.joinToString(",")
    }

    // ── 启停（按 name，互不影响）──

    /** 启动指定 FRP 通道；成功后加入期望集合（看护据此在意外退出后拉起它） */
    fun startFrpWithConfig(name: String): Boolean {
        val clean = FrpEngine.sanitizeName(name)
        val ok = frpEngine.start(clean)
        if (ok) {
            frpFailures.remove(clean)
            addFrpDesired(clean)
        }
        return ok
    }

    /** 停止指定 FRP 通道（用户主动停 → 移出期望集合，看护不会再把它拉起来） */
    fun stopFrp(name: String): Boolean {
        val clean = FrpEngine.sanitizeName(name)
        removeFrpDesired(clean)
        frpFailures.remove(clean)
        return frpEngine.stop(clean)
    }

    /** 停止全部 FRP 通道（用户主动停 → 清空期望集合） */
    fun stopAllFrp(): Boolean {
        settings.tunnelFrpDesired = ""
        frpFailures.clear()
        return frpEngine.stopAll()
    }

    /** 启动指定 CF 隧道 */
    fun startCfTunnel(name: String): Boolean {
        val clean = FrpEngine.sanitizeName(name)
        val ok = cfEngine.start(clean)
        if (ok) {
            cfFailures.remove(clean)
            addCfDesired(clean)
        }
        return ok
    }

    /** 停止指定 CF 隧道（用户主动停 → 移出期望集合） */
    fun stopCf(name: String): Boolean {
        val clean = FrpEngine.sanitizeName(name)
        removeCfDesired(clean)
        cfFailures.remove(clean)
        return cfEngine.stop(clean)
    }

    /** 停止全部 CF 隧道（用户主动停 → 清空期望集合） */
    fun stopAllCf(): Boolean {
        settings.tunnelCfDesired = ""
        cfFailures.clear()
        return cfEngine.stopAll()
    }

    /** 停止两个引擎的全部实例；任一引擎有实例停不掉就返回 false */
    fun stopAll(): Boolean {
        // 两个都要尝试，不能因为前者失败而短路掉后者
        val frpOk = stopAllFrp()
        val cfOk = stopAllCf()
        return frpOk && cfOk
    }

    // ── 看护（断开自动重连 + 服务启动恢复）──

    /**
     * 启动看护协程。开关（[AppSettings.tunnelAutoReconnect]）在**每轮巡检时读**，
     * 所以用户在设置页开关一下即时生效，不需要重启服务。
     */
    fun startGuard() {
        if (guardJob?.isActive == true) return
        guardJob = guardScope.launch {
            delay(FIRST_PASS_DELAY_MS)
            while (isActive) {
                try {
                    guardPass()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Tunnel guard pass failed: ${e.message}")
                }
                delay(settings.tunnelReconnectIntervalSec * 1000L)
            }
        }
        AppLogger.i(TAG, "Tunnel guard started")
    }

    /** 停止看护。**必须在 [shutdown] 停实例之前调用**，否则看护会跟停止流程抢着重启。 */
    fun stopGuard() {
        guardJob?.cancel()
        guardJob = null
    }

    /** 一轮巡检：期望在跑但实际没在跑 → 拉起；配置已被删 → 移出期望集合 */
    private fun guardPass() {
        // 开关只控制"要不要拉起"；"配置已不存在就移出期望集合"与它无关，
        // 否则关掉自动重连之后，设置页的"看护中"会一直显示已经被删掉的隧道。
        val autoReconnect = settings.tunnelAutoReconnect

        // 组件未安装（2026-09-01 起 frpc/cloudflared 按需下载）时整段跳过：
        // 否则每轮都白失败一次，凑满 MAX_RECONNECT_FAILURES 后还会发一封"放弃重连"邮件，
        // 而真正的原因只是用户还没下载组件。
        val frpInstalled = frpEngine.isInstalled()
        val cfInstalled = cfEngine.isInstalled()

        if (frpInstalled) for (name in frpDesired()) {
            if (frpEngine.isRunning(name)) {
                frpFailures.remove(name)
                continue
            }
            if (frpEngine.readConfigFile(name) == null) {
                // 配置在别处被删掉了，期望集合跟着自愈，否则每轮都要白失败一次
                AppLogger.w(TAG, "Guard: FRP config [$name] gone, dropping from desired set")
                removeFrpDesired(name)
                continue
            }
            if (!autoReconnect) continue
            val fails = frpFailures[name] ?: 0
            if (fails >= MAX_RECONNECT_FAILURES) continue
            // desired 是本轮开头的快照，而单次 start() 最长要占用约 1.7 秒（存活探测）。
            // 这段窗口里用户完全可能点了"停止"，所以拉起前再确认一次成员资格，
            // 否则会把用户主动停掉的隧道又拉起来（且它已不在 desired 里，UI 上还显示"未看护"）。
            if (!frpDesired().contains(name)) continue
            AppLogger.w(TAG, "Guard: restarting FRP [$name] (attempt ${fails + 1})")
            if (frpEngine.start(name)) {
                frpFailures.remove(name)
            } else {
                val n = fails + 1
                frpFailures[name] = n
                if (n >= MAX_RECONNECT_FAILURES) {
                    AppLogger.e(
                        TAG,
                        "Guard: giving up on FRP [$name] after $n attempts: ${frpEngine.lastErrorOf(name)}"
                    )
                    notifyGiveUp("FRP", name, n, frpEngine.lastErrorOf(name))
                }
            }
        }

        if (cfInstalled) for (name in cfDesired()) {
            if (cfEngine.isRunning(name)) {
                cfFailures.remove(name)
                continue
            }
            if (!cfEngine.tunnelExists(name)) {
                AppLogger.w(TAG, "Guard: CF tunnel [$name] gone, dropping from desired set")
                removeCfDesired(name)
                continue
            }
            if (!autoReconnect) continue
            val fails = cfFailures[name] ?: 0
            if (fails >= MAX_RECONNECT_FAILURES) continue
            // 同 FRP：拉起前复查 desired，避免把用户刚停掉的隧道又拉起来
            if (!cfDesired().contains(name)) continue
            AppLogger.w(TAG, "Guard: restarting CF tunnel [$name] (attempt ${fails + 1})")
            if (cfEngine.start(name)) {
                cfFailures.remove(name)
            } else {
                val n = fails + 1
                cfFailures[name] = n
                if (n >= MAX_RECONNECT_FAILURES) {
                    AppLogger.e(
                        TAG,
                        "Guard: giving up on CF tunnel [$name] after $n attempts: ${cfEngine.lastErrorOf(name)}"
                    )
                    notifyGiveUp("Cloudflare", name, n, cfEngine.lastErrorOf(name))
                }
            }
        }
    }

    // ── 看护相关设置（供 /api/tunnel/settings 读写）──

    fun guardSettings(): Map<String, Any> = mapOf(
        "auto_reconnect" to settings.tunnelAutoReconnect,
        "reconnect_interval_sec" to settings.tunnelReconnectIntervalSec,
        "notify_on_failure" to settings.tunnelNotifyOnFailure,
        "max_reconnect_attempts" to MAX_RECONNECT_FAILURES,
        // 期望在跑的实例（看护的依据），让 UI 能解释"为什么它自己起来了"
        "frp_desired" to frpDesired(),
        "cf_desired" to cfDesired()
    )

    /** 更新看护设置（只改传进来的字段）；返回更新后的全量设置 */
    fun updateGuardSettings(
        autoReconnect: Boolean? = null,
        reconnectIntervalSec: Int? = null,
        notifyOnFailure: Boolean? = null
    ): Map<String, Any> {
        val prevInterval = settings.tunnelReconnectIntervalSec
        autoReconnect?.let { settings.tunnelAutoReconnect = it }
        reconnectIntervalSec?.let { settings.tunnelReconnectIntervalSec = it }
        notifyOnFailure?.let { settings.tunnelNotifyOnFailure = it }
        if (autoReconnect == true) {
            // 刚打开开关时清掉历史失败计数，否则之前放弃过的实例要等用户手动启动才会再被看护
            frpFailures.clear()
            cfFailures.clear()
        }
        // 循环体每轮重读设置，但**当前那次 delay 已经排好了**：把间隔从 120s 改成 10s
        // 或刚打开开关时，用户得干等最多两分钟才看到效果。这两种情况直接重排看护协程。
        val intervalShrunk = reconnectIntervalSec != null && reconnectIntervalSec < prevInterval
        if (autoReconnect == true || intervalShrunk) {
            stopGuard()
            startGuard()
        }
        return guardSettings()
    }

    // ── 状态 ──

    /**
     * 某个可选组件当前是否有实例在跑（卸载前置检查：不能把正在被执行的二进制删掉）。
     * 组件 id 与 [com.ufi_axis_core.util.BinaryComponentStore] 的常量一致。
     */
    fun hasRunningInstances(componentId: String): Boolean = when (componentId) {
        "frpc" -> frpEngine.snapshot().any { it.running }
        "cloudflared" -> cfEngine.snapshot().any { it.running }
        else -> false
    }

    /**
     * 当前是否有任意隧道实例在运行（frpc 或 cloudflared）。
     *
     * 用途之一是「请求是否来自隧道」的判据：没有隧道在跑，回环接收地址只可能是本机直连
     * （见 `PairingRoutes.isTunnelOrigin`）。
     *
     * 只反映**本 core 进程拉起**的实例：被系统杀掉后残留的孤儿进程不在其中，这也是
     * init 阶段要 [reapOrphans] 的原因。
     */
    fun anyRunning(): Boolean = frpEngine.anyRunning() || cfEngine.anyRunning()

    /** 组件安装/卸载后调用：清掉两个引擎的版本缓存，让 /status 重新探测 */
    fun invalidateComponentCaches() {
        frpEngine.invalidateVersionCache()
        cfEngine.invalidateVersionCache()
    }

    /**
     * 全局状态（不含日志正文：日志按 name 单独取，否则每次轮询都要传 200 行 × N 条）
     */
    fun getStatus(): Map<String, Any> {
        val frpInstances = frpEngine.snapshot()
        val cfInstances = cfEngine.snapshot()
        return mapOf(
            "frp" to mapOf(
                "installed" to frpEngine.isInstalled(),
                "version" to (frpEngine.probeVersion() ?: ""),
                "running_count" to frpInstances.count { it.running },
                "instances" to frpInstances.map { it.toMap() }
            ),
            "cf_tunnel" to mapOf(
                "installed" to cfEngine.isInstalled(),
                "version" to (cfEngine.probeVersion() ?: ""),
                "running_count" to cfInstances.count { it.running },
                "instances" to cfInstances.map { it.toMap() }
            ),
            "local_port" to settings.port,
            "auto_reconnect" to settings.tunnelAutoReconnect,
            "notify_on_failure" to settings.tunnelNotifyOnFailure
        )
    }

    private fun FrpEngine.InstanceStatus.toMap(): Map<String, Any> = mapOf(
        "name" to name,
        "running" to running,
        "status" to status.name,
        "last_error" to lastError
    )

    // ── FRP 通道 CRUD ──

    /**
     * 当前选中的 FRP 通道（自愈：指向已删除通道时清空；只有一个通道时自动选中它）
     */
    fun activeFrpConfig(): String {
        val configs = frpEngine.listConfigFiles()
        val current = settings.tunnelFrpActiveConfig
        if (current.isNotBlank() && configs.contains(current)) return current
        val fixed = if (configs.size == 1) configs[0] else ""
        if (fixed != current) settings.tunnelFrpActiveConfig = fixed
        return fixed
    }

    /** 列出所有 FRP 通道（含结构化摘要与每条的运行状态） */
    fun listFrpConfigs(): Map<String, Any> {
        val configs = frpEngine.listConfigFiles()
        val active = activeFrpConfig()
        val items = configs.map { name ->
            val toml = frpEngine.readConfigFile(name) ?: ""
            val summary = summarizeFrpToml(toml)
            mapOf(
                "name" to name,
                "server_addr" to summary.first,
                "server_port" to summary.second,
                "proxy_count" to summary.third,
                "running" to frpEngine.isRunning(name),
                "status" to frpEngine.statusOf(name).name,
                "last_error" to frpEngine.lastErrorOf(name)
            )
        }
        return mapOf(
            "configs" to configs,
            "active" to active,
            "running" to frpEngine.runningNames(),
            "items" to items
        )
    }

    /**
     * 从 TOML 里提取 serverAddr / serverPort / 代理数量。
     * 这里只做行级解析：frpc 配置是扁平结构，引入 TOML 解析库不值得，且解析失败也不应影响列表。
     */
    private fun summarizeFrpToml(toml: String): Triple<String, Int, Int> {
        var addr = ""
        var port = 0
        var proxies = 0
        toml.lines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#")) return@forEach
            when {
                line.startsWith("[[proxies]]") -> proxies++
                line.startsWith("serverAddr") || line.startsWith("server_addr") ->
                    addr = line.substringAfter("=", "").trim().trim('"', '\'')
                line.startsWith("serverPort") || line.startsWith("server_port") ->
                    port = line.substringAfter("=", "").trim().trim('"', '\'').toIntOrNull() ?: 0
            }
        }
        return Triple(addr, port, proxies)
    }

    /** 读取某通道 TOML（不存在返回 null） */
    fun readFrpConfig(name: String): String? = frpEngine.readConfigFile(name)

    /** 保存某通道 TOML */
    fun saveFrpConfigFile(name: String, tomlText: String): Boolean {
        val ok = frpEngine.saveConfigFile(name, tomlText)
        if (ok && settings.tunnelFrpActiveConfig.isBlank()) {
            // 第一条通道保存后顺手选中，避免 UI 出现"有配置但没有选中项"
            settings.tunnelFrpActiveConfig = FrpEngine.sanitizeName(name)
        }
        return ok
    }

    /** 设为选中通道（通道不存在返回 false → 路由层 404） */
    fun setActiveFrpConfig(name: String): Boolean {
        val clean = FrpEngine.sanitizeName(name)
        if (!frpEngine.listConfigFiles().contains(clean)) return false
        settings.tunnelFrpActiveConfig = clean
        return true
    }

    /**
     * 删除某通道：**停并删由引擎在实例锁内完成**（停不掉就不删，否则配置没了进程还在）。
     * 这里只负责删除成功后修正"选中项"与"期望集合"。
     */
    fun deleteFrpConfig(name: String): Boolean {
        val clean = FrpEngine.sanitizeName(name)
        val ok = frpEngine.stopAndDelete(clean)
        if (ok) {
            removeFrpDesired(clean)
            frpFailures.remove(clean)
            if (settings.tunnelFrpActiveConfig == clean) {
                settings.tunnelFrpActiveConfig = ""
                activeFrpConfig()
            }
        }
        return ok
    }

    // ── CF 隧道 CRUD ──

    /** 当前选中的 CF 隧道（自愈规则同 FRP） */
    fun activeCfTunnel(): String {
        migrateLegacyCfToken()
        val tunnels = cfEngine.listTunnels()
        val current = settings.tunnelCfActiveTunnel
        if (current.isNotBlank() && tunnels.contains(current)) return current
        val fixed = if (tunnels.size == 1) tunnels[0] else ""
        if (fixed != current) settings.tunnelCfActiveTunnel = fixed
        return fixed
    }

    /**
     * 一次性迁移旧版单隧道 token：prefs 里的 token → tunnels/default.token，成功后清空 prefs。
     */
    fun migrateLegacyCfToken() {
        val legacy = settings.tunnelCfToken
        if (legacy.isBlank()) return
        if (cfEngine.tunnelExists("default")) {
            settings.tunnelCfToken = ""
            return
        }
        if (cfEngine.saveTunnel("default", legacy)) {
            settings.tunnelCfToken = ""
            if (settings.tunnelCfActiveTunnel.isBlank()) settings.tunnelCfActiveTunnel = "default"
            AppLogger.i(TAG, "Migrated legacy CF token into tunnels/default.token")
        }
    }

    /** 列出所有 CF 隧道（不含 token 本体） */
    fun listCfTunnels(): Map<String, Any> {
        val active = activeCfTunnel()
        val tunnels = cfEngine.listTunnels()
        val items = tunnels.map { name ->
            mapOf(
                "name" to name,
                "token_set" to !cfEngine.readToken(name).isNullOrBlank(),
                "running" to cfEngine.isRunning(name),
                "status" to cfEngine.statusOf(name).name,
                "last_error" to cfEngine.lastErrorOf(name)
            )
        }
        return mapOf(
            "tunnels" to tunnels,
            "active" to active,
            "running" to cfEngine.runningNames(),
            "items" to items
        )
    }

    /** 读取单条隧道（含 token；不存在返回 null） */
    fun getCfTunnel(name: String): Map<String, Any>? {
        val token = cfEngine.readToken(name) ?: return null
        return mapOf(
            "name" to FrpEngine.sanitizeName(name),
            "token" to token,
            "running" to cfEngine.isRunning(name)
        )
    }

    /** 新建/更新单条隧道 */
    fun saveCfTunnel(name: String, token: String): Boolean {
        val ok = cfEngine.saveTunnel(name, token)
        if (ok && settings.tunnelCfActiveTunnel.isBlank()) {
            settings.tunnelCfActiveTunnel = FrpEngine.sanitizeName(name)
        }
        return ok
    }

    /** 设为选中隧道（不存在返回 false → 路由层 404） */
    fun setActiveCfTunnel(name: String): Boolean {
        val clean = FrpEngine.sanitizeName(name)
        if (!cfEngine.tunnelExists(clean)) return false
        settings.tunnelCfActiveTunnel = clean
        return true
    }

    /** 删除单条隧道：停并删由引擎在实例锁内完成（停不掉就不删） */
    fun deleteCfTunnel(name: String): Boolean {
        val clean = FrpEngine.sanitizeName(name)
        val ok = cfEngine.stopAndDelete(clean)
        if (ok) {
            removeCfDesired(clean)
            cfFailures.remove(clean)
            if (settings.tunnelCfActiveTunnel == clean) {
                settings.tunnelCfActiveTunnel = ""
                activeCfTunnel()
            }
        }
        return ok
    }

    /**
     * 服务停止时清理全部实例。
     *
     * 注意：先停看护再停实例，否则看护会在停止过程中把实例又拉起来；
     * 这里**不清空期望集合** —— 服务下次起来时要靠它恢复（与用户主动停止是两件事）。
     */
    fun shutdown() {
        stopGuard()
        guardScope.cancel()
        try { frpEngine.stopAll() } catch (e: Exception) { AppLogger.w(TAG, "stopAllFrp failed: ${e.message}") }
        try { cfEngine.stopAll() } catch (e: Exception) { AppLogger.w(TAG, "stopAllCf failed: ${e.message}") }
    }
}
