package com.ufi_axis.viewmodel.module

import android.content.Context
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.AppActionRequest
import com.ufi_axis.data.model.AppInstallRequest
import com.ufi_axis.data.model.AppInstallUrlRequest
import com.ufi_axis.viewmodel.state.AppManageState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AppManagerModule(
    private val api: UfiAxisApi,
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(AppManageState())
    val state: StateFlow<AppManageState> = _state.asStateFlow()

    fun loadAppList(filter: String = "user") {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, filter = filter, errorMessage = null)
            try {
                val resp = api.getAppList(filter)
                _state.value = _state.value.copy(apps = resp.apps.sortedByDescending { it.isFrozen || !it.isEnabled }, hasRoot = resp.root, isLoading = false)
            } catch (e: Exception) { _state.value = _state.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun loadAppDetail(packageName: String) {
        scope.launch {
            try { _state.value = _state.value.copy(selectedApp = api.getAppDetail(packageName)) }
            catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "获取详情失败: ${e.message}") }
        }
    }

    fun dismissAppDetail() { _state.value = _state.value.copy(selectedApp = null) }

    fun performAppAction(action: String, packageName: String) {
        scope.launch {
            try {
                when (action) {
                    "uninstall" -> api.uninstallApp(AppActionRequest(packageName))
                    "disable" -> api.disableApp(AppActionRequest(packageName))
                    "enable" -> api.enableApp(AppActionRequest(packageName))
                    "clear" -> api.clearAppData(AppActionRequest(packageName))
                    "force-stop" -> api.forceStopApp(AppActionRequest(packageName))
                    "freeze" -> api.freezeApp(AppActionRequest(packageName))
                    "unfreeze" -> api.unfreezeApp(AppActionRequest(packageName))
                }
                _state.value = _state.value.copy(selectedApp = null, errorMessage = null)
                loadAppList(_state.value.filter)
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "操作失败: ${e.message}") }
        }
    }

    fun installAppFromUrl(url: String) {
        scope.launch {
            _state.value = _state.value.copy(installLoading = true)
            try {
                val resp = api.installAppFromUrl(AppInstallUrlRequest(url))
                _state.value = _state.value.copy(installLoading = false, errorMessage = if (!resp.success) resp.message else null)
                if (resp.success) loadAppList(_state.value.filter)
            } catch (e: Exception) { _state.value = _state.value.copy(installLoading = false, errorMessage = "安装失败: ${e.message}") }
        }
    }

    fun installAppFromPath(path: String) {
        scope.launch {
            _state.value = _state.value.copy(installLoading = true)
            try {
                val resp = api.installApp(AppInstallRequest(path))
                _state.value = _state.value.copy(installLoading = false, errorMessage = if (!resp.success) resp.message else null)
                if (resp.success) loadAppList(_state.value.filter)
            } catch (e: Exception) { _state.value = _state.value.copy(installLoading = false, errorMessage = "安装失败: ${e.message}") }
        }
    }
}