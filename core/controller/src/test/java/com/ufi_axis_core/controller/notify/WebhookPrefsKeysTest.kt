package com.ufi_axis_core.controller.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebhookPrefsKeys] 的两条判据：**键怎么起名**与**要不要搬迁**。
 *
 * 为什么这两条在这里、而 [WebhookConfigStore] 的读写行为在 `:core:api`：
 * store 要 `Context` → `SharedPreferences`，而本模块的单测源集只有 junit
 * （`build.gradle.kts` 里那行注释写了理由）。本仓既有做法是把要真 `Context` 的用例放到
 * `:core:api`（那边有 Robolectric，见 `WebhookChannelContractTest`），
 * 纯判定留在本模块 —— 所以分层与搬迁的**判据**抽成了纯函数放在这里，
 * 而"切换往返不丢 / 搬迁真的搬了"那几条在 `:core:api` 的 `WebhookConfigStoreTest`。
 */
class WebhookPrefsKeysTest {

    // ══════════ 键怎么起名 ══════════

    /** 前缀是预设名的**小写** + `.` —— prefs 文件是要被人打开看的，`bark.url` 得一眼认出来。 */
    @Test
    fun `每预设的键名是小写预设名加点加字段名`() {
        assertEquals("bark.url", WebhookPrefsKeys.scoped(WebhookPreset.BARK, WebhookPrefsKeys.URL))
        assertEquals(
            "pushplus.body_template",
            WebhookPrefsKeys.scoped(WebhookPreset.PUSHPLUS, WebhookPrefsKeys.BODY)
        )
        assertEquals(
            "custom.headers_json",
            WebhookPrefsKeys.scoped(WebhookPreset.CUSTOM, WebhookPrefsKeys.HEADERS)
        )
    }

    /**
     * **每个预设的每个字段都拿到互不相同的键**（七个预设 × 六个字段 = 42 个）。
     *
     * 撞键的后果是隔离直接失效：两个预设共用一个 `url` 键就又变回"切一次覆盖一次"，
     * 而这件事不会报错 —— 界面上只会表现为"我填的 Bark key 怎么跑到 ntfy 那栏去了"。
     */
    @Test
    fun `预设与字段的组合两两不撞键`() {
        val keys = WebhookPreset.entries.flatMap { p ->
            WebhookPrefsKeys.PER_PRESET_FIELDS.map { WebhookPrefsKeys.scoped(p, it) }
        }
        assertEquals(
            WebhookPreset.entries.size * WebhookPrefsKeys.PER_PRESET_FIELDS.size,
            keys.size
        )
        assertEquals("有重复的键名：$keys", keys.size, keys.toSet().size)
    }

    /**
     * 每预设的键**不能撞上顶层键**。
     *
     * 顶层那几个是跨预设共享的（`enabled` / `scenes` / `min_level` / 配额计数器…），
     * 被某个预设的键盖掉的表现是"换个预设，今天已发条数归零"这类看不出原因的怪事。
     */
    @Test
    fun `每预设的键不与顶层键相撞`() {
        for (p in WebhookPreset.entries) {
            for (field in WebhookPrefsKeys.PER_PRESET_FIELDS) {
                val key = WebhookPrefsKeys.scoped(p, field)
                assertFalse("$key 撞上了顶层键", key in WebhookPrefsKeys.TOP_LEVEL_KEYS)
            }
        }
    }

    /**
     * per-preset 字段名**逐字就是老版本的扁平键名**，而且恰好六个。
     *
     * 搬迁能只靠"旧键在不在"来判据，前提就是这份清单与老版本写下的那六个键名一致；
     * 有人在这里改个名（例如 `body_template` → `body`），搬迁就会静默搬不到东西
     * —— 存量用户升级后表现为"配置丢了"，而日志里只会说"已搬迁"。
     */
    @Test
    fun `per-preset 字段名与老版本扁平键名逐字一致`() {
        assertEquals(
            listOf("url", "method", "headers_json", "body_template", "content_type", "timeout_ms"),
            WebhookPrefsKeys.PER_PRESET_FIELDS
        )
        // Long 型那个（timeout_ms）刻意不在 String 清单里：prefs 是强类型读的，
        // 用 getString 去读一个 Long 会抛 ClassCastException
        assertEquals(
            listOf("url", "method", "headers_json", "body_template", "content_type"),
            WebhookPrefsKeys.PER_PRESET_STRING_FIELDS
        )
        assertTrue(WebhookPrefsKeys.TIMEOUT_MS !in WebhookPrefsKeys.PER_PRESET_STRING_FIELDS)
    }

    /** 顶层键就是"要不要发、什么时候发"那一组 + 渠道级配额计数器，一个不多一个不少。 */
    @Test
    fun `顶层键是共享的那一组`() {
        assertEquals(
            setOf(
                "preset", "enabled", "scenes", "respect_dnd", "min_level", "daily_limit",
                "quota_day", "quota_count"
            ),
            WebhookPrefsKeys.TOP_LEVEL_KEYS
        )
    }

    // ══════════ 要不要搬迁 ══════════

    /**
     * 判据的真值表：**旧键在、新键不在**才搬。
     *
     * prefs 里没有 schema 版本号可依赖，这两个条件合起来是唯一能区分
     * "老版本装过、还没搬" 与 "已经搬过 / 全新安装" 的信号。
     */
    @Test
    fun `只有旧键在且新键不在时才搬迁`() {
        assertTrue(
            "老版本装过、还没搬",
            WebhookPrefsKeys.needsFlatMigration(hasFlatUrl = true, hasScopedUrl = false)
        )
        assertFalse(
            "全新安装：两个键都没有",
            WebhookPrefsKeys.needsFlatMigration(hasFlatUrl = false, hasScopedUrl = false)
        )
        assertFalse(
            "已经搬过：扁平键被删掉了（幂等就靠这一条）",
            WebhookPrefsKeys.needsFlatMigration(hasFlatUrl = false, hasScopedUrl = true)
        )
        assertFalse(
            "新键已经有值：不能拿旧键去盖掉用户后来填的那份",
            WebhookPrefsKeys.needsFlatMigration(hasFlatUrl = true, hasScopedUrl = true)
        )
    }
}
