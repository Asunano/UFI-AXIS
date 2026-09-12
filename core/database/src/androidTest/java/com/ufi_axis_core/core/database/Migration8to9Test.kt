package com.ufi_axis_core.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIGRATION_8_9 迁移测试（androidTest，需设备/模拟器）。
 *
 * v9 新增 `sms_rule` + `sms_blocked_log` 两张表（短信黑名单 / 关键词拦截）。
 * 漏写这条迁移**不是静默清库**，而是 `AppDatabase` 打开时直接抛
 * `IllegalStateException`（`buildDatabase` 里全链路显式迁移，没有
 * `fallbackToDestructiveMigration`）—— 所以这个测试守的是「core 还能不能启动」。
 *
 * ## 为什么基线是 v3 而不是 v8
 * `AppDatabase` 是 `exportSchema = false`，`androidTest/assets/databases` 下只有一份
 * 手工维护的 `3.json`，[MigrationTestHelper.createDatabase] 拿不到 v8 的 schema。
 * 所以这里从 v3 建库、再用**完整迁移链**打开 —— 8→9 作为链条的最后一环被真实执行，
 * 顺带还验证了整条链能连通（这比只测一环更有价值）。
 *
 * Room 成功打开本身即完成 v9 schema 校验：表结构、列类型、**索引名**任一不符都会抛异常。
 * 索引名必须严格是 `index_<表名>_<列名>`。
 */
class Migration8to9Test {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration_test_8_9"

    /** v9 应当存在的两张新表与三个索引。 */
    private val EXPECTED_TABLES = listOf("sms_rule", "sms_blocked_log")
    private val EXPECTED_INDICES = listOf(
        "index_sms_rule_enabled",
        "index_sms_blocked_log_blocked_at",
        "index_sms_blocked_log_msg_id"
    )

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration8To9_createsFilterTables_andPreservesExistingData() {
        val helper = MigrationTestHelper(context, AppDatabase::class.java.canonicalName)

        // 1) v3 基线并写入真实数据（迁移不能碰这些行）
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO traffic_records (rxBytes,txBytes,rxSpeed,txSpeed,timestamp) " +
                    "VALUES (100,200,10,20,999)"
            )
            db.execSQL(
                "INSERT INTO alert_records (type,level,message,value,threshold,acknowledged,timestamp) " +
                    "VALUES ('temperature','warning','热','50','45',0,999)"
            )
        }

        // 2) 以 v9 打开（完整迁移链）；打开成功即代表 schema 校验通过
        val appDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        appDb.use { db ->
            // 既有数据无损
            assertEquals(1, countRows(db, "traffic_records"))
            assertEquals(1, countRows(db, "alert_records"))

            // 两张新表存在
            for (t in EXPECTED_TABLES) {
                assertTrue("迁移后缺失表: $t", tableExists(db, t))
            }
            // 三个索引存在且名字符合 Room 规则
            for (idx in EXPECTED_INDICES) {
                assertTrue("迁移后缺失索引: $idx", indexExists(db, idx))
            }
        }
    }

    /** 新表真的可写可读（AUTOINCREMENT 主键 + 各列类型都对）。 */
    @Test
    fun migration8To9_newTablesAreUsable() {
        val helper = MigrationTestHelper(context, AppDatabase::class.java.canonicalName)
        helper.createDatabase(dbName, 3).use { }

        val appDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        appDb.use { db ->
            kotlinx.coroutines.runBlocking {
                val ruleId = db.smsRuleDao().insert(
                    SmsRule(scope = "sender", match_type = "equals", pattern = "10086")
                )
                assertTrue("autoGenerate 主键应回填正数 id", ruleId > 0)
                assertEquals(1, db.smsRuleDao().getEnabled().size)

                // 增量计数：hit_count = hit_count + delta
                db.smsRuleDao().bumpHit(ruleId, 3, 12345L)
                val bumped = db.smsRuleDao().getAll().single()
                assertEquals(3, bumped.hit_count)
                assertEquals(12345L, bumped.last_hit_at)

                db.smsBlockedLogDao().insert(
                    SmsBlockedLog(
                        msg_id = 7L, sender = "10086", snippet = "预览", rule_id = ruleId,
                        rule_pattern = "10086", rule_scope = "sender", rule_match = "equals",
                        blocked_path = "push", blocked_at = 1000L
                    )
                )
                assertEquals(1, db.smsBlockedLogDao().countAll())
                assertEquals(7L, db.smsBlockedLogDao().findByMsgId(7L)?.msg_id)
                // keyset 游标：首页传 null
                assertEquals(1, db.smsBlockedLogDao().getPaged(null, null, 10).size)
            }
        }
    }

    private fun countRows(db: AppDatabase, table: String): Int {
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun tableExists(db: AppDatabase, name: String): Boolean {
        db.query(
            SimpleSQLiteQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(name)
            )
        ).use { cursor -> return cursor.count > 0 }
    }

    private fun indexExists(db: AppDatabase, name: String): Boolean {
        db.query(
            SimpleSQLiteQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
                arrayOf(name)
            )
        ).use { cursor -> return cursor.count > 0 }
    }
}
