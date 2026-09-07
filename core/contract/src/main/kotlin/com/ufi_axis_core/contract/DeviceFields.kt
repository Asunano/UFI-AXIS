package com.ufi_axis_core.contract

/**
 * 设备数据字段契约 —— **对外冻结的 canonical key 集合**。
 *
 * ## 这个文件解决什么问题
 *
 * 设备侧（ZTE goform 等）的字段命名不稳定：换固件会改名、不同型号叫法不同、
 * 同一个值在不同字段里各填一半。历史上 core 把 goform 返回的 map 原样透传，
 * 于是**设备字段一改，对外 API 字段就跟着改，web 与 app 都要动**。
 *
 * 本文件登记的是「不管设备侧怎么变，对外始终长这样」的字段名。设备侧的可变性
 * 由 `:core:device-schema` 的 `DeviceProfile` 吸收（每个 canonical key 挂一条
 * 按优先级排列的 `sources` 别名链）。适配新设备 = 新增一个 profile 文件，
 * **这个文件、route、web、app 都不动**。
 *
 * ## 三条硬性约定
 *
 * 1. **既有 key 一律不改名。** 下面的命名是混的（`lte_band_lock` snake、
 *    `BearerPreference` Pascal、`dhcpLease_hour` camel+snake）——这是从透传时代
 *    继承的历史债，冻结即接受。改名等于逼所有客户端改代码，与本设计目标相反。
 * 2. **新增 key 必须 `snake_case`。** 不能让新键继续乱下去，否则 canonical 集
 *    会退化成第二个 goform。
 * 3. **字段缺失 = 省略该 key，不输出 `null`。** 客户端普遍写 `pick(...) ?? ''` /
 *    `?: default`，省略与 null 行为一致；而 `null` 会让 app 的强类型 DTO 在
 *    非 nullable 字段上抛异常。数值型"无数据"沿用 [Units] 的 `-1` 哨兵。
 *
 * ## 万一必须破坏契约
 *
 * 走版本协商，不要直接改：预留请求头 `X-Api-Fields-Version`（缺省 = `v1` =
 * 本文件当前的形状）。目前未实现，此处只声明出口存在，避免"冻结即永久锁死"。
 *
 * ## 镜像
 *
 * `web/src/api/contract.ts` 有一份手抄镜像（与 [Endpoints] 同样的约定）。
 * 两侧的 `ALL` 集合由 `scripts/verify-api-contract.mjs` 比对，漂移会报错。
 */
object DeviceFields {

    /** 字段版本，对应预留的 `X-Api-Fields-Version`。 */
    const val VERSION = "v1"

    // ───────────────────── GET /api/device/settings ─────────────────────

    /**
     * 设备开关类设置。**值域全是字符串**（goform 时代遗留），布尔用 `"1"`/`"0"`，
     * 个别字段额外接受 `"on"`/`"off"`（见 [Bool]）。
     */
    object DeviceSettings {
        /** LED 指示灯。`"1"` = 开。 */
        const val INDICATOR_LIGHT = "indicator_light_switch"

        /** 性能模式。`"1"` = 性能，`"0"` = 均衡。 */
        const val PERFORMANCE_MODE = "performance_mode"

        /** Samba 共享开关。`"1"` = 开。 */
        const val SAMBA = "samba_switch"

        /** USB 端口开关。`"1"` = 开。仅 web 消费。 */
        const val USB_PORT = "usb_port_switch"

        /** 定时重启开关。`"1"` = 开。 */
        const val RESTART_SCHEDULE = "restart_schedule_switch"

        /** 定时重启时间，`"HH:mm"`。 */
        const val RESTART_TIME = "restart_time"

        /** WiFi 休眠空闲分钟数。`"0"` = 不休眠。 */
        const val WIFI_SLEEP_IDLE_MINUTES = "sleep_sysIdleTimeToSleep"

        /**
         * 网络模式（承载偏好）。值域是设备侧的 BearerPreference 字符串，
         * 双向映射见 [NetworkMode.toBearer] / [NetworkMode.fromBearer]。
         */
        const val BEARER_PREFERENCE = "BearerPreference"

        /** 老固件的网络模式字段，[BEARER_PREFERENCE] 缺失时的回退。 */
        const val NET_SELECT = "net_select"

        /** 连接模式。`"auto"` 或 `"manual"`；部分固件填 `"1"` / `"hand"` 表示手动。 */
        const val CONNECTION_MODE = "connection_mode"

        /** 数据漫游开关。`"1"` 或 `"on"` = 开。 */
        const val ROAM = "roam_setting_option"

        /** 拨号漫游开关，[ROAM] 的同义字段（部分固件只填这个）。 */
        const val DIAL_ROAM = "dial_roam_setting_option"

        /**
         * FOTA 自动检查更新开关。`"1"` = 开启自动升级，`"0"` = 关闭。
         *
         * 与写侧 `POST /api/device/fota` 的 `auto_update` 同为**正向**语义
         * （设备侧命令是 `goformId=SetUpgAutoSetting&UpgMode=0|1`）。
         */
        const val FOTA_AUTO_UPDATE = "UpgMode"

        val ALL = listOf(
            INDICATOR_LIGHT, PERFORMANCE_MODE, SAMBA, USB_PORT,
            RESTART_SCHEDULE, RESTART_TIME, WIFI_SLEEP_IDLE_MINUTES,
            BEARER_PREFERENCE, NET_SELECT, CONNECTION_MODE, ROAM, DIAL_ROAM,
            FOTA_AUTO_UPDATE,
        )
    }

    // ─────────────────── GET /api/device/lan-settings ───────────────────

    /** LAN / DHCP 设置。值域全是字符串。 */
    object LanSettings {
        const val LAN_IP = "lan_ipaddr"
        const val LAN_NETMASK = "lan_netmask"
        const val MAC_ADDRESS = "mac_address"

        /** DHCP 服务开关。`"1"` / `"true"` / `"SERVER"` 均视为开。 */
        const val DHCP_ENABLED = "dhcpEnabled"
        const val DHCP_START = "dhcpStart"
        const val DHCP_END = "dhcpEnd"

        /** 租约时长，**秒**。 */
        const val DHCP_LEASE = "dhcpLease"

        /**
         * 租约时长的**小时**版本。部分固件只填这个，客户端读到后需 `* 3600`。
         * 命名不规范（camel+snake 混用）但已被 web 与 app 双侧固化，不改。
         */
        const val DHCP_LEASE_HOUR = "dhcpLease_hour"

        const val MTU = "mtu"

        /** TCP MSS。设备会返回，当前无客户端消费，登记以免被 allowlist 挡掉后又要加回来。 */
        const val TCP_MSS = "tcp_mss"

        val ALL = listOf(
            LAN_IP, LAN_NETMASK, MAC_ADDRESS, DHCP_ENABLED,
            DHCP_START, DHCP_END, DHCP_LEASE, DHCP_LEASE_HOUR, MTU, TCP_MSS,
        )
    }

    // ───────────────────── GET /api/wifi/settings ─────────────────────

    /**
     * WiFi 设置。key 是 goform 的扁平命名（`wifi_chip1_ssid1_*`），已被 web
     * (`composables/utils.ts` 的 `normalizeWifiSettings`) 与 app
     * (`NetworkModule.parseWifiSettings`) 双侧固化。
     */
    object WifiSettings {
        /** 当前芯片。`"chip1"` / `"chip2"`，部分固件填 `"1"`。 */
        const val CHIP = "wifi_chip"

        const val SSID = "wifi_chip1_ssid1_ssid"

        /** 明文密码（core 已做 base64 解码）。敏感字段，见 device-schema 的 `Sensitivity`。 */
        const val PASSPHRASE = "wifi_chip1_ssid1_passphrase"

        const val AUTH_MODE = "wifi_chip1_ssid1_auth_mode"
        const val ENCRYPT_TYPE = "wifi_chip1_ssid1_encryp_type"

        /** `"1"` = 隐藏 SSID（注意语义是"隐藏"而不是"广播"）。 */
        const val BROADCAST_SSID = "wifi_chip1_ssid1_broadcast_ssid"

        const val MAX_STA_NUM = "wifi_chip1_ssid1_max_sta_num"

        /** WiFi 模块总开关。`"1"` = 开。设备侧的 `wifi_enable` / `wifi_onoff_state` 都归一到这个 key。 */
        const val MODULE_SWITCH = "WiFiModuleSwitch"

        /** 设备侧同义字段，**已不再输出**（归一化到 [MODULE_SWITCH]，阶段 4.1 起响应里不含它）。 */
        @Deprecated("core 不再输出该字段，改读 MODULE_SWITCH")
        const val ENABLE = "wifi_enable"

        /** 设备侧同义字段，**已不再输出**（同 [ENABLE]）。 */
        @Deprecated("core 不再输出该字段，改读 MODULE_SWITCH")
        const val ONOFF_STATE = "wifi_onoff_state"

        val ALL = listOf(
            CHIP, SSID, PASSPHRASE, AUTH_MODE, ENCRYPT_TYPE,
            BROADCAST_SSID, MAX_STA_NUM, MODULE_SWITCH,
        )
    }

    // ────────────────────── GET /api/wifi/clients ──────────────────────

    /**
     * 已连接客户端列表。
     *
     * **设备侧形态不稳定**：容器字段的值可能是 JSON 数组，也可能是"数组的 JSON
     * 字符串"（双重编码）。归一化开启时 core 统一成真数组（见
     * `ZteGoformProfile.normalizeStationLists`），关掉排障开关时原样透出 ——
     * 所以客户端两种形态都要能解。
     */
    object WifiClients {
        const val STATION_LIST = "station_list"

        /** LAN 侧客户端，与 [STATION_LIST] 并列（app 会合并两者）。 */
        const val LAN_STATION_LIST = "lan_station_list"

        // 数组元素字段
        const val ITEM_HOSTNAME = "hostname"
        const val ITEM_IP = "ip_addr"
        const val ITEM_MAC = "mac_addr"

        val ALL = listOf(STATION_LIST, LAN_STATION_LIST)
        val ITEM_ALL = listOf(ITEM_HOSTNAME, ITEM_IP, ITEM_MAC)
    }

    // ─────────────────── GET /api/network/band-status ───────────────────

    /**
     * 频段锁定状态。
     *
     * **值格式是契约**：纯数字逗号串（`"1,3,5"`）。`"0"` / `"all"` 表示未锁定。
     * web 的 `parseBands()` 还会剥掉 `B` / `N` 前缀以兼容个别固件。
     */
    object BandStatus {
        const val LTE_BAND_LOCK = "lte_band_lock"
        const val NR_BAND_LOCK = "nr_band_lock"

        /** 未锁定的两种表示。 */
        val UNLOCKED_VALUES = listOf("0", "all")

        val ALL = listOf(LTE_BAND_LOCK, NR_BAND_LOCK)
    }

    // ──────── GET /api/network/cell-info · /api/network/neighbor-cells ────────

    /**
     * 小区信息。与 [WifiClients] 同理，数组字段的值可能是数组也可能是 JSON 字符串，
     * 两种形态都要能解（web 的 `parseCellArray()` 已固化）。
     */
    object CellInfo {
        const val NEIGHBOR_CELL_INFO = "neighbor_cell_info"
        const val LOCKED_CELL_INFO = "locked_cell_info"

        const val LTE_PCI = "Lte_pci"
        const val LTE_EARFCN = "Lte_fcn"
        const val LTE_BANDS = "Lte_bands"
        const val LTE_RSRP = "lte_rsrp"
        const val LTE_RSRQ = "lte_rsrq"
        const val LTE_SNR = "lte_snr"

        // 数组元素字段
        const val ITEM_PCI = "pci"
        const val ITEM_EARFCN = "earfcn"
        const val ITEM_RSRP = "rsrp"
        const val ITEM_RSRQ = "rsrq"
        const val ITEM_SINR = "sinr"
        const val ITEM_RAT = "rat"

        val ALL = listOf(
            NEIGHBOR_CELL_INFO, LOCKED_CELL_INFO,
            LTE_PCI, LTE_EARFCN, LTE_BANDS, LTE_RSRP, LTE_RSRQ, LTE_SNR,
        )
        val ITEM_ALL = listOf(ITEM_PCI, ITEM_EARFCN, ITEM_RSRP, ITEM_RSRQ, ITEM_SINR, ITEM_RAT)
    }

    // ─────────────────── GET /api/device/traffic-limit ───────────────────

    /**
     * 流量限额。
     *
     * 设备侧把限额存成复合串（`"470_1024"` = 470 GB，乘数 `1=MB / 1024=GB / 1048576=TB`，
     * 1024 进制），并且 `data_volume_limit_unit` 恒为 `"MB"` 不代表真实单位。
     * **这两件事现在完全被 core 吃掉了**（计划书 2.8）：响应里只有
     * [LIMIT_VALUE] / [LIMIT_UNIT_DISPLAY] / [LIMIT_BYTES]，写入侧只收
     * `limit_value` + `limit_unit`，复合串仅存在于 profile 的 `WriteSpec.encode`。
     */
    object TrafficLimit {
        const val ENABLED = "enabled"

        /** 设备侧复合串。**已不再输出**，保留一版供外部旧调用方过渡。 */
        @Deprecated("core 不再输出该字段，改用 LIMIT_VALUE + LIMIT_UNIT_DISPLAY + LIMIT_BYTES")
        const val LIMIT_SIZE = "limit_size"

        /** 设备侧单位字段（恒为 `"MB"`，无意义）。**已不再输出。** */
        @Deprecated("core 不再输出该字段，改用 LIMIT_UNIT_DISPLAY")
        const val LIMIT_UNIT = "limit_unit"

        /** 限额数值（已从设备复合串里解析出来）。 */
        const val LIMIT_VALUE = "limit_value"

        /** 限额单位显示名（`MB` / `GB` / `TB`）。 */
        const val LIMIT_UNIT_DISPLAY = "limit_unit_display"

        /** 限额换算成字节；0 = 未设置限额。 */
        const val LIMIT_BYTES = "limit_bytes"

        const val ALERT_PERCENT = "alert_percent"
        const val AUTO_CLEAR = "auto_clear"
        const val CLEAR_DATE = "clear_date"

        /** 已用字节（月度）。 */
        const val USED_BYTES = "used_bytes"

        const val MONTHLY_RX_BYTES = "monthly_rx_bytes"
        const val MONTHLY_TX_BYTES = "monthly_tx_bytes"
        const val MONTHLY_TIME = "monthly_time"

        val ALL = listOf(
            ENABLED, LIMIT_VALUE, LIMIT_UNIT_DISPLAY,
            LIMIT_BYTES, ALERT_PERCENT, AUTO_CLEAR, CLEAR_DATE, USED_BYTES,
            MONTHLY_RX_BYTES, MONTHLY_TX_BYTES, MONTHLY_TIME,
        )
    }

    // ───────────────────── GET /api/device/identity ─────────────────────

    /** 设备身份。含 PII（手机号、IMEI），敏感度标记见 device-schema。 */
    object Identity {
        const val MSISDN = "msisdn"
        const val IMEI = "imei"
        const val IMSI = "imsi"
        const val ICCID = "iccid"
        const val LANGUAGE = "Language"
        const val CR_VERSION = "cr_version"
        const val INNER_VERSION = "wa_inner_version"

        val ALL = listOf(MSISDN, IMEI, IMSI, ICCID, LANGUAGE, CR_VERSION, INNER_VERSION)
    }

    // ─────────── 信号（REST /api/signal/* 与 WS `signal` 共用） ───────────

    /**
     * 信号字段。**这批名字已经是 core 自有的归一化命名**，不是设备原名
     * （设备侧是 `nr_rsrp` / `Z5g_rsrp` / `lte_rsrp` / `Lte_snr` / `Lte_fcn` / `Lte_bands`…）。
     *
     * 注意 [LTE_SNR] / [NR_SNR] / [LTE_BAND] 长得像设备原名，其实是小写归一名。
     * **它们同时被 WS `signal` 频道和 REST 使用，一个都不能改。**
     */
    object Signal {
        const val RSRP = "rsrp"
        const val RSRQ = "rsrq"
        const val SINR = "sinr"
        const val RSSI = "rssi"
        const val RAT = "rat"
        const val OPERATOR = "operator"
        const val CELL_ID = "cell_id"
        const val NETWORK_REGISTERED = "network_registered"

        // ───────── 服务小区统一字段（core 派生，客户端不需要按制式 if）─────────
        //
        // 选择规则：**按字段是否存在判定，NR 优先、LTE 兜底**。不看 [RAT] 字符串——
        // 它是 44 项映射表的输出（含 "NSA" / "未知(xx)" 等），拿它做分支会在固件返回
        // 新值时静默走错。NSA 双连接下两侧都有值，此时取 NR（与 [RSRP] 的合并顺序一致）。
        //
        // 下面这几个是**派生字段**，不对应任何设备字段；`nr_*` / `lte_*` 原字段全部保留，
        // 需要看双连接的场景仍然读那两组。

        /** 服务小区频段号，纯数字（`"78"` / `"3"`）。 */
        const val BAND = "band"

        /** 频段显示名，由 core 拼好（`"n78"` / `"B3"`）。前端直接显示，不要自己拼前缀。 */
        const val BAND_LABEL = "band_label"

        /** 服务小区频点（NR-ARFCN 或 EARFCN）。 */
        const val ARFCN = "arfcn"

        /** 服务小区带宽 kHz。**设备经常不填**（`Nr_band_widths` / `Lte_bands_widths` 多为空），缺失即省略该 key。 */
        const val BAND_WIDTH = "band_width"

        /** 服务小区信号强度 dBm。 */
        const val SIGNAL_STRENGTH = "signal_strength"

        /** 服务小区物理小区标识。 */
        const val PCI = "pci"

        const val NR_ARFCN = "nr_arfcn"
        const val NR_BAND = "nr_band"
        const val NR_BAND_WIDTH = "nr_band_width"
        const val NR_SIGNAL_STRENGTH = "nr_signal_strength"
        const val NR_SNR = "nr_snr"
        const val NR_PCI = "nr_pci"
        const val NR_CELL_ID = "nr_cell_id"

        const val LTE_ARFCN = "lte_arfcn"
        const val LTE_BAND = "lte_band"
        const val LTE_BAND_WIDTH = "lte_band_width"
        const val LTE_SIGNAL_STRENGTH = "lte_signal_strength"
        const val LTE_SNR = "lte_snr"
        const val LTE_PCI = "lte_pci"
        const val LTE_CELL_ID = "lte_cell_id"
        const val LTE_CA_STATUS = "lte_ca_status"

        val ALL = listOf(
            RSRP, RSRQ, SINR, RSSI, RAT, OPERATOR, CELL_ID, NETWORK_REGISTERED,
            BAND, BAND_LABEL, ARFCN, BAND_WIDTH, SIGNAL_STRENGTH, PCI,
            NR_ARFCN, NR_BAND, NR_BAND_WIDTH, NR_SIGNAL_STRENGTH, NR_SNR, NR_PCI, NR_CELL_ID,
            LTE_ARFCN, LTE_BAND, LTE_BAND_WIDTH, LTE_SIGNAL_STRENGTH, LTE_SNR,
            LTE_PCI, LTE_CELL_ID, LTE_CA_STATUS,
        )
    }

    // ─────────────────── GET /api/dashboard/summary 等 ───────────────────

    /** 连接状态。这两个 key 是设备原名，已被固化在多个端点的响应里。 */
    object Connection {
        /** 拨号状态。`"ppp_connected"` / `"ppp_disconnected"` 等设备侧取值。 */
        const val PPP_STATUS = "ppp_status"

        /** 网络类型（已过 `mapNetworkType` 值映射，如 `"5G"` 而不是 `"20"`）。 */
        const val NETWORK_TYPE = "network_type"

        const val NETWORK_PROVIDER = "network_provider"

        val ALL = listOf(PPP_STATUS, NETWORK_TYPE, NETWORK_PROVIDER)
    }

    // ───────────────────────── 布尔值编码 ─────────────────────────

    /**
     * 设备侧布尔有两套编码，冻结时两套都要能读。
     * 写入侧的编码由 device-schema 的 `WriteSpec` 决定，route 只传 Kotlin `Boolean`。
     */
    object Bool {
        val TRUTHY = listOf("1", "on", "true", "SERVER")
        val FALSY = listOf("0", "off", "false")

        fun isTrue(raw: String?): Boolean =
            raw != null && TRUTHY.any { it.equals(raw, ignoreCase = true) }
    }

    /**
     * 不属于稳定契约的端点：`GET /api/device/goform`（设备原始 dump，75+ 字段）、
     * `GET /api/wifi/module-info`（无白名单平铺展示），以及裸 goform 命令通道
     * `POST /api/device/goform/query` 与 `POST /api/device/goform/set`
     * （字段名完全由调用方传入的命令决定，core 不做归一化也不脱敏）。
     * 这些端点保持原样透传，仅供诊断，客户端**不应**依赖其字段名。
     */
    val UNSTABLE_ENDPOINTS = listOf(
        "api/device/goform",
        "api/device/goform/query",
        "api/device/goform/set",
        "api/wifi/module-info",
    )
}
