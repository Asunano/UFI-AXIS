package com.ufi_axis_core

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.ufi_axis_core.util.AppSettings

class SettingsActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var etPort: EditText
    private lateinit var cbAutoStart: CheckBox
    private lateinit var etGoformIp: EditText
    private lateinit var etGoformPort: EditText
    private lateinit var etGoformPassword: EditText
    private lateinit var cbPairingEnabled: CheckBox
    private lateinit var etPairingMaxDevices: EditText
    private lateinit var tvPairingCurrent: TextView
    private lateinit var tvSaved: TextView
    private var goformPwdVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings.getInstance(this)
        etPort = findViewById(R.id.etPort)
        cbAutoStart = findViewById(R.id.cbAutoStart)
        etGoformIp = findViewById(R.id.etGoformIp)
        etGoformPort = findViewById(R.id.etGoformPort)
        etGoformPassword = findViewById(R.id.etGoformPassword)
        cbPairingEnabled = findViewById(R.id.cbPairingEnabled)
        etPairingMaxDevices = findViewById(R.id.etPairingMaxDevices)
        tvPairingCurrent = findViewById(R.id.tvPairingCurrent)
        tvSaved = findViewById(R.id.tvSaved)
        val btnToggleGoformPwd = findViewById<Button>(R.id.btnToggleGoformPwd)
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
}
