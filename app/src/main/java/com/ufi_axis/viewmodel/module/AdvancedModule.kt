package com.ufi_axis.viewmodel.module

import android.content.Context
import com.ufi_axis.data.repository.FileAppRepository
import com.ufi_axis.viewmodel.state.AdvancedState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AdvancedModule(
    private val repo: FileAppRepository,
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(AdvancedState())
    val state: StateFlow<AdvancedState> = _state.asStateFlow()

    fun loadAdvancedStatus() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            try {
                val ttyd = runCatching { repo.getTtydStatus() }
                val iperf3 = runCatching { repo.getIperf3Status() }
                _state.value = _state.value.copy(
                    ttydRunning = ttyd.getOrNull()?.asJsonObject?.get("running")?.asBoolean ?: false,
                    iperf3Running = iperf3.getOrNull()?.asJsonObject?.get("running")?.asBoolean ?: false,
                    isLoading = false
                )
            } catch (e: Exception) { _state.value = _state.value.copy(isLoading = false, errorMessage = "状态加载失败: ${e.message}") }
        }
    }

    fun toggleTtyd(start: Boolean) {
        scope.launch {
            try {
                if (start) repo.startTtyd() else repo.stopTtyd()
                delay(1000); loadAdvancedStatus()
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "TTYD操作失败: ${e.message}") }
        }
    }

    fun toggleIperf3(start: Boolean) {
        scope.launch {
            try {
                if (start) repo.startIperf3() else repo.stopIperf3()
                delay(500); loadAdvancedStatus()
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "iperf3操作失败: ${e.message}") }
        }
    }

    fun loadCpuCores() {
        scope.launch {
            try { _state.value = _state.value.copy(cpuCores = repo.getCpuCores()) }
            catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "CPU核心查询失败: ${e.message}") }
        }
    }

    fun setCpuCores(enable: Boolean) {
        scope.launch {
            try {
                repo.setCpuCores(enable); delay(800)
                val cores = runCatching { repo.getCpuCores() }.getOrNull()
                _state.value = _state.value.copy(cpuCores = cores ?: _state.value.cpuCores, operationMessage = if (enable) "小核已开启" else "小核已关闭")
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "CPU核心设置失败: ${e.message}") }
        }
    }

    fun loadFotaStatus() {
        scope.launch {
            try { _state.value = _state.value.copy(fotaStatus = repo.getFotaStatus()) }
            catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "FOTA状态查询失败: ${e.message}") }
        }
    }

    fun disableFota() {
        scope.launch {
            try {
                repo.disableFotaAdvanced(); delay(500); loadFotaStatus()
                _state.value = _state.value.copy(operationMessage = "FOTA已禁用")
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "FOTA禁用失败: ${e.message}") }
        }
    }

    fun netAccelerate() {
        scope.launch {
            try { repo.netAccelerate(); _state.value = _state.value.copy(operationMessage = "网络加速已执行") }
            catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "网络加速失败: ${e.message}") }
        }
    }

    fun disablePhantomKiller() {
        scope.launch {
            try { repo.disablePhantomKiller(); _state.value = _state.value.copy(operationMessage = "Phantom Killer已禁用") }
            catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "操作失败: ${e.message}") }
        }
    }

    // ── 带宽限制 ──
    fun loadBandwidthLimit() {
        scope.launch {
            try {
                val resp = repo.getBandwidthLimit()
                val obj = resp.asJsonObject
                _state.value = _state.value.copy(
                    bandwidthEnabled = obj.get("enabled")?.asBoolean ?: false,
                    bandwidthMbit = obj.get("mbit")?.asInt ?: 0
                )
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "带宽查询失败: ${e.message}") }
        }
    }

    fun setBandwidthLimit(mbit: String) {
        scope.launch {
            try {
                repo.setBandwidthLimit(mbit)
                delay(500)
                loadBandwidthLimit()
                _state.value = _state.value.copy(operationMessage = "带宽限制已设置为 ${mbit}Mbit")
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "设置失败: ${e.message}") }
        }
    }

    fun removeBandwidthLimit() {
        scope.launch {
            try {
                repo.removeBandwidthLimit()
                delay(300)
                loadBandwidthLimit()
                _state.value = _state.value.copy(operationMessage = "带宽限制已解除")
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "解除失败: ${e.message}") }
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(errorMessage = null, operationMessage = null) }
}
