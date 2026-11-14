package com.ufi_axis.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ufiNavigateOnce] 两道闸的纯 JVM 回归测试（2026-11-03）。
 *
 * 背景：以前这道闸只有 `feature-media` 在用，其余入口直接 `navigate()`，于是
 * "两根手指同时点两个不同入口"能真的叠两层页面（返回要按两次）。现在所有用户触发的
 * push 都必须走 [ufiNavigateOnce]，本测试守住判据本身；"有没有人漏接"由
 * [NavPushGuardTest] 扫源码守住。
 */
class UfiNavGuardTest {

    private val now = 1_000_000L

    @Test
    fun firstNavigation_isAdmitted() {
        // 进程内首次：currentDestination 还是 null，上次放行时刻是初值 0。
        assertTrue(
            ufiNavAdmission(
                currentRoute = null,
                route = Routes.DETAIL_ABOUT,
                lastAcceptedAtMs = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun sameDestination_isRejectedEvenAfterWindow() {
        // 连点同一行：第一次已经把当前目的地推成目标，第二次没有意义（push 两层）。
        assertFalse(
            ufiNavAdmission(
                currentRoute = Routes.DETAIL_ABOUT,
                route = Routes.DETAIL_ABOUT,
                lastAcceptedAtMs = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun destinationTemplate_matchesConcreteRoute() {
        // currentDestination.route 是模板（{path}），点击传的是填好值的具体串。
        assertFalse(
            ufiNavAdmission(
                currentRoute = "media/audio?path={path}",
                route = "media/audio?path=%2Fsdcard%2Fa.mp3",
                lastAcceptedAtMs = 0L,
                nowMs = now,
            )
        )
        // 同一目的地、不同参数也只该进一次（连点两首歌不允许叠两个播放页）。
        assertFalse(
            ufiNavAdmission(
                currentRoute = "media/audio?path={path}",
                route = "media/audio?path=%2Fsdcard%2Fb.mp3",
                lastAcceptedAtMs = 0L,
                nowMs = now,
            )
        )
    }

    @Test
    fun differentDestinations_withinWindow_areRejected() {
        // 核心场景：同一帧内两个不同入口都触发了 navigate，第二次必须被丢掉。
        assertFalse(
            ufiNavAdmission(
                currentRoute = Routes.MAIN,
                route = Routes.DETAIL_ABOUT,
                lastAcceptedAtMs = now - (UFI_NAV_DEBOUNCE_MS - 1),
                nowMs = now,
            )
        )
    }

    @Test
    fun differentDestinations_atWindowBoundary_areAdmitted() {
        // 边界取 ">="：恰好 350ms 时放行，避免把正常操作节奏也吃掉。
        assertTrue(
            ufiNavAdmission(
                currentRoute = Routes.MAIN,
                route = Routes.DETAIL_ABOUT,
                lastAcceptedAtMs = now - UFI_NAV_DEBOUNCE_MS,
                nowMs = now,
            )
        )
    }

    @Test
    fun unknownCurrentDestination_stillHonoursWindow() {
        // current 为 null 只免掉"目的地去重"，不豁免时间窗：刚导航完又瞬间来一次
        // （例如深链解析尚未落位的极端时序）仍然按连击处理。
        assertFalse(
            ufiNavAdmission(
                currentRoute = null,
                route = Routes.DETAIL_ABOUT,
                lastAcceptedAtMs = now - 1,
                nowMs = now,
            )
        )
    }
}
