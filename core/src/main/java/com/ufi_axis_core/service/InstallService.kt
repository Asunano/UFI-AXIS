package com.ufi_axis_core.service

import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.LogPaths
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * APK 安装服务 — 接收 am startservice 请求，在 app 进程内通过 PackageInstaller API 安装。
 *
 * 背景：am broadcast 在某些 ROM 上 exit=255（兼容性问题），改用 am startservice 更可靠。
 * Service 启动不受 Android 14+ 广播限制影响，且 IntentService 天然在后台线程执行。
 *
 * 调用方式（from shell）:
 *   am startservice -a com.ufi_axis_core.action.INSTALL_APK \
 *     -n com.ufi_axis_core/.service.InstallService --es apk_path "/data/local/tmp/..."
 *
 * 结果写入: /data/local/tmp/ufi_install_result.txt
 */
class InstallService : IntentService("UfiInstallService") {

    companion object {
        private const val TAG = "InstallService"
        const val ACTION_INSTALL = "com.ufi_axis_core.action.INSTALL_APK"
        const val EXTRA_APK_PATH = "apk_path"
        const val RESULT_PATH = "/data/local/tmp/ufi_install_result.txt"

        /**
         * 分类存放的 install log 目录（与 watchdog/core/keepalive 一致，归到 Download/UFI-AXIS/log/）。
         * 不再写到 /Download/UFI-AXIS/ufi_install_pi.log（旧版遗留路径已重定向，避免散乱在根目录）。
         *
         * 路径取自 [LogPaths]（唯一真源），探测顺序与回退语义保持原样。
         */
        private val INSTALL_LOG_DIRS = LogPaths.dirCandidates(LogPaths.Component.INSTALL)
        private val INSTALL_LOG_FILE = "install.log"

        private fun installLogPath(): String {
            for (d in INSTALL_LOG_DIRS) {
                val dir = java.io.File(d)
                if (dir.exists() || dir.mkdirs()) return "$d/$INSTALL_LOG_FILE"
            }
            return "${INSTALL_LOG_DIRS.last()}/$INSTALL_LOG_FILE"
        }

        fun writeResult(result: String) {
            try {
                File(RESULT_PATH).writeText(result)
                AppLogger.i(TAG, "result file written: $result")
            } catch (e: Exception) {
                AppLogger.e(TAG, "failed to write result: ${e.message}")
            }
        }

        /** 写诊断日志到文件（不依赖 AppLogger，确保能在 update log 之外独立查看） */
        private fun flog(msg: String) {
            try {
                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                val f = java.io.File(installLogPath())
                f.parentFile?.mkdirs()
                f.appendText("[$ts] $msg\n")
            } catch (_: Exception) {}
        }

        /**
         * 在 app 进程内通过 PackageInstaller API 安装 APK（同步阻塞）。
         */
        fun installViaPackageInstaller(context: Context, apkPath: String): Pair<Boolean, String> {
            // 每次调用重建日志文件
            try { File(installLogPath()).writeText("=== installViaPackageInstaller start ===\n") } catch (_: Exception) {}

            flog("[1/6] starting, apkPath=$apkPath")
            AppLogger.i(TAG, "[1/6] starting, apkPath=$apkPath")

            // 前置检查：安装权限（Android 8+）
            // 特权系统应用持有 INSTALL_PACKAGES 时即可静默安装，不应因 REQUEST_INSTALL_PACKAGES
            // （appops 可能被意外重置为默认）被拒绝而 abort——否则会回退到易触发 NPE 的 shell pm。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val pm = context.packageManager
                val hasInstallPerm = try {
                    pm.checkPermission("android.permission.INSTALL_PACKAGES", context.packageName) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                } catch (_: Exception) { false }
                val canRequest = try { pm.canRequestPackageInstalls() } catch (_: Exception) { false }
                if (!canRequest && !hasInstallPerm) {
                    flog("[1/6] no install permission (canRequestPackageInstalls=false, INSTALL_PACKAGES not granted) — aborting")
                    AppLogger.w(TAG, "[1/6] no install permission")
                    return false to "no install permission (REQUEST_INSTALL_PACKAGES/INSTALL_PACKAGES)"
                }
                flog("[1/6] install permission OK (canRequest=$canRequest, INSTALL_PACKAGES=$hasInstallPerm)")
            }

            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                flog("[1/6] APK not found!"); return false to "APK not found: $apkPath"
            }
            flog("[1/6] APK exists, size=${apkFile.length()}")

            // 检查 INSTALL_PACKAGES 权限
            val hasInstallPerm = try {
                val pm = context.packageManager
                val permResult = pm.checkPermission("android.permission.INSTALL_PACKAGES", context.packageName)
                permResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) { "error:${e.message}" }
            flog("[1/6] INSTALL_PACKAGES permission=$hasInstallPerm")

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            try { params.setSize(apkFile.length()) } catch (_: Exception) {}

            val latch = CountDownLatch(1)
            var resultSuccess = false
            var resultMessage = "unknown"

            flog("[2/6] creating session...")
            val sessionId = try {
                installer.createSession(params)
            } catch (e: Exception) {
                flog("[2/6] createSession FAILED: ${e.javaClass.simpleName}: ${e.message}")
                AppLogger.e(TAG, "[2/6] createSession failed: ${e.javaClass.simpleName}: ${e.message}")
                return false to "createSession failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            flog("[2/6] session created, id=$sessionId")

            flog("[3/6] opening session...")
            val session = try {
                installer.openSession(sessionId)
            } catch (e: Exception) {
                flog("[3/6] openSession FAILED: ${e.javaClass.simpleName}: ${e.message}")
                AppLogger.e(TAG, "[3/6] openSession failed: ${e.javaClass.simpleName}: ${e.message}")
                return false to "openSession failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            flog("[3/6] session opened")

            flog("[4/6] writing APK data (${apkFile.length()} bytes)...")
            try {
                FileInputStream(apkFile).use { input ->
                    session.openWrite("install", 0, apkFile.length()).use { out ->
                        val buf = ByteArray(65536)
                        var total = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            total += n
                        }
                        session.fsync(out)
                        flog("[4/6] wrote $total bytes OK")
                    }
                }
            } catch (e: Exception) {
                flog("[4/6] write FAILED: ${e.javaClass.simpleName}: ${e.message}")
                AppLogger.e(TAG, "[4/6] write failed: ${e.javaClass.simpleName}: ${e.message}")
                try { session.close() } catch (_: Exception) {}
                return false to "write failed: ${e.javaClass.simpleName}: ${e.message}"
            }

            flog("[5/6] registering callback and committing...")
            try {
                val callbackIntent = Intent(context, InstallReceiver::class.java).apply {
                    action = "com.ufi_axis_core.action.INSTALL_RESULT"
                }
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    context, sessionId, callbackIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                )
                installer.registerSessionCallback(object : PackageInstaller.SessionCallback() {
                    override fun onCreated(sid: Int) { flog("  callback onCreated sid=$sid") }
                    override fun onBadgingChanged(sid: Int) { flog("  callback onBadgingChanged sid=$sid") }
                    override fun onActiveChanged(sid: Int, active: Boolean) { flog("  callback onActiveChanged sid=$sid active=$active") }
                    override fun onProgressChanged(sid: Int, progress: Float) { }
                    override fun onFinished(sid: Int, success: Boolean) {
                        flog("  callback onFinished sid=$sid expected=$sessionId success=$success")
                        AppLogger.i(TAG, "callback onFinished sid=$sid expected=$sessionId success=$success")
                        if (sid == sessionId) {
                            resultSuccess = success
                            resultMessage = if (success) "SUCCESS" else "FAILED:session_callback"
                            if (success) writeResult("SUCCESS") else writeResult(resultMessage)
                            latch.countDown()
                        }
                    }
                }, Handler(Looper.getMainLooper()))

                session.commit(pendingIntent.intentSender)
                flog("[5/6] commit sent OK, waiting for callback...")
            } catch (e: Exception) {
                flog("[5/6] commit FAILED: ${e.javaClass.simpleName}: ${e.message}")
                AppLogger.e(TAG, "[5/6] commit failed: ${e.javaClass.simpleName}: ${e.message}")
                try { session.close() } catch (_: Exception) {}
                return false to "commit failed: ${e.javaClass.simpleName}: ${e.message}"
            }

            flog("[6/6] waiting for callback (max 120s)...")
            val completed = latch.await(120, TimeUnit.SECONDS)
            if (!completed) {
                flog("[6/6] TIMEOUT — PI API did not respond within 120s")
                return false to "timeout waiting for install result"
            }
            flog("[6/6] completed: success=$resultMessage")
            return resultSuccess to resultMessage
        }
    }

    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) {
            AppLogger.w(TAG, "null intent")
            return
        }
        val action = intent.action
        AppLogger.i(TAG, "onHandleIntent action=$action")

        when (action) {
            ACTION_INSTALL -> {
                val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
                if (apkPath == null) {
                    AppLogger.e(TAG, "no apk_path extra!")
                    writeResult("FAILED:no_apk_path")
                    return
                }
                AppLogger.i(TAG, "install request: $apkPath")
                try {
                    val (success, message) = installViaPackageInstaller(this, apkPath)
                    AppLogger.i(TAG, "result: success=$success msg=$message")
                    writeResult(message)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "exception: ${e.javaClass.simpleName}: ${e.message}", e)
                    writeResult("FAILED:exception:${e.message}")
                }
            }
        }
    }
}
