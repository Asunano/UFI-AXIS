package com.ufi_axis.installer.core

import com.ufi_axis.adbcore.AdbClient
import com.ufi_axis.installer.logging.InstallLogger

/**
 * 启动目标应用。对齐 bat 脚本的两段式策略：
 *
 * 1. `cmd package resolve-activity --brief <pkg>` 拿到入口组件
 * 2. 拿到就 `am start -n <component>`
 * 3. 拿不到就回退 `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`
 *
 * `am start` 的返回值经常是 "Starting: Intent {...}" 并且 exit code 为 0，
 * 单看输出无法 100% 判定，所以最终是否启动成功交给之后的健康检查定论。
 */
object AppLauncher {

    data class LaunchResult(
        val method: AdbClient.LaunchMethod,
        val component: String?,
        /** 是否解析出了入口组件 */
        val resolved: Boolean
    )

    /**
     * 启动应用。
     *
     * @param onResolved 解析出组件时的回调（用于日志展示）
     */
    fun launch(
        client: AdbClient,
        pkg: String,
        onResolved: ((String) -> Unit)? = null
    ): LaunchResult {
        val component = try {
            client.resolveLauncherActivity(pkg)
        } catch (e: Exception) {
            InstallLogger.warn("解析启动入口失败：${e.message ?: e.javaClass.simpleName}")
            null
        }

        if (component != null) {
            onResolved?.invoke(component)
            InstallLogger.info("启动入口：$component")
        } else {
            InstallLogger.warn("未能解析启动入口，回退 monkey 方式")
        }

        val method = client.launchApp(pkg)
        InstallLogger.info(
            when (method) {
                AdbClient.LaunchMethod.AM_START -> "启动指令已发送（am start）"
                AdbClient.LaunchMethod.MONKEY -> "启动指令已发送（monkey）"
            }
        )

        return LaunchResult(
            method = method,
            component = component,
            resolved = component != null
        )
    }
}
