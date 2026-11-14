package com.ufi_axis.installer.core

import com.ufi_axis.adbcore.AdbClient

/**
 * 权限授予。
 *
 * 清单的**唯一真源是 core 的 AndroidManifest**（`core/src/main/AndroidManifest.xml`）：
 * 这里只列 core 声明过、且需要 shell 侧授权的那些。core 加了新权限就必须同步这里，
 * 否则装完是「装上了但功能用不了」。给 core 没声明的权限做 grant 是无效动作
 * （`pm grant` 会报 not a requested permission），只会在日志里留下误导性的失败行。
 *
 * 授权方式分两类：
 * - `pm grant`：dangerous 级运行时权限
 * - `appops set`：appop 控制的特殊权限（`pm grant` 对它们必然失败，所以直接走 appops）
 */
object PermissionGranter {

    /**
     * 一项待授权限。
     *
     * @param name      权限短名（不带 `android.permission.` 前缀）
     * @param appop     appops 操作名，null 表示与 [name] 同名。
     *                  注意 `PACKAGE_USAGE_STATS` 的 appop 叫 `GET_USAGE_STATS`，两者不同名。
     * @param pmGrant   是否先尝试 `pm grant`
     * @param minSdk    最低适用 API（低于它的设备上这条权限不存在，跳过而不是报失败）
     * @param maxSdk    最高适用 API（高于它的设备上该权限已被取代，如 WRITE_EXTERNAL_STORAGE）
     * @param note      日志里给人看的用途说明
     */
    data class Perm(
        val name: String,
        val appop: String? = null,
        val pmGrant: Boolean = true,
        val minSdk: Int = 0,
        val maxSdk: Int = Int.MAX_VALUE,
        val note: String = ""
    )

    /** 与 core manifest 对齐的权限清单（顺序按用途分组，便于和日志对照） */
    val PERMISSIONS: List<Perm> = listOf(
        // ── 存储 ──
        Perm("READ_EXTERNAL_STORAGE", maxSdk = 32, note = "读外部存储（Android 13 起由 READ_MEDIA_* 取代）"),
        Perm("WRITE_EXTERNAL_STORAGE", maxSdk = 29, note = "写外部存储（Android 11 起失效）"),
        Perm("MANAGE_EXTERNAL_STORAGE", pmGrant = false, minSdk = 30, note = "全盘文件管理"),

        // ── 媒体库（core 2026-09-16 起的媒体中心需要，MANAGE_EXTERNAL_STORAGE 不等价） ──
        Perm("READ_MEDIA_IMAGES", minSdk = 33, note = "媒体中心：图片"),
        Perm("READ_MEDIA_VIDEO", minSdk = 33, note = "媒体中心：视频"),
        Perm("READ_MEDIA_AUDIO", minSdk = 33, note = "媒体中心：音频"),

        // ── 位置（Android 12+ CellInfo 采集需要） ──
        Perm("ACCESS_FINE_LOCATION", note = "精确位置 / CellInfo"),
        Perm("ACCESS_COARSE_LOCATION", note = "粗略位置"),

        // ── 电话 / 网络信息 ──
        Perm("READ_PHONE_STATE", note = "SIM 与网络状态"),
        Perm("READ_PHONE_NUMBERS", minSdk = 26, note = "读取本机号码"),

        // ── 短信 ──
        Perm("SEND_SMS", note = "发送短信"),
        Perm("READ_SMS", note = "读取短信"),

        // ── 通知 ──
        Perm("POST_NOTIFICATIONS", minSdk = 33, note = "前台服务通知"),

        // ── 安装 / 用量 / 闹钟（都是 appop 控制，pm grant 必失败） ──
        Perm("REQUEST_INSTALL_PACKAGES", pmGrant = false, note = "应用安装"),
        Perm(
            "PACKAGE_USAGE_STATS",
            appop = "GET_USAGE_STATS",
            pmGrant = false,
            note = "流量统计（NetworkStatsManager）"
        ),
        Perm(
            "SCHEDULE_EXACT_ALARM",
            pmGrant = false,
            minSdk = 31,
            note = "精确闹钟（服务自恢复依赖它）"
        ),
    )

    enum class Outcome { GRANTED, FAILED, NOT_APPLICABLE }

    data class Result(
        val permission: String,
        val outcome: Outcome,
        /** 失败原因或跳过原因；成功时为 null */
        val detail: String? = null
    ) {
        val granted: Boolean get() = outcome == Outcome.GRANTED
    }

    /**
     * 依次授权。单项失败不中断整体。
     *
     * 会先读一次设备 API level：低于/高于适用范围的权限直接标 [Outcome.NOT_APPLICABLE]，
     * 免得把「这台设备上本来就没这个权限」报成失败，让日志分不清真假问题。
     *
     * @param onProgress (第几个, 总数, 权限名, 结果, 详情)
     */
    fun grantAll(
        client: AdbClient,
        pkg: String,
        onProgress: ((index: Int, total: Int, permission: String, outcome: Outcome, detail: String?) -> Unit)? = null
    ): List<Result> {
        val sdk = client.sdkInt()
        val results = mutableListOf<Result>()
        PERMISSIONS.forEachIndexed { index, perm ->
            val result = when {
                sdk != null && sdk < perm.minSdk ->
                    Result(perm.name, Outcome.NOT_APPLICABLE, "设备 API $sdk < ${perm.minSdk}")
                sdk != null && sdk > perm.maxSdk ->
                    Result(perm.name, Outcome.NOT_APPLICABLE, "设备 API $sdk > ${perm.maxSdk}")
                else -> try {
                    val ok = client.grantPermission(pkg, perm.name, perm.appop, perm.pmGrant)
                    Result(perm.name, if (ok) Outcome.GRANTED else Outcome.FAILED)
                } catch (e: Exception) {
                    Result(perm.name, Outcome.FAILED, e.message ?: e.javaClass.simpleName)
                }
            }
            results += result
            onProgress?.invoke(index + 1, PERMISSIONS.size, perm.name, result.outcome, result.detail)
        }
        return results
    }
}
