package com.ufi_axis_core.util

/**
 * 设备请求验证的**唯一实现**：token → 设备记录 → 时间戳 → 验签 → nonce 去重。
 *
 * 为什么要单独抽一层：HTTP（`AuthMiddleware`）和 WebSocket 握手
 * （`WebSocketManager` 的 `WsAuthenticator`）走的是同一套设备凭据，但传参方式不同
 * （请求头 vs query）。如果各写一遍校验顺序，迟早会出现"一边补了检查另一边没补"的漂移，
 * 而这种漂移在鉴权代码里等于漏洞。这里把顺序与判定固定下来，两个入口只做参数搬运与错误映射。
 *
 * 校验顺序是刻意的：
 * 1. 先按 token 哈希定位设备——没有设备就无从谈"用谁的公钥验签"；
 * 2. 时间戳窗口在验签**之前**（廉价过滤，挡掉绝大多数无效流量）；
 * 3. nonce 去重在验签**之后**（否则攻击者用垃圾签名就能把 nonce 缓存刷满，
 *    把正常客户端挤出去——用 CPU 换内存是划算的交易）。
 *
 * @param nonceCache HTTP 与 WS 必须共享同一个实例，否则"HTTP 用过的 nonce 拿去开 WS"
 *   这类跨通道重放就拦不住。
 */
class DeviceRequestVerifier(
    private val store: PairedDeviceStore,
    private val nonceCache: DeviceAuth.NonceCache = DeviceAuth.NonceCache()
) {

    sealed class Result {
        /** 通过。 */
        data class Ok(val device: PairedDeviceRecord) : Result()
        /** 没带 token。 */
        object MissingToken : Result()
        /** token 不属于任何已配对设备（含已被移除=已吊销）。 */
        object UnknownToken : Result()
        /**
         * 配对存储读不出来（[PairedDeviceStore.degraded]），**无法判断**这个 token 认不认识。
         *
         * 2026-09-08 事故的教训：这种情况以前和 [UnknownToken] 走同一条分支，
         * 于是一次文件损坏就等于"对所有客户端宣布你没配对"，客户端照约定清空本地凭据 ——
         * 数据损坏被放大成全员重新配对 + 重新输密码。现在它是独立结果，
         * 由调用方回可重试的 503，客户端保留凭据退避重试。
         */
        object StoreUnavailable : Result()

        /** 记录里没有公钥：只可能是配对文件被手工篡改，fail-secure 拒绝并要求重新配对。 */
        object DeviceKeyMissing : Result()
        /** 缺 ts / nonce / sig 任一。 */
        object MissingSignature : Result()
        /** 时间戳超出 ±[DeviceAuth.MAX_TIMESTAMP_DRIFT_MS]。 */
        object StaleTimestamp : Result()
        object BadSignature : Result()
        object Replayed : Result()
    }

    fun verify(
        token: String?,
        timestamp: String?,
        nonce: String?,
        signature: String?,
        method: String,
        uri: String
    ): Result {
        if (token.isNullOrBlank()) return Result.MissingToken
        val device = store.findByTokenHash(DeviceAuth.sha256Hex(token.toByteArray(Charsets.UTF_8)))
        if (device == null) {
            // 降级态下"查不到"不等于"没配对"：存储读不出来时我们根本没有判据。
            // 顺序也重要 —— 先判有没有匹配，再看是不是降级，
            // 这样降级期间恰好命中 .bak 恢复出的记录的设备照常放行，不会被一刀切成 503。
            return if (store.degraded) Result.StoreUnavailable else Result.UnknownToken
        }

        if (device.pubKey.isBlank()) return Result.DeviceKeyMissing
        if (timestamp.isNullOrBlank() || nonce.isNullOrBlank() || signature.isNullOrBlank()) {
            return Result.MissingSignature
        }
        if (!DeviceAuth.isTimestampFresh(timestamp)) return Result.StaleTimestamp
        val canonical = DeviceAuth.canonicalString(
            method = method,
            uri = uri,
            timestampMs = timestamp,
            nonce = nonce
        )
        if (!DeviceAuth.verifySignature(device.pubKey, canonical, signature)) return Result.BadSignature
        if (!nonceCache.accept(nonce)) return Result.Replayed
        store.touch(device.fingerprint)
        return Result.Ok(device)
    }
}
