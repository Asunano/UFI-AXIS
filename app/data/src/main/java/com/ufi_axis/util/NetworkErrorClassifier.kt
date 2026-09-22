package com.ufi_axis.util

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把异常分成「传输层打不通」与「业务层出错」两类。
 *
 * ## 为什么需要它
 *
 * 在这之前，各 module 的 catch 分支一律拼 `"xxx失败: ${e.message}"`（`DashboardModule` 里
 * 就有 8 处），既不区分「手机压根没连上设备」还是「接口返回了 500」，也让用户在设备离线时
 * 看到十几条互不相干的红条 —— 每条都在描述症状，没有一条说出病因。
 *
 * 判定口径故意保守：**只有确凿的传输层异常**才算 [isTransportFailure]。
 * HTTP 4xx/5xx（Retrofit 的 `HttpException`）说明**连接是通的**，属于业务错误，
 * 不能算进来，否则「core 活着但某个接口报 500」会被误报成设备离线。
 */
object NetworkErrorClassifier {

    /**
     * 是否为传输层失败（连不上 / 超时 / DNS / SSL / 连接被重置）。
     *
     * 会沿 `cause` 链向下找：OkHttp 与 Retrofit 常把真实原因包在外层异常里。
     */
    fun isTransportFailure(e: Throwable?): Boolean {
        var cur = e
        var depth = 0
        while (cur != null && depth < MAX_CAUSE_DEPTH) {
            when (cur) {
                is UnknownHostException,          // DNS 解析失败 / 主机名不存在
                is ConnectException,              // 连接被拒（core 没在跑 / 端口不对）
                is SocketTimeoutException,        // 连上了但没响应
                is NoRouteToHostException,        // 不在同一网络
                is PortUnreachableException,
                is SSLException -> return true
                is SocketException -> return true // connection reset / broken pipe
            }
            // 兜底：纯 IOException 且不是上面的具体类型时也算传输层
            // （OkHttp 会抛 `java.io.IOException: unexpected end of stream` 这类）
            if (cur is IOException && cur.javaClass == IOException::class.java) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    /**
     * 给传输层失败一个统一的、指向病因的文案；非传输层返回 null（让调用方保留自己的业务文案）。
     *
     * @param hasNetwork 手机是否连着任何网络（见 [NetworkMonitor.hasNetwork]）。
     *   传 null 表示调用方拿不到这个信息，此时给一句中性的文案。
     */
    fun describe(e: Throwable?, hasNetwork: Boolean? = null): String? {
        if (!isTransportFailure(e)) return null
        return when (hasNetwork) {
            false -> "手机未连接网络"
            else -> "无法连接到设备，请确认已连上设备热点且后台服务在运行"
        }
    }

    private const val MAX_CAUSE_DEPTH = 8
}
