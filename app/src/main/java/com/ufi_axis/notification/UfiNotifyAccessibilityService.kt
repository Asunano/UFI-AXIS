package com.ufi_axis.notification

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ufi_axis.util.DebugLog

/**
 * 无障碍保活服务（独立进程架构 v2 Phase 1/3）。
 *
 * 用途：系统级保活 —— 注册后系统将应用归类为"特殊服务"，国产 ROM（MIUI/EMUI/HyperOS）
 * 后台管理通常豁免。需用户在「系统设置 → 无障碍 → UFI-AXIS 通知守护」手动开启（默认关）。
 *
 * 实现要点：
 * - [onServiceConnected] 仅记录日志——**不再启动 [NotifyService] FGS**（2026-08-10 修复：
 *   Android 12+ 从无障碍回调启动前台服务会被系统后台限制拒绝，反复抛
 *   ForegroundServiceStartNotAllowedException；且无障碍服务自身已是系统级保活，
 *   FGS 属冗余叠加，去掉更稳）；
 * - [onAccessibilityEvent] 空实现：不读取、不处理任何窗口内容
 *   （Manifest 中 canRetrieveWindowContent=false，隐私最小化）；
 * - 运行在 `:ufi_notify` 进程（Manifest 声明）。
 */
class UfiNotifyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 2026-08-10：无障碍服务已注册即获得系统级保活（国产 ROM 归类"特殊服务"豁免后台管理），
        // 无需再叠加前台服务（Android 12+ 后台启动 FGS 受限会抛异常）。
        DebugLog.i(TAG, "UfiNotifyAccessibilityService connected（系统级保活已生效）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 保活用途：不读取/不处理任何窗口内容（canRetrieveWindowContent=false）
    }

    override fun onInterrupt() {
        // 空实现：无障碍服务被系统中断时无需处理
    }

    private companion object {
        const val TAG = "UfiNotifyAccessibility"
    }
}
