package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.WeatherCity
import com.ufi_axis.data.model.WeatherConfigRequest
import com.ufi_axis.data.model.WeatherConfigResponse
import com.ufi_axis.data.model.WeatherNowResponse
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 标题栏天气（`/api/weather`，2026-09-17）。
 *
 * 职责只有取数与缓存节流；weather_code → 文案的翻译在 core，这里不重做一份。
 *
 * ## 为什么没有周期任务
 * 天气不需要"实时"，而常驻定时器在这台设备上是明确要避免的（后台唤醒只允许事件驱动）。
 * 所以刷新时机是**事件驱动**：进首页 Tab / 回前台 / 用户改了城市。客户端侧再加
 * [MIN_REFRESH_INTERVAL_MS] 节流，配合 core 的 15 分钟 TTL，切 Tab 来回点也不会打上游。
 */
data class WeatherState(
    val config: WeatherConfigResponse = WeatherConfigResponse(),
    val configLoaded: Boolean = false,
    val now: WeatherNowResponse? = null,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    /** 最近一次成功取到天气的时刻（节流判据）。 */
    val lastLoadedAt: Long = 0L
) {
    /** 标题栏该不该显示：开关开着、城市设过、也真拿到数据了。三者缺一都不显示。 */
    val displayable: Boolean
        get() = config.enabled && now?.configured == true
}

class WeatherModule(
    private val api: UfiAxisApi,
    private val scope: CoroutineScope,
    /**
     * 跨模块 UI 事件出口（2026-09-22，阶段 3.5）：写成功提示直投这里，由
     * `MainViewModel.writeNotice` 落到 Activity 级 Toast 宿主。
     *
     * 本模块没有 `_events`，也不需要 —— sink 是构造参数，直接投比再加一层转发协程干净。
     */
    private val crossModuleEventSink: MutableSharedFlow<UiEvent>
) {
    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    /** 读配置。设置页进入、以及 app 启动时各一次。 */
    fun loadConfig() {
        scope.launch {
            try {
                val cfg = api.getWeatherConfig()
                _state.update { it.copy(config = cfg, configLoaded = true, errorMessage = null) }
                // 开着就顺手取一次数据，否则标题栏会空一拍
                if (cfg.enabled) refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "读取天气配置失败: ${e.message}")
                _state.update { it.copy(configLoaded = true, errorMessage = "读取天气配置失败: ${e.message}") }
            }
        }
    }

    /**
     * 取当前天气。
     *
     * @param force 忽略客户端节流（用户手动点刷新、或刚改完城市）。core 侧的 TTL 仍然生效，
     *   所以 force 也不会真的每次都打上游 —— 它只是让本地这层不拦。
     */
    fun refresh(force: Boolean = false) {
        val s = _state.value
        if (!force && s.now != null &&
            System.currentTimeMillis() - s.lastLoadedAt < MIN_REFRESH_INTERVAL_MS
        ) return
        if (s.loading) return

        scope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val now = api.getWeatherNow()
                _state.update {
                    it.copy(
                        now = now,
                        loading = false,
                        // configured=false 不是错误（还没设城市），不要写 errorMessage
                        errorMessage = null,
                        lastLoadedAt = if (now.configured) System.currentTimeMillis() else it.lastLoadedAt
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "读取天气失败: ${e.message}")
                // 失败保留上一次的数据：标题栏上一格旧温度比空白有用
                _state.update { it.copy(loading = false, errorMessage = "读取天气失败: ${e.message}") }
            }
        }
    }

    /** 回前台 / 进首页 Tab 的刷新入口（走节流）。 */
    fun onAppForegrounded() {
        if (_state.value.config.enabled) refresh()
    }

    /** 城市搜索。返回空列表既可能是没搜到、也可能是请求失败，失败原因写进 state。 */
    suspend fun searchCity(name: String): List<WeatherCity> {
        val q = name.trim()
        if (q.isEmpty()) return emptyList()
        return try {
            api.searchWeatherCity(q).results
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w(TAG, "城市搜索失败: ${e.message}")
            _state.update { it.copy(errorMessage = "城市搜索失败: ${e.message}") }
            emptyList()
        }
    }

    /** 开关。关掉时不清城市 —— 再打开还是原来那个城市。 */
    fun setEnabled(enabled: Boolean) = patch(
        WeatherConfigRequest(enabled = enabled),
        refreshAfter = enabled,
        successNotice = if (enabled) "已开启标题栏天气" else "已关闭标题栏天气"
    )

    /** 选定城市：名字与坐标一起写，不允许只改一半。 */
    fun setCity(city: WeatherCity) = patch(
        WeatherConfigRequest(
            city = displayName(city),
            latitude = city.latitude,
            longitude = city.longitude
        ),
        refreshAfter = true,
        successNotice = "城市已设为「${displayName(city)}」"
    )

    fun setUnit(unit: String) = patch(
        WeatherConfigRequest(unit = unit),
        refreshAfter = true,
        successNotice = if (unit == "fahrenheit") "温度单位已改为华氏度" else "温度单位已改为摄氏度"
    )

    /**
     * 2026-09-22（阶段 3.5）：补成功提示。
     *
     * 失败路径不动 —— 它写 `state.errorMessage`，设置页底部有一张红字卡在渲染它。
     * 成功以前是静默的：真源在 core，本地 `config` 是用回包覆盖的，所以"开关拨过去了"
     * 只代表界面动了，不代表 core 收下了（连不上时开关会自己弹回来，且没有任何解释）。
     */
    private fun patch(body: WeatherConfigRequest, refreshAfter: Boolean, successNotice: String) {
        scope.launch {
            try {
                val resp = api.updateWeatherConfig(body)
                _state.update { it.copy(config = resp.config, errorMessage = null) }
                crossModuleEventSink.tryEmit(UiEvent.ShowWriteNotice(successNotice))
                if (refreshAfter) refresh(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "保存天气配置失败: ${e.message}")
                _state.update { it.copy(errorMessage = "保存天气配置失败: ${e.message}") }
            }
        }
    }

    companion object {
        private const val TAG = "WeatherModule"

        /**
         * 客户端节流间隔。比 core 的 TTL（15 分钟）短一些：core 命中缓存的请求很便宜，
         * 让本地稍微勤一点可以在 TTL 刚过期时更快拿到新值。
         */
        const val MIN_REFRESH_INTERVAL_MS = 600_000L

        /** 搜索结果的展示名：`朝阳 · 北京市 · 中国`，用来区分同名城市。 */
        fun displayName(city: WeatherCity): String =
            listOf(city.name, city.admin1, city.country)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" · ")
    }
}
