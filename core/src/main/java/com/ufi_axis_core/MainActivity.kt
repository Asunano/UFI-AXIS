package com.ufi_axis_core

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ufi_axis_core.service.BackendService
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var tvPairingStatus: TextView
    private lateinit var tvPairingLimit: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvHint: TextView
    private lateinit var storageCard: View
    private lateinit var installCard: View
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQUEST_CODE_NOTIFICATION = 1001
        private const val REQUEST_CODE_CORE_PERMISSIONS = 1002

        /** 后端运行所需的全部运行时权限 */
        val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS
        )
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ══════════════════════════════════════════════════════
        // 全局异常保护：任何 onCreate 阶段的未捕获异常都弹窗显示
        // ══════════════════════════════════════════════════════
        try {
            initViews()
            initLogic()
        } catch (e: Exception) {
            showCrashDialog("启动失败", e)
            return
        }
        
        // 为后台线程（Handler、Service 回调等）安装兜底异常捕获
        // 2026-08-22 修复「服务无法链接」：本处理器曾覆盖 Application 层的处理器且
        // 无良性过滤——Ktor Netty 取消请求协程时偶发 kotlinx.coroutines 的
        // CompletionHandlerException（已知良性），弹"后台线程崩溃"窗并吞异常，
        // event loop 线程死亡导致 HTTP 服务器瘫痪。现对齐 Application 层：
        // ① 良性协程异常仅记日志跳过；② 其余异常弹窗后 rethrow 给原 default
        // handler（保持 Application 层崩溃落盘 + 进程退出语义）。
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val isBenign = throwable.javaClass.name == "kotlinx.coroutines.CompletionHandlerException"
            if (isBenign) {
                AppLogger.w("MainActivity", "Coroutine completion handler issue (benign) on '${thread.name}': ${throwable.message}")
                return@setDefaultUncaughtExceptionHandler
            }
            AppLogger.e("MainActivity", "Uncaught in thread '${thread.name}': ${throwable.javaClass.name}: ${throwable.message}")
            runOnUiThread { showCrashDialog("后台线程崩溃 (${thread.name})", throwable) }
            // rethrow：交还原处理器（Application 层：崩溃日志落盘 + 系统默认退出/重启）
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun initViews() {
        setContentView(R.layout.activity_main)
        settings = AppSettings.getInstance(this)
        statusTitle = findViewById(R.id.statusTitle)
        statusSubtitle = findViewById(R.id.statusSubtitle)
        statusCard = findViewById(R.id.statusCard)
        tvPairingStatus = findViewById(R.id.tvPairingStatus)
        tvPairingLimit = findViewById(R.id.tvPairingLimit)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvHint = findViewById(R.id.tvHint)
        storageCard = findViewById(R.id.storageCard)
        installCard = findViewById(R.id.installCard)
        // 版本号原来写死在 layout 里（"v0.1"），装了新版也还是显示 0.1。
        // BuildConfig 由 core/build.gradle.kts 从 version.json→gradle.properties 注入，是唯一版本源。
        findViewById<TextView>(R.id.tvVersion).text =
            "UFI-AXIS-Core v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    private fun initLogic() {
        val btnStorage = findViewById<Button>(R.id.btnStorage)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        updateUI()

        // 存储权限检查 (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                storageCard.visibility = View.VISIBLE
            }
            btnStorage.setOnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    AppLogger.w("MainActivity", "Failed to open storage permission settings: ${e.message}")
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }

        // 安装权限检查（在线更新需要 REQUEST_INSTALL_PACKAGES）
        val btnInstall = findViewById<Button>(R.id.btnInstall)
        checkInstallPermission()
        btnInstall.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                AppLogger.w("MainActivity", "Failed to open install permission settings: ${e.message}")
            }
        }

        btnStart.setOnClickListener {
            ensureAllPermissionsThenStart { startBackendService() }
        }

        btnStop.setOnClickListener {
            try {
                BackendService.stop(this)
                updateUI()
            } catch (e: Exception) {
                showCrashDialog("停止服务失败", e)
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 自动启动服务（先检查全部权限）
        if (settings.autoStartOnBoot && !BackendService.isRunning) {
            ensureAllPermissionsThenStart { startBackendServiceQuiet() }
        }
    }

    /**
     * 检查并请求后端核心权限（位置 + 电话状态）。
     * 这些权限是 TelephonyCollector 读取信号强度的前提。
     * @return true 如果全部已授予；false 如果正在请求中
     */
    private fun ensureCorePermissions(): Boolean {
        val missing = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_CODE_CORE_PERMISSIONS)
            return false
        }
        return true
    }

    /**
     * 确保通知权限 + 核心权限全部就绪后再启动服务。
     */
    private fun ensureAllPermissionsThenStart(startAction: () -> Unit) {
        // 先检查核心权限
        if (!ensureCorePermissions()) {
            pendingStartAction = startAction
            return
        }
        // 再检查通知权限
        if (!ensureNotificationPermission()) {
            pendingStartAction = startAction
            return
        }
        startAction()
    }

    /** 暂存启动动作，等权限回调后执行 */
    private var pendingStartAction: (() -> Unit)? = null

    /**
     * 检查并请求 POST_NOTIFICATIONS 权限（Android 13+）。
     * @return true 如果权限已授予或不需要（Android < 13）；false 如果正在请求中
     */
    private fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION
                )
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_NOTIFICATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    AppLogger.i("MainActivity", "POST_NOTIFICATIONS granted")
                } else {
                    AppLogger.w("MainActivity", "POST_NOTIFICATIONS denied — service notification may not appear")
                    Toast.makeText(this, "未授予通知权限，服务通知可能不会显示", Toast.LENGTH_LONG).show()
                }
                // 无论授权与否都执行暂存的启动动作
                pendingStartAction?.invoke()
                pendingStartAction = null
            }
            REQUEST_CODE_CORE_PERMISSIONS -> {
                val allGranted = grantResults.isNotEmpty() && grantResults.all {
                    it == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) {
                    AppLogger.i("MainActivity", "Core permissions granted (location + phone_state)")
                } else {
                    val denied = permissions.zip(grantResults.toList())
                        .filter { it.second != android.content.pm.PackageManager.PERMISSION_GRANTED }
                        .map { it.first }
                    AppLogger.w("MainActivity", "Core permissions denied: $denied")
                    Toast.makeText(this, "未授予核心权限，信号强度等数据将不可用", Toast.LENGTH_LONG).show()
                }
                // 继续检查通知权限，然后执行启动
                pendingStartAction?.let { action ->
                    pendingStartAction = null
                    if (ensureNotificationPermission()) {
                        action()
                    } else {
                        pendingStartAction = action
                    }
                }
            }
        }
    }

    private fun startBackendService() {
        try {
            BackendService.start(this)
            updateUI()
            Toast.makeText(this, "正在启动后端服务...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showCrashDialog("启动服务失败", e)
        }
    }

    private fun startBackendServiceQuiet() {
        try {
            BackendService.start(this)
            updateUI()
        } catch (e: Exception) {
            statusSubtitle.text = "自动启动失败: ${e.message}"
            AppLogger.e("MainActivity", "Auto-start failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        // 回到前台时重新检查存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageCard.visibility = if (Environment.isExternalStorageManager()) View.GONE else View.VISIBLE
        }
        // 回到前台时重新检查安装权限
        checkInstallPermission()
        handler.postDelayed(refreshRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun checkInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = packageManager.canRequestPackageInstalls()
            installCard.visibility = if (canInstall) View.GONE else View.VISIBLE
        }
    }

    private fun updateUI() {
        val running = BackendService.isRunning
        if (running) {
            statusTitle.text = "后端服务运行中"
            statusSubtitle.text = "HTTP :${settings.port}  |  WS /ws/realtime"
            statusCard.setBackgroundResource(R.drawable.card_bg)
            tvHint.visibility = View.VISIBLE
        } else {
            statusTitle.text = "后端服务未运行"
            statusSubtitle.text = "点击下方按钮启动后端服务"
            statusCard.setBackgroundResource(R.drawable.card_bg)
            tvHint.visibility = View.GONE
        }
        btnStart.isEnabled = !running
        btnStop.isEnabled = running

        // 配对状态
        val count = settings.pairedFingerprints.size
        val enabled = settings.pairingEnabled
        val maxDevices = settings.pairingMaxDevices
        if (count == 0) {
            tvPairingStatus.text = "暂无配对设备"
        } else {
            tvPairingStatus.text = "已配对 $count 台设备"
        }
        if (enabled && maxDevices > 0) {
            tvPairingLimit.text = "限制: $count / $maxDevices"
        } else if (enabled) {
            tvPairingLimit.text = "限制: 未设置上限"
        } else {
            tvPairingLimit.text = "配对限制: 关闭（无限制）"
        }
    }

    /** 弹窗显示崩溃详情 */
    private fun showCrashDialog(title: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString().take(2000)
        AppLogger.e("MainActivity", title, throwable)
        
        runOnUiThread {
            try {
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("${throwable.javaClass.simpleName}: ${throwable.message}\n\n$stackTrace")
                    .setPositiveButton("确定") { _, _ -> }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                AppLogger.w("MainActivity", "Failed to show error dialog: ${e.message}")
                // 如果连 Dialog 都创建不了，用 Toast 兜底
                Toast.makeText(this, "$title: ${throwable.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
