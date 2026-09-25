package com.ufi_axis.installer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ufi_axis.installer.core.AddressParser
import com.ufi_axis.installer.core.AssetApkProvider
import com.ufi_axis.installer.core.NotificationHelper
import com.ufi_axis.installer.core.PermissionGranter
import com.ufi_axis.installer.databinding.ActivityMainBinding
import com.ufi_axis.installer.logging.InstallLogger
import com.ufi_axis.installer.service.InstallerService
import com.ufi_axis.installer.state.InstallEngine
import com.ufi_axis.installer.state.InstallInteraction
import com.ufi_axis.installer.state.InstallStage
import com.ufi_axis.installer.state.InstallState
import com.ufi_axis.installer.remoteadb.RemoteAdbActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 主界面：分屏向导式（4 屏）。
 *
 * 屏 1 设备地址 → 屏 2 安装详情/确认 → 屏 3 安装过程 → 屏 4 结果。
 * 日志改为独立界面：顶栏「日志」按钮或结果页「查看日志」跳转 LogActivity。
 *
 * 引擎状态仍单向流出，界面只读；唯一的改动是：
 * 用户在「安装详情」页确认后，引擎内部的 CONFIRM 等待会被自动放行（[preConfirmed]）。
 * 其余交互（连接失败重试 / 改地址 / 手动包名）仍由对话框叠加在过程屏之上。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 界面需要弹出的交互对话框种类 */
    private enum class DialogKind { CONFIRM, CONNECT_DECISION, ADDRESS_CHANGE, MANUAL_PACKAGE }

    /** 当前正在展示的交互对话框类型，避免 render 反复重建同一对话框 */
    private var dialog: AlertDialog? = null
    private var currentDialog: DialogKind? = null

    /** 内置 APK 是否就绪：缺 APK 时禁用「继续」 */
    private var apkReady = false

    /** 用户在详情页是否已确认：用于自动放行引擎的 CONFIRM 等待 */
    private var preConfirmed = false

    /** 屏 1 录入、带到屏 2 展示并传给引擎的地址 */
    private var pendingAddress: String = ""

    /** 屏 2 展示、屏 4 结果复用的 APK 信息 */
    private var apkName: String = "?"
    private var apkSizeText: String = "?"

    /** 安装开始时间，用于结果页计算耗时 */
    private var installStartTime: Long = 0L

    private val colorPending: Int by lazy { ContextCompat.getColor(this, R.color.text_hint) }
    private val colorOnDot: Int by lazy { ContextCompat.getColor(this, R.color.white_text) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 拒绝不影响安装，但通知栏不会有进度，必须明确告诉用户，别让它变成「静默失效」
        if (!granted) {
            InstallLogger.warn("通知权限被拒绝：安装进度只能在本界面查看")
            Toast.makeText(this, R.string.toast_notification_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        InstallEngine.attach(applicationContext)
        NotificationHelper.ensureChannel(this)
        askNotificationPermission()

        setupViews()
        observeEngine()
        prefillAddress()
        checkAssetApk()
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private fun setupViews() {
        // 屏 1 → 屏 2
        binding.btnContinue.setOnClickListener { onContinue() }

        // 屏 1 → 开启远程 ADB（引导）
        binding.btnRemoteAdb.setOnClickListener {
            startActivity(Intent(this, RemoteAdbActivity::class.java))
        }

        // 屏 2 按钮
        binding.btnBack.setOnClickListener {
            preConfirmed = false
            goScreen(0)
        }
        binding.btnConfirm.setOnClickListener { onConfirm() }

        // 屏 3 取消
        binding.btnCancel.setOnClickListener { InstallerService.cancel(this) }

        // 屏 4 结果操作
        binding.btnRetry.setOnClickListener {
            preConfirmed = false
            installStartTime = 0L
            InstallEngine.reset()
        }
        binding.btnShareLog.setOnClickListener { shareLog() }
        binding.btnCopyLog.setOnClickListener { copyLog() }

        // 日志入口：跳转独立日志界面
        binding.btnViewLog.setOnClickListener { openLog() }
        binding.btnLogOpen.setOnClickListener { openLog() }
    }

    private fun onContinue() {
        hideKeyboard()
        pendingAddress = binding.etAddress.text?.toString().orEmpty()
        binding.tvDetailAddr.text = getString(
            R.string.label_address,
            pendingAddress.ifEmpty { AddressParser.DEFAULT_IP }
        )
        goScreen(1)
    }

    private fun onConfirm() {
        preConfirmed = true
        // 同步告诉引擎：CONFIRM 阶段无需再等界面回应，避免等待与放行抢先后顺序
        InstallEngine.markPreConfirmed()
        installStartTime = System.currentTimeMillis()
        val addr = pendingAddress.ifEmpty { binding.etAddress.text?.toString().orEmpty() }
        InstallerService.startInstall(this, addr)
    }

    /** 切到指定屏并同步顶栏步骤指示 */
    private fun goScreen(n: Int) {
        if (binding.flipper.displayedChild != n) binding.flipper.displayedChild = n
        binding.tvStep.text = getString(R.string.step_indicator, n + 1)
    }

    private fun prefillAddress() {
        binding.etAddress.setText(AddressParser.DEFAULT_IP)
        binding.etAddress.setSelection(binding.etAddress.text?.length ?: 0)
        binding.tvHint.text = getString(R.string.hint_address)
    }

    /**
     * 启动时检查内置 APK，并把信息带到「安装详情」页。
     * 缺 APK 时禁用「继续」，避免用户进了详情页才发现没法装。
     *
     * 枚举 assets 要读文件（未配置 noCompress 时还会整包流式统计大小），
     * 必须放到 IO 线程，否则大 APK 会在 onCreate 里把主线程卡到 ANR。
     */
    private fun checkAssetApk() {
        apkReady = false
        binding.btnContinue.isEnabled = false
        binding.tvDetailPerm.text = getString(R.string.detail_perm_count, PermissionGranter.PERMISSIONS.size)
        binding.tvApkInfo.text = getString(R.string.apk_scanning)

        lifecycleScope.launch {
            val apks = withContext(Dispatchers.IO) { AssetApkProvider.listApks(this@MainActivity) }
            val core = withContext(Dispatchers.IO) { AssetApkProvider.pickCore(apks) }
            renderAssetApk(apks, core)
        }
    }

    private fun renderAssetApk(
        apks: List<AssetApkProvider.AssetApk>,
        core: AssetApkProvider.AssetApk?
    ) {
        if (apks.isEmpty()) {
            InstallLogger.warn("assets/${AssetApkProvider.ASSET_DIR}/ 为空，未检测到内置 APK")
            binding.tvApkInfo.text = getString(R.string.apk_missing)
            binding.tvApkInfo.setTextColor(ContextCompat.getColor(this, R.color.state_error))
            apkReady = false
        } else if (core != null) {
            apkName = core.name
            apkSizeText = formatSize(core.sizeBytes)
            binding.tvApkInfo.text = getString(R.string.apk_ready, apkName, apkSizeText)
            binding.tvApkInfo.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            apkReady = true
        } else {
            binding.tvApkInfo.text = getString(R.string.apk_ambiguous, apks.joinToString { it.name })
            binding.tvApkInfo.setTextColor(ContextCompat.getColor(this, R.color.state_warning))
            apkReady = false
        }

        if (!apkReady) {
            apkName = "?"
            apkSizeText = "?"
        }
        binding.btnContinue.isEnabled = apkReady
        binding.tvDetailName.text = getString(R.string.label_install_pkg, apkName)
        binding.tvDetailSize.text = getString(R.string.label_install_size, apkSizeText)
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

    private fun computeScreen(state: InstallState): Int = when {
        state.stage == InstallStage.DONE || state.stage == InstallStage.FAILED -> 3
        state.stage == InstallStage.CONFIRM && !preConfirmed -> 1
        state.stage == InstallStage.IDLE -> if (preConfirmed) 2 else 0
        else -> 2
    }

    private fun render(state: InstallState) {
        // 向导已在详情页确认过：自动放行引擎的 CONFIRM 等待
        if (state.awaitingConfirm && preConfirmed) {
            InstallEngine.confirmInstall()
        }

        // 切屏
        val screen = computeScreen(state)
        if (binding.flipper.displayedChild != screen) {
            binding.flipper.displayedChild = screen
        }
        binding.tvStep.text = getString(R.string.step_indicator, screen + 1)

        // 环形进度：确定进度显示百分比，无百分比阶段进入旋转不确定态
        when {
            state.progress in 0..100 -> {
                binding.ringProgress.setIndeterminate(false)
                binding.ringProgress.setProgress(state.progress)
                binding.ringProgress.visibility = View.VISIBLE
            }
            state.running -> {
                binding.ringProgress.setIndeterminate(true)
                binding.ringProgress.visibility = View.VISIBLE
            }
            else -> binding.ringProgress.visibility = View.GONE
        }

        // 阶段文案
        binding.tvStage.text = state.stage.title
        binding.tvStatus.text = state.statusText

        // 安装期间禁止进入远程 ADB：两个引擎互不相让，远程 ADB 还可能重启设备
        binding.btnRemoteAdb.isEnabled = !state.running

        // 横向步骤指示
        setStepIndicator(stageToStep(state.stage))

        // 结果状态 + 信息卡
        when {
            state.stage == InstallStage.DONE -> {
                binding.tvResultIcon.setBackgroundResource(R.drawable.bg_result_icon_success)
                binding.tvResultIcon.text = getString(R.string.icon_success)
                binding.tvResultTitle.text = getString(R.string.result_success_title)
                binding.tvResultSubtitle.text = getString(R.string.result_success_subtitle)
                binding.tvResultSubtitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                setResultVisibility(true)
                showResultCard(state)
            }
            state.stage == InstallStage.FAILED -> {
                binding.tvResultIcon.setBackgroundResource(R.drawable.bg_result_icon_error)
                binding.tvResultIcon.text = getString(R.string.icon_error)
                binding.tvResultTitle.text = getString(R.string.result_failed_title)
                binding.tvResultSubtitle.text = state.errorMessage.ifEmpty { getString(R.string.result_failed_subtitle) }
                binding.tvResultSubtitle.setTextColor(ContextCompat.getColor(this, R.color.state_error))
                setResultVisibility(true)
                showResultCard(state)
            }
            else -> {
                setResultVisibility(false)
                binding.cardResult.visibility = View.GONE
            }
        }

        // 统一驱动需要用户回应的对话框（连接/改地址/手动包名；确认已向导化）
        syncDialog(state)
    }

    private fun showResultCard(state: InstallState) {
        binding.cardResult.visibility = View.VISIBLE
        binding.tvResultAddr.text = state.address.ifEmpty { "-" }
        binding.tvResultPkg.text = state.packageName.ifEmpty { "-" }
        binding.tvResultApkName.text = apkName
        binding.tvResultApkSize.text = apkSizeText

        val seconds = if (installStartTime > 0L) {
            (System.currentTimeMillis() - installStartTime) / 1000.0
        } else 0.0
        binding.tvResultDuration.text = getString(R.string.duration_format, seconds)
    }

    private fun setResultVisibility(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.tvResultIcon.visibility = v
        binding.tvResultTitle.visibility = v
        binding.tvResultSubtitle.visibility = v
    }

    private fun stageToStep(stage: InstallStage): Int = when (stage) {
        InstallStage.CHECK_ENV, InstallStage.PARSE_ADDR, InstallStage.PREFLIGHT -> 0
        InstallStage.CONNECT -> 1
        InstallStage.CONFIRM, InstallStage.READY, InstallStage.INSTALL,
        InstallStage.RESOLVE_PKG, InstallStage.GRANT -> 2
        InstallStage.LAUNCH, InstallStage.HEALTH -> 3
        else -> -1
    }

    private fun setStepIndicator(active: Int) {
        val dots = listOf(binding.hstep0, binding.hstep1, binding.hstep2, binding.hstep3)
        dots.forEachIndexed { i, dot ->
            when {
                i < active -> {
                    dot.setBackgroundResource(R.drawable.bg_step_dot_done)
                    dot.setTextColor(colorOnDot)
                }
                i == active -> {
                    dot.setBackgroundResource(R.drawable.bg_step_dot_active)
                    dot.setTextColor(colorOnDot)
                }
                else -> {
                    dot.setBackgroundResource(R.drawable.bg_step_dot)
                    dot.setTextColor(colorPending)
                }
            }
        }
    }

    /**
     * 依据 state 决定当前应展示哪种交互对话框，并在种类变化时切换，
     * 避免每次 render 都重建同一个对话框、或遗漏引擎等待的输入。
     * CONFIRM 已向导化：仅在没有预先确认时才退化为对话框（正常情况下不会走到）。
     */
    private fun syncDialog(state: InstallState) {
        val want = when {
            state.interaction == InstallInteraction.CONNECT_DECISION -> DialogKind.CONNECT_DECISION
            state.interaction == InstallInteraction.ADDRESS_CHANGE -> DialogKind.ADDRESS_CHANGE
            state.interaction == InstallInteraction.MANUAL_PACKAGE -> DialogKind.MANUAL_PACKAGE
            state.awaitingConfirm && !preConfirmed -> DialogKind.CONFIRM
            else -> null
        }
        if (want == currentDialog) return
        dialog?.dismiss()
        dialog = null
        currentDialog = null
        when (want) {
            DialogKind.CONFIRM -> showConfirmDialog(state)
            DialogKind.CONNECT_DECISION -> showConnectDecisionDialog()
            DialogKind.ADDRESS_CHANGE -> showAddressDialog()
            DialogKind.MANUAL_PACKAGE -> showManualPackageDialog()
            null -> Unit
        }
        currentDialog = want
    }

    /** 安装前确认：展示 APK 名与大小，与 bat 的 Y/N 提示等价（仅未预先确认时启用） */
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

    /** 连接失败：重试 / 修改地址 / 取消。对应引擎的 connectWithRetry 等待 */
    private fun showConnectDecisionDialog() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_connect_title)
            .setMessage(getString(R.string.dialog_connect_message, InstallEngine.state.value.address))
            .setCancelable(false)
            .setPositiveButton(R.string.action_retry_connect) { _, _ ->
                InstallEngine.submitConnectAction(InstallEngine.ConnectAction.RETRY)
            }
            .setNeutralButton(R.string.action_change_address) { _, _ ->
                InstallEngine.submitConnectAction(InstallEngine.ConnectAction.CHANGE_ADDRESS)
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                InstallEngine.submitConnectAction(InstallEngine.ConnectAction.CANCEL)
            }
            .show()
    }

    /** 修改设备地址：提交后引擎用新地址重连 */
    private fun showAddressDialog() {
        val input = EditText(this).apply {
            setText(InstallEngine.state.value.address)
            setSelection(text?.length ?: 0)
            hint = getString(R.string.dialog_address_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_address_title)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                InstallEngine.submitAddressChange(input.text?.toString().orEmpty())
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> InstallEngine.cancel() }
            .show()
    }

    /** 包名识别兜底：手动输入 Core 包名 */
    private fun showManualPackageDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_package_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_package_title)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                // 包名非法时引擎会拒收并要求重输，先清掉去重标记，好让下一次 render 重新弹出
                currentDialog = null
                InstallEngine.submitManualPackage(input.text?.toString().orEmpty())
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> InstallEngine.cancel() }
            .show()
    }

    override fun onDestroy() {
        // 这些对话框都是 setCancelable(false)：Activity 结束时不 dismiss 会 WindowLeaked
        // 并把 Activity 引用漏在对话框上
        dialog?.dismiss()
        dialog = null
        currentDialog = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 日志：跳转到独立日志界面（LogActivity）
    // ------------------------------------------------------------------

    private fun openLog() {
        startActivity(Intent(this, LogActivity::class.java))
    }

    // ------------------------------------------------------------------
    // 日志导出（结果页：分享 / 复制）
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
}
