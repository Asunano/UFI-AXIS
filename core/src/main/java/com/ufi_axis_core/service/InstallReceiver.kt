package com.ufi_axis_core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ufi_axis_core.util.AppLogger
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 接收来自 ufi_update.sh / AppManager 的安装请求，在 app 进程内通过 Java PackageInstaller API 执行安装。
 *
 * 背景：shell pm install 在某些 ROM 上触发 AppOpsService.checkPackage NPE（uid=2000 的 shell
 * 用户在 AppOps 注册表中找不到对应 package），所有 shell 策略（cmd/pm/pm --full）均失败。
 * 本 receiver 在 app 自身进程内执行 PackageInstaller API，调用方 UID 为 app UID（非 shell），
 * 不触发该 NPE。app 作为系统应用拥有 INSTALL_PACKAGES 权限，可静默安装。
 *
 * 调用方式（from shell）:
 *   am broadcast -a com.ufi_axis_core.action.INSTALL_APK \
 *     -n com.ufi_axis_core/.service.InstallReceiver --es "apk_path" "/data/local/tmp/..."
 *
 * 结果写入: /data/local/tmp/ufi_install_result.txt（SUCCESS / FAILED:<message>）
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallReceiver"
        const val ACTION_INSTALL = "com.ufi_axis_core.action.INSTALL_APK"
        const val EXTRA_APK_PATH = "apk_path"
        const val RESULT_PATH = "/data/local/tmp/ufi_install_result.txt"

        /**
         * 在 app 进程内通过 PackageInstaller API 安装 APK（同步阻塞，最长 120s）。
         * 注意：此方法会阻塞当前线程，调用方应确保不在主线程调用。
         */
        fun installViaPackageInstaller(context: Context, apkPath: String): Pair<Boolean, String> {
            AppLogger.i(TAG, "[1/6] starting, apkPath=$apkPath")
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                AppLogger.e(TAG, "[1/6] APK not found: $apkPath")
                return false to "APK not found: $apkPath"
            }
            AppLogger.i(TAG, "[1/6] APK exists, size=${apkFile.length()}")

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            try {
                params.setSize(apkFile.length())
            } catch (_: Exception) {}

            val latch = CountDownLatch(1)
            var resultSuccess = false
            var resultMessage = "unknown"

            // Step 2: createSession
            AppLogger.i(TAG, "[2/6] creating session...")
            val sessionId = try {
                installer.createSession(params)
            } catch (e: Exception) {
                AppLogger.e(TAG, "[2/6] createSession failed: ${e.javaClass.simpleName}: ${e.message}")
                return false to "createSession failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            AppLogger.i(TAG, "[2/6] session created, id=$sessionId")

            // Step 3: openSession
            AppLogger.i(TAG, "[3/6] opening session...")
            val session = try {
                installer.openSession(sessionId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "[3/6] openSession failed: ${e.javaClass.simpleName}: ${e.message}")
                return false to "openSession failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            AppLogger.i(TAG, "[3/6] session opened")

            // Step 4: write APK data
            AppLogger.i(TAG, "[4/6] writing APK data (${apkFile.length()} bytes)...")
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
                        AppLogger.i(TAG, "[4/6] wrote $total bytes")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "[4/6] write failed: ${e.javaClass.simpleName}: ${e.message}")
                try { session.close() } catch (_: Exception) {}
                return false to "write failed: ${e.javaClass.simpleName}: ${e.message}"
            }

            // Step 5: register callback + commit
            AppLogger.i(TAG, "[5/6] registering callback and committing...")
            try {
                val callbackIntent = Intent(context, InstallReceiver::class.java).apply {
                    action = "com.ufi_axis_core.action.INSTALL_RESULT"
                }
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    context, sessionId, callbackIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                )
                installer.registerSessionCallback(object : PackageInstaller.SessionCallback() {
                    override fun onCreated(sid: Int) {
                        AppLogger.d(TAG, "callback onCreated sid=$sid")
                    }
                    override fun onBadgingChanged(sid: Int) {
                        AppLogger.d(TAG, "callback onBadgingChanged sid=$sid")
                    }
                    override fun onActiveChanged(sid: Int, active: Boolean) {
                        AppLogger.d(TAG, "callback onActiveChanged sid=$sid active=$active")
                    }
                    override fun onProgressChanged(sid: Int, progress: Float) {
                        AppLogger.d(TAG, "callback onProgressChanged sid=$sid progress=$progress")
                    }
                    override fun onFinished(sid: Int, success: Boolean) {
                        AppLogger.i(TAG, "callback onFinished sid=$sid expected=$sessionId success=$success")
                        if (sid == sessionId) {
                            resultSuccess = success
                            resultMessage = if (success) "SUCCESS" else "FAILED:session_callback"
                            latch.countDown()
                        }
                    }
                }, Handler(Looper.getMainLooper()))

                session.commit(pendingIntent.intentSender)
                AppLogger.i(TAG, "[5/6] commit sent, waiting for callback...")
            } catch (e: Exception) {
                AppLogger.e(TAG, "[5/6] commit failed: ${e.javaClass.simpleName}: ${e.message}")
                try { session.close() } catch (_: Exception) {}
                return false to "commit failed: ${e.javaClass.simpleName}: ${e.message}"
            }

            // Step 6: wait for result
            AppLogger.i(TAG, "[6/6] waiting for install result (max 120s)...")
            val completed = latch.await(120, TimeUnit.SECONDS)
            if (!completed) {
                AppLogger.e(TAG, "[6/6] TIMEOUT waiting for install result")
                return false to "timeout waiting for install result"
            }
            AppLogger.i(TAG, "[6/6] install completed: success=${resultSuccess} msg=$resultMessage")
            return resultSuccess to resultMessage
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        AppLogger.i(TAG, "onReceive action=$action")

        when (action) {
            ACTION_INSTALL -> {
                val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
                if (apkPath == null) {
                    AppLogger.e(TAG, "no apk_path extra!")
                    writeResult("FAILED:no_apk_path")
                    return
                }
                // goAsync 延长 broadcast 生命周期，后台线程执行安装（最长 120s），
                // 避免阻塞主线程导致 ANR
                val pendingResult = goAsync()
                AppLogger.i(TAG, "received install request: $apkPath, starting background thread")
                Thread {
                    try {
                        val (success, message) = installViaPackageInstaller(context, apkPath)
                        AppLogger.i(TAG, "final result: success=$success msg=$message")
                        writeResult(message)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "install exception: ${e.javaClass.simpleName}: ${e.message}", e)
                        writeResult("FAILED:exception:${e.message}")
                    } finally {
                        try { pendingResult.finish() } catch (_: Exception) {}
                    }
                }.start()
            }
            "com.ufi_axis_core.action.INSTALL_RESULT" -> {
                // PackageInstaller session commit 结果回调
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                AppLogger.i(TAG, "INSTALL_RESULT callback: status=$status msg=$msg")
                
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        AppLogger.w(TAG, "Install requires user confirmation: $msg")
                        // 2026-08-23 修复：如果是用户确认，不立即写失败，脚本或服务会继续等待
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        AppLogger.i(TAG, "Session commit success")
                        writeResult("SUCCESS")
                    }
                    else -> {
                        AppLogger.e(TAG, "Session commit failed: status=$status msg=$msg")
                        writeResult("FAILED:$msg")
                    }
                }
            }
            else -> {
                AppLogger.d(TAG, "onReceive: unhandled action=$action")
            }
        }
    }

    private fun writeResult(result: String) {
        try {
            File(RESULT_PATH).writeText(result)
            AppLogger.i(TAG, "result file written: $result")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write result file: ${e.message}")
            Log.w(TAG, "Failed to write result: ${e.message}")
        }
    }
}
