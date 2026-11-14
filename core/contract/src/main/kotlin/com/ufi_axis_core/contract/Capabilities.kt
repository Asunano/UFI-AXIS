package com.ufi_axis_core.contract

/**
 * 设备能力集（计划书 §7 阶段 3 / §11.4）—— **冻结区，只增不改**。
 *
 * ## 本文件是冻结区的一部分
 *
 * 这些 [wire] 取值会出现在 `GET /api/device/capabilities` 的响应里，app 与 web 照它渲染
 * 「这个开关能不能点」。同一个名字将来有三份镜像（core 的枚举 / app 的常量 /
 * `web/src/api/contract.ts` 的字符串联合类型），所以纪律是：
 *
 * - **只增不改**。改一个 [wire] = 三端同时改；只改 core 的话旧客户端会把它当成
 *   「不认识的能力」直接忽略 —— 开关静默灰掉，没有任何报错。
 * - 要删先按 §11.4 加 `@Deprecated` 保留一个版本，等三端都不读了再删。
 *   **例外（§9 批 25 的裁决）**：零引用项（从未下发过、两端都没读过）可以直接删。
 * - 新增对旧客户端是安全的：旧 app 遇到不认识的 wire 名忽略即可。
 *
 * ## 语义：Capability 是「功能域」，不是 `SettingKey` 的一一映射（2026-09-24 用户拍板）
 *
 * 29 个 `SettingKey` 不可能都归到 11 个域里。口径是：
 * **只有这里列出的域做 route 门禁，其余写操作照旧不拦**
 * （反过来也不是双射：**纯读侧项连门禁都没有**，见下面「纯读侧能力」那一节）。
 * 所以「有 `WriteSpec` 却没有对应 capability」**不是缺陷**，
 * 守门测试只做单向断言（声明了某项 → 必须能找到对应写能力与 route），防的是「定了不用」。
 *
 * 判据是**用户可见动作是否可达**，不是「某个通道是否支持」（§11.6）：
 * 定成 [NETWORK_MODE] 而不是 `GOFORM_NETWORK_MODE` —— 换台设备这个动作可能走 AT 通道完成。
 *
 * ## 门禁覆盖口径：**域内所有写入口都要拦**（2026-09-24 用户裁决）
 *
 * 一个域往往有多个写入口（`/network/mode` 与 `/network/bearer`、
 * `/device/cell-lock` 与 `/device/cell-unlock`）。**每一个都必须有门禁**。
 * 放行其中一个，等于给前端留了一条绕过门禁、把请求打到设备再失败的路 ——
 * 那正是阶段 3 要消除的失败模式。
 *
 * 「同一条设备命令只拦一次」在这里不成立：拦的不是**命令**，
 * 而是**这台设备支不支持这个功能域**。所以守门测试的判据是
 * 「每个域**至少**被一处门禁引用」，不是「恰好一处」。
 *
 * ## 缺失时的对外行为
 *
 * route 层门禁抛 `CapabilityMissing`（`:core:api`），由 `HttpServer` 的 `StatusPages`
 * 统一映射成 **501 + [ErrorCode.NOT_SUPPORTED]**：不可恢复，前端应灰掉、不要重试。
 * 与另两种「不可用」严格分开（§11.6）：
 * [ErrorCode.UNAVAILABLE]（503，设备离线/会话失效/提权失败，可重试）、
 * [ErrorCode.OUT_OF_RANGE]（400，值域非法）。
 *
 * ## 第一批 10 个（3A）+ 3B 补的 [BATTERY]
 *
 * 3A 的判据：**在 ZTE F50 上能明确验证，且 core 侧已经有对应的写 route** —— 按这条定了 10 项
 * （[SMS] 到 [TRAFFIC_LIMIT]）。[BATTERY] 是 3B 补的第 11 项，它**不满足第二条**，
 * 因为它本质上不该有写 route（见下面「纯读侧能力」）。
 * §11.5 想要的 `RAW_GOFORM` 仍然等有实测依据再进 —— 冻结区宁可晚定。
 *
 * ## 为什么 3B 只落了 [BATTERY] 一项
 *
 * 计划书原本要在 3B 一次落 `BATTERY` / `ROOT_SHELL` / `AT_CHANNEL` 三项，
 * 2026-09-24 砍到一项：**只有「这个型号有没有电池」是设备事实**。
 * 一台机器出厂带不带电池，在组件图构造时就定死了，整个进程生命周期不会变。
 *
 * `ROOT_SHELL` 与 `AT_CHANNEL` 不是设备事实，是**运行时状态**：
 * 提权走的 ADB 自连通道（`localhost:5555`）会断、会被设备端关掉；
 * AT 通道有 20 次失败熔断，熔断之后在同一个进程里它就是不可用的。
 * 把会变的东西塞进「组件图构造时算一次、之后永不更新」的静态 capabilities，
 * 得到的就是又一个**假开关**：集合里写着能用、实际早就断了 ——
 * 那正是这批能力集要消除的失败模式（开关能点、点了没反应）。
 *
 * 而且这两件事**已经各有对外表达**，不缺入口：AT 通道看 `/api/at/status` 的 `connected`，
 * root shell 看 shell 那一侧的 root 上报。那两处是**实时查**的，语义本来就对。
 *
 * ## 「纯读侧能力」这一类（2026-09-24 用户裁决）
 *
 * 从 [BATTERY] 起，本枚举**不再假设每一项都有写 route**。
 * 一项能力可以只影响**读侧**：它回答「这个字段有没有意义」，而不是「这个开关能不能点」。
 *
 * 纯读侧项的三条口径：
 *
 * 1. **没有 route 门禁**，因此必须进 `CapabilityGateTest` 的 `exempt` 豁免表；
 * 2. 豁免理由**分两种**，登记时必须写明是哪一种 ——
 *    **「core 侧还没有那个写 route」**（将来会补，属临时豁免，补上就要从表里删掉）
 *    vs **「本质上不该有写 route」**（永久豁免；[BATTERY] 属于这一种：
 *    电池是硬件事实，没有任何写操作可言）；
 * 3. 也**不进** `PluginContractTest.writeKeysOf` 那张对照表，
 *    改走那里的 `READ_ONLY_CAPABILITIES` **显式白名单** ——
 *    不是「找不到写能力就跳过」。后者会把「新加了 capability 却忘了登记 `SettingKey`」
 *    一起放过去，而那条全覆盖断言存在的全部意义就是拦住它。
 *
 * 纯读侧项的消费方不是 route 门禁，而是采集侧与下发的读数本身，逐项写在各自的枚举 KDoc 上。
 *
 * @property wire 对外 JSON 里的取值：小写 snake。
 *   **映射写在枚举上、不在下发处 `name.lowercase()`** —— 口径同
 *   `DeviceRuntime.Selection.wire`：那样写等于把 Kotlin 标识符变成对外契约，
 *   以后谁重构一次枚举名，线上值域就跟着变而编译器一声不响。
 */
enum class Capability(val wire: String) {

    /**
     * 收发设备短信。
     *
     * 覆盖的写动作：`POST /api/sms/send`（下发 `SEND_SMS` + 回读信箱确认）。
     * 门禁落点：`RootSmsRoutes` 的 `/sms/send`。
     * 设备侧依据：`profile.smsSpec()`（短信不走 `SettingKey`，理由见计划书 §11.2）。
     */
    SMS("sms"),

    /**
     * 切换 SIM 卡槽（含外置卡）。
     *
     * 覆盖的写动作：`POST /api/sim/switch`。
     * 门禁落点：`SimRoutes` 的 `/sim/switch`。
     * 设备侧依据：`SettingKey.SIM_SLOT` 的 `WriteSpec`。
     */
    SIM_SLOT_SWITCH("sim_slot_switch"),

    /**
     * 锁定 / 解锁频段（LTE 与 NR 同属一个域）。
     *
     * 覆盖的写动作：`POST /api/network/band`（lock 与 unlock 两个 action 都在这一个端点上）。
     * 门禁落点：`NetworkRoutes` 的 `/network/band`。
     * 设备侧依据：`SettingKey.BAND_LOCK_LTE` / `SettingKey.BAND_LOCK_NR` 的 `WriteSpec`。
     *
     * 不拆成 LTE / NR 两项：对用户是同一个动作（在频段列表里勾选），
     * 而「这台设备有没有 NR」由读侧的频段列表回答，不该由能力集回答。
     */
    BAND_LOCK("band_lock"),

    /**
     * 锁定 / 解锁基站（小区）。
     *
     * 覆盖的写动作：`POST /api/device/cell-lock`（锁定）与 `POST /api/device/cell-unlock`（解锁全部）。
     * 门禁落点：`DeviceRoutes` 的 `/device/cell-lock` **与** `/device/cell-unlock`
     * （**域内所有写入口都要拦**，见本文件下面那条口径）。
     * 设备侧依据：`SettingKey.CELL_LOCK` / `SettingKey.CELL_UNLOCK` 的 `WriteSpec`。
     */
    CELL_LOCK("cell_lock"),

    /**
     * 切换网络制式 / 承载偏好。
     *
     * 覆盖的写动作：`POST /api/network/mode` 与 `POST /api/network/bearer`
     * （两个端点的差别只有回显字段名，下发的是同一条 `SET_BEARER_PREFERENCE`）。
     * 门禁落点：`NetworkRoutes` 的 `/network/mode` **与** `/network/bearer`（两个入口都拦）。
     * 设备侧依据：`SettingKey.NETWORK_MODE` 的 `WriteSpec`。
     */
    NETWORK_MODE("network_mode"),

    /**
     * 设备自带的 Samba 文件共享开关。
     *
     * 覆盖的写动作：`POST /api/device/samba`。
     * 门禁落点：`DeviceRoutes` 的 `/device/samba`。
     * 设备侧依据：`SettingKey.SAMBA` 的 `WriteSpec`。
     *
     * 注意与 core 自己的 SMB 文件源（`SmbFileProvider`）无关：那是 core 去连别人的共享，
     * 这一项是「把设备自己的存储共享出来」。
     */
    SAMBA("samba"),

    /**
     * 设备的 USB 调试端口（ADB）开关。
     *
     * 覆盖的写动作：`POST /api/device/debug`。
     * 门禁落点：`DeviceRoutes` 的 `/device/debug`。
     * 设备侧依据：`SettingKey.USB_PORT` 的 `WriteSpec`（该 key 的 KDoc 写明是「USB 调试端口」）。
     *
     * 叫 `USB_DEBUG` 而不是 `USB_PORT`：对外表达的是**用户动作**（开关调试），
     * 而 `USB_PORT` 是设备侧那条命令的名字。
     * 与 `api/device/usb-mode`（`Endpoints` 负面清单里的幻影端点）无关 —— core 从没实现过它。
     */
    USB_DEBUG("usb_debug"),

    /**
     * 设备固件的 FOTA 自动升级开关。
     *
     * 覆盖的写动作：`POST /api/device/fota`。
     * 门禁落点：`DeviceRoutes` 的 `/device/fota`。
     * 设备侧依据：`SettingKey.FOTA_AUTO_UPDATE` 的 `WriteSpec`。
     *
     * 指的是**设备固件**的升级开关，与 core / app / web 自己的更新链路
     * （`api/update` 与 `api/web` 那两组）没有关系。
     */
    FOTA("fota"),

    /**
     * 性能模式（均衡 / 高性能）。
     *
     * 覆盖的写动作：`POST /api/device/performance`。
     * 门禁落点：`DeviceRoutes` 的 `/device/performance`。
     * 设备侧依据：`SettingKey.PERFORMANCE_MODE` 的 `WriteSpec`。
     */
    PERFORMANCE_MODE("performance_mode"),

    /**
     * 流量限额配置（含告警百分比与自动清零）。
     *
     * 覆盖的写动作：`POST /api/device/data-limit`。
     * 门禁落点：`DeviceRoutes` 的 `/device/data-limit`。
     * 设备侧依据：`SettingKey.TRAFFIC_LIMIT` 的 `WriteSpec`（自动清零与限额同属一条设备命令）。
     *
     * **只管设备侧那条限额命令**。同一个端点里还会落盘 core 自制的「到阈值自动关网」开关
     * （`TrafficAutoOffGuard`），那是 core 的功能、不是设备能力；但门禁仍然**拦整个端点** ——
     * 自动关网的判据就是设备的限额与用量，设备根本设不了限额时单独打开它没有意义，
     * 而「只写 core 那半边」会给用户一个开着却永远不触发的开关。
     */
    TRAFFIC_LIMIT("traffic_limit"),

    /**
     * **这个型号有没有电池** —— 本枚举的第一个**纯读侧能力**（口径见本文件「纯读侧能力」一节）。
     *
     * ## 判据是设备事实，不是「这一次有没有读到」
     *
     * 问的是**出厂带不带电池**，答案在组件图构造时就定死、整个进程生命周期不变。
     * 不声明本项 = 这个型号压根没有电池，于是电量、电池温度、电压、充电状态**全部无意义**。
     *
     * ## 没有写 route，将来也不会有
     *
     * 电池是硬件，不存在任何「开关电池」的写操作 —— 所以它在 `CapabilityGateTest.exempt` 里
     * 属于**「本质上不该有写 route」**那一类（永久豁免），不是「core 侧还没补 route」。
     * 同理它不进 `PluginContractTest.writeKeysOf`，走那里的 `READ_ONLY_CAPABILITIES` 白名单。
     *
     * ## 消费方（都在读侧）
     *
     * ⚠ 2026-09-24 批 M 改了消费方式（用户推翻了批 L 的裁决 C，改成方案 D）。
     * 现在的口径是 **「读数照发，可信度另说」**，而不是「抹成 -1」：
     *
     * - `SystemCollector.getBatteryInfo()`：**不因为未声明本项而改动任何读数**
     *   （percent 就是系统报的值，F50 上是 50）。它只往返回的 map 里多填一个
     *   `supported: Boolean` 字段，如实转述本项的声明结果。
     *   为什么当初的「抹成 -1」被推翻：app 端会把 -1 渲染成红色的 `-1%`（低电量 critical 配色），
     *   比那个假的 50% 更像故障。
     * - `DataScheduler.collectBattery()` / `scanLocalAlerts()`：未声明本项时**跳过入库与告警**
     *   （不写 `batteryBuffer`、不调 `checkBattery` / `evaluateBattery`）。
     *   这两件事都与显示无关 —— 拦的是「数据库里存一条永远 50% 的假曲线」
     *   和「告警引擎按假值判级别」。`_latestBattery` 照常赋值，读端点不受影响。
     * - `GET /api/system/battery` 与 `GET /api/dashboard` 的 battery 段：下发系统读数 + `supported`。
     * - 两端（app / web）的电量卡片：照系统值渲染；`supported=false` 时在电池详情里
     *   提示「可能无电池或检测不到」（app 侧改动登记为下一批，本项的 core 侧已就位）。
     *
     * ⚠ **ZTE F50 不声明本项** —— 用户 2026-09-24 实测确认这台机器没有电池，
     * 而系统的 sticky `ACTION_BATTERY_CHANGED` 在它上面恒报 `level=50, scale=100`：
     * 那是个**假值**，不是「读不到」。正因为是假值，所有靠「读不到」判断的旧兜底全都失效，
     * 必须由本项这样的**设备事实**来回答。
     */
    BATTERY("battery"),
    ;

    companion object {

        /**
         * 按对外 [wire] 名反查。认不出返回 null —— 调用方（将来的 app / web 镜像校验、
         * 或读配置的地方）自己决定是忽略还是报错，这里不抛异常。
         */
        fun fromWire(wire: String): Capability? = entries.firstOrNull { it.wire == wire }
    }
}
