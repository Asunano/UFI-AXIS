package com.ufi_axis_core.api.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待批准请求的单元测试（2026-10-15）。
 *
 * 纯 JVM（不碰 Context）：待批准是短命状态（15 分钟 TTL），内存态即可，见类注释。
 */
class PairedDeviceApprovalStoreTest {

    private val store = PairedDeviceApprovalStore()

    @Test
    fun `请求生成六位码且字符集不含易混字符`() {
        val p = store.request("sid-1", "fp-1", "客厅电脑", "192.168.***.***")
        assertEquals(6, p.code.length)
        assertTrue(
            "码是要人眼从通知里读、再手输的，不能出现 I/O/0/1",
            p.code.all { it in PairedDeviceApprovalStore.CODE_ALPHABET }
        )
        assertFalse(p.code.contains('I') || p.code.contains('O') || p.code.contains('0') || p.code.contains('1'))
        assertTrue("刚创建应是 pending", p.approved.not() && p.rejected.not())
    }

    @Test
    fun `同一 sid 幂等 —— 刷新页面不换码也不新建`() {
        val first = store.request("sid-1", "fp-1", "客厅电脑", "192.168.***.***")
        val again = store.request("sid-1", "fp-1", "客厅电脑（改名）", "192.168.***.***")
        assertEquals("必须是同一条", first.sid, again.sid)
        assertEquals("码不能变（否则机主手上那个码就废了）", first.code, again.code)
        assertEquals("展示名应刷新", "客厅电脑（改名）", again.deviceName)
        assertEquals("列表里只有一条", 1, store.pendingList().size)
    }

    @Test
    fun `取消 pending 只删未落终态的请求`() {
        store.request("sid-pending", "fp-1", "待批准", "局域网")
        store.request("sid-approved", "fp-2", "已批准", "局域网")
        store.request("sid-rejected", "fp-3", "已拒绝", "局域网")
        assertTrue(store.approve("sid-approved"))
        assertTrue(store.reject("sid-rejected"))

        assertEquals("只应清掉 1 条仍在 pending 的", 1, store.cancelPending())
        assertTrue("App 列表必须为空", store.pendingList().isEmpty())
        assertEquals(
            "approved 是终态，请求方可能还要用它完成 confirm，不能删",
            PairedDeviceApprovalStore.STATUS_APPROVED,
            store.statusOf("sid-approved")
        )
        assertEquals(
            "rejected 也是终态",
            PairedDeviceApprovalStore.STATUS_REJECTED,
            store.statusOf("sid-rejected")
        )
        assertEquals(
            "被取消的请求对请求方表现为 expired",
            PairedDeviceApprovalStore.STATUS_EXPIRED,
            store.statusOf("sid-pending")
        )
        assertEquals("幂等：再取消一次返回 0", 0, store.cancelPending())
    }

    @Test
    fun `不同指纹复用同一个 sid 会换掉旧请求`() {
        val a = store.request("sid-1", "fp-a", "A", "局域网")
        val b = store.request("sid-1", "fp-b", "B", "局域网")
        assertNotEquals("换了指纹就不能复用旧批准（码必须换）", a.code, b.code)
        assertEquals("sid 是键：同一 sid 只保留最新那条", 1, store.pendingList().size)
        assertEquals("留下的是新指纹", "fp-b", store.pendingList().single().fingerprint)
    }

    @Test
    fun `App 点允许后状态变 approved`() {
        store.request("sid-1", "fp-1", "设备", "局域网")
        assertEquals(PairedDeviceApprovalStore.STATUS_PENDING, store.statusOf("sid-1"))
        assertTrue(store.approve("sid-1"))
        assertEquals(PairedDeviceApprovalStore.STATUS_APPROVED, store.statusOf("sid-1"))
    }

    @Test
    fun `App 点拒绝后状态变 rejected 且不能被码翻回来`() {
        store.request("sid-1", "fp-1", "设备", "局域网")
        assertTrue(store.reject("sid-1"))
        assertEquals(PairedDeviceApprovalStore.STATUS_REJECTED, store.statusOf("sid-1"))
        assertFalse("拒绝后再批准应无效", store.approve("sid-1"))
    }

    // ── 2026-10-21（审计 §A2）────────────────────────────────────────────────
    //
    // 拒绝是终态，但**不是这个会话的终态**。之前同一个 sid 重发请求会被幂等复用，
    // 于是请求方在 15 分钟 TTL 内永远读到 rejected、机主也无法再批准 —— 死胡同。
    // 现在「被拒」按新请求重建（新码 / 新时间，rejected 清掉），这两条守的就是它。

    @Test
    fun `被拒绝后重新请求同一个 sid 会重建为新请求`() {
        val first = store.request("sid-1", "fp-1", "设备", "局域网")
        store.reject("sid-1")

        val second = store.request("sid-1", "fp-1", "设备", "局域网")

        assertEquals("必须回到 pending（否则请求方永远读到 rejected）", PairedDeviceApprovalStore.STATUS_PENDING, store.statusOf("sid-1"))
        assertTrue("要能重新出现在待处理列表里，机主才有机会再批", store.pendingList().any { it.sid == "sid-1" })
        assertNotEquals("码必须换（旧的已经随拒绝作废）", first.code, second.code)
        assertTrue("新建的条目必须能再被批准", store.approve("sid-1"))
    }

    @Test
    fun `被拒绝后重建不会因为指纹不同而互串`() {
        store.request("sid-1", "fp-A", "A", "局域网")
        store.reject("sid-1")

        store.request("sid-1", "fp-B", "B", "局域网")

        assertEquals("换指纹也是新请求", "B", store.pendingList().single().deviceName)
        assertTrue("批准要落在新的那条上", store.approve("sid-1"))
    }

    @Test
    fun `配对码比较大小写不敏感`() {
        val p = store.request("sid-1", "fp-1", "设备", "局域网")
        assertTrue(
            "机主从通知里抄码时用小写非常正常（审计 §C5），不该静默失败",
            store.verifyCode("sid-1", p.code.lowercase())
        )
    }

    @Test
    fun `回传正确配对码等价于批准`() {
        val p = store.request("sid-1", "fp-1", "设备", "局域网")
        assertFalse("错误的码不该通过", store.verifyCode("sid-1", "AAAAAA"))
        assertFalse("空码不该通过", store.verifyCode("sid-1", null))
        assertTrue("正确的码应通过", store.verifyCode("sid-1", p.code))
        assertEquals(
            "码正确即视为已批准（Web 之后重试 confirm 时读到的就是 approved）",
            PairedDeviceApprovalStore.STATUS_APPROVED,
            store.statusOf("sid-1")
        )
    }

    @Test
    fun `未知 sid 的状态是 expired`() {
        assertEquals(PairedDeviceApprovalStore.STATUS_EXPIRED, store.statusOf("不存在的-sid"))
        assertNull(store.get("不存在的-sid"))
        assertFalse(store.approve("不存在的-sid"))
    }

    @Test
    fun `过期的请求查不到也不再占位`() {
        val shortLived = PairedDeviceApprovalStore(ttlMs = 30L)
        shortLived.request("sid-1", "fp-1", "设备", "局域网")
        assertNotNull(shortLived.get("sid-1"))
        Thread.sleep(60L)
        assertNull("过期后应查不到", shortLived.get("sid-1"))
        assertEquals(PairedDeviceApprovalStore.STATUS_EXPIRED, shortLived.statusOf("sid-1"))
        assertTrue("过期条目不该再出现在待批准列表里", shortLived.pendingList().isEmpty())
    }

    @Test
    fun `待批准表有上限 —— 满了先丢最旧的`() {
        val capped = PairedDeviceApprovalStore(maxPending = 3)
        repeat(5) { i -> capped.request("sid-$i", "fp-$i", "设备$i", "局域网") }
        val list = capped.pendingList()
        assertEquals("不该无界增长", 3, list.size)
        assertTrue("留下的应是最新的三条", list.map { it.sid }.containsAll(listOf("sid-2", "sid-3", "sid-4")))
    }

    // ══════════════ 限速（2026-10-15，方案 §6.4） ══════════════

    @Test
    fun `同一来源 IP 的新请求数有上限`() {
        val store = PairedDeviceApprovalStore()
        repeat(PairedDeviceApprovalStore.MAX_NEW_PER_IP) {
            assertTrue("第 ${it + 1} 条新请求应放行", store.allowNewRequest("192.168.1.9"))
        }
        assertFalse("超出上限应被拒", store.allowNewRequest("192.168.1.9"))
        assertTrue("另一个 IP 不受影响", store.allowNewRequest("192.168.1.10"))
    }

    @Test
    fun `限速窗口过期后重新放行`() {
        val store = PairedDeviceApprovalStore()
        repeat(PairedDeviceApprovalStore.MAX_NEW_PER_IP) { store.allowNewRequest("10.0.0.1") }
        assertFalse(store.allowNewRequest("10.0.0.1"))
        // 把"现在"推到窗口之外
        val future = System.currentTimeMillis() + PairedDeviceApprovalStore.RATE_WINDOW_MS + 1
        assertTrue("窗口滚过之后应重新放行", store.allowNewRequest("10.0.0.1", now = future))
    }

    @Test
    fun `外部通知有全局上限 —— 防陌生人靠不停发起请求刷屏机主`() {
        val store = PairedDeviceApprovalStore()
        repeat(PairedDeviceApprovalStore.MAX_NOTIFICATIONS_PER_WINDOW) {
            assertTrue("第 ${it + 1} 条通知应放行", store.allowNotification())
        }
        assertFalse("超出上限应静默（不再叫醒机主）", store.allowNotification())
    }

    // ══════════════ 已处理的条目必须退出待处理列表（2026-10-17 实测 bug） ══════════════

    /**
     * 现象：App 点「允许」后批准弹窗关不掉、还反复弹回来。
     * 根因：待处理列表把**已批准**的条目也列了出来，弹窗驱动的那份数据永远非空。
     *
     * 同时断言"条目本身没被删"：Web 端还要靠同一份 map 轮询到 `approved` 才能继续登录 ——
     * 过滤只该影响展示，不该影响状态查询。
     */
    @Test
    fun `已批准或已拒绝的请求不再出现在待处理列表里`() {
        store.request("sid-a", "fp-a", "A", "局域网")
        store.request("sid-b", "fp-b", "B", "局域网")
        store.request("sid-c", "fp-c", "C", "局域网")

        assertTrue(store.approve("sid-a"))
        assertTrue(store.reject("sid-b"))

        assertEquals(
            "只应剩下未处理的那一条（否则 App 弹窗关不掉）",
            listOf("sid-c"),
            store.pendingList().map { it.sid }
        )
        // 状态查询仍要能读到 —— Web 端靠它继续登录
        assertEquals(PairedDeviceApprovalStore.STATUS_APPROVED, store.statusOf("sid-a"))
        assertEquals(PairedDeviceApprovalStore.STATUS_REJECTED, store.statusOf("sid-b"))
    }

    @Test
    fun `回传正确配对码后该条目也退出待处理列表`() {
        val p = store.request("sid-1", "fp-1", "设备", "局域网")
        assertTrue(store.verifyCode("sid-1", p.code))
        assertTrue("码正确即视为已处理，弹窗应消失", store.pendingList().isEmpty())
        assertEquals(PairedDeviceApprovalStore.STATUS_APPROVED, store.statusOf("sid-1"))
    }
}
