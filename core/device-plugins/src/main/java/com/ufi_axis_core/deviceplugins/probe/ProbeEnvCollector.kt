package com.ufi_axis_core.deviceplugins.probe

import android.os.Build
import com.ufi_axis_core.devicespi.BuildInfo
import com.ufi_axis_core.devicespi.CpuInfoPlatform
import com.ufi_axis_core.devicespi.ProbeEnv
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * [ProbeEnv] 采集器 —— 阶段 5 的 5.1。**整个组件图只采一次**，由装配层
 * `ComponentFactory.build()` 在选型之前调用，采到的那一份贯穿全程。
 *
 * ## 为什么放在 `:core:device-plugins` 而不是别处
 *
 * - **不放 `:core:device-spi`**：那是纯契约层，它的单测必须不起 Android 就能跑。
 *   本类要读 `Build.*`、要发 HTTP —— 三件事全是 I/O，放进去等于把 Android 与网络
 *   拖进契约层。契约层只留判据常量与纯函数（[CpuInfoPlatform]）。
 * - **不放 `:core:common`**：本类的返回类型是 [ProbeEnv]，那会逼 `:core:common`
 *   反过来依赖 `:core:device-spi`。`:core:common` 是**被所有模块依赖**的底座，
 *   让它依赖设备 SPI 就是把依赖方向倒过来（device-spi 已经依赖 device-schema / contract），
 *   下一次有人给 device-spi 加依赖就可能成环。
 * - **不放 `:core` 的 service 包（装配层）**：采集口径（哪个文件、哪个 cmd、判据怎么写）
 *   是**设备探测知识**，5.2 / 5.3 还会继续往上长；装配层应该只有「调它一次」这一行。
 *   而且 `:core` 是 application module，它的单测要跟着 app 变体跑。
 * - **放这里的实际代价是零**：本模块已经有 `:core:device-spi`（api）、`:core:common`
 *   （`AppLogger`）、协程与 Android（`Build`），**不需要新增任何 Gradle 依赖**。
 *   包名单开一层 `probe/` —— 它不是某台设备的知识，所以既不在 `zte/f50/` 也不在 `platform/sprd/`。
 *
 * ## 三条纪律
 *
 * 1. **绝不抛异常**：任何一项失败就退化成 `null` / `false`，自己吞掉并记一条日志。
 *    本类在**启动路径**上（`ComponentFactory.build()`），它失败不许影响组件图构造 ——
 *    「型号不认识不能导致整个不工作」（计划书 10.2）在采集这一层同样成立。
 *    唯一放过去的是 [CancellationException]：调用方取消就该真的取消。
 * 2. **只发一次网络请求**，硬超时 1.5s（计划书 §11.8）。
 * 3. **日志文案固定、不拼可变内容**：`AppLogger` 的 `repeatGate` 按「级别 + tag + 完整消息」
 *    折叠重复，文案里带 IP / 端口 / 异常串就折不住了。
 */
object ProbeEnvCollector {

    private const val TAG = "ProbeEnv"

    /**
     * `LD` 探测的硬超时（毫秒）—— 计划书 §11.8 定的 1.5s。
     *
     * 同时用在三处：`connectTimeout`、`readTimeout` 与外层的 [withTimeoutOrNull]。
     * 前两者是 socket 级的，后者才是**调用方视角的硬上限**：
     * `HttpURLConnection` 的阻塞读不响应协程取消，只靠 socket 超时的最坏情况是
     * 「连接 1.5s + 读 1.5s」= 3s。外面再套一层 [withTimeoutOrNull] 之后，
     * 本方法一定在 1.5s 内返回（那条后台线程自己结束，结果丢弃）。
     */
    private const val PROBE_TIMEOUT_MS = 1_500L

    /**
     * 采一份指纹。**不抛异常**，最坏情况返回「什么都没采到」的那一份
     * （`cpuInfoPlatform = null` / `goformLdReachable = false` / [BuildInfo] 全空串）。
     *
     * @param deviceIp 设备 IP。口径与 `TransportConfig.deviceIp` **完全一致**
     *   （`AppSettings.goformIp` 空则回落网关 IP）——**由装配层算好传进来**，
     *   本类不读 `AppSettings`：两处各算一遍就会有两个口径，而装配层那一处已经在算了。
     * @param port 设备后台端口（`AppSettings.goformPort`）。
     */
    suspend fun collect(deviceIp: String, port: Int): ProbeEnv = ProbeEnv(
        cpuInfoPlatform = readCpuInfoPlatform(),
        androidBuild = readBuildInfo(),
        goformLdReachable = probeGoformLd(deviceIp, port),
    )

    /**
     * 读 `/proc/cpuinfo`，交给 [CpuInfoPlatform.normalize] 归一化（trim + 小写）。
     *
     * 读不到（文件不存在 / 没权限 / 内容为空）→ `null` + 一条 WARN。
     * 这一步是**本地文件读**，耗时可忽略（计划书 §11.8）。
     */
    private suspend fun readCpuInfoPlatform(): String? = withContext(Dispatchers.IO) {
        try {
            val normalized = CpuInfoPlatform.normalize(File("/proc/cpuinfo").readText())
            if (normalized == null) {
                AppLogger.w(TAG, "读 /proc/cpuinfo 得到空内容，平台判据本次缺失（不影响启动）")
            }
            normalized
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 文案固定、不带异常串：repeatGate 按完整消息折叠，带上 message 就折不住。
            AppLogger.w(TAG, "读 /proc/cpuinfo 失败，平台判据本次缺失（不影响启动）")
            null
        }
    }

    /**
     * Android `Build` 字段快照。
     *
     * 每个字段单独兜 `?: ""`：`Build.*` 在 Kotlin 侧是平台类型（`String!`），
     * 定制 ROM 上理论上可以是 null，而本类的纪律是「任何一项失败就用空串」。
     */
    private fun readBuildInfo(): BuildInfo = try {
        BuildInfo(
            brand = Build.BRAND ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            manufacturer = Build.MANUFACTURER ?: "",
            sdkInt = Build.VERSION.SDK_INT,
        )
    } catch (_: Exception) {
        AppLogger.w(TAG, "读 Build 字段失败，设备指纹本次缺失（不影响启动）")
        BuildInfo(brand = "", model = "", device = "", manufacturer = "", sdkInt = 0)
    }

    /**
     * 免登录、免 profile 的裸 HTTP 探测：`GET .../goform_get_cmd_process?cmd=LD` 是否回 200。
     *
     * ## 为什么不复用 `GoformClient`
     *
     * 这是 `DeviceProfiles.kt` 记的**鸡生蛋**问题的另一半：本方法跑在**选型之前**，
     * 而 `GoformClient` 是**选中的插件**造出来的（`DevicePlugin.createTransport`）——
     * 此刻传输层还不存在。反过来，`GoformClient` 的每条读写都先 `ensureLogin()`
     * （要密码、要 session、要版本字段），那些全是「已经知道这是哪台设备」之后才成立的东西。
     * `LD` 是唯一不需要这些的一条：免登录、免 profile，一个固定 cmd 名就能取。
     *
     * ## URL 形状照 `GoformClient.ensureLogin()` 的实测写法
     *
     * - base：`port == 80` 时 `http://ip`，否则 `http://ip:port`（与 `GoformClient.baseUrl()` 同）;
     * - path + query：`/goform/goform_get_cmd_process?cmd=LD&isTest=false&_=<毫秒时间戳>`；
     * - 只带 `Referer: <base>/index.html`，**不带 Cookie**（`ensureLogin()` 取 LD 那一次也没带 ——
     *   它就是拿来算密码哈希的，必须在登录之前能取到）。
     *
     * ⚠ **不做** `GoformClient.ensureBaseUrlResolved()` 那套 `127.0.0.1` / `localhost` 回落：
     * 那是「连不上就换个宿主再试」，一次回落就是一次额外请求（最多 3 次），
     * 与「启动路径上只发一次」直接冲突。本探测的语义是「按配置的地址，1.5s 内能不能拿到 LD」，
     * 拿不到就当**不可达**（[ProbeEnv.goformLdReachable] = false）—— 保守方向正确：
     * 5.2 里它只会让插件**不自称匹配**，兜底插件仍由 `PluginRegistry.DEFAULT` 提供，
     * 后端照样起得来。
     *
     * ## 失败处理
     *
     * 超时 / 异常 / 非 200 **一律 `false`**，并打**一条固定文案**的 WARN。
     * 绝不抛：这条请求失败不许影响组件图构造。
     */
    private suspend fun probeGoformLd(deviceIp: String, port: Int): Boolean {
        val base = if (port == 80) "http://$deviceIp" else "http://$deviceIp:$port"
        val url = "$base/goform/goform_get_cmd_process?cmd=LD&isTest=false&_=${System.currentTimeMillis()}"
        val ok = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { getStatusIs200(url, base) }
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            // 文案固定、不含 IP / 端口 / 异常串：否则 repeatGate 折不住（见类 KDoc 第 3 条）。
            AppLogger.w(TAG, "goform LD 免登录探测未通过（1.5s 内未拿到 200），本次按「后台不可达」计")
        }
        return ok
    }

    /** 真正发请求的那一段。阻塞调用，必须在 IO 调度器上。 */
    private fun getStatusIs200(url: String, base: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = PROBE_TIMEOUT_MS.toInt()
                readTimeout = PROBE_TIMEOUT_MS.toInt()
                useCaches = false
                setRequestProperty("Referer", "$base/index.html")
            }
            conn.responseCode == HttpURLConnection.HTTP_OK
        } catch (_: Exception) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
