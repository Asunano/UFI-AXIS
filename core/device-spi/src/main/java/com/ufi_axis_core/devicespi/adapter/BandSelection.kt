package com.ufi_axis_core.devicespi.adapter

/**
 * 「这次要锁哪些频段」的意图（2026-09-25 批 A2b）。
 *
 * ## 为什么不是一个 String
 *
 * 改造前调用点（`NetworkController.lockBands` 的 unlockAll 分支）是这么做的：
 * 先问 `GoformNetworkClient.lteAllBands()` 拿一串**下发给设备的频段掩码**，再把它当普通
 * 频段列表发下去。那串掩码是 goform 的实现细节 —— 把它放到协议无关的契约上，
 * 等于规定「别人家的设备也得用掩码串表达全部频段」。
 *
 * 所以 [NetworkControl] 上收的是**意图**：[All] 表示「这个 RAT 不加限制」，
 * 掩码串只出现在 adapter 实现里（goform 系的那份仍旧去问
 * `GoformNetworkClient.lteAllBands()` / `nrAllBands()`，取值逐字未变）。
 *
 * ## 为什么没有「保持不变」这一档
 *
 * 现有唯一调用点每次都同时下发 LTE 与 NR 两条命令（参考项目的 `Promise.all`，
 * 「未选某个 RAT」表达为 [Only] 一个空串 = 清除该 RAT 的限制），没有「这个 RAT 不动」
 * 这条路径。按批 A1 定下的口径（[DeviceAdapter] 的类 KDoc）**不预先声明没人用的成员** ——
 * 真需要「不动」语义时再加一档，连同它的实现与调用点一起落地。
 */
sealed interface BandSelection {

    /**
     * 全部频段 = 解除该 RAT 的频段限制。
     *
     * **它是意图，不是取值**：具体要不要下发一串东西、下发什么，由 adapter 决定。
     * goform 系发的是 profile 登记的频段全集掩码；profile 没登记时那份实现会折叠成空串
     * 并留一行 WARN（唯一的折叠归属地在 `GoformNetworkClient.lteAllBands()`，
     * 不要在别处重新实现一份）。
     */
    data object All : BandSelection

    /**
     * 明确的频段列表。
     *
     * @param bands **逗号分隔的频段号**（如 `"1,3,5"`）；空串 = 不发限制（= 清除该 RAT 的限制）。
     *   取值格式是既有的对外契约（route 收到的就是这个形状），本批**不动**它 ——
     *   把它收紧成 `List<Int>` 会改动 route 与客户端的入参形状，不属于纯接缝批次。
     *   值域校验（频段号必须是 1..255 的纯数字）仍在设备侧实现里，非法值不下发、
     *   以 [com.ufi_axis_core.devicespi.WriteOutcome.Rejected] 返回原因。
     */
    data class Only(val bands: String) : BandSelection
}
