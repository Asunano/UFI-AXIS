// TODO(F25): God-class 计划内拆分（DownloadManager 1094 / GoformClient 615 / Aria2Engine 577 / BackendService 570 / NetworkModule 921 / FileManagerScreen 879）。本类仅做最小安全抽取（见 GoformCodec），全量拆分需人工评审 + 编译验证。
package com.ufi_axis_core.controller.system

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.AssetExtractor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * aria2c 引擎管理器
 *
 * 以非 daemon 前台模式启动 aria2c（由 Java Process 管理生命周期），
 * 通过 JSON-RPC 控制下载。
 * 支持协议: HTTP, HTTPS, FTP, SFTP, magnet, torrent, metalink
 *
 * 懒加载策略:
 * - 程序启动时: probeVersion() 获取版本信息后关闭
 * - 有下载任务时: start() 启动引擎
 * - 所有任务完成后: 可选关闭以释放资源
 */
class Aria2Engine(
    private val appContext: android.content.Context,
    private val rpcPort: Int = 6801,
    private val rpcSecret: String = AppSettings.getInstance(appContext).aria2RpcSecret
) {
    companion object {
        private const val TAG = "Aria2Engine"
        private const val READY_TIMEOUT_MS = 15_000L
    }

    /**
     * 添加下载的结果。之所以不能只返回 `String?`：aria2 对"同一个 infoHash 已经在下载中"
     * 回的是 RPC error（`InfoHash <hash> is already registered.`），和"真的提交失败"用同一个
     * null 表示的话，调用方只能一遍遍重试，任务永远停在报错状态 —— 实际上该做的是去认领
     * 已经存在的那个 GID。
     */
    sealed class AddResult {
        data class Ok(val gid: String) : AddResult()
        /** aria2 里已经有同一个 infoHash / URI 的下载，[infoHash] 取自错误文本（可能为 null） */
        data class Duplicate(val infoHash: String?) : AddResult()
        data class Failed(val message: String?) : AddResult()
    }


    private var process: Process? = null
    @Volatile private var running = false
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    /** 等待 RPC 就绪的协程 Job，stop() 时取消防止泄漏 */
    private var rpcReadyJob: Job? = null

    /** 缓存的版本号 */
    var cachedVersion: String? = null
        private set

    /** aria2c 二进制路径 */
    private val binaryPath: String
        get() = AssetExtractor.getPath(appContext, "aria2c")

    /** JSON-RPC endpoint */
    private val rpcUrl: String
        get() = "http://127.0.0.1:$rpcPort/jsonrpc"

    // ─── 生命周期 ──────────────────────────────────────────

    /**
     * 检查 aria2 RPC 是否可达
     * 不仅检查本进程的状态标记，还探测端口上是否有 aria2 实例（可能是上一次运行遗留的）
     */
    fun isRunning(): Boolean {
        // 快速路径：本进程启动的实例且标记为 running
        if (running && process?.isAlive == true) {
            // 二次确认 RPC 可达
            return try { getVersion() != null } catch (_: Exception) { false }
        }
        // 慢路径：本进程没有启动 aria2，但端口上可能有旧实例
        // （比如上次 app 运行期间 start() 的进程还活着）
        return try {
            if (isPortInUse()) {
                getVersion() != null
            } else false
        } catch (_: Exception) { false }
    }

    /**
     * 启动时探测版本信息（启动 → 获取版本 → 停止）
     * 用于 UI 显示 aria2 版本，不长期占用进程
     */
    fun probeVersion(): String? {
        if (cachedVersion != null) return cachedVersion

        val binary = File(binaryPath)
        if (!binary.exists()) {
            AppLogger.w(TAG, "aria2c binary not found at $binaryPath")
            return null
        }

        // 尝试通过 --version 命令行获取版本
        return try {
            val pb = ProcessBuilder(binary.absolutePath, "--version")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            // 必须先 waitFor 再读：readText() 一路读到 EOF，二进制损坏/被 stop 住时永不返回，
            // 后面那句 waitFor(5s) 就永远执行不到 —— 这条调用在组件图第 12 步（DownloadManager
            // 构造）上，卡住的表现就是 Core 通知栏一直停在"正在初始化组件..."。
            // --version 输出只有几百字节，远小于管道缓冲区，先等退出不会死锁。
            val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val output = if (exited) {
                runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")
            } else {
                AppLogger.w(TAG, "aria2c --version timed out after 5s, killing probe")
                ""
            }
            proc.destroyForcibly()
            // aria2c 1.37.0 ...
            val version = output.lineSequence().firstOrNull()
                ?.substringAfter("aria2c ")?.substringBefore(" ")?.trim()
            if (version != null && version.isNotEmpty()) {
                cachedVersion = version
                AppLogger.i(TAG, "aria2 version probed: $version")
            }
            version
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to probe aria2 version: ${e.message}")
            null
        }
    }

    /**
     * 启动 aria2c 引擎（长期运行）
     *
     * 注意: 不使用 ProcessBuilder.Redirect，因为某些 Android 设备上
     * redirectOutput/Redirect.appendTo 会导致进程异常或管道阻塞。
     * 改为后台守护线程手动排空 stdout/stderr。
     */
    fun start(config: DownloadManager.DownloadConfig) {
        if (isRunning()) return

        // 先杀死可能残留的 aria2c 进程（来自旧版 daemon=true 模式）
        killLingeringProcess()

        val binary = File(binaryPath)
        if (!binary.exists()) {
            AppLogger.e(TAG, "aria2c binary not found at $binaryPath")
            return
        }

        val aria2Dir = File(appContext.filesDir, "aria2")
        aria2Dir.mkdirs()
        val sessionFile = File(aria2Dir, "session.dat")
        val configFile = File(aria2Dir, "aria2.conf")
        val logFile = File(aria2Dir, "aria2.log")
        val trackerFile = File(aria2Dir, "bt-trackers.txt")

        // 确保保存目录存在
        File(config.saveDir).mkdirs()

        // Write config file — 所有值从 DownloadConfig 读取
        configFile.writeText(buildString {
            // RPC
            appendLine("enable-rpc=true")
            appendLine("rpc-listen-port=$rpcPort")
            appendLine("rpc-secret=$rpcSecret")
            appendLine("rpc-listen-all=false")
            appendLine("rpc-allow-origin-all=true")
            // 基本
            appendLine("dir=${config.saveDir}")
            appendLine("continue=true")
            appendLine("input-file=${sessionFile.absolutePath}")
            appendLine("save-session=${sessionFile.absolutePath}")
            appendLine("save-session-interval=30")
            appendLine("max-concurrent-downloads=${config.maxConcurrent}")
            appendLine("max-connection-per-server=${config.maxConnectionsPerServer}")
            appendLine("min-split-size=1M")
            appendLine("split=${config.splitCount}")
            appendLine("file-allocation=none")
            // 速度
            if (config.globalSpeedLimit > 0) appendLine("max-overall-download-limit=${config.globalSpeedLimit}")
            if (config.maxOverallUploadLimit > 0) appendLine("max-overall-upload-limit=${config.maxOverallUploadLimit}")
            // BT
            appendLine("bt-seed-unverified=true")
            appendLine("bt-save-metadata=true")
            appendLine("bt-hash-check-seed=true")
            appendLine("seed-ratio=${config.btSeedRatio}")
            appendLine("bt-max-peers=${config.btMaxPeers}")
            appendLine("bt-tracker-connect-timeout=60")
            appendLine("bt-max-open-files=100")
            appendLine("bt-enable-lpd=${config.btEnableLpd}")
            appendLine("enable-peer-exchange=true")
            appendLine("listen-port=6881-6999")
            appendLine("enable-dht=${config.btEnableDht}")
            appendLine("dht-listen-port=6881-6999")
            // DHT 路由表必须落到应用私有目录：不写 dht-file-path 时 aria2 用 $HOME/.aria2/dht.dat，
            // 而 Android 上 HOME 指向不可写路径 —— 路由表既读不出也存不下，每次启动 DHT 都是空表。
            appendLine("dht-file-path=${File(aria2Dir, "dht.dat").absolutePath}")
            // 空路由表只能靠 dht-entry-point 引导，而这一项 aria2 默认是空的。
            // 没有引导节点 + 没有 tracker（见下）＝ 磁链永远停在 metadata 阶段。
            appendLine("dht-entry-point=router.bittorrent.com:6881")
            appendLine("follow-torrent=mem")
            // BT Tracker：优先用 TrackerManager 的缓存，缓存缺失/为空时退回内置列表。
            // 此前只在缓存文件存在时才写 bt-tracker —— 首次安装（还没拉到列表）或拉取失败时
            // aria2 一条 tracker 都没有，磁链只能指望 DHT，于是卡在"获取种子信息中"。
            val trackers = trackerFile.takeIf { it.exists() }?.readText()?.trim()
                ?.takeIf { it.isNotBlank() } ?: TrackerManager.BUILTIN_TRACKERS
            appendLine("bt-tracker=$trackers")
            // 网络
            appendLine("disable-ipv6=${config.disableIpv6}")
            appendLine("check-certificate=${config.checkCertificate}")
            // 仅在开启证书校验时指定 CA bundle（Android 上 aria2 的 OpenSSL 可能找不到默认 CA store）。
            // 关闭校验时不设置 ca-certificate，避免 aria2 TLS 库加载不兼容的 CA 文件导致异常。
            if (config.checkCertificate) {
                for (caPath in listOf(
                    "/etc/ssl/certs/ca-certificates.crt",
                    "/system/etc/security/cacerts.pem"
                )) {
                    if (java.io.File(caPath).canRead()) {
                        appendLine("ca-certificate=$caPath")
                        break
                    }
                }
            }
            // DNS: async-dns=true 让 aria2 自己发 DNS 查询（绕开 Android Bionic/musl getaddrinfo
            // 在无 /etc/resolv.conf 或 netd 不通时返回 "No address associated with hostname" 的问题）。
            // 使用国内可靠可达的 DNS 服务器，避免 8.8.8.8/1.1.1.1 在墙内被拦截。
            appendLine("async-dns=true")
            appendLine("async-dns-server=223.5.5.5,119.29.29.29,114.114.114.114")
            appendLine("dns-cache-timeout=600")
            appendLine("max-tries=${config.maxTries}")
            appendLine("retry-wait=${config.retryWait}")
            appendLine("connect-timeout=15")
            appendLine("timeout=30")
            // 日志
            appendLine("log=${logFile.absolutePath}")
            appendLine("log-level=notice")
        })

        // Create session file if not exists
        if (!sessionFile.exists()) sessionFile.createNewFile()

        try {
            // 不使用 redirectOutput —— 在某些 Android 设备上会导致进程/管道异常
            val pb = ProcessBuilder(binary.absolutePath, "--conf-path=${configFile.absolutePath}")
            pb.directory(aria2Dir)
            pb.redirectErrorStream(true)
            // 注意: 不调用 pb.redirectOutput()，保持 PIPE 模式

            process = pb.start()
            running = true
            AppLogger.i(TAG, "aria2c process started")

            // 守护线程排空 stdout（防止管道缓冲区满导致进程阻塞）
            // 同时将 aria2 的输出转发到 AppLogger 便于调试
            val procRef = process
            if (procRef != null) {
                Thread({
                    try {
                        val reader = procRef.inputStream.bufferedReader()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            AppLogger.d("aria2-stdout", line!!)
                        }
                    } catch (_: Exception) {
                        // 进程结束时会抛 IOException，正常现象
                    }
                    AppLogger.d(TAG, "aria2 stdout stream ended")
                }, "aria2-stdout-drainer").apply {
                    isDaemon = true
                    start()
                }
            }

            // 等待 RPC 就绪（在 IO 协程中，包含详细的诊断日志）
            // 注意：使用成员变量 rpcReadyJob 管理，stop() 时取消，防止 scope 泄漏
            rpcReadyJob?.cancel()
            rpcReadyJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val ready = waitForRpc()
                if (ready) {
                    cachedVersion = getVersion()
                    AppLogger.i(TAG, "aria2c RPC ready, version=$cachedVersion")
                } else {
                    AppLogger.e(TAG, "aria2c RPC failed to become ready after ${READY_TIMEOUT_MS}ms")
                    // 进程是否还活着？
                    val alive = process?.isAlive ?: false
                    AppLogger.e(TAG, "aria2c process alive=$alive")
                    // 读取 aria2 自己的日志帮助诊断
                    if (logFile.exists()) {
                        val lastLines = logFile.readLines().takeLast(15).joinToString("\n")
                        AppLogger.e(TAG, "aria2 log tail:\n$lastLines")
                    }
                    running = false
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start aria2c: ${e.javaClass.simpleName}: ${e.message}", e)
            running = false
        }
    }

    fun stop() {
        if (!running && process == null) return
        // 取消等待 RPC 就绪的协程，防止 scope 泄漏
        rpcReadyJob?.cancel()
        rpcReadyJob = null
        try {
            // 先通过 RPC 通知 aria2 优雅关闭
            rpcCall("aria2.shutdown", emptyList())
        } catch (_: Exception) {}
        try {
            process?.destroy()
            val exited = process?.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) ?: true
            if (!exited) {
                process?.destroyForcibly()
            }
        } catch (_: Exception) {}
        process = null
        running = false
        AppLogger.i(TAG, "aria2c stopped")
    }

    /**
     * 杀死所有残留的 aria2c 进程（解决端口占用问题）
     * 策略: RPC 优雅关闭 → fuser/lsof 端口杀进程 → pkill 兜底
     */
    private fun killLingeringProcess() {
        // 1) 尝试通过 RPC 通知已运行的 aria2 关闭
        try {
            AppLogger.d(TAG, "Attempting RPC shutdown of any existing aria2...")
            rpcCall("aria2.shutdown", emptyList())
            Thread.sleep(1000)
            // 如果 RPC 关闭成功，端口应该已释放
            if (!isPortInUse()) {
                AppLogger.i(TAG, "aria2 shut down via RPC successfully")
                return
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "RPC shutdown attempt: ${e.message}")
        }

        // 2) 用 fuser 找到占用端口的进程并杀掉
        try {
            AppLogger.d(TAG, "Trying fuser to kill process on port $rpcPort...")
            val fuser = ProcessBuilder("fuser", "-k", "$rpcPort/tcp")
                .redirectErrorStream(true).start()
            val fuserOutput = fuser.inputStream.bufferedReader().readText()
            fuser.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            fuser.destroyForcibly()
            AppLogger.d(TAG, "fuser output: $fuserOutput")
            Thread.sleep(500)
            if (!isPortInUse()) {
                AppLogger.i(TAG, "Port $rpcPort freed via fuser")
                return
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "fuser attempt: ${e.message}")
        }

        // 3) pkill/killall 兜底
        for (cmd in listOf(
            listOf("pkill", "-9", "-f", "aria2c"),
            listOf("killall", "-9", "aria2c")
        )) {
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                p.destroyForcibly()
            } catch (_: Exception) {}
        }

        Thread.sleep(500)
        AppLogger.i(TAG, "Lingering aria2c cleanup completed (port free: ${!isPortInUse()})")
    }

    /**
     * 检查 RPC 端口是否被占用（通过尝试 TCP 连接）
     */
    private fun isPortInUse(): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", rpcPort), 500)
            socket.close()
            true  // 能连接 = 端口被占用
        } catch (_: Exception) {
            false // 连接失败 = 端口空闲
        }
    }

    // ─── 下载操作 ──────────────────────────────────────────

    fun addUri(
        uris: List<String>,
        dir: String? = null,
        fileName: String? = null,
        maxConnPerServer: Int? = null,
        speedLimit: Long? = null  // bytes/s, 0 = unlimited
    ): String? = (addUriDetailed(uris, dir, fileName, maxConnPerServer, speedLimit) as? AddResult.Ok)?.gid

    fun addUriDetailed(
        uris: List<String>,
        dir: String? = null,
        fileName: String? = null,
        maxConnPerServer: Int? = null,
        speedLimit: Long? = null  // bytes/s, 0 = unlimited
    ): AddResult {
        val options = mutableMapOf<String, String>()
        dir?.let { options["dir"] = it }
        fileName?.let { options["out"] = it }
        maxConnPerServer?.let { options["max-connection-per-server"] = it.toString() }
        speedLimit?.let { if (it > 0) options["max-download-limit"] = "${it}" }

        val params = mutableListOf<JsonElement>()
        params.add(buildJsonArray { uris.forEach { add(JsonPrimitive(it)) } })
        if (options.isNotEmpty()) {
            params.add(buildJsonObject {
                options.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
        }

        return toAddResult(rpcCallDetailed("aria2.addUri", params))
    }

    /**
     * 用本地 .torrent 文件建下载。
     *
     * 参数顺序必须是 (torrent, uris, options)：aria2 的签名是
     * `aria2.addTorrent(secret, torrent, uris, options, position)`，中间那个 uris 数组
     * 不能省 —— 直接把 options 放到 torrent 后面会被当成 uris，aria2 回
     * "The parameter at 1 has wrong type."（这条调用此前一直是坏的，没有调用方所以没暴露）。
     */
    fun addTorrent(torrentPath: String, dir: String? = null): String? =
        (addTorrentDetailed(torrentPath, dir) as? AddResult.Ok)?.gid

    fun addTorrentDetailed(torrentPath: String, dir: String? = null): AddResult {
        // readBytes 必须在 try/catch 内：路径不存在/无读权限时应返回 Failed 而非抛出 IOException
        val b64 = runCatching {
            val bytes = File(torrentPath).readBytes()
            java.util.Base64.getEncoder().encodeToString(bytes)
        }.getOrNull() ?: return AddResult.Failed("无法读取种子文件 $torrentPath")
        val params = mutableListOf<JsonElement>(JsonPrimitive(b64), buildJsonArray { })
        if (dir != null) {
            params.add(buildJsonObject { put("dir", JsonPrimitive(dir)) })
        }
        return toAddResult(rpcCallDetailed("aria2.addTorrent", params))
    }

    /** 把 RPC 结果翻译成 [AddResult]，识别 aria2 的"已注册"错误 */
    private fun toAddResult(rpc: Pair<JsonElement?, String?>): AddResult {
        val (result, errorMessage) = rpc
        val gid = result?.jsonPrimitive?.contentOrNull
        if (gid != null) return AddResult.Ok(gid)
        if (errorMessage != null && errorMessage.contains("already registered", ignoreCase = true)) {
            val hash = Regex("([0-9a-fA-F]{40})").find(errorMessage)?.groupValues?.get(1)?.lowercase()
            return AddResult.Duplicate(hash)
        }
        return AddResult.Failed(errorMessage)
    }

    fun tellStatus(gid: String): JsonObject? {
        val result = rpcCall("aria2.tellStatus", listOf(JsonPrimitive(gid)))
        return result?.jsonObject
    }

    fun tellActive(): List<JsonObject> {
        val result = rpcCall("aria2.tellActive", emptyList())
        return result?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    fun tellWaiting(offset: Int = 0, num: Int = 50): List<JsonObject> {
        val result = rpcCall("aria2.tellWaiting", listOf(
            JsonPrimitive(offset), JsonPrimitive(num)
        ))
        return result?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    fun tellStopped(offset: Int = 0, num: Int = 50): List<JsonObject> {
        val result = rpcCall("aria2.tellStopped", listOf(
            JsonPrimitive(offset), JsonPrimitive(num)
        ))
        return result?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    fun pause(gid: String): Boolean {
        val result = rpcCall("aria2.pause", listOf(JsonPrimitive(gid)))
        return result != null
    }

    fun unpause(gid: String): Boolean {
        val result = rpcCall("aria2.unpause", listOf(JsonPrimitive(gid)))
        return result != null
    }

    fun forcePauseAll(): Boolean {
        val result = rpcCall("aria2.forcePauseAll", emptyList())
        return result != null
    }

    fun unpauseAll(): Boolean {
        val result = rpcCall("aria2.unpauseAll", emptyList())
        return result != null
    }

    fun remove(gid: String): Boolean {
        val result = rpcCall("aria2.forceRemove", listOf(JsonPrimitive(gid)))
        return result != null
    }

    fun removeDownloadResult(gid: String): Boolean {
        val result = rpcCall("aria2.removeDownloadResult", listOf(JsonPrimitive(gid)))
        return result != null
    }

    /**
     * 热更新全局选项。只能放 aria2 changeGlobalOption 支持的项 ——
     * split / max-connection-per-server / enable-dht / disable-ipv6 等属于启动期选项，
     * 塞进来 aria2 会整个调用报错，那些只能靠重写 conf + 重启进程生效。
     */
    fun changeGlobalSetting(
        maxConcurrentDownloads: Int? = null,
        maxOverallDownloadLimit: Long? = null,
        maxOverallUploadLimit: Long? = null,
        btMaxPeers: Int? = null
    ) {
        val options = buildJsonObject {
            maxConcurrentDownloads?.let { put("max-concurrent-downloads", JsonPrimitive(it.toString())) }
            maxOverallDownloadLimit?.let {
                put("max-overall-download-limit", JsonPrimitive(if (it > 0) "${it}" else "0"))
            }
            maxOverallUploadLimit?.let {
                put("max-overall-upload-limit", JsonPrimitive(if (it > 0) "${it}" else "0"))
            }
            btMaxPeers?.let { put("bt-max-peers", JsonPrimitive(it.toString())) }
        }
        rpcCall("aria2.changeGlobalOption", listOf(options))
    }

    /** 热更新 BT Tracker 列表（aria2 运行中无需重启） */
    fun changeBtTracker(trackers: String) {
        val options = buildJsonObject {
            put("bt-tracker", JsonPrimitive(trackers))
        }
        rpcCall("aria2.changeGlobalOption", listOf(options))
    }

    fun getGlobalStat(): JsonObject? {
        val result = rpcCall("aria2.getGlobalStat", emptyList())
        return result?.jsonObject
    }

    fun getVersion(): String? {
        val result = rpcCall("aria2.getVersion", emptyList())
        return result?.jsonObject?.get("version")?.jsonPrimitive?.contentOrNull
    }

    // ─── JSON-RPC 通信 ────────────────────────────────────

    private fun rpcCall(method: String, params: List<JsonElement>): JsonElement? =
        rpcCallDetailed(method, params).first

    /**
     * 同 [rpcCall]，但把 aria2 回的 error.message 一起交出来（`result to errorMessage`）。
     * 只记日志、对调用方一律返回 null 的话，"已注册"这类可自愈的错误就无法区分。
     */
    private fun rpcCallDetailed(method: String, params: List<JsonElement>): Pair<JsonElement?, String?> {
        return try {
            val fullParams = mutableListOf<JsonElement>()
            fullParams.add(JsonPrimitive("token:$rpcSecret"))
            fullParams.addAll(params)

            val requestBody = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(System.currentTimeMillis().toString()))
                put("method", JsonPrimitive(method))
                put("params", buildJsonArray { fullParams.forEach { add(it) } })
            }

            val conn = URL(rpcUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json")

            OutputStreamWriter(conn.outputStream).use {
                it.write(requestBody.toString())
            }

            val code = conn.responseCode
            if (code != 200) {
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                AppLogger.w(TAG, "RPC $method HTTP $code body=$errBody")
                conn.disconnect()
                // aria2 把 addUri/addTorrent 的业务错误也用 HTTP 400 + JSON-RPC error 回，
                // 所以这里必须把 message 解出来，不能只当成传输失败。
                return null to (errBody?.let { extractRpcErrorMessage(it) } ?: "HTTP $code")
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val response = json.parseToJsonElement(responseText).jsonObject
            response["error"]?.let { error ->
                val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                AppLogger.w(TAG, "RPC $method error: $message")
                return null to message
            }
            response["result"] to null
        } catch (e: Exception) {
            // 所有方法都记录详细异常信息（之前 shutdown/getVersion 被静默吞掉导致无法诊断）
            AppLogger.w(TAG, "RPC $method failed: ${e.javaClass.simpleName}: ${e.message}")
            null to "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** 从 HTTP 400 的响应体里取 JSON-RPC 的 error.message；解不出来就原样返回 */
    private fun extractRpcErrorMessage(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]
            ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body.take(200)

    private suspend fun waitForRpc(): Boolean {
        val start = System.currentTimeMillis()
        var tcpConnected = false

        // 阶段 1: 等待 TCP 端口可达（最多 8 秒）
        while (System.currentTimeMillis() - start < 8000) {
            val alive = process?.isAlive ?: false
            if (isPortInUse()) {
                val elapsed = System.currentTimeMillis() - start
                AppLogger.i(TAG, "TCP port $rpcPort reachable after ${elapsed}ms (process alive=$alive)")
                tcpConnected = true
                break
            }
            if (!alive) {
                AppLogger.e(TAG, "aria2c process died before RPC port became available")
                return false
            }
            AppLogger.d(TAG, "Waiting for TCP port $rpcPort... (alive=$alive, elapsed=${System.currentTimeMillis()-start}ms)")
            delay(500)
        }

        if (!tcpConnected) {
            AppLogger.e(TAG, "TCP port $rpcPort not reachable after 8s")
            return false
        }

        // 阶段 2: 等待 JSON-RPC 响应（再给 7 秒）
        val rpcDeadline = READY_TIMEOUT_MS
        while (System.currentTimeMillis() - start < rpcDeadline) {
            try {
                val ver = getVersion()
                if (ver != null) {
                    AppLogger.i(TAG, "JSON-RPC responded after ${System.currentTimeMillis()-start}ms")
                    return true
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "JSON-RPC probe exception: ${e.javaClass.simpleName}: ${e.message}")
            }
            delay(500)
        }

        AppLogger.e(TAG, "TCP connected but JSON-RPC not responding after ${System.currentTimeMillis()-start}ms")
        return false
    }

}
