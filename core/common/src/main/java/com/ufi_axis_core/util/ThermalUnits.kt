package com.ufi_axis_core.util

import kotlin.math.roundToInt

/**
 * 摄氏度 → 毫摄氏度的单位换算 —— 阶段 4 的 4.3（批 I）。
 *
 * ## 为什么需要这一步
 *
 * 平台适配层（`PlatformAdapter.readTemperature()`）统一按**摄氏度 `Float?`** 出数，
 * 而「采集降频 / 温度熔断」这条链路的阈值全是**毫摄氏度 `Int`**
 * （`DataScheduler` 里的 `thermalWarnMilliC` / `thermalCriticalMilliC` = 设置值 × 1000）。
 * 换算就在这两个口径之间，别的什么都不做 —— **不含任何阈值判断**：
 * 「该不该降频 / 该不该清缓存 / 该不该告警」留在 `DataScheduler`，
 * 口径同 `PlatformAdapter.readTemperature` 的第 2 条硬约束。
 *
 * ## 为什么必须 `roundToInt()`，不许 `toInt()`
 *
 * 适配层的读数是 `sysfs` 里的毫度整数经 `milli / 1000f` 得来的，
 * 这一步会丢精度：`Float` 只有 24 位有效位，`32002` → `32.002f` → 再 ×1000 得到的是
 * `32001.998f`。`toInt()` 是**向零截断**，于是这类值会**少 1** ——
 * 而 `roundToInt()` 就地还原成 `32002`。
 *
 * 2026-09-24 实测（JVM，与 Kotlin 的 `Float.roundToInt()` = `Math.round(Float)` 同语义）：
 * 遍历 20.000 ~ 100.000 °C 的全部 **80001** 个毫度值做 `milli → Float → 毫度` 往返，
 * - `toInt()`：**555** 个不匹配，最小的是 `32002`（往返得 32001）；
 * - `roundToInt()`：**0** 个不匹配。
 *
 * 少 1 毫度本身对 70000 / 80000 这两个阈值几乎没有影响（要正好压在阈值上才改判），
 * 但它会让「adapter 读到 X 度」与「scheduler 认为是 X 度」出现无法解释的偏差，
 * 排障时是纯噪音。零成本就能消掉，所以消掉。
 *
 * ## `null` 为什么映射成 `0`
 *
 * `0` 是这条链路既有的「读不到」哨兵值，下游三处行为都按它设计：
 * 三档自适应许可（root / cacheTtl / goform）全落最凉档、温度熔断的 `when` 两个分支都不进、
 * `scanLocalAlerts()` 的 `if (milli > 0)` 不成立 → 跳过温度告警。
 * 改成别的值（比如 -1 或抛异常）会逐路改掉这三处，所以这里**必须**是 0。
 *
 * ⚠ 入参若为 `NaN`，`roundToInt()` 会抛 `IllegalArgumentException`。
 * 今天唯一的产出者 `readTemperature()` 的值来自 `Long / 1000f`，恒为有限值，
 * 所以这里**不加哨兵**（加了就是一段永远不执行的分支）。换了产出者要重新评估这一条。
 *
 * @param celsius 摄氏度；`null` = 读不到。
 * @return 毫摄氏度；`null` → `0`。
 */
fun celsiusToMilliC(celsius: Float?): Int {
    if (celsius == null) return 0
    return (celsius * 1000).roundToInt()
}
