package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.DlnaConfigRequest
import com.ufi_axis.data.model.DlnaStatusResponse
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * DLNA MediaServer（`/api/dlna`，2026-10-09）。
 *
 * 只负责取数与下发，与 [PoetryModule] 同构。业务规则在 core：
 *  · 目录未配置（`ready == false`）时 `enabled=true` 会被拒 —— UI 层据此**置灰开关**，
 *    别等 core 报错才提示；
 *  · 改目录后 core 会自动重启 MediaServer（stop→start，SSDP byebye→alive），无需额外动作。
 */
data class DlnaState(
    val status: DlnaStatusResponse = DlnaStatusResponse(),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val errorMessage: String? = null
)

class DlnaModule(
    private val api: UfiAxisApi,
    private val scope: CoroutineScope,
    private val crossModuleEventSink: MutableSharedFlow<UiEvent>
) {
    private val _state = MutableStateFlow(DlnaState())
    val state: StateFlow<DlnaState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            try {
                val s = api.getDlnaStatus()
                _state.update { it.copy(status = s, loaded = true, errorMessage = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "读取 DLNA 状态失败: ${e.message}")
                _state.update { it.copy(loaded = true, errorMessage = "读取状态失败: ${e.message}") }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = patch(DlnaConfigRequest(enabled = enabled))

    fun setDirs(dirs: List<String>) = patch(DlnaConfigRequest(dirs = dirs))

    private fun patch(body: DlnaConfigRequest) {
        if (_state.value.saving) return
        scope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val s = api.putDlnaConfig(body)
                _state.update { it.copy(status = s, saving = false, errorMessage = null) }
                crossModuleEventSink.tryEmit(UiEvent.ShowWriteNotice("已保存"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "保存 DLNA 配置失败: ${e.message}")
                _state.update { it.copy(saving = false, errorMessage = "保存失败: ${e.message}") }
            }
        }
    }

    companion object {
        private const val TAG = "DlnaModule"
    }
}
