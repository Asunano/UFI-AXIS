package com.ufi_axis_core.devicespi.adapter

import com.ufi_axis_core.deviceschema.NormalizedFields
import com.ufi_axis_core.devicespi.WriteOutcome
import kotlinx.serialization.json.JsonObject

/** 接入控制名单里的一台设备。`name` 可能为空串（设备侧名单允许只有 MAC）。 */
data class AclEntry(val mac: String, val name: String)

/**
 * 一次接入控制名单读取的快照。
 *
 * [mode] 直接透传设备的档位取值（ZTE 的 `AclMode` 里 `"2"` = 黑名单生效），本项目只用黑名单，
 * 白名单读出来只为「整表回写时不把它冲掉」。
 */
data class AclSnapshot(
    val mode: String,
    val black: List<AclEntry>,
    val white: List<AclEntry>,
)

/**
 * wifi 域（热点配置 / 开关 / 频段 / 功率 / 休眠 / 状态读取 / 已连客户端 / 二维码 /
 * 接入控制名单）的设备适配接口（2026-09-25 批 A2b 立写侧，批 C2 把读侧与
 * [setAccessControlList] 也迁进来）。
 *
 * 由 [DeviceAdapter.wifi] 交付；goform 系的实现在 `:core:device-plugins` 里委派给
 * `GoformWifiClient`，非 goform 设备（飞猫等）另写一份实现，上层调用点一个字都不用改。
 *
 * 方法签名（参数名、参数类型、默认值、返回类型）与 KDoc 照抄 `GoformWifiClient`，
 * 本批是纯接缝迁移，行为与语义不变 —— 包括「哪些回 [Boolean]、哪些回 [WriteOutcome]」
 * 这个既有的不齐整。覆盖面 = 那个类的全部 public 成员（7 个写 + 7 个读 +
 * [setAccessControlList] + [lastQrCodeFailure]）。
 *
 * ## 批 C2：读侧为什么现在能进来了
 *
 * 批 A2b 把读侧留在外面的理由是「返回裸 `JsonObject` 等于冻结 goform 的响应形状」。
 * 批 B1 之后这条已经不成立了：需要归一化保证的两个（[getWifiSettingsMerged] /
 * [getConnectedClients]）返回 [NormalizedFields] —— 那个类型在实现侧的模块里造不出来
 * （构造入口是 `internal`），所以「返回了没归一化的数据」在类型上不可表达；
 * 仍返回裸 [JsonObject] 的两个（[getWifiModuleInfo] / [getWifiSettings]）是**原样透传**，
 * 它们是前者的两个输入，其中 [getWifiModuleInfo] 本身就是已登记的诊断端点例外
 * （`DeviceFields.UNSTABLE_ENDPOINTS` 里的 `GET /api/wifi/module-info`）。
 *
 * 这里**刻意不统一**这两类（口径同 [SignalSource] 的类 KDoc）：统一就不是纯接缝迁移了。
 *
 * ## [AclEntry] / [AclSnapshot] 为什么是**顶层**声明，不再住在 goform 模块里
 *
 * 语义是**协议无关**的（MAC 地址黑白名单），而它们出现在本接口的签名上
 * （[getAccessControlList] / [setAccessControlList] 共用），所以住在契约层 ——
 * 做法同 [SmsControl] 那边的 [SendVerdict] / [SendOutcome] / [SmsMeta]。
 * goform 客户端改成 import 它们，字段名与 KDoc 逐字未变。
 *
 * ## 两处**已知的 goform 形状泄漏**（本批原样保留，不顺手改）
 *
 * - [getCurrentWifiConfig] 的 map 键是设备侧 CamelCase 原名（`AuthMode` / `SSID` …）。
 *   它没有任何上层消费点（三个写入口自己用它读回当前值），上接口只为「域接口的覆盖面 =
 *   客户端的 public 成员」这条口径完整。
 * - [setWifiBand] 的 `chip` 取值是 goform 词汇（见那个方法的 `@param`）。
 */
interface WifiControl {

    /**
     * 改整份 WiFi 热点配置。三个写入口里唯一「调用方可以逐项指定」的那个。
     *
     * 设备侧是**整表替换**：漏发一个键那一项就按设备默认值走（仓库为此出过两次事故），
     * 所以 [authMode] 或 [ssid] 有一个没给时，实现侧必须先把当前值从设备读回来再合并。
     * 这条「读回当前值」的责任留在实现侧 —— 它是设备事实，不是调用方要关心的事。
     *
     * @param passphrase **明文**口令。设备侧的编码（base64 等）在实现侧做，只编一次。
     * @return 三态结果：`Rejected` = 参数被值域校验拦下、根本没下发（密码位数 / 加密组合
     *   不合法这类**改一下入参就能过**的原因），route 回 400 + 原因而不是一律 500。
     */
    suspend fun setWifiConfig(
        ssid: String? = null,
        authMode: String? = null,
        encrypType: String? = null,
        passphrase: String? = null,
        maxStaNum: Int? = null,
        broadcastDisabled: Int? = null,
        chipIndex: String? = null
    ): WriteOutcome

    /** @param level 发射功率档位（值域 0~2 的判据在实现侧，与 route 的入参校验同一份事实）。 */
    suspend fun setWifiPower(level: Int): Boolean

    /** 只改 SSID —— 其余热点配置由实现侧读回后原样带上（整表替换，见 [setWifiConfig]）。 */
    suspend fun setWifiSSID(ssid: String): Boolean

    /**
     * WiFi 总开关。
     *
     * ⚠ 「开」在设备侧等于「在**某个频段**上启用 WiFi」，所以实现侧必须把设备**当前**频段
     * 一起发出去（不发会把在 5G 的用户静默切到 2.4G —— 实测过的 bug）。读当前频段、
     * 读失败时的退路与那条 WARN 都在实现侧，本接口不暴露频段参数：调用方点的是「开 / 关」。
     */
    suspend fun setWifiEnabled(enabled: Boolean): Boolean

    /**
     * 切换 WiFi 频段。
     *
     * ⚠ 这条动作与 [setWifiEnabled] 的「开」是**同一件事**：WiFi 原本是关的时候切频段会把它
     * 打开，并且**会重启 WiFi 模块 —— 正连着 WiFi 的客户端（包括发起这次请求的那台）会掉线**。
     * 要不要先跟用户确认由 UI 侧决定，本方法不加确认语义。
     *
     * @param chip 设备侧的频段词汇（`"chip1"` = 2.4G、`"chip2"` = 5G）。
     *   ⚠ **已知的契约泄漏**：取值是 goform 的词汇，且是对外入参（`POST` 到
     *   `/api/wifi/band` 原样透传，route 刻意只认这两个值）。取值域校验在实现侧，
     *   非法取值**不下发**并以 [WriteOutcome.Rejected] 返回原因。接第二种协议时
     *   必须先在这里立中立别名（2.4G / 5G），别把本参数当成协议无关的。
     */
    suspend fun setWifiBand(chip: String): WriteOutcome

    /** 只改口令 —— 其余热点配置由实现侧读回后原样带上（整表替换，见 [setWifiConfig]）。 */
    suspend fun setWifiPassword(password: String): Boolean

    /** @param time WiFi 空闲休眠时长（分钟，`"0"` = 不休眠）；值域校验在实现侧。 */
    suspend fun setWifiSleep(time: String): WriteOutcome

    /**
     * 整表下发接入控制名单。
     *
     * 四条名单一次全发（补分号 / 空 key 这类设备侧细节在实现侧），
     * 所以调用方传进来的必须是**替换后的完整名单**，不是增量。
     *
     * @param mode 档位；不给（null / 空白）时由实现侧回落到该设备的默认档位 ——
     *   判据只有一份，在设备 profile 那边，调用方不许自己编一个档位。
     */
    suspend fun setAccessControlList(
        black: List<AclEntry>,
        white: List<AclEntry> = emptyList(),
        mode: String? = null,
    ): WriteOutcome

    /**
     * 读回接入控制名单。
     *
     * 设备侧的名单是分号串（`"2a:ed:87:b3:e8:29;"`）+ CamelCase 键名，拆成结构化的
     * [AclEntry] 是实现侧的事，调用方只见 [AclSnapshot]。Mac 与 Name 按下标配对，
     * 缺名字的补空串。
     *
     * 写侧是**整表替换**（[setAccessControlList]），所以调用方每次都得先读这个再改。
     */
    suspend fun getAccessControlList(): AclSnapshot?

    /**
     * WiFi 模块开关 + AP 列表（`ResponseList` 里是 AP 对象）。
     *
     * **原样透传，未归一化**（批 B1：返回裸 [JsonObject]）—— 它是 [getWifiSettingsMerged]
     * 的**两个输入之一**，归一化在那里对合并后的整体做一次（别名链要一次看到全部输入才能定优先级）。
     * 这份原始形状同时也是诊断端点 `GET /api/wifi/module-info` 的出口
     * （`DeviceFields.UNSTABLE_ENDPOINTS` 里登记过的例外）。
     */
    suspend fun getWifiModuleInfo(): JsonObject?

    /**
     * WiFi 的扁平字段查询。
     *
     * **原样透传，未归一化**（批 B1：返回裸 [JsonObject]）—— 同 [getWifiModuleInfo]，
     * 它是 [getWifiSettingsMerged] 的另一个输入，归一化在那里做。
     */
    suspend fun getWifiSettings(): JsonObject?

    /**
     * `/api/wifi/settings` 的唯一数据来源：扁平字段查询 + 模块信息合并后**归一化**。
     *
     * 为什么在实现侧合并：两份响应互补，而归一化必须一次看到全部输入才能按别名链定优先级。
     *
     * @return 只含 `DeviceFields.WifiSettings` 登记字段的对象；**非空契约** ——
     *   两个查询都失败时给 [NormalizedFields.EMPTY]（内容就是 `{}`）。
     */
    suspend fun getWifiSettingsMerged(): NormalizedFields

    /**
     * 已连接客户端。
     *
     * 设备侧有多种返回形态（对象 / 裸数组 / 双重编码的字符串），归一化后一律是
     * `{"station_list":[{...}]}` 的真数组，元素键统一成 `hostname`/`ip_addr`/`mac_addr`。
     */
    suspend fun getConnectedClients(): NormalizedFields?

    /**
     * 当前 WiFi 热点配置，用于修改单项参数时保留其他参数不被重置（整表替换，见 [setWifiConfig]）。
     *
     * ⚠ **返回值契约（读明文 / 写 base64 的不对称）**：`Password` 是**明文**（设备侧是
     * base64，实现侧已经解过一次）。而回写设备时要的是 base64 —— 所以任何拿这个值回写的
     * 地方**必须自己编一次**，既不能原样发，也不能再解一次（明文不是合法 base64 时会拿到空串）。
     *
     * ⚠ **已知的 goform 形状泄漏**：键名是设备侧 CamelCase 原名（`AuthMode` / `EncrypType` /
     * `SSID` / `Password` / `ChipIndex` / `ApMaxStationNumber` / `ApBroadcastDisabled`）。
     * 本方法**没有上层消费点**（三个写入口在实现侧自己用它），见类 KDoc 的「两处泄漏」。
     */
    suspend fun getCurrentWifiConfig(): Map<String, String>

    /**
     * 获取 WiFi 连接二维码图片。
     *
     * 与其它读取不同，这个出口给的是**图片字节流**而不是 JSON。图片由设备按当前 SSID/密码
     * 实时生成，所以改完 WiFi 配置重新拉一次就是新的（route 侧因此不缓存它）。
     * 候选文件名与尝试顺序是**设备事实**，在实现侧（由设备 profile 登记）。
     *
     * @return (图片字节, Content-Type)；非 2xx、空响应、拿到非图片内容都返回 null，
     *   失败真因落在 [lastQrCodeFailure]。
     */
    suspend fun getWifiQrCode(chip: String = "chip1", ssidIndex: Int = 1): Pair<ByteArray, String>?

    /**
     * 最近一次二维码读取失败的原因（供 `/api/wifi/qrcode` 把真因带进 503 响应）。
     *
     * 之前失败只在 logcat 里，前端永远只看到一句"无法从设备读取 WiFi 二维码"，
     * 没有 adb 就没法判断是连不上、404 还是拿到了登录页。成功一次就清空。
     */
    val lastQrCodeFailure: String
}
