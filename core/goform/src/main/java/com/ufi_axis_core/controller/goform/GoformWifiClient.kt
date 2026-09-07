package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import com.ufi_axis_core.util.AppLogger
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Goform WiFi 管理客户端
 *
 * 从 GoformClient 拆分，负责：
 * - WiFi 状态/热点/客户端查询
 * - WiFi 基础设置（SSID/加密/密码/最大连接数）
 * - WiFi 功率
 * - WiFi 休眠
 * - WiFi 连接二维码
 *
 * @param profile 字段映射表；传 null 关闭归一化（原样透传设备字段，见 [GoformFieldMapper]）
 */
class GoformWifiClient(
    private val client: GoformClient,
    profile: DeviceProfile?,
) {
    private val tag = "GoformWifi"
    private val fields = GoformFieldMapper(profile)
    private val writer = GoformSettingWriter(client, profile)

    // ==================== WiFi 查询 ====================

    suspend fun getWifiModuleInfo(): JsonObject? {
        return client.query(listOf("queryWiFiModuleSwitch", "queryAccessPointInfo"))
    }

    /**
     * 获取 WiFi 连接二维码图片。
     *
     * 与其它 goform 调用不同，这个端点返回的是**图片字节流**而不是 JSON：
     * `/goform/goform_get_file_process/{chip}_ssid{index}_qrcode_wifikey`。
     * 图片由设备按当前 SSID/密码实时生成，所以改完 WiFi 配置重新拉一次就是新的。
     *
     * @return (图片字节, Content-Type)；非 2xx、空响应、拿到非图片内容都返回 null。
     */
    /**
     * 最近一次二维码读取失败的原因（供 `/api/wifi/qrcode` 把真因带进 503 响应）。
     *
     * 之前失败只在 logcat 里，前端永远只看到一句"无法从设备读取 WiFi 二维码"，
     * 没有 adb 就没法判断是连不上、404 还是拿到了登录页。
     */
    @Volatile
    var lastQrCodeFailure: String = ""
        private set

    suspend fun getWifiQrCode(chip: String = "chip1", ssidIndex: Int = 1): Pair<ByteArray, String>? {
        // 2026-08-30：这里原来有 `if (!client.ensureLogin()) return null` 的硬门禁 ——
        // 该文件端点**不需要鉴权**（实测 curl 无 Cookie 无痕直接拿到 PNG），而 ensureLogin()
        // 会因为设备无密码/登录接口差异等原因返回 false，于是请求根本没发出去。
        // 但 base url 的解析原来搭在 ensureLogin() 里，所以这里必须自己解析一次，
        // 否则 baseUrl() 只是未验证的拼装值（端口/宿主不对就直接连不上）。
        client.ensureBaseUrlResolved()

        // 候选文件名：先按请求的频段/序号，失败再退回已知可用的 chip1_ssid1。
        // 有些固件只生成 2.4G 那一张（chip2_ssid1_… 直接 404），此时给用户一张能用的
        // 比什么都不显示更有意义 —— 二维码内容是 SSID/密码，两个频段通常同密码。
        val names = linkedSetOf(
            "${chip}_ssid${ssidIndex}_qrcode_wifikey",
            "chip1_ssid1_qrcode_wifikey"
        )
        val failures = mutableListOf<String>()
        for (name in names) {
            val url = "${client.baseUrl()}/goform/goform_get_file_process/$name"
            val result = try {
                fetchQrCode(url, name, failures)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "$name: ${e.javaClass.simpleName}: ${e.message}"
                null
            }
            if (result != null) {
                lastQrCodeFailure = ""
                return result
            }
        }
        lastQrCodeFailure = failures.joinToString("; ")
        AppLogger.w(tag, "getWifiQrCode 全部候选失败: $lastQrCodeFailure (base=${client.baseUrl()})")
        return null
    }

    /** 拉取单个候选文件名；失败原因追加到 [failures]，成功返回 (字节, Content-Type)。 */
    private suspend fun fetchQrCode(
        url: String,
        name: String,
        failures: MutableList<String>
    ): Pair<ByteArray, String>? {
        val resp = client.httpGet(url)
        if (!resp.status.isSuccess()) {
            failures += "$name: HTTP ${resp.status.value}"
            return null
        }
        val bytes = resp.readBytes()
        if (bytes.isEmpty()) {
            failures += "$name: 空响应"
            return null
        }
        // 必须验字节头：session 失效或文件不存在时设备**不一定回 4xx**，
        // 可能是 200 + 登录页/错误页 HTML，直接当图片透传就是一张永远解不出来的"二维码"。
        if (!looksLikeImage(bytes)) {
            failures += "$name: 非图片(${bytes.size}B, 头=${
                bytes.take(8).joinToString("") { "%02x".format(it) }
            })"
            return null
        }
        // 部分固件不带 Content-Type 或给 text/html，只在明确是 image/* 时采信，否则按 PNG 处理
        val contentType = resp.headers[HttpHeaders.ContentType]
            ?.takeIf { it.startsWith("image/") } ?: "image/png"
        AppLogger.i(tag, "getWifiQrCode ok: $name (${bytes.size}B, $contentType)")
        return bytes to contentType
    }


    /**
     * 按魔数判断是不是图片（PNG / JPEG / GIF / BMP）。
     *
     * 不看 Content-Type：ZTE 固件对文件端点的 Content-Type 本来就不可靠（常给 text/html），
     * 而登录页 HTML 也可能带 200 + 任意 Content-Type，只有字节头骗不了人。
     */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            // PNG: 89 50 4E 47
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> true
            // JPEG: FF D8 FF
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> true
            // GIF: "GIF8"
            b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38 -> true
            // BMP: "BM"
            b(0) == 0x42 && b(1) == 0x4D -> true
            else -> false
        }
    }


    /**
     * 获取当前 WiFi 热点配置（AuthMode/EncrypType/Password/SSID 等），
     * 用于修改单项参数时保留其他参数不被重置
     */
    internal suspend fun getCurrentWifiConfig(): Map<String, String> {
        val info = getWifiModuleInfo()
        val config = mutableMapOf<String, String>()
        if (info != null) {
            val list = info["ResponseList"]?.jsonArray
            if (list != null && list.isNotEmpty()) {
                val allAps = list.mapNotNull { try { it.jsonObject } catch (_: Exception) { null } }
                val ap = allAps.firstOrNull {
                    it["AccessPointSwitchStatus"]?.jsonPrimitive?.contentOrNull == "1"
                } ?: allAps.firstOrNull()
                if (ap != null) {
                    ap["AuthMode"]?.jsonPrimitive?.contentOrNull?.let { config["AuthMode"] = it }
                    ap["EncrypType"]?.jsonPrimitive?.contentOrNull?.let { config["EncrypType"] = it }
                    ap["SSID"]?.jsonPrimitive?.contentOrNull?.let { config["SSID"] = it }
                    // 【2026-08-23修复】Password 是 Base64 编码的，需要解码后返回明文
                    ap["Password"]?.jsonPrimitive?.contentOrNull?.let { pwd ->
                        try {
                            // 尝试 Base64 解码，如果成功则返回明文密码
                            val decodedPwd = client.base64Decode(pwd)
                            config["Password"] = decodedPwd
                        } catch (e: Exception) {
                            // 如果解码失败，可能是明文密码或未设置密码
                            config["Password"] = pwd
                        }
                    }
                    ap["ChipIndex"]?.jsonPrimitive?.contentOrNull?.let { config["ChipIndex"] = it }
                    ap["ApMaxStationNumber"]?.jsonPrimitive?.contentOrNull?.let { config["ApMaxStationNumber"] = it }
                    ap["ApBroadcastDisabled"]?.jsonPrimitive?.contentOrNull?.let { config["ApBroadcastDisabled"] = it }
                }
            }
        }
        return config
    }

    suspend fun getWifiSettings(): JsonObject? {
        return client.query(listOf(
            "wifi_chip1_ssid1_ssid", "wifi_onoff_state", "wifi_access_sta_num",
            "wifi_chip1_ssid1_access_sta_num", "wifi_5g_enable", "wifi_enable",
            "wifi_chip1_ssid1_passphrase", "wifi_chip",
            "wifi_chip1_ssid1_auth_mode", "wifi_chip1_ssid1_encryp_type",
            "wifi_chip1_ssid1_max_sta_num", "wifi_chip1_ssid1_broadcast_ssid"
        ))
    }

    /**
     * `/api/wifi/settings` 的唯一数据来源：扁平字段查询 + module-info 合并后**归一化**。
     *
     * 为什么在这一层合并：两份响应互补（扁平查询给 `wifi_*` 键，module-info 给
     * `ResponseList` 里的 AP 对象），而归一化必须一次看到全部输入才能按别名链定优先级。
     * 原来这段在 `DataHub.getWifiSettingsMerged` 里，还要路由把 `base64Decode` 传进来 —
     * 现在密码解码由 profile 的 `WIFI_PASSWORD_DECODER` 负责，上层不再碰设备字段。
     *
     * @return 只含 [DeviceFields.WifiSettings] 登记字段的对象；两个查询都失败时返回空对象。
     */
    suspend fun getWifiSettingsMerged(): JsonObject {
        val settings = getWifiSettings()
        val moduleInfo = try {
            getWifiModuleInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "getWifiModuleInfo failed: ${e.message}")
            null
        }
        val raw = buildJsonObject {
            settings?.forEach { (k, v) -> put(k, v) }
            // module-info 后放：ResponseList / WiFiModuleSwitch 与扁平键不重名，
            // 真正的优先级由 profile 里的别名链顺序表达（ZTE 原名在前）
            moduleInfo?.forEach { (k, v) -> put(k, v) }
        }
        return fields.normalize(FieldGroup.WIFI_SETTINGS, raw) ?: JsonObject(emptyMap())
    }


    /**
     * 已连接客户端。`station_list` 必须单独查（与其它 cmd 组合时设备返回空，2026-08-23 修复）。
     *
     * 设备有两种返回形态：`{"station_list":[...]}`、裸数组，且数组本身还可能是**双重编码的字符串**。
     * 归一化后一律是 `{"station_list":[{...}]}` 的真数组，元素键统一成
     * `hostname`/`ip_addr`/`mac_addr`（见 `ZteGoformProfile.normalizeStationLists`）。
     */
    suspend fun getConnectedClients(): JsonObject? {
        val raw = client.querySingle("station_list")?.let {
            when (it) {
                is JsonObject -> it
                is JsonArray -> JsonObject(mapOf("station_list" to it))
                else -> null
            }
        }
        return fields.normalize(FieldGroup.WIFI_CLIENTS, raw)
    }

    // ==================== 接入控制名单（拉黑） ====================

    /**
     * 读回接入控制名单（`cmd=queryDeviceAccessControlList`，单 cmd 查询，无 multi_data）。
     *
     * 设备返回：
     * ```json
     * { "AclMode": "2", "WhiteMacList": "", "BlackMacList": "2a:ed:87:b3:e8:29;",
     *   "WhiteNameList": "", "BlackNameList": "OPPO-Find-X7;" }
     * ```
     * 这里就把分号串拆成结构化的 [AclEntry]，设备侧的 CamelCase 键名不出本模块
     * （route 只见 [AclSnapshot]）。Mac 与 Name 按下标配对，缺名字的补空串。
     *
     * 写侧是**整表替换**，所以调用方每次都得先读这个再改。
     */
    suspend fun getAccessControlList(): AclSnapshot? {
        val obj = client.querySingle("queryDeviceAccessControlList") as? JsonObject ?: return null
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return AclSnapshot(
            mode = str("AclMode").ifEmpty { ZteGoformProfile.ACL_MODE_BLACKLIST },
            black = zipAcl(str("BlackMacList"), str("BlackNameList")),
            white = zipAcl(str("WhiteMacList"), str("WhiteNameList")),
        )
    }

    /**
     * 整表下发接入控制名单。
     *
     * 四条名单一次全发（[SettingKey.WIFI_ACL] 的 encode 负责补分号 / 空 key），
     * 所以调用方传进来的必须是**替换后的完整名单**，不是增量。
     */
    suspend fun setAccessControlList(
        black: List<AclEntry>,
        white: List<AclEntry> = emptyList(),
        mode: String? = null,
    ): WriteOutcome = writer.writeChecked(
        SettingKey.WIFI_ACL,
        mapOf(
            "mode" to (mode?.takeIf { it.isNotBlank() } ?: ZteGoformProfile.ACL_MODE_BLACKLIST),
            "black_macs" to black.map { it.mac },
            "black_names" to black.map { it.name },
            "white_macs" to white.map { it.mac },
            "white_names" to white.map { it.name },
        )
    )

    /** `"a;b;"` + `"n1;n2;"` → `[AclEntry(a,n1), AclEntry(b,n2)]`；名字少了补空串。 */
    private fun zipAcl(macList: String, nameList: String): List<AclEntry> {
        val macs = macList.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val names = nameList.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        return macs.mapIndexed { i, mac -> AclEntry(mac = mac, name = names.getOrElse(i) { "" }) }
    }

    // ==================== WiFi 设置 ====================


    suspend fun setWifiConfig(
        ssid: String? = null,
        authMode: String? = null,
        encrypType: String? = null,
        passphrase: String? = null,
        maxStaNum: Int? = null,
        broadcastDisabled: Int? = null,
        chipIndex: String? = null
    ): Boolean {
        val params = mutableMapOf("isTest" to "false", "goformId" to "setAccessPointInfo")
        val current = if (authMode == null || ssid == null) getCurrentWifiConfig() else emptyMap()

        val effectiveSsid = ssid?.trim() ?: current["SSID"]?.trim()
        effectiveSsid?.let { params["SSID"] = it }

        val effectiveAuth = authMode ?: current["AuthMode"]
        if (effectiveAuth != null) {
            params["AuthMode"] = effectiveAuth
            if (effectiveAuth == "OPEN") {
                params["EncrypType"] = "NONE"
            } else {
                params["EncrypType"] = encrypType ?: current["EncrypType"] ?: "CCMP"
            }
        } else {
            params["AuthMode"] = "WPA2PSK"
            params["EncrypType"] = encrypType ?: "CCMP"
        }

        val effectiveEncryp = params["EncrypType"]
        if (effectiveAuth != "OPEN" && effectiveEncryp != "NONE") {
            val effectivePwd = passphrase ?: current["Password"]?.let { client.base64Decode(it) }
            effectivePwd?.let { params["Password"] = client.base64Encode(it) }
        }

        maxStaNum?.let { params["ApMaxStationNumber"] = it.toString() }
        params["ApBroadcastDisabled"] = (broadcastDisabled ?: current["ApBroadcastDisabled"]?.toIntOrNull() ?: 0).toString()
        params["ApIsolate"] = "0"
        params["AccessPointIndex"] = "0"
        params["ChipIndex"] = chipIndex ?: current["ChipIndex"] ?: "0"
        AppLogger.i(tag, "setWifiConfig: SSID=${params["SSID"]} Auth=${params["AuthMode"]} Enc=${params["EncrypType"]}")
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun setWifiPower(level: Int): Boolean {

        return client.isGoformSuccess(client.goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_WIFI_POWER",
            "wifiPowerLevel" to level.toString()
        )))
    }

    suspend fun setWifiSSID(ssid: String): Boolean {

        val current = getCurrentWifiConfig()
        val authMode = current["AuthMode"] ?: "WPA2PSK"
        val encrypType = current["EncrypType"] ?: "CCMP"
        val chipIndex = current["ChipIndex"] ?: "0"
        val params = mutableMapOf(
            "isTest" to "false", "goformId" to "setAccessPointInfo",
            "SSID" to ssid.trim(), "AuthMode" to authMode, "EncrypType" to encrypType,
            "AccessPointIndex" to "0", "ChipIndex" to chipIndex,
            "ApBroadcastDisabled" to (current["ApBroadcastDisabled"] ?: "0"),
            "ApIsolate" to "0"
        )
        if (authMode != "OPEN" && encrypType != "NONE") {
            current["Password"]?.let { params["Password"] = it }
        }
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun setWifiEnabled(enabled: Boolean): Boolean {
        return if (enabled) {
            client.isGoformSuccess(client.goformPost(mapOf(
                "isTest" to "false", "goformId" to "switchWiFiChip",
                "ChipEnum" to "chip1", "GuestEnable" to "0"
            )))
        } else {
            client.isGoformSuccess(client.goformPost(mapOf(
                "isTest" to "false", "goformId" to "switchWiFiModule",
                "SwitchOption" to "0"
            )))
        }
    }

    suspend fun setWifiPassword(password: String): Boolean {
        val current = getCurrentWifiConfig()
        val authMode = current["AuthMode"] ?: "WPA2PSK"
        val encrypType = if (authMode == "OPEN") "NONE" else (current["EncrypType"] ?: "CCMP")
        val chipIndex = current["ChipIndex"] ?: "0"
        val params = mutableMapOf(
            "isTest" to "false", "goformId" to "setAccessPointInfo",
            "Password" to client.base64Encode(password),
            "AuthMode" to authMode, "EncrypType" to encrypType,
            "ApBroadcastDisabled" to (current["ApBroadcastDisabled"] ?: "0"),
            "ApIsolate" to "0", "AccessPointIndex" to "0",
            "ChipIndex" to chipIndex
        )
        current["SSID"]?.let { params["SSID"] = it }
        return client.isGoformSuccess(client.goformPost(params))
    }

    suspend fun setWifiSleep(time: String): WriteOutcome =
        writer.writeChecked(SettingKey.WIFI_SLEEP_IDLE_MINUTES, time)
}

/** 接入控制名单里的一台设备。`name` 可能为空串（设备侧名单允许只有 MAC）。 */
data class AclEntry(val mac: String, val name: String)

/**
 * 一次 `queryDeviceAccessControlList` 的快照。
 *
 * [mode] 直接透传设备的 `AclMode`（`"2"` = 黑名单生效），本项目只用黑名单，
 * 白名单读出来只为「整表回写时不把它冲掉」。
 */
data class AclSnapshot(
    val mode: String,
    val black: List<AclEntry>,
    val white: List<AclEntry>,
)


