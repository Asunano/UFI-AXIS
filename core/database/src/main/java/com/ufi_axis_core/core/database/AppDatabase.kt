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
 * - sms_rule: 短信拦截规则（号码黑名单 + 关键词）
 * - sms_blocked_log: 短信拦截记录
 * - cpu_history: CPU 历史
 * - memory_history: 内存历史
 * - battery_history: 电池历史
 * - mail_send_records: 邮件投递记录
 * - console_history: 终端命令历史（AT / Shell，两端共享）
 */
@Database(
    entities = [
        TrafficRecord::class,
        SignalRecord::class,
        AlertRecord::class,
        SmsRecord::class,
        SmsReadState::class,
        SmsVerificationCode::class,
        SmsRule::class,
        SmsBlockedLog::class,
        CpuHistoryRecord::class,
        MemoryHistoryRecord::class,
        BatteryHistoryRecord::class,
        MailSendRecord::class,
        ConsoleHistoryRecord::class
    ],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trafficDao(): TrafficDao
    abstract fun signalDao(): SignalDao
    abstract fun alertDao(): AlertDao
    abstract fun smsDao(): SmsDao
    abstract fun smsReadStateDao(): SmsReadStateDao
    abstract fun smsVerificationCodeDao(): SmsVerificationCodeDao
    abstract fun smsRuleDao(): SmsRuleDao
    abstract fun smsBlockedLogDao(): SmsBlockedLogDao
    abstract fun cpuHistoryDao(): CpuHistoryDao
    abstract fun memoryHistoryDao(): MemoryHistoryDao
    abstract fun batteryHistoryDao(): BatteryHistoryDao
    abstract fun mailSendRecordDao(): MailSendRecordDao
    abstract fun consoleHistoryDao(): ConsoleHistoryDao

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
                .addMigrations(*ALL_MIGRATIONS)
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
         * v8→v9: 短信黑名单 / 关键词拦截落地，新增 sms_rule + sms_blocked_log 两张表。
         *
         * 取代 `SmsForwardConfig.blacklist`（一个 StringSet，每条同时匹配发件人和正文，
         * 无法区分「拉黑号码」和「屏蔽关键词」两种意图）。规则搬进 DB 之后才能有
         * enabled 开关、命中次数、以及「被拦下的短信长什么样」的自查记录。
         *
         * 两张表都是新建表 → 既有数据完全不受影响；`CREATE TABLE IF NOT EXISTS` 让重复执行也安全。
         * 索引名必须严格是 `index_<表名>_<列名>`，否则 Room 打开时的 schema 校验会失败。
         */
        @VisibleForTesting
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_rule` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `scope` TEXT NOT NULL,
                        `match_type` TEXT NOT NULL,
                        `pattern` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `hit_count` INTEGER NOT NULL,
                        `last_hit_at` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_rule_enabled` ON `sms_rule` (`enabled`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_blocked_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `msg_id` INTEGER NOT NULL,
                        `sender` TEXT NOT NULL,
                        `snippet` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `rule_id` INTEGER NOT NULL,
                        `rule_pattern` TEXT NOT NULL,
                        `rule_scope` TEXT NOT NULL,
                        `rule_match` TEXT NOT NULL,
                        `blocked_path` TEXT NOT NULL,
                        `blocked_at` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sms_blocked_log_blocked_at` " +
                        "ON `sms_blocked_log` (`blocked_at`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sms_blocked_log_msg_id` " +
                        "ON `sms_blocked_log` (`msg_id`)"
                )
            }
        }

        /**
         * v9→v10: 新增 mail_send_records 表（邮件投递记录）。
         *
         * 在此之前「这封邮件发出去了没有」只有 `MailStats` 的三个计数器可查：
         * 成功数、失败数、**最后一条**错误 —— 失败三次只看得到最后一条原因，
         * 前两条连时间和主题都没留下。新表按投递逐条留档，只记真正发起过 SMTP 的那些。
         *
         * 新建表 → 既有数据不受影响。索引名必须严格是 `index_<表名>_<列名>`，
         * 否则 Room 打开时的 schema 校验会失败。
         */
        @VisibleForTesting
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `mail_send_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `scene` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `recipient` TEXT NOT NULL,
                        `success` INTEGER NOT NULL,
                        `error` TEXT NOT NULL,
                        `sent_at` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mail_send_records_sent_at` " +
                        "ON `mail_send_records` (`sent_at`)"
                )
            }
        }

        /**
         * v10→v11: `mail_send_records` 新增 `channel` 列（通知分发器阶段 1）。
         *
         * 表名**保留不改**：它是 v10 的既有表名，改名要写"建新表 + 搬数据 + 删旧表"的迁移，
         * 唯一收益是名字好看一点。历史包袱，认了（见 [MailSendRecord] 头注释）。
         *
         * `DEFAULT 'mail'` 让存量行自动归到邮件渠道 —— v10 时这张表只有邮件一个写入方，
         * 所以默认值等价于事实，不需要额外的 UPDATE 回填。
         *
         * 索引名必须严格是 `index_<表名>_<列名>`，否则 Room 打开时的 schema 校验会失败
         * （TableInfo 按名字比对索引，名字不对就报"expected/found"不一致）。
         */
        @VisibleForTesting
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `mail_send_records` ADD COLUMN `channel` TEXT NOT NULL DEFAULT 'mail'"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mail_send_records_channel` " +
                        "ON `mail_send_records` (`channel`)"
                )
            }
        }

        /**
         * v11→v12: `mail_send_records` 新增 `outcome` 列 —— 投递记录从二态变三态。
         *
         * 加它的原因是 v11 的表答不出排查里最常问的那句「我开着通知，这条为什么没收到」：
         * 没发起投递的那几种情况（免打扰 / 场景未勾 / 级别不够 / 配额用尽）在表里
         * 没有任何痕迹，只落在 INFO 日志里，而 release 构建不留 INFO。
         *
         * `DEFAULT 'sent'` **不足以**回填：v11 的表里有失败行。所以紧跟一条 UPDATE 按
         * `success` 反推（v11 及以前只写"发起过投递"的行，必然是 sent/failed 二态，
         * 没有第三种可能被误判成 sent）。这是 [MIGRATION_6_7] 那种"默认值不等价于事实、
         * 必须显式回填"的情形。
         *
         * `success` 列**保留**：删一个 NOT NULL 列要建新表搬数据，唯一收益是少一列。
         * 它从此是 `outcome == 'sent'` 的副本，写入口只有一处、自己算（见 [MailSendRecord.success]）。
         *
         * 索引名必须严格是 `index_<表名>_<列名>`（理由同 [MIGRATION_10_11]）。
         */
        @VisibleForTesting
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `mail_send_records` ADD COLUMN `outcome` TEXT NOT NULL DEFAULT 'sent'"
                )
                db.execSQL(
                    "UPDATE `mail_send_records` SET `outcome` = " +
                        "CASE WHEN `success` = 1 THEN 'sent' ELSE 'failed' END"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mail_send_records_outcome` " +
                        "ON `mail_send_records` (`outcome`)"
                )
            }
        }

        /**
         * v13：新增 `console_history`（终端命令历史，AT / Shell 两端共享）。
         *
         * 纯建表，既有数据不受影响。索引名严格用 `index_<表名>_<列名>`：Room 打开时按
         * 名字比对 TableInfo 的索引，名字不一致会报 expected/found 不符（见 8→9 的注释）。
         */
        @VisibleForTesting
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `console_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `channel` TEXT NOT NULL,
                        `command` TEXT NOT NULL,
                        `as_root` INTEGER NOT NULL,
                        `exit_code` INTEGER,
                        `stdout` TEXT NOT NULL,
                        `stderr` TEXT NOT NULL,
                        `ok` INTEGER NOT NULL,
                        `truncated` INTEGER NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_console_history_created_at` " +
                        "ON `console_history` (`created_at`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_console_history_channel` " +
                        "ON `console_history` (`channel`)"
                )
            }
        }

        /**
         * 全部迁移，按版本递增排列。**新增迁移只追加到这个数组末尾。**
         *
         * 2026-09-08：生产装配与两个 androidTest 此前各手抄一份迁移列表。DB 从 8 一路涨到 10
         * 时只有生产那份补齐了，两个迁移测试仍停在 8→9，Room 打开直接抛
         * 「A migration from 9 to 10 was required but not found」；而它们跑在 androidTest 上，
         * 没有设备就不会执行，于是「core 升级后还能不能打开 DB」这道唯一的守卫一直是失效的。
         * 三处统一读这一份，从机制上消掉这种漂移。
         *
         * ## 手写建表 SQL 的一条硬规则：AUTOINCREMENT 只能写在列上
         *
         * 2026-09-10 修：本文件里四处建表（`sms_rule` / `sms_blocked_log` /
         * `mail_send_records` / `console_history`）都写成了
         * ``` `id` INTEGER NOT NULL, … , PRIMARY KEY(`id`) AUTOINCREMENT ```。
         * 那是**非法 SQL** —— SQLite 只允许 `AUTOINCREMENT` 作为**列约束**跟在
         * `INTEGER PRIMARY KEY` 后面，写成表级约束会直接抛
         * `near "AUTOINCREMENT": syntax error`。
         *
         * 后果是整条迁移在事务里失败并回滚：版本号不前进、表没建出来，而 Room 每次打开都
         * 重试一遍 —— 表现是**任何一个碰数据库的接口都回 500**（用户是在
         * `GET /api/alerts/list` 上撞到的，那只是升级后第一个读库的请求）。
         * 新装用户不会遇到：全新安装的表由 Room 自己按生成的 schema 建，走不到手写这份。
         *
         * 正确写法就抄 Room 生成的那一份：``` `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL ```
         * （`core/database/src/androidTest/assets/databases/…/3.json` 里的 `createSql` 是现成样本）。
         */
        @VisibleForTesting
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
            MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13
        )

        /**
         * 获取数据库总大小（近似值）
         */
        fun getDatabaseSize(): Long {            val path = dbPath ?: return 0L
            val dbFile = File(path)
            return if (dbFile.exists()) dbFile.length() else 0L
        }
    }
}
