package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 摄氏度 → 毫摄氏度换算（阶段 4 的 4.3 / 批 I）。
 *
 * 这个函数是 `DataScheduler.readMaxCpuTemp()` 唯一剩下的自有逻辑 ——
 * 热区读法本身已经收进 `PlatformAdapter.readTemperature()`（摄氏度 `Float?`），
 * 而降频 / 熔断阈值仍然是毫摄氏度 `Int`。
 *
 * 钉三件事：
 * 1. **`null` → `0`**。0 是这条链路既有的「读不到」哨兵值，下游三处行为（三档自适应许可 /
 *    熔断 `when` / `if (milli > 0)` 的温度告警门）全按它设计。改成别的值就是一次静默的行为变更。
 * 2. **`roundToInt()` 的往返零误差**。`Float` 只有 24 位有效位，毫度经 `/1000f` 再 `×1000`
 *    回不到原值，截断会少 1。
 * 3. **反例：`toInt()` 真的会少 1**。第 3 个用例是**故意断言 `toInt()` 算错**的 ——
 *    它把「为什么这里必须是 roundToInt」钉死在测试里，防止将来有人「顺手简化」成截断。
 */
class ThermalUnitsTest {

    /** 与 `SprdPlatform.readTemperature()` 一致：sysfs 毫度整数 → 摄氏度 Float。 */
    private fun asAdapterReading(milli: Int): Float = milli.toLong() / 1000f

    @Test
    fun `读不到时映射成 0`() {
        // readTemperature() 返回 null = 一个热区都没读成功。
        // 这里必须是 0：它是下游三处「最凉档 / 不熔断 / 不告警」的触发条件。
        assertEquals(0, celsiusToMilliC(null))
    }

    @Test
    fun `20 到 100 度的全部毫度值往返零误差`() {
        var mismatches = 0
        var firstBad = -1
        for (milli in 20_000..100_000) {
            val back = celsiusToMilliC(asAdapterReading(milli))
            if (back != milli) {
                mismatches++
                if (firstBad < 0) firstBad = milli
            }
        }
        assertEquals(
            "roundToInt 往返必须零误差，首个不匹配=$firstBad",
            0,
            mismatches
        )
    }

    @Test
    fun `toInt 截断会少 1 —— 所以不许用它`() {
        // 实测区间 20~100°C 的 80001 个毫度值里有 555 个这样的点，最小的就是 32002。
        val reading = asAdapterReading(32_002)   // 32.002f
        assertEquals("往返值确实落在 32002 下方", 32_001, (reading * 1000).toInt())
        assertNotEquals("截断法与原值不等 —— 这正是弃用 toInt 的理由", 32_002, (reading * 1000).toInt())
        // 同一个输入，roundToInt 拿到原值
        assertEquals(32_002, celsiusToMilliC(reading))
    }

    @Test
    fun `阈值边界与零点精确`() {
        // monitorThermalWarnC / CriticalC 默认 70 / 80，进 DataScheduler 时 ×1000。
        // 这几个点换算错一毫度就可能改判一档，所以单独钉住。
        assertEquals(69_999, celsiusToMilliC(asAdapterReading(69_999)))
        assertEquals(70_000, celsiusToMilliC(asAdapterReading(70_000)))
        assertEquals(70_001, celsiusToMilliC(asAdapterReading(70_001)))
        assertEquals(79_999, celsiusToMilliC(asAdapterReading(79_999)))
        assertEquals(80_000, celsiusToMilliC(asAdapterReading(80_000)))
        assertEquals(80_001, celsiusToMilliC(asAdapterReading(80_001)))
        // adapter 的 max 从 0f 起，所以「读到了但都 ≤ 0」会给 0f ——
        // 它与 null 在本函数出口合并成同一个 0（下游语义一致，见类注释第 1 条）。
        assertEquals(0, celsiusToMilliC(0f))
    }
}
