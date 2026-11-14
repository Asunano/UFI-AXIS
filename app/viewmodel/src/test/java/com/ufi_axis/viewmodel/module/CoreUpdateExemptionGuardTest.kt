package com.ufi_axis.viewmodel.module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「core 自更新期间的断联豁免」回归护栏（2026-10-10）。
 *
 * ## 被守护的问题
 * core 自更新进行中，app 弹出**全屏**「无法连接到设备」红字提示，同时更新状态**全程无数据**。
 *
 * ## 根因
 * `ToolsModule.triggerDeviceUpdate()` 旧实现把 `markCoreUpdating(true)` 写在 `runCatching`
 * 的 **`.onSuccess` 分支**里。但 `POST /api/update/check` 的语义是「让 core 立刻开始
 * 下载+安装+重启」—— **应答返回时 core 已经在下载了**。于是只要这次请求因为
 * 「core 一边重启一边应答」而失败（这是更新的**必然结果**，不是意外），
 * `runCatching` 就走 `onFailure`，标记永远没落地：
 * - 标记没落地 → `MainViewModel.isCoreUpdateInterruptingBackend()` 返回 false
 *   → 健康检查照常弹全屏提示；
 * - 同时没人起轮询 → 更新状态从触发到完成一格数据都没有。
 *
 * ## 两条不变量
 * 1. **标记先于请求**：`markCoreUpdating(true)` 必须在 `api.triggerDeviceUpdate()` 之前，
 *    且不得只出现在 `onSuccess` 分支里。
 * 2. **失败不清标记**：「请求失败」≠「core 没开始更新」——core 完全可能已经拉起来了
 *    （正是上面那种情况）。`onFailure` 里写 `markCoreUpdating(false)` 会把这个 bug 稳定复现；
 *    正确做法是起轮询读真实状态，由轮询自己清（终态 done/failed，或 5 分钟失联阈值）。
 *
 * ## 对照证据（为什么只锁这一条路径）
 * `pushApkAndInstall`（本地 APK 推送）那条路径本来就是「先写标记再上传」，顺序正确 ——
 * 所以只有 core 自更新出问题，正好佐证根因是**写入时机**而不是标记机制本身。
 *
 * ## 为什么护栏是源码级断言
 * 复现需要「core 在应答的同时重启」这个竞态，行为级测试要真造一个 Retrofit 假故障 +
 * 主线程调度，成本远高于收益；而回归形态非常具体（把 `markCoreUpdating(true)` 挪回
 * `onSuccess`、或在 `onFailure` 里补一句 `markCoreUpdating(false)`），正则就能钉死。
 */
class CoreUpdateExemptionGuardTest {

    @Test
    fun triggerDeviceUpdate_markMustPrecedeRequest() {
        val code = executableCode(source(ToolsModulePath))
        val body = functionBody(code, "fun triggerDeviceUpdate()")

        val markIdx = body.indexOf("markCoreUpdating(true)")
        val reqIdx = body.indexOf("api.triggerDeviceUpdate()")

        assertTrue(
            "triggerDeviceUpdate 里找不到 markCoreUpdating(true) —— " +
                "更新期间的断联豁免标记必须无条件写入，不能只在成功分支里写",
            markIdx >= 0
        )
        assertTrue(
            "triggerDeviceUpdate 里找不到 api.triggerDeviceUpdate() 调用点，护栏定位假设已失效",
            reqIdx >= 0
        )
        assertTrue(
            "markCoreUpdating(true) 必须写在 api.triggerDeviceUpdate() **之前** —— " +
                "POST /api/update/check 的语义是「让 core 立刻开始下载+安装+重启」，" +
                "应答返回时 core 已经在下载了；写在 onSuccess 分支里会漏标记，回归为" +
                "「更新中弹全屏无法连接到设备 + 更新状态全程无数据」",
            markIdx < reqIdx
        )

        // 旧回归形态：markCoreUpdating(true) 被塞进 onSuccess 分支。
        val onSuccessIdx = body.indexOf(".onSuccess")
        assertTrue(
            "onSuccess 分支消失了，护栏的定位假设已失效，请同步更新本测试",
            onSuccessIdx >= 0
        )
        assertTrue(
            "markCoreUpdating(true) 回到 onSuccess 分支里了 —— 这正是 2026-10-10 的根因，" +
                "标记必须先于请求写",
            markIdx < onSuccessIdx
        )
    }

    @Test
    fun triggerDeviceUpdate_failureMustNotClearMark() {
        val body = functionBody(executableCode(source(ToolsModulePath)), "fun triggerDeviceUpdate()")

        val onFailureIdx = body.indexOf(".onFailure")
        assertTrue(
            "onFailure 分支消失了，护栏的定位假设已失效，请同步更新本测试",
            onFailureIdx >= 0
        )
        val failureTail = body.substring(onFailureIdx)
        assertFalse(
            "onFailure 里出现 markCoreUpdating(false) —— 「请求失败」≠「core 没开始更新」，" +
                "core 完全可能已经拉起来了。清掉标记会让健康检查立刻弹全屏「无法连接到设备」，" +
                "把这个 bug 稳定复现。正确做法是起轮询读真实状态，由轮询自己清",
            Regex("""markCoreUpdating\s*\(\s*false\s*\)""").containsMatchIn(failureTail)
        )
        assertTrue(
            "onFailure 里应当起轮询接管（core 可能已经在更新，只是这次请求没拿到应答）—— " +
                "没有轮询就没人读真实状态，更新状态会全程无数据",
            failureTail.contains("startDeviceUpdatePolling()")
        )
    }

    // ── 测试脚手架 ──────────────────────────────────────────────────────────

    private companion object {
        const val ToolsModulePath =
            "src/main/java/com/ufi_axis/viewmodel/viewmodel/module/ToolsModule.kt"
    }

    /** 从 `fun xxx(` 起截取配平花括号的方法体（含签名行）。 */
    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        if (start < 0) throw AssertionError("源码里找不到 `$signature`")
        val open = code.indexOf('{', start)
        if (open < 0) throw AssertionError("`$signature` 后面找不到 '{'")
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("`$signature` 的花括号没配平")
    }

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/viewmodel/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "定位不到源文件 '$relative'（起点：${File(".").absolutePath}）。" +
                "若模块路径发生变化，请同步更新本测试中的相对路径。"
        )
    }

    /** 剥掉块注释与行注释，只留**可执行代码**（这两个文件的 KDoc 里反复出现这两个函数名）。 */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
}