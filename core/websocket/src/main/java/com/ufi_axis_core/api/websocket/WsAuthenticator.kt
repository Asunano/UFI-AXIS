package com.ufi_axis_core.api.websocket

/**
 * WebSocket 握手鉴权器。
 *
 * 由 `ComponentFactory` 注入一个基于 `PairedDeviceStore` + `DeviceAuth` 的实现，
 * 使 `:core:websocket` 不必依赖配对存储，也让 [WebSocketManager] 可在纯 JVM 测试中被注入。
 *
 * @see WebSocketManager 握手参数与规范签名串的约定
 */
fun interface WsAuthenticator {
    /**
     * @param token 设备独占 token（query `token`）
     * @param timestamp 毫秒时间戳（query `ts`）
     * @param nonce 一次性随机串（query `nonce`）
     * @param signature 对 `GET\n<path>\n<ts>\n<nonce>` 的 ECDSA-SHA256 签名（query `sig`）
     * @param path 请求 path（不含 query）
     * @return 通过时返回设备指纹；任一环节失败返回 null
     */
    fun authenticate(
        token: String?,
        timestamp: String?,
        nonce: String?,
        signature: String?,
        path: String
    ): String?
}
