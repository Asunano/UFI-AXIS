package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.model.WebSocketMessage
import com.ufi_axis.util.DebugLog
import com.ufi_axis_core.contract.WsChannel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 网络页实时数据的**契约**测试（2026-11-07）。
 *
 * ## 被守护的三条链路
 * 1. `signal`：网络页 hero 卡从「切频段后一直无服务、必须重启」变成自动跟手；
 * 2. `connection`：`ppp_status` 与 signal 同源（core 同一次 goform 查询里已有），
 *    把「已连接/未连接」从 10s 轮询降到 3~4.5s 推送；
 * 3. 低频字段（networkStatus / wifiClients）的 10s 轻量轮询 + 详情子资源的新鲜度闸门：
 *    该省请求的地方省，该强制回读的地方（写后 / data_changed）不被闸门吃掉。
 *
 * ## 为什么用纯函数 + 源码断言
 * 完整行为验证要构造 `NetworkModule`（需要 Android `Context`，本机 JDK 25 下 Robolectric
 * 无法启动），成本远高于收益；而回归形态非常具体（删掉订阅、改错频道、忘传流、闸门丢了
 * force、轮询漏了生命周期门控），正则 + 纯函数就能钉死并附上原因。
 */
class NetworkModuleSignalPushTest {

    // ── 纯函数：signal / connection 的解码 ──────────────────────────────────

    @Test
    fun signalMessage_isDecodedIntoSignalInfo() {
        val signal = signalFromWsMessage(
            WebSocketMessage(
                type = WsChannel.SIGNAL,
                data = buildJsonObject {
                    put("rsrp", -97)
                    put("sinr", 12)
                    put("rat", "5G")
                    put("operator", "中国移动")
                    put("band_label", "N78")
                },
            )
        )

        assertEquals("signal 帧必须被接受", -97, signal?.rsrp)
        assertEquals(12, signal?.sinr)
        assertEquals("5G", signal?.rat)
        assertEquals("中国移动", signal?.operator)
        assertEquals("N78", signal?.bandLabelDisplay)
    }

    @Test
    fun nonSignalMessage_isIgnored() {
        assertNull(
            "data_changed 等其它频道不能冒充信号快照",
            signalFromWsMessage(
                WebSocketMessage(
                    type = "data_changed",
                    data = buildJsonObject { put("changed", "network:band-status") },
                )
            )
        )
    }

    @Test
    fun malformedPayload_returnsNullWithoutThrowing() {
        // 解析失败会走 DebugLog.parseError → android.util.Log，而本地单测里的 android.jar
        // 是抛异常的空桩。关掉 app 日志开关让 shouldEmit 提前返回：本用例断言的是
        // 「解析失败不把异常抛给调用方」，不是日志通道本身。
        val previousAppLog = DebugLog.appEnabled
        DebugLog.appEnabled = false
        try {
            assertNull(
                "坏帧按「本次不更新」处理，不能抛异常打断整条收集协程",
                signalFromWsMessage(
                    WebSocketMessage(type = WsChannel.SIGNAL, data = JsonPrimitive("not-an-object"))
                )
            )
        } finally {
            DebugLog.appEnabled = previousAppLog
        }
    }

    @Test
    fun missingPayload_returnsNull() {
        assertNull(signalFromWsMessage(WebSocketMessage(type = WsChannel.SIGNAL, data = null)))
    }

    @Test
    fun connectionMessage_yieldsPppStatus() {
        assertEquals(
            "connected",
            pppStatusFromWsMessage(
                WebSocketMessage(
                    type = WsChannel.CONNECTION,
                    data = buildJsonObject { put("ppp_status", "connected") },
                )
            )
        )
        // 空串 = 这轮没读到，必须返回 null（调用方保留上一帧），不能当成 disconnected
        assertNull(
            pppStatusFromWsMessage(
                WebSocketMessage(
                    type = WsChannel.CONNECTION,
                    data = buildJsonObject { put("ppp_status", "") },
                )
            )
        )
        assertNull(
            "非 connection 频道不能冒充连接态",
            pppStatusFromWsMessage(
                WebSocketMessage(
                    type = WsChannel.SIGNAL,
                    data = buildJsonObject { put("ppp_status", "connected") },
                )
            )
        )
        assertNull(
            pppStatusFromWsMessage(
                WebSocketMessage(type = WsChannel.CONNECTION, data = JsonPrimitive("not-an-object"))
            )
        )
    }

    // ── 源码级护栏：signal / connection 订阅必须真的接在 MainViewModel 上 ─────

    @Test
    fun wsSignalSubscription_mustStayWired() {
        val network = executableCode(
            source("src/main/java/com/ufi_axis/viewmodel/viewmodel/module/NetworkModule.kt")
        )
        val mainViewModel = executableCode(
            source("src/main/java/com/ufi_axis/viewmodel/viewmodel/MainViewModel.kt")
        )
        val dashboard = executableCode(
            source("src/main/java/com/ufi_axis/viewmodel/viewmodel/module/DashboardModule.kt")
        )
        val signalParser = executableCode(
            source("src/main/java/com/ufi_axis/viewmodel/viewmodel/module/RealtimeSignalParser.kt")
        )
        val connectionParser = executableCode(
            source("src/main/java/com/ufi_axis/viewmodel/viewmodel/module/RealtimeConnectionParser.kt")
        )
        val coreNetworkRoutes = executableCode(
            source("core/api/src/main/java/com/ufi_axis_core/api/routes/NetworkRoutes.kt")
        )

        assertTrue(
            "NetworkModule 必须持有 WS 消息流（构造参数 wsMessages）",
            network.contains("wsMessages: Flow<WebSocketMessage>")
        )
        assertTrue(
            "NetworkModule 必须订阅 wsMessages",
            network.contains("wsMessages.collect")
        )
        assertTrue(
            "init 里必须启动 collectRealtimeFromWs()，否则订阅永远不会跑",
            network.contains("collectRealtimeFromWs()")
        )
        assertTrue(
            "signal 帧必须走共享解码函数，不能各模块各写一份 decode",
            network.contains("signalFromWsMessage(message)") &&
                dashboard.contains("signalFromWsMessage(message)")
        )
        assertTrue(
            "signal / connection 频道名必须走 contract 常量，不能写裸字面量",
            signalParser.contains("WsChannel.SIGNAL") &&
                connectionParser.contains("WsChannel.CONNECTION")
        )
        assertTrue(
            "共享解码函数必须仍能被单测直接调用（纯函数、无 Context）",
            signalParser.contains("internal fun signalFromWsMessage(message: WebSocketMessage): SignalInfo?") &&
                connectionParser.contains("internal fun pppStatusFromWsMessage(message: WebSocketMessage): String?")
        )
        assertTrue(
            "MainViewModel 必须把 WebSocketRepository.messages 传给 NetworkModule",
            Regex("""NetworkModule\([\s\S]{0,300}?webSocketRepository\.messages""")
                .containsMatchIn(mainViewModel)
        )
        assertTrue(
            "DashboardModule 也必须消费 connection（走共享解码）—— 仪表盘 hero 卡的连接态要靠它",
            dashboard.contains("WsChannel.CONNECTION -> pppStatusFromWsMessage(message)")
        )
        assertTrue(
            "NetworkModule 合成器必须同时处理 signal 与 connection",
            network.contains("WsChannel.SIGNAL -> signalFromWsMessage(message)") &&
                network.contains("WsChannel.CONNECTION -> pppStatusFromWsMessage(message)")
        )
        assertTrue(
            "锁频段 / 锁基站 / 解锁基站收敛后都要补一次静默轻量回读（silent = true），" +
                "否则操作结束那一刻的 hero 卡只能等下一次推送；而带错误上报的回读会在设备重启网络栈期间误报",
            Regex("""refreshNetworkLight\(silent\s*=\s*true\)""").findAll(network).count() >= 3
        )
        assertTrue(
            "network:cell-info 是低频率事件，light 刷新不包含 cellInfo；必须补一次 loadCellInfo（force 绕过 20s 窗口）",
            Regex("""network:cell-info[\s\S]{0,500}?loadCellInfo\(silent\s*=\s*true,\s*force\s*=\s*true\)""")
                .containsMatchIn(network)
        )
        assertTrue(
            "invalidateNetwork() 的通配 key `network:*` 必须被消费，否则具体 key 没命中缓存时外部变更完全静默",
            network.contains("changedType == \"network:*\"")
        )
        assertTrue(
            "飞行模式路由必须发失效事件（invalidateNetwork），否则另一端不会回读 ppp/network_type",
            Regex("""post\("/airplane"\)[\s\S]{0,900}?invalidateNetwork\(\)""")
                .containsMatchIn(coreNetworkRoutes)
        )
        val refreshNetworkBlock = network
            .substringAfter("fun refreshNetwork(silent: Boolean = false)")
            .substringBefore("fun refreshNetworkLive")
        assertTrue(
            "refreshNetwork 必须复用 refreshWifi 的 10s 新鲜度戳：否则从仪表盘切到网络页时，" +
                "刚刷过的 WiFi 设置 + 客户端列表还会再拉一遍（2 个白付的请求）",
            refreshNetworkBlock.contains("isForegroundDataFresh(wifiSuccessElapsed") &&
                refreshNetworkBlock.contains("if (wifiFresh) null else async")
        )
        assertTrue(
            "同一次写操作会 invalidate 多个 network key；light 刷新必须在途合并，" +
                "否则一条事件串会重复打 3 个请求",
            network.contains("lightRefreshJob?.isActive == true")
        )
    }

    // ── 源码级护栏：10s 轮询 + 详情子资源新鲜度闸门 ──────────────────────────

    @Test
    fun livePollAndDetailFreshness_mustStayWired() {
        val network = executableCode(
            source("src/main/java/com/ufi_axis/viewmodel/viewmodel/module/NetworkModule.kt")
        )
        val networkScreen = executableCode(
            source("app/feature-network/src/main/java/com/ufi_axis/ui/screens/NetworkScreen.kt")
        )
        val dataScheduler = executableCode(
            source("core/scheduler/src/main/java/com/ufi_axis_core/core/scheduler/DataScheduler.kt")
        )
        val contract = executableCode(
            source("core/contract/src/main/kotlin/com/ufi_axis_core/contract/Enums.kt")
        )

        assertTrue(
            "core 必须真的广播 connection 频道（不能只声明不上报）",
            dataScheduler.contains("broadcast(\"connection\"") &&
                dataScheduler.contains("DeviceFields.Connection.PPP_STATUS")
        )
        assertTrue(
            "contract 的 WsChannel.ALL 必须包含 CONNECTION，否则 app/web 的 UI_TOPICS 订阅不到",
            contract.contains("const val CONNECTION = \"connection\"") &&
                Regex("""val ALL: List<String> = listOf\([\s\S]{0,300}?CONNECTION""")
                    .containsMatchIn(contract)
        )
        assertTrue(
            "NetworkModule.refreshNetworkLive 必须存在，且常态只拉 status + clients 两个请求",
            network.contains("suspend fun refreshNetworkLive(includeSignal: Boolean = false)") &&
                network.contains("api.getNetworkStatus()") &&
                network.contains("api.getWifiClients()") &&
                network.contains("api.getSignalInfo()")
        )
        assertTrue(
            "网络页必须有 10s 轻量轮询，且用 pageForeground + lifecycleResumed 双门控" +
                "（否则二级页背后的 Tab 会空跑）",
            networkScreen.contains("FOREGROUND_REFRESH_INTERVAL_MS") &&
                networkScreen.contains("refreshNetworkLive(") &&
                networkScreen.contains("lifecycleResumed") &&
                networkScreen.contains("LifecycleEventObserver")
        )
        assertTrue(
            "四个详情子资源 loader 必须带 force 参数 + 30s（cellInfo 20s）新鲜度窗口",
            listOf(
                "fun loadBandStatus(silent: Boolean = false, force: Boolean = false)",
                "fun loadCellInfo(silent: Boolean = false, force: Boolean = false)",
                "fun loadDeviceSettings(silent: Boolean = false, force: Boolean = false)",
                "fun loadLanSettings(silent: Boolean = false, force: Boolean = false)",
            ).all { network.contains(it) } &&
                network.contains("bandStatusSuccessElapsed") &&
                network.contains("cellInfoSuccessElapsed") &&
                network.contains("deviceSettingsSuccessElapsed") &&
                network.contains("lanSettingsSuccessElapsed") &&
                network.contains("CELL_INFO_FRESH_MS")
        )
        assertTrue(
            "resetFreshness 必须把四个新戳一起清零，否则换设备后仍会复用上一台设备的详情数据",
            Regex("""fun resetFreshness\(\)[\s\S]{0,600}?bandStatusSuccessElapsed = 0L""")
                .containsMatchIn(network) &&
                Regex("""fun resetFreshness\(\)[\s\S]{0,600}?cellInfoSuccessElapsed = 0L""")
                    .containsMatchIn(network) &&
                Regex("""fun resetFreshness\(\)[\s\S]{0,600}?deviceSettingsSuccessElapsed = 0L""")
                    .containsMatchIn(network) &&
                Regex("""fun resetFreshness\(\)[\s\S]{0,600}?lanSettingsSuccessElapsed = 0L""")
                    .containsMatchIn(network)
        )
        assertTrue(
            "data_changed 的写事件必须 force 回读，不能被 30s/20s 闸门吃掉",
            network.contains("changedType == \"device:lan\" -> loadLanSettings(force = true)") &&
                network.contains("changedType == \"device:settings\" -> loadDeviceSettings(force = true)")
        )
        // 手动刷新按钮也必须 force：用户点「刷新」时不能因为窗口内数据“还新鲜”而无反应
        val bandLockScreen = executableCode(
            source("app/feature-network/src/main/java/com/ufi_axis/ui/screens/BandLockScreen.kt")
        )
        val cellLockDialog = executableCode(
            source("app/feature-network/src/main/java/com/ufi_axis/ui/screens/CellLockDialog.kt")
        )
        assertTrue(
            "BandLockScreen / CellLockDialog 的手动刷新必须 force = true",
            bandLockScreen.contains("loadBandStatus(force = true)") &&
                cellLockDialog.contains("loadCellInfo(force = true)")
        )
    }

    // ── 源码定位辅助（与 ForegroundRefreshFreshnessTest 同一手法）──────────────

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/viewmodel/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "定位不到源文件 '$relative'（起点：${File(".").absolutePath}）。" +
                "若模块路径发生变化，请同步更新本测试中的相对路径。"
        )
    }

    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
}
