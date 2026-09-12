package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后端（core）健康检查。
 *
 * 两套用途：
 * 1. [startPeriodicCheck] 周期访问 core 的 `/health` 端点，维护 [healthState]（当前是否可达）；
 * 2. [checkHealthNow] 供「数据加载出错后」即时复查后端是否还活着 —— 若探活也失败，基本可判定
 *    后端服务挂了 / 地址不可达，UI 据此弹「后端掉线」提示而不是笼统的「加载失败」。
 *
 * `/health` 免鉴权、零负载（HttpServer.kt 直接回 status=timestamp），可作为廉价的"后端是否在线"探针。
 */
enum class HealthStatus { UNKNOWN, HEALTHY, UNREACHABLE }

data class HealthState(
    val status: HealthStatus = HealthStatus.UNKNOWN,
    val lastCheckedAt: Long = 0L,
    val errorMessage: String? = null
)

class HealthModule(
    private val api: UfiAxisApi,
    private val scope: CoroutineScope
) {
    private val _healthState = MutableStateFlow(HealthState())
    val healthState: StateFlow<HealthState> = _healthState.asStateFlow()

    /**
     * 单次健康检查：成功回 true（status=HEALTHY），失败回 false（status=UNREACHABLE 并记录错误）。
     * 调用方可用返回值 + [healthState].errorMessage 决定如何提示用户。
     */
    suspend fun checkHealthNow(): Boolean {
        return try {
            api.getHealth()
            _healthState.value = HealthState(
                status = HealthStatus.HEALTHY,
                lastCheckedAt = System.currentTimeMillis(),
                errorMessage = null
            )
            true
        } catch (e: Exception) {
            DebugLog.w("HealthModule", "health probe failed: ${e.message}")
            _healthState.value = HealthState(
                status = HealthStatus.UNREACHABLE,
                lastCheckedAt = System.currentTimeMillis(),
                errorMessage = e.message ?: "无法连接到后端服务"
            )
            false
        }
    }

    /** 周期健康检查：每 [intervalMs] 探活一次，直到 [scope] 取消。 */
    fun startPeriodicCheck(intervalMs: Long) {
        scope.launch {
            while (isActive) {
                checkHealthNow()
                delay(intervalMs)
            }
        }
    }
}
