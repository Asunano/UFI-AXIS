package com.ufi_axis_core.deviceschema

/**
 * 一类设备后台的字段/命令映射规则集合。
 *
 * **适配新设备 = 新增一个本接口的实现**，route / web / app 都不动。
 *
 * 实现要求：
 * - `readSpecs()` 返回的 `canonical` 必须取自 `core:contract` 的 `DeviceFields` 常量，
 *   不写字面量（守门脚本会扫 route 里的字段字面量，profile 是唯一允许写设备侧字面量的地方）。
 * - 同一 `group` + `canonical` 只能有一条 spec。冲突时以先出现者为准（[SpecIndex] 会记录）。
 * - 无对应字段的设备直接不登记，归一化时该 key 会被省略——**不要**登记一个假的 source。
 */
interface DeviceProfile {

    /** 稳定标识，用于日志与诊断（如 `"zte-goform"`）。 */
    val id: String

    /** 人类可读名称（如 `"ZTE goform（F50 等）"`）。 */
    val displayName: String

    /** 全部读字段规则。实现方应返回不可变列表（建议用 `by lazy` 缓存）。 */
    fun readSpecs(): List<FieldSpec>

    /**
     * 查询某分组需要向设备请求的命令名。
     *
     * 与 [readSpecs] 分开是因为一个命令可能喂多个字段（如 `network_information`
     * 一次返回一整块），而个别字段又必须单独查（如 `station_list` 与其它命令组合会失败）。
     */
    fun cmdsFor(group: FieldGroup): List<String>

    /**
     * 必须单独发一次查询的命令（不能与同组其它命令合并）。
     * 已知案例：`station_list` 与其它 cmd 组合时设备返回空。
     */
    fun soloCmds(group: FieldGroup): List<String> = emptyList()

    /**
     * 结构解码器：把**嵌套/非扁平**的设备响应摊平成扁平 JsonObject，供后续的字段归一化使用。
     *
     * 为什么需要它：[FieldSpec] 只能表达"顶层扁平键的别名链"。个别设备把一组字段塞进
     * 数组或子对象里，且需要"带谓词的元素选择"（例如挑出 `AccessPointSwitchStatus == "1"`
     * 的那个 AP）、"输出键由输入值生成"（按 `ChipIndex` 拼 `wifi_chip1_*` / `wifi_chip2_*`）。
     * 这类变换用路径表达式描述会退化成一门查询语言，因此直接给一个函数。
     *
     * ## 硬性约束（缺一条就退化成透传）
     *
     * 1. **输出必须再过一遍 [FieldNormalizer.normalize]**（两段式：结构展平 → allowlist 归一化）。
     *    `FieldNormalizer` 已经保证了这一点，调用方不要绕过它直接用解码器输出。
     * 2. **必须是纯函数**：不做 I/O、不读全局状态。需要外部能力（如 base64 解码）时，
     *    在构造 profile 时注入，不在解码器内部去取。
     * 3. **只写在 profile 文件里**（守门脚本的白名单覆盖 `Profile.kt`，扫描范围不用改）。
     * 4. 解码器只负责"搬键"，值格式变换仍然交给 [FieldSpec.decode]。
     *
     * 默认返回 null = 该分组的响应本来就是扁平的，不需要预处理。
     */
    fun structuralDecoder(group: FieldGroup): ((kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject)? = null

    /** 写操作规则；不支持该项则返回 null（route 应回 `NOT_SUPPORTED`）。 */
    fun writeSpec(key: SettingKey): WriteSpec?
}

/**
 * 可写设置项的稳定标识。
 *
 * route 只认这些 key + Kotlin 原生类型，具体命令名与参数编码由 [WriteSpec] 决定。
 * 新增可写项时在此加一个枚举值，然后在各 profile 里登记 [WriteSpec]。
 */
enum class SettingKey {
    /** LED 指示灯开关。value: Boolean */
    LED,

    /** 性能模式。value: Boolean（true = 性能） */
    PERFORMANCE_MODE,

    /** WiFi 休眠空闲分钟数。value: Int（0 = 不休眠） */
    WIFI_SLEEP_IDLE_MINUTES,

    /**
     * WiFi 接入控制名单（拉黑 / 白名单）。
     * params: `mode` + `black_macs` + `black_names` + `white_macs` + `white_names`
     *
     * **整表替换**：设备侧这条命令不支持增删单项，一次必须把四条名单都发全，
     * 所以调用方要先读回当前名单（`queryDeviceAccessControlList`）再改，
     * 读-改-写收敛在 core（`WifiRoutes` 的 acl 分支），两端客户端只发单台设备的增删。
     */
    WIFI_ACL,


    /** 定时重启。params: `enabled: Boolean` + `time: "HH:mm"` */
    RESTART_SCHEDULE,

    /** 数据漫游。value: Boolean（注意：设备侧这条命令用 `"on"`/`"off"` 编码） */
    ROAM,

    /** Samba 共享。value: Boolean */
    SAMBA,

    /** USB 调试端口。value: Boolean */
    USB_PORT,

    /** 网络模式（承载偏好）。value: String（contract 的 NetworkMode，由 WriteSpec 转成设备值域） */
    NETWORK_MODE,

    /** LTE 频段锁定。value: String（逗号分隔的频段号，空 = 解锁） */
    BAND_LOCK_LTE,

    /** NR 频段锁定。value: String */
    BAND_LOCK_NR,

    /**
     * 流量限额。params: `enabled` + `limit_value` + `limit_unit` + `alert_percent`
     * + `auto_clear` + `clear_date`。
     *
     * 自动清零（`wan_auto_clear_flow_data_switch` / `traffic_clear_date`）与限额同属
     * 一条设备命令 —— 2026-08-30 真机抓包确认，别再拆。
     */
    TRAFFIC_LIMIT,



    /**
     * LAN / DHCP 设置。params: `lan_ip` + `lan_netmask` + `dhcp_type`（`"SERVER"` / `"DISABLE"`）
     * + `dhcp_start` + `dhcp_end`（关闭 DHCP 服务器时可为空）+ `dhcp_lease`（秒）。
     */
    LAN_DHCP,

    /**
     * FOTA 自动升级开关。value: Boolean（true = 允许自动升级）。
     *
     * 注意语义方向：这个 key 是**正向**的（true = 开）。设备侧 `UpgMode` 恰好也是
     * 正向（1 = 开），但对外的 `POST /api/device/fota` 历史上收的是**反向**的
     * `enabled=true → 禁用`。那次反转只在 route 的旧字段兼容分支里做一次
     * （计划书 2.7：语义翻转只允许存在一份）。
     */
    FOTA_AUTO_UPDATE,

    /**
     * 锁定当前/指定基站。params: `pci: String` + `earfcn: String` + `network_type: String`。
     *
     * `network_type` 用制式名（`"LTE"` / `"NR"`），**不是** goform 的数字 RAT 码。
     * 数字码（LTE=12、NR=16）是设备侧事实，只在 [WriteSpec.encode] 里出现。
     */
    CELL_LOCK,

    /** 解锁全部基站。无参数。 */
    CELL_UNLOCK,

    /**
     * SIM 卡槽切换。value: `Int`（1 起的卡槽序号）或 `"external"`（外置卡）。
     *
     * 设备侧的卡槽值是运营商预置位（0/1/2/11），映射关系只在 [WriteSpec.encode] 里。
     */
    SIM_SLOT,

    /**
     * 流量手动校准。params: `target: "data"|"time"` + `value: String`。
     *
     * 设备侧要同时收 `data` 与 `time` 两个字段（未校准的那个填 "0"），
     * 这份"补零"规则属于设备侧，不该让调用方拼。
     */
    FLOW_CALIBRATION,
}

/**
 * 一个写操作的设备侧规则。
 *
 * @param command 设备侧命令标识（goform 的 `goformId`）。
 * @param encode 把 route 传入的 canonical 参数转成设备侧参数表。


 *   **这是布尔/枚举编码的唯一归属地**——设备侧布尔有 `"1"/"0"` 和 `"on"/"off"` 两套，
 *   route 只应传 Kotlin `Boolean`。
 * @param validate 值域校验：返回错误描述则拒绝下发（route 回 `OUT_OF_RANGE` 等），
 *   返回 null 表示通过。**在这里挡住非法值，既是正确性也是安全性**——
 *   设备侧的表单拼接对特殊字符没有防护。
 */
data class WriteSpec(
    val command: String,
    val encode: (Map<String, Any?>) -> Map<String, String>,
    val validate: (Map<String, Any?>) -> String? = { null },
)
