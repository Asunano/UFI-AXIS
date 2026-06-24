package com.ufi_axis.viewmodel.module

import android.content.Context
import com.ufi_axis.data.repository.FileAppRepository
import com.ufi_axis.viewmodel.state.AppManageState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AppManagerModule(
    private val repo: FileAppRepository,
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(AppManageState())
    val state: StateFlow<AppManageState> = _state.asStateFlow()

    fun loadAppList(filter: String = "user") {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, filter = filter, errorMessage = null)
            try {
                val resp = repo.getAppList(filter)
                _state.value = _state.value.copy(apps = resp.apps.sortedByDescending { it.isFrozen || !it.isEnabled }, hasRoot = resp.root, isLoading = false)
            } catch (e: Exception) { _state.value = _state.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun loadAppDetail(packageName: String) {
        scope.launch {
            try { _state.value = _state.value.copy(selectedApp = repo.getAppDetail(packageName)) }
            catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "获取详情失败: ${e.message}") }
        }
    }

    fun dismissAppDetail() { _state.value = _state.value.copy(selectedApp = null) }

    fun performAppAction(action: String, packageName: String) {
        scope.launch {
            try {
                when (action) {
                    "uninstall" -> repo.uninstallApp(packageName)
                    "disable" -> repo.disableApp(packageName)
                    "enable" -> repo.enableApp(packageName)
                    "clear" -> repo.clearAppData(packageName)
                    "force-stop" -> repo.forceStopApp(packageName)
                    "freeze" -> repo.freezeApp(packageName)
                    "unfreeze" -> repo.unfreezeApp(packageName)
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
                val resp = repo.installAppFromUrl(url)
                _state.value = _state.value.copy(installLoading = false, errorMessage = if (!resp.success) resp.message else null)
                if (resp.success) loadAppList(_state.value.filter)
            } catch (e: Exception) { _state.value = _state.value.copy(installLoading = false, errorMessage = "安装失败: ${e.message}") }
        }
    }

    fun installAppFromPath(path: String) {
        scope.launch {
            _state.value = _state.value.copy(installLoading = true)
            try {
                val resp = repo.installApp(path)
                _state.value = _state.value.copy(installLoading = false, errorMessage = if (!resp.success) resp.message else null)
                if (resp.success) loadAppList(_state.value.filter)
            } catch (e: Exception) { _state.value = _state.value.copy(installLoading = false, errorMessage = "安装失败: ${e.message}") }
        }
    }
}
