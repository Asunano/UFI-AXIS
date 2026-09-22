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
     *
     * **返回值契约（读明文 / 写 base64 的不对称，改本函数前先读计划书 §11.3）**：
     * 返回的 `Password` 是**明文**（设备侧是 base64，这里已经解过一次）。
     * 而 `setAccessPointInfo` 的 `Password` 字段要的是 **base64** ——
     * 所以任何拿这个值回写设备的地方**必须自己 `client.base64Encode(...)`**，
     * 既不能原样发（会把口令写成明文串），也不能再 `base64Decode` 一次（明文不是合法
     * base64 时会拿到空串）。这个不对称就是 2026-09-21 修的那两处 Password bug 的根因。
     *
     * 其余键（SSID/AuthMode/EncrypType/ChipIndex/Ap*）都是设备原值，回写时原样透传。
     *
     * 陷阱：[GoformClient.base64Decode] 解码失败时**返回空串**而不是抛异常
     * （`GoformClient.kt:868`，catch 里只打日志后 `return ""`），所以下面那个 try/catch
     * 的 fallback 基本不会命中 —— 非法 base64 走的是「`config["Password"] = ""`」这条路。
     * **不要改它的行为**：`/api/wifi/settings` 的读路径依赖现有语义。
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


    /**
     * 改整份 WiFi 热点配置。三个写入口里唯一「调用方可以逐项指定」的那个。
     *
     * 读回条件保持原样：只要 [authMode] 或 [ssid] 有一个没给，就得先 [getCurrentWifiConfig]
     * 把当前值捞回来 —— 设备侧 `setAccessPointInfo` 是**整表替换**，漏发一个键那一项就按
     * 设备默认值走（仓库为此出过两次事故，见 [SettingKey.WIFI_AP_CONFIG] 的 KDoc）。
     * 两个都给了就不读：那是「用户在设置页把两项都填了」的路径，省一次查询。
     *
     * 合并规则本身在 [mergeApConfigParams]（纯函数，可单测）；base64 编码 / `ApIsolate` /
     * `AccessPointIndex` 这些设备侧细节现在全在 profile 的 encode 里，本文件不再重复一份。
     */
    suspend fun setWifiConfig(
        ssid: String? = null,
        authMode: String? = null,
        encrypType: String? = null,
        passphrase: String? = null,
        maxStaNum: Int? = null,
        broadcastDisabled: Int? = null,
        chipIndex: String? = null
    ): Boolean {
        val current = if (authMode == null || ssid == null) getCurrentWifiConfig() else emptyMap()
        val params = mergeApConfigParams(
            current = current,
            ssid = ssid,
            authMode = authMode,
            encrypType = encrypType,
            passphrase = passphrase,
            maxStaNum = maxStaNum,
            broadcastDisabled = broadcastDisabled,
            chipIndex = chipIndex,
        )
        // 只打三个非敏感键：口令（哪怕是编码后的）不进日志
        AppLogger.i(
            tag,
            "setWifiConfig: SSID=${params["ssid"]} Auth=${params["auth_mode"]} Enc=${params["encrypt_type"]}"
        )
        return writer.write(SettingKey.WIFI_AP_CONFIG, params)
    }

    /** @param level 发射功率档位（值域 0~2 的判据在 profile 的 validate 里，与 WifiRoutes 同一份事实）。 */
    suspend fun setWifiPower(level: Int): Boolean = writer.write(SettingKey.WIFI_POWER, level)

    /**
     * 只改 SSID —— 其余 AP 配置必须从设备读回后原样带上（整表替换，见 [mergeApSsidParams]）。
     */
    suspend fun setWifiSSID(ssid: String): Boolean {
        val current = getCurrentWifiConfig()
        return writer.write(SettingKey.WIFI_AP_CONFIG, mergeApSsidParams(current, ssid))
    }

    /**
     * WiFi 总开关。
     *
     * 设备侧开/关是**两条不同的命令**（开 `switchWiFiChip` + `ChipEnum=chip1&GuestEnable=0`，
     * 关 `switchWiFiModule` + `SwitchOption=0`），命令选择与参数集都在
     * [SettingKey.WIFI_ENABLED] 的 WriteSpec 里 —— 这里不再留 if。
     */
    suspend fun setWifiEnabled(enabled: Boolean): Boolean =
        writer.write(SettingKey.WIFI_ENABLED, enabled)

    /**
     * 只改口令 —— 其余 AP 配置从设备读回后原样带上（整表替换，见 [mergeApPasswordParams]）。
     */
    suspend fun setWifiPassword(password: String): Boolean {
        val current = getCurrentWifiConfig()
        return writer.write(SettingKey.WIFI_AP_CONFIG, mergeApPasswordParams(current, password))
    }

    suspend fun setWifiSleep(time: String): WriteOutcome =
        writer.writeChecked(SettingKey.WIFI_SLEEP_IDLE_MINUTES, time)

    /**
     * 「读回当前值 → 合并 → 交给 [SettingKey.WIFI_AP_CONFIG] 的 encode」这一段的纯函数部分。
     *
     * ## 为什么抽成 companion 的纯函数
     *
     * 本类持有的是**具体类** [GoformClient]（阶段 1 才接口化），端到端路径注入不了假对象，
     * 于是「current + 入参 → canonical params」这段判断以前只能靠真机验证。抽出来之后
     * 三个入口各自放哪几个键就能逐字断言（`GoformWifiApParamsTest`）——
     * 设备侧 `setAccessPointInfo` 是整表替换，多一个键 / 少一个键都会改掉 AP 的某一项配置，
     * 这里错一个键是「读取毫无影响、写入静默改坏设备」的错误。做法同
     * [GoformSettingWriter] 的 companion 纯函数。
     *
     * ## 三个入口共用的契约（值全是**明文 / 原值**，设备侧编码由 profile 负责）
     *
     * - 键不放 == 放 null → 该项不发（`auth_mode` / `encrypt_type` / `broadcast_disabled` /
     *   `chip_index` 有 profile 侧缺省值，不发就是走缺省）
     * - `passphrase` 只要**键在**就会发 `Password`（空串也发），所以「不想动口令」必须不放这个键
     * - `ApIsolate` / `AccessPointIndex` / base64 编码 / `OPEN` 时强制 `NONE`：全在 profile
     */
    internal companion object {

        /** 设备读不到 `AuthMode` 时最终生效的值（与 profile 的缺省档一致，用于本地算口令发送条件）。 */
        private const val AUTH_DEFAULT = "WPA2PSK"

        /** 开放热点。 */
        private const val AUTH_OPEN = "OPEN"

        /** 读不到 `EncrypType` 时最终生效的值（同上）。 */
        private const val ENCRYP_DEFAULT = "CCMP"

        /** [AUTH_OPEN] 下唯一合法的加密方式。 */
        private const val ENCRYP_NONE = "NONE"

        /**
         * [setWifiConfig] 的参数合并。
         *
         * 口令的发送条件按「**最终会生效的**认证 / 加密方式」算，不是按入参算 ——
         * 入参为 null 时生效的是 profile 缺省（WPA2PSK / CCMP），此时口令是要带上的；
         * 这是改造前那段 `effectiveAuth != "OPEN" && effectiveEncryp != "NONE"` 的原义，逐字保住。
         *
         * 2026-09-21 修过的坑别再踩：[getCurrentWifiConfig] 返回的 `Password` **已经是明文**，
         * 这里原来还会再 `base64Decode` 一次 —— 明文不是合法 base64 时解码返回空串，
         * 于是「只改 SSID / 加密方式、不传 passphrase」会把设备侧口令清空。
         * 现在明文直接进 `passphrase`，base64(UTF-8) 由 profile 的 encode 做，只编一次。
         */
        internal fun mergeApConfigParams(
            current: Map<String, String>,
            ssid: String?,
            authMode: String?,
            encrypType: String?,
            passphrase: String?,
            maxStaNum: Int?,
            broadcastDisabled: Int?,
            chipIndex: String?,
        ): Map<String, Any?> {
            val params = LinkedHashMap<String, Any?>()
            // trim 在调用方这一侧做：profile 明确不猜「要不要去空格」
            (ssid?.trim() ?: current["SSID"]?.trim())?.let { params["ssid"] = it }
            val auth = authMode ?: current["AuthMode"]
            auth?.let { params["auth_mode"] = it }
            val encryp = encrypType ?: current["EncrypType"]
            encryp?.let { params["encrypt_type"] = it }

            val effectiveAuth = auth ?: AUTH_DEFAULT
            val effectiveEncryp =
                if (effectiveAuth == AUTH_OPEN) ENCRYP_NONE else (encryp ?: ENCRYP_DEFAULT)
            if (effectiveAuth != AUTH_OPEN && effectiveEncryp != ENCRYP_NONE) {
                (passphrase ?: current["Password"])?.let { params["passphrase"] = it }
            }

            maxStaNum?.let { params["max_sta_num"] = it }
            params["broadcast_disabled"] =
                broadcastDisabled ?: current["ApBroadcastDisabled"]?.toIntOrNull() ?: 0
            (chipIndex ?: current["ChipIndex"])?.let { params["chip_index"] = it }
            return params
        }

        /**
         * [setWifiSSID] 的参数合并：只有 SSID 是新值，其余全是读回来的原值。
         *
         * `max_sta_num` **永不放**：改造前这个入口也没发 `ApMaxStationNumber`
         * （设备侧不发就保持原值，发一个自己猜的值才是改坏配置）。
         *
         * ## 一处刻意的行为变更（2026-09-21 由用户裁决，选方案 A）
         *
         * 改造前这里把 `current["EncrypType"]` 原样发出去，即使 `AuthMode == "OPEN"` ——
         * 于是可能发出 `AuthMode=OPEN` + `EncrypType=CCMP`。走 profile 之后
         * `OPEN` 会被**强制**改成 `NONE`，这是本次唯一一处设备侧报文差异。
         *
         * 裁决理由：`OPEN` + 非 `NONE` 在设备侧本身是矛盾组合（开放热点没有加密算法），
         * 另两个入口（改整份配置 / 只改口令）改造前就都强制 `NONE`，只有这里漏了，属笔误。
         * 统一成 profile 的一份判断，而不是把笔误抄进 profile。
         */
        internal fun mergeApSsidParams(current: Map<String, String>, ssid: String): Map<String, Any?> {
            val params = LinkedHashMap<String, Any?>()
            params["ssid"] = ssid.trim()
            val auth = current["AuthMode"]
            auth?.let { params["auth_mode"] = it }
            val encryp = current["EncrypType"]
            encryp?.let { params["encrypt_type"] = it }
            // 口令按「最终生效值」判：读不到就是 profile 缺省 WPA2PSK / CCMP，那种情况要带上口令
            if ((auth ?: AUTH_DEFAULT) != AUTH_OPEN && (encryp ?: ENCRYP_DEFAULT) != ENCRYP_NONE) {
                // current["Password"] 是**明文**，base64 由 profile 编一次（改造前这里直发明文，
                // 等于让设备把「明文按 base64 解出来的字节」当新口令 —— 改 SSID 把口令写坏的那个 bug）
                current["Password"]?.let { params["passphrase"] = it }
            }
            current["ApBroadcastDisabled"]?.toIntOrNull()?.let { params["broadcast_disabled"] = it }
            current["ChipIndex"]?.let { params["chip_index"] = it }
            return params
        }

        /**
         * [setWifiPassword] 的参数合并：只有口令是新值，其余全是读回来的原值。
         *
         * `passphrase` **无条件放**（`OPEN` 也放）：这是「只改口令」这个入口的**意图** ——
         * 用户点的就是改口令，不能因为当前是开放热点就把他输入的值悄悄丢掉。
         * 所以这个条件不能搬进 profile 的 encode（另两处是有条件的），见
         * [SettingKey.WIFI_AP_CONFIG] 的 KDoc。
         *
         * `max_sta_num` 同样永不放；`SSID` 不 trim —— 读回来的原值原样送回去。
         */
        internal fun mergeApPasswordParams(
            current: Map<String, String>,
            password: String,
        ): Map<String, Any?> {
            val params = LinkedHashMap<String, Any?>()
            current["SSID"]?.let { params["ssid"] = it }
            current["AuthMode"]?.let { params["auth_mode"] = it }
            // OPEN 时 profile 会强制 NONE —— 与改造前这个入口的行为一致
            current["EncrypType"]?.let { params["encrypt_type"] = it }
            params["passphrase"] = password
            current["ApBroadcastDisabled"]?.toIntOrNull()?.let { params["broadcast_disabled"] = it }
            current["ChipIndex"]?.let { params["chip_index"] = it }
            return params
        }
    }
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


