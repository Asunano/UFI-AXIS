package com.ufi_axis.installer.remoteadb

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ufi_axis.installer.LogActivity
import com.ufi_axis.installer.R
import com.ufi_axis.installer.databinding.ActivityRemoteAdbBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 开启远程 ADB 引导流程界面。
 * - 输入面板：设备后台地址（默认 192.168.0.1:8080）+ 密码（默认 admin）；
 * - 进度面板：环形进度 + 阶段/状态文案；
 * - 结果面板：成功/失败 + adb connect 命令提示；
 * - 两个确认弹窗由 [RemoteAdbEngine.state] 的 interaction 驱动。
 *
 * 面板可见性**完全由 state 推导**（[render]），不由点击驱动：
 * 否则进程重建后引擎还在跑、界面却回到输入表单。
 */
class RemoteAdbActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteAdbBinding
    private var dialog: AlertDialog? = null
    private var currentDialog: RemoteAdbInteraction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteAdbBinding.inflate(layoutInflater)
        setContentView(binding.root)
        RemoteAdbEngine.attach(applicationContext)
        setupViews(savedInstanceState)
        observe()
    }

    private fun setupViews(savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnStart.setOnClickListener { onStartClick() }
        binding.btnCancel.setOnClickListener { RemoteAdbEngine.cancel() }
        binding.btnRetry.setOnClickListener { RemoteAdbEngine.reset() }
        binding.btnViewLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        // 只有首次创建才填默认值：重建时覆盖会把用户刚输入的地址/密码抹掉
        if (savedInstanceState == null) {
            binding.etAddress.setText(getString(R.string.remote_adb_default_address))
            binding.etPassword.setText(getString(R.string.remote_adb_default_password))
        }
    }

    private fun onStartClick() {
        val addrInput = binding.etAddress.text?.toString().orEmpty().trim()
        val pwd = binding.etPassword.text?.toString().orEmpty()

        // 校验失败必须说明原因，不能静默替换成默认值
        if (addrInput.isEmpty()) {
            showFieldError(binding.etAddress, getString(R.string.remote_adb_err_address_empty))
            return
        }
        if (pwd.isEmpty()) {
            showFieldError(binding.etPassword, getString(R.string.remote_adb_err_password_empty))
            return
        }
        val parsed = parseGoform(addrInput)
        if (parsed == null) {
            showFieldError(binding.etAddress, getString(R.string.remote_adb_err_address_invalid))
            return
        }
        binding.etAddress.error = null
        binding.etPassword.error = null
        RemoteAdbEngine.start(parsed.first, parsed.second, pwd)
    }

    private fun showFieldError(field: android.widget.EditText, message: String) {
        field.error = message
        field.requestFocus()
    }

    /**
     * 解析「host[:port]」。非法输入返回 null 交给调用方提示，不做静默兜底。
     * IPv6 需要用 `[::1]:8080` 形式，否则无法区分地址里的冒号与端口分隔符。
     */
    private fun parseGoform(raw: String): Pair<String, Int>? {
        val c = raw.removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (c.isEmpty()) return null

        val host: String
        val portPart: String?
        when {
            c.startsWith("[") -> {
                val end = c.indexOf(']')
                if (end < 0) return null
                host = c.substring(1, end)
                val rest = c.substring(end + 1)
                portPart = if (rest.startsWith(":")) rest.substring(1) else if (rest.isEmpty()) null else return null
            }
            c.count { it == ':' } > 1 -> return null // 裸 IPv6，要求加方括号
            c.contains(':') -> {
                host = c.substringBefore(':')
                portPart = c.substringAfter(':')
            }
            else -> {
                host = c
                portPart = null
            }
        }
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        val port = if (portPart == null) DEFAULT_GOFORM_PORT else portPart.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoteAdbEngine.state.collectLatest { render(it) }
            }
        }
    }

    private fun render(state: RemoteAdbState) {
        // 面板可见性由状态推导：重建后也能回到正确的那一屏
        val showResult = state.showResult
        val showProgress = !showResult && state.stage != RemoteAdbStage.IDLE
        binding.panelInput.visibility = if (!showResult && !showProgress) View.VISIBLE else View.GONE
        binding.panelProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
        binding.panelResult.visibility = if (showResult) View.VISIBLE else View.GONE

        // 运行中「开启」按钮必须真的置灰，不能显示可点却被 busy 拒绝
        binding.btnStart.isEnabled = !RemoteAdbEngine.isRunning()

        if (state.progress in 0..100) {
            binding.ringProgress.setIndeterminate(false)
            binding.ringProgress.setProgress(state.progress)
            binding.ringProgress.visibility = View.VISIBLE
        } else if (state.running) {
            binding.ringProgress.setIndeterminate(true)
            binding.ringProgress.visibility = View.VISIBLE
        } else {
            binding.ringProgress.visibility = View.GONE
        }
        binding.tvStage.text = state.stage.title
        binding.tvStatus.text = state.statusText

        if (showResult) {
            dialog?.dismiss(); dialog = null; currentDialog = null
            renderResult(state)
            return
        }
        syncDialog(state)
    }

    private fun renderResult(state: RemoteAdbState) {
        if (state.success) {
            binding.tvResultIcon.setBackgroundResource(R.drawable.bg_result_icon_success)
            binding.tvResultIcon.text = getString(R.string.icon_success)
            binding.tvResultTitle.text = getString(R.string.remote_adb_success_title)
            binding.tvResultSubtitle.text = getString(
                if (state.persistent) R.string.remote_adb_success_sub
                else R.string.remote_adb_success_sub_temporary
            )
            binding.tvResultSubtitle.setTextColor(ContextCompat.getColor(this, R.color.state_success))
            binding.cardResult.visibility = View.VISIBLE
            val addr = state.adbAddress
            binding.tvResultAddr.text = addr
            binding.tvResultCmd.text = getString(R.string.remote_adb_cmd_format, addr)
        } else {
            binding.tvResultIcon.setBackgroundResource(R.drawable.bg_result_icon_error)
            binding.tvResultIcon.text = getString(R.string.icon_error)
            binding.tvResultTitle.text = getString(R.string.remote_adb_failed_title)
            binding.tvResultSubtitle.text =
                state.errorMessage.ifEmpty { getString(R.string.remote_adb_failed_sub) }
            binding.tvResultSubtitle.setTextColor(ContextCompat.getColor(this, R.color.state_error))
            binding.cardResult.visibility = View.GONE
        }
    }

    private fun syncDialog(state: RemoteAdbState) {
        val want = when {
            state.awaitingWireConfirm -> RemoteAdbInteraction.WIRE_CONFIRM
            state.awaitingRebootConfirm -> RemoteAdbInteraction.REBOOT_CONFIRM
            else -> null
        }
        if (want == currentDialog) return
        dialog?.dismiss(); dialog = null; currentDialog = null
        when (want) {
            RemoteAdbInteraction.WIRE_CONFIRM -> showWireDialog()
            RemoteAdbInteraction.REBOOT_CONFIRM -> showRebootDialog()
            else -> Unit
        }
        currentDialog = want
    }

    /** 有线连接提醒：必须给「取消」出口，否则用户只能点确认 */
    private fun showWireDialog() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.remote_adb_wire_title)
            .setMessage(R.string.remote_adb_wire_message)
            .setCancelable(false)
            .setPositiveButton(R.string.remote_adb_wire_confirm) { _, _ -> RemoteAdbEngine.confirmWire() }
            .setNegativeButton(R.string.remote_adb_cancel) { _, _ -> RemoteAdbEngine.cancel() }
            .show()
    }

    /** 重启是破坏性动作：必须由用户在这里点确认，引擎不会自动下发 */
    private fun showRebootDialog() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.remote_adb_reboot_title)
            .setMessage(
                getString(
                    R.string.remote_adb_reboot_message,
                    RemoteAdbEngine.PORT_OPEN_WAIT_SECONDS
                )
            )
            .setCancelable(false)
            .setPositiveButton(R.string.remote_adb_reboot_confirm) { _, _ -> RemoteAdbEngine.confirmReboot() }
            .setNegativeButton(R.string.remote_adb_reboot_cancel) { _, _ -> RemoteAdbEngine.rejectReboot() }
            .show()
    }

    override fun onDestroy() {
        dialog?.dismiss(); dialog = null
        // 界面真的关掉且引擎正在等弹窗回应时必须中止：没有 UI 就没人能回应，
        // 否则等待协程会一直挂着并把引擎锁死。
        if (isFinishing) RemoteAdbEngine.abortIfAwaitingInteraction()
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_GOFORM_PORT = 8080
    }
}
