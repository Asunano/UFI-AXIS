package com.ufi_axis_core.devicespi.adapter

import kotlinx.serialization.json.JsonObject

/**
 * 发送结论。设备回 `success` 只代表受理，最终状态要回读信箱 `tag` 才知道。
 *
 * ## 为什么 [REJECTED] 与 [NO_RESPONSE] 必须分成两档
 *
 * 上层（`LocalSmsDelivery.classify`）拿这个枚举决定**要不要重试**，而重试一条短信
 * 等于可能再花一笔话费。判据只有一个：**我们知不知道设备没收到这条发送请求**。
 * - 知道没收到（[REJECTED]）→ 重试安全，不会重复发；
 * - 不知道（[NO_RESPONSE]）→ 请求可能已经落到固件里、短信可能已经发出去了，
 *   重试就是第二条真短信、第二笔钱。
 *
 * 合成一档的代价是实测过的：`goformPost` 返回 null（请求没走完 / 响应读不出来）
 * 与「设备明确回了一个失败结果」都落进 REJECTED，整档判可重试 → 后者安全、
 * 前者会重复计费。所以分开的是"设备有没有表态"，不是"失败原因"。
 */
enum class SendVerdict {
    /** 信箱 tag=2：设备确认已发出 */
    SENT,
    /** 信箱 tag=3：设备侧发送失败 */
    FAILED,
    /** 设备已受理，但短时间内没给最终状态（不代表失败） */
    PENDING,
    /**
     * **设备明确拒收**：请求到了设备、设备也回了响应，只是结果是"没受理"
     * （参数不合法、固件忙、或 SEND_SMS 根本没被投出去——如未登录）。
     *
     * 设备说了"我没收下"，所以重试不会重复发 → 这是唯一**可重试**的一档，
     * 也是唯一**不计配额**的一档（两件事必须同时成立，见 `LocalSmsDelivery`）。
     */
    REJECTED,
    /**
     * **拿不到设备的表态**：请求没走完 / 响应无法解析 / 超时（`goformPost` 返回 null）。
     *
     * 无法排除"设备其实已经收下并发出了"，所以**不可重试**（重试可能是第二笔话费），
     * 而配额**要计**（宁可少发一条，也不要漏计导致真实发送超过用户设的上限）。
     */
    NO_RESPONSE,
}

data class SendOutcome(val verdict: SendVerdict, val detail: String)

/** Goform 短信元数据（总数 / 未读），用于替代 ContentResolver 计数 */
data class SmsMeta(val total: Int, val unread: Int)

/**
 * sms 域（信箱查询 / 发送 / 删除 / 已读标记 / 条数统计）的设备适配接口（2026-09-25 批 C1）。
 *
 * 由 [DeviceAdapter.sms] 交付；goform 系的实现在 `:core:device-plugins` 里委派给
 * `GoformSmsClient`，非 goform 设备（飞猫等）另写一份实现，上层调用点一个字都不用改。
 *
 * 覆盖面 = `GoformSmsClient` 的全部 public 方法（5 个）。方法签名、参数名、默认值、返回类型
 * 与 KDoc 逐字照抄那边，本批是纯接缝迁移，行为与语义不变。
 *
 * ## 为什么本域的**读**方法也直接进来了（与 wifi 读侧不同）
 *
 * [getSmsList] 返回裸 [JsonObject]，看起来像「冻结 goform 的响应形状」——
 * 那正是 wifi / signal 读侧当初不迁的理由。sms 域不一样：它**没有 profile 字段映射**
 * （短信字段固定、不走 `fields.normalize`），信箱响应的键就是**对外形状**本身
 * （`messages` 数组里的 `id` / `number` / `content` / `encode_type` / `date` / `tag`
 * 被 `SmsController` 逐个解析，`sms_nv_rev_total` / `sms_unread_num` 被 [getSmsMeta] 读）。
 * 把它留在接口外面只剩一个理由 ——「别的域的读侧还没动」，而那不是理由：
 * 留着就得让装配层继续把 `GoformSmsClient` 这个具体类交给 4 个上层消费点。
 *
 * ## 三个结论类型为什么是**顶层**声明，不再嵌在客户端里
 *
 * [SendVerdict] / [SendOutcome] / [SmsMeta] 原本嵌在 `GoformSmsClient` 里，但语义是
 * **协议无关**的：「设备对这次发信有没有表态」（重试与计费的唯一判据）与「信箱里有多少条」。
 * 它们出现在本接口的签名上，所以住在契约层；goform 客户端改成 import 它们，
 * 枚举值名、字段名与 KDoc 逐字未变。
 *
 * ## 有一个成员**没**上接口：`GoformSmsClient.maskNumber`
 *
 * 它是 `internal` 的**纯工具函数**（日志里的号码只留前 3 后 2），不是设备操作 ——
 * 上不来也不该上来。上层要脱敏的那处自己有一份同口径实现
 * （`LocalSmsDelivery.maskNumber`，那边的 KDoc 写明了为什么不共用）。
 */
interface SmsControl {

    /**
     * 读设备信箱。
     *
     * 返回的就是**设备信箱响应本身**（`messages` 数组 + 计数字段），不过归一化 ——
     * 短信字段固定，没有 profile 映射这一层（见类 KDoc）。null = 读失败 / 未登录 /
     * 该设备不声明短信能力。
     */
    suspend fun getSmsList(page: Int = 0, perPage: Int = 50): JsonObject?

    /**
     * 发送短信 + **回读设备信箱确认真实结果**。
     *
     * **为什么必须回读**：固件回 `{"result":"success"}` 只表示这条短信**进了发送队列**，
     * 与"运营商真的发出去了"是两件事。2026-09-01 实测就出现过 `result=success` 但对端
     * 收不到。设备把最终状态写在信箱行的 `tag` 上，所以发完回读几次才能拿到真实结论，
     * 而不是让 UI 显示一个假的"发送成功"。回读的次数与间隔是**实现侧**的时间预算。
     *
     * **失败分两档**（判据见 [SendVerdict]）：设备回了响应但结果是拒绝 → [SendVerdict.REJECTED]；
     * 请求没走完 / 响应读不出来 → [SendVerdict.NO_RESPONSE]（不知道设备有没有已经发出去）。
     *
     * @param message **明文**正文。设备侧的正文编码（UCS2 等）与 `sms_time` 格式在实现侧。
     */
    suspend fun sendSms(phoneNumber: String, message: String): SendOutcome

    /** 删除信箱里的一条短信。@param msgId 设备侧的消息 id。 */
    suspend fun deleteSms(msgId: String): Boolean

    /**
     * 标记设备信箱里某条短信的已读状态。
     *
     * ⚠ 这是**设备侧**的已读位；UFI-AXIS 自己的已读状态真源是本地 `sms_read_state` 表
     * （见 `SmsController`），两者不是同一件事。
     */
    suspend fun markSmsRead(msgId: String, read: Boolean = true): Boolean

    /**
     * 信箱条数统计（总数 / 未读），用于替代 ContentResolver 计数。
     *
     * 实现侧只查计数、不取数据行。null = 读失败 / 未登录 / 该设备不声明短信能力。
     */
    suspend fun getSmsMeta(): SmsMeta?
}
