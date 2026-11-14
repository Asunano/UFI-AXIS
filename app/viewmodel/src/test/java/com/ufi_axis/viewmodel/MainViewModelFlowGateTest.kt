package com.ufi_axis.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「转场期暂缓发布」的统一性护栏（2026-11-03）。
 *
 * ## 守的是什么
 * 掉帧的规律很稳定：动画本身满帧，掉帧一律发生在"进页那一刻有数据往屏幕上砸"。
 * 治法是让 `MainViewModel` 暴露给 UI 的每条 StateFlow 都过 `gatedByTransitionGate`
 * （转场期间攒着、落定后只提交最新值）。这是**唯一出口**，所以新页面、新模块
 * 只要把状态从这里暴露出去就自动生效，不需要各自记得加闸门。
 *
 * 唯一的失效方式就是：以后有人加一条新的委托式状态流，忘了写 `.ui()` ——
 * 那样它会绕过闸门，同一个毛病在新页面上复活，而且没有任何报错。本测试就是那个兜底。
 *
 * ## 判定口径
 * 只检查 `val X: StateFlow<…> get() = <表达式>` 这种**委托式**属性。
 * `combine(...)` / `asStateFlow()` 这类**自建**流不在检查范围：它们里面既有必须立刻可见的
 * 全局错误、连接状态、启动遮罩（这些**不该**被暂缓），也有页面数据（该暂缓）——
 * 一刀切会破坏"用户操作反馈必须立刻可见"的语义，所以逐条人工判断，这里不强制。
 */
class MainViewModelFlowGateTest {

    private val viewModelPath =
        "app/viewmodel/src/main/java/com/ufi_axis/viewmodel/viewmodel/MainViewModel.kt"

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("定位不到源文件 '$relative'")
    }

    @Test
    fun everyDelegatingStateFlow_mustBeGated() {
        val lines = source(viewModelPath).lines()
        // 委托式状态流：`val xxx: StateFlow<T> get() = <表达式>`
        val delegating = Regex("""^\s*val \w+: StateFlow<.+> get\(\) = .+$""")
        val offenders = lines
            .filter { delegating.matches(it) }
            .filterNot { it.trimEnd().endsWith(".ui()") }
        assertTrue(
            "以下状态流没有经过转场闸门（缺 .ui()），会绕过「转场期暂缓发布」：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun gateHelper_mustBeWired() {
        val text = source(viewModelPath)
        assertTrue(
            "MainViewModel 里找不到 gatedByTransitionGate —— 统一闸门被摘掉了？",
            text.contains("gatedByTransitionGate"),
        )
    }
}
