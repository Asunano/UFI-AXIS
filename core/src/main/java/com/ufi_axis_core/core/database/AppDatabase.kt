package com.ufi_axis_core.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * Room 数据库
 * 存储路径: /data/ufiaxis/db/
 *
 * 数据表:
 * - traffic_records: 流量记录
 * - signal_history: 信号历史
 * - alert_records: 告警记录
 * - sms_records: 短信记录
 * - cpu_history: CPU 历史
 * - memory_history: 内存历史
 * - battery_history: 电池历史
 */
@Database(
    entities = [
        TrafficRecord::class,
        SignalRecord::class,
        AlertRecord::class,
        SmsRecord::class,
        CpuHistoryRecord::class,
        MemoryHistoryRecord::class,
        BatteryHistoryRecord::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trafficDao(): TrafficDao
    abstract fun signalDao(): SignalDao
    abstract fun alertDao(): AlertDao
    abstract fun smsDao(): SmsDao
    abstract fun cpuHistoryDao(): CpuHistoryDao
    abstract fun memoryHistoryDao(): MemoryHistoryDao
    abstract fun batteryHistoryDao(): BatteryHistoryDao

    /**
     * 将 4 个 buffer 的写入合并为 1 个事务，减少事务竞争。
     * 每 20 秒调用一次，4 个独立事务 → 1 个事务，WAL 模式写合并更高效。
     */
    @androidx.room.Transaction
    open suspend fun flushAllBuffers(
        cpu: List<CpuHistoryRecord>,
        mem: List<MemoryHistoryRecord>,
        traffic: List<TrafficRecord>,
        signal: List<SignalRecord>
    ) {
        if (cpu.isNotEmpty()) cpuHistoryDao().insertAll(cpu)
        if (mem.isNotEmpty()) memoryHistoryDao().insertAll(mem)
        if (traffic.isNotEmpty()) trafficDao().insertAll(traffic)
        if (signal.isNotEmpty()) signalDao().insertAll(signal)
    }

    companion object {
        private const val DB_NAME = "ufi_axis_core.db"
        @Volatile
        private var instance: AppDatabase? = null
        private var dbPath: String? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            // 优先使用 /data/ufiaxis/db (root)，回退到 app 私有目录
            val resolvedPath = try {
                val rootDir = File("/data/ufiaxis/db")
                if (rootDir.exists() && rootDir.canWrite()) {
                    File(rootDir, DB_NAME).absolutePath
                } else {
                    // 非 root 环境使用 app 私有目录
                    val appDbDir = File(context.filesDir, "ufiaxis/db")
                    appDbDir.mkdirs()
                    File(appDbDir, DB_NAME).absolutePath
                }
            } catch (e: Exception) {
                val appDbDir = File(context.filesDir, "ufiaxis/db")
                appDbDir.mkdirs()
                File(appDbDir, DB_NAME).absolutePath
            }

            dbPath = resolvedPath

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                resolvedPath
            )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_3_4)
                .build()
        }

        /**
         * v3→v4: 添加 timestamp 索引（提升时序查询性能）
         * 后续 schema 变更应在此处添加新 Migration，避免 fallbackToDestructiveMigration 丢数据
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_traffic_records_timestamp` ON `traffic_records` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_signal_history_timestamp` ON `signal_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cpu_history_timestamp` ON `cpu_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_history_timestamp` ON `memory_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_history_timestamp` ON `battery_history` (`timestamp`)")
            }
        }

        /**
         * 获取数据库总大小（近似值）
         */
        fun getDatabaseSize(): Long {
            val path = dbPath ?: return 0L
            val dbFile = File(path)
            return if (dbFile.exists()) dbFile.length() else 0L
        }
    }
}
