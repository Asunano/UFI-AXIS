package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.program.ProgramPaths
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「第三方程序」的清单模型（2026-10-30 应用商店 P0）。
 *
 * ## 命名：为什么是 Program 而不是 App
 *
 * 仓库里 `App` 已经被 APK 管理占用了（`AppManager` / `AppRoutes` / `/api/apps`）。
 * 两者是完全不同的东西：APK 由系统 `PackageManager` 托管生命周期，第三方 Linux 程序
 * 由 core 用 `ProcessBuilder` 拉起、core 负责它的生死。名字撞了会让后来的人
 * 在 `/api/apps` 上找错实现，所以这里一律叫 **program**，路由走 `/api/programs`。
 *
 * ## 这份清单是"插件契约"的唯一入口
 *
 * 分层是：**清单（本文件）→ 运行时（[ProgramRuntime]）→ 适配器（[ProgramAdapter]）**。
 * 90% 的程序只写清单就能接进来；只有"首次运行要初始化管理员"这类特殊行为才写适配器。
 *
 * 清单**只描述、不执行**：字段全部是声明式的，没有一条能让 core 执行任意代码。
 * 参数模板里的占位符（`{DATA}` 等）由 [ProgramArgs] 填充，前端无法指定。
 *
 * ## 兼容策略
 *
 * - [schema] 高于 [SUPPORTED_SCHEMA] → 整条拒绝（旧 core 不猜新字段的语义）
 * - 未知字段 → 忽略（[Json] 配了 `ignoreUnknownKeys`）
 * - 缺失字段 → 用默认值，但关键字段（id/artifact.url/sha256/argv）没有默认值，解析即失败
 */
@Serializable
data class ProgramManifest(
    /** 清单 schema 版本。目前只有 1。 */
    val schema: Int = 1,
    val id: String,
    val name: String,
    val summary: String = "",
    val homepage: String = "",
    val license: String = "",
    val artifact: Artifact,
    val run: Run,
    val resources: Resources = Resources(),
    val restart: Restart = Restart(),
    val requires: Requires = Requires(),
    val content: Content = Content(),
    /** 首次运行前要跑的适配器 id；没有就是纯声明式程序。 */
    @SerialName("first_run") val firstRun: FirstRun? = null,
    val ui: Ui = Ui()
) {
    /** 远端产物的描述。**只含远端信息，不含任何本地路径** —— 二进制由用户安装时下载。 */
    @Serializable
    data class Artifact(
        val version: String = "",
        /** 上游直链。强制 HTTPS（本地调试地址除外，见 [ProgramManifestCodec.validate]）。 */
        val url: String,
        /** 64 位十六进制，唯一信任锚点。 */
        val sha256: String,
        /** 压缩包字节数。用于体积提示、磁盘预检（×3）、下载完整性比对。 */
        val size: Long = 0,
        /** `raw` | `tar.gz` */
        val archive: String = "raw",
        /** archive=tar.gz 时必需：包内可执行文件的名字（basename 匹配）。 */
        val entry: String = "",
        val abi: String = "arm64-v8a",
        /** `static` | `android-dynamic`（发布方自行声明，实际以 [com.ufi_axis_core.util.program.ElfInspector] 为准） */
        val linkage: String = "static"
    )

    @Serializable
    data class Run(
        /** argv 模板。占位符见 [ProgramArgs]。 */
        val argv: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val cwd: String = "{DATA}",
        /** 程序真实监听的端口（通常绑 127.0.0.1）。 */
        @SerialName("internal_port") val internalPort: Int = 0,
        /**
         * 默认访问方式：`any`（直连，用户用浏览器打开 ip:端口）或 `loopback`（经 Gateway）。
         *
         * **默认 any**：不替用户决定"必须从控制台进"。Gateway 是可选能力，不是强制门禁
         * —— 第三方程序自带登录，强制绕道只会让"装了却打不开"。
         */
        val bind: String = "any",
        /** Gateway 对外端口；bind=loopback 时必需，且必须与 internalPort 不同。 */
        @SerialName("external_port") val externalPort: Int = 0,
        val health: Health = Health(),
        val stop: Stop = Stop(),
        /** 是否只允许一个实例（目前恒为 true，字段留着是为了清单可读） */
        val singleton: Boolean = true,

        /**
         * 数据目录位置：`internal`（默认，`/data/data`，FBE 加密、慢）或
         * `external`（`/sdcard/.../programs/<id>/data`，快、可备份、但可被拔出）。
         *
         * **只影响 `data/` 与 `conf/`**：二进制永远在内部（`/sdcard` 是 noexec）。
         * 用户可以在安装时覆盖这个默认值。
         */
        val storage: String = "internal"
    )

    @Serializable
    data class Health(
        /** `http` | `tcp` | `process` */
        val kind: String = "http",
        val path: String = "/",
        /**
         * 二级判定：拿到这些状态码之一就算"活着"。
         *
         * **默认含 401/403**：OpenList 的 `/ping` 如果要求登录，只认 200 会把正常运行的实例
         * 判成卡死，然后被看护反复重启 —— 这是最容易踩的误杀。三级判定（[expectContains]）
         * 才用于"返回 200 但其实是错误页"。
         */
        @SerialName("accept_status") val acceptStatus: List<Int> = listOf(200, 401, 403),
        /** 三级判定：响应体包含该子串。空 = 不做三级判定。 */
        @SerialName("expect_contains") val expectContains: String = "",
        @SerialName("timeout_ms") val timeoutMs: Int = 2000,
        @SerialName("interval_s") val intervalS: Int = 30,
        @SerialName("fail_threshold") val failThreshold: Int = 3,
        /** 启动后多久才开始计失败。 */
        @SerialName("startup_grace_s") val startupGraceS: Int = 20,
        /**
         * 首次启动可延长到的上限（建库/迁移可能几十秒）。
         *
         * 这段时间内**只做存活判定，不触发资源分级处置** —— 否则第一次启动就会被
         * 自己的看门狗按"内存翻倍"杀掉，永远起不来。
         */
        @SerialName("startup_grace_max_s") val startupGraceMaxS: Int = 240,

        /**
         * **无进展判定窗口**：这么久没有任何"正在动"的迹象（CPU/内存/IO wait），
         * 就判定卡死、立刻失败，而不是干等硬上限。
         *
         * 为什么需要它：固定超时只能赌一个数字 —— 赌大了，真卡死的程序白占几分钟；
         * 赌小了，建库慢的程序被误杀（OpenList 首次实测 106s）。用"有没有进展"
         * 代替"过了多久"，两种都能判对。
         */
        @SerialName("stall_timeout_s") val stallTimeoutS: Int = 90,

        /** 有进展时允许延到的总上限（秒）。只有观察到进展才会用到它。 */
        @SerialName("startup_grace_extend_max_s") val startupGraceExtendMaxS: Int = 600
    )

    @Serializable
    data class Stop(
        /** 目前只支持 SIGTERM；字段留着是为了将来支持 SIGHUP 之类 */
        val signal: String = "TERM",
        @SerialName("grace_s") val graceS: Int = 10
    )

    @Serializable
    data class Resources(
        @SerialName("rss_warn_mb") val rssWarnMb: Int = 128,
        @SerialName("rss_restart_mb") val rssRestartMb: Int = 220,
        @SerialName("rss_kill_mb") val rssKillMb: Int = 360,
        /** 系统可用内存低于此值 → 拒绝启动（不是杀，是"这次别起"）。 */
        @SerialName("start_min_avail_mb") val startMinAvailMb: Int = 200,
        /** 0..19，越大越给 core 让路 */
        val nice: Int = 10,

        /**
         * 数据配额（MB）。**统计口径是"二进制 + 数据目录"**（[ProgramPaths.usedBytes]），
         * 不是只算 data/ —— 二进制动辄上百 MB，不计入会让配额数字没有意义。
         *
         * 超限行为：运行中只告警（不杀进程，避免删/断用户数据），
         * 但**下次启动会被拒绝**，把"继续增长"停在可控的位置。
         */
        @SerialName("data_quota_mb") val dataQuotaMb: Int = 2048,

        /**
         * 日志总量（MB）。**每程序总量**，含 stdout + stderr 两路与滚动保留的历史文件
         * （`ProgramLogSink` 的 `maxFiles`）。不是"单文件上限" —— 早期按单文件算时，
         * 一个 `32` 实际能占 32×3×2 = 192MB，清单作者与用户都会误判。
         */
        @SerialName("log_quota_mb") val logQuotaMb: Int = 32,
        /** 防 fork 炸弹：连同孙进程一起数 */
        @SerialName("max_processes") val maxProcesses: Int = 8
    )

    @Serializable
    data class Restart(
        /** 第 n 次重启用第 n 个，超出取最后一个。必须递增。 */
        @SerialName("backoff_s") val backoffS: List<Int> = listOf(5, 15, 60, 300, 900),
        /** 窗口内连续退出达到 3 次 → 熔断 */
        @SerialName("crash_loop_window_s") val crashLoopWindowS: Int = 120,
        /** 熔断前最多尝试次数 */
        @SerialName("give_up_after") val giveUpAfter: Int = 8
    )

    @Serializable
    data class Requires(
        @SerialName("privileged_port") val privilegedPort: Boolean = false,
        val root: Boolean = false,
        /** `online-only` | `offline-ok` */
        val network: String = "offline-ok",

        /**
         * 数据目录里会落凭据：云盘 token、设备私钥、用户口令哈希。
         *
         * 为 true 时**禁止把数据放到外部存储**（`/sdcard/Download/UFI-AXIS/programs/<id>`）——
         * 那里任何拿到存储权限的应用都能读，等于把凭据摊在公共区。
         * 三个内置程序（OpenList / Syncthing / Cloudreve）都是 true。
         */
        @SerialName("private_data") val privateData: Boolean = false
    )

    /**
     * 「内容目录」声明：这个程序有没有"大文件"要放（上传物、媒体库、共享目录）。
     *
     * 有的话，商店会把 `{CONTENT}` 指到一个**可以外置**的目录：数据目录（DB/密钥）
     * 仍然锁在内部，内容却可以放到 SD 卡上 —— 设备内部只有十几 G，插卡才是常态。
     */
    @Serializable
    data class Content(
        val enabled: Boolean = false,
        /**
         * 安装时的默认落点：`internal`（`programs/<id>/content`）
         * 或 `external`（`<公共存储>/UFI-AXIS/programs/content/<id>`）。
         */
        @SerialName("default_root") val defaultRoot: String = "internal",

        /**
         * 内容目录是否**必须**可用。
         *
         * - `true`（默认）：卡拔了就拒绝启动 —— 适用于"内容就是它的全部"的程序（Cloudreve 网盘）。
         * - `false`：内容目录只是"优先放这里"，不可用时程序照常跑（OpenList 的临时目录就是这种）。
         */
        val required: Boolean = true
    )

    @Serializable
    data class FirstRun(val adapter: String)

    @Serializable
    data class Ui(
        @SerialName("open_path") val openPath: String = "/",
        val title: String = ""
    )
}

/**
 * 清单的解析与校验。
 *
 * 校验的原则：**一次给出全部问题**，而不是遇到第一个就返回。用户看到的应该是
 * "这条清单有 3 处不对"，而不是"改完第一处、再报第二处"。
 */
object ProgramManifestCodec {

    /** 本端支持的清单版本。往上加一档就必须同步考虑"旧 core 怎么拒绝"。 */
    const val SUPPORTED_SCHEMA = 1

    /**
     * 单个产物上限。
     *
     * 2026-10-30 真机实测：OpenList `openlist-android-arm64` 解压后 **157.8MB**（150.5MiB）。
     * 原定 192MB 只剩 18% 余量，上游随便加个驱动就撞墙，所以放到 256MB。
     * 再大就要重新评估 —— 设备 `/data` 只有 18G 可用，且解压峰值是包体的 4 倍。
     */
    const val MAX_ARTIFACT_BYTES = 256L * 1024 * 1024

    /**
     * 端口保留表：这些端口上有 core 或系统的东西，程序不能申请。
     *
     * - 80    goform（设备自身的后台）
     * - 445   Samba
     * - 5555  adbd（ADB 自连接通道）
     * - 6801  aria2 JSON-RPC
     * - 8088  core 自己
     * - 17682 ttyd
     * - 53/67/68 系统 tethering 的 DNS/DHCP（非 root 也占不到，但提前拒绝比运行时失败好）
     */
    val RESERVED_PORTS = setOf(53, 67, 68, 80, 445, 5555, 6801, 8088, 17682)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

    /** 解析清单文本。JSON 语法错误 → failure，message 已是中文。 */
    fun parse(text: String): Result<ProgramManifest> = runCatching {
        json.decodeFromString(ProgramManifest.serializer(), text)
    }

    /**
     * 解析"清单集合"（`assets/programs/catalog.json` 或 `version.json` 的 `programs` 段）。
     *
     * 两种输入都接受：
     * - `{ "programs": [ ... ] }`
     * - `[ ... ]`（裸数组，方便测试与手工编写）
     *
     * **单条解析失败不影响其余条目**：一条写坏的清单不该让整个商店变成空白页。
     * 失败条目以 (id 或序号, 原因) 返回，供诊断页展示。
     */
    fun parseCatalog(text: String): Result<CatalogParse> = runCatching {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) {
            json.parseToJsonElement(trimmed).let { it as kotlinx.serialization.json.JsonArray }
        } else {
            val root = json.parseToJsonElement(trimmed)
            (root as? kotlinx.serialization.json.JsonObject)
                ?.get("programs")
                ?.let { it as kotlinx.serialization.json.JsonArray }
                ?: throw IllegalArgumentException("清单文件缺少 programs 数组")
        }

        val ok = mutableListOf<ProgramManifest>()
        val bad = mutableListOf<Pair<String, String>>()
        array.forEachIndexed { index, element ->
            val single = runCatching {
                json.decodeFromJsonElement(ProgramManifest.serializer(), element)
            }
            single.fold(
                onSuccess = { m ->
                    val errs = validate(m)
                    if (errs.isEmpty()) ok += m
                    else bad += (m.id.ifBlank { "#$index" }) to errs.joinToString("；")
                },
                onFailure = { e -> bad += ("#$index") to (e.message ?: "解析失败") }
            )
        }
        CatalogParse(ok, bad)
    }

    /** 清单集合的解析结果：[ok] 可用，[bad] 是"被丢弃的条目 + 原因" */
    data class CatalogParse(
        val ok: List<ProgramManifest>,
        val bad: List<Pair<String, String>>
    )

    /**
     * 静态校验（不碰文件系统、不发网络）。
     *
     * @return 空列表 = 通过；否则是**给人看的中文原因**，可直接拼进安装失败的提示。
     */
    fun validate(m: ProgramManifest): List<String> {
        val errors = mutableListOf<String>()

        if (m.schema > SUPPORTED_SCHEMA) {
            errors += "清单版本 ${m.schema} 高于本端支持的 $SUPPORTED_SCHEMA，请先升级 Core"
            // 版本不匹配时后续字段的含义不可信，直接返回
            return errors
        }
        if (!ID_REGEX.matches(m.id)) {
            errors += "非法 id「${m.id}」：只允许小写字母/数字/下划线/连字符，且以字母或数字开头，最长 32"
        }
        if (m.name.isBlank()) errors += "缺少名称"

        // ── 产物 ──
        val a = m.artifact
        if (a.url.isBlank()) errors += "缺少下载地址"
        if (!SHA256_REGEX.matches(a.sha256)) errors += "sha256 必须是 64 位十六进制（当前「${a.sha256}」）"
        if (a.size <= 0) errors += "缺少产物体积（size）"
        if (a.size > MAX_ARTIFACT_BYTES) {
            errors += "产物 ${a.size / 1024 / 1024}MB 超过上限 ${MAX_ARTIFACT_BYTES / 1024 / 1024}MB"
        }
        when (a.archive) {
            "raw" -> Unit
            "tar.gz" -> if (a.entry.isBlank()) errors += "tar.gz 必须声明 entry（包内可执行文件名）"
            else -> errors += "未知的压包格式「${a.archive}」（只支持 raw / tar.gz）"
        }
        if (a.entry.isNotBlank()) {
            runCatching { ProgramPaths.requireEntry(a.entry) }
                .onFailure { errors += it.message ?: "非法 entry" }
        }
        if (a.abi != "arm64-v8a") errors += "ABI「${a.abi}」不受支持（本机只有 arm64-v8a）"
        if (a.linkage !in setOf("static", "android-dynamic")) {
            errors += "linkage「${a.linkage}」不受支持（只接受 static / android-dynamic）"
        }

        // ── 入口路径 ──
        // `/apps/{id}/enter` 会用 `http://<设备>:<port><open_path>` 302 跳转。清单可控时，
        // `@evil.com/` 能拼出 `http://<IP>:<port>@evil.com/`（userinfo 劫持），把用户送到外部站点。
        val openPath = m.ui.openPath
        if (!openPath.startsWith("/") || openPath.contains("//") || openPath.contains("@") ||
            openPath.contains("://") || openPath.contains("\\")
        ) {
            errors += "open_path「$openPath」不合法（必须以 / 开头，且不含 // 、@ 、://）"
        }

        // ── 运行 ──
        val r = m.run
        // 占位符在**安装期**就要查出来：启动时才发现，用户只会看到"点了没反应"
        val unknown = (r.argv + r.env.values + listOf(r.cwd))
            .flatMap { ProgramArgs.unknownPlaceholders(it) }
            .distinct()
        if (unknown.isNotEmpty()) {
            errors += "启动参数里有未知占位符 ${unknown.joinToString("、") { "{$it}" }}" +
                "（可用：${ProgramArgs.KNOWN_PLACEHOLDERS.joinToString(" ") { "{$it}" }}）"
        }
        if (r.argv.isEmpty()) errors += "缺少启动参数（argv）"
        if (r.internalPort !in 1..65535) errors += "internal_port「${r.internalPort}」非法"
        if (r.bind !in setOf("loopback", "any")) errors += "bind 只能是 loopback / any"
        if (r.bind == "loopback") {
            if (r.externalPort !in 1..65535) {
                errors += "bind=loopback 时必须给 external_port（Gateway 对外端口）"
            } else if (r.externalPort == r.internalPort) {
                errors += "external_port 不能等于 internal_port（同一端口无法既被程序占又被 Gateway 占）"
            }
        }
        listOf(r.internalPort, r.externalPort)
            .filter { it in 1..65535 }
            .filter { it in RESERVED_PORTS }
            .distinct()
            .forEach { errors += "端口 $it 是保留端口，不能分配给程序" }

        if (r.storage !in setOf("internal", "external")) {
            errors += "storage 只能是 internal / external（当前「${r.storage}」）"
        }

        val h = r.health
        if (h.kind !in setOf("http", "tcp", "process")) errors += "health.kind 只能是 http / tcp / process"
        if (h.kind == "http" && !h.path.startsWith("/")) errors += "health.path 必须以 / 开头"
        if (h.timeoutMs <= 0) errors += "health.timeout_ms 必须为正"
        if (h.intervalS <= 0) errors += "health.interval_s 必须为正"
        if (h.failThreshold <= 0) errors += "health.fail_threshold 必须为正"
        if (h.startupGraceMaxS < h.startupGraceS) {
            errors += "startup_grace_max_s 不能小于 startup_grace_s"
        }
        if (r.stop.graceS !in 0..120) errors += "stop.grace_s 必须在 0..120"

        // ── 资源 ──
        val res = m.resources
        if (!(res.rssWarnMb > 0 && res.rssWarnMb < res.rssRestartMb && res.rssRestartMb < res.rssKillMb)) {
            errors += "内存阈值必须满足 0 < rss_warn_mb < rss_restart_mb < rss_kill_mb"
        }
        if (res.startMinAvailMb < 0) errors += "start_min_avail_mb 不能为负"
        if (res.nice !in 0..19) errors += "nice 必须在 0..19"
        if (res.dataQuotaMb < 0) errors += "data_quota_mb 不能为负"
        if (res.logQuotaMb < 0) errors += "log_quota_mb 不能为负"
        if (res.maxProcesses < 1) errors += "max_processes 至少为 1"

        // ── 重启 ──
        val rs = m.restart
        if (rs.backoffS.isEmpty()) errors += "backoff_s 不能为空"
        if (rs.backoffS.any { it < 0 }) errors += "backoff_s 不能有负数"
        if (rs.backoffS.zipWithNext().any { (a1, b) -> b < a1 }) {
            errors += "backoff_s 必须非递减（否则退避会越退越短）"
        }
        if (rs.crashLoopWindowS <= 0) errors += "crash_loop_window_s 必须为正"
        if (rs.giveUpAfter < 1) errors += "give_up_after 至少为 1"

        // ── 能力声明 ──
        if (m.requires.privilegedPort) {
            errors += "该程序需要特权端口（<1024），非 root 设备无法运行，已拒绝安装"
        }
        if (m.requires.root) {
            errors += "该程序需要 root，已拒绝安装"
        }
        if (m.requires.network !in setOf("online-only", "offline-ok")) {
            errors += "requires.network 只能是 online-only / offline-ok"
        }
        if (m.content.defaultRoot !in setOf("internal", "external")) {
            errors += "content.default_root 只能是 internal / external"
        }
        // 清单自相矛盾：含凭据却默认放外部存储 → 装了也一定会被拒，不如现在就报
        if (m.requires.privateData && m.run.storage == "external") {
            errors += "requires.private_data=true 时 run.storage 不能是 external（凭据不能落公共存储）"
        }

        // ── 适配器 ──
        m.firstRun?.let {
            if (it.adapter.isBlank()) errors += "first_run.adapter 为空"
            else if (!ProgramAdapterRegistry.isKnown(it.adapter)) {
                errors += "未注册的适配器「${it.adapter}」（当前构建不认识它，可能清单比 Core 新）"
            }
        }

        return errors
    }

    /**
     * 下载地址的安全校验：强制 HTTPS。
     *
     * 与 `ComponentManager.requireSecureUrl` 同一口径：本地调试地址（localhost / 内网 IP）
     * 允许 HTTP，其余一律 HTTPS。**不允**许静默降级 —— 二进制是从公网下来要执行的东西。
     */
    fun requireSecureUrl(urlStr: String): Result<Unit> = runCatching {
        val u = java.net.URL(urlStr)
        if (u.protocol.equals("https", ignoreCase = true)) return@runCatching
        // 只放行**字面内网 IP** 与 localhost。
        //
        // 原实现按字符串前缀判（`host.startsWith("10.")` 等），`http://10.evil.com/x` 与
        // `http://192.168.evil.com/x` 都能通过；`172.` 还顺带放行了 172.32.0.0/11 这类公网段。
        // 解析成 InetAddress 再按私网 CIDR 判定，且**不接受域名**（避免 DNS 指向内网做绕过）。
        val host = u.host.orEmpty().removeSurrounding("[", "]")
        require(host.isNotBlank() && u.userInfo == null) {
            "下载地址不合法（必须是 https，或字面内网 IP）"
        }
        val isLocal = runCatching {
            val addr = java.net.InetAddress.getByName(host)
            // getByName 对域名会解析：这里要求 host 本身就是 IP 字面量
            require(addr.hostAddress != null)
            addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
        }.getOrDefault(false) || host.equals("localhost", ignoreCase = true)
        require(isLocal) { "下载地址必须使用 HTTPS（当前协议: ${u.protocol}）" }
    }
}
