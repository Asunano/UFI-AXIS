package com.ufi_axis_core.api.files

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 「core 暂存文件 → 远端存储源」的推送作业表。
 *
 * ## 为什么要有第二阶段
 * 远端上传（FTP / WebDAV / SMB / S3）**不能**直接复用分片上传那套：分片方案靠
 * 「追加写 `.ufipart` + 同目录 rename」实现续传，而这四个协议都没有「往文件中间某个
 * 偏移补一块」的原语。所以链路拆成两段：
 *
 *  1. **手机 → core**：完全复用既有的 `upload/session|chunk|complete`（可断点续传），
 *     只是 `.ufipart` 落在 core 的**暂存目录**而不是目标目录（目标在别的机器上）；
 *  2. **core → 远端**：整份流式 `provider.uploadStream`，由本类作为后台作业执行。
 *
 * 第二阶段**必须是可见、可管理的**：它发生在用户按下"上传完成"之后，如果只有第一段有
 * 进度条，用户会以为文件已经在远端了，而实际上可能还在排队、甚至已经失败。所以这里
 * 维护完整的作业状态（排队/推送中/成功/失败/已取消 + 字节进度 + 失败原因），
 * 并支持取消、重试、清除，由 `/api/files/remote-push*` 暴露给客户端轮询。
 *
 * ## 三条设计取舍
 *
 * ### 1. 串行推送
 * 所有作业共用一把 [pushMutex]。理由：远端连接本身就是串行的（`FtpFileProvider`
 * 内部有 Mutex、SMB 单 share），并发推送只会互相抢上行带宽并放大超时。
 * 排队中的作业状态是 [State.QUEUED]，客户端能看到"前面还有几个"。
 *
 * ### 2. 失败保留暂存文件，成功/取消才删
 * 失败的作业留着暂存文件才能"重试"而不必让用户重新从手机传一遍（那是最贵的一段）。
 * 代价是占 core 的内部存储，由 [FINISHED_TTL_MS] 与 [purgeStaleStaging] 兜底。
 *
 * ### 3. 取消靠流上的标志位，不只靠协程取消
 * `provider.uploadStream` 内部是**阻塞 IO**（OkHttp / commons-net / smbj），
 * 协程 cancel 不会打断正在进行的 socket 写。所以 [CountingInputStream] 每次 read
 * 都检查 [PushJob.cancelRequested]，置位后直接抛 [IOException] 把阻塞写打断。
 *
 * ## 暂存目录的编码
 * `<stagingRoot>/<sourceId>/<base64url(远端相对目录)>/`。把远端目标**编码进路径**
 * 而不是另存一份映射表：这样 core 重启后，凭暂存文件的路径就能还原出"它该推到哪里"，
 * 与 [UploadSessionStore] 用文件名编码会话元数据是同一个思路 —— 不引入第二种持久化。
 */
class RemotePushManager(
    private val registry: FileProviderRegistry,
    /** 暂存根目录。由调用方给（core 路由层拿不到 Context，只能用 `java.io.tmpdir`）。 */
    private val stagingRoot: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    /** 作业状态。字符串形态直接回给客户端，两端必须同口径。 */
    enum class State { QUEUED, PUSHING, SUCCESS, FAILED, CANCELLED }

    /**
     * 一个推送作业。
     *
     * @param destPath 客户端形态的目标路径（`remote:<id>/dir/name`），用于 UI 显示与定位
     * @param stagedPath core 上的暂存文件绝对路径
     */
    class PushJob internal constructor(
        val id: String,
        val sourceId: String,
        val sourceLabel: String,
        val fileName: String,
        val relDir: String,
        val destPath: String,
        val totalBytes: Long,
        val stagedPath: String,
        val createdAt: Long
    ) {
        @Volatile
        var state: State = State.QUEUED
            internal set

        @Volatile
        var sentBytes: Long = 0L
            internal set

        @Volatile
        var error: String? = null
            internal set

        /**
         * 完整错误详情（异常类名 + 原始 message + 目标 + 时间点 + 暂存文件状态）。
         *
         * 与 [error] 的分工：[error] 是给提示条看的一句话，这一份是给**排查**看的 ——
         * 远端失败的原因往往藏在原始异常里（FTP 的 reply string、S3 的 XML error code、
         * SSL 握手细节），只留一句"上传失败"等于把唯一的线索丢掉，用户除了重试没有别的办法。
         * UI 侧提供"复制"按钮把这一段整体带走。
         */
        @Volatile
        var errorDetail: String? = null
            internal set

        @Volatile
        var finishedAt: Long = 0L
            internal set

        /** 用户已请求取消 —— [CountingInputStream] 靠它打断阻塞写。 */
        @Volatile internal var cancelRequested: Boolean = false
        @Volatile internal var handle: Job? = null

        val stagedFile: File get() = File(stagedPath)

        /** 0f..1f；总字节未知时为 -1f（不会发生，声明大小是必填的，留作防御）。 */
        val progress: Float
            get() = if (totalBytes > 0) (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f

        val active: Boolean get() = state == State.QUEUED || state == State.PUSHING

        /** 失败后还能重试吗 —— 暂存文件还在才行。 */
        val retryable: Boolean
            get() = (state == State.FAILED || state == State.CANCELLED) && stagedFile.isFile
    }

    private val jobs = ConcurrentHashMap<String, PushJob>()
    private val pushMutex = Mutex()
    private val random = SecureRandom()

    // ──────────────────────── 对外 API ────────────────────────

    /**
     * 某条暂存路径对应的目标目录。开会话时用它决定 `.ufipart` 落哪儿。
     *
     * @param relDir provider 认的相对目录（`/photos`）
     */
    fun stagingDirFor(sourceId: String, relDir: String): File {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(relDir.ifEmpty { "/" }.toByteArray())
        return File(File(stagingRoot, sourceId), encoded)
    }

    /**
     * 反解暂存目录 → (sourceId, relDir)。不是本管理器造的目录时返回 null。
     *
     * `complete` 靠它判断"这条会话是不是远端会话"，所以不需要在会话表里加字段。
     */
    fun parseStagingDir(dir: String): Pair<String, String>? {
        val d = File(dir)
        val encoded = d.name
        val sourceId = d.parentFile?.name ?: return null
        val root = d.parentFile?.parentFile ?: return null
        if (root.absolutePath != stagingRoot.absolutePath) return null
        if (sourceId.isEmpty() || encoded.isEmpty()) return null
        val relDir = runCatching {
            String(Base64.getUrlDecoder().decode(encoded))
        }.getOrNull() ?: return null
        return sourceId to relDir
    }

    /** 排一个推送作业并立刻开跑（真正的推送要等 [pushMutex]）。 */
    fun enqueue(
        sourceId: String,
        sourceLabel: String,
        fileName: String,
        relDir: String,
        destPath: String,
        stagedFile: File,
        totalBytes: Long
    ): PushJob {
        purgeFinished()
        val bytes = ByteArray(JOB_ID_BYTES)
        random.nextBytes(bytes)
        val job = PushJob(
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            sourceId = sourceId,
            sourceLabel = sourceLabel,
            fileName = fileName,
            relDir = relDir,
            destPath = destPath,
            totalBytes = totalBytes,
            stagedPath = stagedFile.absolutePath,
            createdAt = nowMs()
        )
        jobs[job.id] = job
        start(job)
        return job
    }

    fun get(id: String): PushJob? = jobs[id]

    /** 全部作业，新的在前。含完成/失败记录（[FINISHED_TTL_MS] 内）。 */
    fun list(): List<PushJob> {
        purgeFinished()
        return jobs.values.sortedByDescending { it.createdAt }
    }

    fun activeCount(): Int = jobs.values.count { it.active }

    /**
     * 取消：置标志位打断阻塞写 + cancel 协程 + 删暂存文件。
     *
     * 已经完成的作业不能"取消"（文件已经在远端了），返回 false 让客户端知道这次点击无效。
     */
    fun cancel(id: String): Boolean {
        val job = jobs[id] ?: return false
        if (!job.active) return false
        job.cancelRequested = true
        job.handle?.cancel()
        return true
    }

    /** 重试失败/已取消的作业。暂存文件已被清掉时返回 null（只能让用户重新上传）。 */
    fun retry(id: String): PushJob? {
        val job = jobs[id] ?: return null
        if (job.active) return job
        if (!job.stagedFile.isFile) return null
        job.cancelRequested = false
        start(job)
        return job
    }

    /** 清掉所有已结束的记录（连失败作业的暂存文件一起删）。返回清掉的条数。 */
    fun clearFinished(): Int {
        var n = 0
        val it = jobs.entries.iterator()
        while (it.hasNext()) {
            val job = it.next().value
            if (job.active) continue
            runCatching { job.stagedFile.delete() }
            it.remove()
            n++
        }
        return n
    }

    /**
     * 清理暂存目录里的孤儿文件（core 被杀 / 断电后残留的 `.ufipart` 与未推送的整文件）。
     *
     * 只按 mtime 判断，且跳过当前作业还引用着的文件 —— 与 `UploadSessionStore.isStalePart`
     * 同一个判据：core 重启后内存里什么都没有，文件时间是唯一还活着的活动痕迹。
     */
    fun purgeStaleStaging(ttlMs: Long = FINISHED_TTL_MS) {
        val referenced = jobs.values.map { it.stagedPath }.toSet()
        val now = nowMs()
        runCatching {
            stagingRoot.listFiles()?.forEach { sourceDir ->
                sourceDir.listFiles()?.forEach { destDir ->
                    destDir.listFiles()?.forEach { f ->
                        if (!f.isFile) return@forEach
                        if (f.absolutePath in referenced) return@forEach
                        val m = f.lastModified()
                        if (m <= 0L) return@forEach
                        if (m > now || now - m > ttlMs) f.delete()
                    }
                    if (destDir.isDirectory && destDir.listFiles()?.isEmpty() == true) destDir.delete()
                }
                if (sourceDir.isDirectory && sourceDir.listFiles()?.isEmpty() == true) sourceDir.delete()
            }
        }
    }

    /** 暂存占用的总字节（含在途 `.ufipart`），供配额判定。 */
    fun stagedBytes(): Long = runCatching {
        var sum = 0L
        stagingRoot.listFiles()?.forEach { sourceDir ->
            sourceDir.listFiles()?.forEach { destDir ->
                destDir.listFiles()?.forEach { f -> if (f.isFile) sum += f.length() }
            }
        }
        sum
    }.getOrDefault(0L)

    // ──────────────────────── 内部 ────────────────────────

    private fun start(job: PushJob) {
        job.state = State.QUEUED
        job.error = null
        job.sentBytes = 0L
        job.finishedAt = 0L
        job.handle = scope.launch {
            try {
                // 排队等上一个推完。状态停在 QUEUED，客户端据此显示"排队中"。
                pushMutex.withLock {
                    ensureActive()
                    if (job.cancelRequested) throw CancellationException("cancelled before start")
                    job.state = State.PUSHING
                    pushOnce(job)
                }
                job.sentBytes = job.totalBytes
                job.state = State.SUCCESS
                job.finishedAt = nowMs()
                // 成功后暂存文件没有任何用途，立刻删 —— 留着就是纯占空间
                runCatching { job.stagedFile.delete() }
            } catch (e: CancellationException) {
                job.state = State.CANCELLED
                job.error = null
                job.finishedAt = nowMs()
                runCatching { job.stagedFile.delete() }
                throw e
            } catch (e: Exception) {
                if (job.cancelRequested) {
                    // 取消是通过在流上抛 IOException 实现的，落到这里才是正常路径
                    job.state = State.CANCELLED
                    job.error = null
                    job.errorDetail = null
                    runCatching { job.stagedFile.delete() }
                } else {
                    job.state = State.FAILED
                    job.error = classifyError(e)
                    job.errorDetail = buildErrorDetail(job, e)
                    AppLogger.w(TAG, "推送到远端失败 ${job.destPath}: ${job.error}")
                }
                job.finishedAt = nowMs()
            }
        }
    }

    private suspend fun pushOnce(job: PushJob) {
        val provider = registry.get(job.sourceId)
            ?: throw FileProvider.ProviderException("存储源不存在或已被停用：${job.sourceId}")
        if (FileProvider.Capability.UPLOAD !in provider.capabilities) {
            throw FileProvider.ProviderException("该存储源不支持上传")
        }
        val staged = job.stagedFile
        if (!staged.isFile) {
            throw FileProvider.ProviderException("暂存文件已丢失，请重新上传")
        }
        val size = staged.length()
        val target = joinRemote(job.relDir, job.fileName)
        staged.inputStream().use { raw ->
            val counting = CountingInputStream(raw, job) { sent -> job.sentBytes = sent }
            provider.uploadStream(target, counting, size)
        }
    }

    /**
     * 把异常翻成**一句能指导下一步动作**的话。
     *
     * 为什么要分类而不是直接抛 `e.message`：原始 message 长这样 ——
     * `WebDAV 错误 HTTP 507: …` / `550 Permission denied` / `AccessDenied` /
     * `failed to connect to /10.0.0.2 (port 445) after 15000ms`。
     * 用户从这些字符串里读不出"该怎么办"，而"该怎么办"在这几类之间差别很大：
     * 空间不足要去清远端、认证失败要去改配置、网关错误只能等或联系服务方。
     * 完整原文不会丢，在 [PushJob.errorDetail] 里（页面上有「失败详情 + 复制」）。
     *
     * 判据同时看**异常类型**和**message 关键字**：provider 把协议错误都包成了
     * `ProviderException`，类型信息在 message 里；而网络层的异常（超时、DNS）类型是准的。
     */
    private fun classifyError(e: Throwable): String {
        // 顺着 cause 链把所有 message 拼起来再匹配：真正的原因常常在第二层
        val text = buildString {
            var cur: Throwable? = e
            var depth = 0
            while (cur != null && depth < MAX_CAUSE_DEPTH) {
                append(cur.javaClass.simpleName).append(' ').append(cur.message ?: "").append(' ')
                cur = cur.cause?.takeIf { it !== cur }
                depth++
            }
        }
        val lower = text.lowercase()

        fun has(vararg keys: String) = keys.any { lower.contains(it) }

        return when {
            // ── 容量 / 配额（远端拒收，不是我们这边的问题）──
            has("enospc", "no space", "insufficient storage", "quota", "507") ->
                "远端空间不足或超出配额，请先清理远端再重试"
            has("entitytoolarge", "413", "too large", "exceeds the maximum", "file size limit") ->
                "文件超过远端允许的单文件上限，该源不接受这个大小"

            // ── 鉴权 / 权限 ──
            has("401", "unauthorized", "530 ", "login incorrect", "authentication fail", "auth fail") ->
                "远端拒绝登录（账号或密码不对），请到存储源配置里改正"
            has("403", "accessdenied", "access denied", "permission denied", "550 ", "forbidden") ->
                "远端拒绝写入（没有权限），请检查该账号对目标目录的写权限"
            has("signaturedoesnotmatch", "invalidaccesskeyid") ->
                "S3 签名校验失败（AccessKey / SecretKey 或 region 不对）"

            // ── 目标不存在 ──
            has("404", "notfound", "no such file", "nosuchbucket", "nosuchkey", "553 ") ->
                "远端目标目录不存在或已被删除"

            // ── 服务端 / 网关 ──
            has("502", "bad gateway", "503", "service unavailable", "504", "gateway time") ->
                "远端服务暂时不可用（网关错误），稍后重试"
            has("500", "internal server error") ->
                "远端服务内部错误，稍后重试"
            has("429", "too many requests", "slowdown") ->
                "被远端限流了，等一会儿再重试"

            // ── 连接层 ──
            has("unknownhost") -> "解析不到远端地址（域名写错或设备没有网）"
            has("sockettimeout", "timeout", "timed out") -> "连接远端超时（网络不通或对方无响应）"
            has("connectexception", "failed to connect", "econnrefused", "connection refused") ->
                "连不上远端（端口不对、服务没开或被防火墙拦住）"
            has("sslhandshake", "sslexception", "certpath", "certificate") ->
                "TLS 证书校验失败（自签证书需要在配置里允许）"
            has("connection reset", "broken pipe", "unexpected end of stream") ->
                "传输过程中连接被断开，重试通常可以恢复"

            // ── 我们自己抛的那几种 ──
            has("暂存文件已丢失") -> "设备上的暂存文件已被清理，需要重新上传"
            has("存储源不存在") -> "这个存储源已被删除或停用"
            has("不支持上传") -> "该存储源不支持上传"

            else -> e.message?.takeIf { it.isNotBlank() }?.take(120) ?: e.javaClass.simpleName
        }
    }

    /**
     * 拼一段能直接发给开发者的错误详情。
     *
     * 刻意**不带堆栈全文**：远端失败的有效线索几乎总在异常链的 message 里
     * （FTP 的 reply string、S3 的 error code、SSL 的握手原因），而堆栈在混淆包里全是
     * `a.b.c` 这种无意义的名字。所以顺着 `cause` 把每一层的类名+message 都摊开，
     * 再补上"这次推送的上下文"（目标、大小、已发多少、暂存文件还在不在）——
     * 后者往往比异常本身更能定位问题（比如"已发 0 字节"说明连都没连上）。
     */
    private fun buildErrorDetail(job: PushJob, e: Throwable): String = buildString {
        appendLine("目标: ${job.destPath}")
        appendLine("源: ${job.sourceLabel} (${job.sourceId})")
        appendLine("文件: ${job.fileName}")
        appendLine("大小: ${job.totalBytes} 字节，已发送 ${job.sentBytes} 字节")
        appendLine("暂存文件: ${job.stagedPath}（${if (job.stagedFile.isFile) "仍在，可重试" else "已不存在"}）")
        appendLine("--- 异常链 ---")
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < MAX_CAUSE_DEPTH) {
            appendLine("${"  ".repeat(depth)}${cur.javaClass.name}: ${cur.message ?: "(无 message)"}")
            cur = cur.cause?.takeIf { it !== cur }
            depth++
        }
        // 只保留最顶层的几行栈：定位"是哪个 provider 抛的"够用，又不会把提示撑爆
        e.stackTrace.take(STACK_LINES).forEach { appendLine("  at $it") }
    }.trimEnd()

    /** 已结束且超过 TTL 的记录清掉；条数超上限时也从最老的已结束记录开始清。 */
    private fun purgeFinished() {
        val now = nowMs()
        jobs.values
            .filter { !it.active && it.finishedAt > 0L && (it.finishedAt > now || now - it.finishedAt > FINISHED_TTL_MS) }
            .forEach { job ->
                runCatching { job.stagedFile.delete() }
                jobs.remove(job.id)
            }
        while (jobs.size > MAX_JOBS) {
            val oldest = jobs.values.filter { !it.active }.minByOrNull { it.createdAt } ?: return
            runCatching { oldest.stagedFile.delete() }
            jobs.remove(oldest.id)
        }
    }

    /**
     * 输入流包装：推进度 + 响应取消。
     *
     * 为什么取消要做在流上：`uploadStream` 的实现都是阻塞 IO，协程 cancel 只会标记
     * 协程状态，正在进行的 socket 写照样跑完。在 read 上抛异常才能真正把它打断。
     */
    private class CountingInputStream(
        delegate: InputStream,
        private val job: PushJob,
        private val onProgress: (Long) -> Unit
    ) : FilterInputStream(delegate) {
        private var total = 0L
        private var lastReported = 0L

        override fun read(): Int {
            checkCancelled()
            val b = super.read()
            if (b >= 0) bump(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkCancelled()
            val n = super.read(b, off, len)
            if (n > 0) bump(n.toLong())
            return n
        }

        private fun bump(n: Long) {
            total += n
            // 每 256KB 才回调一次：进度字段是 @Volatile，每 8KB 写一次纯属浪费
            if (total - lastReported >= PROGRESS_STEP) {
                lastReported = total
                onProgress(total)
            }
        }

        private fun checkCancelled() {
            if (job.cancelRequested) throw IOException("已取消推送")
        }
    }

    companion object {
        private const val TAG = "RemotePush"
        private const val JOB_ID_BYTES = 9

        /** 已结束记录（含失败作业的暂存文件）保留 6 小时。 */
        const val FINISHED_TTL_MS = 6 * 60 * 60 * 1000L

        /** 作业记录条数上限。超了从最老的已结束记录开始清。 */
        const val MAX_JOBS = 64

        /** 暂存根目录名。 */
        const val STAGING_DIR_NAME = "ufi-remote-upload"

        private const val PROGRESS_STEP = 256L * 1024

        /** 错误详情里最多摊开几层 cause。 */
        private const val MAX_CAUSE_DEPTH = 6

        /** 错误详情里保留的栈行数。 */
        private const val STACK_LINES = 4

        /** 拼远端路径：`/photos` + `a.mp4` → `/photos/a.mp4`，不出双斜杠。 */
        fun joinRemote(relDir: String, fileName: String): String {
            val dir = relDir.trimEnd('/')
            val name = fileName.trim('/')
            return if (dir.isEmpty()) "/$name" else "$dir/$name"
        }
    }
}
