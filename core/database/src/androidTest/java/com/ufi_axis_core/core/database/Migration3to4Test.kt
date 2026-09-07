package com.ufi_axis_core.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F15 — Room MIGRATION_3_4 迁移测试（androidTest，需设备/模拟器）。
 *
 * 验证：
 *  - 3→4 迁移后 5 个时间戳索引（traffic/signal/cpu/memory/battery）被建立；
 *  - 迁移过程不丢数据（写入的 traffic_records / memory_history 行仍在）；
 *  - 破坏性回退（fallbackToDestructiveMigration，未提供迁移）会清空旧数据 ——
 *    反向说明显式 MIGRATION_3_4 的必要性（避免用户数据被误删）。
 *
 * 做法：用 [MigrationTestHelper.createDatabase] 在 v3 基线落库（schema 取自
 * src/androidTest/assets/databases/.../3.json），再以 [Room] + [MIGRATION_3_4]
 * 打开触发真实迁移；Room 成功打开本身即完成 schema 校验（无需手工维护 v4 identityHash）。
 *
 * 依赖（core/database build.gradle.kts androidTestImplementation）：
 *  - androidx.room:room-testing（MigrationTestHelper）
 *  - androidx.test.ext.junit
 */
class Migration3to4Test {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val migrateDbName = "migration_test_3_4"
    private val destructiveDbName = "migration_test_destructive"

    private val EXPECTED_INDICES = listOf(
        "index_traffic_records_timestamp",
        "index_signal_history_timestamp",
        "index_cpu_history_timestamp",
        "index_memory_history_timestamp",
        "index_battery_history_timestamp"
    )

    @After
    fun tearDown() {
        context.deleteDatabase(migrateDbName)
        context.deleteDatabase(destructiveDbName)
    }

    @Test
    fun migration3To4_createsIndicesAndPreservesData() {
        val helper = MigrationTestHelper(context, AppDatabase::class.java.canonicalName)

        // 1) 建立 v3 基线并写入真实数据
        helper.createDatabase(migrateDbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO traffic_records (rxBytes,txBytes,rxSpeed,txSpeed,timestamp) " +
                    "VALUES (100,200,10,20,999)"
            )
            db.execSQL(
                "INSERT INTO memory_history (total,used,available,usagePercent,timestamp) " +
                    "VALUES (4096,2048,2048,50.0,999)"
            )
        }

        // 2) 以 v4 schema 打开（应用 MIGRATION_3_4）；打开成功即代表 schema 校验通过
        val appDb = Room.databaseBuilder(context, AppDatabase::class.java, migrateDbName)
            .addMigrations(MIGRATION_3_4)
            .build()

        appDb.use { db ->
            // 数据保留
            assertEquals(1, countRows(db, "traffic_records"))
            assertEquals(1, countRows(db, "memory_history"))

            // 5 个时间戳索引均已建立
            for (idx in EXPECTED_INDICES) {
                assertTrue("迁移后缺失索引: $idx", indexExists(db, idx))
            }
        }
    }

    @Test
    fun migration3To4_destructiveFallbackDropsData() {
        val helper = MigrationTestHelper(context, AppDatabase::class.java.canonicalName)

        // 1) v3 基线写入数据
        helper.createDatabase(destructiveDbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO traffic_records (rxBytes,txBytes,rxSpeed,txSpeed,timestamp) " +
                    "VALUES (1,2,3,4,5)"
            )
        }

        // 2) 未提供 MIGRATION_3_4，仅 fallbackToDestructiveMigration → 3→4 走破坏性回退
        val appDb = Room.databaseBuilder(context, AppDatabase::class.java, destructiveDbName)
            .fallbackToDestructiveMigration()
            .build()

        appDb.use { db ->
            // 破坏性回退：旧表被重建，数据清空
            assertEquals(0, countRows(db, "traffic_records"))
        }
    }

    private fun countRows(db: AppDatabase, table: String): Int {
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
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
