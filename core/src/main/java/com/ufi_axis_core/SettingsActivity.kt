package com.ufi_axis_core

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.PairedDeviceStore

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
    private var goformPwdVisible = false
    private var corePwdVisible = false

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
    }

    private fun loadSettings() {
        etPort.setText(settings.port.toString())
        cbAutoStart.isChecked = settings.autoStartOnBoot
        etGoformIp.setText(settings.goformIp)
        etGoformPort.setText(settings.goformPort.toString())
        etGoformPassword.setText(settings.goformPassword)
        cbPairingEnabled.isChecked = settings.pairingEnabled
        etPairingMaxDevices.setText(settings.pairingMaxDevices.toString())
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
    }

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

        when (pairingManager.changePassword(oldPw, newPw, LOCAL_UI_CLIENT)) {
            is PairingManager.ChangePwdResult.Success -> {
                clearCorePwdFields()
                showCorePwdStatus("配对密码已修改", success = true)
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

    private companion object {
        /** 本机设置页的失败计数标识（[PairingManager] 用它区分来源，不是真实 IP）。 */
        private const val LOCAL_UI_CLIENT = "local-settings-ui"
        private val COLOR_OK = Color.parseColor("#2E7D32")
        private val COLOR_ERR = Color.parseColor("#C62828")
    }
}
