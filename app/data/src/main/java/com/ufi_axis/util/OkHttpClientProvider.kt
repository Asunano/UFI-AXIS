package com.ufi_axis.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 共享 OkHttpClient 单例。
 *
 * 收敛 App 内多处自建的 OkHttpClient，统一复用以降低连接 / 线程开销。
 * 保留大文件长超时（read 120s / write 300s），适配下载与大文件传输场景。
 *
 * 2026-08-27：[NetworkLogInterceptor] 改为**所有构建都安装**（原来仅 DEBUG）。网络日志现在是
 * 产品功能（日志页「网络」筛选）而不只是开发期便利：release 包出现「服务连不上」时，
 * 失败请求必须能在设备上直接看到。开销可控 —— 成功请求在调试日志关闭时直接跳过（不读正文），
 * 只有 4xx/5xx/IO 异常才无条件落一条摘要。
 */
object OkHttpClientProvider {
    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .addInterceptor(NetworkLogInterceptor())
        .build()
}
