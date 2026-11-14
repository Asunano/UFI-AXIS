package com.ufi_axis_core.controller.carrier

import android.content.Context

/**
 * 自动登录的非敏感运行态：尝试次数、上次成功时间。
 *
 * ## 2026-11-05（P1）：按账号存
 *
 * 以前只有一份 `attempts` / `last_login_at`。多账号之后每个号有自己的日上限与重登间隔，
 * 混在一起会让"A 号今天已经试了 8 次"把 B 号也锁死。
 *
 * 键格式：`attempts_<accountId>` / `last_login_<accountId>`。
 * accountId 是 hash/随机串，**不含手机号明文**；本文件本来就在备份排除表里
 * （`ufi_carrier_auto`），换机后重置是对的。
 */
class CarrierAutoLoginStore(context: Context) {
    private val prefs = context.getSharedPreferences("ufi_carrier_auto", Context.MODE_PRIVATE)

    fun attemptsToday(accountId: String, now: Long = System.currentTimeMillis()): List<Long> =
        // 与设备总额度同一口径：本地自然日，不是滚动 24h。
        read(accountId).filter { it >= startOfLocalDay(now) }

    fun recordAttempt(accountId: String, now: Long = System.currentTimeMillis()) {
        val list = (read(accountId) + now).filter { it > now - 86_400_000L }
        prefs.edit().putString(attemptsKey(accountId), list.joinToString(",")).commit()
    }

    fun lastLoginAt(accountId: String): Long = prefs.getLong(lastLoginKey(accountId), 0L)

    fun setLastLoginAt(accountId: String, value: Long) {
        prefs.edit().putLong(lastLoginKey(accountId), value).commit()
    }

    /** 删除账号时清掉该 id 的尝试计数与上次成功时间（同号重加不继承旧状态）。 */
    fun clear(accountId: String) {
        prefs.edit()
            .remove(attemptsKey(accountId))
            .remove(lastLoginKey(accountId))
            .commit()
    }

    private fun read(accountId: String): List<Long> = prefs.getString(attemptsKey(accountId), "")
        .orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    private fun attemptsKey(accountId: String) = "attempts_$accountId"

    private fun lastLoginKey(accountId: String) = "last_login_$accountId"
}
