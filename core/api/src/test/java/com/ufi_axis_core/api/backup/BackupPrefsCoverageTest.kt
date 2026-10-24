package com.ufi_axis_core.api.backup

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「core 里每个 prefs 文件都必须显式表态」的守卫测试（2026-10-15）。
 *
 * ## 为什么需要它
 * 新功能很自然地会新建一个 `getSharedPreferences("xxx")`，但**很容易忘记**把它登记进
 * `BackupAssembler.PREFS_FILES`。表现是"换设备后这份配置全没了"，而且**不报任何错** ——
 * 2026-10-15 实测就漏了三份：音乐歌单（`audio_playlists`）、媒体库移除名单
 *（`media_exclusions`）、以及当时刚做的配对黑名单（`paired_blacklist`）。
 *
 * 所以口径钉死：**每个 prefs 文件名要么进 `PREFS_FILES`，要么进下面的排除名单（带理由）**。
 *
 * 只扫 `src/main`（测试自己建的 prefs 不算生产数据）。
 */
class BackupPrefsCoverageTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（起点：${File(".").absolutePath}）")
    }

    /** 扫 core 各模块的 main 源码，收集 `getSharedPreferences("名字", …)` 里的名字。 */
    private fun prefsNamesInCore(): Set<String> {
        val regex = Regex("""getSharedPreferences\(\s*"([^"]+)"""")
        val found = sortedSetOf<String>()
        val core = File(repoRoot(), "core")
        core.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .forEach { file ->
                regex.findAll(file.readText()).forEach { found += it.groupValues[1] }
            }
        return found
    }

    /**
     * 明确**不整份搬**的 prefs 文件 → 理由。
     *
     * 目前只有一个：主设置文件。它的键由 `AppSettings.BACKUP_FIELDS` 白名单逐键搬，
     * 整份搬会把身份与凭据也带过去（见 `AppSettingsBackupCompletenessTest`）。
     */
    private val notBackedUp: Map<String, String> = mapOf(
        "ufi_axis_settings" to "主设置文件：走 BACKUP_FIELDS 白名单逐键搬，不整份复制"
    )

    @Test
    fun `core 里每个 prefs 文件要么进备份要么在排除名单里`() {
        val names = prefsNamesInCore()
        assertTrue("前置：应能扫到若干 prefs 文件名（扫描失败说明路径/正则失效）", names.size >= 3)

        val assembler = File(
            repoRoot(),
            "core/api/src/main/java/com/ufi_axis_core/api/backup/BackupAssembler.kt"
        ).readText()
        val backedUp = Regex("""PrefsSpec\(\s*"([^"]+)"""")
            .findAll(assembler).map { it.groupValues[1] }.toSet()

        val unaccounted = names - backedUp - notBackedUp.keys
        assertTrue(
            "以下 prefs 文件既不在 PREFS_FILES、也没在排除名单里 —— 新增 store 必须显式表态。" +
                "漏登记的表现是「换设备后这份配置全没了」，而且不报错。实际漏了：$unaccounted",
            unaccounted.isEmpty()
        )
    }

    @Test
    fun `本轮补漏的三个 prefs 文件必须在备份列表里`() {
        val assembler = File(
            repoRoot(),
            "core/api/src/main/java/com/ufi_axis_core/api/backup/BackupAssembler.kt"
        ).readText()
        val backedUp = Regex("""PrefsSpec\(\s*"([^"]+)"""")
            .findAll(assembler).map { it.groupValues[1] }.toSet()

        listOf(
            "audio_playlists" to "音乐歌单",
            "media_exclusions" to "媒体库移除名单",
            "paired_blacklist" to "配对设备黑名单"
        ).forEach { (name, label) ->
            assertTrue("$label（$name）必须进 PREFS_FILES", name in backedUp)
        }
    }
}
