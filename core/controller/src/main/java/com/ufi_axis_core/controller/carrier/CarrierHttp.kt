package com.ufi_axis_core.controller.carrier

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * 运营商账单共用的 HTTP 客户端（2026-11-14）。
 *
 * ## 为什么不能每个 client 各建一个
 *
 * 原先 `MobileCarrierClient` / `UnicomCarrierClient` / `CloudCardApi` 的 `defaultClient()`
 * 各自 `OkHttpClient.Builder().build()`，而构造点在 `CarrierBillService` 里有十几处
 * （发码、验证、心跳、换票、商城、账单……），全部是 `XxxClient()` 不传 http。
 *
 * 后果：
 * - 每个实例自带 ConnectionPool 与 Dispatcher，**连接与 TLS 会话完全不复用** ——
 *   移动一轮账单要串行打 8 个接口，每个都重新握手；
 * - 实例数随「账号数 × 刷新轮次 + 用户操作」线性增长，空闲连接要等各自 keepAlive 才回收，
 *   没有任何上限。
 *
 * ## 做法
 *
 * 一个进程级 `shared`（只提供连接池与调度器），三个超时档用 `newBuilder()` 派生。
 * OkHttp 的 `newBuilder()` **共享** ConnectionPool / Dispatcher / 缓存，只有超时与策略不同，
 * 所以既复用了连接，又保持了各协议原先的超时口径。
 */
internal object CarrierHttp {

    /** 进程级共享实例：只承载连接池与调度器，不直接用。 */
    private val shared: OkHttpClient = OkHttpClient.Builder()
        // 4 条空闲连接、5 分钟回收：够覆盖"多账号轮询"的并发面，又不会长期占着。
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .build()

    /** 移动 App 域：接口多、串行打，单次 20s 封顶。 */
    val mobile: OkHttpClient by lazy {
        shared.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 联通：登录链路有 302，必须跟随跳转。 */
    val unicom: OkHttpClient by lazy {
        shared.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 物联卡：上游慢得多（腾讯云 CDN 后面的 PHP 站）。 */
    val cloud: OkHttpClient by lazy {
        shared.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
