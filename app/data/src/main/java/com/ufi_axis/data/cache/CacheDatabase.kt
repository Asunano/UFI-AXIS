package com.ufi_axis.data.cache

import androidx.room.*
import android.content.Context

@Entity(tableName = "dashboard_cache")
data class CachedDashboardEntity(
    @PrimaryKey val id: Int = 1,
    val timestamp: Long,
    val cpuJson: String?,
    val memoryJson: String?,
    val trafficJson: String?,
    val signalJson: String?,
    val deviceInfoJson: String? = null,
    val batteryInfoJson: String? = null,
    val storageInfoJson: String? = null,
    val uptimeInfoJson: String? = null
)

@Dao
interface DashboardCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CachedDashboardEntity)

    @Query("SELECT * FROM dashboard_cache WHERE id = 1")
    suspend fun get(): CachedDashboardEntity?

    @Query("DELETE FROM dashboard_cache")
    suspend fun clear()
}

@Database(
    entities = [CachedDashboardEntity::class],
    version = 2,
    exportSchema = false
)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun dashboardCacheDao(): DashboardCacheDao

    companion object {
        @Volatile
        private var instance: CacheDatabase? = null

        fun getInstance(context: Context): CacheDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CacheDatabase::class.java,
                    "ufi_axis_cache.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
        }
    }
}
