package com.ufi_axis.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通知闸门与通知历史的**回归护栏**（2026-09-08）。
 *
 * ## 被守护的不变量
 * 1. **每条通知的结果都有记录**：`NotifyHistoryStore.record` 只允许 `NotificationCenter.notify`
 *    调用，且 `notify` 的每个提前退出分支都要落一条 —— 否则"为什么没收到通知"又会退回靠猜。
 * 2. **分类键两处一致**：`sceneEnabledKey` 里出现的每个分类键都必须在 `CATEGORY_KEYS` 里。
 *    这两处一个管投递闸门、一个管「是否还有分类开着」的 UI 摘要，漏一个就会出现
 *    「摘要说全关了，实际那一类还在弹」。
 * 3. **后台守护只有一个闸门**：`BackgroundGuardWorker` 不得再引用告警分类键
 *    （拆分前它把 `alert_notification_enabled` 当隐藏总闸，关掉告警连巡检一起静默停掉）。
 * 4. **保留上限是真设置项**：历史裁剪必须现取当前值（`maxRows(` / `maxAgeDays(`），
 *    不许在裁剪处写死数字 —— 本仓的教训是 `AlertConfig.maxRows`「可配置但引擎从不读」。
 * 5. **远端改配置必须推镜像快照**：`NotificationConfigSync.applyRemote` 写完共享 prefs 后
 *    必须 `dispatchSwitchSnapshot`，否则 `:ufi_notify` 仍按旧值发通知 / 裁历史（假开关）。
 *
 * ## 为什么是源码级断言
 * 与 [KeepAliveGateGuardTest] 同一理由：这些不变量没有纯 JVM 的运行期出口 ——
 * 取值要 `Context` + `SharedPreferences`，`NotifyPrefs.isNotifyProcess()` 会调
 * `android.app.Application.getProcessName()`（JVM 单测未 mock 直接抛）。
 * 这里退一步做源码不变量护栏：纯文件读取 + 正则，零依赖、毫秒级，红灯时附上原因。
 */
class NotifyGateGuardTest {

    private val notificationCenterPath =
        "app/data/src/main/java/com/ufi_axis/data/notification/NotificationCenter.kt"

    private val historyStorePath =
        "app/data/src/main/java/com/ufi_axis/data/notification/NotifyHistoryStore.kt"

    private val guardWorkerPath =
        "app/data/src/main/java/com/ufi_axis/data/notification/BackgroundGuardWorker.kt"

    private val configSyncPath =
        "app/data/src/main/java/com/ufi_axis/data/notification/NotificationConfigSync.kt"

    /** 承载状态栏通知的模块目录：护栏 1 要在整个目录里数 `record(` 的出现位置。 */
    private val notificationDir = "app/data/src/main/java/com/ufi_axis/data/notification"

    /**
     * 定位源文件（口径同 [KeepAliveGateGuardTest.source]）。
     *
     * Gradle 单测的工作目录是模块目录（`app`），IDE 可能从仓库根启动，所以逐级上溯。
     */
    private fun resolve(relative: String): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "定位不到 '$relative'（起点：${File(".").absolutePath}）。" +
                "若模块路径发生变化，请同步更新本测试中的相对路径。"
        )
    }

    private fun source(relative: String): String = resolve(relative).readText()

    /** 剥掉块注释与行注释，只留可执行代码 —— 这些文件的注释里键名出现多次，全文搜必然误报。 */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    /** 取 `fun <name>(` 之后到下一个同缩进成员函数声明之前的代码片段。 */
    private fun functionBody(code: String, name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("源码里找不到函数 `$name`，护栏的定位假设已失效", start >= 0)
        val rest = code.substring(start + 4)
        val next = Regex("""\n {4}(?:private |internal |public )?(?:suspend )?fun """)
            .find(rest)?.range?.first ?: rest.length
        return rest.substring(0, next)
    }

    // ── 1. 历史只在唯一出口写，且每个分支都写 ───────────────────────────────

    @Test
    fun historyMustBeWrittenOnlyByNotificationCenter() {
        val offenders = resolve(notificationDir).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "NotifyHistoryStore.kt" && it.name != "NotificationCenter.kt" }
            .filter { executableCode(it.readText()).contains("NotifyHistoryStore.record(") }
            .map { it.name }

        assertTrue(
            "只有 NotificationCenter.notify 允许写通知历史，否则「每条通知都有记录」会退化成" +
                "「记得写的地方才有记录」。越界文件：$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun notifyMustRecordEveryExit() {
        val body = functionBody(executableCode(source(notificationCenterPath)), "notify")

        // 被拦下的分支统一走本地 `blocked(reason)` 辅助函数（它内部落记录），
        // 送达分支单独写一次 —— 两者都必须在 notify 体内出现。
        assertTrue(
            "notify 里必须有集中记录「被拦下」的出口（blocked(...)），否则每加一个闸门" +
                "都要记得补一次记录，迟早漏。",
            body.contains("blocked(")
        )
        assertTrue(
            "notify 必须在真正发出通知后记一条 delivered = true 的历史。",
            Regex("""NotifyHistoryStore\.record\([\s\S]*?delivered = true""").containsMatchIn(body)
        )
        // 每个 REASON_* 常量都应有人用：常量加了但没人传，等于历史里永远查不到那个原因。
        val reasons = Regex("""const val (REASON_[A-Z_]+)""")
            .findAll(source(historyStorePath))
            .map { it.groupValues[1] }
            .toList()
        assertTrue("NotifyHistoryStore 里应当定义了 REASON_* 常量", reasons.isNotEmpty())
        val unused = reasons.filterNot { body.contains("NotifyHistoryStore.$it") }
        assertTrue("这些拦截原因没有任何出口在用，历史里查不到它们：$unused", unused.isEmpty())
    }

    // ── 2. 分类键两处一致 ───────────────────────────────────────────────────

    @Test
    fun categoryKeysMustCoverEverySceneKey() {
        val code = executableCode(source(notificationCenterPath))

        val listedInCategory = Regex("""val CATEGORY_KEYS[\s\S]*?\)""")
            .find(code)
            ?.value
            ?.let { Regex("""KEY_[A-Z0-9_]+""").findAll(it).map { m -> m.value }.toSet() }
            .orEmpty()
        assertTrue("找不到 CATEGORY_KEYS 的定义，护栏的定位假设已失效", listedInCategory.isNotEmpty())

        val usedBySceneKey = Regex("""fun sceneEnabledKey\([\s\S]*?\n {4}\}""")
            .find(code)
            ?.value
            ?.let { Regex("""->\s*(KEY_[A-Z0-9_]+)""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
            .orEmpty()
        assertTrue("找不到 sceneEnabledKey 的定义，护栏的定位假设已失效", usedBySceneKey.isNotEmpty())

        assertEquals(
            "sceneEnabledKey 与 CATEGORY_KEYS 必须逐个对应：前者管投递闸门、后者管「还有分类开着吗」" +
                "的 UI 摘要，漏一个就会出现「摘要说全关了、那一类还在弹」。",
            usedBySceneKey,
            listedInCategory
        )
    }

    // ── 3. 后台守护只有一个闸门 ─────────────────────────────────────────────

    @Test
    fun backgroundGuardMustNotReadAlertCategory() {
        val code = executableCode(source(guardWorkerPath))
        assertFalse(
            "后台守护只能由 guard_enabled 一个开关决定。它曾经把告警分类键当隐藏总闸，" +
                "用户关掉告警通知会连带把巡检静默停掉。",
            code.contains("KEY_ALERT_NOTIF")
        )
    }

    // ── 4. 保留上限是真设置项 ───────────────────────────────────────────────

    @Test
    fun historyTrimMustReadCurrentSetting() {
        val body = functionBody(executableCode(source(historyStorePath)), "record")
        assertTrue(
            "裁剪必须现取保留条数（maxRows(...)）；写死数字会让「保留条数」变成假开关。",
            body.contains("maxRows(")
        )
        assertTrue(
            "裁剪必须现取保留天数（maxAgeDays(...)），并且 0 = 不按时间清理。",
            body.contains("maxAgeDays(")
        )
        assertFalse(
            "record 里不该出现写死的天数/毫秒常量：两道上限都必须来自设置项。" +
                "（2026-09-08 之前这里挂着一条界面上看不见的 7 天规则。）",
            Regex("""7L?\s*\*\s*24""").containsMatchIn(body)
        )
    }

    // ── 5. 远端改配置必须刷新 `:ufi_notify` 的镜像 ───────────────────────────

    @Test
    fun applyRemoteMustPushMirrorSnapshot() {
        val body = functionBody(executableCode(source(configSyncPath)), "applyRemote")
        assertTrue(
            "applyRemote 写完共享 prefs 后必须 NotifyDispatchReceiver.dispatchSwitchSnapshot：" +
                "`:ufi_notify` 读开关只认自己那份 mirror_ 副本，AIDL 通道只覆盖免打扰/守护/保活几个键。" +
                "少这一句，从 web 关掉「全局通知」那个进程仍会继续弹（假开关）。",
            body.contains("dispatchSwitchSnapshot(")
        )
    }
}
