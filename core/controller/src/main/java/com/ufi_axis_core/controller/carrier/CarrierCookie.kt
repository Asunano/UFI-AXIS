package com.ufi_axis_core.controller.carrier

import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** 可序列化的 Cookie 快照；由 `CarrierSessionStore` 加密落盘。 */
@Serializable
data class CarrierCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
)

/**
 * 只存在内存里的 CookieJar；持久化由上层负责。
 *
 * 移动/联通共用：两家的 Cookie 语义都是「按 domain/path 回放」，
 * 不需要为每家写一份。
 */
class CarrierCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            this.cookies.add(cookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { it.matches(url) }

    fun snapshot(): List<CarrierCookie> = cookies.map {
        CarrierCookie(
            name = it.name,
            value = it.value,
            // OkHttp 的 domain cookie 以 "." 开头，但 Cookie.Builder.domain()
            // 不接受前导点。快照统一去掉，恢复时才不会静默丢掉整批 Cookie。
            domain = it.domain.removePrefix("."),
            path = it.path,
            expiresAt = it.expiresAt,
            secure = it.secure,
            httpOnly = it.httpOnly,
        )
    }

    fun restore(items: List<CarrierCookie>) {
        cookies.clear()
        for (item in items) {
            runCatching {
                Cookie.Builder()
                    .name(item.name)
                    .value(item.value)
                    .domain(item.domain.removePrefix("."))
                    .path(item.path)
                    .apply {
                        if (item.expiresAt > 0) expiresAt(item.expiresAt)
                        if (item.secure) secure()
                        if (item.httpOnly) httpOnly()
                    }
                    .build()
            }.onSuccess { cookies.add(it) }
        }
    }

    fun clear() = cookies.clear()

    fun value(name: String): String? = cookies.firstOrNull { it.name == name }?.value

    /** 手工塞一个 Cookie（联通的 `piw` 是 JSONP 返回的，不在 Set-Cookie 里）。 */
    fun putRaw(name: String, value: String, domain: String) {
        runCatching {
            Cookie.Builder().name(name).value(value).domain(domain.removePrefix(".")).path("/").build()
        }.onSuccess { cookie ->
            cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            cookies.add(cookie)
        }
    }
}
