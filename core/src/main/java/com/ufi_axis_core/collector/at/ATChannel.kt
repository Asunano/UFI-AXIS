package com.ufi_axis_core.collector.at

import android.content.Context
import android.os.Build
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AT 指令通道 — 通过 Android service call (Binder IPC) 直接与 Unisoc modem 通信
 *
 * 无需外部二进制，根据 Android API 等级选择对应的 HIDL 接口：
 * - API > 33 (Android 14+): vendor.sprd.hardware.tool.IToolControl/default 事务码 3
 * - API <= 33 (Android 13-): vendor.sprd.hardware.log.ILogControl/default 事务码 1
 *
 * service call 输出为小端序 hex word，需解析为可读文本。
 */
class ATChannel(private val context: Context) {
    private val tag = "ATChannel"
    private val mutex = Mutex()

    enum class Platform { SPREADTRUM, QUALCOMM, UNKNOWN }

    private var platform: Platform = Platform.UNKNOWN
    private var connected: Boolean = false
    val isConnected: Boolean get() = connected

    /**
     * 初始化 AT 通道：
     * 1. 探测设备平台
     * 2. 发送 AT 测试指令验证连通性
     */
    suspend fun init(): Boolean = mutex.withLock {
        AppLogger.i(tag, "Initializing AT channel (service call mode)...")

        // 探测平台
        platform = detectPlatform()
        AppLogger.i(tag, "Detected platform: $platform, API level: ${Build.VERSION.SDK_INT}")

        // 测试 AT 通道
        connected = true // 临时设为 true 以便测试
        val testOk = sendCommandInternal("AT")?.contains("OK") == true
        if (!testOk) {
            AppLogger.w(tag, "AT init test failed")
            connected = false
        } else {
            AppLogger.i(tag, "AT channel ready ($platform)")
        }
        testOk
    }

    /**
     * 发送 AT 指令并返回响应文本
     */
    suspend fun sendCommand(command: String, timeoutMs: Long = 3000): String? = mutex.withLock {
        sendCommandInternal(command, timeoutMs)
    }

    /**
     * 内部发送逻辑，不加锁。
     * init() 已持有 mutex，直接调用此方法；外部通过 sendCommand() 加锁调用。
     *
     * 根据 Android API 等级构造 service call 命令，通过 root shell 执行，
     * 然后将 hex word 输出解析为可读文本。
     */
    private suspend fun sendCommandInternal(command: String, timeoutMs: Long = 3000): String? {
        AppLogger.at("TX", command)
        try {
            val shellCmd = buildServiceCallCommand(command, slot = 0)
            val result = ShellExecutor.executeAsRoot(shellCmd, timeoutMs + 500)
            val rawText = parseServiceCallHex(result.stdout)
            val response = parseATResponse(rawText)
            AppLogger.at("RX", command, response ?: "ERROR")
            return response
        } catch (e: Exception) {
            AppLogger.e(tag, "AT sendCommand failed", e)
            return null
        }
    }

    /**
     * 根据 Android API 等级构造 service call 命令字符串
     *
     * Android 14+ (API > 33): 使用 IToolControl 接口
     *   service call vendor.sprd.hardware.tool.IToolControl/default 3 i32 <phoneId> s16 "<at_command>"
     *
     * Android 13 及以下 (API <= 33): 使用 ILogControl 接口
     *   service call vendor.sprd.hardware.log.ILogControl/default 1 s16 "miscserver" s16 "sendAt <phoneId> <at_command>"
     */
    private fun buildServiceCallCommand(atCommand: String, slot: Int): String {
        val escaped = atCommand.replace("\"", "\\\"")
        return if (Build.VERSION.SDK_INT > 33) {
            // Android 14+: IToolControl.sendAtCmd(phoneId, atCommand)
            "service call vendor.sprd.hardware.tool.IToolControl/default 3 i32 $slot s16 \"$escaped\""
        } else {
            // Android 13-: ILogControl.sendCommand("miscserver", "sendAt <phoneId> <atCommand>")
            "service call vendor.sprd.hardware.log.ILogControl/default 1 " +
                "s16 \"miscserver\" s16 \"sendAt $slot $escaped\""
        }
    }

    /**
     * 解析 service call 输出的 hex word 为可读文本
     *
     * service call 输出格式类似:
     *   Result: Parcel(
     *     00000000    00000000 00000000 4b4f2b0a '...\n+OK'
     *   )
     *
     * 每个 8 字符 hex word 代表 4 字节小端序数据，
     * 按小端序拆解为 2 字节一组，转为 UTF-16LE 字符。
     */
    private fun parseServiceCallHex(raw: String): String {
        val hexWordPattern = Regex("\\b[0-9a-fA-F]{8}\\b")
        val sb = StringBuilder()
        for (line in raw.lines()) {
            // 跳过单引号后的内容（service call 输出中的 ASCII 预览部分）
            val truncated = line.substringBefore('\'', line)
            for (word in hexWordPattern.findAll(truncated)) {
                val hex = word.value
                if (hex.length != 8) continue
                // 小端序：bytes 排列为 [b3b4, b1b2] → 重组为 [b1, b2, b3, b4]
                val bytes = listOf(
                    hex.substring(6, 8), hex.substring(4, 6),
                    hex.substring(2, 4), hex.substring(0, 2)
                )
                for (i in 0 until 4 step 2) {
                    val lo = bytes[i].toIntOrNull(16) ?: continue
                    val hi = bytes[i + 1].toIntOrNull(16) ?: continue
                    val charCode = (hi shl 8) or lo
                    if (charCode in 32..126 || charCode == '\n'.code || charCode == '\r'.code) {
                        sb.append(charCode.toChar())
                    }
                }
            }
        }
        return sb.toString()
    }

    suspend fun getSignalQuality(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        sendCommand("AT+CSQ")?.let { parseCSQ(it, result) }
        sendCommand("AT+CESQ")?.let { parseCESQ(it, result) }
        sendCommand("AT+COPS?")?.let { result["operator"] = parseCOPS(it) }
        sendCommand("AT+CREG?")?.let { result["registered"] = parseCREG(it) }
        return result
    }

    suspend fun getSimInfo(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        sendCommand("AT+CIMI")?.let { if (!it.contains("ERROR")) result["imsi"] = it.trim() }
        sendCommand("AT+CGSN")?.let { if (!it.contains("ERROR")) result["imei"] = it.trim() }
        return result
    }

    suspend fun sendSms(phoneNumber: String, message: String): Boolean {
        sendCommand("AT+CMGF=1")
        val response = sendCommand("AT+CMGS=\"$phoneNumber\"\r$message\u001A", 15000)
        return response?.contains("+CMGS:") == true
    }

    suspend fun lockBand(rat: String, earfcn: Int): Boolean {
        val cmd = when (rat.uppercase()) {
            "LTE" -> "AT+ZLOCKFREQ=$earfcn"
            "NR" -> "AT+ZLOCKNR=$earfcn"
            else -> return false
        }
        return sendCommand(cmd)?.contains("OK") == true
    }

    suspend fun unlockBand(): Boolean =
        sendCommand("AT+ZLOCKFREQ=0")?.contains("OK") == true

    fun getPlatformInfo(): Map<String, Any> = if (connected) {
        mapOf("platform" to platform.name, "connected" to true, "method" to "service_call")
    } else {
        mapOf("connected" to false)
    }

    private suspend fun detectPlatform(): Platform {
        val cpuInfo = ShellExecutor.executeAsRoot("cat /proc/cpuinfo").stdout
        return when {
            cpuInfo.contains("Spreadtrum", true) || cpuInfo.contains("sprd", true) -> Platform.SPREADTRUM
            cpuInfo.contains("Qualcomm", true) || cpuInfo.contains("qcom", true) -> Platform.QUALCOMM
            else -> Platform.UNKNOWN
        }
    }

    private fun parseATResponse(raw: String): String? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.filter { !it.startsWith("AT") && it != "OK" && it != "ERROR" }
            .joinToString("\n").ifEmpty { if (raw.contains("OK")) "OK" else null }
    }

    private fun parseCSQ(response: String, result: MutableMap<String, Any>) {
        val m = Regex("\\+CSQ:\\s*(\\d+),(\\d+)").find(response) ?: return
        val rssi = m.groupValues[1].toIntOrNull() ?: 99
        val rssiDbm = if (rssi in 0..31) -113 + rssi * 2 else -113
        result["rssi"] = rssiDbm
        result["signal_level"] = when { rssi >= 20 -> "Excellent"; rssi >= 15 -> "Good"; rssi >= 10 -> "Fair"; rssi >= 5 -> "Poor"; else -> "No Signal" }
    }

    private fun parseCESQ(response: String, result: MutableMap<String, Any>) {
        // 匹配标准 6 字段 + 可选扩展字段（ZTE 5G NSA 设备额外返回 NR 信号指标）
        val m = Regex("\\++CESQ:\\s*(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)(?:,(\\d+),(\\d+),(\\d+))?").find(response) ?: return
        val rxlev = m.groupValues[1].toIntOrNull() ?: 99
        val rsrqIdx = m.groupValues[5].toIntOrNull() ?: 255
        val rsrpIdx = m.groupValues[6].toIntOrNull() ?: 255

        // 标准 LTE 字段 (SS-RSRP/SS-RSRQ, 3GPP TS 36.133)
        // SS-RSRP: index 0-97 → -140 ~ -43 dBm, step ≈ 0.38dB, 255=unknown
        if (rsrpIdx != 255) result["rsrp"] = -140 + rsrpIdx * 97 / 254
        // SS-RSRQ: index 0-34 → -19.5 ~ -2.5 dB, 255=unknown
        if (rsrqIdx != 255) result["rsrq"] = (-19.5 + rsrqIdx * 17.0 / 254).toInt()

        // 5G NSA 扩展字段 7-9 — 仅在标准字段为 255(unknown) 时回退使用
        // ZTE 设备标准 LTE 字段有效时与 Goform 一致，扩展字段编码不同须作为后备
        if (m.groupValues.size > 7 && m.groupValues[7].isNotEmpty()) {
            val nrRsrpIdx = m.groupValues[7].toIntOrNull() ?: 255
            val nrSinrIdx = m.groupValues[8].toIntOrNull() ?: 255
            val nrRsrqIdx = m.groupValues[9].toIntOrNull() ?: 255
            // RSRP: 仅当标准字段无效时回退
            if (rsrpIdx == 255 && nrRsrpIdx != 255) {
                result["rsrp"] = -140 + nrRsrpIdx * 97 / 254
            }
            // SINR: CESQ 标准字段无 SINR，始终从扩展字段取
            if (nrSinrIdx != 255) {
                result["sinr"] = -20 + nrSinrIdx * 40 / 254
            }
            // RSRQ: 仅当标准字段无效时回退
            if (rsrqIdx == 255 && nrRsrqIdx != 255) {
                result["rsrq"] = (-19.5 + nrRsrqIdx * 17.0 / 254).toInt()
            }
        }

        // RSSI 补充：CSQ=99(unknown) 但 CESQ rxlev 有效时估算
        if (rxlev != 99 && !result.containsKey("rssi")) {
            result["rssi"] = -110 + rxlev
        }
    }

    private fun parseCOPS(response: String): String =
        Regex("\\+COPS:\\s*\\d+,\\d+,\"([^\"]+)\"").find(response)?.groupValues?.get(1) ?: "Unknown"

    private fun parseCREG(response: String): Boolean {
        val s = Regex("\\+CREG:\\s*\\d+,(\\d+)").find(response)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return s == 1 || s == 5
    }

    fun stop() {
        connected = false
    }
}
