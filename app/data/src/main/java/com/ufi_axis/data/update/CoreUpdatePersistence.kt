package com.ufi_axis.data.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Core 自更新「进行中」标记的持久化契约。
 *
 * 背景（前端重连接管，2026-09-13）：用户在 App 端点「更新 Core」后，后端会**脱离前端独立**
 * 下载 + 校验 + 安装 + 重启（[com.ufi_axis_core.api.update.UpdateManager] 跑在 core 进程自身的作用域，
 * 前端只发一次 `POST /api/update/check` 并轮询进度）。因此 App 被杀不会取消更新。
 *
 * 但前端的 [com.ufi_axis.viewmodel.module.UpdatePromptModule] 状态全是内存态，App 被杀即清零，
 * 重开后没有这个标记就无法把「后端仍在更」的进度接管显示出来，用户误以为更新失败/取消。
 *
 * 约定：
 * - 用户确认更新 Core（`ToolsModule.triggerDeviceUpdate()` 成功触发）时置 `true`；
 * - 前端冷启动读到 `true` → [UpdatePromptModule] 据此恢复轮询并接管进度显示；
 * - `updateDeviceState` 状态到达 `done`/`failed` 终态时清回 `false`。
 *
 * 接口放在 data 层（最低层），由 viewmodel 层依赖，避免 data→viewmodel 反向依赖。
 */
interface CoreUpdatePersistence {
    /** 是否有一次 Core 自更新正在进行（后端已脱离前端执行，App 可能被杀过）。 */
    fun isCoreUpdating(): Boolean

    /** 写入标记：`true`=进行中；`false`=已终态 / 未开始。 */
    fun setCoreUpdating(value: Boolean)
}

/**
 * 基于 `SharedPreferences` 的实现，复用与 [com.ufi_axis.util.AppPreferences] 相同的
 * `ufi_axis_prefs` 文件（多句柄共享同一文件，读写的就是同一份真源）。
 */
class SharedPreferencesCoreUpdatePersistence(context: Context) : CoreUpdatePersistence {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isCoreUpdating(): Boolean = prefs.getBoolean(KEY, false)

    override fun setCoreUpdating(value: Boolean) {
        prefs.edit().putBoolean(KEY, value).apply()
    }

    private companion object {
        const val PREFS_NAME = "ufi_axis_prefs"
        const val KEY = "core_update_in_progress"
    }
}
