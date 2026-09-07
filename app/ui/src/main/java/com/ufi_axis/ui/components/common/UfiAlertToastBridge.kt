// 告警 → **普通 Toast** 的桥接层（2026-08-30）。
//
// 历史：告警原来走一套自己的「应用内 banner」——UfiAlertBanner / UfiAlertBannerBridge /
// UfiAlertBannerOverlay 三个文件，约 400 行手写 View：自己 addView 到 decorView、自己做
// 入场动画、自己画 × 按钮和上滑关闭手势、自己维护静音窗口。而 UfiToastOverlay 早就把
// 「脱离 Compose 视图树的顶部浮层」这件事做完了，两套实现只是长得不一样，维护要改两处。
//
// 现在告警直接复用普通 Toast（同一个 UfiToastOverlay、同一套配色映射
// [toUfiToastColors]、同一套下落/退出动画），banner 三件套已删除。
//
// 随之消失的两个 banner 专属能力（都依附在已删掉的手势上，没有别的入口）：
//   - ×/上滑关闭：普通 Toast 没有关闭控件，到时自动收；
//   - 上滑后的「静音 N 分钟」窗口（alert_banner_muted_until）与滑动关闭开关
//     （alert_banner_swipe_close）：两个 pref 键已无写入方，一并移除。
// 要压制告警弹窗仍走通知配置里的 `alert_enabled`（真源在 core），而不是本地静音窗口。
package com.ufi_axis.ui.components.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis_core.alert.AlertBus
import com.ufi_axis_core.alert.AlertBusItem

/**
 * 告警 → Toast 的映射规则（纯函数，便于单测覆盖；见 UfiAlertToastRulesTest）。
 */
object UfiAlertToastRules {

    /** critical 是唯一"必须让人看见"的等级，停留更久。 */
    fun isCritical(level: String): Boolean = level.equals("critical", ignoreCase = true)

    /** 等级 → Toast 语义类型（决定图标与主色）。 */
    fun toastType(level: String): ToastType = when {
        isCritical(level) -> ToastType.ERROR
        level.equals("warning", ignoreCase = true) -> ToastType.WARNING
        else -> ToastType.INFO
    }

    /**
     * 停留时长。critical 6s，其余 3s（与普通 Toast 默认一致）。
     *
     * 原 banner 对 critical 是"永不自动收，必须手动关"。普通 Toast 没有关闭控件，
     * 常驻等于永久遮挡顶部，所以改成"更久但仍自动收"——严重告警同时还有系统通知与
     * 事件中心两条不会消失的记录，不依赖这一个浮层。
     */
    fun durationMs(level: String): Long = if (isCritical(level)) 6000L else 3000L

    /** 告警类型 → 中文标签（与 NotificationCenter.alertTypeLabel 对齐，避免跨模块依赖）。 */
    fun alertTypeLabel(type: String): String = when (type) {
        "cpu_temp" -> "CPU 温度"
        "battery" -> "电池"
        "signal" -> "信号"
        "traffic" -> "流量"
        "connectivity" -> "连接"
        "disk" -> "存储"
        else -> type
    }
}

/**
 * 挂在 MainActivity 顶层，把 [AlertBus] 上的告警投给普通 Toast。
 *
 * 本 Composable 不渲染任何 UI（与 [UfiToastHost] 同套路），只做 collect + 转交。
 *
 * @param onAck Toast 收起时回调，用于按类型批量已读（消除 N+1，见 DashboardModule.ackAlertByBanner）。
 *   与原 banner 的差别：banner 只在用户点 × / 上滑时 ack，自动收起不 ack；普通 Toast 没有
 *   手动关闭入口，因此改为"弹完即视为已看到"。
 */
@Composable
fun UfiAlertToastBridge(onAck: (AlertBusItem) -> Unit) {
    val palette = LocalResolvedPalette.current
    val context = LocalContext.current
    val colors = remember(palette) { palette.toUfiToastColors() }

    LaunchedEffect(Unit) {
        AlertBus.events.collect { item ->
            UfiToastOverlay.show(
                context = context,
                type = UfiAlertToastRules.toastType(item.level),
                title = UfiAlertToastRules.alertTypeLabel(item.type),
                subtitle = item.message,
                durationMs = UfiAlertToastRules.durationMs(item.level),
                colors = colors,
                onDismiss = { onAck(item) }
            )
        }
    }
}
