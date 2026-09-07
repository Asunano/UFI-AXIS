package com.ufi_axis.util

import android.content.Context
import com.ufi_axis.data.api.RetrofitClient
import okhttp3.OkHttpClient

/**
 * 下载 / 通用 HTTP 客户端：复用 [OkHttpClientProvider] 的共享单例（含网络日志拦截器），
 * 并叠加与 Retrofit 相同的鉴权拦截器（自动注入 Bearer、触发 401 重配对）。
 *
 * 必须在 [init] 中于 Application.onCreate() 完成初始化，拦截器才能惰性读取最新 token。
 */
object AppHttpClient {
    @Volatile
    private var appContext: Context? = null

    fun init(ctx: Context) { appContext = ctx.applicationContext }

    val instance: OkHttpClient by lazy {
        val ctx = appContext
            ?: throw IllegalStateException("AppHttpClient 未初始化：请在 Application.onCreate() 调用 AppHttpClient.init(context)")
        OkHttpClientProvider.shared.newBuilder()
            .addInterceptor(RetrofitClient.authInterceptor { AppPreferences(ctx) })
            .build()
    }
}
