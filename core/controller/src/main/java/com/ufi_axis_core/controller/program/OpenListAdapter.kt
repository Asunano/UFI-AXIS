package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.AppLogger
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.SecureRandom

/**
 * OpenList 的适配器 —— 本仓库第一个、也是 P0 阶段唯一一个第三方程序适配器。
 *
 * ## 它只做一件事：首次启动前把管理员密码设好
 *
 * OpenList 首次启动会生成 `config.json` 与一个**没有管理员**的空库。此时打开 Web UI
 * 会要求初始化；而我们的访问方式（Gateway 票据 → 跳端口）不适合让用户在初始化页面里
 * 手填用户名密码 —— 用户看到的是一个"要密码但没给过密码"的登录页。
 *
 * 所以由 core 在首次启动前代跑 `admin set`，生成一个随机口令，**只展示一次**。
 *
 * ## 三个必须守住的点
 *
 * 1. **幂等**：`config.json` 已存在就跳过。否则用户每停一次、再启动一次，
 *    管理员密码就被重置一次，永远登录不上。
 * 2. **必须显式带 `--data`**：不带的话命令会写到进程 cwd 下的 `./data`，
 *    与 `server --data {DATA}` 用的不是同一个目录 —— 表现为"设了密码但登录不上"，
 *    而且排查时两边目录都存在、都像是对的。
 * 3. **口令不落盘**：只放在内存里给 UI 展示一次，不写 prefs、不写日志。
 *    同 uid 的其它程序能读 `shared_prefs`（见计划书 §5.7），写进去等于公开。
 *
 * ## 已知不确定性（M0 要验证）
 *
 * 上游 `--data` 是 cobra 的全局 flag，理论上子命令前后都行；这里放在**最前面**
 * 是最保守的位置。若某个版本只认配置文件、不认 flag，`preflight` 会失败并
 * 把 stderr 原样带出来 —— 那时应改用"预写 config.json"的策略，而不是猜参数。
 */
class OpenListAdapter : ProgramAdapter {

    override val id = "openlist"

    @Volatile
    private var secret: String? = null

    override fun preflight(ctx: ProgramContext): Result<Unit> = runCatching {
        ctx.dataDir.mkdirs()

        val cfg = File(ctx.dataDir, "config.json")
        val configExisted = cfg.exists() && cfg.length() > 0
        val provisioning = File(ctx.dataDir, PROVISIONING_MARKER)
        val initialized = File(ctx.dataDir, ADMIN_MARKER)

        // 全新安装先落一个"正在初始化"标记：即使 bootstrap/admin 中途失败，
        // 下一次 preflight 也不会把"config 已生成"误判成"管理员已设置"。
        if (!configExisted) provisioning.writeText(System.currentTimeMillis().toString())

        // 按用户选的访问模式决定监听地址。gateway 模式下如果改不了地址必须失败，
        // 不能让程序以 0.0.0.0 起来绕过 Gateway。
        ensureAddress(ctx, loopback = ctx.accessMode == "gateway")

        // 内容目录（可能是 SD 卡）：把上传/转码用的临时目录挪过去。
        // 缩略图**搬不了** —— OpenList 用 conf.DataDir（即 --data）存它们，没有配置项；
        // 所以这里只搬 temp_dir，别声称"内容全在卡上"。
        ensureTempDir(ctx)

        if (initialized.exists()) {
            provisioning.delete()
            return@runCatching
        }

        // 旧版本/手工初始化过的数据：config 在、没有 provisioning 标记，保守认为管理员已设置，
        // 不重置密码（新版安装会走下面的 admin set）。这类老数据可通过重置接口补管理员。
        if (configExisted && !provisioning.exists()) {
            initialized.writeText(System.currentTimeMillis().toString())
            AppLogger.i(TAG, "检测到已有配置，按已初始化处理（不重置管理员）")
            return@runCatching
        }

        val pwd = generatePassword()
        val args = listOf(
            ctx.binPath.absolutePath,
            "--data", ctx.dataDir.absolutePath,
            "admin", "set", pwd
        )
        val r = ctx.exec(args, timeoutMs = 60_000)

        if (!r.ok) {
            // 失败时把 stderr/stdout 首段带出来：这是用户在"启动失败"里能看到的唯一线索
            val detail = (r.stderr.ifBlank { r.stdout }).take(300).ifBlank { "无输出" }
            throw IllegalStateException("设置管理员失败（退出码 ${r.exitCode}）：$detail")
        }

        secret = pwd
        initialized.writeText(System.currentTimeMillis().toString())
        provisioning.delete()
        AppLogger.i(TAG, "管理员已初始化（口令只在内存中）")
    }

    /**
     * 让 OpenList 只监听回环。
     *
     * 两种情况：
     * 1. `config.json` 已存在 → 直接改 `scheme.address`（保留其余字段原样）；
     * 2. 不存在 → **先跑一次**让它生成完整默认配置（含 `jwt_secret` 等我们不该猜的字段），
     *    杀掉，再改地址。
     *
     * 为什么不手写一份"最小 config"：上游的字段集随版本变化，少写一个关键字段
     * （比如 `jwt_secret`）会让它启动失败，而失败信息埋在 stderr 里很难定位。
     * 让上游自己生成，我们只做一处最小改写。
     *
     * 代价：首次多一次启动（实测 30s 左右）。换来的是"绑定地址一定生效"，
     * 比让 Gateway 静默失效划算。
     */
    private fun ensureAddress(ctx: ProgramContext, loopback: Boolean) {
        val cfg = File(ctx.dataDir, "config.json")

        if (!cfg.exists() || cfg.length() == 0L) {
            bootstrapDefaultConfig(ctx)
        }
        if (!cfg.exists() || cfg.length() == 0L) {
            throw IllegalStateException("无法生成 OpenList config.json（初始化失败）")
        }
        rewriteAddress(cfg, if (loopback) "127.0.0.1" else "0.0.0.0", ctx.internalPort)
    }

    /** 跑一次 `server` 只为让它写出 config.json，然后杀掉并等端口释放 */
    private fun bootstrapDefaultConfig(ctx: ProgramContext) {
        val proc = try {
            ProcessBuilder(ctx.binPath.absolutePath, "--data", ctx.dataDir.absolutePath, "server")
                .apply {
                    environment()["TMPDIR"] = ctx.confDir.absolutePath
                    redirectErrorStream(true)
                }
                .start()
        } catch (e: Exception) {
            throw IllegalStateException("启动 OpenList 生成默认配置失败：${e.message ?: e.javaClass.simpleName}")
        }

        val deadline = System.currentTimeMillis() + 90_000
        val cfg = File(ctx.dataDir, "config.json")
        // 必须持续读 stdout，否则管道写满会把它卡死在写日志上（同 TtydManager 的教训）
        val drain = Thread {
            runCatching { proc.inputStream.bufferedReader().use { r -> while (r.readLine() != null) Unit } }
        }.apply { isDaemon = true; start() }

        while (System.currentTimeMillis() < deadline) {
            if (cfg.exists() && cfg.length() > 0) break
            if (!proc.isAlive) break
            Thread.sleep(500)
        }
        runCatching { proc.destroy() }
        runCatching { proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) }
        drain.interrupt()
        // 端口释放要时间：紧接着的正式启动会用同一个 5244
        Thread.sleep(2_000)
        if (!cfg.exists() || cfg.length() == 0L) {
            throw IllegalStateException("OpenList 90s 内未生成 config.json（首次初始化失败）")
        }
    }

    /** 只改 `scheme.address`，其余字段原样保留 */
    /**
     * 把 `temp_dir` 指到 `{CONTENT}/temp`（幂等）。
     *
     * 只在清单声明了 content 且内容目录**当前可写**时做：卡被拔掉时保持原样，
     * 让程序用默认目录继续跑（清单里 `content.required=false` 就是这个语义）。
     */
    private fun ensureTempDir(ctx: ProgramContext) {
        if (!ctx.contentEnabled) return
        val cfg = File(ctx.dataDir, "config.json")
        if (!cfg.exists() || cfg.length() == 0L) return

        val target = File(ctx.contentDir, "temp")
        if (!runCatching { target.mkdirs() }.getOrDefault(false) && !target.isDirectory) {
            AppLogger.w(TAG, "内容目录不可写，temp_dir 维持默认：${target.absolutePath}")
            return
        }
        rewriteStringField(cfg, "temp_dir", target.absolutePath)
    }

    /** 把 config.json 顶层的某个字符串字段改成 [value]；已是该值则 no-op。 */
    private fun rewriteStringField(cfg: File, key: String, value: String) {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(cfg.readText())
                .let { it as kotlinx.serialization.json.JsonObject }
                .toMutableMap()
        } catch (e: Exception) {
            AppLogger.w(TAG, "config.json 解析失败（$key 未改）：${e.message ?: e.javaClass.simpleName}")
            return
        }
        val current = (root[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        if (current == value) return

        root[key] = kotlinx.serialization.json.JsonPrimitive(value)
        val tmp = File(cfg.parentFile, cfg.name + ".tmp")
        tmp.writeText(
            kotlinx.serialization.json.Json { prettyPrint = true }
                .encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(root))
        )
        if (!tmp.renameTo(cfg)) {
            cfg.writeText(tmp.readText())
            tmp.delete()
        }
        AppLogger.i(TAG, "config.json: $key = $value")
    }

    private fun rewriteAddress(cfg: File, target: String, port: Int) {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(cfg.readText())
                .let { it as kotlinx.serialization.json.JsonObject }
                .toMutableMap()
        } catch (e: Exception) {
            throw IllegalStateException("config.json 解析失败：${e.message ?: e.javaClass.simpleName}")
        }
        val scheme = (root["scheme"] as? kotlinx.serialization.json.JsonObject)?.toMutableMap()
            ?: throw IllegalStateException("config.json 缺少 scheme 段，无法设置监听地址")
        val current = (scheme["address"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val currentPort = (scheme["http_port"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull()
        // 端口也要一起校正（2026-11-10）：用户改过内部端口后，只改地址会让程序继续
        // 监听旧端口，健康检查与 Gateway 全部对不上。
        if (current == target && currentPort == port) return
        scheme["address"] = kotlinx.serialization.json.JsonPrimitive(target)
        scheme["http_port"] = kotlinx.serialization.json.JsonPrimitive(port)
        root["scheme"] = kotlinx.serialization.json.JsonObject(scheme)
        val tmp = File(cfg.parentFile, "config.json.tmp")
        try {
            tmp.writeText(kotlinx.serialization.json.JsonObject(root).toString())
            if (!tmp.renameTo(cfg)) {
                cfg.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            throw IllegalStateException("写入 config.json 失败：${e.message ?: e.javaClass.simpleName}")
        }
        AppLogger.i(TAG, "监听地址 $current → $target")
    }

    /** 一次性口令：UI 取走展示后应当自行丢弃；列表只暴露 has_secret。 */
    override fun bootstrapSecret(id: String): String? = secret

    override fun consumeBootstrapSecret(id: String): String? = synchronized(this) {
        val v = secret
        secret = null
        v
    }

    override fun resetBootstrapSecret(ctx: ProgramContext, password: String?): Result<String> = runCatching {
        ctx.dataDir.mkdirs()
        val pwd = password?.takeIf { it.isNotBlank() } ?: generatePassword()
        val r = ctx.exec(
            listOf(ctx.binPath.absolutePath, "--data", ctx.dataDir.absolutePath, "admin", "set", pwd),
            timeoutMs = 60_000
        )
        if (!r.ok) {
            val detail = (r.stderr.ifBlank { r.stdout }).take(300).ifBlank { "无输出" }
            throw IllegalStateException("重置管理员失败（退出码 ${r.exitCode}）：$detail")
        }
        secret = pwd
        File(ctx.dataDir, ADMIN_MARKER).writeText(System.currentTimeMillis().toString())
        AppLogger.i(TAG, "管理员口令已重置（口令只在内存中）")
        pwd
    }

    /**
     * 生成 20 位随机口令。
     *
     * 去掉 `0O1lI` 这类形近字符：这个口令要靠用户**手抄**到另一个设备上，
     * 形近字符是最常见的"抄错一次、登不上、以为程序坏了"。
     */
    private fun generatePassword(len: Int = 20): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return buildString(len) {
            repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) }
        }
    }

    private companion object {
        const val TAG = "OpenListAdapter"

        /** 新版 preflight 已成功设置过管理员的标记；与 config.json 是否存在无关。 */
        const val ADMIN_MARKER = ".ufi_admin_initialized"

        /** “全新安装初始化进行中”标记；admin set 失败后保留，供下一次重试识别。 */
        const val PROVISIONING_MARKER = ".ufi_provisioning"
    }
}
