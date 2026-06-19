package com.ufi_axis.util

import android.content.Context
import com.google.gson.Gson
import com.ufi_axis.data.cache.CacheDatabase
import com.ufi_axis.data.cache.CachedDashboardEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class CachedDashboardData(
    val timestamp: Long,
    val cpuJson: String?,
    val memoryJson: String?,
    val trafficJson: String?,
    val signalJson: String?
)

class CacheManager(context: Context) {

    private val dao = CacheDatabase.getInstance(context).dashboardCacheDao()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

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

    fun saveDataAsync(data: CachedDashboardData) {
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
}
