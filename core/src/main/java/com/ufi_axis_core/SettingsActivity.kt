package com.ufi_axis_core

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ufi_axis_core.api.pairing.PairedDeviceBlacklistStore
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.service.BackendService
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.PairedDeviceStore
import com.ufi_axis_core.util.WebResourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 本机设置页（设备端，唯一的"最后保险"入口）。
 *
 * ## 为什么这一页重要（2026-10-19）
 * 设备**没有屏幕**，只能 adb 投屏进来；而远程一旦把"只有我们软件才管"的设置改坏
 * （忘记配对密码、黑名单自锁、端口被占…），厂商 goform 后台救不了、客户端也进不来 ——
 * 这一页就是最后一条路。所以 2026-10-19 补了一组「安全与恢复」入口，全部**只在本地可用**：
 * 重置配对密码、解除全部配对、清空黑名单、回滚 Web 前端、恢复出厂、端口回退。
 *
 * ## 边界
 * - 本页直接读写 SharedPreferences，**不依赖 HTTP 服务**：服务起不来时也能进（这是它存在的意义）。
 * - 配对密码只存哈希，任何情况下不回填；设备后台（goform）口令仍按原样回填明文 ——
 *   它只是 core 登录厂商后台用的口令，**不是**固件 admin 密码（改固件密码要去 App/Web）。
 */
class SettingsActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var etPort: EditText
    private lateinit var cbAutoStart: CheckBox
    private lateinit var etGoformIp: EditText
    private lateinit var etGoformPort: EditText
    private lateinit var etGoformPassword: EditText
    private lateinit var etCoreOldPwd: EditText
    private lateinit var etCoreNewPwd: EditText
    private lateinit var etCoreNewPwd2: EditText
    private lateinit var tvCorePwdStatus: TextView
    private lateinit var cbPairingEnabled: CheckBox
    private lateinit var etPairingMaxDevices: EditText
    private lateinit var tvPairingCurrent: TextView
    private lateinit var tvSaved: TextView

    // ── 2026-10-19 新增：安全与恢复 ──
    private lateinit var cbApprovalRequired: CheckBox
    private lateinit var btnResetPassword: Button
    private lateinit var btnUnpairAll: Button
    private lateinit var btnClearBlacklist: Button
    private lateinit var tvBlacklistCount: TextView
    private lateinit var btnRollbackWeb: Button
    private lateinit var tvWebOverrideState: TextView
    private lateinit var btnFactoryReset: Button
    private lateinit var tvDiagnostics: TextView
    private lateinit var btnPortFallback: Button
    private lateinit var tvPortHint: TextView
    private lateinit var btnTestGoform: Button
    private lateinit var tvGoformTest: TextView
    private lateinit var cbRevokeOnPwdChange: CheckBox

    private var goformPwdVisible = false
    private var corePwdVisible = false

    /** 清黑名单是 suspend（要加锁 + 落盘），用独立协程；Activity 销毁时取消。 */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 页面可见期间的只读状态刷新（onResume 起、onPause 停），见 [startStatusTicker]。 */
    private var statusTicker: Pair<android.os.Handler, Runnable>? = null

    private val blacklistStore by lazy { PairedDeviceBlacklistStore(this) }
    private val webResourceManager by lazy { WebResourceManager(this) }

    /**
     * 配对密码的业务规则全部收敛在 [PairingManager]（长度 / 旧密码校验 / 失败次数限制），
     * 本页只做输入与反馈。:core 已依赖 :core:api 与 :core:common，可直接构造。
     * 延迟构造：[PairedDeviceStore] 首次创建时会读配对记录文件，进设置页时没必要付这份 IO。
     *
     * 取进程内单例而不是 new：后台服务就在同一进程里跑，两个实例各写各的
     * `paired_devices.json` 会互相覆盖（见 [PairedDeviceStore.getInstance]）。
     */
    private val pairingManager: PairingManager by lazy {
        PairingManager(settings, PairedDeviceStore.getInstance(this, settings))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings.getInstance(this)
        etPort = findViewById(R.id.etPort)
        cbAutoStart = findViewById(R.id.cbAutoStart)
        etGoformIp = findViewById(R.id.etGoformIp)
        etGoformPort = findViewById(R.id.etGoformPort)
        etGoformPassword = findViewById(R.id.etGoformPassword)
        etCoreOldPwd = findViewById(R.id.etCoreOldPwd)
        etCoreNewPwd = findViewById(R.id.etCoreNewPwd)
        etCoreNewPwd2 = findViewById(R.id.etCoreNewPwd2)
        tvCorePwdStatus = findViewById(R.id.tvCorePwdStatus)
        cbPairingEnabled = findViewById(R.id.cbPairingEnabled)
        etPairingMaxDevices = findViewById(R.id.etPairingMaxDevices)
        tvPairingCurrent = findViewById(R.id.tvPairingCurrent)
        tvSaved = findViewById(R.id.tvSaved)

        cbApprovalRequired = findViewById(R.id.cbApprovalRequired)
        btnResetPassword = findViewById(R.id.btnResetPassword)
        btnUnpairAll = findViewById(R.id.btnUnpairAll)
        btnClearBlacklist = findViewById(R.id.btnClearBlacklist)
        tvBlacklistCount = findViewById(R.id.tvBlacklistCount)
        btnRollbackWeb = findViewById(R.id.btnRollbackWeb)
        tvWebOverrideState = findViewById(R.id.tvWebOverrideState)
        btnFactoryReset = findViewById(R.id.btnFactoryReset)
        tvDiagnostics = findViewById(R.id.tvDiagnostics)
        btnPortFallback = findViewById(R.id.btnPortFallback)
        tvPortHint = findViewById(R.id.tvPortHint)
        btnTestGoform = findViewById(R.id.btnTestGoform)
        tvGoformTest = findViewById(R.id.tvGoformTest)
        cbRevokeOnPwdChange = findViewById(R.id.cbRevokeOnPwdChange)

        val btnToggleGoformPwd = findViewById<Button>(R.id.btnToggleGoformPwd)
        val btnToggleCorePwd = findViewById<Button>(R.id.btnToggleCorePwd)
        val btnChangeCorePwd = findViewById<Button>(R.id.btnChangeCorePwd)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnReset = findViewById<Button>(R.id.btnReset)

        loadSettings()

        btnToggleGoformPwd.setOnClickListener {
            goformPwdVisible = !goformPwdVisible
            etGoformPassword.inputType = if (goformPwdVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            etGoformPassword.setSelection(etGoformPassword.text.length)
            btnToggleGoformPwd.text = if (goformPwdVisible) "隐藏" else "显示"
        }

        btnToggleCorePwd.setOnClickListener {
            corePwdVisible = !corePwdVisible
            val type = if (corePwdVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            for (field in listOf(etCoreOldPwd, etCoreNewPwd, etCoreNewPwd2)) {
                field.inputType = type
                field.setSelection(field.text.length)
            }
            btnToggleCorePwd.text = if (corePwdVisible) "隐藏" else "显示"
        }

        // 配对密码独立提交：需要旧密码校验，且不能因为用户只改了端口就被顺手重写
        btnChangeCorePwd.setOnClickListener { changeCorePassword() }

        btnSave.setOnClickListener {
            saveSettings()
            tvSaved.visibility = View.VISIBLE
        }

        btnReset.setOnClickListener {
            settings.resetAll()
            loadSettings()
            tvSaved.visibility = View.VISIBLE
        }

        // ── 2026-10-19：安全与恢复入口 ──
        btnPortFallback.setOnClickListener { fallbackPort() }
        btnResetPassword.setOnClickListener { showResetPasswordDialog() }
        btnUnpairAll.setOnClickListener { confirmUnpairAll() }
        btnClearBlacklist.setOnClickListener { confirmClearBlacklist() }
        btnRollbackWeb.setOnClickListener { confirmRollbackWeb() }
        btnFactoryReset.setOnClickListener { confirmFactoryReset() }
        btnTestGoform.setOnClickListener { testGoformConnectivity() }
        // 关掉自启是"重启后永久失联"的起点 —— 勾选变化时给强提示（不阻止，只告知）
        cbAutoStart.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("注意：关闭后重启不会自动启动")
                    .setMessage(
                        "关闭此项后，设备一旦重启，core 不会再自己起来；\n" +
                            "届时只能通过 adb（am start-foreground-service）手动拉起，或重新打开此开关。"
                    )
                    .setPositiveButton("我知道了", null)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * 回到前台时重读一遍（2026-10-19）。
     *
     * 之前只在 `onCreate` 读一次：App/Web 那边改了配置（配对开关、黑名单、web 覆盖…），
     * 本页仍显示旧值 —— 也就是"设置项不能实时刷新"。`onResume` 覆盖"从别处切回来"的路径。
     */
    override fun onResume() {
        super.onResume()
        loadSettings()
        startStatusTicker()
    }

    override fun onPause() {
        super.onPause()
        stopStatusTicker()
    }

    /**
     * 页面可见期间的轻量轮询（2026-10-19）：只刷新**只读状态**（已配对/黑名单/web 覆盖/诊断/端口），
     * 不碰任何输入框与开关 —— 否则用户正在输入时会被远程改动覆盖掉。
     */
    private fun startStatusTicker() {
        if (statusTicker != null) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                refreshReadOnlyStatus()
                handler.postDelayed(this, STATUS_REFRESH_MS)
            }
        }
        statusTicker = Pair(handler, runnable)
        handler.postDelayed(runnable, STATUS_REFRESH_MS)
    }

    private fun stopStatusTicker() {
        statusTicker?.let { (handler, runnable) -> handler.removeCallbacks(runnable) }
        statusTicker = null
    }

    /** 只刷新只读显示，不改任何可编辑控件（见 [startStatusTicker]）。 */
    private fun refreshReadOnlyStatus() {
        // refreshDiagnostics 只写只读 TextView（配对台数/黑名单/web 覆盖/诊断/端口提示），
        // 不碰任何 EditText 或 CheckBox —— 轮询是安全的。
        refreshDiagnostics()
    }

    private fun loadSettings() {
        etPort.setText(settings.port.toString())
        cbAutoStart.isChecked = settings.autoStartOnBoot
        etGoformIp.setText(settings.goformIp)
        etGoformPort.setText(settings.goformPort.toString())
        etGoformPassword.setText(settings.goformPassword)
        cbPairingEnabled.isChecked = settings.pairingEnabled
        etPairingMaxDevices.setText(settings.pairingMaxDevices.toString())
        cbApprovalRequired.isChecked = settings.pairingApprovalRequired
        val count = settings.pairedFingerprints.size
        tvPairingCurrent.text = "当前已配对: $count 台设备"
        // 配对密码只存哈希，任何时候都不回填（含 恢复默认 之后）
        clearCorePwdFields()
        etCoreOldPwd.hint = if (settings.hasDefaultPassword) {
            "当前密码（尚未设置，留空即可）"
        } else {
            "当前密码"
        }
        tvCorePwdStatus.visibility = View.GONE
        tvSaved.visibility = View.GONE
        refreshDiagnostics()
    }

    /** 诊断信息：排障时（adb 投屏进来）一眼能看出问题在哪。 */
    private fun refreshDiagnostics() {
        val paired = settings.pairedFingerprints.size
        val blocked = runCatching { blacklistStore.entries().size }.getOrDefault(0)
        tvBlacklistCount.text = "当前黑名单：$blocked 条"
        val hasOverride = runCatching { webResourceManager.hasOverride() }.getOrDefault(false)
        tvWebOverrideState.text =
            if (hasOverride) "Web 前端：已覆盖（若白屏请用上方按钮回滚）" else "Web 前端：未覆盖（使用内置版本）"
        tvDiagnostics.text = buildString {
            append("监听端口：").append(settings.port).append("（修改后需重启服务）\n")
            append("已配对：").append(paired).append(" 台\n")
            append("配对密码：").append(if (settings.hasDefaultPassword) "未设置（默认）" else "已设置").append('\n')
            append("新设备批准：").append(if (settings.pairingApprovalRequired) "开" else "关").append('\n')
            append("安全模式：").append(if (settings.safeModeActive) "开（下次启动强制默认端口+关后台）" else "关").append('\n')
            append("连续启动失败：").append(settings.startupFailureCount).append(" 次\n")
            append("开机自启：").append(if (settings.autoStartOnBoot) "开" else "关（重启后不会自动启动）")
        }
        checkPortAvailability()
    }

    /**
     * 端口占用检测（2026-10-19，2026-10-19 修正误报）。
     *
     * 端口被占时服务永远起不来，而 core 没有自动换端口的逻辑 —— 这里在本地页直接告诉用户。
     *
     * ⚠ 修正：**本服务正在运行时，8088 必然"绑不上"**（它自己占着）。之前一律报"被占用"，
     * 用户看到的就是"服务明明在跑，却提示端口被占用"。现在先看 [BackendService.isRunning] +
     * 当前配置端口，区分三种情况：本服务在用 / 空闲 / 被别的程序占用。
     */
    private fun checkPortAvailability() {
        val port = settings.port
        scope.launch {
            val free = runCatching { java.net.ServerSocket(port).use { true } }.getOrDefault(false)
            runOnUiThread {
                val ours = !free && BackendService.isRunning && port == settings.port
                tvPortHint.text = when {
                    ours ->
                        "端口 $port 正由本服务使用（正常）。"
                    free ->
                        "端口 $port 当前空闲（服务未运行）。"
                    else ->
                        "端口 $port 已被其他程序占用。若服务起不来，请点上方按钮回退到 " +
                            "${AppSettings.DEFAULT_PORT}（8080 goform / 5555 adbd / 7682 ttyd 是常见冲突源）。"
                }
                tvPortHint.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 设备后台连通性测试（2026-10-19）。
     *
     * 用页面上的 IP/端口做一次 HEAD，区分"地址不通"与"口令不对" —— 后者会表现为能连上但业务 503。
     * 跑在 IO 协程里，1.5s 超时，不会卡 UI。
     */
    private fun testGoformConnectivity() {
        val ip = etGoformIp.text.toString().trim().ifBlank { settings.goformIp }
        val port = etGoformPort.text.toString().toIntOrNull() ?: settings.goformPort
        tvGoformTest.text = "测试中…"
        tvGoformTest.visibility = View.VISIBLE
        scope.launch {
            val result = runCatching {
                val url = java.net.URL(if (port == 80) "http://$ip" else "http://$ip:$port")
                val conn = url.openConnection() as java.net.HttpURLConnection
                try {
                    conn.connectTimeout = 1_500
                    conn.readTimeout = 1_500
                    conn.requestMethod = "HEAD"
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            }
            runOnUiThread {
                tvGoformTest.text = result.fold(
                    onSuccess = { code ->
                        "设备后台可达（HTTP $code）。若业务接口仍报 503，检查设备后台密码是否与固件一致。"
                    },
                    onFailure = { e ->
                        "设备后台不可达：${e.javaClass.simpleName}。检查 IP / 端口，以及设备是否在运行。"
                    }
                )
                tvGoformTest.visibility = View.VISIBLE
            }
        }
    }

    private fun saveSettings() {
        etPort.text.toString().toIntOrNull()?.let { settings.port = it }
        settings.autoStartOnBoot = cbAutoStart.isChecked
        settings.goformIp = etGoformIp.text.toString().trim()
        etGoformPort.text.toString().toIntOrNull()?.let { settings.goformPort = it }
        val pwd = etGoformPassword.text.toString().trim()
        if (pwd.isNotBlank()) settings.goformPassword = pwd
        settings.pairingEnabled = cbPairingEnabled.isChecked
        etPairingMaxDevices.text.toString().toIntOrNull()?.let { settings.pairingMaxDevices = it }
        // 2026-10-19：批准开关也在本页可改。默认关，关着时新设备凭密码即可配对。
        settings.pairingApprovalRequired = cbApprovalRequired.isChecked
        refreshDiagnostics()
    }

    // ─────────────────────────── 安全与恢复 ───────────────────────────

    /**
     * 端口回退（2026-10-19）。
     *
     * 端口被占（8080 是设备自身 goform、5555 是 adbd、7682 是 ttyd）时服务永远起不来，
     * 而 `HttpServer` 只做自检失败 → `stopSelf` → 看门狗重拉，**不会自己换端口**。
     * 这里是唯一能让设备重新可达的本地动作。
     */
    private fun fallbackPort() {
        val old = settings.port
        settings.port = AppSettings.DEFAULT_PORT
        etPort.setText(settings.port.toString())
        tvPortHint.text = "已从 $old 回退到 ${AppSettings.DEFAULT_PORT}。请回到主页重启服务后生效。"
        tvPortHint.visibility = View.VISIBLE
        refreshDiagnostics()
    }

    /**
     * 重置配对密码（**不需要旧密码**，2026-10-19）。
     *
     * 为什么允许绕过旧密码：忘记密码 = 全仓没有任何恢复路径（本地改密也要旧密码，
     * 「恢复默认」又刻意保留密码哈希），设备就此变成砖。而本页只能靠物理在场 / adb 投屏进入，
     * 等价于"持有人在场"，所以这里放行；代价是**必须两步确认**，避免误触。
     *
     * 注意：改密码**不会**撤销已配对设备的 token（与 `PairingManager.changePassword` 一致），
     * 要踢出已配对设备请用「解除全部配对」。
     */
    private fun showResetPasswordDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etNew = EditText(this).apply {
            hint = "新配对密码（4-64 位）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etNew2 = EditText(this).apply {
            hint = "确认新密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(etNew)
        container.addView(etNew2)

        AlertDialog.Builder(this)
            .setTitle("重置配对密码")
            .setMessage(
                "无需旧密码。仅用于忘记密码时自救；\n" +
                    "已配对设备不会被踢下线。"
            )
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认重置") { _, _ ->
                val newPw = etNew.text.toString()
                val newPw2 = etNew2.text.toString()
                if (newPw != newPw2) {
                    toast("两次输入的新密码不一致")
                    return@setPositiveButton
                }
                if (newPw.length < PairingManager.MIN_PASSWORD_LENGTH ||
                    newPw.length > PairingManager.MAX_PASSWORD_LENGTH
                ) {
                    toast(
                        "新密码长度需为 ${PairingManager.MIN_PASSWORD_LENGTH}-" +
                            "${PairingManager.MAX_PASSWORD_LENGTH} 位"
                    )
                    return@setPositiveButton
                }
                settings.setDevicePassword(newPw)
                clearCorePwdFields()
                showCorePwdStatus("配对密码已重置（无需旧密码）", success = true)
                loadSettings()
                toast("配对密码已重置")
            }
            .show()
    }

    /** 解除全部配对：本地无需密码（本机操作即视为机主在场）。 */
    private fun confirmUnpairAll() {
        val count = settings.pairedFingerprints.size
        AlertDialog.Builder(this)
            .setTitle("解除全部配对？")
            .setMessage(
                "将清除 $count 台已配对设备，所有 App / Web 需要重新配对。\n" +
                    "配对密码、黑名单不会被清除。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("解除") { _, _ ->
                runCatching { pairingManager.unpairAll() }
                    .onSuccess {
                        loadSettings()
                        toast("已解除全部配对")
                    }
                    .onFailure { toast("解除失败：${it.message}") }
            }
            .show()
    }

    /** 清空配对黑名单（suspend 落盘，走 IO 协程）。 */
    private fun confirmClearBlacklist() {
        val count = runCatching { blacklistStore.entries().size }.getOrDefault(0)
        AlertDialog.Builder(this)
            .setTitle("清空配对黑名单？")
            .setMessage(
                "当前有 $count 条记录。清空后这些设备可以重新配对/登录。\n" +
                    "注意：黑名单条目不会随「解除全部配对」或「恢复默认」被清除，只能在这里清。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                scope.launch {
                    val removed = runCatching { blacklistStore.clear() }.getOrElse { -1 }
                    runOnUiThread {
                        if (removed >= 0) {
                            refreshDiagnostics()
                            toast("已清空黑名单（$removed 条）")
                        } else {
                            toast("清空失败，请重试")
                        }
                    }
                }
            }
            .show()
    }

    /** 回滚 Web 前端覆盖：坏包白屏时唯一的本地出口。 */
    private fun confirmRollbackWeb() {
        val hasOverride = runCatching { webResourceManager.hasOverride() }.getOrDefault(false)
        AlertDialog.Builder(this)
            .setTitle("回滚 Web 前端？")
            .setMessage(
                if (hasOverride) {
                    "将删除当前上传的 Web 前端，恢复 APK 内置版本。"
                } else {
                    "当前没有 Web 覆盖，执行后会确认恢复为内置版本。"
                }
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("回滚") { _, _ ->
                val ok = runCatching { webResourceManager.clearOverride() }.getOrDefault(false)
                refreshDiagnostics()
                toast(if (ok) "已回滚到内置 Web" else "回滚失败，请查看日志")
            }
            .show()
    }

    /**
     * 恢复出厂（放弃所有权）—— 两步确认。
     *
     * 三处存储一起清：本 prefs（含密码哈希 / device_id / 全部配置）、
     * `paired_devices.json`（配对记录）、`paired_blacklist`（黑名单）。
     * 少清任何一处都会留下"看起来清空了、实际还能进"的残渣。
     */
    private fun confirmFactoryReset() {
        AlertDialog.Builder(this)
            .setTitle("恢复出厂？")
            .setMessage(
                "将清除：配对密码、全部配对设备、配对黑名单、设备身份（device_id）与所有配置。\n" +
                    "此操作不可恢复，设备将回到「等待首次配对」状态。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ -> showFactoryResetConfirmStep2() }
            .show()
    }

    private fun showFactoryResetConfirmStep2() {
        val et = EditText(this).apply {
            hint = "输入 恢复出厂 四个字确认"
            setPadding(48, 24, 48, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("最后确认")
            .setView(et)
            .setNegativeButton("取消", null)
            .setPositiveButton("执行") { _, _ ->
                if (et.text.toString().trim() != "恢复出厂") {
                    toast("确认词不匹配，已取消")
                    return@setPositiveButton
                }
                scope.launch {
                    val err = runCatching {
                        PairedDeviceStore.getInstance(this@SettingsActivity, settings).clear()
                        blacklistStore.clear()
                        settings.factoryReset()
                    }.exceptionOrNull()
                    runOnUiThread {
                        if (err == null) {
                            loadSettings()
                            toast("已恢复出厂")
                        } else {
                            toast("恢复出厂失败：${err.message}")
                        }
                    }
                }
            }
            .show()
    }

    // ─────────────────────────── 原有：改密码 / 提示 ───────────────────────────

    /**
     * 修改配对密码，与 [saveSettings] 完全分离。
     *
     * 规则复用 [PairingManager.changePassword]：长度 4-64、旧密码校验、失败次数限制。
     * 仅「设备从未设过密码」这一支例外——此时 `verifyDevicePassword` 恒 false，
     * changePassword 只会回 WrongOldPassword，没有任何旧密码能通过；
     * 按 `PairingManager.confirm()` 的「首次即设置密码」语义直接落库，长度仍用
     * PairingManager 的常量判定，不另立一套阈值。
     */
    private fun changeCorePassword() {
        val oldPw = etCoreOldPwd.text.toString()
        val newPw = etCoreNewPwd.text.toString()
        val confirmPw = etCoreNewPwd2.text.toString()

        if (newPw != confirmPw) {
            showCorePwdStatus("两次输入的新密码不一致", success = false)
            return
        }
        if (newPw.length < PairingManager.MIN_PASSWORD_LENGTH ||
            newPw.length > PairingManager.MAX_PASSWORD_LENGTH
        ) {
            showCorePwdStatus(
                "新密码长度需为 ${PairingManager.MIN_PASSWORD_LENGTH}-" +
                    "${PairingManager.MAX_PASSWORD_LENGTH} 位",
                success = false
            )
            return
        }

        if (!settings.devicePasswordSet) {
            settings.setDevicePassword(newPw)
            clearCorePwdFields()
            showCorePwdStatus("配对密码已设置", success = true)
            return
        }

        when (pairingManager.changePassword(
            oldPw,
            newPw,
            LOCAL_UI_CLIENT,
            revokeAllDevices = cbRevokeOnPwdChange.isChecked
        )) {
            is PairingManager.ChangePwdResult.Success -> {
                clearCorePwdFields()
                val revokedNote = if (cbRevokeOnPwdChange.isChecked) "，已踢出所有已配对设备" else ""
                showCorePwdStatus("配对密码已修改$revokedNote", success = true)
                cbRevokeOnPwdChange.isChecked = false
                loadSettings()
            }
            is PairingManager.ChangePwdResult.WrongOldPassword ->
                showCorePwdStatus("当前密码错误", success = false)
            is PairingManager.ChangePwdResult.PasswordLocked ->
                showCorePwdStatus("密码错误次数过多，请稍后再试", success = false)
            is PairingManager.ChangePwdResult.InvalidNewPassword ->
                showCorePwdStatus(
                    "新密码长度需为 ${PairingManager.MIN_PASSWORD_LENGTH}-" +
                        "${PairingManager.MAX_PASSWORD_LENGTH} 位",
                    success = false
                )
            // 本页不提交 Goform 参数，理论上不会走到；保留分支保证 when 穷尽
            is PairingManager.ChangePwdResult.InvalidGoformConfig ->
                showCorePwdStatus("修改失败：设备接口配置无效", success = false)
        }
    }

    private fun clearCorePwdFields() {
        etCoreOldPwd.setText("")
        etCoreNewPwd.setText("")
        etCoreNewPwd2.setText("")
    }

    private fun showCorePwdStatus(message: String, success: Boolean) {
        tvCorePwdStatus.text = message
        tvCorePwdStatus.setTextColor(if (success) COLOR_OK else COLOR_ERR)
        tvCorePwdStatus.visibility = View.VISIBLE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        /** 本机设置页的失败计数标识（[PairingManager] 用它区分来源，不是真实 IP）。 */
        private const val LOCAL_UI_CLIENT = "local-settings-ui"

        /** 只读状态轮询间隔（2026-10-19）：够快能看出"远程改了"，又不至于频繁读盘。 */
        private const val STATUS_REFRESH_MS = 3_000L

        private val COLOR_OK = Color.parseColor("#2E7D32")
        private val COLOR_ERR = Color.parseColor("#C62828")
    }
}
