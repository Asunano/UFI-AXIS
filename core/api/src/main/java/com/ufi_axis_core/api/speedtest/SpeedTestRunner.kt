package com.ufi_axis_core.api.speedtest

import com.ufi_axis_core.contract.Units
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * core 侧测速执行器（定时测速，2026-10-06）。
 *
 * ## 为什么不用客户端测速的结果
 * 现有测速是**客户端协议**：浏览器/app 直连（或经 relay）外网节点，测的是
 * 「客户端 ↔ 外网」的吞吐 —— 链路里包含客户端自己的 WiFi 段。定时测速要回答的是
 * 「**设备蜂窝网**现在多快」，必须在设备本地跑，且不能依赖任何客户端在线。
 *
 * ## 协议（与 SpeedTestRoutes 的服务端两侧互为镜像）
 * 1. 延迟/抖动：对 /speedtest 发 N 次 HEAD，取中位数与平均绝对偏差；
 * 2. 下行：GET /speedtest?ckSize=N 流式读取，按 (bytes * 8 / elapsed) 算 Mbps；
 * 3. 上行：POST /upload 灌 4~8s 随机数据，按响应里的 bytes 结算。
 *
 * 走白名单外网节点（speedtestone.losn.cc，与客户端同一节点，可对比）。
 * 全程普通 HttpURLConnection，无任何特权。
 *
 * 并发防护：对象级 [running] 标志 —— 测速本来就独占蜂窝带宽，同时跑两个只会
 * 互相污染结果；并发位被占时直接返回 null（调用方提示「测速进行中」）。
 */
class SpeedTestRunner(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val tag = "SpeedTestRunner"
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /**
     * 跑一轮完整测速。
     * @return 结果；已有测速在跑时返回 null（不是排队 —— 排队会让两次测速叠一起）。
     */
    suspend fun run(): SpeedTestResult? {
        if (!running.compareAndSet(false, true)) return null
        try {
            return withContext(Dispatchers.IO) { execute() }
        } catch (e: Exception) {
            AppLogger.w(tag, "speedtest failed: ${e.message}")
            throw e
        } finally {
            running.set(false)
        }
    }

    private fun execute(): SpeedTestResult {
        var bytesUsed = 0L
        val (latency, jitter) = measureLatency()
        val down = measureDownload { bytesUsed += it }
        val up = measureUpload { bytesUsed += it }
        return SpeedTestResult(
            timestamp = nowMs(),
            latencyMs = latency,
            jitterMs = jitter,
            downloadMbps = down,
            uploadMbps = up,
            bytesUsed = bytesUsed,
        )
    }

    /** 8 次 HEAD 探针：中位数 = 延迟，平均绝对偏差 = 抖动。 */
    private fun measureLatency(): Pair<Int, Int> {
        val samples = mutableListOf<Long>()
        repeat(LATENCY_PROBES) {
            val conn = open("HEAD", null)
            try {
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                val t0 = nowMs()
                val code = conn.responseCode
                val dt = nowMs() - t0
                if (code in 200..399) samples += dt
            } catch (_: IOException) {
                // 单次探针失败忽略：中位数对离群值鲁棒
            } finally {
                conn.disconnect()
            }
        }
        if (samples.isEmpty()) return -1 to -1
        samples.sort()
        val median = samples[samples.size / 2]
        val mad = samples.map { kotlin.math.abs(it - median) }.average().toInt()
        return median.toInt() to mad
    }

    /** 下行：拉固定块数，吞吐按实际读到字节与墙钟时间算。 */
    private fun measureDownload(onBytes: (Long) -> Unit): Double {
        val conn = open("GET", "?ckSize=${Units.SPEEDTEST_CHUNKS_DEFAULT}")
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            val t0 = nowMs()
            var bytes = 0L
            val sink = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                while (nowMs() - t0 < DOWNLOAD_BUDGET_MS) {
                    val n = input.read(sink)
                    if (n == -1) break
                    bytes += n
                }
            }
            onBytes(bytes)
            val elapsedMs = (nowMs() - t0).coerceAtLeast(1)
            return bytes * 8.0 / elapsedMs / 1000.0 // bytes→Mbit / s
        } finally {
            conn.disconnect()
        }
    }

    /** 上行：灌 4s 随机数据，读响应里的 bytes（服务端按实际收到结算）。 */
    private fun measureUpload(onBytes: (Long) -> Unit): Double {
        val conn = open("POST", "/upload")
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
            val t0 = nowMs()
            var sent = 0L
            conn.outputStream.use { out ->
                while (nowMs() - t0 < UPLOAD_BUDGET_MS) {
                    out.write(payload)
                    sent += payload.size
                }
                out.flush()
            }
            val body = conn.inputStream?.readBytes()?.toString(StandardCharsets.UTF_8) ?: ""
            val acknowledged = Regex("\"bytes\":(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: sent
            onBytes(acknowledged)
            val elapsedMs = (nowMs() - t0).coerceAtLeast(1)
            return acknowledged * 8.0 / elapsedMs / 1000.0
        } catch (e: IOException) {
            // 上行链路差是真实结果不是错误：已发出去的部分照样算
            AppLogger.d(tag, "upload interrupted: ${e.message}")
            return -1.0
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, query: String?): HttpURLConnection {
        val url = URL(baseUrl + (query ?: ""))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Cache-Control", "no-store")
        if (method == "POST") {
            conn.setRequestProperty("Content-Type", "application/octet-stream")
        }
        return conn
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://speedtestone.losn.cc/speedtest"

        private const val LATENCY_PROBES = 8
        private const val DOWNLOAD_BUDGET_MS = 15_000L
        private const val UPLOAD_BUDGET_MS = 6_000L
    }
}

/** 一次测速的结果快照（入库与 WS 推送共用）。 */
data class SpeedTestResult(
    val timestamp: Long,
    val latencyMs: Int,
    val jitterMs: Int,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val bytesUsed: Long,
)
