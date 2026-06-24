package com.ufi_axis.data.repository

import com.google.gson.JsonElement
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.*

/** 文件管理 · 应用管理 · 高级工具 · 监控中心 */
class FileAppRepository(private val api: UfiAxisApi) {
    // Root Check
    suspend fun checkRootAccess() = api.checkRootAccess()

    // File Management
    suspend fun listFiles(path: String, force: Boolean = false) = api.listFiles(path, force)
    suspend fun getFileInfo(path: String) = api.getFileInfo(path)
    suspend fun readFile(path: String) = api.readFile(mapOf("path" to path))
    suspend fun writeFile(path: String, content: String) = api.writeFile(mapOf("path" to path, "content" to content))
    suspend fun deleteFile(path: String) = api.deleteFile(mapOf("path" to path))
    suspend fun renameFile(oldPath: String, newPath: String) = api.renameFile(mapOf("old_path" to oldPath, "new_path" to newPath))
    suspend fun moveFile(source: String, destination: String) = api.moveFile(mapOf("source" to source, "destination" to destination))
    suspend fun copyFile(source: String, destination: String) = api.copyFile(mapOf("source" to source, "destination" to destination))
    suspend fun createDirectory(path: String) = api.createDirectory(mapOf("path" to path))
    suspend fun searchFiles(path: String, query: String, depth: Int = 3) = api.searchFiles(path, query, depth)
    suspend fun getDiskUsage(): JsonElement = api.getDiskUsage()
    suspend fun chmodFile(path: String, mode: String) = api.chmodFile(mapOf("path" to path, "mode" to mode))
    suspend fun touchFile(path: String) = api.touchFile(mapOf("path" to path))

    // App Management
    suspend fun getAppList(filter: String = "user") = api.getAppList(filter)
    suspend fun getAppDetail(pkg: String) = api.getAppDetail(pkg)
    suspend fun installApp(path: String) = api.installApp(AppInstallRequest(path))
    suspend fun installAppFromUrl(url: String) = api.installAppFromUrl(AppInstallUrlRequest(url))
    suspend fun uninstallApp(pkg: String) = api.uninstallApp(AppActionRequest(pkg))
    suspend fun disableApp(pkg: String) = api.disableApp(AppActionRequest(pkg))
    suspend fun enableApp(pkg: String) = api.enableApp(AppActionRequest(pkg))
    suspend fun clearAppData(pkg: String) = api.clearAppData(AppActionRequest(pkg))
    suspend fun forceStopApp(pkg: String) = api.forceStopApp(AppActionRequest(pkg))
    suspend fun managePermission(pkg: String, perm: String, grant: Boolean) = api.managePermission(AppPermissionRequest(pkg, perm, grant))
    suspend fun freezeApp(pkg: String) = api.freezeApp(AppActionRequest(pkg))
    suspend fun unfreezeApp(pkg: String) = api.unfreezeApp(AppActionRequest(pkg))

    // Advanced Tools
    suspend fun getTtydStatus() = api.getTtydStatus()
    suspend fun startTtyd() = api.startTtyd()
    suspend fun stopTtyd() = api.stopTtyd()
    suspend fun getIperf3Status() = api.getIperf3Status()
    suspend fun startIperf3() = api.startIperf3()
    suspend fun stopIperf3() = api.stopIperf3()
    suspend fun getFotaStatus() = api.getFotaStatus()
    suspend fun disableFotaAdvanced() = api.disableFotaAdvanced()
    suspend fun getCpuCores() = api.getCpuCores()
    suspend fun setCpuCores(enable: Boolean) = api.setCpuCores(mapOf("enable" to enable))
    suspend fun netAccelerate() = api.netAccelerate()
    suspend fun disablePhantomKiller() = api.disablePhantomKiller()
    suspend fun getBandwidthLimit() = api.getBandwidthLimit()
    suspend fun setBandwidthLimit(mbit: String) = api.setBandwidthLimit(mapOf("mbit" to mbit))
    suspend fun removeBandwidthLimit() = api.removeBandwidthLimit()
    suspend fun getCellularUsage(start: Long? = null, end: Long? = null) = api.getCellularUsage(start, end)

    // Monitor
    suspend fun getMonitorHistory(type: String, hours: Int, points: Int = 360) = api.getMonitorHistory(type, hours, points)
    suspend fun getMonitorStorage() = api.getMonitorStorage()
    suspend fun cleanHistory(type: String? = null, days: Int? = null) = api.cleanHistory(CleanHistoryRequest(type, days))
}
