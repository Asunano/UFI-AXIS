package com.ufi_axis.connection

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.NetworkMonitor
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 连接引导集中层（F22）。
 *
 * 将 [com.ufi_axis.MainActivity] 中散落的四类连接职责集中到此处，降低 :app 入口与网络
 * 基础设施的耦合，且 **不改变任何运行行为**：
 *   1. Retrofit 重建（凭据 / 服务器配置变更后）
 *   2. WS url 改写（http(s):// -> ws(s)://，纯函数）
 *   3. onUnauthorized 全局回调（业务 /api 路径返回 401 时清空本地凭据并标记未配对）
 *   4. 手写 MainViewModel factory
 *
 * 调用方（MainActivity）仅做装配，不在入口散落网络细节。
 */
object ConnectionBootstrap {

    /** 将 http(s):// baseUrl 改写为 ws(s):// WebSocket 地址（纯函数，无副作用）。 */
    fun rewriteWsUrl(baseUrl: String): String =
        baseUrl.replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://")
            .trimEnd('/')

    /**
     * 注册全局鉴权失败（401/444）回调。
     * 在 UI 线程（runOnUiThread）清空本机设备 token、标记未配对，再触发调用方提供的
     * [onUnauthorized]。**不删除 Keystore 密钥**：设备身份保持不变，重新配对时仍是同一台
     * 设备、复用原配对槽位；要换身份得走设置页的「重新配对 / 切换设备」（那里会 reset 密钥）。
     */
    fun registerUnauthorizedHandler(
        context: Context,
        prefs: AppPreferences,
        onUnauthorized: () -> Unit
    ) {
        RetrofitClient.onUnauthorized = {
            val runnable = Runnable {
                prefs.token = ""
                prefs.isSetupComplete = false
                onUnauthorized()
            }
            if (context is ComponentActivity) context.runOnUiThread(runnable) else runnable.run()
        }
    }

    /** 重建 Retrofit 实例（凭据 / 服务器配置变更后）。 */
    fun recreateRetrofit(prefs: AppPreferences) {
        RetrofitClient.recreate(prefs)
    }

    /** 构造 MainViewModel 的 factory（原 MainActivity 手写 VM factory 提取）。 */
    fun mainViewModelFactory(
        api: UfiAxisApi,
        webSocketRepository: WebSocketRepository,
        networkMonitor: NetworkMonitor,
        appContext: Context
    ): ViewModelProvider.Factory =
        MainViewModel.provideFactory(api, webSocketRepository, networkMonitor, appContext)
}
