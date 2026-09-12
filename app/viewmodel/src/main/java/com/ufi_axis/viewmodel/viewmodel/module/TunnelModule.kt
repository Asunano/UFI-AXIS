package com.ufi_axis.viewmodel.module

import android.content.Context
import android.os.SystemClock
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.state.CfTunnelInfo
import com.ufi_axis.viewmodel.state.ComponentInfo
import com.ufi_axis.viewmodel.state.ComponentTask
import com.ufi_axis.viewmodel.state.FrpChannelInfo
import com.ufi_axis.viewmodel.state.TunnelInstanceInfo
import com.ufi_axis.viewmodel.state.TunnelState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import retrofit2.HttpException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 隧道状态与操作（**多实例**：FRP 通道与 CF 隧道都可以同时跑多条）。
 *
 * 运行期状态一律来自 `/api/tunnel/status` 的 `frp.instances` / `cf_tunnel.instances`，
 * 每条实例带自己的 running/status/last_error；日志按名字单独取（不再塞进 status）。
 */
class TunnelModule(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private fun api() = RetrofitClient.getApiService(AppPreferences(appContext))

    // ── 看护设置（后端是唯一真源：core 的看护协程直接读它，app 只做展示与提交）──

    /**
     * 最近一次看护设置**成功读取**的时刻（单调时钟，`0` = 本进程内从未成功）。
     * 单调时钟的理由见 `DataFreshness.kt`（`currentTimeMillis` 可被用户 / NTP 随时改）。
     */
    @Volatile private var tunnelSettingsSuccessElapsed: Long = 0L

    /**
     * 拉取看护设置；进设置页时调。
     *
     * ## 新鲜度闸门（2026-09-05）
     * 这份数据和 `ToolsModule.loadTrafficLimit` 同类 —— **用户设定项**，除了本 App 自己改
     * （`pushTunnelSettings` 直接用响应里的 `settings` 回写，不走本方法）或 `/status` 顺带回传，
     * 它不会自己变。而 `TunnelSettingsScreen` 的 `LaunchedEffect(Unit)` 每次进页面都会调一次，
     * 于是"退出去再进来"必然多一次 REST 回包整页写（`TunnelState` 被 6 个隧道页面 collect）。
     * 默认走 [isForegroundDataFresh]，窗口 [FOREGROUND_REFRESH_INTERVAL_MS]。
     *
     * 本方法**不写任何 loading 态**（原本就没有），所以除闸门外无需再改。
     *
     * @param force 绕过闸门。目前没有"写后回读"调用方（提交走 [pushTunnelSettings]），
     *   留形参是为了让将来真需要立刻回读的路径有正规出口，而不是去掉闸门。
     */
    fun loadTunnelSettings(force: Boolean = false) {
        if (!force &&
            tunnelSettingsSuccessElapsed > 0L &&
            isForegroundDataFresh(tunnelSettingsSuccessElapsed, SystemClock.elapsedRealtime())
        ) {
            return
        }
        scope.launch {
            try {
                val element = withContext(Dispatchers.IO) { api().getTunnelSettings() }
                if (element is JsonObject) {
                    applyTunnelSettings(element)
                    // 只在成功落地后记新鲜度基准，失败不记（否则一次失败会把后续重拉全跳过）
                    tunnelSettingsSuccessElapsed = SystemClock.elapsedRealtime()
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "读取隧道设置失败: ${e.message}")
            }
        }
    }

    private fun applyTunnelSettings(obj: JsonObject) {
        _state.value = _state.value.copy(
            tunnelAutoReconnect = obj["auto_reconnect"]?.jsonPrimitive?.booleanOrNull
                ?: _state.value.tunnelAutoReconnect,
            tunnelReconnectIntervalSec = obj["reconnect_interval_sec"]?.jsonPrimitive?.intOrNull
                ?: _state.value.tunnelReconnectIntervalSec,
            tunnelNotifyOnFailure = obj["notify_on_failure"]?.jsonPrimitive?.booleanOrNull
                ?: _state.value.tunnelNotifyOnFailure,
            tunnelMaxReconnectAttempts = obj["max_reconnect_attempts"]?.jsonPrimitive?.intOrNull
                ?: _state.value.tunnelMaxReconnectAttempts,
            tunnelGuardedFrp = obj["frp_desired"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: _state.value.tunnelGuardedFrp,
            tunnelGuardedCf = obj["cf_desired"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: _state.value.tunnelGuardedCf
        )
    }

    /**
     * 提交一项设置。先乐观更新 UI，失败则回滚并提示 —— 不能只改本地，
     * 否则又变成"开关拨了但后端没生效"的假开关。
     *
     * [rollback] 只还原**本次改动的那个字段**，不能整份 state 倒回去：请求在飞的这几秒里
     * `/status` 轮询可能已经把实例状态/失败原因写进来了，整状态回滚会把它们一起吞掉
     * （连"从 Running 变 Error"的通知边沿也会被抹平，那次断开就永远不提醒了）。
     */
    private fun pushTunnelSettings(body: Map<String, Any>, rollback: (TunnelState) -> TunnelState) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api().updateTunnelSettings(body) }
                val obj = result as? JsonObject
                val ok = obj?.get("success")?.jsonPrimitive?.booleanOrNull ?: false
                if (!ok) {
                    _state.value = rollback(_state.value).copy(errorMessage = "保存隧道设置失败")
                    return@launch
                }
                (obj["settings"] as? JsonObject)?.let { applyTunnelSettings(it) }
            } catch (e: Exception) {
                _state.value = rollback(_state.value).copy(errorMessage = "保存隧道设置失败: ${e.message}")
            }
        }
    }

    fun setNotifyOnFailure(enabled: Boolean) {
        val before = _state.value.tunnelNotifyOnFailure
        _state.value = _state.value.copy(tunnelNotifyOnFailure = enabled)
        pushTunnelSettings(mapOf("notify_on_failure" to enabled)) { it.copy(tunnelNotifyOnFailure = before) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        val before = _state.value.tunnelAutoReconnect
        _state.value = _state.value.copy(tunnelAutoReconnect = enabled)
        pushTunnelSettings(mapOf("auto_reconnect" to enabled)) { it.copy(tunnelAutoReconnect = before) }
    }

    fun setReconnectInterval(sec: Int) {
        val v = sec.coerceIn(10, 120)
        val before = _state.value.tunnelReconnectIntervalSec
        _state.value = _state.value.copy(tunnelReconnectIntervalSec = v)
        pushTunnelSettings(mapOf("reconnect_interval_sec" to v)) { it.copy(tunnelReconnectIntervalSec = before) }
    }

    /**
     * 连通性测试：TCP Socket 连接到 [serverAddr]:[serverPort]，3 秒超时。
     * 异步回调 [callback] 返回 (ok: Boolean, info: String) —— ok 时 info 为延迟毫秒，失败时为原因。
     */
    fun testConnectivity(serverAddr: String, serverPort: Int, callback: (Boolean, String) -> Unit) {
        if (serverAddr.isBlank()) {
            callback(false, "地址为空")
            return
        }
        scope.launch(Dispatchers.IO) {
            val port = serverPort.takeIf { it > 0 } ?: 7000
            val start = System.currentTimeMillis()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(serverAddr, port), 3000)
                    val latency = System.currentTimeMillis() - start
                    callback(true, "${latency}ms")
                }
            } catch (e: Exception) {
                callback(false, e.message ?: "连接失败")
            }
        }
    }

    /** 清除全部实例的运行日志：日志在设备端进程内存里，必须由后端清 */
    fun clearLogs() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api().clearAllTunnelLogs() }
                _state.value = _state.value.copy(frpLogs = emptyMap(), cfLogs = emptyMap())
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "清除日志失败: ${e.message}")
            }
        }
    }

    /**
     * 最近一次 `/status` **成功落地**的时刻（单调时钟，`0` = 本进程内从未成功）。
     * 只用来回答「有没有可显示的状态数据」，理由见 [loadStatus]。
     * 单调时钟的理由见 `DataFreshness.kt`。
     */
    @Volatile private var statusSuccessElapsed: Long = 0L

    /**
     * 读运行期状态（`GET /api/tunnel/status`）：谁在跑、版本号、是否已安装、失败原因。
     *
     * ## 有数据时不写 loading 态（2026-09-05）
     * 原实现无条件 `copy(isLoading = true)`，而本方法是 `TunnelScreen` / `FrpChannelScreen` /
     * `CfTunnelScreen` 的 **5s 轮询**入口，另外 5 个隧道页面进页面时也各调一次。
     * `TunnelState` 被 6 个页面 `collectAsState`，于是每一轮轮询要付 3 次整页重组
     * （`isLoading = true` → [parseStatus] 写数据 → `isLoading = false`），
     * 其中只有中间那次带来真实变化。
     *
     * 收窄成「本进程还没成功读到过状态」时才写。判据用 `statusSuccessElapsed == 0L`
     * 而不是某个 state 字段：`frpVersion` / `cfInstances` 等在"两个组件都没装"这种
     * 完全正常的情况下会**长期全空**，拿它们当"没数据"会让每一轮轮询又开始写 loading。
     *
     * 收尾的 `copy(isLoading = false)` 保留不动：已经是 false 时 data class 的 copy
     * 结构相等，`MutableStateFlow` 不发射 ⇒ 稳态下每轮轮询只剩 [parseStatus] 那一次重组。
     *
     * ## 为什么**不**加新鲜度闸门
     * 这是"实时运行状态"，5s 轮询短于 10s 新鲜窗口；加了闸门就会把隧道的
     * 启动/断开/重连在界面上压成 10s 才可见，与本页存在的意义直接冲突。
     */
    fun loadStatus() {
        scope.launch {
            try {
                // 注意：这里**不要**清 errorMessage。启动失败后紧接着就会调 loadStatus()，
                // 详情页还每 5 秒轮询一次，清了就等于把"启动失败原因"横幅擦掉（用户根本看不到）。
                if (statusSuccessElapsed == 0L) {
                    _state.value = _state.value.copy(isLoading = true)
                }
                val element = withContext(Dispatchers.IO) { api().getTunnelStatus() }
                if (element !is JsonObject) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = "状态数据格式异常")
                    return@launch
                }
                parseStatus(element)
                // 只在成功落地后记基准，失败不记
                statusSuccessElapsed = SystemClock.elapsedRealtime()
                _state.value = _state.value.copy(isLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, errorMessage = "获取状态失败: ${e.message}")
            }
        }
    }

    fun loadConfigs() {
        loadFrpConfigs()
        loadCfTunnels()
    }

    // ── FRP 通道（多实例）──

    /** 加载所有通道列表（含 items 元数据；不自动拉取通道文本，避免覆盖详情页正在编辑的内容） */
    fun loadFrpConfigs() {
        scope.launch {
            try {
                val element = withContext(Dispatchers.IO) { api().listFrpConfigs() }
                if (element is JsonObject) {
                    val configs = element["configs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    val active = element["active"]?.jsonPrimitive?.contentOrNull ?: ""
                    val items = element["items"]?.jsonArray?.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        FrpChannelInfo(
                            name = name,
                            serverAddr = obj["server_addr"]?.jsonPrimitive?.contentOrNull ?: "",
                            serverPort = obj["server_port"]?.jsonPrimitive?.intOrNull ?: 0,
                            proxyCount = obj["proxy_count"]?.jsonPrimitive?.intOrNull ?: 0,
                            running = obj["running"]?.jsonPrimitive?.booleanOrNull ?: false
                        )
                    } ?: configs.map { FrpChannelInfo(name = it) }
                    _state.value = _state.value.copy(frpConfigItems = items, frpActiveConfig = active)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "加载通道列表失败: ${e.message}")
            }
        }
    }

    /**
     * 读取某个通道的 TOML 文本（填入编辑器）。
     * 通道已不存在（404）不算错误：清空编辑器并刷新列表即可，不要弹错误横幅。
     */
    fun loadFrpConfigText(name: String) {
        scope.launch {
            try {
                val element = withContext(Dispatchers.IO) { api().readFrpConfig(name) }
                if (element is JsonObject) {
                    val toml = element["toml"]?.jsonPrimitive?.contentOrNull ?: ""
                    _state.value = _state.value.copy(frpConfigText = toml, frpConfigTextName = name)
                }
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 404) {
                    _state.value = _state.value.copy(frpConfigText = "", frpConfigTextName = name)
                    loadFrpConfigs()
                } else {
                    _state.value = _state.value.copy(errorMessage = "读取配置失败: ${e.message}")
                }
            }
        }
    }

    /** 保存某个通道（TOML 文本由前端生成）；名字非法时后端返回 400 */
    fun saveFrpConfigFile(name: String, tomlText: String) {
        scope.launch {
            try {
                val body = mapOf<String, Any>("toml" to tomlText)
                val result = withContext(Dispatchers.IO) { api().saveFrpConfigFile(name, body) }
                val success = result.jsonObject["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!success) {
                    _state.value = _state.value.copy(errorMessage = "保存通道失败")
                } else {
                    // 保存成功后设为选中通道并刷新列表
                    withContext(Dispatchers.IO) { api().activateFrpConfig(name) }
                    _state.value = _state.value.copy(
                        frpActiveConfig = name,
                        frpConfigText = tomlText,
                        frpConfigTextName = name
                    )
                    loadFrpConfigs()
                }
            } catch (e: Exception) {
                val msg = if (e is HttpException && e.code() == 400) {
                    "通道名不可用（不能为空或含 \\ / : * ? \" < > | 等字符）"
                } else {
                    "保存通道失败: ${e.message}"
                }
                _state.value = _state.value.copy(errorMessage = msg)
            }
        }
    }

    /** 删除某个通道（后端会先停掉正在运行的它；停不掉则不删并回 success=false） */
    fun deleteFrpConfig(name: String) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api().deleteFrpConfig(name) }
                val success = result.jsonObject["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!success) {
                    _state.value = _state.value.copy(
                        errorMessage = "删除通道 [$name] 失败：它可能仍在运行且无法停止，或配置文件已不存在"
                    )
                }
                loadFrpConfigs()
                loadStatus()
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "删除通道失败: ${e.message}")
            }
        }
    }

    /** 选中某个通道（设为选中并加载文本）；通道已不存在（404）时只刷新列表 */
    fun selectFrpConfig(name: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api().activateFrpConfig(name) }
                _state.value = _state.value.copy(frpActiveConfig = name)
                loadFrpConfigText(name)
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 404) {
                    loadFrpConfigs()
                } else {
                    _state.value = _state.value.copy(errorMessage = "切换通道失败: ${e.message}")
                }
            }
        }
    }

    /** 启动指定通道（不影响其它通道） */
    fun startFrpWithConfig(name: String) {
        scope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true, errorMessage = null)
                val result = withContext(Dispatchers.IO) { api().startFrpConfig(name) }
                val success = result.jsonObject["success"]?.jsonPrimitive?.booleanOrNull ?: false
                val message = result.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: ""
                if (!success) {
                    _state.value = _state.value.copy(errorMessage = message, isLoading = false)
                }
                loadStatus()
                loadFrpConfigs()
                refreshFrpLog(name)
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "启动 FRP 失败: ${e.message}", isLoading = false)
            }
        }
    }

    /** 停止指定通道 */
    fun stopFrp(name: String) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api().stopFrpConfig(name) }
                val success = result.jsonObject["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!success) {
                    _state.value = _state.value.copy(
                        errorMessage = result.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "停止 FRP 失败"
                    )
                }
                loadStatus()
                loadFrpConfigs()
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "停止 FRP 失败: ${e.message}")
            }
        }
    }

    /**
     * 刷新某条通道的运行日志（按名字写进 frpLogs，迟到响应不会清掉其它通道的日志）。
     * @param full true = 读运行期日志文件（完整启动过程）；轮询时用 false，只取内存最近 200 行
     */
    fun refreshFrpLog(name: String, full: Boolean = false) {
        scope.launch {
            try {
                val element = withContext(Dispatchers.IO) {
                    api().getFrpConfigLog(name, if (full) 1 else 0)
                }
                if (element is JsonObject) {
                    val log = element["log"]?.jsonPrimitive?.contentOrNull ?: ""
                    _state.value = _state.value.copy(frpLogs = _state.value.frpLogs + (name to log))
                }
            } catch (_: Exception) {}
        }
    }

    /** 清空某条通道的日志缓冲 */
    fun clearFrpLog(name: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api().clearFrpConfigLog(name) }
                _state.value = _state.value.copy(frpLogs = _state.value.frpLogs + (name to ""))
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "清除日志失败: ${e.message}")
            }
        }
    }

    // ── CF Tunnel（多实例 / 仅 token） ──

    /** 加载 CF 隧道列表（含 items；token 本体不在列表里） */
    fun loadCfTunnels() {
        scope.launch {
            try {
                val element = withContext(Dispatchers.IO) { api().listCfTunnels() }
                if (element is JsonObject) {
                    val names = element["tunnels"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    val active = element["active"]?.jsonPrimitive?.contentOrNull ?: ""
                    val items = element["items"]?.jsonArray?.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        CfTunnelInfo(
                            name = name,
                            tokenSet = obj["token_set"]?.jsonPrimitive?.booleanOrNull ?: false,
                            running = obj["running"]?.jsonPrimitive?.booleanOrNull ?: false
                        )
                    } ?: names.map { CfTunnelInfo(name = it) }
                    _state.value = _state.value.copy(cfTunnelItems = items, cfActiveTunnel = active)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "加载隧道列表失败: ${e.message}")
            }
        }
    }

    /** 读取某条隧道的 token（详情页）；隧道不存在（404）只刷新列表，不弹错误 */
    fun loadCfToken(name: String) {
        scope.launch {
            try {
                val element = withContext(Dispatchers.IO) { api().getCfTunnel(name) }
                if (element is JsonObject) {
                    _state.value = _state.value.copy(
                        cfTokenText = element["token"]?.jsonPrimitive?.contentOrNull ?: "",
                        cfTokenName = name
                    )
                }
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 404) {
                    _state.value = _state.value.copy(cfTokenText = "", cfTokenName = name)
                    loadCfTunnels()
                } else {
                    _state.value = _state.value.copy(errorMessage = "读取隧道失败: ${e.message}")
                }
            }
        }
    }

    /** 新建/更新某条隧道的 token */
    fun saveCfTunnel(name: String, token: String) {
        scope.launch {
            try {
                val body = mapOf<String, Any>("token" to token)
                withContext(Dispatchers.IO) { api().saveCfTunnel(name, body) }
                _state.value = _state.value.copy(cfTokenText = token, cfTokenName = name)
                loadCfTunnels()
            } catch (e: Exception) {
                val msg = if (e is HttpException && e.code() == 400) {
                    "隧道名或 token 不可用（名称不能为空或含 \\ / : * ? \" < > | 等字符，token 不能为空）"
                } else {
                    "保存隧道失败: ${e.message}"
                }
                _state.value = _state.value.copy(errorMessage = msg)
            }
        }
    }

    /** 删除某条隧道（后端会先停掉正在运行的它）；隧道已不存在时后端 404 */
    fun deleteCfTunnel(name: String) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api().deleteCfTunnel(name) }
                val success = result.jsonObject["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!success) {
                    _state.value = _state.value.copy(
                        errorMessage = "删除隧道 [$name] 失败：它可能仍在运行且无法停止"
                    )
                }
                loadCfTunnels()
                loadStatus()
            } catch (e: Exception) {
                val msg = if (e is HttpException && e.code() == 404) "隧道 [$name] 已不存在" else "删除隧道失败: ${e.message}"
                _state.value = _state.value.copy(errorMessage = msg)
                loadCfTunnels()
            }
        }
    }

    /** 选中某条隧道（进入详情页时调用）；404 只刷新列表 */
    fun selectCfTunnel(name: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api().activateCfTunnel(name) }
                _state.value = _state.value.copy(cfActiveTunnel = name)
                loadCfToken(name)
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 404) {
                    loadCfTunnels()
                } else {
                    _state.value = _state.value.copy(errorMessage = "切换隧道失败: ${e.message}")
                }
            }
        }
    }

    /** 启动指定隧道（不影响其它隧道） */
    fun startCfTunnel(name: String) {
        scope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true, errorMessage = null)
                val result = withContext(Dispatchers.IO) { api().startCfTunnel(name) }
                val success = result.jsonObject["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!success) {
                    val message = result.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "启动失败"
                    _state.value = _state.value.copy(errorMessage = message)
                }
                loadStatus()
                loadCfTunnels()
                refreshCfLog(name)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, errorMessage = "启动 CF Tunnel 失败: ${e.message}")
            }
        }
    }

    /** 停止指定隧道 */
    fun stopCfTunnel(name: String) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api().stopCfTunnel(name) }
                val success = result.jsonObject["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!success) {
                    _state.value = _state.value.copy(
                        errorMessage = result.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "停止 CF Tunnel 失败"
                    )
                }
                loadStatus()
                loadCfTunnels()
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "停止 CF Tunnel 失败: ${e.message}")
            }
        }
    }

    /** 刷新某条隧道的运行日志（按名字写进 cfLogs）；[full] = 读运行期日志文件 */
    fun refreshCfLog(name: String, full: Boolean = false) {
        scope.launch {
            try {
                val element = withContext(Dispatchers.IO) {
                    api().getCfTunnelLog(name, if (full) 1 else 0)
                }
                if (element is JsonObject) {
                    val log = element["log"]?.jsonPrimitive?.contentOrNull ?: ""
                    _state.value = _state.value.copy(cfLogs = _state.value.cfLogs + (name to log))
                }
            } catch (_: Exception) {}
        }
    }

    /** 清空某条隧道的日志缓冲 */
    fun clearCfLog(name: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api().clearCfTunnelLog(name) }
                _state.value = _state.value.copy(cfLogs = _state.value.cfLogs + (name to ""))
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "清除日志失败: ${e.message}")
            }
        }
    }

    /** 停止全部隧道（两个引擎的所有实例） */
    fun stopAll() {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api().stopTunnel() }
                // 后端的 success 表示"全部实例都确认停下了"；强杀失败时必须让用户看到，
                // 否则会以为已经停了而进程还在跑
                val success = result.jsonObject["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!success) {
                    _state.value = _state.value.copy(
                        errorMessage = result.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "停止隧道失败"
                    )
                }
                loadStatus()
                loadConfigs()
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "停止隧道失败: ${e.message}")
            }
        }
    }

    // ── 可选二进制组件（2026-09-01：frpc / cloudflared 按需下载）──
    // 本地上传兜底只在 Web 面板提供（见 UfiAxisApi 注释），app 端只做下载/卸载/进度。

    /**
     * 拉取组件列表。
     * @param refresh true = 让 core 重新拉一次 version.json（会打网络，「检查更新」用）；
     *                false = 用 core 进程内缓存，适合进页面时的常规刷新
     */
    fun loadComponents(refresh: Boolean = false) {
        scope.launch {
            try {
                val element = withContext(Dispatchers.IO) {
                    api().listComponents(if (refresh) "true" else null)
                }
                val obj = element as? JsonObject ?: return@launch
                val items = obj["components"]?.jsonArray?.mapNotNull { parseComponent(it) } ?: emptyList()
                _state.value = _state.value.copy(
                    components = items,
                    componentTask = parseComponentTask(obj),
                    componentManifestError = obj["manifest_error"]?.jsonPrimitive?.contentOrNull ?: "",
                    // core 侧 refresh=false 时清单是后台拉的，这个标志告诉界面"还在拉，等会儿回读"
                    componentManifestLoading = obj["manifest_loading"]?.jsonPrimitive?.contentOrNull == "true"
                )
            } catch (e: Exception) {
                // 旧后端没有这组路由（404），静默即可 —— 隧道页其余部分照常可用
                if (!(e is HttpException && e.code() == 404)) {
                    _state.value = _state.value.copy(errorMessage = "加载组件列表失败: ${e.message}")
                }
            }
        }
    }

    /** 只刷新安装进度（任务进行中时高频轮询，避免每次都重算整张列表） */
    fun refreshComponentTask() {
        scope.launch {
            try {
                val obj = withContext(Dispatchers.IO) { api().getComponentStatus() } as? JsonObject ?: return@launch
                val task = parseComponentTask(obj)
                val was = _state.value.componentTask.active
                _state.value = _state.value.copy(
                    componentTask = task,
                    componentManifestError = obj["manifest_error"]?.jsonPrimitive?.contentOrNull
                        ?: _state.value.componentManifestError
                )
                // 任务刚落到终态：补拉列表与 /status，否则已装版本号不会变
                if (was && !task.active) {
                    loadComponents()
                    loadStatus()
                }
            } catch (_: Exception) { /* 轮询失败静默 */ }
        }
    }

    /** 触发下载安装（core 侧异步执行，进度经 [refreshComponentTask] 轮询） */
    fun installComponent(id: String) {
        scope.launch {
            try {
                val obj = withContext(Dispatchers.IO) { api().installComponent(id) } as? JsonObject
                if (obj != null) _state.value = _state.value.copy(componentTask = parseComponentTask(obj))
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "触发组件安装失败: ${errorBody(e)}")
            }
        }
    }

    /** 卸载组件；仍有实例在跑时 core 返回 409，错误文案直接透出 */
    fun uninstallComponent(id: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api().uninstallComponent(id) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(errorMessage = "卸载组件失败: ${errorBody(e)}")
            } finally {
                loadComponents()
                loadStatus()
            }
        }
    }

    private fun parseComponent(element: JsonElement): ComponentInfo? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return ComponentInfo(
            id = id,
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            installed = obj["installed"]?.jsonPrimitive?.booleanOrNull ?: false,
            installedVersion = obj["installed_version"]?.jsonPrimitive?.contentOrNull ?: "",
            installedSize = obj["installed_size"]?.jsonPrimitive?.longOrNull ?: 0L,
            source = obj["source"]?.jsonPrimitive?.contentOrNull ?: "",
            latestVersion = obj["latest_version"]?.jsonPrimitive?.contentOrNull ?: "",
            downloadSize = obj["download_size"]?.jsonPrimitive?.longOrNull ?: 0L,
            available = obj["available"]?.jsonPrimitive?.booleanOrNull ?: false,
            updateAvailable = obj["update_available"]?.jsonPrimitive?.booleanOrNull ?: false,
            upstream = obj["upstream"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    private fun parseComponentTask(obj: JsonObject) = ComponentTask(
        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
        state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "idle",
        percent = obj["percent"]?.jsonPrimitive?.intOrNull ?: 0,
        message = obj["message"]?.jsonPrimitive?.contentOrNull ?: ""
    )

    /** core 的错误体是 { error: 中文原因 }，直接取出来比 HTTP 码有用得多 */
    private fun errorBody(e: Exception): String {
        if (e is HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val msg = runCatching {
                (Json.parseToJsonElement(body ?: "") as? JsonObject)
                    ?.get("error")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (!msg.isNullOrBlank()) return msg
        }
        return e.message ?: "未知错误"
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun parseStatus(element: JsonObject) {
        val localPort = element["local_port"]?.jsonPrimitive?.intOrNull ?: 8088
        val frpObj = element["frp"]?.jsonObject
        val cfObj = element["cf_tunnel"]?.jsonObject
        val frpInstances = parseInstances(frpObj)
        val cfInstances = parseInstances(cfObj)

        _state.value = _state.value.copy(
            localPort = localPort,
            frpVersion = frpObj?.get("version")?.jsonPrimitive?.contentOrNull ?: "",
            frpInstances = frpInstances,
            cfVersion = cfObj?.get("version")?.jsonPrimitive?.contentOrNull ?: "",
            cfInstances = cfInstances,
            // 组件安装状态（2026-09-01 起 frpc/cloudflared 按需下载）。
            // 字段缺失（旧后端）时保持 true，否则老 core 会被显示成"未安装"。
            frpInstalled = frpObj?.get("installed")?.jsonPrimitive?.booleanOrNull ?: true,
            cfInstalled = cfObj?.get("installed")?.jsonPrimitive?.booleanOrNull ?: true,
            // /status 顺带回传这两项，设置页没打开过时状态也是对的
            tunnelAutoReconnect = element["auto_reconnect"]?.jsonPrimitive?.booleanOrNull
                ?: _state.value.tunnelAutoReconnect,
            tunnelNotifyOnFailure = element["notify_on_failure"]?.jsonPrimitive?.booleanOrNull
                ?: _state.value.tunnelNotifyOnFailure
        )
    }

    private fun parseInstances(obj: JsonObject?): List<TunnelInstanceInfo> =
        obj?.get("instances")?.jsonArray?.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            TunnelInstanceInfo(
                name = name,
                running = o["running"]?.jsonPrimitive?.booleanOrNull ?: false,
                status = o["status"]?.jsonPrimitive?.contentOrNull ?: "Stopped",
                lastError = o["last_error"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        } ?: emptyList()
}
