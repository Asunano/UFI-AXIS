package com.ufi_axis_core.collector.at

/**
 * `AT+CGEQOSRDP` 的响应解析（2026-09-22）。
 *
 * ## 这条命令是什么
 * 3GPP TS 27.007 定义的「EPS QoS 读取」：返回当前每条 EPS 承载协商到的 QoS 参数。
 * 一条承载一行，形如
 *
 * ```
 * +CGEQOSRDP: 1,8,0,0,0,0,500000,100000
 * +CGEQOSRDP: 11,5,0,0,0,0,30000,30000
 * ```
 *
 * 字段顺序（全部以 **kbps** 为单位）：
 * `<cid>,<QCI>,<DL_GBR>,<UL_GBR>,<DL_MBR>,<UL_MBR>,<DL_AMBR>,<UL_AMBR>`
 *
 * 我们要的三项在其中：QCI（第 2 项）、下行 AMBR（第 7 项）、上行 AMBR（第 8 项）。
 * 上面那条例子即 QCI 8、下行 500000 kbps = 500 Mbps、上行 100000 kbps = 100 Mbps。
 *
 * ## 为什么单独一个文件、不放进 SignalCollector
 * `SignalCollector` 的注释明确写了"不再使用 AT 指令查询信号，全部由 goform 覆盖"，
 * 而且它那三层优先级的输入是 goform 的 `JsonObject`，与 AT 的纯文本结构不兼容。
 * 这里是全仓**第一条**"AT 查询 → 解析 → 字段"的链路，独立成件，纯 JVM 无 Android 依赖，
 * 解析逻辑可以直接单测（AT 通道本身不可测，但真正容易写错的是解析）。
 */
object CgeqosrdpParser {

    /**
     * 一条 EPS 承载的 QoS。
     *
     * @param cid 承载 id（`<cid>`）。带出来是为了排查："为什么显示的是 QCI 5 而不是 8"
     *   这类问题只有知道取的是哪条承载才说得清。
     * @param qci QoS Class Identifier。
     * @param downlinkKbps 下行 AMBR，kbps。
     * @param uplinkKbps 上行 AMBR，kbps。
     */
    data class Bearer(
        val cid: Int,
        val qci: Int,
        val downlinkKbps: Long,
        val uplinkKbps: Long
    )

    /** 至少要有 cid + QCI + 6 个速率位才算一条完整记录。 */
    private const val MIN_FIELDS = 8

    /**
     * 默认承载的 cid。
     *
     * 优先取它而不是"第一条"：多条承载的出现顺序不保证，而用户关心的是**主数据承载**
     * 的速率上限。取不到 cid=1 时才退回第一条（有些设备的默认承载不是 1）。
     */
    private const val DEFAULT_CID = 1

    /**
     * 解析整段响应，返回所有能解出的承载（按出现顺序）。
     *
     * 宽容处理：
     * - 逐行扫，非 `+CGEQOSRDP:` 的行（回显、`OK`、空行）直接跳过；
     * - 前缀允许被垃圾字符污染 —— 实测抓到过 `Q+CGEQOSRDP:` 这种被回显吃掉一个字符的形态，
     *   所以用 `contains` 定位冒号后的负载而不是 `startsWith`；
     * - 字段数不足或关键位不是数字的行丢掉，**不让一行坏数据毁掉整次解析**。
     */
    fun parse(raw: String?): List<Bearer> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { parseLine(it) }.toList()
    }

    /**
     * 挑出要展示的那一条：优先 cid=1，否则第一条。全都解不出时返回 null。
     */
    fun pickPrimary(raw: String?): Bearer? {
        val all = parse(raw)
        return all.firstOrNull { it.cid == DEFAULT_CID } ?: all.firstOrNull()
    }

    private fun parseLine(line: String): Bearer? {
        val marker = line.indexOf(MARKER, ignoreCase = true)
        if (marker < 0) return null
        val payload = line.substring(marker + MARKER.length).trim()
        val parts = payload.split(',').map { it.trim() }
        if (parts.size < MIN_FIELDS) return null
        val cid = parts[0].toIntOrNull() ?: return null
        val qci = parts[1].toIntOrNull() ?: return null
        // 速率位允许空串（3GPP 允许省略），按 0 处理 —— 0 的语义是"无上限/未协商"
        val downlink = parts[6].toLongOrNull() ?: 0L
        val uplink = parts[7].toLongOrNull() ?: 0L
        return Bearer(cid = cid, qci = qci, downlinkKbps = downlink, uplinkKbps = uplink)
    }

    private const val MARKER = "+CGEQOSRDP:"

    /**
     * kbps → 人类可读速率。
     *
     * 1000 以下直接给 kbps；整数 Mbps 不带小数点（`500 Mbps` 而不是 `500.0 Mbps`）；
     * 非整数保留一位（`1.5 Mbps`）。0 表示"未协商 / 无上限"，回 null 让调用方显示占位。
     */
    fun formatRate(kbps: Long): String? {
        if (kbps <= 0L) return null
        if (kbps < 1000L) return "$kbps Kbps"
        val mbps = kbps / 1000.0
        return if (mbps % 1.0 == 0.0) "${mbps.toInt()} Mbps" else "${"%.1f".format(mbps)} Mbps"
    }
}
