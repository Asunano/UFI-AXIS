package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.AppLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 适配器：清单表达不了的那 10%。
 *
 * ## 边界（很重要）
 *
 * 适配器能做三件事：**首次启动前初始化**、**补充健康判定**、**给出一次性初始口令**。
 * 它**不能**：改 core 的 prefs、拿 `RouteContext`、自己拉进程常驻、注册路由。
 * 一旦放开这些，适配器就会长成"第二套业务层"，插件契约立刻失效。
 *
 * 适配器是**编译期注册的 Kotlin 代码**，不是下载来的。清单里的 `first_run.adapter`
 * 只是一个 id，经 [ProgramAdapterRegistry] 查表。这样"插件式"不会变成第二条 RCE 通道。
 *
 * ## 为什么 preflight 必须幂等
 *
 * 用户可能"停掉再启动"很多次。[preflight] 每次启动前都会调（因为 core 可能重启过，
 * 内存里的"已初始化"标记没了），所以它必须自己判"是否真的需要初始化"。
 * OpenList 的例子：`data/config.json` 存在就跳过 —— 否则每次重启都会重置管理员密码，
 * 用户永远登录不上。
 */
interface ProgramAdapter {

    /** 与清单 `first_run.adapter` 对应 */
    val id: String

    /**
     * 首次启动前执行。**必须幂等**，且**必须自带超时**。
     *
     * 失败时返回中文原因 —— 它会直接展示给用户，不要透传英文异常串。
     */
    fun preflight(ctx: ProgramContext): Result<Unit>

    /**
     * 补充健康判定。返回 null 表示"用清单的声明式检查"。
     *
     * 用途举例：某程序的 `/` 永远返回 200 但实际没就绪，就必须靠这里额外查一个接口。
     */
    fun probeHealth(ctx: ProgramContext): Boolean? = null

    /** 一次性初始口令（如 OpenList 的管理员密码）。只在内存里传递给 UI，**不落盘**。 */
    fun bootstrapSecret(id: String): String? = null

    /**
     * 取走并清空初始口令。列表只返回 `has_secret`，值必须调用 secret 端点消费；
     * 取走后同一 Core 生命周期内不再可读。
     */
    fun consumeBootstrapSecret(id: String): String? = bootstrapSecret(id)

    /**
     * 重置初始口令（用户丢失口令/旧数据没有管理员时）。
     *
     * 默认不支持；Adapter 必须自己保证“只在程序停止时调用”（Route 侧会检查 currentId）。
     */
    fun resetBootstrapSecret(ctx: ProgramContext, password: String? = null): Result<String> =
        Result.failure(UnsupportedOperationException("该程序不支持重置初始口令"))
}

/**
 * 给适配器的运行时上下文。
 *
 * 刻意**只给目录与一个受控的 exec**，不给 `AppSettings` / `Context`：
 * 适配器要做的初始化是"跑一条 CLI 命令"，不是"访问整个应用"。
 */
class ProgramContext(
    val id: String,
    val binPath: File,
    val dataDir: File,
    val confDir: File,
    val runDir: File,
    /** 内容目录（大文件）：可能挂在 SD 卡上，`{CONTENT}` 展开成它 */
    val contentDir: File,
    /** 清单是否声明了 `content.enabled`（没声明就别去碰内容目录） */
    val contentEnabled: Boolean = false,
    val internalPort: Int,
    /** `direct` 或 `gateway`：适配器据此决定要不要把程序的监听地址钉在回环上 */
    val accessMode: String = "direct"
) {
    /**
     * 跑一条子命令（典型：`openlist admin set <pwd> --data <dir>`）。
     *
     * 三个约束，都是踩过的坑：
     * 1. **合并 stderr**：初始化失败的原因几乎总在 stderr，分开读会让调用方拿不到；
     * 2. **必须 waitFor 带超时**：外部二进制可能不退出（架构不符 / 等输入），
     *    `readText()` 会一路阻塞到 EOF，后面的超时永远走不到（`ComponentManager.probeVersion` 的教训）；
     * 3. **输出限长**：初始化命令通常几百字节，限 64KB 足够且防 OOM。
     */
    fun exec(args: List<String>, timeoutMs: Long = 15_000): ExecResult {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder(args)
                .directory(dataDir)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val output = if (finished) {
                runCatching { proc.inputStream.bufferedReader().use { readLimited(it, MAX_OUTPUT) } }
                    .getOrDefault("")
            } else {
                ""
            }
            if (!finished) {
                proc.destroyForcibly()
                ExecResult(-1, output.trim(), "命令超时（${timeoutMs}ms）后被终止")
            } else {
                ExecResult(proc.exitValue(), output.trim(), "")
            }
        } catch (e: Exception) {
            runCatching { proc?.destroyForcibly() }
            ExecResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readLimited(reader: java.io.BufferedReader, max: Int): String {
        val sb = StringBuilder()
        val buf = CharArray(4096)
        var total = 0
        while (total < max) {
            val n = reader.read(buf)
            if (n < 0) break
            sb.append(buf, 0, n)
            total += n
        }
        return sb.toString()
    }

    data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    private companion object {
        const val MAX_OUTPUT = 64 * 1024
    }
}

/**
 * 适配器注册表（编译期固定）。
 *
 * 用 `id → 实例` 的静态表，不做反射、不做动态加载 —— 反射加载等于把"能执行什么代码"
 * 交给运行时字符串决定，与商店本身的风险叠加。
 */
object ProgramAdapterRegistry {

    private val adapters = LinkedHashMap<String, ProgramAdapter>()

    /** 由 `:core` 的装配层在启动时调用（见 `ComponentFactory`）。重复注册同 id 会覆盖并告警。 */
    fun register(adapter: ProgramAdapter) {
        val prev = adapters.put(adapter.id, adapter)
        if (prev != null) {
            AppLogger.w(TAG, "适配器 ${adapter.id} 被重复注册，后者覆盖前者")
        }
    }

    /**
     * 只在该 id 尚未注册时注册（幂等）。
     *
     * 装配层可能被重复调用（服务重启不换进程），而适配器是**有状态的**：
     * OpenList 的管理员口令只存在实例的内存里，无条件覆盖实例等于把口令丢了。
     */
    fun registerIfAbsent(adapter: ProgramAdapter) {
        if (adapters.containsKey(adapter.id)) return
        adapters[adapter.id] = adapter
    }

    fun isKnown(id: String): Boolean = adapters.containsKey(id)

    fun get(id: String): ProgramAdapter? = adapters[id]

    fun all(): List<ProgramAdapter> = adapters.values.toList()

    /** 仅测试用：清空注册表 */
    fun clearForTest() = adapters.clear()

    private const val TAG = "ProgramAdapter"
}
