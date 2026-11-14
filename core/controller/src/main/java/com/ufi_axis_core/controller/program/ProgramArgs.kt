package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.program.ProgramPaths

/**
 * argv / env 模板的占位符展开。
 *
 * ## 为什么参数必须由 core 生成
 *
 * 清单里的 `argv` 会原样进 `ProcessBuilder`。如果允许前端传参、或者允许程序自己拼，
 * 那么"安装一个程序"就等于"在这台设备上执行任意命令"—— 它本来就是个 RCE 通道，
 * 不需要再多一条。
 *
 * 所以：**占位符是一份白名单，未知占位符直接报错**。宁可让清单作者改清单，
 * 也不要让 `{FOO}` 静默变成字面量 `{FOO}` 传进程序（那种 bug 要等到运行时才暴露）。
 *
 * ## 为什么逐元素展开、不拼 shell 字符串
 *
 * `{DATA}` 展开后是 `/data/data/.../files/programs/x` 这类含路径的串。
 * 走 `sh -c` 拼字符串会被空格/特殊字符切断，还要额外做转义。
 * 这里每个元素独立展开、直接作为 argv 的一项，全程不经过 shell。
 */
object ProgramArgs {

    /** 占位符可用的运行环境。字段名即占位符名。 */
    data class Env(
        val dataDir: String,
        val confDir: String,
        val contentDir: String,
        val cacheDir: String,
        val binPath: String,
        val runDir: String,
        val internalPort: Int,
        val externalPort: Int,
        /**
         * 程序应绑定的地址：`gateway` → 127.0.0.1（对外由 Gateway 转发）；
         * `direct` → 0.0.0.0（局域网直连）。由清单用 `{BIND}` 引用。
         */
        val bindHost: String = "127.0.0.1",
    )

    /**
     * 占位符匹配：`{DATA}` 这种。
     *
     * **必须用字符类 `[{]`/`[}]`，不能用 `\{`/`\}`**。
     * Android 的 `java.util.regex` 是 **ICU 实现**，对 `\{` 的处理与 OpenJDK 不同：
     * 它会抛 `PatternSyntaxException: Syntax error in regexp pattern near index 12`，
     * 而同样的正则在 JVM 单测（OpenJDK Pattern）里完全正常 —— 于是这类 bug
     * 只会在真机 release 包上炸，2026-10-30 就是这样让程序"永远起不来"，
     * 界面上只显示一个被吞掉 cause 的错误。
     */
    private val PLACEHOLDER = Regex("""[{]([A-Z_]+)[}]""")

    /**
     * 从清单 + 已安装元数据算出 [Env]。
     *
     * `entry` 由调用方从 `app.json` 取（[ProgramStore.readMeta]），**不在这里反查** ——
     * 反查会让本对象反过来依赖 Store，形成"参数工具要用存储层"的怪依赖。
     */
    fun env(
        paths: ProgramPaths,
        id: String,
        entry: String,
        internalPort: Int,
        externalPort: Int,
        storage: ProgramPaths.StorageKind = ProgramPaths.StorageKind.INTERNAL,
        contentRoot: String? = null,
        /** `gateway` / `direct`；决定 `{BIND}` 展开成 127.0.0.1 还是 0.0.0.0。 */
        accessMode: String = "gateway",
    ): Env {
        paths.ensureDirs(id, storage)
        return Env(
            dataDir = paths.dataDir(id, storage).absolutePath,
            confDir = paths.confDir(id, storage).absolutePath,
            contentDir = paths.contentDir(id, contentRoot).absolutePath,
            cacheDir = paths.cacheDir(id).absolutePath,
            binPath = paths.binFile(id, entry).absolutePath,
            runDir = paths.runDir(id).absolutePath,
            internalPort = internalPort,
            externalPort = externalPort,
            bindHost = if (accessMode == "direct") "0.0.0.0" else "127.0.0.1",
        )
    }

    /** 可用占位符名单；与 [expandOne] 的 when 分支**必须同步**。 */
    val KNOWN_PLACEHOLDERS = setOf(
        "DATA", "CONF", "CONTENT", "CACHE", "BIN", "RUN",
        "INTERNAL_PORT", "EXTERNAL_PORT", "BIND",
    )

    /**
     * 找出 [raw] 里的未知占位符。
     *
     * 给清单校验用：未知占位符如果只等到**启动**才报错，用户看到的是"点了启动没反应"，
     * 而清单本身是远端下发的、装之前就能查出来的东西。
     */
    fun unknownPlaceholders(raw: String): List<String> =
        PLACEHOLDER.findAll(raw).map { it.groupValues[1] }.filterNot { it in KNOWN_PLACEHOLDERS }.toList()

    /** 展开整条 argv。任一元素含未知占位符即失败（中文原因）。 */
    fun expand(template: List<String>, env: Env): Result<List<String>> = runCatching {
        template.map { expandOne(it, env) }
    }

    /** 展开单个字符串（env 的 value 也用它，例如 `TMPDIR={CACHE}`）。 */
    fun expandOne(raw: String, env: Env): String {
        val unknown = mutableListOf<String>()
        val out = PLACEHOLDER.replace(raw) { mr ->
            when (val key = mr.groupValues[1]) {
                "DATA" -> env.dataDir
                "CONF" -> env.confDir
                "CONTENT" -> env.contentDir
                "CACHE" -> env.cacheDir
                "BIN" -> env.binPath
                "RUN" -> env.runDir
                "INTERNAL_PORT" -> env.internalPort.toString()
                "EXTERNAL_PORT" -> env.externalPort.toString()
                "BIND" -> env.bindHost
                else -> {
                    unknown += key
                    mr.value
                }
            }
        }
        require(unknown.isEmpty()) {
            "未知占位符 ${unknown.distinct().joinToString("、") { "{$it}" }} " +
                "（可用：{DATA} {CONF} {CONTENT} {CACHE} {BIN} {RUN} {INTERNAL_PORT} {EXTERNAL_PORT}）"
        }
        return out
    }

    /** 展开整张 env 表。 */
    fun expandEnv(envMap: Map<String, String>, env: Env): Result<Map<String, String>> = runCatching {
        envMap.mapValues { (_, v) -> expandOne(v, env) }
    }
}
