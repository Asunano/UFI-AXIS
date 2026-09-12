package com.ufi_axis.adbcore

import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP 端口可达性探测。
 *
 * 纯 JVM、无 Android 依赖，可在单元测试里用 [java.net.ServerSocket] 验证。
 * 用于在真正握手之前先确认目标 IP:端口有人监听，避免等到 ADB 握手超时
 * 才暴露「连不上」——对应 bat 脚本里「先 ping 一下设备」的意图。
 */
object PortProbe {

    /** 单次探测结果 */
    data class Result(
        val host: String,
        val port: Int,
        /** 能否在超时内建立 TCP 连接 */
        val reachable: Boolean,
        /** 本次探测耗时（毫秒） */
        val elapsedMs: Long,
        /** 不可达时的原因（可达为 null） */
        val error: String? = null
    )

    /**
     * 探测单个 TCP 端口。
     *
     * @param timeoutMs 建立连接的超时；只控制「连不上要等多久」，不影响已建立连接
     */
    fun probe(host: String, port: Int, timeoutMs: Int = 3000): Result {
        val start = System.currentTimeMillis()
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), timeoutMs)
                Result(host, port, true, System.currentTimeMillis() - start)
            }
        } catch (e: Exception) {
            Result(host, port, false, System.currentTimeMillis() - start, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 探测 ADB 端口（默认 [AdbConnection.DEFAULT_PORT] = 5555） */
    fun probeAdb(
        host: String,
        port: Int = AdbConnection.DEFAULT_PORT,
        timeoutMs: Int = 3000
    ): Result = probe(host, port, timeoutMs)

    /** 探测健康检查端口（默认 [AdbClient.HEALTH_PORT] = 8088） */
    fun probeHealth(
        host: String,
        port: Int = AdbClient.HEALTH_PORT,
        timeoutMs: Int = 3000
    ): Result = probe(host, port, timeoutMs)
}
