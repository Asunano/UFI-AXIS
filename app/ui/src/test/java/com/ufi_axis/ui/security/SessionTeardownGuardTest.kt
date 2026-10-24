package com.ufi_axis.ui.security

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话拆除的**唯一入口**守卫（2026-10-22）。
 *
 * ## 事故
 * 2026-10-21 的 B12 修复让"WS 握手被拒"也走"清会话、回配对引导"。
 * 但冷启动时 WS 会**先于** `/health` 的校时发起握手
 *（`MainViewModel.init` 里 `dashboard.init()` 在 `health.startPeriodicCheck` 之前），
 * 此时签名用的时钟偏差还是 0 —— 设备时钟漂移 >5min 时那次握手必然 1008。
 * 于是每次打开 App 都被踢回配对页，用户看到的就是**「每次打开都要重新登录」**。
 *
 * 正确的边界只有一条：**凭据是否确证吊销，只能由 HTTP 444 的连击阈值判定**
 * （`RetrofitClient` 的 `AuthRejectionPolicy`，该文件里写着"阈值只允许有一份"）。
 * 其它任何单次失败（WS 1008、401、503）都不许清凭据、不许回配对页。
 *
 * 这类回归编译通过、单测也不会红（没人会在单测里跑真 WS 握手），只能靠源码守卫。
 */
class SessionTeardownGuardTest {

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/ui/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("定位不到源文件 '$relative'（起点：${File(".").absolutePath}）")
    }

    private fun code(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `WS 鉴权被拒不得清会话`() {
        val src = code(source("app/src/main/java/com/ufi_axis/MainActivity.kt"))
        val idx = src.indexOf("onAuthRejected")
        assertTrue("MainActivity 必须装配 wsRepo.onAuthRejected（实时通道失败要提示）", idx >= 0)
        // 取装配点之后的一小段（到下一个 DisposableEffect 为止足够覆盖整个 lambda）
        val tail = src.substring(idx, (idx + 600).coerceAtMost(src.length))
        assertTrue(
            "onAuthRejected 里不许再出现 `isSetupComplete = false` —— " +
                "WS 1008 最常见的原因是冷启动时钟未校准，不是凭据失效；" +
                "把它当成吊销会让用户每次打开 App 都要重新配对（2026-10-22 事故）",
            "isSetupComplete = false" !in tail
        )
        assertTrue(
            "也不许顺手 prepareDeviceSwitch（那是主动换设备/吊销才做的事）",
            "prepareDeviceSwitch" !in tail
        )
    }

    @Test
    fun `WS 鉴权被拒必须先重试再放弃`() {
        val src = code(source("app/data/src/main/java/com/ufi_axis/data/repository/WebSocketRepository.kt"))
        assertTrue(
            "必须保留「被拒后重试」—— 给 /health 的首次校时留出时间（否则冷启动必失败）",
            "authRetriesLeft" in src && "AUTH_RETRY_MAX" in src
        )
        assertTrue(
            "重试必须走 openSocket 而不是 connect（后者会重置计数，等于无限重试）",
            Regex("""openSocket\(\)""").findAll(src).count() >= 2
        )
    }
}
