package com.ufi_axis.data.notification

import android.content.Context
import java.util.concurrent.atomic.AtomicReference

/**
 * 跨进程/跨模块后台动作辅助类（2026-09-13）。
 *
 * ## 为什么存在
 * `:app:data` 模块无法反向依赖 `:app` 模块，因此 [NotificationCenter] 无法直接看到
 * [com.ufi_axis.notification.UfiNotifyAccessibilityService]。
 *
 * 通过本助手类，`:app` 模块可以在无障碍服务连接时将其实例（作为 Context）注册进来，
 * 供 `app:data` 内的组件在后台执行受限操作（如剪贴板写入）。
 */
object BackgroundActionHelper {
    private val contextRef = AtomicReference<Context?>(null)

    /** 注册活跃的无障碍服务 Context。 */
    fun registerAccessibilityContext(context: Context) {
        contextRef.set(context)
    }

    /** 撤销无障碍服务 Context。 */
    fun unregisterAccessibilityContext() {
        contextRef.set(null)
    }

    /** 获取当前可用的后台特权 Context（如果有）。 */
    fun getPrivilegedContext(): Context? = contextRef.get()
}
