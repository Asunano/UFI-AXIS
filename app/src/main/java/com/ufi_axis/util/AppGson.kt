package com.ufi_axis.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * 全局共享 Gson 实例（setLenient 兼容 goform 透传的字符串数字）。
 * 避免各 Module/Repository 重复创建，减少对象开销。
 */
@Suppress("DEPRECATION")
object AppGson {
    val instance: Gson = GsonBuilder()
        .setLenient()
        .create()
}