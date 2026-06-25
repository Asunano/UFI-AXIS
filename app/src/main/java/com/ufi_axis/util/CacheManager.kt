package com.ufi_axis.util

import android.content.Context
import com.ufi_axis.data.cache.CacheDatabase
import com.ufi_axis.data.cache.CachedDashboardEntity
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

data class CachedDashboardData(
    val timestamp: Long,
    val cpuJson: String?,
    val memoryJson: String?,
    val trafficJson: String?,
    val signalJson: String?
)

class CacheManager(context: Context) {

    private val dao = CacheDatabase.getInstance(context).dashboardCacheDao()
    // 使用 SupervisorJob + Dispatchers.IO，支持外部取消
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val isShutdown = AtomicBoolean(false)

    suspend fun saveData(data: CachedDashboardData) {
        val entity = CachedDashboardEntity(
            timestamp = data.timestamp,
            cpuJson = data.cpuJson,
            memoryJson = data.memoryJson,
            trafficJson = data.trafficJson,
            signalJson = data.signalJson
        )
        dao.insert(entity)
    }

    /** 异步保存，如果已 shutdown 则跳过 */
    fun saveDataAsync(data: CachedDashboardData) {
        if (isShutdown.get()) return
        scope.launch { saveData(data) }
    }

    suspend fun getLatestData(): CachedDashboardData? {
        val entity = dao.get() ?: return null
        return CachedDashboardData(
            timestamp = entity.timestamp,
            cpuJson = entity.cpuJson,
            memoryJson = entity.memoryJson,
            trafficJson = entity.trafficJson,
            signalJson = entity.signalJson
        )
    }

    suspend fun clearCache() {
        dao.clear()
    }

    /** 取消所有待处理和进行中的保存任务 */
    fun shutdown() {
        isShutdown.set(true)
        job.cancel()
    }
}
