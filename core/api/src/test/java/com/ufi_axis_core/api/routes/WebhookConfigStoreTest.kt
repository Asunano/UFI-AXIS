package com.ufi_axis_core.api.routes

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.controller.notify.WebhookConfig
import com.ufi_axis_core.controller.notify.WebhookConfigStore
import com.ufi_axis_core.controller.notify.WebhookPreset
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WebhookConfigStore] 的**分层存储**：每个预设各存一份"发到哪、怎么发"，顶层那几个跨预设共享。
 *
 * 起因是一句用户原话：「配置信息需要独立储存，不要切换一次就清除一次」。改造前整个渠道只有
 * 一份扁平配置，切到别的预设就用那个预设的默认值把 url / 模板盖掉，切回来上次填的 token
 * 已经没了 —— app 与 web 为此各挂了一个「会覆盖当前配置，确认吗」的弹窗，那弹窗是在给
 * 一个设计缺陷道歉。本文件钉住的就是"不再需要那个弹窗"这件事。
 *
 * 为什么在 `:core:api` 而不是 `:core:controller`：store 要 `Context` → `SharedPreferences`，
 * 而 `:core:controller` 的单测源集只有 junit。做法与 `WebhookChannelContractTest` 一致 ——
 * 这个模块有 Robolectric，能给出真 `Context`，**不给生产代码加参数注入去迎合测试**。
 * 纯判据（键怎么起名、要不要搬迁）在 `:core:controller` 的 `WebhookPrefsKeysTest`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebhookConfigStoreTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var store: WebhookConfigStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        store = WebhookConfigStore(context)
    }

    private companion object {
        /**
         * prefs 文件名与键名在这里是**故意手抄**的。
         *
         * 本文件有几条用例钉的就是**磁盘布局**（"没存过的键不许写盘"、"扁平键搬完即删"）——
         * 跟着实现里的常量走的话，实现改名时用例会一起改名，于是什么都没测到。
         * 这几个字符串同时也是"存量用户的 prefs 长什么样"的记录：改动它们就是在改存储格式，
         * 必须连搬迁一起想清楚。
         */
        const val PREFS_NAME = "notify_webhook"

        const val KEY_PRESET = "preset"
        const val KEY_ENABLED = "enabled"
        const val KEY_SCENES = "scenes"
        const val KEY_MIN_LEVEL = "min_level"

        /** 老版本的扁平键（改造前六个字段直接摆在根上）。 */
        val FLAT_KEYS = listOf(
            "url", "method", "headers_json", "body_template", "content_type", "timeout_ms"
        )

        const val BARK_URL = "https://api.day.app/bark_device_key"
        const val NTFY_URL = "https://ntfy.sh/my_topic"
    }

    /** 一份填好的配置（per-preset 字段全部与预设默认值不同，否则测不出"存了没有"）。 */
    private fun configured(
        preset: WebhookPreset,
        url: String,
        enabled: Boolean = true,
        scenes: Set<String> = setOf(NotifyScenes.ALERT),
        minLevel: NotifyLevel = NotifyLevel.INFO,
        dailyLimit: Int = 0,
        respectDnd: Boolean = true
    ) = WebhookConfig(
        enabled = enabled,
        preset = preset,
        url = url,
        method = "POST",
        headers = mapOf("Authorization" to "Bearer ${preset.name.lowercase()}"),
        bodyTemplate = """{"from":"${preset.name}"}""",
        contentType = "application/json; charset=utf-8",
        timeoutMs = 20_000L,
        minLevel = minLevel,
        dailyLimit = dailyLimit,
        scenes = scenes,
        respectDnd = respectDnd
    )

    /**
     * 切预设：**只写顶层**（`saveShared`），per-preset 那一份一个键都不碰 ——
     * 这就是"切换"的全部动作，也是 `PUT /config` 只带 `preset` 时走的那条路。
     */
    private fun switchTo(preset: WebhookPreset) {
        store.saveShared(store.load().copy(preset = preset))
    }

    // ══════════ 切换往返 ══════════

    /**
     * **本文件最重要的一条**：A 预设填好密钥 → 切到 B → 切回 A，读到的还是那一份。
     *
     * 改造前这一趟走完 Bark 的 device key 就没了（被 Bark 的默认 `<device_key>` 模板盖掉），
     * 而用户看不到任何提示 —— 下一次告警直接发不出去。
     */
    @Test
    fun `切到别的预设再切回来，原来那份配置一字不差`() {
        val bark = configured(WebhookPreset.BARK, BARK_URL)
        store.save(bark)

        switchTo(WebhookPreset.NTFY)
        assertEquals(WebhookPreset.NTFY, store.load().preset)

        switchTo(WebhookPreset.BARK)
        val back = store.load()
        assertEquals(WebhookPreset.BARK, back.preset)
        assertEquals(BARK_URL, back.url)
        assertEquals(bark.headers, back.headers)
        assertEquals(bark.bodyTemplate, back.bodyTemplate)
        assertEquals(bark.contentType, back.contentType)
        assertEquals(bark.method, back.method)
        assertEquals(bark.timeoutMs, back.timeoutMs)
    }

    /**
     * 切换只写顶层：目标预设那一份**连默认值都不落盘**。
     *
     * 这条守的是"从未配过"这个状态本身 —— 切一次就把默认值当成"用户配的"存下来的话，
     * 将来改了那个预设的默认模板，只是点过一下的用户永远收不到新默认值。
     */
    @Test
    fun `切换预设不会把目标预设的默认值落盘`() {
        store.save(configured(WebhookPreset.BARK, BARK_URL))

        switchTo(WebhookPreset.NTFY)

        assertEquals(WebhookPreset.NTFY, store.load().preset)
        for (field in FLAT_KEYS) {
            assertFalse("切换预设时写了 ntfy.$field", prefs.contains("ntfy.$field"))
        }
        // 原来那份仍然在（切换没碰它）
        assertEquals(BARK_URL, prefs.getString("bark.url", null))
    }

    /** 写 A 不动 B：两个预设的"发到哪、怎么发"之间没有任何关系，共用一份存储就是错的。 */
    @Test
    fun `写一个预设不影响另一个预设那一份`() {
        store.save(configured(WebhookPreset.BARK, BARK_URL))
        store.save(configured(WebhookPreset.NTFY, NTFY_URL))

        assertEquals(NTFY_URL, store.load(WebhookPreset.NTFY).url)
        assertEquals(BARK_URL, store.load(WebhookPreset.BARK).url)
        // 顺手钉住 headers 也是各存一份的（Bearer 值里带着预设名）
        assertEquals(
            mapOf("Authorization" to "Bearer bark"),
            store.load(WebhookPreset.BARK).headers
        )
        assertEquals(
            mapOf("Authorization" to "Bearer ntfy"),
            store.load(WebhookPreset.NTFY).headers
        )
    }

    // ══════════ 从未配过的预设 ══════════

    /**
     * 从没配过的预设读到的是**该预设的默认值**，而且**读一次之后仍然是"没存过"**。
     *
     * 不写盘这件事有实际后果："从未配过"和"配成默认值"必须能区分 —— 一旦读的时候顺手落盘，
     * 将来改了某个预设的默认模板（比如 ntfy 的 `Priority` 头），用户那份"其实从没碰过"的配置
     * 就会以"用户自己填的"身份留在旧值上，界面上完全看不出为什么它和文档说的不一样。
     */
    @Test
    fun `从未配过的预设读到该预设的默认值且不写盘`() {
        val fresh = store.load(WebhookPreset.NTFY)

        assertEquals(WebhookPreset.NTFY.defaultUrl, fresh.url)
        assertEquals(WebhookPreset.NTFY.defaultMethod, fresh.method)
        assertEquals(WebhookPreset.NTFY.defaultHeaders, fresh.headers)
        assertEquals(WebhookPreset.NTFY.defaultBody, fresh.bodyTemplate)
        assertEquals(WebhookPreset.NTFY.defaultContentType, fresh.contentType)
        assertEquals(WebhookConfig.DEFAULT_TIMEOUT_MS, fresh.timeoutMs)

        // 读第二遍还是默认值，且磁盘上一个 ntfy.* 键都没有
        assertEquals(fresh, store.load(WebhookPreset.NTFY))
        for (field in FLAT_KEYS) {
            assertFalse(
                "读一次就把 ntfy.$field 写盘了（'从未配过'与'配成默认值'从此分不开）",
                prefs.contains("ntfy.$field")
            )
        }
    }

    /** 全新安装（一个键都没有）读出来的就是 CUSTOM 那一份默认值 —— 与改造前的行为逐字一致。 */
    @Test
    fun `全新安装读到 CUSTOM 的默认值`() {
        val fresh = store.load()

        assertEquals(WebhookPreset.CUSTOM, fresh.preset)
        assertFalse(fresh.enabled)
        assertEquals("", fresh.url)
        assertEquals(WebhookConfig.DEFAULT_METHOD, fresh.method)
        assertEquals(WebhookPreset.CUSTOM.defaultBody, fresh.bodyTemplate)
        assertEquals(emptyMap<String, String>(), fresh.headers)
        assertEquals(emptySet<String>(), fresh.scenes)
    }

    // ══════════ 顶层字段跨预设共享 ══════════

    /**
     * 「这条渠道要不要发、什么时候发」那一组换预设不变。
     *
     * 让它们跟着预设各存一份的话，用户每换一次目标就要把六个场景重勾一遍、级别重调一遍 ——
     * 而他想换的只是"发到哪"。
     */
    @Test
    fun `enabled 与 scenes 与级别配额上限跨预设共享`() {
        store.save(
            configured(
                WebhookPreset.BARK, BARK_URL,
                enabled = true,
                scenes = setOf(NotifyScenes.ALERT, NotifyScenes.SMS),
                minLevel = NotifyLevel.WARNING,
                dailyLimit = 30,
                respectDnd = false
            )
        )

        switchTo(WebhookPreset.WECOM)
        val after = store.load()

        assertEquals(WebhookPreset.WECOM, after.preset)
        assertTrue(after.enabled)
        assertEquals(setOf(NotifyScenes.ALERT, NotifyScenes.SMS), after.scenes)
        assertEquals(NotifyLevel.WARNING, after.minLevel)
        assertEquals(30, after.dailyLimit)
        assertFalse(after.respectDnd)
    }

    /**
     * 配额计数器留在顶层：**切预设不重开一天的量**。
     *
     * 每日条数上限是**渠道级**闸门（防的是下游被刷）。跟着预设分家的话用户换个预设就能
     * 重新发满一天 —— 那就把这道闸做成了假开关。
     */
    @Test
    fun `切预设不会让今日已发条数归零`() {
        store.save(configured(WebhookPreset.BARK, BARK_URL, dailyLimit = 10))
        store.consume()
        store.consume()
        assertEquals(2, store.sentToday())

        switchTo(WebhookPreset.SERVERCHAN)

        assertEquals(WebhookPreset.SERVERCHAN, store.load().preset)
        assertEquals("换个预设就能重开一天的量 = 把配额闸做成了假开关", 2, store.sentToday())
    }

    // ══════════ 一次性搬迁 ══════════

    /** 造一份"老版本存过"的扁平 prefs（六个键直接摆在根上）。 */
    private fun writeLegacyFlatPrefs(preset: WebhookPreset) {
        prefs.edit()
            .putString(KEY_PRESET, preset.name)
            .putBoolean(KEY_ENABLED, true)
            .putStringSet(KEY_SCENES, setOf(NotifyScenes.ALERT))
            .putString(KEY_MIN_LEVEL, NotifyLevel.WARNING.wireName)
            .putString("url", BARK_URL)
            .putString("method", "POST")
            .putString("headers_json", """{"Authorization":"Bearer legacy"}""")
            .putString("body_template", """{"title":"{{title}}"}""")
            .putString("content_type", "application/json; charset=utf-8")
            .putLong("timeout_ms", 15_000L)
            .apply()
    }

    /**
     * 存量用户升级：扁平键搬到**当前预设**名下、扁平键消失、再 `load()` 一次结果不变。
     *
     * 不搬的话升级后表现就是"配置丢了"（新键还没有值 → 读到预设默认值 → 一条通知都发不出去），
     * 而搬到 `custom` 的话一个用 Bark 的用户会看到"Bark 未配置"，他的 device key 躺在自定义那栏里。
     */
    @Test
    fun `扁平键搬到当前预设名下且搬完即删`() {
        writeLegacyFlatPrefs(WebhookPreset.BARK)

        val migrated = store.load()

        assertEquals(WebhookPreset.BARK, migrated.preset)
        assertEquals(BARK_URL, migrated.url)
        assertEquals(mapOf("Authorization" to "Bearer legacy"), migrated.headers)
        assertEquals("""{"title":"{{title}}"}""", migrated.bodyTemplate)
        assertEquals(15_000L, migrated.timeoutMs)
        // 顶层字段本来就在顶层，搬迁不该碰它们
        assertTrue(migrated.enabled)
        assertEquals(setOf(NotifyScenes.ALERT), migrated.scenes)
        assertEquals(NotifyLevel.WARNING, migrated.minLevel)

        // 值落到了 bark.* 前缀下，扁平键一个不剩（幂等就靠"扁平键没了"）
        assertEquals(BARK_URL, prefs.getString("bark.url", null))
        for (field in FLAT_KEYS) {
            assertFalse("扁平键 $field 搬完没删（下次 load 会再搬一遍）", prefs.contains(field))
        }

        // 第二次 load 结果完全一样（不是搬第二遍，而是判据已经不成立）
        assertEquals(migrated, store.load())
    }

    /** 搬迁之后仍然只有"当前预设"那一份有值 —— 别的预设照旧是"从未配过"。 */
    @Test
    fun `搬迁只写当前预设那一份`() {
        writeLegacyFlatPrefs(WebhookPreset.BARK)
        store.load()

        assertFalse(prefs.contains("ntfy.url"))
        assertEquals(WebhookPreset.NTFY.defaultUrl, store.load(WebhookPreset.NTFY).url)
    }

    /**
     * 已经搬过的 prefs 再进来不能被"搬"第二遍 —— 判据是"旧键在、新键不在"，
     * 而扁平键早就删了。这条防的是"用户后来改过配置，某次 load 又把老值搬回来盖掉"。
     */
    @Test
    fun `搬迁之后改配置不会被老值盖回去`() {
        writeLegacyFlatPrefs(WebhookPreset.BARK)
        store.load()

        val updated = "https://api.day.app/new_key_after_upgrade"
        store.save(store.load().copy(url = updated))

        assertEquals(updated, store.load().url)
        assertEquals(updated, store.load().url)
    }
}
