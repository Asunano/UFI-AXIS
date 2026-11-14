package com.ufi_axis_core.deviceschema

/**
 * 一类设备后台的字段与命令映射规则的集合。
 *
 * 适配新设备只需新增本接口的一个实现，route / web / app 均无需改动。
 *
 * 实现要求：
 * - `readSpecs()` 返回的 `canonical` 必须取自 `core:contract` 的 `DeviceFields` 常量，
 *   不得写字面量。守门脚本会扫描 route 中的字段字面量；profile 是实现中唯一允许出现
 *   设备侧字段名的位置。
 * - 同一 `group` 与 `canonical` 组合只能登记一条 spec，出现重复时以先登记的一条为准。
 * - 设备没有对应字段时不登记，归一化阶段会省略该 key。不得为缺失字段登记占位 source。
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
     * 「一次拉取全量设备状态」的字段名批次（供诊断 dump 使用，见 `GoformSignalClient.getFullStatus`）。
     *
     * ## 为什么不定义为新的 [FieldGroup]
     *
     * [FieldGroup] 是穷举枚举，`GoformFieldMapper.coverageReport()` 会遍历 `FieldGroup.entries`
     * 逐组向设备查询一次。新增一个枚举值会使 `/api/diagnose?fields=1` 的 `field_coverage`
     * 多出一个分组，`registered` 合计与分组数量随之改变，从而破坏既有的真机基线，
     * 以及覆盖率判据：`registered` 逐组不变、`queried` 全为 true。全量批次不登记 canonical，
     * 新增分组必然是 `registered=0`。
     *
     * 全量 dump 与「按需查询的分组」语义本就不同：前者不参与归一化，
     * 只用于原样读取设备后台提供的字段集合。
     *
     * ## 返回值为什么是 `List<List<String>>`（外层为批次）而不是 `List<String>`
     *
     * 分批数量属于设备事实，不是实现细节：ZTE F50 上的这份 dump 是三次独立请求
     * （30 / 29 / 37 项），一次发送 96 项会触发设备截断或返回空。
     * 扁平的 `List<String>` 无法表达批次边界，调用点只能自行切片，
     * 等于把设备知识重新散落到客户端。`station_list` 已证明合并查询会被设备丢弃
     * （见 [soloCmds]），批次边界必须可表达。
     *
     * 与 [soloCmds] 的分工：[soloCmds] 描述「某个 cmd 不能与同组其它命令一起发送」，
     * 本方法描述「一份 dump 固定分成哪几批」。两者都不是可自由合并的调参项。
     *
     * 默认返回空列表，表示该设备不提供全量 dump，调用点因此不发出任何查询。
     */
    fun fullStatusCmds(): List<List<String>> = emptyList()

    /**
     * 结构解码器：把嵌套或非扁平的设备响应展平为扁平 JsonObject，供后续字段归一化使用。
     *
     * [FieldSpec] 只能表达顶层扁平键的别名链。部分设备把一组字段放进数组或子对象，
     * 并且需要带谓词的元素选择（例如选出 `AccessPointSwitchStatus == "1"` 的 AP）
     * 以及由输入值生成输出键（按 `ChipIndex` 拼出 `wifi_chip1_*` / `wifi_chip2_*`）。
     * 这类变换用路径表达式描述会退化成一门查询语言，因此以函数形式提供。
     *
     * ## 硬性约束（任一缺失都会使解码退化为透传）
     *
     * 1. 输出必须再过一遍 [FieldNormalizer.normalize]（两段式：结构展平 → allowlist 归一化）。
     *    `FieldNormalizer` 已保证这一点，调用方不得绕过它直接使用解码器输出。
     * 2. 必须是纯函数：不做 I/O、不读全局状态。需要外部能力（如 base64 解码）时，
     *    在构造 profile 时注入，不在解码器内部获取。
     * 3. 只能在 profile 文件中实现。守门脚本的扫描白名单已覆盖 `Profile.kt`，扫描范围无需调整。
     * 4. 解码器只负责移动键名，值格式变换仍由 [FieldSpec.decode] 承担。
     *
     * 默认返回 null，表示该分组的响应本身是扁平的，无需预处理。
     */
    fun structuralDecoder(group: FieldGroup): ((kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject)? = null

    /**
     * WiFi 连接二维码图片在设备后台的文件名候选，按尝试顺序排列。
     *
     * 调用方用这些名字拼接自身的文件端点（goform 为
     * `/goform/goform_get_file_process/<名字>`）：路径前缀属于传输层，不在本契约内，
     * 本方法只描述「该图片在设备上的文件名」。
     *
     * ## 语义：空列表与「文件不存在」不同
     *
     * 返回空列表表示该设备不支持从后台读取二维码图片，即不存在该文件。
     * 调用方对空列表的处理与「所有候选都取不到」一致：不发出文件请求、返回取不到，
     * 并记录一行 WARN（见 `GoformWifiClient.getWifiQrCode`）。
     * 空列表不得解释为「文件不存在」或「本次未取到」：后者是一次运行时结果，
     * 前者是设备事实。调用方也不得为空列表补一个兜底文件名，
     * 否则设备知识会重新散落到客户端。
     *
     * ## 返回值为什么是 `List` 而不是单个 `String`
     *
     * 设备侧存在多个候选与兜底顺序，而「先试哪个、取不到退回哪个」属于设备事实：
     * 部分固件只生成 2.4G 的那一张，5G 的直接返回 404，此时退回一张可用的图片优于不显示
     * （二维码内容为 SSID 与口令，两个频段通常使用同一口令）。返回单个 `String`
     * 无法表达「存在兜底且兜底排在第二」，调用点只能自行补一份 `if`；
     * 理由同 [fullStatusCmds] 用 `List<List<String>>` 表达批次边界。
     *
     * 实现方必须自行去重：调用方按返回顺序逐个发请求，不做任何过滤。
     * 主候选与兜底命中同一文件名时（`chip1` 与第 1 个 SSID），
     * 列表中的重复项会导致对同一文件重复发起 HTTP 请求。
     *
     * @param chip 频段的设备词汇（`"chip1"` = 2.4G / `"chip2"` = 5G，与读侧 canonical 字段
     *   `wifi_chip`、写侧 [SettingKey.WIFI_BAND] 的取值域一致），不是 `"2.4G"` 这类界面词汇。
     * @param ssidIndex SSID 序号，从 1 开始（对外 `GET /api/wifi/qrcode` 的 `ssid_index`
     *   缺省为 1，ZTE 的扁平字段同样是 `wifi_chip1_ssid1_…`）。
     *
     * 默认实现返回空列表：新 profile 未声明该项表示该设备不具备此能力，而不是「未实现」，
     * 口径同 [smsSpec] 与 [lteAllBandsMask]。
     */
    fun qrCodeFileNames(chip: String, ssidIndex: Int): List<String> = emptyList()

    /** 写操作规则；不支持该项则返回 null（route 应回 `NOT_SUPPORTED`）。 */
    fun writeSpec(key: SettingKey): WriteSpec?

    /**
     * 「解锁全部 LTE 频段」时下发的频段全集掩码（逗号分隔的频段号，如 `"1,3,5,8"`）。
     *
     * ## 语义：`null` 与空串不同
     *
     * - 返回非空串：该设备的解锁动作需要显式下发这一串全频段。
     * - 返回 `null`：该设备不需要或不支持显式下发全频段掩码。调用方遇到 `null` 时
     *   不下发该 RAT 的全集（与「空串 = 不发限制」同侧），并记录一行 WARN，
     *   见 `GoformNetworkClient` 中对应的读取方法。
     *   不得把 `null` 当空串使用：「空串 = 不发限制」与「空串 = 下发全频段」互换属于行为变更，
     *   不是等价迁移。
     *
     * ## 为什么位于写侧（命令表范畴）而不是读侧归一化
     *
     * 频段全集是 [SettingKey.BAND_LOCK_LTE] 这条写命令的参数值域：
     * 字段归一化可以关闭，命令表不能关闭。因此调用方必须从非空的 profile
     * （装配层传入的 `commandProfile`）取值，不能从可空的归一化 profile 取值。
     *
     * ## 为什么是两个方法而不是 `allBandsMask(rat: SomeEnum)`
     *
     * `core/contract` 属于冻结区，新增对外类型的门槛较高，而当前需要表达的只有 LTE / NR 两档。
     * 出现第三档需求时再引入枚举，届时才有足够依据确定其值域。
     *
     * 默认实现返回 `null`：新 profile 未声明该项表示不存在这条设备事实，而不是「未实现」，
     * 口径同 [smsSpec]。
     */
    fun lteAllBandsMask(): String? = null

    /**
     * 「解锁全部 NR 频段」时要下发的频段全集掩码。
     *
     * 语义、`null` 的含义、以及「为什么是两个方法而不是带 RAT 参数的一个方法」
     * 全部同 [lteAllBandsMask]，不在这里重复一遍。
     */
    fun nrAllBandsMask(): String? = null

    /**
     * WiFi 接入控制（ACL）在设备未上报 mode 字段时应采用的档位。
     *
     * ## 语义：`null` 与空串不同
     *
     * - 返回非空串：该设备的 ACL 存在默认生效档位（ZTE F50 为 `"2"`，黑名单）。
     *   读侧取不到 mode 时用它兜底，避免「设备省略该字段」被渲染为「没有模式」。
     * - 返回 `null`：该设备没有此概念，或其 mode 一定会上报。
     *   调用方遇到 `null` 时保持空值原样上传，不得自行构造档位。
     *
     * ## 该取值必须由 profile 提供
     *
     * 客户端若直接引用具体 profile 的常量兜底，就绕过了 commandProfile 这条唯一通道：
     * 更换设备后仍按 ZTE 的 `"2"` 兜底，而 `"2"` 在其他设备上可能是白名单，
     * 也可能不存在该字段。结果是界面显示「黑名单生效」而设备实际放行全部客户端，
     * 此类错误不会被任何测试发现。
     */
    fun aclDefaultMode(): String? = null

    /**
     * 短信规则。不支持短信的设备返回 null（对应 `Capability.SMS` 缺失）。
     *
     * ## 短信为什么不走 [SettingKey] + [WriteSpec]
     *
     * 以下三条理由中的任意一条都足以否定「纳入通用写入表」：
     * 1. [WriteSpec.encode] 必须是纯函数，而短信参数包含 `sms_time`，其原有默认参数为
     *    `System.currentTimeMillis()` 与 `TimeZone.getDefault()`，放进 `encode` 后无法断言；
     * 2. 下发之后还需回读确认（轮询信箱检查 `tag`），而 `WriteSpec` 只能表达「发送一条命令」，
     *    无法表达「发送后再查询」；
     * 3. 编码为短信专有（UCS2 正文 / `encode_type` / `ID=-1` / `sms_time` 的分号串格式），
     *    纳入通用写入表意味着单个 key 携带一套只属于它的编码规则。
     *
     * 默认返回 null 而不抛异常：新 profile 未声明短信支持表示不具备该能力，而不是「未实现」，
     * 上层按 null 返回 `NOT_SUPPORTED` 即可。
     */
    fun smsSpec(): SmsSpec? = null
}

/**
 * 一类设备的短信规则。
 *
 * ## 边界：只包含设备事实，不包含流程与调参
 *
 * - 流程留在客户端：发送后的回读确认循环（`GoformSmsClient.verifySend`）属于流程而非设备事实；
 *   但循环中的判据（[sentTag] / [failedTag]）必须由本接口提供 —— 更换设备后 tag 取值会变，
 *   循环结构不变。
 * - 实测调参不进本接口：回读的次数与间隔（ZTE 上为 3 次 × 1.2s）是按机型实测得出的时间预算，
 *   属于 `DeviceTuning` 的范畴，不应在此新增 `verifyAttempts()` 一类成员。
 * - 全部成员必须是纯函数：相同入参恒定得到相同输出，不读时钟、不读全局状态、不做 I/O。
 *   这是「没有设备也能断言」的前提，也是 profile 抽象的基本约束。
 */
interface SmsSpec {

    /**
     * 下发一条短信的参数表。
     *
     * 命令名（`goformId`）、参数键名、正文编码与时间格式均按设备而异，
     * 这四项共同构成「如何发送一条短信」的全部设备知识。
     *
     * 不得读取系统时钟：时间戳与时区由调用方传入。现有 `GoformSmsClient.formatSmsTime`
     * 可被单测覆盖，正是因为 `buildSendParams(number, message, smsTime)` 把时间作为入参；
     * 一旦实现内部读取 `System.currentTimeMillis()`，该参数表就无法断言，
     * 而「缺少 `sms_time` 或格式不正确」正是「短信能收不能发」的根因。
     *
     * @param number 目标号码，已 trim（是否 trim 属于调用方意图，不是设备事实）。
     * @param message 正文明文，编码由 [encodeBody] 完成。
     * @param atMillis 发送时刻（epoch 毫秒）。
     * @param zone 渲染 `sms_time` 使用的时区。
     * @return 设备侧参数表。实现应返回有序 map（`linkedMapOf`）：部分固件对表单字段顺序敏感，
     *   顺序本身即为被记录下来的设备事实之一。
     */
    fun sendParams(number: String, message: String, atMillis: Long, zone: java.util.TimeZone): Map<String, String>

    /**
     * 信箱列表查询的 cmd 与分页参数。
     *
     * 该查询在 goform 上为 `cmd=sms_data_total` 加一组分页、排序与存储位参数，
     * 换一套后台就是另一组键；上层只应表达「第几页、每页几条」。
     *
     * 实现只返回短信专有的键。传输层通用键（goform 的 `isTest` / `multi_data` /
     * 防缓存的 `_=时间戳`）由客户端统一追加：它们对每次读取都相同，而 `_` 依赖时钟，
     * 纳入本接口会破坏纯函数约束。
     */
    fun listQuery(page: Int, perPage: Int): Map<String, String>

    /**
     * 删除若干条短信的参数表。
     *
     * 为什么在契约里：id 的**参数名与拼接方式**是设备事实（goform 是 `msg_id` + 分号分隔，
     * 且末尾也要带一个分号），不是调用方该知道的东西。
     */
    fun deleteParams(ids: List<String>): Map<String, String>

    /**
     * 标记若干条短信的已读状态。
     *
     * 参数名、id 拼法以及「已读 / 未读」在设备侧的取值均按设备而异。
     *
     * `read` 有默认值，使契约的主用法保持 `markReadParams(ids)` 这一形状，但该参数必须存在：
     * 对外 `POST /api/sms/read` 支持 `read=false`（标回未读），
     * 现有 `GoformSmsClient.markSmsRead(msgId, read)` 的两个分支都在被调用，
     * 只表达「标记已读」会静默丢失一半既有行为。
     */
    fun markReadParams(ids: List<String>, read: Boolean = true): Map<String, String>

    /**
     * 信箱行上「已发送」的 tag 取值。
     *
     * 为什么在契约里：设备回 `success` 只代表**受理**，真实结果写在信箱行的 `tag` 上，
     * 而这个编码是纯粹的设备约定（ZTE 是 `2`）。判据放进 spec，回读循环留在客户端。
     */
    fun sentTag(): String

    /**
     * 信箱行上「发送失败」的 tag 取值。
     *
     * 与 [sentTag] 分为两个成员的原因：上层据此决定是否重试与是否计费，
     * 「失败」与「尚未出结果」必须可以区分，合并为一个「终态集合」后无法区分。
     */
    fun failedTag(): String

    /**
     * 正文编码（UCS2 / GSM7 / 明文…）。
     *
     * 为什么在契约里：编码方式与 [sendParams] 里声明编码的那个键是**配套**的一对
     * （goform 上是 UCS2 正文 + `encode_type=UNICODE`）。拆开就会出现「按 UCS2 编码却
     * 声明成另一种」这类**读取毫无影响、但发不出去**的错误。
     */
    fun encodeBody(message: String): String
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
     * 一条设备命令，已由真机抓包确认，不得拆分。
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
     * 语义方向：该 key 为正向（true = 开），设备侧 `UpgMode` 同为正向（1 = 开）；
     * 而对外的 `POST /api/device/fota` 接收的是反向字段 `enabled=true → 禁用`。
     * 该反转只在 route 的旧字段兼容分支中执行一次（语义翻转只允许存在一份实现）。
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
     * 设备侧需要同时接收 `data` 与 `time` 两个字段（未校准的一项填 `"0"`），
     * 该补零规则属于设备侧，不由调用方拼接。
     */
    FLOW_CALIBRATION,
    // ───────── 动作类与设置类写命令 ─────────
    // 这些项原先硬编码在 GoformDeviceClient / GoformNetworkClient / GoformWifiClient 中，
    // 命令名与参数键逐字迁入 profile。

    /**
     * 重启设备。无参数。
     *
     * 动作类：重发一次就是再重启一次，所以 retry 必须是 [RetryPolicy.NEVER]。
     */
    REBOOT,

    /**
     * 关机。无参数。
     *
     * 比 [REBOOT] 更不可重试：关机成功后设备已不可达，此时的会话失效属于正常现象，
     * 重登重试只会在日志中留下误导性的失败记录。
     */
    SHUTDOWN,

    /**
     * 恢复出厂设置。无参数。
     *
     * 破坏性动作，且执行后设备的后台口令会回到出厂值 —— 重试的前提（同一会话同一口令）不成立。
     */
    FACTORY_RESET,

    /**
     * 后台管理口令修改。params: `old_hash` + `new_hash`，两者均为已哈希的值。
     *
     * profile 不做哈希：设备要求 `SHA256` 大写十六进制，该算法与登录握手共用
     * `GoformClient.sha256Hex`；在 profile 中复制第二份实现会产生两个真源，
     * 登录侧更换算法后此处不会报错，只会静默登录失败。附带效果是明文口令不进入
     * device-schema，减少一处泄露面。encode 只做字段名映射（`old_hash` → `oldPassword`）。
     *
     * **WriteSpec 无法表达副作用**：修改成功后调用点必须紧接着执行
     * `updateGoformPassword()` 与 `resetLogin()`，否则下一次请求仍以旧口令登录。
     */
    BACKEND_PASSWORD,

    /**
     * 连接模式（自动拨号 / 手动拨号）。value: String（设备侧原值 `auto_dial` / `manual_dial`）。
     *
     * 该 key 与 [ROAM] 共用设备命令 `SET_CONNECTION_MODE`：设备侧把漫游开关放在连接模式命令中
     * （见 ZteGoformProfile 中 ROAM 的注释）。两者参数集不同，因此必须是两个 key，不能合并。
     */
    CONNECTION_MODE,

    /**
     * WiFi 发射功率档位。value: Int。
     *
     * 值域 0~2 与 `WifiRoutes.kt` 的入参校验（`level must be 0-2`）一致；在此登记
     * 是为了让「档位上限」这项设备事实只有一份。
     */
    WIFI_POWER,

    /**
     * WiFi 总开关。params: `value: Boolean` + `chip: String?`（可选，`"chip1"` / `"chip2"`）。
     *
     * 设备侧把「开 WiFi」与「关 WiFi」实现为两条不同的命令（真机抓包，逐字）：
     * - 关：`goformId=switchWiFiModule&isTest=false&SwitchOption=0&AD=…`
     * - 开：`goformId=switchWiFiChip&isTest=false&ChipEnum=chip2&GuestEnable=0&AD=…`
     *
     * 抓包时设备处于 5G，因此「开」下发的是 `chip2`。结合已确认的 `chip1` = 2.4G / `chip2` = 5G，
     * `switchWiFiChip&ChipEnum=X&GuestEnable=0` 的语义是「在频段 X 上启用 WiFi」，
     * 既是「开」也是「切频段」，同属一条命令。「切频段」的独立动作是 [WIFI_BAND]。
     *
     * 因此「开」分支必须能接收频段：`chip` 的取值域是设备词汇
     * （`chip1` / `chip2`，与读侧 canonical 字段 `wifi_chip` 的取值域一致），
     * 不使用 `"2.4G"` / `"5G"` 这类界面词汇。
     * - `chip` 有值：原样作为 `ChipEnum` 下发，不在取值域内直接拒绝；
     * - `chip` 缺失：退回 `"chip1"`。这是调用方未提供当前频段时的兜底，会把设备切到 2.4G，
     *   属于已知的不理想分支；调用方（`GoformWifiClient`）有责任传入设备当前频段。
     * - 「关」分支不读 `chip`（传入无副作用，不影响编码结果）：`switchWiFiModule` 只认 `SwitchOption`。
     *
     * **实测边界**：`switchWiFiModule` 在本项目中只用于「关」，其 `SwitchOption=1`（开）
     * 从未发送过，抓包中亦不存在，无任何实测依据，不得出现在代码中。
     * 完整设备事实见 `ZteGoformProfile` 中该项 WriteSpec 的注释，修改该项前应先阅读该处。
     */
    WIFI_ENABLED,

    /**
     * WiFi 频段选择。value: String —— 取值只有 `"chip1"`（2.4G）/ `"chip2"`（5G），其余一律拒绝。
     *
     * **副作用：该命令同时会打开 WiFi。** 设备侧
     * `goformId=switchWiFiChip&isTest=false&ChipEnum=chip1|chip2&GuestEnable=0` 的语义是
     * 「在频段 X 上启用 WiFi」，不存在「只切频段、不改开关」的形态（真机抓包）。
     * 它与 [WIFI_ENABLED] 的「开」分支是同一条命令：两个 key 只是对外的两个用户动作
     * （开关 WiFi / 选择频段），设备侧事实只有一条。
     *
     * 取值域刻意使用设备词汇，与读侧 canonical 字段 `wifi_chip` 一致：不接受
     * `"2.4G"` / `"5G"`（界面文案），也不接受 `"0"` / `"1"`（读侧 `ChipIndex` 的原始编码）。
     * 接受别名会使「界面词汇 / 读侧编码 / 设备值」三套取值域在此互相透传。
     */
    WIFI_BAND,

    /**
     * 移动数据开关。value: Boolean。
     *
     * 设备侧的两条事实都由 [WriteSpec] 承担，调用点只传一个布尔值：
     * 1. 开与关是两条不同的命令（开 `CONNECT_NETWORK`、关 `DISCONNECT_NETWORK`），
     *    由 [WriteSpec.commandOf] 按取值选择；
     * 2. 主命令失败时还需发送旧命令 `SET_DATA_ENABLED`（`data=1/0`）兜底，
     *    由 [WriteSpec.fallback] 表达（主命令失败后改发备用命令属于设备事实）。
     *
     * 与 [PPP_DIAL] 的区别只有第 2 条，两者不能合并，理由见 [PPP_DIAL]。
     */
    MOBILE_DATA,

    /**
     * 手动拨号 / 挂断。value: Boolean（true = 拨号，false = 挂断）。
     *
     * 对应 `GoformNetworkClient.connectNetwork()` / `disconnectNetwork()` 两个独立入口
     * （route 侧为 `POST /api/network/connect` 与 `/disconnect`）。命令名与 [MOBILE_DATA]
     * 的主命令完全相同（`CONNECT_NETWORK` / `DISCONNECT_NETWORK`），参数也相同。
     *
     * **与 [MOBILE_DATA] 的区别仅在于没有兜底命令，不得合并**：现有两个方法失败即为失败，
     * 不会改发 `SET_DATA_ENABLED`。合并相当于给这两个入口增加一条它们从未发送过的命令，
     * 导致「连接」失败后设备的数据开关被额外修改一次，属于行为变更而非重构。
     */
    PPP_DIAL,

    /**
     * WiFi 热点配置（SSID / 加密方式 / 口令 / 广播 / 最大接入数）。
     *
     * params（全部可选；某项为 null 或不存在时不发对应设备键，固定值除外）：
     * - `ssid: String?` —— SSID。调用方负责 trim（profile 不判断是否去空格）
     * - `auth_mode: String?` —— 认证方式，缺省 `"WPA2PSK"`；取值域（真机抓包，大小写敏感）：
     *   `OPEN` / `WPA2PSK` / `WPA3PSK` / `WPA2PSKWPA3PSK`，其余值被 validate 拒绝
     * - `encrypt_type: String?` —— 加密方式，缺省 `"CCMP"`；`auth_mode == "OPEN"` 时强制 `"NONE"`
     * - `passphrase: String?` —— 明文口令，由 encode 做 base64(UTF-8)
     * - `max_sta_num: Int?` —— 最大接入设备数。闭区间 `1..10`，越界被 validate 拒绝
     *   （上限 10 为中兴 F50 的规格上限）
     * - `broadcast_disabled: Int?` —— 隐藏 SSID，缺省 `0`
     * - `chip_index: String?` —— 芯片序号，缺省 `"0"`
     *
     * 校验只覆盖 `auth_mode` 与 `max_sta_num`；SSID 与 `passphrase` 不做任何校验
     * （允许任意字符，增加格式校验会把当前可设置的值变为 Rejected）。
     *
     * ## 为什么只有一个 key（而不是 WIFI_SSID + WIFI_PASSPHRASE 两个）
     *
     * 设备侧 `setAccessPointInfo` 是整份 AP 配置替换：漏发一个键，该项即按设备默认值写入。
     * 已知两类故障均由此产生：修改 SSID 时写坏口令、未传口令时清空口令。
     * 拆成两个 key 会使「哪些键必须一起发送」这条设备事实分散在两处。
     *
     * ## 读-改-写留在调用方
     *
     * 「先读回当前值再合并」不是纯函数（需要发起一次查询），因此留在 `GoformWifiClient`；
     * profile 只接收调用方合并后的完整参数集。三个入口（修改整份配置 / 只改 SSID / 只改口令）
     * 的差异全部体现在「向 params 中放入哪几个键」，encode 的实现对三者通用。
     *
     * ## `Password` 的发送条件只看 `passphrase` 键是否存在
     *
     * encode 中不得再判断 `auth != OPEN && encryp != NONE`：三个入口的条件并不相同
     * （「只改口令」入口对 OPEN 也会发送 Password）。该条件属于调用方意图而非设备事实，
     * 写进 encode 后三处将无法共用同一份实现。
     *
     * ## 调用方须知：`auth_mode == "OPEN"` 时不应传入 `passphrase`
     *
     * 真机抓包中，界面选择 `OPEN` 发出的请求不包含 `Password` 字段
     * （`…&AuthMode=OPEN&ApBroadcastDisabled=0&ApMaxStationNumber=10&EncrypType=NONE&AD=…`）。
     * encode 的规则是「`passphrase` 键非 null 即发送」，因此该约束由调用方负责：
     * profile 不会代为删除（删除会使「只改口令」入口在 OPEN 下静默丢弃用户输入）。
     * 需要发出与真机一致的 OPEN 报文时，不得放入 `passphrase` 键。
     */
    WIFI_AP_CONFIG,
}

/**
 * 会话失效时能否重发这条命令。
 *
 * 不使用 `idempotent: Boolean`：幂等是数学性质，此处需要表达的是
 * 「会话失效并重新登录后能否将同一条命令再发送一次」；该枚举也为后续扩展
 * 「重试次数 / 退避时长」保留位置。
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
 * @param encode 把 route 传入的 canonical 参数转换为设备侧参数表。
 *   **布尔与枚举编码的唯一归属地**：设备侧布尔存在 `"1"/"0"` 与 `"on"/"off"` 两套编码，
 *   route 只应传 Kotlin `Boolean`。
 * @param validate 值域校验：返回错误描述则拒绝下发（route 返回 `OUT_OF_RANGE` 等），
 *   返回 null 表示通过。在此拦截非法值既是正确性要求也是安全要求：
 *   设备侧的表单拼接对特殊字符没有防护。
 *   返回文案不得包含参数值（口令 / PIN / APN 凭据都会经过这里并被写入日志），只描述违反的规则。
 * @param retry 会话失效时是否重发。默认取安全侧（[RetryPolicy.NEVER]），原因是漏标的代价不对称：
 *   默认重试时漏标会导致重复发送短信与重复计费，用户不可见且不可撤回；
 *   默认不重试时漏标只会少一次自动重试，用户重新触发即可。因此设置类命令必须显式标注
 *   [RetryPolicy.RETRY_ON_SESSION_LOSS]，否则「切换网络制式首次必定失败」的缺陷会复发
 *   （见 `GoformSettingWriter` 的注释）。
 * @param fallback 主命令返回 `Failed` 时再试一次的备用命令；默认 null 表示没有备用命令。
 *   其存在的原因是设备侧部分动作为「先试新命令、不识别再发旧命令」（旧固件不识别新 goformId），
 *   这属于设备事实，不应在客户端保留 `if`。
 *   **fallback 的 [retry] 独立判定，不继承主命令**：主命令可重试不代表备用命令可重试，
 *   两条命令的副作用可以完全不同。
 * @param commandOf 按取值选择命令名；默认 null 表示该项只有一个命令名（[command]）。
 *
 *   需要它的原因：部分设备把「同一用户动作的开与关」实现为两条不同的命令 ——
 *   ZTE 的移动数据开 = `CONNECT_NETWORK`、关 = `DISCONNECT_NETWORK`；
 *   WiFi 开 = `switchWiFiChip`、关 = `switchWiFiModule`。若没有该字段，调用点需要写
 *   `if (enabled) keyA else keyB`，这段设备知识就会漏在 profile 之外
 *   （更换设备后会静默发送错误命令），且 [SettingKey] 会为此新增一整个 `*_OFF` 系列
 *   （`MOBILE_DATA_OFF` / `WIFI_OFF`…），对外的「一个动作」在内部裂成两个 key。
 *
 *   优先级：`commandOf` 非空时覆盖 [command]。[command] 仍然必填，作为默认命令名
 *   以及日志与断言使用的标识（因此不改为可空：一个 spec 始终能说出自身命令名）。
 *
 *   硬限制：只允许「按取值选择 command」。禁止在其中做 I/O、读全局状态、执行多步编排
 *   或产生任何副作用。与 [encode] 相同，它必须是纯函数：同一份 params 输入恒得同一命令名。
 *   需要「先读回再决定」或「成功后还要执行其它操作」的场景无法由 commandOf 表达，应留在调用点。
 */
data class WriteSpec(
    val command: String,
    val encode: (Map<String, Any?>) -> Map<String, String>,
    val validate: (Map<String, Any?>) -> String? = { null },
    val retry: RetryPolicy = RetryPolicy.NEVER,
    val fallback: WriteSpec? = null,
    val commandOf: ((Map<String, Any?>) -> String)? = null,
)
