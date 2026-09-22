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
     * 「一次拉全量设备状态」的字段名批次（诊断 dump 用，见 `GoformSignalClient.getFullStatus`）。
     *
     * ## 为什么**不**做成一个新的 [FieldGroup]（阶段 0.4b 的裁决）
     *
     * [FieldGroup] 是穷举枚举，`GoformFieldMapper.coverageReport()` 遍历 `FieldGroup.entries`
     * 逐组向设备查一次。加一个枚举值会让 `/api/diagnose?fields=1` 的 `field_coverage`
     * 多出一个块（`registered` 合计与组数**必然**变），直接冲掉计划书 §16 的 2026-09-22 真机基线
     * 与 §14.3 的判据 1（`registered` 逐组不变）/ 判据 2（`queried` 全 true —— 全量批次没有
     * 登记 canonical，那个新组只会是 `registered=0`）。全量 dump 与「按需查询的分组」语义本来
     * 也不同：它不参与归一化，只是把设备后台有什么原样捞一份出来看。
     *
     * ## 为什么返回值是 `List<List<String>>`（外层 = 批次）而不是 `List<String>`
     *
     * 「分几批发」是**设备事实**而不是实现细节：ZTE F50 上这份 dump 是**三次独立请求**
     * （30 / 29 / 37 项），一次发 96 项设备会截断/返回空。用扁平 `List<String>` 表达不了批次边界，
     * 调用点就得自己重新切片 —— 那等于把刚搬走的设备知识又搬回客户端。
     * 本仓已有过合并查询被设备吞掉的先例（`station_list`，见 [soloCmds]），所以批次边界必须可表达。
     *
     * 与 [soloCmds] 的分工：[soloCmds] 描述「某个 cmd 不能和同组别人一起发」，
     * 这里描述「一整份 dump 天生分成哪几批」，两者都不是可以随手合并的调参项。
     *
     * 默认空列表 = 该设备不提供全量 dump（调用点自然一条查询都不发）。
     */
    fun fullStatusCmds(): List<List<String>> = emptyList()

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

    /**
     * 短信规则。不支持短信的设备返回 null（对应 `Capability.SMS` 缺失）。
     *
     * ## 为什么短信不走 [SettingKey] + [WriteSpec]（计划书 §11.2，已决）
     *
     * 三条理由，任一都足以否掉「塞进通用写入表」：
     * 1. [WriteSpec.encode] 必须是纯函数，而短信参数里有 `sms_time` —— 原实现的默认参数是
     *    `System.currentTimeMillis()` + `TimeZone.getDefault()`，藏进 `encode` 就没法断言；
     * 2. 下发之后还有**回读确认**（轮询信箱看 `tag`）。`WriteSpec` 只能表达「发一条命令」，
     *    表达不了「发完再查」；
     * 3. 编码是短信专有的（UCS2 正文 / `encode_type` / `ID=-1` / `sms_time` 的分号串格式），
     *    放进通用写入表等于让一个 key 带一套只有它用的编码规则。
     *
     * 默认返回 null 而不是抛异常：新 profile 不写短信支持时应当是「没有这个能力」，
     * 不是「忘了实现」——上层按 null 回 `NOT_SUPPORTED` 即可。
     */
    fun smsSpec(): SmsSpec? = null
}

/**
 * 一类设备的短信规则。
 *
 * ## 边界：这里只放「设备事实」，不放流程与调参
 *
 * - **流程留在客户端**：发完回读确认的那个循环（`GoformSmsClient.verifySend`）是流程，
 *   不是设备事实；但循环里的**判据**（[sentTag] / [failedTag]）必须来自本接口 ——
 *   换一台设备，tag 的取值就会变，而循环结构不变。
 * - **实测调参不进本接口**：回读的次数与间隔（ZTE 上是 3 次 × 1.2s）属于按机型实测出来的
 *   时间预算，归计划书 §3.2 的 `DeviceTuning`（阶段 4），不要往这里加 `verifyAttempts()`。
 * - **全部成员必须是纯函数**：同样的入参永远得到同样的输出。不读时钟、不读全局状态、
 *   不做 I/O。这是「没有设备也能断言」的前提，也是整个 profile 抽象的底线约束。
 */
interface SmsSpec {

    /**
     * 下发一条短信的参数表。
     *
     * 为什么在契约里：命令名（`goformId`）、参数键名、正文编码、时间格式**全部**按设备而异，
     * 这四件事凑在一起就是「怎么发一条短信」这条设备知识的全部内容。
     *
     * **不许读系统时钟**：时间戳与时区由调用方传入。现有 `GoformSmsClient.formatSmsTime`
     * 之所以能被单测覆盖，正是因为 `buildSendParams(number, message, smsTime)` 把时间作为
     * **入参**；一旦实现里去取 `System.currentTimeMillis()`，这张参数表就再也没法断言，
     * 而「缺 `sms_time` / 格式不对」恰恰是「短信能收不能发」的历史根因。
     *
     * @param number 目标号码，**已 trim**（是否 trim 属于调用方意图，不是设备事实）。
     * @param message 正文明文。编码由 [encodeBody] 做。
     * @param atMillis 发送时刻（epoch 毫秒）。
     * @param zone 渲染 `sms_time` 用的时区。
     * @return 设备侧参数表。实现应返回**有序** map（`linkedMapOf`）：个别固件对表单字段顺序敏感，
     *   顺序本身就是抄下来的事实之一。
     */
    fun sendParams(number: String, message: String, atMillis: Long, zone: java.util.TimeZone): Map<String, String>

    /**
     * 信箱列表查询的 cmd 与分页参数。
     *
     * 为什么在契约里：查信箱这件事在 goform 上是 `cmd=sms_data_total` 加一串分页/排序/存储位
     * 参数，换一套后台就完全是另一组键 —— 上层只应该说「要第几页、每页几条」。
     *
     * 实现只返回**短信专有**的键。传输层通用的那几个（goform 的 `isTest` / `multi_data` /
     * 防缓存的 `_=时间戳`）由客户端统一追加：它们对每一次读取都一样，而 `_` 还依赖时钟，
     * 放进来就破坏纯函数。
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
     * 为什么在契约里：同上 —— 参数名、id 拼法、以及「已读/未读」在设备侧的取值都按设备而异。
     *
     * `read` 有默认值是为了让契约的主用法保持 `markReadParams(ids)` 这一种形状；
     * 但它**必须存在** —— 对外 `POST /api/sms/read` 支持 `read=false`（标回未读），
     * 现有 `GoformSmsClient.markSmsRead(msgId, read)` 的两个分支都在被调用，
     * 只表达「标已读」会静默丢掉一半既有行为（计划书 §11.2 列的签名里漏了这个参数，
     * 见报告里的 P1 说明）。
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
     * 为什么和 [sentTag] 分成两个成员：上层拿它决定「要不要重试 / 要不要计费」，
     * 「失败」与「还没出结果」必须能分开 —— 合并成一个「终态集合」就分不出来了。
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
     * WiFi 总开关。params: `value: Boolean` + `chip: String?`（**可选**，`"chip1"` / `"chip2"`）。
     *
     * 设备侧把「开 WiFi」和「关 WiFi」做成了**两条不同的命令**（2026-09-22 真机抓包，逐字）：
     * - 关：`goformId=switchWiFiModule&isTest=false&SwitchOption=0&AD=…`
     * - 开：`goformId=switchWiFiChip&isTest=false&ChipEnum=chip2&GuestEnable=0&AD=…`
     *
     * 抓包时用户设备在 5G，所以「开」发的是 `chip2`。结合已确认的 `chip1` = 2.4G / `chip2` = 5G，
     * `switchWiFiChip&ChipEnum=X&GuestEnable=0` 的语义是**「在频段 X 上启用 WiFi」** ——
     * 既是「开」也是「切频段」，同一条命令。「切频段」这个独立动作是 [WIFI_BAND]。
     *
     * 所以「开」分支必须能收一个频段：`chip` 的取值域是**设备自己的词汇**
     * （`chip1` / `chip2`，与读侧 canonical 字段 `wifi_chip` 的取值域完全一致），
     * 不用 `"2.4G"` / `"5G"` 这种界面词汇。
     * - `chip` 有值 → 原样作 `ChipEnum` 下发，不在取值域内直接拒；
     * - `chip` 缺失 → 退回 `"chip1"`。这是**调用方没告诉我当前频段时的兜底，会把设备切到 2.4G**，
     *   属于已知的不理想分支：调用方（`GoformWifiClient`）有责任把设备当前频段传进来。
     * - 「关」分支**不读** `chip`（传了也无害、不影响编码结果）：`switchWiFiModule` 只认 `SwitchOption`。
     *
     * **实测边界**：`switchWiFiModule` 在本项目里只用于「关」，它的 `SwitchOption=1`（开）
     * 从未发过、抓包里也没有 —— **至今无任何实测依据，不许出现在代码里**。
     * 完整的事实与「原拆分方案第 2 步被证伪」的归档记在 `ZteGoformProfile` 里这一项的
     * WriteSpec 注释上 —— 改这一项前先读那段。
     */
    WIFI_ENABLED,

    /**
     * WiFi 频段选择。value: String —— 取值只有 `"chip1"`（2.4G）/ `"chip2"`（5G），其余一律拒。
     *
     * **副作用：这条命令同时会把 WiFi 打开。** 设备侧
     * `goformId=switchWiFiChip&isTest=false&ChipEnum=chip1|chip2&GuestEnable=0` 的语义是
     * 「在频段 X 上启用 WiFi」，没有「只切频段、不动开关」这种形态（2026-09-22 真机抓包）。
     * 它与 [WIFI_ENABLED] 的「开」分支是**同一条命令**：两个 key 只是对外的两个用户动作
     * （开关 WiFi / 选频段），设备侧的事实只有一条。
     *
     * 取值域刻意用设备自己的词汇，与读侧 canonical 字段 `wifi_chip` 一致：**不接受**
     * `"2.4G"` / `"5G"`（界面文案），也**不接受** `"0"` / `"1"`（读侧 `ChipIndex` 的原始编码）。
     * 收了别名就等于让「界面词汇 / 读侧编码 / 设备值」三套取值域在这里悄悄互相透传。
     */
    WIFI_BAND,

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
     * - `auth_mode: String?` —— 认证方式，缺省 `"WPA2PSK"`；**取值域**（2026-09-22 真机抓包，
     *   大小写敏感）：`OPEN` / `WPA2PSK` / `WPA3PSK` / `WPA2PSKWPA3PSK`，其余值被 validate 拒
     * - `encrypt_type: String?` —— 加密方式，缺省 `"CCMP"`；`auth_mode == "OPEN"` 时**强制** `"NONE"`
     * - `passphrase: String?` —— **明文**口令，由 encode 做 base64(UTF-8)
     * - `max_sta_num: Int?` —— 最大接入设备数。**闭区间 `1..10`**，越界被 validate 拒
     *   （上限 10 = 用户对中兴 F50 的实测/规格结论，2026-09-22）
     * - `broadcast_disabled: Int?` —— 隐藏 SSID，缺省 `0`
     * - `chip_index: String?` —— 芯片序号，缺省 `"0"`
     *
     * 校验只覆盖 `auth_mode` 与 `max_sta_num`；**SSID 与 `passphrase` 不做任何校验**
     * （允许任意字符，加格式校验会把现在能设的值变成 Rejected）。

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
     *
     * ## 调用方须知：`auth_mode == "OPEN"` 时**不应该**传 `passphrase`
     *
     * 2026-09-22 真机抓包里，界面选 `OPEN` 发出的请求**没有 `Password` 字段**
     * （`…&AuthMode=OPEN&ApBroadcastDisabled=0&ApMaxStationNumber=10&EncrypType=NONE&AD=…`）。
     * 但 encode 的规则是上面那条「`passphrase` 键非 null 就发」，**所以这件事由调用方负责** ——
     * profile 不会替你把它删掉（删了就会让「只改口令」那个入口在 OPEN 下静默丢掉用户的输入）。
     * 想发出与真机逐字一致的 OPEN 报文，就**不要放 `passphrase` 这个键**。

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
