package com.ufi_axis_core.api.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.controller.notify.WebhookChannel
import com.ufi_axis_core.controller.notify.WebhookConfig
import com.ufi_axis_core.controller.notify.WebhookConfigStore
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
 * [WebhookChannel] 的**渠道级契约**：四个常量 + `accepts` + `isConfigured`。
 *
 * 为什么在 `:core:api` 而不是 `:core:controller`：这几条必须拿一个**真实例**来问，
 * 而渠道要 `WebhookConfigStore`（`Context` → SharedPreferences）—— `:core:controller`
 * 的单测源集只有 junit，构造不出 `Context`。这个模块已经有 Robolectric（同 `SmsRuleRoutesTest`），
 * 所以放这里，**不给生产代码加参数注入/接口抽象**去迎合测试。
 * 纯判定（渲染 / 注入防线 / 状态码与异常分类）的用例在 `:core:controller` 的 `WebhookDeliveryTest`。
 *
 * 这四个常量各自的语义都有真实后果，改掉任何一个都不会报错、只会静默走偏：
 * - `respectsMasterGate = false` → 用户关了总开关照样往群机器人里发；
 * - `hasDeliveryConfirmation = false` → `TrafficAutoOffGuard` 认不了 Webhook 的通报，
 *   只配了 Bark 的用户永远等不到自动关网；
 * - `recordsHistory = false` → 投递记录里再也查不到 webhook 那条；
 * - `id` 变了 → `NotifyEvent.channels` 的限定集合与 `mail_send_records.channel` 列对不上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebhookChannelContractTest {

    private lateinit var store: WebhookConfigStore
    private lateinit var channel: WebhookChannel

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        store = WebhookConfigStore(context)
        channel = WebhookChannel(store)
    }

    /** 一次性把配置写进真 prefs（`accepts` / `isConfigured` 都是从那里读的）。 */
    private fun save(
        enabled: Boolean = true,
        url: String = "https://api.day.app/abcdef",
        scenes: Set<String> = emptySet()
    ) = store.save(WebhookConfig(enabled = enabled, url = url, scenes = scenes))

    // ══════════ 渠道级常量 ══════════

    /** 渠道 id 是 `NotifyEvent.channels` 与投递记录 `channel` 列共用的那个字符串。 */
    @Test
    fun `渠道 id 是 webhook`() {
        assertEquals("webhook", channel.id)
        assertEquals(WebhookChannel.ID, channel.id)
    }

    /** 受总闸约束：请求打出去就收不回来，用户关掉总开关就是不想收。 */
    @Test
    fun `webhook 受全局总闸约束`() {
        assertTrue(channel.respectsMasterGate)
    }

    /** 有送达确认：2xx 是目标服务端收下了 —— 自动关网的"通报到位"判定认它。 */
    @Test
    fun `webhook 有送达确认`() {
        assertTrue(channel.hasDeliveryConfirmation)
    }

    /** 留投递记录：HTTP 有状态码，"这条投出去了没有"是有答案的。 */
    @Test
    fun `webhook 留投递记录`() {
        assertTrue(channel.recordsHistory)
    }

    // ══════════ 场景勾选 ══════════

    /** 勾了才投。真源只有 `WebhookConfig.scenes` 一处。 */
    @Test
    fun `accepts 只认勾选集里的场景`() {
        save(scenes = setOf(NotifyScenes.ALERT, NotifyScenes.SMS))

        assertTrue(channel.accepts(NotifyScenes.ALERT))
        assertTrue(channel.accepts(NotifyScenes.SMS))
        assertFalse(channel.accepts(NotifyScenes.DOWNLOAD))
        assertFalse(channel.accepts(NotifyScenes.TRAFFIC_80))
        assertFalse("没登记的场景 id 不能被放行", channel.accepts("不存在的场景"))
    }

    /**
     * 空勾选集 = 一个场景都不投（默认就是空集）。
     *
     * 这里反过来防的是"空集当成全选"那种便利写法 —— 那会让用户一打开 Webhook 开关
     * 就被所有场景轰炸，而他一个勾都没打。
     */
    @Test
    fun `空勾选集时任何场景都不投`() {
        save(scenes = emptySet())

        for (scene in NotifyScenes.ALL) {
            assertFalse("场景 $scene 在空勾选集下被放行了", channel.accepts(scene))
        }
    }

    // ══════════ 配置齐不齐（经过真 prefs 一圈） ══════════

    @Test
    fun `开关关闭时渠道判定未配置齐全`() {
        save(enabled = false)
        assertFalse(channel.isConfigured())
    }

    @Test
    fun `URL 为空或非 http 时渠道判定未配置齐全`() {
        save(url = "")
        assertFalse("URL 空", channel.isConfigured())

        save(url = "ftp://example.com/hook")
        assertFalse("ftp", channel.isConfigured())

        save(url = "javascript:alert(1)")
        assertFalse("javascript", channel.isConfigured())

        save(url = "api.day.app/abcdef")
        assertFalse("没有 scheme", channel.isConfigured())
    }

    /** 预设 URL 里的 `<占位>` 没替换掉也算没配完（否则会打到一个不存在的 device key 上）。 */
    @Test
    fun `URL 还留着预设占位时渠道判定未配置齐全`() {
        save(url = "https://api.day.app/<device_key>")
        assertFalse(channel.isConfigured())
    }

    @Test
    fun `启用且 URL 是 http 或 https 时渠道判定配置齐全`() {
        save(url = "https://api.day.app/abcdef")
        assertTrue("https", channel.isConfigured())

        save(url = "http://www.pushplus.plus/send")
        assertTrue("http", channel.isConfigured())
    }
}
