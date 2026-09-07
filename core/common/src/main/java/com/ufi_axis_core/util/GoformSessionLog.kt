package com.ufi_axis_core.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Goform 会话事件日志 — 用户可直接查看（诊断"官方后台被挤下线"问题）。
 *
 * 写入 /sdcard/Download/UFI-AXIS/log/core/goform-session.log（区别于 AppLogger 的
 * 应用私有目录 /data/ufiaxis/logs/，文件管理器可直接访问）。
 *
 * 记录内容（不受 debugMode 影响，但受 [AppLogger.isLogEnabled] 总开关约束）：
 * - 服务启动标记（版本 + 安装时间，用于确认部署的构建）
 * - 每次 goform LOGIN（含调用方 caller 追踪）/ 登录结果
 * - 每次 LOGOUT 释放
 * - 被顶检测与让位退避
 * - 每次 goform 写操作（goformId + caller）
 *
 * 正常状态下（空闲）该文件除启动标记外应无任何 LOGIN 行——出现 LOGIN 即可从
 * caller= 字段直接定位来源。
 */
object GoformSessionLog {

    private val dir = File("/sdcard/Download/UFI-AXIS/log/core")
    private val logFile get() = File(dir, "goform-session.log")

    /** 单文件上限 512KB，超限保留尾部 100KB（诊断场景足够，防止无限增长）。 */
    private const val MAX_BYTES = 512L * 1024
    private const val KEEP_BYTES = 100L * 1024

    @Synchronized
    fun log(message: String) {
        // 日志总开关：这条旁路不经 AppLogger.log()，必须自己判一次，否则「关闭后不记录任何日志」不成立
        if (!AppLogger.isLogEnabled()) return
        try {
            if (!dir.exists() && !dir.mkdirs()) return
            val f = logFile
            if (f.exists() && f.length() > MAX_BYTES) {
                val bytes = f.readBytes()
                val tail = bytes.copyOfRange((bytes.size - KEEP_BYTES.toInt()).coerceAtLeast(0), bytes.size)
                f.writeBytes(tail)
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            f.appendText("[$ts] $message\n")
        } catch (_: Exception) {
            // 日志失败静默（外部存储不可用等），不影响业务
        }
    }

    /** 服务启动标记：版本 + 最后安装时间（重装会更新 lastUpdateTime，可据此确认新构建已部署）。 */
    @Synchronized
    fun logStartupMarker(versionName: String, lastUpdateTime: Long) {
        val installTs = if (lastUpdateTime > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastUpdateTime))
        } else "unknown"
        log("=== UFI-AXIS-Core start | version=$versionName | apkLastUpdateTime=$installTs ===")
    }
}
