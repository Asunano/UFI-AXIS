package com.ufi_axis_core.core.database

import android.content.Context
import androidx.annotation.VisibleForTesting
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
 * - sms_read_state: 短信已读状态
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
        SmsReadState::class,
        SmsVerificationCode::class,
        CpuHistoryRecord::class,
        MemoryHistoryRecord::class,
        BatteryHistoryRecord::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trafficDao(): TrafficDao
    abstract fun signalDao(): SignalDao
    abstract fun alertDao(): AlertDao
    abstract fun smsDao(): SmsDao
    abstract fun smsReadStateDao(): SmsReadStateDao
    abstract fun smsVerificationCodeDao(): SmsVerificationCodeDao
    abstract fun cpuHistoryDao(): CpuHistoryDao
    abstract fun memoryHistoryDao(): MemoryHistoryDao
    abstract fun batteryHistoryDao(): BatteryHistoryDao

    /**
     * 将 5 个 buffer 的写入合并为 1 个事务，减少事务竞争。
     * 每 20 秒调用一次，5 个独立事务 → 1 个事务，WAL 模式写合并更高效。
     */
    @androidx.room.Transaction
    open suspend fun flushAllBuffers(
        cpu: List<CpuHistoryRecord>,
        mem: List<MemoryHistoryRecord>,
        traffic: List<TrafficRecord>,
        signal: List<SignalRecord>,
        battery: List<BatteryHistoryRecord>
    ) {
        if (cpu.isNotEmpty()) cpuHistoryDao().insertAll(cpu)
        if (mem.isNotEmpty()) memoryHistoryDao().insertAll(mem)
        if (traffic.isNotEmpty()) trafficDao().insertAll(traffic)
        if (signal.isNotEmpty()) signalDao().insertAll(signal)
        if (battery.isNotEmpty()) batteryHistoryDao().insertAll(battery)
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
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
        }

        /**
         * v3→v4: 添加 timestamp 索引（提升时序查询性能）
         * 后续 schema 变更应在此处添加新 Migration，避免 fallbackToDestructiveMigration 丢数据
         */
        @VisibleForTesting
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_traffic_records_timestamp` ON `traffic_records` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_signal_history_timestamp` ON `signal_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cpu_history_timestamp` ON `cpu_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_history_timestamp` ON `memory_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_history_timestamp` ON `battery_history` (`timestamp`)")
            }
        }

        /**
         * v4→v5: 新增 sms_read_state 表（短信已读状态本地管理）
         * 独立于设备 goform tag 字段，由软件自主管理已读状态。
         */
        @VisibleForTesting
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_read_state` (
                        `msg_id` INTEGER NOT NULL,
                        `read` INTEGER NOT NULL DEFAULT 1,
                        `first_seen` INTEGER NOT NULL,
                        `phone` TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(`msg_id`)
                    )
                """.trimIndent())
            }
        }

        /**
         * v5→v6: 新增 sms_verification_codes 表（验证码解析缓存）
         * DataScheduler 实时扫描新短信提取验证码，结果持久化到此表。
         */
        @VisibleForTesting
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_verification_codes` (
                        `msg_id` INTEGER NOT NULL,
                        `code` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `snippet` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `keyword` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`msg_id`)
                    )
                """.trimIndent())
            }
        }

        /**
         * v6→v7: 告警去重改造
         * - alert_records 新增 count / firstSeenAt / resolvedAt 三列（均带默认值，旧数据无损）
         * - firstSeenAt 回填为原 timestamp（保留最旧出现时间）
         * - 补建 timestamp / acknowledged / (type,level) 三索引，支撑游标分页与聚合查询
         */
        @VisibleForTesting
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alert_records` ADD COLUMN `count` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `alert_records` ADD COLUMN `firstSeenAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `alert_records` ADD COLUMN `resolvedAt` INTEGER")
                db.execSQL("UPDATE `alert_records` SET `firstSeenAt` = `timestamp` WHERE `firstSeenAt` = 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_records_timestamp` ON `alert_records` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_records_acknowledged` ON `alert_records` (`acknowledged`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_records_type_level` ON `alert_records` (`type`, `level`)")
            }
        }

        /**
         * v7→v8: sms_verification_codes 新增 body 列（原短信全文）
         *
         * snippet 一直是 80 字截断预览，详情页要看全文只能再回查一次 /api/sms/list ——
         * 多一次请求，而且弹窗先渲染 snippet 再被全文替换，内容会闪一下。
         * 现在入库时同时存全文；旧数据 body 为空串，读取方回退 snippet（不做回填：
         * 原短信可能已被删除，回填反而会写出不一致的数据）。
         */
        @VisibleForTesting
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sms_verification_codes` ADD COLUMN `body` TEXT NOT NULL DEFAULT ''")
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
