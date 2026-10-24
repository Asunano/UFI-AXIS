package com.ufi_axis_core.api.routes

import android.content.Context
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.update.MirrorResolver
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 赞助名单（`/api/sponsors`，2026-10-14）。
 *
 * ## 用途
 * core 在这件事上**只负责提供更新通道**：客户端（web / app）各自在构建期打包一份
 * `sponsors.json` 作保底，运行时向本端点要一份"可能更新"的版本，据此显示
 * 「名单更新于 X」。端点只读，不改任何状态、不产生副作用。
 *
 * 响应：`{"source":"bundled"|"remote","updatedAt":"2026-10","sponsors":[…]}`。
 * [SOURCE] 描述的是**这一份数据从哪来**（`remote` = 来自远端拉取或它的落盘缓存），
 * 不是"这次响应有没有出网"。
 *
 * ## 三层取数（本文件存在的全部理由）
 * 1. **保底**：构建期由 gradle Copy 任务把仓库根 `sponsors.json` 打进 assets
 *    （见 `core/api/build.gradle.kts` 的 `copySponsorsJson`），永远有内容；
 * 2. **更新**：后台每 [REFRESH_INTERVAL_MS] 拉一次远端（地址由 [AppSettings.updateUrl]
 *    推导，见 [deriveSponsorsUrl]），校验通过才落盘缓存并替换内存快照；
 * 3. **容错**：拉取失败 / 校验不过 → **一个字都不动**，继续用落盘缓存；缓存也没有 → 用第 1 层。
 *
 * 「坏响应绝不覆盖好名单」是硬约束：本文件里没有任何一条路径会把空数组或半份数据写进
 * 内存快照或缓存文件（见 [refreshOnce] 与 [validate]）。
 *
 * ## 为什么单独一个文件、而不是并进 UpdateManager
 * 它与更新链路只共享两件事：镜像候选（[MirrorResolver]）与「从 `update_url` 推导同目录资源」
 * 这条规则。而 UpdateManager 的职责是 APK 下载 + 守护脚本 + 安装状态机（四百余行、带互斥锁
 * 与进程被杀等副作用），把「拉一份只读 JSON」塞进去，等于让那条链路多一个与安装无关的失败面。
 * 这里只依赖 `settings` + `Context`，因此可以在
 * [com.ufi_axis_core.core.server.HttpServer] 的注册处就地构造（一行），
 * 不必再往 `ComponentFactory` 加装配点与构造参数。
 *
 * ## 否决过的替代方案
 * - **惰性刷新（请求时发现过期才拉）**：会把一次外网请求挂在用户请求路径上
 *   （最坏 = 候选数 × [TIMEOUT_MS]），而这份名单对时效毫无要求。改为后台固定 12 小时。
 *   注意这与 [GeoRoutes]「没有定时器」的取向不冲突：那边是**几乎不变**的本地检测结果，
 *   这边是一条明确要求周期更新的外部数据源。
 * - **复用 `ResponseCache` 做 TTL 缓存**：那是给"上游调用结果"用的内存缓存，进程重启即失效；
 *   而第 3 层容错要求这份数据**跨重启存活**，所以必须落盘。
 * - **落盘缓存放 `/sdcard/Download/UFI-AXIS/`**（UpdateManager 的 APK / 日志目录）：
 *   那是外部存储、可被其它 App 读写。一份展示用名单没必要暴露给外部，更不该让外部文件
 *   有机会冒充远端结果。改用 `context.filesDir`（与 `WebResourceManager` 的 web override 同一种手法）。
 * - **把远端地址写死成 `.../main/sponsors.json`**：用户与 CI 都可以把 `update_url` 指到自建 CDN
 *   或本地调试地址；写死 URL 会让这些部署形态静默走回官方源，且与 `version.json` 不同源。
 * - **core 侧再排一次序 / 过滤匿名条目**：排序口径（`at` 倒序、同级 `name` 升序）与匿名条目
 *   的渲染方式都是**客户端渲染口径**，已经写在源文件的 `_note` 里。core 多存一份口径就多一处
 *   漂移点，所以这里只做校验与透传。
 */
class SponsorRoutes(
    private val settings: AppSettings,
    private val context: Context
) {

    /**
     * 内存中的当前快照（**唯一读源**）。
     *
     * 为什么把 `source` / `updatedAt` / `sponsors` 打包成一个不可变对象而不是三个并列字段：
     * 刷新协程（写）与 Netty worker（读）并发，三个 `@Volatile` 字段各自赋值会让读侧看到
     * 「remote 的名单 + bundled 的 source」这种撕裂组合。一个 `@Volatile` 引用赋值天然原子。
     */
    private data class Snapshot(val source: String, val updatedAt: String?, val sponsors: JsonArray)

    /** 刷新协程的作用域。写法与 [com.ufi_axis_core.api.update.UpdateManager] / WebUpdateManager 一致 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var snapshot: Snapshot? = null

    /** 落盘缓存的路径（`filesDir/sponsors/sponsors.json`）。单独一级目录的理由见类注释「否决过的替代方案」 */
    private val cacheFile = File(File(context.filesDir, CACHE_DIR), CACHE_FILE)

    init {
        // 第 1 层先同步读出来：文件只有 KB 级，而这一步发生在服务启动的注册路径上，
        // 代价可忽略；换来的是任何时刻 [snapshot] 都非空 —— 端点永远不会因为
        // "还没读到数据"而回空或 500。异步读则会让首个请求有概率撞上未初始化。
        snapshot = loadBundled()
        // 第 2 层覆盖第 1 层：有合规的落盘缓存就先用它（source=remote，它的出身就是远端）。
        loadDiskCache()?.let { snapshot = it }
        // 第 3 层：异步刷新，失败不影响上面两步的结果。
        startRefreshLoop()
    }

    fun register(route: Route) {
        route.route("/sponsors") {
            get {
                // snapshot 在 init 里已赋值；`?:` 只是防御（构造期 asset 读取被极端环境拦掉时兜底）。
                val snap = snapshot ?: EMPTY_SNAPSHOT
                call.respond(
                    toJsonElement(
                        mapOf(
                            "source" to snap.source,
                            "updatedAt" to snap.updatedAt,
                            // 原样透传已校验过的条目对象：远端将来给某条加个 link/avatar 字段时，
                            // 这里不需要跟着改，也不会把它吞掉。
                            "sponsors" to snap.sponsors
                        )
                    )
                )
            }
        }
    }

    // ── 第 1 层：构建期打进 assets 的保底数据 ──

    /**
     * 读内置 `assets/sponsors.json`。
     *
     * 内置那份**也走同一套校验**：它由构建期 Copy 从仓库根复制而来，正常必然合规，
     * 但"复制到一半/被别的工具改写"是可能的 —— 不校验就等于给了一条绕过校验的输入通道。
     * 校验不过时回空名单（而不是抛异常把 core 启动带崩）：端点必须永远可用。
     */
    private fun loadBundled(): Snapshot {
        val raw = runCatching {
            context.assets.open(ASSET_NAME).use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (raw == null) {
            AppLogger.w(TAG, "读不到内置 assets/$ASSET_NAME，赞助名单回空（检查构建期 copySponsorsJson 是否执行）")
            return EMPTY_SNAPSHOT
        }
        return runCatching { validate(raw, SOURCE_BUNDLED) }.getOrElse {
            AppLogger.w(TAG, "内置 assets/$ASSET_NAME 不合规，回空名单：${it.message}")
            EMPTY_SNAPSHOT
        }
    }

    // ── 第 2 / 3 层：落盘缓存 + 后台刷新 ──

    /**
     * 读落盘缓存。文件不存在 / 读失败 / 内容不合规一律回 null（= 继续用第 1 层）。
     *
     * 不合规时**不删文件**：删了不会让下一次拉取更容易成功，反而抹掉了排查线索；
     * 下一次成功拉取本来就会原子覆盖它。
     */
    private fun loadDiskCache(): Snapshot? {
        val f = cacheFile
        if (!f.isFile) return null
        val raw = runCatching { f.readText() }.getOrElse {
            AppLogger.w(TAG, "落盘缓存读取失败，改用内置保底：${it.message}")
            return null
        }
        return runCatching { validate(raw, SOURCE_REMOTE) }.getOrElse {
            AppLogger.w(TAG, "落盘缓存不合规，忽略并保留内置保底：${it.message}")
            null
        }
    }

    /**
     * 后台刷新循环：**先拉一次，之后每 [REFRESH_INTERVAL_MS] 一次**。
     *
     * 照抄 core 既有的后台循环写法（`DataScheduler.start()` 里那些
     * `scope.launch { while (isActive) { work(); delay(interval) } }`）：
     * 用私有 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，随进程起停
     * （本类与 HttpServer 同生命周期，没有独立的 stop 点，与 UpdateManager / WebUpdateManager
     * 的既有做法一致 —— 它们同样不写 stop）。
     *
     * 外层再包一层 `runCatching`：[refreshOnce] 内部已经全兜住了，这里只是保证
     * **任何**意外都不会让循环退出（循环一死，这台设备就永久停在旧名单上，且无人知道）。
     */
    private fun startRefreshLoop() {
        scope.launch {
            while (isActive) {
                runCatching { refreshOnce() }.onFailure {
                    AppLogger.w(TAG, "赞助名单刷新出现未预期异常（保留现有名单）：${it.message}")
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    /**
     * 拉一次远端并尝试替换内存快照。
     *
     * 三条失败路径（地址推导失败 / 全部候选不可达 / 校验不过）**都只打 WARN 并原样返回**：
     * 不抛、不回 500、不写空、不动 [snapshot]、不动缓存文件。这就是「坏响应绝不覆盖好名单」。
     */
    private suspend fun refreshOnce() {
        val rawUrl = deriveSponsorsUrl(settings.updateUrl)
        if (rawUrl == null) {
            AppLogger.w(TAG, "无法从 update_url 推导赞助名单地址（值=${settings.updateUrl}），跳过本次远端刷新")
            return
        }
        // 镜像决策入口：与 UpdateManager / WebUpdateManager 完全一致 —— 先按需刷新地区，
        // 再用 MirrorResolver 出候选（三镜像 + 按出口地区直连 + 原地址兜底）。
        MirrorResolver.prepare(settings)
        val text = try {
            fetchWithFallback(rawUrl)
        } catch (e: Exception) {
            AppLogger.w(TAG, "赞助名单拉取失败（保留现有名单，source=${snapshot?.source}）：${e.message}")
            return
        }
        val fresh = try {
            validate(text, SOURCE_REMOTE)
        } catch (e: Exception) {
            AppLogger.w(TAG, "远端赞助名单不合规，整份丢弃并保留现有名单：${e.message}")
            return
        }
        // 校验通过才落盘：缓存文件里因此**永远只可能是合规内容**（读回来还要再校验一次，
        // 那是为了防"文件被外部工具改写"，不是为了防我们自己写坏）。
        writeCache(text)
        snapshot = fresh
        AppLogger.i(
            TAG,
            "赞助名单已更新：updatedAt=${fresh.updatedAt ?: "-"}，共 ${fresh.sponsors.size} 条（源=$rawUrl）"
        )
    }

    /**
     * 逐候选拉取（照抄 UpdateManager.fetchManifestWithFallback / WebUpdateManager 的同名函数）：
     * 镜像挂了还能直连，而不是"镜像一挂整条链路挂"。
     *
     * @throws Exception 全部候选失败，消息里带每个候选的失败原因（定位是镜像挂了还是清单本身错了）
     */
    private fun fetchWithFallback(rawUrl: String): String {
        val candidates = MirrorResolver.candidates(settings, rawUrl)
        if (candidates.isEmpty()) throw Exception("赞助名单地址为空")
        val errors = mutableListOf<String>()
        candidates.forEachIndexed { index, url ->
            val failure = try {
                requireSecureUrl(url)
                val text = fetchUrl(url, TIMEOUT_MS)
                if (text != null) {
                    // 体积闸门：这份名单正常只有几 KB，而读全文是没有上限的。设备内存只有
                    // 256MB 级，一个异常大的响应足以把 core 打挂 —— 超限按本候选失败处理
                    //（下一候选多半同样超限，最终就是"保留旧名单"，那正是要的结果）。
                    if (text.length > MAX_RAW_CHARS) {
                        throw Exception("响应过大（${text.length} 字符 > $MAX_RAW_CHARS）")
                    }
                    if (index > 0) {
                        AppLogger.i(TAG, "赞助名单第 ${index + 1}/${candidates.size} 个候选成功: $url")
                    }
                    return text
                }
                "不可达"
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            errors += "[${index + 1}] $url → $failure"
        }
        throw Exception("赞助名单全部 ${candidates.size} 个候选都失败：\n" + errors.joinToString("\n"))
    }

    /**
     * 从 [AppSettings.updateUrl]（指向仓库根 `version.json`）推导 `sponsors.json` 的地址。
     *
     * 规则：**同目录**的 `sponsors.json` —— 即把最后一段路径换掉，其余（scheme / host / port /
     * 目录 / 基础路径）原样保留。用 `URI.resolve` 而不是字符串拼接，是为了让
     * `.../main/version.json`、`.../main/`、`https://host`（无路径）这几种写法都得到正确结果，
     * 且不把 `version.json` 后面的查询串粘到新文件名上。
     *
     * 这样镜像与降级**不需要任何额外工作**：拿到的还是同一个 GitHub 仓库的地址，
     * [MirrorResolver] 照常识别域名并拼前缀。
     *
     * @return 推导失败（`update_url` 为空 / 不是合法 URI）返回 null —— 调用方按"跳过本次刷新"处理
     */
    private fun deriveSponsorsUrl(updateUrl: String): String? {
        val trimmed = updateUrl.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { URI(trimmed).resolve(SPONSORS_FILE).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 原子写缓存：先写 `.tmp` 再 rename。
     *
     * 必须原子：进程在写一半时被杀（core 有自更新、会被 watchdog 重启）会留下半份 JSON，
     * 而"半份"在下次启动时是**解析失败**，等于白白丢掉一层容错。写法与 UpdateManager
     * 的 pending 文件、[com.ufi_axis_core.util.WebResourceManager] 的 installFromZip 一致。
     *
     * 写失败只打 WARN：本次内存快照已经换好了，端点照样给出新名单，只是这次重启后拿不到它。
     */
    private fun writeCache(raw: String) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(raw)
            if (!tmp.renameTo(cacheFile)) {
                tmp.delete()
                error("缓存文件 rename 失败")
            }
        }.onFailure {
            AppLogger.w(TAG, "赞助名单落盘失败（不影响本次内存名单）：${it.message}")
        }
    }

    // ── 校验（不合规 = 整份丢弃，见类注释） ──

    /**
     * 校验一份 `sponsors.json` 文本并产出快照。
     *
     * 逐条规则（**任何一条不满足都整份丢弃**，不是"跳过这一条"）：
     * - 顶层必须是 JSON 对象，且 `version` == [SUPPORTED_VERSION]（缺失也算不过：
     *   "没写版本"和"写了别的版本"在兼容性上是一回事，放行等于把未来格式当现在格式用）；
     * - 顶层必须有 `sponsors` 数组；
     * - 每条**恰好**有 `name`（非空、trim 后 ≤ [NAME_MAX] 字）或 `anonymous: true` 之一；
     * - 每条 `at` 必填且形如 `YYYY-MM` / `YYYY-MM-DD`。
     *
     * `updatedAt` 是**可选**的（缺失不算不合规，响应里给 null）：它只用于展示"更新于 X"，
     * 为它拒掉一整份好名单不划算。
     *
     * 为什么整份丢弃而不是逐条过滤：名单是**一份**事实（某次发布的产物）。逐条过滤会把
     * "远端给了一份带错别字的新名单"变成"新旧混合的名单"，而且没有任何日志之外的信号 ——
     * 整份丢弃 + 一条带下标的 WARN 才能让人一眼看出是第几条坏、坏在哪。
     *
     * @throws IllegalArgumentException 不合规，message 是给人看的定位信息
     */
    private fun validate(raw: String, source: String): Snapshot {
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("不是合法 JSON（${e.message}）")
        } ?: throw IllegalArgumentException("顶层不是 JSON 对象")

        val versionRaw = (root["version"] as? JsonPrimitive)?.contentOrNull
        val version = versionRaw?.trim()?.toIntOrNull()
        if (version != SUPPORTED_VERSION) {
            throw IllegalArgumentException(
                "version 必须为 $SUPPORTED_VERSION（实际=${versionRaw ?: "缺失"}）"
            )
        }

        val sponsors = root["sponsors"] as? JsonArray
            ?: throw IllegalArgumentException("缺少 sponsors 数组")
        sponsors.forEachIndexed { index, element ->
            val entry = element as? JsonObject
                ?: throw IllegalArgumentException("第 ${index + 1} 条不是 JSON 对象")
            validateEntry(entry, index)
        }

        val updatedAt = (root["updatedAt"] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return Snapshot(source, updatedAt, sponsors)
    }

    /** 单条校验，规则见 [validate]。`index` 只用于日志定位（0 基，报错时 +1）。 */
    private fun validateEntry(entry: JsonObject, index: Int) {
        val pos = "第 ${index + 1} 条"
        val name = (entry["name"] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.contentOrNull
            ?.trim()
        val anonymous = (entry["anonymous"] as? JsonPrimitive)?.booleanOrNull == true
        val at = (entry["at"] as? JsonPrimitive)?.contentOrNull?.trim()

        if (name != null) {
            require(name.isNotEmpty()) { "$pos 的 name 为空" }
            require(name.length <= NAME_MAX) { "$pos 的 name 超过 $NAME_MAX 字" }
            // 互斥是硬规则：同时给了 name 与 anonymous 时"这条到底算不算实名"没有答案，
            // 任一取舍都会让客户端计数与渲染对不上。
            require(!anonymous) { "$pos 同时给了 name 与 anonymous（互斥）" }
        } else {
            require(anonymous) { "$pos 既没有 name 也没有 anonymous:true" }
        }

        require(at != null && AT_REGEX.matches(at)) {
            "$pos 的 at 缺失或格式非法（需要 YYYY-MM 或 YYYY-MM-DD，实际=${at ?: "缺失"}）"
        }
    }

    // ── 工具（与 UpdateManager / WebUpdateManager 的同名私有函数逐字同源） ──
    //
    // 为什么是复制而不是共用：这两个函数在那两个类里都是 `private`，要共用就得把它们
    // 提成 `internal` —— 那是改动更新链路（本任务明确不许动的文件）的公开面。
    // 逻辑只有十几行且已经稳定（HTTPS 强制 + disconnect 收尾），复制的代价小于改动它。

    /** GET URL 返回文本；失败回 null（不抛，交给逐候选循环统计原因）。 */
    private fun fetchUrl(urlStr: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
                instanceFollowRedirects = true
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "拉取赞助名单失败: ${e.message}")
            null
        } finally {
            // disconnect 必须在 finally：异常路径漏掉会留下 keep-alive 连接（2026-10-05 R3-6 同款修复）
            conn?.disconnect()
        }
    }

    /** 强制 HTTPS（本地调试 IP 例外），与更新链路同一口径 —— 名单内容会被展示给用户，不该走明文。 */
    private fun requireSecureUrl(urlStr: String) {
        val u = try {
            URL(urlStr)
        } catch (e: Exception) {
            throw Exception("赞助名单地址不是合法 URL: $urlStr")
        }
        if (u.protocol.equals("https", ignoreCase = true)) return
        val host = u.host ?: ""
        val isLocalDebug = host == "localhost" || host == "127.0.0.1" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            host.startsWith("172.16.") || host.startsWith("172.17.") ||
            host.startsWith("172.18.") || host.startsWith("172.19.") ||
            host.startsWith("172.2") || host.startsWith("172.3") ||
            host.endsWith(".local")
        if (!isLocalDebug) {
            throw Exception("赞助名单地址必须使用 HTTPS（当前协议: ${u.protocol}）")
        }
    }

    companion object {
        private const val TAG = "SponsorRoutes"

        /** assets 里的文件名（由 `core/api/build.gradle.kts` 的 `copySponsorsJson` 生成）。 */
        const val ASSET_NAME = "sponsors.json"

        /** 当前支持的名单格式版本。远端 `version` 必须**恰好**等于它，见 [validate]。 */
        const val SUPPORTED_VERSION = 1

        /** 实名条目名长度上限（trim 后，按 Kotlin `String.length` 即 UTF-16 单元计）。 */
        const val NAME_MAX = 32

        /** 落盘缓存：`filesDir/sponsors/sponsors.json`。 */
        private const val CACHE_DIR = "sponsors"
        private const val CACHE_FILE = "sponsors.json"

        /** 远端文件名。与 `version.json` 同目录、同仓库。 */
        private const val SPONSORS_FILE = "sponsors.json"

        /** 刷新周期：12 小时。名单是"人"级别的变更频率，比这更勤只是白耗流量与电。 */
        private const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L

        /** 单候选超时。与 UpdateManager 的后台拉取同档（这是后台任务，不必压到前端等待档的 8s）。 */
        private const val TIMEOUT_MS = 15_000

        /**
         * 响应体上限（字符数，约 768KB UTF-8）。
         *
         * 取这么宽：正常名单（哪怕上千条）也就几十 KB，而闸门只防"把设备读爆"这一件事，
         * 不该有任何把正常名单误判成超限的风险。
         */
        private const val MAX_RAW_CHARS = 256 * 1024

        /** `YYYY-MM` 或 `YYYY-MM-DD`。月份/日期做范围校验，避免 `2026-13-99` 这种"形状对、语义错"的值混进来。 */
        private val AT_REGEX = Regex("""^\d{4}-(0[1-9]|1[0-2])(-(0[1-9]|[12]\d|3[01]))?$""")

        /** 数据来自构建期打进 assets 的保底那份。 */
        const val SOURCE_BUNDLED = "bundled"

        /** 数据来自远端拉取（含它的落盘缓存）。 */
        const val SOURCE_REMOTE = "remote"

        private val EMPTY_SNAPSHOT = Snapshot(SOURCE_BUNDLED, null, JsonArray(emptyList()))
    }
}
