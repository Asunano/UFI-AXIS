package com.ufi_axis_core.api.pairing

/**
 * 配对申请对外展示时的**掩码**（2026-10-15）。
 *
 * ## 为什么要有唯一实现处
 * 同一份信息会出现在三个地方：App 批准弹窗、外部通知（邮件 / Webhook / 系统通知）、
 * 事件中心。三处各写一遍掩码，迟早出现"弹窗里遮住了、邮件里漏出去了"——
 * 而这类泄漏不会报错，只会静默发生。所以规则只在这里定义一次。
 *
 * ## 规则（用户 2026-10-15 定稿：显示基本信息，敏感项掩码）
 * | 字段 | 规则 | 示例 |
 * |---|---|---|
 * | 来源 IP | 只留前两段 | `192.168.***.***` |
 * | 隧道来源 | 整个显示为「隧道」，不暴露出口地址 | `隧道` |
 * | 设备名 | 留前 2 个字符 + `***` | `iP***` |
 * | 指纹 | 只留前 4 位 | `ab12` |
 * | 公钥 / token / 配对密码 | **永不出现** | — |
 *
 * 时间不掩码：它不是隐私，而且机主要靠它判断"这条请求是不是刚刚那次"。
 */
object PairingNotificationMask {

    /** 指纹对外只展示这么多位。 */
    const val FINGERPRINT_PREFIX_LEN = 4

    /** 设备名对外保留的字符数。 */
    const val DEVICE_NAME_KEEP = 2

    /**
     * 来源掩码。
     *
     * @param ip 客户端地址；空/无法识别时返回「隧道」—— 认不出来的一律按最保守的展示处理
     *（不把可能是出口地址的东西露出去）。
     */
    fun source(ip: String?): String {
        val raw = ip?.trim().orEmpty()
        if (raw.isEmpty()) return "隧道"
        val parts = raw.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.***.***" else "隧道"
    }

    /** 设备名掩码：短名字（≤2 字符）整体显示为 `***`，避免"遮了等于没遮"。 */
    fun deviceName(name: String?): String {
        val raw = name?.trim().orEmpty()
        if (raw.isEmpty()) return "未命名设备"
        if (raw.length <= DEVICE_NAME_KEEP) return "***"
        return raw.take(DEVICE_NAME_KEEP) + "***"
    }

    /** 指纹前缀（用于事件中心/日志对照，不足以还原身份）。 */
    fun fingerprintPrefix(fingerprint: String?): String =
        fingerprint?.trim().orEmpty().take(FINGERPRINT_PREFIX_LEN)
}
