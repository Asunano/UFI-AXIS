package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.NormalizedFields
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.devicespi.WriteOutcome
import com.ufi_axis_core.devicespi.adapter.AclEntry
import com.ufi_axis_core.devicespi.adapter.AclSnapshot
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
 * @param commandProfile 命令表来源，非空；由装配层传选中插件的 profile
 *
 * ## 两份 profile 的分工
 *
 * - [profile]（**可空**）只管**字段归一化**：`null` = 排障开关关掉了归一化。
 *   它决定 `GoformFieldMapper.enabled` / `profileId`，而那个 `profileId` 一路传到
 *   `/api/diagnose` 的 `device_profile.normalization_enabled`（链路见 [GoformSignalClient]），
 *   所以**不许**在本类里给它补非空兜底 —— 那个排障开关会永远报 `true`。
 * - [commandProfile]（**非空**）只喂**命令表**：字段归一化可以关，命令表不能关 ——
 *   没有 cmd 列表连一条 WiFi 查询都发不出去。
 *
 * ## 为什么 [commandProfile] 没有默认值、也不许在本类里兜底
 *
 * 这里原来写的是 `GoformFieldMapper(profile, profile ?: DeviceProfiles.DEFAULT)` ——
 * 命令表由客户端自己回落到**注册表默认插件**的 profile。单插件时两者同值；
 * 接第二台设备之后，「用户打开排障开关（关归一化）」就会顺带把命令表悄悄换成
 * **默认设备**的命令表 —— 排障模式下向 B 设备发 A 设备的 cmd。
 * 一个排障开关不该改变「往设备发什么命令」（同一条判据见 `DeviceRuntime.commandProfile`）。
 *
 * 所以命令表那一份由调用方（`ComponentFactory`，传 `runtime.commandProfile` =
 * **选中插件**的 profile）定下来，构造参数**不给默认值**：默认值等于把选型逻辑散进每个
 * 客户端的签名，换设备要改 N 处且漏一处不报错。口径与 [GoformSmsClient] 一致。
 *
 * ## 批 B1：读方法的归一化保证写在返回类型上
 *
 * 与 [GoformSignalClient] 同一条口径（判据与例外都在那边的类 KDoc 里）：
 * 返回 [NormalizedFields] 的（[getWifiSettingsMerged] / [getConnectedClients]）= 过了归一化闸门，
 * 那个类型在本模块造不出来；仍返回裸 `JsonObject?` 的（[getWifiModuleInfo] / [getWifiSettings]）
 * = 原样透传，它们是前者的两个输入。消费点用 `.values` 解包，对外 JSON 一个字节没变。
 *
 * ## 批 C2：本类的全部 public 成员都在 `WifiControl` 上
 *
 * 读侧（含二维码与接入控制名单）与 `setAccessControlList` 本批也迁进了那个域接口，
 * 上层（`core/api` / `core/src`）不再认识本类型。[AclEntry] / [AclSnapshot] 两个语义上
 * 协议无关的类型随之搬到 `:core:device-spi`，本文件改成 import 它们（不留 typealias）。
 * [getCurrentWifiConfig] 也因此从 `internal` 放开到 public —— 它是那个接口的成员之一，
 * 而 adapter 在另一个模块里（`:core:device-plugins`）。
 */
class GoformWifiClient(
    private val client: GoformTransport,
    profile: DeviceProfile?,
    private val commandProfile: DeviceProfile,
) {
    private val tag = "GoformWifi"
    // 双 profile：可空那份管归一化（排障开关关掉就是 null），非空那份管命令表。
    // 命令表**不在这里兜底** —— 兜底会让排障模式下的命令表悄悄换成默认设备的（见类 KDoc）。
    private val fields = GoformFieldMapper(profile, commandProfile)
    // 写侧同理吃非空那份（阶段 2 批 D1，P1-31）：原来传的是可空 profile，
    // 由 writer 内部 `?: ZteGoformProfile` 兜底 —— 那会让排障模式下的**写命令表**
    // 悄悄换成默认设备的（读侧换字段名是排障想要的，写侧发错命令不是）。
    private val writer = GoformSettingWriter(client, commandProfile)


    // ==================== WiFi 查询 ====================

    /**
     * WiFi 模块开关 + AP 列表（`ResponseList` 里是 AP 对象）。
     *
     * **原样透传，未归一化**（批 B1：返回裸 `JsonObject?`）—— 它是 [getWifiSettingsMerged]
     * 的**两个输入之一**，归一化在那里对合并后的整体做一次（别名链要一次看到全部输入才能定优先级）。
     * 这份原始形状同时也是诊断端点 `GET /api/wifi/module-info` 的出口
     * （`DeviceFields.UNSTABLE_ENDPOINTS` 里登记过的例外）。
     */
    suspend fun getWifiModuleInfo(): JsonObject? {
        // 刻意的**分批**查询，不走 profile 命令表：这 2 个容器命令 + [getWifiSettings] 的 12 个扁平 cmd
        // **合起来**才等于 cmdsFor(WIFI_SETTINGS) 的 14 项，但线上是**两次独立请求**。
        // 合成一次会改变设备侧请求形状 —— 本仓有过 `station_list` 因合并查询被设备吞掉的先例。
        // 字段名的核对依据是计划书 §16 的真机基线（2026-09-22）。
        return client.read(listOf("queryWiFiModuleSwitch", "queryAccessPointInfo"))
    }

    /**
     * 获取 WiFi 连接二维码图片。
     *
     * 与其它 goform 调用不同，这个端点返回的是**图片字节流**而不是 JSON：
     * `/goform/goform_get_file_process/<文件名>`，而**文件名由 profile 给**
     * （[DeviceProfile.qrCodeFileNames]，阶段 2 任务 2.10 / 计划书 §15 的 P1-5）——
     * 本文件只负责拼路径、发请求、验字节头。
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

        // 候选文件名与它们的尝试顺序由 profile 给（阶段 2 任务 2.10）：「先按请求的频段/序号取、
        // 取不到退回已知一定会生成的那一张」整条都是**设备事实**，不是本客户端的策略
        // （理由与去重约定见 [DeviceProfile.qrCodeFileNames]）。
        //
        // 取**非空**的 [commandProfile]：这条读取与命令表同一侧 —— 排障开关（关掉字段归一化）
        // 不该改变「向设备请求哪个文件」，口径同 [GoformNetworkClient] 的两个频段全集方法。
        val names = commandProfile.qrCodeFileNames(chip, ssidIndex)
        if (names.isEmpty()) {
            // 空列表 = 该 profile 没登记二维码文件名（契约里「空列表 ≠ 文件不存在」）。
            // 行为与「所有候选都取不到」**完全一致**：下面的循环一条请求都不发，
            // [lastQrCodeFailure] 落成空串、走同一句 WARN、返回 null（route 于是回不带真因的 503）。
            // 刻意不抛异常：用户点一下「显示二维码」不该崩在读路径上。
            AppLogger.w(
                tag,
                "${commandProfile.id} 未登记 WiFi 二维码文件名（chip=$chip, ssidIndex=$ssidIndex），" +
                    "本次不发任何文件请求 —— 按「所有候选都取不到」处理"
            )
        }
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
     * 所以任何拿这个值回写设备的地方**必须自己做一次 base64(UTF-8) 编码**，
     * 既不能原样发（会把口令写成明文串），也不能再 `decodeDeviceText` 一次（明文不是合法
     * base64 时会拿到空串）。这个不对称就是 2026-09-21 修的那两处 Password bug 的根因。
     *
     * 其余键（SSID/AuthMode/EncrypType/ChipIndex/Ap*）都是设备原值，回写时原样透传。
     *
     * 陷阱：[DeviceTransport.decodeDeviceText] 解码失败时**返回空串**而不是抛异常
     * （`GoformClient.kt:868`，catch 里只打日志后 `return ""`），所以下面那个 try/catch
     * 的 fallback 基本不会命中 —— 非法 base64 走的是「`config["Password"] = ""`」这条路。
     * **不要改它的行为**：`/api/wifi/settings` 的读路径依赖现有语义。
     */
    suspend fun getCurrentWifiConfig(): Map<String, String> {
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
                            val decodedPwd = client.decodeDeviceText(pwd)
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

    /**
     * WiFi 的 12 个扁平字段查询。
     *
     * **原样透传，未归一化**（批 B1：返回裸 `JsonObject?`）—— 同 [getWifiModuleInfo]，
     * 它是 [getWifiSettingsMerged] 的另一个输入，归一化在那里做。
     */
    suspend fun getWifiSettings(): JsonObject? {
        // 刻意的**分批**查询，不走 profile 命令表：本方法的 12 项 + [getWifiModuleInfo] 的 2 个容器命令
        // **合起来**才等于 cmdsFor(WIFI_SETTINGS) 的 14 项，但线上是**两次独立请求**（见 getWifiModuleInfo）。
        // 字段名的核对依据是计划书 §16 的真机基线（2026-09-22）。
        return client.read(listOf(
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
     * 原来这段在 `DataHub.getWifiSettingsMerged` 里，还要路由把 `decodeDeviceText` 传进来 —
     * 现在密码解码由 profile 的 `WIFI_PASSWORD_DECODER` 负责，上层不再碰设备字段。
     *
     * @return 只含 [DeviceFields.WifiSettings] 登记字段的对象；两个查询都失败时返回空对象。
     *   返回 [NormalizedFields]（批 B1）：`.values` 解包出来的 `JsonObject` 与改造前逐字一致。
     *   非空契约保持不变 —— 两个查询都失败时给 [NormalizedFields.EMPTY]（内容就是 `{}`，
     *   与改造前那句 `?: JsonObject(emptyMap())` 等价）。
     */
    suspend fun getWifiSettingsMerged(): NormalizedFields {
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
        return fields.normalize(FieldGroup.WIFI_SETTINGS, raw) ?: NormalizedFields.EMPTY
    }


    /**
     * 已连接客户端。`station_list` 必须单独查（与其它 cmd 组合时设备返回空，2026-08-23 修复）。
     *
     * 设备有两种返回形态：`{"station_list":[...]}`、裸数组，且数组本身还可能是**双重编码的字符串**。
     * 归一化后一律是 `{"station_list":[{...}]}` 的真数组，元素键统一成
     * `hostname`/`ip_addr`/`mac_addr`（见 `ZteGoformProfile.normalizeStationLists`）。
     */
    suspend fun getConnectedClients(): NormalizedFields? {
        val raw = client.readOne("station_list")?.let {
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
        val obj = client.readOne("queryDeviceAccessControlList") as? JsonObject ?: return null
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return AclSnapshot(
            // 设备没报 AclMode 时的兜底档位由 profile 提供（2026-09-25）。
            // 此前这里直读 `ZteGoformProfile.ACL_MODE_BLACKLIST`，绕过了 commandProfile ——
            // 换设备后仍拿 ZTE 的 "2" 兜底，而 "2" 在别家可能是白名单。
            // profile 返回 null = 这台设备没有这个概念，空值原样往上传，不许自己编一个档位。
            mode = str("AclMode").ifEmpty { commandProfile.aclDefaultMode().orEmpty() },
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
            // 不在这里兜底档位（2026-09-25）：`SettingKey.WIFI_ACL` 的 encode 本来就做了
            // 同一件事（缺 mode → profile 的默认档位）。两处都兜的后果是「改了 profile 却
            // 被客户端这一层的硬编码盖住」—— 判据只该有一份，留在 profile 那边。
            "mode" to mode?.takeIf { it.isNotBlank() },
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
     *
     * 返回 [WriteOutcome] 而不是 `Boolean`（与 [setWifiSleep] / [setAccessControlList] 同口径）：
     * `Boolean` 把「参数被值域校验拒绝」「设备明确回失败」「命令没被受理」压成同一个 false，
     * 路由只能一律回 500，用户看到的就只剩一句 `HTTP 500` —— 密码几位、加密组合不合法这类
     * **改一下入参就能过**的原因传不出来。三态保留后由 `respondRejected` 把理由带回客户端。
     */
    suspend fun setWifiConfig(
        ssid: String? = null,
        authMode: String? = null,
        encrypType: String? = null,
        passphrase: String? = null,
        maxStaNum: Int? = null,
        broadcastDisabled: Int? = null,
        chipIndex: String? = null
    ): WriteOutcome {
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
        return writer.writeChecked(SettingKey.WIFI_AP_CONFIG, params)
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
     * 设备侧开/关是**两条不同的命令**（开 `switchWiFiChip` + `ChipEnum=<当前频段>&GuestEnable=0`，
     * 关 `switchWiFiModule` + `SwitchOption=0`），命令选择与参数集都在
     * [SettingKey.WIFI_ENABLED] 的 WriteSpec 里 —— 这里不再留 if。
     *
     * ## 「开」要带上设备**当前**频段（2026-09-22 真机抓包 + 用户实测的真 bug）
     *
     * `switchWiFiChip&ChipEnum=X&GuestEnable=0` 的语义是**「在频段 X 上启用 WiFi」** ——
     * 「开」与「切频段」是同一条命令（`chip1` = 2.4G、`chip2` = 5G）。所以「开」必须把
     * 设备此刻所在的频段一起发出去：不发就会命中 profile 的兜底 `chip1`，用户在 5G 下点
     * 「打开 WiFi」会被静默切到 2.4G（实测过的现象）。
     *
     * 频段取值走**已有的读路径** [getWifiSettingsMerged] 的归一化字段 `wifi_chip`
     * （已登记在 `cmdsFor(FieldGroup.WIFI_SETTINGS)` 里）——**不许**为此新造 ad-hoc 查询：
     * `GoformCommandTableGuardTest` 逐字冻结命令表，新增查询就是一次未被记录的请求形状变更。
     *
     * 读失败 / 读到的值不在 `{chip1, chip2}` 内时退回 `chip1` 并打 WARN（决策见
     * [wifiEnableParams]）—— 不静默：这条路径会把设备切到 2.4G，必须在日志里留痕。
     * 「关」分支**不做任何读取**：`switchWiFiModule` 只认 `SwitchOption`。
     *
     * 独立的频段切换入口是 [setWifiBand]。
     */
    suspend fun setWifiEnabled(enabled: Boolean): Boolean = writer.write(
        SettingKey.WIFI_ENABLED,
        wifiEnableParams(
            enabled = enabled,
            readChip = { readCurrentWifiChip() },
            warn = { AppLogger.w(tag, it) },
        )
    )

    /**
     * 切换 WiFi 频段（`chip1` = 2.4G、`chip2` = 5G）。
     *
     * ## ⚠ 这条命令等于「在该频段上启用 WiFi」
     *
     * 设备侧没有「只切频段、不动开关」的形态：它与 [setWifiEnabled] 的「开」分支是**同一条**
     * 命令（`goformId=switchWiFiChip&ChipEnum=<chip>&GuestEnable=0`），所以 WiFi 原本是关的
     * 时候发这条会把它打开。
     *
     * **会重启 WiFi 模块 —— 此刻正通过 WiFi 连着的客户端（包括发起这次请求的那台）会掉线。**
     * 要不要在动作前跟用户确认由 UI 侧决定，本方法与 `POST /api/wifi/band` 都不加确认语义。
     *
     * 取值域校验在 [SettingKey.WIFI_BAND] 的 validate 里（只收设备词汇 `chip1` / `chip2`，
     * 不收 `2.4G` / `5G` / `0` / `1`），非法取值**不下发**并以 [WriteOutcome.Rejected] 返回原因。
     *
     * @return 三态结果（同 [setWifiSleep] / [setAccessControlList] / [setWifiConfig]）：
     *   `Boolean` 会把「取值非法」「设备明确回失败」「命令没被受理」压成同一个 false，
     *   路由就只能一律回 500，用户看不到「改一下入参就能过」这件事。
     */
    suspend fun setWifiBand(chip: String): WriteOutcome =
        writer.writeChecked(SettingKey.WIFI_BAND, chip)

    /**
     * 设备当前所在频段（归一化字段 `wifi_chip`），读不到返回 null。
     *
     * 复用 [getWifiSettingsMerged]（`cmdsFor(WIFI_SETTINGS)` 里已有 `wifi_chip`），
     * 不发任何额外命令。异常不外抛：调用方（[setWifiEnabled] 的「开」分支）在读不到时
     * 有兜底路径，让一次查询失败把「打开 WiFi」整个动作打断反而更糟。
     */
    private suspend fun readCurrentWifiChip(): String? = try {
        (getWifiSettingsMerged().values[WIFI_CHIP_FIELD] as? JsonPrimitive)
            ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.w(tag, "读取当前 WiFi 频段失败：${e.message}")
        null
    }

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
     * 本类持有的是接口 [GoformTransport]（阶段 1 已接口化），端到端路径**可以**注入假对象了，
     * 但「current + 入参 → canonical params」这段判断在接口化之前只能靠真机验证。抽出来之后
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

        /**
         * 归一化后的「当前频段」字段名（`DeviceFields.WifiSettings.CHIP` 的值，取值 `chip1` / `chip2`）。
         *
         * 这里是字面量而不是引用常量：`:core:contract` 对本模块是**传递依赖**
         * （`:core:device-schema` 用 `implementation` 引它），不在 goform 的编译类路径上。
         * 与 `cmdsFor(FieldGroup.WIFI_SETTINGS)` 里的 `wifi_chip` 是同一个键（见 [getWifiSettings]）。
         */
        internal const val WIFI_CHIP_FIELD = "wifi_chip"

        /** `ChipEnum` / `wifi_chip` 的取值域：`chip1` = 2.4G、`chip2` = 5G（2026-09-22 抓包）。 */
        private val WIFI_CHIPS = setOf("chip1", "chip2")

        /** 读不到当前频段时「开」用的频段。会把设备切到 2.4G，所以必须带 WARN 日志。 */
        internal const val WIFI_CHIP_FALLBACK = "chip1"

        /**
         * [setWifiEnabled] 交给 [SettingKey.WIFI_ENABLED] 的参数。
         *
         * - **关**：只有 `value`，[readChip] 一次都不调 —— `switchWiFiModule` 只认 `SwitchOption`，
         *   为「关」去读一次当前频段是纯浪费的请求。
         * - **开**：`value` + `chip`（设备当前频段）。读不到 / 读到的值不在 `{chip1, chip2}` 内
         *   时退回 [WIFI_CHIP_FALLBACK] 并 [warn]：这条路径会把用户从 5G 切到 2.4G，
         *   静默走过去就是 2026-09-22 之前那个「在 5G 下点开 WiFi 被切到 2.4G」的 bug 再现一次。
         *
         * 抽成以 lambda 收读路径与日志出口的 companion 函数，理由同 [mergeApConfigParams]：
         * 本类持有的是接口 [GoformTransport]（阶段 1 已接口化，可以注入假对象了），
         * 而这种写法能不经过传输层就逐字断言
         * 「读到 chip2 就发 chip2 / 读失败退回 chip1 / 关分支不读」。
         *
         * @param readChip 当前频段的读取出口（生产是 [readCurrentWifiChip]，失败给 null）
         */
        internal suspend fun wifiEnableParams(
            enabled: Boolean,
            readChip: suspend () -> String?,
            warn: (String) -> Unit,
        ): Map<String, Any?> {
            if (!enabled) return mapOf("value" to false)
            val read = readChip()
            val chip = read?.takeIf { it in WIFI_CHIPS } ?: run {
                warn(
                    "没读到当前 WiFi 频段（wifi_chip=${read ?: "缺失"}），按 2.4G" +
                        "（$WIFI_CHIP_FALLBACK）开 WiFi —— 设备若在 5G 会被切到 2.4G"
                )
                WIFI_CHIP_FALLBACK
            }
            return mapOf("value" to true, "chip" to chip)
        }

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

