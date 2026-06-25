package com.ufi_axis.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object AppHttpClient {
    val instance: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
}