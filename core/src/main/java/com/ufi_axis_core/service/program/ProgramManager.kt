package com.ufi_axis_core.service.program

import com.ufi_axis_core.api.program.ProgramCatalogUpdater
import com.ufi_axis_core.api.routes.ProgramRoutes
import com.ufi_axis_core.controller.program.ProgramRuntime
import com.ufi_axis_core.controller.program.ProgramSupervisor
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.program.ProgramPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 第三方程序功能的生命周期入口。
 *
 * 以前 runtime / supervisor / routes 是 ComponentFactory 里的局部变量，服务停止时
 * 没有任何人能把它们收掉：程序继续跑、看护继续拉起、安装协程继续下载，页面又因为
 * “服务已停止”不可见。这里把停机/暂停入口收成一个对象，挂进 ComponentGraph。
 */
class ProgramManager(
    private val routes: ProgramRoutes,
    private val supervisor: ProgramSupervisor,
    private val runtime: ProgramRuntime,
    private val paths: ProgramPaths,
    private val catalogUpdater: ProgramCatalogUpdater
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    fun start() {
        supervisor.restoreAutostart()
        supervisor.startGuard()
        if (refreshJob?.isActive == true) return
        // 首轮延迟避开 core 启动风暴；之后 6h 一次。失败保留内置清单，不阻塞任何 UI。
        refreshJob = scope.launch {
            delay(15_000)
            while (isActive) {
                catalogUpdater.refresh()
                    .onSuccess { AppLogger.i(TAG, "远端程序清单已刷新：${it.total} 条（${it.source}）") }
                    .onFailure { AppLogger.w(TAG, "远端程序清单刷新失败（保留内置）：${it.message}") }
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    /** 暂停后台活动：看护停、**所有**实例发 SIGTERM；desired 保留，恢复时按原意图拉起。 */
    fun pause() {
        supervisor.stopGuard()
        runtime.stopNow("后台服务停止")
    }

    fun resume() {
        supervisor.startGuard()
    }

    /**
     * 服务销毁：取消安装协程、停看护、优雅停所有程序、取消运行时协程、清临时下载区。
     */
    suspend fun shutdown() {
        supervisor.stopGuard()
        routes.close()
        // 服务销毁要把**所有**实例停掉：多实例之后"当前那一个"不再成立
        runCatching { runtime.stopAll("服务停止", 3) }
            .onFailure { AppLogger.w(TAG, "停机停止第三方程序失败: ${it.message}") }
        refreshJob?.cancel()
        refreshJob = null
        runtime.shutdown()
        scope.cancel()
        paths.cleanupTemp()
        AppLogger.i(TAG, "第三方程序功能已停机")
    }

    private companion object {
        const val TAG = "ProgramManager"
    }
}
