package com.ufi_axis.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 手机自身的网络可用性监听。
 *
 * ## 两个信号必须分开（2026-09-21）
 *
 * 这里刻意暴露**两个**语义不同的 StateFlow，因为把它们混成一个正是「连不上设备却没有提示」
 * 的根因：
 *
 * - [hasNetwork]：手机**连着某个网络**（WiFi / 蜂窝 / 以太网），不要求这个网络能上外网。
 *   判断「手机压根没联网」只能看它。
 * - [hasInternet]：这个网络具备 `NET_CAPABILITY_INTERNET`。判断「手机能不能访问互联网」看它。
 *
 * 为什么不能用 [hasInternet] 代表「能不能连上设备」：随身WiFi 设备本身可能没有外网
 * （没插卡 / 欠费 / 无信号），此时手机连着设备热点，`hasInternet` 为 false，
 * 但**手机到设备的局域网是通的**，core 完全可达。反过来，手机连着一个有外网的
 * 公共 WiFi 时 `hasInternet` 为 true，而设备根本不在这个网里。
 *
 * 结论：**这两个信号都不能单独用来判断 core 是否可达**。core 可达性只有一个权威来源 ——
 * `HealthModule` 对 `/health` 的实际探活。本类的作用是给探活失败**归因**
 * （是手机没联网，还是联了网但摸不到设备）。
 */
class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _hasInternet = MutableStateFlow(false)

    /**
     * 手机当前网络是否具备互联网能力。
     *
     * 历史名字，保留是因为已有调用点（`DashboardModule.observeNetworkState`）依赖它做
     * 「断网→恢复」的重连触发。**不要**用它表达「后端未连接」。
     */
    val isOnline: StateFlow<Boolean> = _hasInternet

    /** 同 [isOnline]，语义更明确的别名。新代码用这个。 */
    val hasInternet: StateFlow<Boolean> = _hasInternet

    private val _hasNetwork = MutableStateFlow(false)

    /**
     * 手机是否连着**任何**网络（不要求能上外网）。
     *
     * 为 false 时可以确定地告诉用户「手机未连接网络」；为 true 而 `/health` 探不通，
     * 才是「连着网但摸不到设备」。
     */
    val hasNetwork: StateFlow<Boolean> = _hasNetwork

    /** 当前可用网络集合。onAvailable/onLost 是按 Network 逐个回调的，必须计数而不是布尔。 */
    private val availableNetworks = mutableSetOf<Network>()
    private val networksLock = Any()

    @Volatile private var internetCallbackRegistered = false
    @Volatile private var anyNetCallbackRegistered = false

    /** 只关心「有互联网能力的网络」，维护 [hasInternet]。 */
    private val internetCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _hasInternet.value = true
        }

        override fun onLost(network: Network) {
            _hasInternet.value = false
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _hasInternet.value =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    /**
     * 关心「任何已连接网络」，维护 [hasNetwork]。
     *
     * 请求里**不加** `NET_CAPABILITY_INTERNET` —— 加了就收不到「连着没外网的设备热点」
     * 这种网络的回调，而那恰恰是本项目最常见的形态。
     */
    private val anyNetCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(networksLock) { availableNetworks.add(network) }
            publishHasNetwork()
        }

        override fun onLost(network: Network) {
            synchronized(networksLock) { availableNetworks.remove(network) }
            publishHasNetwork()
        }
    }

    private fun publishHasNetwork() {
        _hasNetwork.value = synchronized(networksLock) { availableNetworks.isNotEmpty() }
    }

    fun startMonitoring() {
        // 先用同步查询播种一次：回调只在**变化时**触发，进程启动时若网络早已就绪则不会有回调，
        // 不播种的话首屏会有一段时间误判为「手机未联网」。
        seedFromCurrentState()

        if (!internetCallbackRegistered) {
            runCatching {
                connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    internetCallback
                )
                internetCallbackRegistered = true
            }
        }
        if (!anyNetCallbackRegistered) {
            runCatching {
                // 不加 capability 过滤，只按 transport 收口，才能覆盖「无外网的设备热点」
                connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .build(),
                    anyNetCallback
                )
                anyNetCallbackRegistered = true
            }
        }
    }

    private fun seedFromCurrentState() {
        runCatching {
            val active = connectivityManager.activeNetwork
            val caps = active?.let { connectivityManager.getNetworkCapabilities(it) }
            _hasInternet.value =
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            synchronized(networksLock) {
                availableNetworks.clear()
                if (active != null && caps != null) availableNetworks.add(active)
            }
            publishHasNetwork()
        }
    }

    fun stopMonitoring() {
        if (internetCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(internetCallback) }
            internetCallbackRegistered = false
        }
        if (anyNetCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(anyNetCallback) }
            anyNetCallbackRegistered = false
        }
        synchronized(networksLock) { availableNetworks.clear() }
        _hasNetwork.value = false
    }
}
