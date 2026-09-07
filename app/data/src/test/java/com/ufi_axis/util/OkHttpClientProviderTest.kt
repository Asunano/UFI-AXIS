package com.ufi_axis.util

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * OkHttpClientProvider 单测（B3：收敛为共享单例 + 长超时）。
 *
 * 验证：
 *  - shared 非 null
 *  - 超时与代码一致：connect 15s / read 120s / write 300s
 *  - 两次取 shared 为同一实例（单例）
 */
class OkHttpClientProviderTest {

    @Test
    fun `shared is non-null singleton`() {
        val a = OkHttpClientProvider.shared
        val b = OkHttpClientProvider.shared
        assertNotNull(a)
        assertSame("shared 应为同一单例实例", a, b)
    }

    @Test
    fun `timeouts match spec connect=15s read=120s write=300s`() {
        val client: OkHttpClient = OkHttpClientProvider.shared
        assertEquals("connectTimeout 应为 15s", 15_000, client.connectTimeoutMillis)
        assertEquals("readTimeout 应为 120s", 120_000, client.readTimeoutMillis)
        assertEquals("writeTimeout 应为 300s", 300_000, client.writeTimeoutMillis)
    }
}
