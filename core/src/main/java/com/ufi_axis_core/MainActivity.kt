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
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvHint: TextView
    private lateinit var storageCard: View
    private val handler = Handler(Looper.getMainLooper())

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
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 先写日志
            AppLogger.e("MainActivity", "Uncaught in thread '${thread.name}': ${throwable.javaClass.name}: ${throwable.message}")
            // 弹窗显示
            runOnUiThread { showCrashDialog("后台线程崩溃 (${thread.name})", throwable) }
        }
    }

    private fun initViews() {
        setContentView(R.layout.activity_main)
        settings = AppSettings.getInstance(this)
        statusTitle = findViewById(R.id.statusTitle)
        statusSubtitle = findViewById(R.id.statusSubtitle)
        statusCard = findViewById(R.id.statusCard)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvHint = findViewById(R.id.tvHint)
        storageCard = findViewById(R.id.storageCard)
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

        btnStart.setOnClickListener {
            try {
                BackendService.start(this)
                updateUI()
                Toast.makeText(this, "正在启动后端服务...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                showCrashDialog("启动服务失败", e)
            }
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

        // 自动启动服务
        if (settings.autoStartOnBoot && !BackendService.isRunning) {
            try {
                BackendService.start(this)
                updateUI()
            } catch (e: Exception) {
                // 自动启动失败不弹窗，仅在标题显示
                statusSubtitle.text = "自动启动失败: ${e.message}"
                AppLogger.e("MainActivity", "Auto-start failed", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        // 回到前台时重新检查存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageCard.visibility = if (Environment.isExternalStorageManager()) View.GONE else View.VISIBLE
        }
        handler.postDelayed(refreshRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
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
