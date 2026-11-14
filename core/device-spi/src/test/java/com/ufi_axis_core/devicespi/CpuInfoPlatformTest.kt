package com.ufi_axis_core.devicespi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CpuInfoPlatform] 的纯逻辑测试（阶段 5 的 5.1 / 阶段 4 的 4.6）。
 *
 * ## 这里**不测**什么，以及为什么
 *
 * `ProbeEnvCollector` 的三件采集动作**一条都不在这里测**：
 * - 读 `/proc/cpuinfo`：JVM 单测机器上没有这个文件（Windows 上必然读不到），
 *   断言只会变成「测环境有没有这个文件」；
 * - `Build.*`：JVM 单测里是 `RuntimeException: Stub!`；
 * - `LD` 探测：**绝不在单测里发真请求** —— 那会让测试结果取决于跑测试的那台机器
 *   当时能不能连上某个 IP（无网络时 1.5s 超时、有 goform 设备在同网段时甚至会真的成功），
 *   这种测试比没有测试更糟。要覆盖它得先给采集器注入一个 HTTP 抽象，
 *   而那层抽象在**当前只有一个调用点**的情况下属于过度设计。
 *   它的验证靠真机冒烟（`/api/at/platform` 与启动日志里那条 `goform_ld_reachable=`）。
 *
 * 于是能在 JVM 上稳定测的正好就是被抽出来的那两块纯逻辑：**串归一化**与**marker 匹配**。
 */
class CpuInfoPlatformTest {

    // ── normalize ────────────────────────────────────────────────────────────

    @Test
    fun `normalize 转小写并去首尾空白`() {
        assertEquals("hardware : spreadtrum ums9620", CpuInfoPlatform.normalize("  Hardware : Spreadtrum UMS9620\n"))
    }

    @Test
    fun `normalize 对 null 与空白内容一律给 null`() {
        // 读不到 / 读到空文件与「读到了但没有平台信息」在下游是同一件事：判不出平台。
        assertNull(CpuInfoPlatform.normalize(null))
        assertNull(CpuInfoPlatform.normalize(""))
        assertNull(CpuInfoPlatform.normalize("   \n\t "))
    }

    @Test
    fun `normalize 不丢内容 —— 全文原样保留只是换了大小写`() {
        // 这一条钉住「不许改成只抽某一行」：ATChannel 改造前是对全文做 contains，
        // 抽行会让特征串出现在别的键上的机型静默变成 UNKNOWN（见 normalize 的 KDoc）。
        val raw = "processor\t: 0\nBogoMIPS\t: 26.00\nHardware\t: SPRD-ums9620\n"
        assertEquals(raw.trim().lowercase(), CpuInfoPlatform.normalize(raw))
    }

    // ── marker 匹配 ──────────────────────────────────────────────────────────

    @Test
    fun `展锐三个 marker 全部命中（合并后的并集）`() {
        // 4.6 把两份判据合成一份并取并集：sprd / spreadtrum 来自 ATChannel，unisoc 来自 probe()。
        assertEquals(listOf("sprd", "spreadtrum", "unisoc"), CpuInfoPlatform.SPREADTRUM_MARKERS)
        CpuInfoPlatform.SPREADTRUM_MARKERS.forEach { marker ->
            assertTrue("marker $marker 必须命中", CpuInfoPlatform.isSpreadtrum("hardware : $marker ums9620"))
        }
    }

    @Test
    fun `unisoc 单独出现时也判展锐 —— 这是 4point6 的行为变更`() {
        // 改造前：ATChannel 没有 unisoc，这种串判 UNKNOWN；合并后判 SPREADTRUM。
        // 影响面是 /api/at/platform 与 /api/at/status 的 platform 字段（已在执行报告里显式报出）。
        assertTrue(CpuInfoPlatform.isSpreadtrum("hardware : unisoc t760"))
        assertFalse(CpuInfoPlatform.isQualcomm("hardware : unisoc t760"))
    }

    @Test
    fun `高通两个 marker 保留 —— 本仓没有高通插件也不许删`() {
        // QUALCOMM 分支是 /api/at/platform 的合法取值：删了就是把高通机器报成 UNKNOWN，
        // 丢掉「这台其实不是展锐」这条排障信息。
        assertEquals(listOf("qualcomm", "qcom"), CpuInfoPlatform.QUALCOMM_MARKERS)
        assertTrue(CpuInfoPlatform.isQualcomm("hardware : qualcomm technologies, inc sm8450"))
        assertTrue(CpuInfoPlatform.isQualcomm("hardware : qcom"))
        assertFalse(CpuInfoPlatform.isSpreadtrum("hardware : qualcomm technologies, inc sm8450"))
    }

    @Test
    fun `匹配大小写不敏感 —— 不依赖调用方是否已 normalize`() {
        assertTrue(CpuInfoPlatform.isSpreadtrum("Hardware : Spreadtrum UMS9620"))
        assertTrue(CpuInfoPlatform.isQualcomm("Hardware : Qualcomm"))
    }

    @Test
    fun `null 与都不命中的串一律 false`() {
        // 对应 ATChannel 的 UNKNOWN 分支：读不到、或者读到了但两组 marker 都不中。
        assertFalse(CpuInfoPlatform.isSpreadtrum(null))
        assertFalse(CpuInfoPlatform.isQualcomm(null))
        assertFalse(CpuInfoPlatform.isSpreadtrum(""))
        assertFalse(CpuInfoPlatform.isSpreadtrum("hardware : mt6893"))
        assertFalse(CpuInfoPlatform.isQualcomm("hardware : mt6893"))
    }

    @Test
    fun `判定顺序可判定 —— 同时含两家特征时展锐优先`() {
        // ATChannel.detectPlatform() 的 when 是「先展锐后高通」，与改造前逐字一致。
        // 这条把「两个都 true」这种病态输入的结论固定下来，免得将来有人调换顺序时以为无害。
        val both = "hardware : sprd + qcom"
        assertTrue(CpuInfoPlatform.isSpreadtrum(both))
        assertTrue(CpuInfoPlatform.isQualcomm(both))
    }
}
