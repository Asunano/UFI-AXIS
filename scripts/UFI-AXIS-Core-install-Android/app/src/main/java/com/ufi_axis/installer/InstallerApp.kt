package com.ufi_axis.installer

import android.app.Application
import com.ufi_axis.installer.core.NotificationHelper
import com.ufi_axis.installer.state.InstallEngine

/**
 * 应用入口。
 *
 * 在这里做最小化的初始化：
 * - 把 ApplicationContext 交给引擎（引擎是单例，必须尽早绑定）
 * - 建通知渠道，避免首次安装时通知被系统丢弃
 *
 * 刻意不在这里做任何耗时操作，保证冷启动速度。
 */
class InstallerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        InstallEngine.attach(this)
        NotificationHelper.ensureChannel(this)
    }
}
