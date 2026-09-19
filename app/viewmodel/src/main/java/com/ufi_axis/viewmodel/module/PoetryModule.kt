package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.PoetryConfigRequest
import com.ufi_axis.data.model.PoetryConfigResponse
import com.ufi_axis.data.model.PoetryResponse
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 今日诗词（`/api/poetry`，2026-09-18）。
 *
 * 与 [WeatherModule] 同构：只负责取数与节流，**不做任何"挑诗"的逻辑** ——
 * 季节/天气/时辰/地理的匹配由上游按设备 IP 自动完成（见 core 的 `PoetryRoutes`）。
 *
 * 刷新时机是事件驱动的：进首页 Tab、回前台、用户手动点刷新。没有常驻定时器。
 */
data class PoetryState(
    val config: PoetryConfigResponse = PoetryConfigResponse(),
    val configLoaded: Boolean = false,
    val poem: PoetryResponse? = null,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    /** 最近一次成功取到诗句的时刻（节流判据）。 */
    val lastLoadedAt: Long = 0L
) {
    /** 标题栏下方该不该显示：开关开着、且真拿到了诗句。 */
    val displayable: Boolean
        get() = config.enabled && !poem?.content.isNullOrBlank()
}

class PoetryModule(
    private val api: UfiAxisApi,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(PoetryState())
    val state: StateFlow<PoetryState> = _state.asStateFlow()

    fun loadConfig() {
        scope.launch {
            try {
                val cfg = api.getPoetryConfig()
                _state.update { it.copy(config = cfg, configLoaded = true, errorMessage = null) }
                if (cfg.enabled) refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "读取诗词配置失败: ${e.message}")
                _state.update {
                    it.copy(configLoaded = true, errorMessage = "读取诗词配置失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 取一句诗。
     *
     * @param force 用户手动刷新。会给 core 传 `refresh=1` 绕过它的本地缓存 ——
     *   但上游对同一 token 有约 10 分钟的预生成缓存，所以**短时间内很可能还是同一句**。
     *   这是上游机制，不是失败，界面上不要提示"刷新失败"。
     */
    fun refresh(force: Boolean = false) {
        val s = _state.value
        if (!force && s.poem != null &&
            System.currentTimeMillis() - s.lastLoadedAt < MIN_REFRESH_INTERVAL_MS
        ) return
        if (s.loading) return

        scope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val poem = api.getPoetry(refresh = if (force) "1" else null)
                _state.update {
                    it.copy(
                        poem = poem,
                        loading = false,
                        errorMessage = null,
                        lastLoadedAt = System.currentTimeMillis()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "读取诗词失败: ${e.message}")
                // 失败保留上一句：标题栏留着旧诗句比空白好
                _state.update { it.copy(loading = false, errorMessage = "读取诗词失败: ${e.message}") }
            }
        }
    }

    /** 回前台 / 进首页 Tab 的刷新入口（走节流）。 */
    fun onAppForegrounded() {
        if (_state.value.config.enabled) refresh()
    }

    fun setEnabled(enabled: Boolean) =
        patch(PoetryConfigRequest(enabled = enabled), refreshAfter = enabled)

    fun setShowOrigin(show: Boolean) =
        patch(PoetryConfigRequest(show_origin = show), refreshAfter = false)

    private fun patch(body: PoetryConfigRequest, refreshAfter: Boolean) {
        scope.launch {
            try {
                val resp = api.updatePoetryConfig(body)
                _state.update { it.copy(config = resp.config, errorMessage = null) }
                if (refreshAfter) refresh(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "保存诗词配置失败: ${e.message}")
                _state.update { it.copy(errorMessage = "保存诗词配置失败: ${e.message}") }
            }
        }
    }

    companion object {
        private const val TAG = "PoetryModule"

        /**
         * 客户端节流间隔，对齐上游的缓存周期（10 分钟）。
         *
         * 比它更勤没有意义 —— 上游在这个窗口内返回的是同一句。
         */
        const val MIN_REFRESH_INTERVAL_MS = 600_000L
    }
}
