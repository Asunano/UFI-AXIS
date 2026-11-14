package com.ufi_axis_core.controller.carrier.cloud

/**
 * 物联卡站点提供商（2026-11-09）。
 *
 * 云上卡（ysv2.iot098.cn）与小鲸卡（xj.mywuliankj.cn）是同一套前端构建、接口路径完全一致，
 * 差异只在站点配置与登录流程：
 * - 云上卡免密，只传充值号；refresh 可用；
 * - 小鲸卡需要 `verify`（本卡是绑定手机号后四位）；refresh 实测持续 code=23，不作主流程。
 *
 * 把差异收在这一张表里，客户端与账单服务按 provider 选 baseUrl / 是否 refresh，
 * 不再复制第二份 CloudCardClient。
 */
enum class IotProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    /** 登录是否必须带 verify。 */
    val requiresVerify: Boolean,
    /** refresh 接口是否可用；false 时服务端直接跳过，不把 code=23 变成告警。 */
    val supportsRefresh: Boolean,
) {
    CLOUD("cloud", "云上卡", CloudCardClient.BASE_URL, requiresVerify = false, supportsRefresh = true),
    XJ("xj", "小鲸卡", "http://xj.mywuliankj.cn", requiresVerify = true, supportsRefresh = false);

    companion object {
        /**
         * 解析存储里的 provider 字符串。
         *
         * - null / 空串 = 2026-11-08 之前的老物联卡账号，按云上卡处理（向后兼容）；
         * - 未知非空值 = 数据/客户端写错，直接报错，不静默落到云上卡。
         */
        fun of(raw: String?): IotProvider {
            val id = raw?.trim()?.lowercase()
            if (id.isNullOrBlank()) return CLOUD
            return entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("未知物联卡提供商「$raw」")
        }
    }
}
