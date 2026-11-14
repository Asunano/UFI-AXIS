package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ThermalZones.readMax] 的单元测试（2026-09-25 缺陷 C）。
 *
 * 钉的是三种场景：多 zone、部分不可读、全部不可读（返回 null 的约定）。
 * 全部在裸 JVM 上跑（不依赖 Android）—— [ThermalZones] 只用 `java.io.File`，
 * 测试通过 [TemporaryFolder] 构造虚拟的 `/sys/class/thermal` 结构。
 */
class ThermalZonesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ────────────────── 正常多 zone ──────────────────

    @Test
    fun `多 zone 取最大值`() {
        val root = tmp.newFolder("thermal")
        // zone0: 45000 (45°C)
        val z0 = root.resolve("thermal_zone0").apply { mkdir() }
        z0.resolve("temp").writeText("45000")
        // zone1: 52000 (52°C)
        val z1 = root.resolve("thermal_zone1").apply { mkdir() }
        z1.resolve("temp").writeText("52000")
        // zone2: 38000 (38°C)
        val z2 = root.resolve("thermal_zone2").apply { mkdir() }
        z2.resolve("temp").writeText("38000")

        val r = ThermalZones.readMax(root.absolutePath)
        assertNotNull("至少读到了一个热区", r.maxMilliC)
        assertEquals("应取三个中的最大值", 52000L, r.maxMilliC)
        assertEquals(3, r.zonesSeen)
        assertEquals(3, r.zonesRead)
    }

    // ────────────────── 部分不可读 ──────────────────

    @Test
    fun `部分 zone 不可读时仍返回其余的最大值`() {
        val root = tmp.newFolder("thermal_partial")
        // zone0: 正常
        val z0 = root.resolve("thermal_zone0").apply { mkdir() }
        z0.resolve("temp").writeText("60000")
        // zone1: temp 文件不存在
        root.resolve("thermal_zone1").apply { mkdir() }
        // zone2: 正常但值较小
        val z2 = root.resolve("thermal_zone2").apply { mkdir() }
        z2.resolve("temp").writeText("33000")

        val r = ThermalZones.readMax(root.absolutePath)
        assertNotNull(r.maxMilliC)
        assertEquals(60000L, r.maxMilliC)
        assertEquals(3, r.zonesSeen)
        assertEquals(2, r.zonesRead)
    }

    @Test
    fun `内容不是数字时不算读到但不影响其余热区`() {
        val root = tmp.newFolder("thermal_nan")
        val z0 = root.resolve("thermal_zone0").apply { mkdir() }
        z0.resolve("temp").writeText("not_a_number")
        val z1 = root.resolve("thermal_zone1").apply { mkdir() }
        z1.resolve("temp").writeText("40000")

        val r = ThermalZones.readMax(root.absolutePath)
        assertNotNull(r.maxMilliC)
        assertEquals(40000L, r.maxMilliC)
        // P3-3：zone0 的 temp 能读但内容不是数字 => **不**算有效读数（原来算）=> zonesRead = 1
        assertEquals(1, r.zonesRead)
        assertEquals(2, r.zonesSeen)
        assertTrue("非数字这件事要能在 detail 里看出来", r.detail.contains("内容不是数字"))
    }

    // ────────────────── 全部不可读 / 不存在 ──────────────────

    @Test
    fun `全部 zone 的 temp 都不存在时返回 null`() {
        val root = tmp.newFolder("thermal_empty")
        root.resolve("thermal_zone0").apply { mkdir() }
        root.resolve("thermal_zone1").apply { mkdir() }

        val r = ThermalZones.readMax(root.absolutePath)
        assertNull("没有任何 temp 文件，应返回 null", r.maxMilliC)
        assertEquals(2, r.zonesSeen)
        assertEquals(0, r.zonesRead)
    }

    @Test
    fun `根目录不存在时返回 null`() {
        val r = ThermalZones.readMax("/nonexistent_path_for_test")
        assertNull(r.maxMilliC)
        assertEquals(0, r.zonesSeen)
        assertEquals(0, r.zonesRead)
    }

    @Test
    fun `根目录存在但没有 thermal_zone 子目录时返回 null`() {
        val root = tmp.newFolder("thermal_no_zones")
        // 放一个非 thermal_zone 前缀的目录
        root.resolve("cooling_device0").apply { mkdir() }

        val r = ThermalZones.readMax(root.absolutePath)
        assertNull(r.maxMilliC)
        assertEquals(0, r.zonesSeen)
    }

    // ────────────────── 负数 / 非数字读数不算「读到了」（2026-09-25 P3-3）──────────────────
    //
    // 这三条钉的是同一件事：**「热区能读、但读出来的东西没有意义」必须返回 null，不是 0**。
    // 改坏之前这里返回 `maxMilliC = 0L` —— 调用方靠 null 决定要不要打 WARN，于是
    // 一条日志都不打；而 0（毫度）对下游是「最凉」哨兵，下载温控熔断与温度告警一起静默失效。

    @Test
    fun `唯一热区读数为负时返回 null`() {
        val root = tmp.newFolder("thermal_neg")
        val z0 = root.resolve("thermal_zone0").apply { mkdir() }
        z0.resolve("temp").writeText("-1")

        val r = ThermalZones.readMax(root.absolutePath)
        assertNull("负数不是有效读数（原来被地板夹成 0 并算作读到了）", r.maxMilliC)
        assertEquals(1, r.zonesSeen)
        assertEquals(0, r.zonesRead)
    }

    /** 全部热区都报 -1（某些内核热区未就绪时的写法）→ 必须 null，不能是 0。 */
    @Test
    fun `全部热区读数都是 -1 时返回 null`() {
        val root = tmp.newFolder("thermal_all_neg")
        for (i in 0..2) {
            root.resolve("thermal_zone$i").apply { mkdir() }.resolve("temp").writeText("-1")
        }

        val r = ThermalZones.readMax(root.absolutePath)
        assertNull("热区全部未就绪 = 读不到，不是 0°C", r.maxMilliC)
        assertEquals(3, r.zonesSeen)
        assertEquals(0, r.zonesRead)
        assertTrue("「读数为负」这件事要能在 detail 里看出来", r.detail.contains("读数为负"))
    }

    /** 全部热区值都不是数字 → 同样 null（与「读数为负」在 detail 里是两句不同的话）。 */
    @Test
    fun `全部热区值都不是数字时返回 null`() {
        val root = tmp.newFolder("thermal_all_nan")
        root.resolve("thermal_zone0").apply { mkdir() }.resolve("temp").writeText("N/A")
        root.resolve("thermal_zone1").apply { mkdir() }.resolve("temp").writeText("")

        val r = ThermalZones.readMax(root.absolutePath)
        assertNull("能读到文件但内容不是数字 = 读不到，不是 0°C", r.maxMilliC)
        assertEquals(2, r.zonesSeen)
        assertEquals(0, r.zonesRead)
        assertTrue(r.detail.contains("内容不是数字"))
        assertFalse("非数字不能被说成「读数为负」", r.detail.contains("读数为负"))
    }

    // ────────────────── readAll：逐热区出数（P3-12） ──────────────────

    /**
     * `GET /api/system/thermal` 消费的就是这个出口。两件事必须成立：
     *   1. 每个热区都出一条（读不到的也在，只是 milliC 为 null）；
     *   2. **负数照原样给出**（不夹 0、不抹 null）—— 那个端点的对外取值历来是
     *      「解析得到就原样 / 1000.0」，`-1` 要能渲染成 `-0.001`。
     */
    @Test
    fun `readAll 逐热区出数且负数原样保留`() {
        val root = tmp.newFolder("thermal_read_all")
        root.resolve("thermal_zone0").apply { mkdir() }.resolve("temp").writeText("45000")
        root.resolve("thermal_zone1").apply { mkdir() }.resolve("temp").writeText("-1")
        // temp 文件不存在
        root.resolve("thermal_zone2").apply { mkdir() }

        val scan = ThermalZones.readAll(root.absolutePath)
        assertNull("目录层没问题", scan.rootFailure)
        assertEquals(3, scan.zones.size)

        val byName = scan.zones.associateBy { it.name }
        assertEquals(45000L, byName.getValue("thermal_zone0").milliC)
        assertTrue(byName.getValue("thermal_zone0").valid)

        assertEquals("负数原样保留", -1L, byName.getValue("thermal_zone1").milliC)
        assertFalse("但不算有效读数", byName.getValue("thermal_zone1").valid)

        assertNull("temp 不存在 → null", byName.getValue("thermal_zone2").milliC)
        assertFalse(byName.getValue("thermal_zone2").valid)
    }

    @Test
    fun `readAll 在根目录不存在时只填 rootFailure`() {
        val scan = ThermalZones.readAll("/nonexistent_path_for_test")
        assertTrue(scan.zones.isEmpty())
        assertNotNull(scan.rootFailure)
    }
}

