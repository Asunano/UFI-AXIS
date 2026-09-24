package com.ufi_axis_core.controller.system

import com.ufi_axis_core.devicespi.DeviceTuning
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.5 接线的守门测试：`DownloadManager` 的下载限速温度阈值什么时候吃插件的
 * [DeviceTuning]、什么时候**必须**让用户已写的配置说话。
 *
 * ## 为什么 tuning 里故意填 77 / 88 / 12
 *
 * F50 插件的真实取值是 75 / 85 / 10，而 [DownloadManager.DownloadConfig] 的字段字面量
 * 恰好也是 75 / 85（第 4 档的偏移量原本是裸字面量 10）。用真值断言的话，
 * 「接线接错了、仍然读字面量」与「接线接对了」两种实现会给出同样的结果 —— 测了等于没测。
 * 所以这里一律用**与字面量不同**的取值。
 *
 * ## 这三套 75 不是一件事（别顺手对齐）
 *
 * 本文件只碰 `downloadThrottle*`。采集降频那套（`AppSettings.monitorThermal*`，70/80，
 * Int 毫摄氏度）与用户告警那套（`AlertEngine`，65/75，Double + 3°C 回差）
 * **不在本批范围内**，尤其别因为 `AlertEngine.temperatureCritical = 75.0` 与
 * `downloadThrottleWarnC = 75f` 数值相同就把它们接到一起（见 [DeviceTuning] 的类 KDoc）。
 */
class DownloadConfigTuningTest {

    private val tuning = DeviceTuning(
        downloadThrottleWarnC = 77f,
        downloadThrottleCriticalC = 88f,
        downloadThrottleForcePauseOffsetC = 12f,
        // 下面两个字段与下载限速无关，本文件不碰；填值只为构造出对象
        thermalJitterC = 3f,
        bootGraceMs = 90_000L,
    )

    // ① 首次创建配置（config.json 还不存在）时，两个阈值来自 tuning
    @Test
    fun `initial config takes both temp thresholds from tuning`() {
        val config = DownloadManager.initialConfig(tuning)
        assertEquals(77f, config.throttleTempWarn, 0f)
        assertEquals(88f, config.throttleTempCritical, 0f)
        // 其余字段仍走 DownloadConfig 的字段默认值，本批不动
        assertEquals(3, config.maxConcurrent)
        assertTrue(config.smartThrottle)
    }

    // ② 用户已写的配置不被 tuning 覆盖：值在地板判据之上 → 迁移一个字都不改
    @Test
    fun `existing user config is not overwritten by tuning`() {
        val config = DownloadManager.DownloadConfig(
            saveDir = DownloadManager.PUBLIC_DOWNLOAD_DIR,
            throttleTempWarn = 72f,      // 低于 tuning 的 77，但高于地板 70 → 不许动
            throttleTempCritical = 82f,  // 低于 tuning 的 88，但高于地板 80 → 不许动
        )
        val changed = DownloadManager.migrateConfigInPlace(config, tuning)
        assertFalse("没有任何该迁移的项，不该报告改动（否则会无谓落盘）", changed)
        assertEquals(72f, config.throttleTempWarn, 0f)
        assertEquals(82f, config.throttleTempCritical, 0f)
    }

    // ② 续：盘上的 json 反序列化出来的值同样优先（这是「用户已写」的真实入口）
    @Test
    fun `values from config json survive migration`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<DownloadManager.DownloadConfig>(
            """{"throttleTempWarn":71.5,"throttleTempCritical":80.5,"maxConcurrent":8}"""
        )
        DownloadManager.migrateConfigInPlace(decoded, tuning)
        assertEquals(71.5f, decoded.throttleTempWarn, 0f)
        assertEquals(80.5f, decoded.throttleTempCritical, 0f)
        assertEquals(8, decoded.maxConcurrent)
    }

    // ③ 迁移抬升仍然生效，且抬到 tuning 的值（不是抬到 75/85 字面量）
    @Test
    fun `migration raises legacy thresholds to tuning values`() {
        // 55/70 就是 2026-09-02 之前的那套旧默认值
        val config = DownloadManager.DownloadConfig(
            throttleTempWarn = 55f,
            throttleTempCritical = 70f,
        )
        val changed = DownloadManager.migrateConfigInPlace(config, tuning)
        assertTrue(changed)
        assertEquals(77f, config.throttleTempWarn, 0f)
        assertEquals(88f, config.throttleTempCritical, 0f)
    }

    // ③ 续：地板判据是 70 / 80 的字面量，**不是** tuning 的 77 / 88。
    // 这两条如果被误接成 tuning，69.9 与 79.9 之外的 70~77 / 80~88 区间会被连带抬升，
    // 也就是把用户自己调低过的阈值改回去。
    @Test
    fun `migration floors stay at 70 and 80 literals`() {
        val justAboveFloor = DownloadManager.DownloadConfig(
            throttleTempWarn = 70f,
            throttleTempCritical = 80f,
        )
        assertFalse(DownloadManager.migrateConfigInPlace(justAboveFloor, tuning))
        assertEquals(70f, justAboveFloor.throttleTempWarn, 0f)
        assertEquals(80f, justAboveFloor.throttleTempCritical, 0f)

        val justBelowFloor = DownloadManager.DownloadConfig(
            throttleTempWarn = 69.9f,
            throttleTempCritical = 79.9f,
        )
        assertTrue(DownloadManager.migrateConfigInPlace(justBelowFloor, tuning))
        assertEquals(77f, justBelowFloor.throttleTempWarn, 0f)
        assertEquals(88f, justBelowFloor.throttleTempCritical, 0f)
    }

    // ③ 续：saveDir 的旧路径迁移与温度无关，接线不许把它带坏
    @Test
    fun `legacy save dir still migrates`() {
        val config = DownloadManager.DownloadConfig(
            saveDir = "/storage/emulated/0/Downloads/UFI",
            throttleTempWarn = 77f,
            throttleTempCritical = 88f,
        )
        assertTrue(DownloadManager.migrateConfigInPlace(config, tuning))
        assertEquals(DownloadManager.PUBLIC_DOWNLOAD_DIR, config.saveDir)
    }

    // ④ 第 4 档（强制全部暂停）用的是 tuning 的偏移量，不是裸字面量 10
    @Test
    fun `force pause tier uses tuning offset`() {
        val config = DownloadManager.DownloadConfig(
            throttleTempWarn = 77f,
            throttleTempCritical = 88f,
        )
        // critical + offset = 88 + 12 = 100
        assertEquals(4, DownloadManager.computeTempLevel(100f, config, tuning))
        assertEquals(4, DownloadManager.computeTempLevel(100.1f, config, tuning))
        // 99 越过了 critical 但没越过暂停线 → 第 3 档。
        // 如果偏移量被写死成 10，这里会算成 88 + 10 = 98 → 返回 4，本断言会红。
        assertEquals(3, DownloadManager.computeTempLevel(99f, config, tuning))
        assertEquals(3, DownloadManager.computeTempLevel(88f, config, tuning))
        assertEquals(2, DownloadManager.computeTempLevel(77f, config, tuning))
        assertEquals(0, DownloadManager.computeTempLevel(76.9f, config, tuning))
    }

    // ④ 续：读不到温度时 DownloadManager 把 null 折成 0f，0f 必须落在 0 档
    // （与改造前 readMaxTemp() 返回 0f 的行为一致，不许变成「读不到就按最高档保护」）
    @Test
    fun `zero temperature falls into level zero`() {
        val config = DownloadManager.DownloadConfig(
            throttleTempWarn = 77f,
            throttleTempCritical = 88f,
        )
        assertEquals(0, DownloadManager.computeTempLevel(0f, config, tuning))
    }
}
