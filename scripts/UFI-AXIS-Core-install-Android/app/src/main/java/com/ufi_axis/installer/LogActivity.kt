package com.ufi_axis.installer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.ufi_axis.installer.databinding.ActivityLogBinding
import com.ufi_axis.installer.logging.InstallLogger
import com.ufi_axis.installer.ui.LogAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 日志界面：独立全屏，替代原先的抽屉弹窗。
 * 通过主界面顶栏「日志」或结果页「查看日志」跳转，返回即回到向导。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var logAdapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logAdapter = LogAdapter().also {
            binding.rvLogs.apply {
                layoutManager = LinearLayoutManager(this@LogActivity)
                adapter = it
                itemAnimator = null
            }
        }

        binding.btnLogBack.setOnClickListener { finish() }
        binding.btnLogCopy.setOnClickListener { copyLog() }
        binding.btnLogShare.setOnClickListener { shareLog() }

        observeLogs()
    }

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

    private fun shareLog() {
        InstallLogger.flush()
        val file = InstallLogger.lastLogFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.toast_no_log_file, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
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

    companion object {
        private const val MAX_UI_LINES = 500
    }
}
