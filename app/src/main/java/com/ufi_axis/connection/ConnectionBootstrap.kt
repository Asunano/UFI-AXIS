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
 *   3. onUnauthorized 全局回调（凭据被**确证**吊销时清空本地凭据并标记未配对）
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
     * 注册「凭据被确证吊销」回调。
     *
     * **这里不做任何判定**：什么算吊销、要连续几次、多长跨度、传输失败如何豁免，全部由
     * `RetrofitClient` 那侧的鉴权策略决定（2026-09-08 事故：core 重启时的一次瞬时 401
     * 就把 token 清了，用户被迫重新输密码 —— 所以阈值只允许有一份，绝不在这里再判一次）。
     * 本函数只负责**执行**：在 UI 线程清空本机 token、标记未配对，再触发调用方的 [onUnauthorized]。
     *
     * **不删除 Keystore 密钥**：设备身份保持不变，重新配对时仍是同一台设备、复用原配对槽位；
     * 要换身份得走设置页的「重新配对 / 切换设备」（那里会 reset 密钥）。
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

    /**
     * 注册「任何业务请求传输层失败 → 立刻探活」的桥接（2026-09-21）。
     *
     * 为什么必须有这一条：原来「失败 → 探活 → 提示」只由 5 个 state 的 `errorMessage`
     * combine 触发，文件 / 媒体 / 下载 / 隧道 / 应用管理这些域的失败压根不在链上，
     * 于是设备离线时用户看到一堆行内红条却永远等不到那句「设备未连接」。
     * 现在从 OkHttp 拦截器直接出信号，**与是哪个域无关**。
     *
     * 回调跑在 OkHttp 的网络线程上，所以这里只做转发；去抖与协程调度都在
     * `HealthModule.notifyTransportFailure()` 里。
     */
    fun registerTransportFailureHandler(onTransportFailure: () -> Unit) {
        RetrofitClient.onTransportFailure = onTransportFailure
    }

    /** 解绑（Activity 销毁时调用，避免持有已死的 VM）。 */
    fun unregisterTransportFailureHandler() {
        RetrofitClient.onTransportFailure = null
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
