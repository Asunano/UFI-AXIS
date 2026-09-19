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
 * - 两个确认弹窗由 [RemoteAdbEngine.state.interaction] 驱动。
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
        setupViews()
        observe()
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnStart.setOnClickListener { onStartClick() }
        binding.btnCancel.setOnClickListener { RemoteAdbEngine.cancel() }
        binding.btnRetry.setOnClickListener { RemoteAdbEngine.reset(); showInput() }
        binding.btnViewLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        // 默认填充
        binding.etAddress.setText("192.168.0.1:8080")
        binding.etPassword.setText("admin")
    }

    private fun onStartClick() {
        val raw = binding.etAddress.text?.toString().orEmpty().ifBlank { "192.168.0.1:8080" }
        val (host, port) = parseGoform(raw)
        val pwd = binding.etPassword.text?.toString().orEmpty().ifBlank { "admin" }
        showProgress()
        RemoteAdbEngine.start(host, port, pwd)
    }

    private fun parseGoform(raw: String): Pair<String, Int> {
        val c = raw.trim().removePrefix("http://").removePrefix("https://")
        val idx = c.lastIndexOf(':')
        return if (idx > 0) {
            c.substring(0, idx) to (c.substring(idx + 1).toIntOrNull() ?: 8080)
        } else c to 8080
    }

    private fun showInput() {
        binding.panelInput.visibility = View.VISIBLE
        binding.panelProgress.visibility = View.GONE
        binding.panelResult.visibility = View.GONE
    }

    private fun showProgress() {
        binding.panelInput.visibility = View.GONE
        binding.panelProgress.visibility = View.VISIBLE
        binding.panelResult.visibility = View.GONE
    }

    private fun showResult(state: RemoteAdbState) {
        binding.panelInput.visibility = View.GONE
        binding.panelProgress.visibility = View.GONE
        binding.panelResult.visibility = View.VISIBLE
        if (state.success) {
            binding.tvResultIcon.setBackgroundResource(R.drawable.bg_result_icon_success)
            binding.tvResultIcon.text = "✓"
            binding.tvResultTitle.text = getString(R.string.remote_adb_success_title)
            binding.tvResultSubtitle.text = getString(R.string.remote_adb_success_sub)
            binding.tvResultSubtitle.setTextColor(ContextCompat.getColor(this, R.color.state_success))
            binding.cardResult.visibility = View.VISIBLE
            val addr = if (state.adbAddress.isNotEmpty()) state.adbAddress else "192.168.0.1:5555"
            binding.tvResultAddr.text = addr
            binding.tvResultCmd.text = "adb connect $addr"
        } else {
            binding.tvResultIcon.setBackgroundResource(R.drawable.bg_result_icon_error)
            binding.tvResultIcon.text = "✕"
            binding.tvResultTitle.text = getString(R.string.remote_adb_failed_title)
            binding.tvResultSubtitle.text =
                state.errorMessage.ifEmpty { getString(R.string.remote_adb_failed_sub) }
            binding.tvResultSubtitle.setTextColor(ContextCompat.getColor(this, R.color.state_error))
            binding.cardResult.visibility = View.GONE
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoteAdbEngine.state.collectLatest { render(it) }
            }
        }
    }

    private fun render(state: RemoteAdbState) {
        // 环形进度
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

        if (state.showResult) {
            dialog?.dismiss(); dialog = null; currentDialog = null
            showResult(state)
            return
        }
        syncDialog(state)
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
            null -> Unit
            else -> Unit
        }
        currentDialog = want
    }

    private fun showWireDialog() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.remote_adb_wire_title)
            .setMessage(R.string.remote_adb_wire_message)
            .setCancelable(false)
            .setPositiveButton(R.string.remote_adb_wire_confirm) { _, _ -> RemoteAdbEngine.confirmWire() }
            .show()
    }

    private fun showRebootDialog() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.remote_adb_reboot_title)
            .setMessage(R.string.remote_adb_reboot_message)
            .setCancelable(false)
            .setPositiveButton(R.string.remote_adb_reboot_confirm) { _, _ -> RemoteAdbEngine.confirmReboot() }
            .setNegativeButton(R.string.remote_adb_reboot_cancel) { _, _ -> RemoteAdbEngine.rejectReboot() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss(); dialog = null
    }
}
