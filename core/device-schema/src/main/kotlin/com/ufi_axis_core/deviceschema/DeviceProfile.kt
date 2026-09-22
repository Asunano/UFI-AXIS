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
    // ───────── 以下为计划书阶段 0 批 1 补齐的动作类/设置类写命令 ─────────
    // 这些项此前散在 GoformDeviceClient / GoformNetworkClient / GoformWifiClient 里硬编码，
    // 命令名与参数键逐字搬进 profile（调用点改走 writer 是下一批的事）。

    /**
     * 重启设备。无参数。
     *
     * 动作类：重发一次就是再重启一次，所以 retry 必须是 [RetryPolicy.NEVER]。
     */
    REBOOT,

    /**
     * 关机。无参数。
     *
     * 比 [REBOOT] 更不可重试 —— 关机成功后设备已经不可达，这时候的"会话失效"是正常现象，
     * 重登重试只会在日志里留一串误导人的失败。
     */
    SHUTDOWN,

    /**
     * 恢复出厂设置。无参数。
     *
     * 破坏性动作，且执行后设备的后台口令会回到出厂值 —— 重试的前提（同一会话同一口令）不成立。
     */
    FACTORY_RESET,

    /**
     * 后台管理口令修改。params: `old_hash` + `new_hash`，两个都是**已经哈希过**的值。

     *
     * 为什么 profile 不做哈希：设备要的是 `SHA256` 大写十六进制，而这套算法与登录握手
     * 共用 `GoformClient.sha256Hex` —— 在 profile 里复制第二份实现就有了两个真源，
     * 哪天登录侧换算法这里不会报错、只会静默登不上。顺带一个好处是明文口令根本不进
     * device-schema，少一处泄露面。encode 只做字段名映射（`old_hash` → `oldPassword`）。
     *
     * **副作用 WriteSpec 表达不了**：改成功后调用点必须紧接着
     * `updateGoformPassword()` + `resetLogin()`，否则下一次请求还拿旧口令登录（见计划书 §11.3）。
     */
    BACKEND_PASSWORD,

    /**
     * 连接模式（自动拨号 / 手动拨号）。value: String（设备侧原值 `auto_dial` / `manual_dial`）。
     *
     * 注意它与 [ROAM] **共用同一条设备命令** `SET_CONNECTION_MODE` —— 那不是抄错，
     * 是设备侧把"漫游开关"塞进了连接模式这条命令里（见 ZteGoformProfile 中 ROAM 的注释）。
     * 两者的参数集不同，所以必须是两个 key，不能合并。
     */
    CONNECTION_MODE,

    /**
     * WiFi 发射功率档位。value: Int。
     *
     * 值域 0~2 不是这里发明的：`WifiRoutes.kt` 的入参校验（`level must be 0-2`）
     * 早就是这个判据，搬进 profile 是为了让"档位上限"这件设备事实只有一份。
     */
    WIFI_POWER,

    /**
     * WiFi 总开关。value: Boolean。
     *
     * 设备侧把「开 WiFi」和「关 WiFi」做成了**两条不同的命令**，两边的参数还都是固定常量：
     * - 开：`switchWiFiChip` + `ChipEnum=chip1` + `GuestEnable=0`
     * - 关：`switchWiFiModule` + `SwitchOption=0`
     *
     * 所以它不是通用的「切芯片 / 关模块」能力，而就是「开关 WiFi」一个动作
     * —— 唯一的调用形态是 `GoformWifiClient.setWifiEnabled(enabled)` 的两个分支。
     * 命令选择在 [WriteSpec.commandOf] 里，参数集在 [WriteSpec.encode] 里按取值给。
     *
     * **实测边界**（批 1 的提示在这里更新过一次）：真机只验过上面这两种组合。
     * `switchWiFiModule` 在本项目里**只用于「关」**，它的 `SwitchOption=1`（开）从未发过、
     * 没有实测依据；同样地 `switchWiFiChip` 也从未用来关过 WiFi。要改动这两个分支的
     * 命令/参数，先在真机上验一次，不要按「对称性」推测另一半。
     *
     * 将来真要支持「多芯片机型选芯片」这种数量型能力时，按计划书 §11.12 用 `limits` 表达，
     * 不要把 `chip` 参数塞回这个 key —— 那会让「开关 WiFi」重新变成半个通用命令。
     */
    WIFI_ENABLED,

    /**
     * 移动数据开关。value: Boolean。
     *
     * 设备侧的两条事实都由 [WriteSpec] 承担，调用点只传一个布尔：
     * 1. 开/关是**两条不同的命令**（开 `CONNECT_NETWORK`、关 `DISCONNECT_NETWORK`），
     *    由 [WriteSpec.commandOf] 按取值选；
     * 2. 主命令失败时还要发一条老命令 `SET_DATA_ENABLED`（`data=1/0`）兜底，
     *    由 [WriteSpec.fallback] 表达（计划书 §11.3：「主失败换备用命令」属于设备事实）。
     *
     * 与 [PPP_DIAL] 的区别**只有第 2 条**。两者不能合并，理由写在 [PPP_DIAL]。
     */
    MOBILE_DATA,

    /**
     * 手动拨号 / 挂断。value: Boolean（true = 拨号，false = 挂断）。
     *
     * 对应 `GoformNetworkClient.connectNetwork()` / `disconnectNetwork()` 两个独立入口
     * （route 侧是 `POST /api/network/connect` 与 `/disconnect`）。命令名与 [MOBILE_DATA]
     * 的主命令完全相同（`CONNECT_NETWORK` / `DISCONNECT_NETWORK`），参数也一样。
     *
     * **它与 [MOBILE_DATA] 的区别只在于没有兜底命令，不要合并**：现有这两个方法失败就是失败，
     * 不会再发 `SET_DATA_ENABLED`。合并等于给这两个入口偷偷加上一条它们从来没发过的命令
     * —— 用户点「连接」失败后，设备的数据开关会被额外改一次。那是行为变更，不是重构。
     */
    PPP_DIAL,

    /**
     * WiFi 热点配置（SSID / 加密方式 / 口令 / 广播 / 最大接入数）。
     *
     * params（**全部可选**，某项为 null 或不存在 → 不发对应设备键，固定值除外）：
     * - `ssid: String?` —— SSID。**调用方负责 trim**（profile 不猜「要不要去空格」）
     * - `auth_mode: String?` —— 认证方式，缺省 `"WPA2PSK"`
     * - `encrypt_type: String?` —— 加密方式，缺省 `"CCMP"`；`auth_mode == "OPEN"` 时**强制** `"NONE"`
     * - `passphrase: String?` —— **明文**口令，由 encode 做 base64(UTF-8)
     * - `max_sta_num: Int?` —— 最大接入设备数
     * - `broadcast_disabled: Int?` —— 隐藏 SSID，缺省 `0`
     * - `chip_index: String?` —— 芯片序号，缺省 `"0"`
     *
     * ## 为什么只有一个 key（而不是 WIFI_SSID + WIFI_PASSPHRASE 两个）
     *
     * 设备侧 `setAccessPointInfo` 是**整份 AP 配置替换**：漏发一个键，那一项就按设备默认值走
     * （已经出过两次事故 —— 改 SSID 把口令写坏、不传口令把口令清空，见 `fix(wifi)` 那个提交）。
     * 拆成两个 key 等于让「哪些键必须一起发」这条设备事实散在两处。
     *
     * ## 读-改-写留在调用方（计划书 §11.3）
     *
     * 「先读回当前值再合并」不是纯函数（要发一次查询），所以它留在 `GoformWifiClient`；
     * profile 只收**调用方合并后的完整参数集**。三个入口（改整份配置 / 只改 SSID / 只改口令）
     * 的差异全部体现在「往 params 里放哪几个键」，encode 这一份对三者通用。
     *
     * ## `Password` 的发送条件只看 `passphrase` 键在不在
     *
     * **不要**在 encode 里再判 `auth != OPEN && encryp != NONE`：三个入口的条件并不相同
     * （「只改口令」这个入口对 OPEN 也发 Password）。那个条件是**调用方的意图**、
     * 不是设备事实 —— 把它写进 encode，三处就再也共用不了同一份实现。
     */
    WIFI_AP_CONFIG,
}

/**
 * 会话失效时能不能重发这条命令。
 *
 * 为什么不叫 `idempotent: Boolean`：「幂等」是数学性质，这里真正要表达的是
 * 「会话失效重登后能不能把同一条命令再发一次」；而且将来要加「重试几次/退避多久」时有地方放。
 */
enum class RetryPolicy {
    /** 会话失效时重登并重试一次。只给「同一取值幂等」的设置类命令。 */
    RETRY_ON_SESSION_LOSS,

    /** 永不重试。动作类 / 计费类 / 有副作用的命令（重发一次可能发两条短信、扣两次费）。 */
    NEVER,
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
 *   返回的文案**不许包含参数值**（口令 / PIN / APN 凭据都会走这里并被写进日志），只说违反了哪条规则。
 * @param retry 会话失效时是否重发。默认取安全侧（[RetryPolicy.NEVER]）是因为**漏标的代价不对称**：
 *   默认重试时漏标 → 重复发短信 / 重复计费，用户看不见也撤不回；默认不重试时漏标 → 少一次
 *   自动重试，用户再点一次即可。所以设置类命令必须**显式**标 [RetryPolicy.RETRY_ON_SESSION_LOSS]
 *   （漏标就会复发「切换网络制式第一次必定失败」那个 bug，见 `GoformSettingWriter` 的注释）。
 * @param fallback 主命令返回 `Failed` 时再试一次的备用命令；默认 null = 没有备用命令。
 *   存在的理由：个别动作在设备侧就是"先试新命令、不认再发老命令"（老固件不认新 goformId），
 *   这属于设备事实，不该在客户端留一个 if。
 *   **fallback 的 [retry] 独立判定，不继承主命令的** —— 主命令可重试不代表备用命令也可重试
 *   （两条命令的副作用可以完全不同）。
 * @param commandOf **按取值选命令名**；默认 null = 该项只有一个命令名（[command]）。
 *
 *   为什么需要它：个别设备把「同一个用户动作的开 / 关」做成了**两条不同的命令** ——
 *   ZTE 的移动数据开 = `CONNECT_NETWORK`、关 = `DISCONNECT_NETWORK`；
 *   WiFi 开 = `switchWiFiChip`、关 = `switchWiFiModule`。不给这个字段，调用点就得写
 *   `if (enabled) keyA else keyB`：那段设备知识就漏在 profile 外面（换设备时会静默发错命令），
 *   而且 [SettingKey] 会为此长出一整个 `*_OFF` 系列（`MOBILE_DATA_OFF` / `WIFI_OFF`…），
 *   对外的「一个动作」在内部裂成两个 key。
 *
 *   优先级：`commandOf` 非空时**覆盖** [command]。[command] 仍然必填，作为默认命令名
 *   与日志 / 断言用的标识（所以它**不改成可空** —— 一个 spec 永远能说出自己叫什么）。
 *
 *   硬限制：只许「按取值选 command」。**禁止**在里面做 I/O、读全局状态、做多步编排、
 *   产生任何副作用。它和 [encode] 一样必须是**纯函数**：同一份 params 进去，永远得到
 *   同一个命令名。需要「先读回再决定」或「成功后还要做点别的」的，那不是 commandOf
 *   能表达的东西，留在调用点（计划书 §11.3）。
 */
data class WriteSpec(
    val command: String,
    val encode: (Map<String, Any?>) -> Map<String, String>,
    val validate: (Map<String, Any?>) -> String? = { null },
    val retry: RetryPolicy = RetryPolicy.NEVER,
    val fallback: WriteSpec? = null,
    val commandOf: ((Map<String, Any?>) -> String)? = null,
)
