package com.ufi_axis_core.controller.carrier

import kotlinx.serialization.Serializable

/**
 * 账单磁盘缓存（2026-11-09）。
 *
 * 只存**最近一次成功拉取**的账单；登录状态、Cookie、卡号仍只存在账号体里。
 * 加密方式与账号相同（CarrierSessionStore 的 ufi_carrier prefs），因此：
 * - 后端重启后 App 打开仍能先看到数据；
 * - 会话失效时也能把这份数据当"最近一次数据"展示，同时标"需要登录"。
 *
 * [cachedAt] 是拉取时刻（epoch ms），不是套餐到期时间。
 */
@Serializable
data class CachedCarrierBill(
    val bill: CarrierBill,
    val cachedAt: Long,
)
