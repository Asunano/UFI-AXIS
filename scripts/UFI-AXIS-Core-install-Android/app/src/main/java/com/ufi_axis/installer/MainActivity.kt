package com.ufi_axis.installer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.ufi_axis.installer.core.AddressParser
import com.ufi_axis.installer.core.AssetApkProvider
import com.ufi_axis.installer.core.NotificationHelper
import com.ufi_axis.installer.databinding.ActivityMainBinding
import com.ufi_axis.installer.logging.InstallLogger
import com.ufi_axis.installer.service.InstallerService
import com.ufi_axis.installer.state.InstallEngine
import com.ufi_axis.installer.state.InstallStage
import com.ufi_axis.installer.state.InstallState
import com.ufi_axis.installer.ui.LogAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 主界面：一屏搞定。
 *
 * 布局从上到下：
 * 1. 设备地址输入框 + 连接参数
 * 2. 大号状态卡（当前阶段 / 进度条 / 一句话状态）
 * 3. 操作按钮区（开始 / 取消 / 重试 / 分享日志）
 * 4. 可折叠的实时日志
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter

    /** 待确认安装的 APK 信息，供确认弹窗使用（避免闭包捕获旧值） */
    private var dialog: AlertDialog? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不影响安装，只是没有前台通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        InstallEngine.attach(applicationContext)
        NotificationHelper.ensureChannel(this)
        askNotificationPermission()

        setupViews()
        observeEngine()
        observeLogs()
        prefillAddress()
        checkAssetApk()
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private fun setupViews() {
        logAdapter = LogAdapter()
        binding.rvLogs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
            itemAnimator = null // 日志追加频繁，关掉动画更流畅
        }

        binding.btnStart.setOnClickListener {
            hideKeyboard()
            val address = binding.etAddress.text?.toString().orEmpty()
            InstallerService.startInstall(this, address)
        }

        binding.btnCancel.setOnClickListener {
            InstallerService.cancel(this)
        }

        binding.btnRetry.setOnClickListener {
            InstallEngine.reset()
            binding.btnStart.performClick()
        }

        binding.btnShareLog.setOnClickListener { shareLog() }

        binding.btnCopyLog.setOnClickListener { copyLog() }

        binding.btnToggleLog.setOnClickListener {
            val visible = binding.rvLogs.visibility == android.view.View.VISIBLE
            binding.rvLogs.visibility = if (visible) android.view.View.GONE else android.view.View.VISIBLE
            binding.btnToggleLog.text = getString(
                if (visible) R.string.action_show_log else R.string.action_hide_log
            )
        }

        binding.rvLogs.visibility = android.view.View.GONE
    }

    private fun prefillAddress() {
        binding.etAddress.setText(AddressParser.DEFAULT_IP)
        binding.etAddress.setSelection(binding.etAddress.text?.length ?: 0)
        binding.tvHint.text = getString(R.string.hint_address)
    }

    /**
     * 启动时检查内置 APK。
     * 如果 assets 是空的（release 工作流还没注入），直接把问题说清楚，
     * 免得用户点了开始才失败。
     */
    private fun checkAssetApk() {
        val apks = AssetApkProvider.listApks(this)
        if (apks.isEmpty()) {
            InstallLogger.warn("assets/${AssetApkProvider.ASSET_DIR}/ 为空，未检测到内置 APK")
            binding.tvApkInfo.text = getString(R.string.apk_missing)
            binding.tvApkInfo.setTextColor(ContextCompat.getColor(this, R.color.state_error))
            binding.btnStart.isEnabled = false
        } else {
            val core = AssetApkProvider.pickCore(apks)
            binding.tvApkInfo.text = if (core != null) {
                getString(R.string.apk_ready, core.name, formatSize(core.sizeBytes))
            } else {
                getString(R.string.apk_ambiguous, apks.joinToString { it.name })
            }
            binding.tvApkInfo.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (core != null) R.color.text_secondary else R.color.state_warning
                )
            )
            binding.btnStart.isEnabled = core != null
        }
    }

    private fun askNotificationPermission() {
        if (!NotificationHelper.hasPermission(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------
    // 状态订阅
    // ------------------------------------------------------------------

    private fun observeEngine() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallEngine.state.collectLatest { render(it) }
            }
        }
    }

    private fun render(state: InstallState) {
        // 状态标题与文案
        binding.tvStage.text = state.stage.title
        binding.tvStatus.text = state.statusText

        // 环形进度：确定进度显示百分比，无百分比阶段进入旋转不确定态
        when {
            state.progress in 0..100 -> {
                binding.ringProgress.setIndeterminate(false)
                binding.ringProgress.setProgress(state.progress)
                binding.ringProgress.visibility = android.view.View.VISIBLE
            }
            state.running -> {
                binding.ringProgress.setIndeterminate(true)
                binding.ringProgress.visibility = android.view.View.VISIBLE
            }
            else -> binding.ringProgress.visibility = android.view.View.GONE
        }

        // 关键信息：内容为空时隐藏整行，避免首屏出现两行空白
        if (state.address.isNotEmpty()) {
            binding.tvAddress.visibility = android.view.View.VISIBLE
            binding.tvAddress.text = getString(R.string.label_address, state.address)
        } else {
            binding.tvAddress.visibility = android.view.View.GONE
            binding.tvAddress.text = ""
        }
        if (state.packageName.isNotEmpty()) {
            binding.tvPackage.visibility = android.view.View.VISIBLE
            binding.tvPackage.text = getString(R.string.label_package, state.packageName)
        } else {
            binding.tvPackage.visibility = android.view.View.GONE
            binding.tvPackage.text = ""
        }

        // 按钮可见性
        binding.btnStart.isEnabled = !state.running && !state.finished
        binding.btnStart.visibility =
            if (state.finished) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnCancel.visibility =
            if (state.running) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnRetry.visibility =
            if (state.showResultActions) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnShareLog.visibility =
            if (state.showResultActions) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnCopyLog.visibility =
            if (state.showResultActions) android.view.View.VISIBLE else android.view.View.GONE

        // 输入框运行中锁住
        binding.etAddress.isEnabled = !state.running

        // 结果横幅：背景与文字随成功 / 失败语义态切换
        when {
            state.stage == InstallStage.DONE -> {
                binding.tvResult.visibility = android.view.View.VISIBLE
                binding.tvResult.setBackgroundResource(R.drawable.bg_banner_success)
                binding.tvResult.setTextColor(ContextCompat.getColor(this, R.color.state_success))
                binding.tvResult.text = getString(R.string.result_success)
            }
            state.stage == InstallStage.FAILED -> {
                binding.tvResult.visibility = android.view.View.VISIBLE
                binding.tvResult.setBackgroundResource(R.drawable.bg_banner_error)
                binding.tvResult.setTextColor(ContextCompat.getColor(this, R.color.state_error))
                binding.tvResult.text = state.errorMessage.ifEmpty {
                    getString(R.string.result_failed)
                }
            }
            else -> binding.tvResult.visibility = android.view.View.GONE
        }

        // 需要用户回应时弹窗
        if (state.awaitingConfirm && dialog?.isShowing != true) showConfirmDialog(state)
        if (state.stage != InstallStage.CONFIRM && dialog?.isShowing == true) {
            dialog?.dismiss()
        }
    }

    /** 安装前确认：展示 APK 名与大小，与 bat 的 Y/N 提示等价 */
    private fun showConfirmDialog(state: InstallState) {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_confirm_title)
            .setMessage(
                getString(
                    R.string.dialog_confirm_message,
                    state.pendingApkName,
                    formatSize(state.pendingApkSize),
                    state.address
                )
            )
            .setCancelable(false)
            .setPositiveButton(R.string.action_confirm) { _, _ -> InstallEngine.confirmInstall() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> InstallEngine.rejectInstall() }
            .show()
    }

    // ------------------------------------------------------------------
    // 日志订阅
    // ------------------------------------------------------------------

    private fun observeLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallLogger.lines.collect { line ->
                    val current = logAdapter.currentList.toMutableList()
                    current.add(line)
                    // 界面上最多保留 500 行，避免长跑内存增长
                    if (current.size > MAX_UI_LINES) current.removeAt(0)
                    logAdapter.submitList(current) {
                        binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
                    }
                }
            }
        }

        // 首次进入 / 旋转屏幕后回填已有日志（在订阅之后，避免漏掉新行）
        if (logAdapter.currentList.isEmpty()) {
            val history = InstallLogger.bufferedLines()
            if (history.isNotEmpty()) logAdapter.submitList(history)
        }
    }

    // ------------------------------------------------------------------
    // 日志导出
    // ------------------------------------------------------------------

    private fun shareLog() {
        InstallLogger.flush()
        val file = InstallLogger.lastLogFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.toast_no_log_file, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.action_share_log)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.toast_share_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun copyLog() {
        val text = InstallLogger.snapshot()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_log, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ufi-axis-install-log", text))
        Toast.makeText(this, R.string.toast_log_copied, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etAddress.windowToken, 0)
        binding.etAddress.clearFocus()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> getString(R.string.size_unknown)
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    companion object {
        private const val MAX_UI_LINES = 500
    }
}
