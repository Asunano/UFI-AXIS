package com.ufi_axis.data.api

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码守卫：「凭据被吊销 → 退回配对页」这条回调必须**强引用**持有。
 *
 * ## 守的是哪次事故
 *
 * 2026-11-14：机主在 A 上删掉 B 的配对，B 的 App 不会被踢出登录。排查结果是
 * `ConnectionBootstrap` 用 `WeakReference` 持有 MainActivity 传进来的**内联 lambda** ——
 * 除了这个弱引用没人持有它，一次 GC 就回收，`get()` 返回 null：
 * 吊销判定照常执行、`isSetupComplete=false` 也写进了 prefs，**但界面当场毫无反应**，
 * 用户只能重启 App 才被踢回配对页。
 *
 * 这类回归没有任何运行期测试能覆盖（要触发真实 GC 时序），但"用没用弱引用"是源码事实，
 * 所以按本仓既有的源码扫描守卫（见 `SessionTeardownGuardTest`）钉在这里。
 */
class CredentialRevocationWiringGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（起点：${File(".").absolutePath}）")
    }

    /** 去掉注释：说明里会提到 WeakReference（讲历史），那不是代码。 */
    private fun bootstrapSource(): String =
        File(repoRoot(), "app/src/main/java/com/ufi_axis/connection/ConnectionBootstrap.kt")
            .readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `吊销回调用强引用持有，不得退回 WeakReference`() {
        val src = bootstrapSource()
        assertFalse(
            "ConnectionBootstrap 又用上了 WeakReference：内联 lambda 只被弱引用持有，一次 GC 就会让" +
                "「删配对 → 退回配对页」静默失效（2026-11-14 事故）。改用强引用字段 + onDispose 注销。",
            src.contains("WeakReference")
        )
        assertTrue(
            "找不到强引用字段 onUnauthorizedHandler：吊销回调必须有地方被强持有",
            src.contains("onUnauthorizedHandler")
        )
    }

    @Test
    fun `注销时把静态回调一并清掉`() {
        val src = bootstrapSource()
        val unregister = src.substringAfter("fun unregisterUnauthorizedHandler()", "")
        assertTrue(
            "unregisterUnauthorizedHandler 必须同时清 onUnauthorizedHandler 与 RetrofitClient.onUnauthorized，" +
                "否则静态字段会滞留已销毁的 Activity",
            unregister.contains("onUnauthorizedHandler = null") &&
                unregister.contains("RetrofitClient.onUnauthorized = null")
        )
    }
}
