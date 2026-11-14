package com.ufi_axis_core.api.files

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 分片上传的会话表。
 *
 * ## 为什么需要它
 * 整体上传（`POST /api/files/upload`）有两个不可回避的问题：
 * 1. **断了从零重来**。2GB 视频按 5MB/s 上行要 400 秒，任何一次抖动全废；
 * 2. **峰值占用 2× 文件大小**。老实现先落 `File.createTempFile`（app 内部存储）
 *    再 `Files.copy` 到目标，目标在 SD 卡时还是跨卷复制。
 *
 * 分片方案把两件事一起解决：分片**直接追加到目标目录下的 `.ufipart`**，
 * 完成时同目录 `renameTo` —— 元数据操作，瞬时完成、零额外空间。
 *
 * ## 三条设计前提（任一条被改坏都会以很难定位的方式炸掉）
 *
 * ### 1. `received` 永远以 `.ufipart` 文件的实际长度为准
 * 不在内存里维护一个计数器。理由：core 被系统杀掉或设备断电后，内存计数全丢，
 * 而文件长度是唯一还活着的事实。把它当唯一真源，续传逻辑在"正常重试"和
 * "core 重启后"两条路径上就是同一套代码，不需要第二种恢复分支。
 *
 * ### 2. 会话元数据编码进文件名，不落索引文件
 * 命名 `.<name>.<size>.<sessionId>.ufipart`。于是：
 * - **续传**：对目标目录做一次 `listFiles()` 就能找回会话（不是全卷 walk）；
 * - **core 重启后仍可续传**：文件名自带 name/size/sessionId，无需任何持久化状态；
 * - **并发隔离**：两个标签页传同一个文件会拿到不同 sessionId ⇒ 不同 `.ufipart`，
 *   不会互相追加把文件写坏。
 *
 * 原本设计的 `upload_sessions.json` 索引被砍掉了：`FileRoutes` 没有 `Context`
 * （`class FileRoutes {`，零构造参数），拿不到可写的私有目录。而文件名编码
 * 不需要任何额外存储，反而更简单。
 *
 * ### 3. 路径校验在 [open] 之前跑完，之后不再解析用户输入
 * 与 [com.ufi_axis_core.api.media.MediaTicketStore] 同一个模式：会话里存的是
 * **已通过 `safeResolve` 的真实目录**，`chunk`/`complete` 只认它。
 * 把校验放在开会话时是这套设计能成立的前提 —— 否则一个 sessionId 就能往任意路径写。
 *
 * ## 垃圾文件由谁清
 * 四道闸门，这个类负责第 2、4 道：
 * 1. 客户端主动 `DELETE session`（覆盖"取消"与"正常关标签页"）；
 * 2. **[purgeExpired]**：每次 open/find 时顺手清超过 [ttlMs] 无活动的会话
 *    （同 `MediaTicketStore.issue()` 的惰性清理，不引入定时器）；
 * 3. `/list` 列目录时清该目录里 mtime 超时的 `.ufipart`（覆盖 core 重启、断电）
 *    —— 在 `FileRoutes` 里，见 [isStalePart]；
 * 4. [open] 时的配额与冲突判定。
 *
 * 线程安全：[ConcurrentHashMap] + 条目内 `@Volatile` 时间戳，与 `MediaTicketStore` 一致。
 * 纯 JVM（无 Android 依赖），判据可单测。
 */
class UploadSessionStore(
    /** 无活动超时。超过即视为废弃，连 `.ufipart` 一起删。 */
    private val ttlMs: Long = DEFAULT_TTL_MS,
    /** 同时存在的会话上限；超出淘汰最久未活动的那条（连文件一起删）。 */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** 注入时钟，供单测推进时间。 */
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    /**
     * 一个上传会话。
     *
     * @param dir 已通过路径校验的真实目录（绝对路径，不含尾斜杠）
     * @param fileName 最终落盘的文件名。**已经过 sanitize 与自动改名**，
     *   调用方不要再加工 —— 改名结果必须在开会话时就定下来并告知客户端，
     *   否则"面板上显示的名字"和"实际落盘的名字"会不一致。
     * @param declaredSize 客户端声明的总字节数，[complete] 时用它校验
     */
    class Session(
        val id: String,
        val dir: String,
        val fileName: String,
        val declaredSize: Long,
        @Volatile internal var lastUsedAt: Long
    ) {
        /** 分片累积文件。 */
        val partFile: File get() = File(dir, partNameOf(fileName, declaredSize, id))

        /** 最终目标文件。 */
        val destFile: File get() = File(dir, fileName)

        /**
         * 已接收字节数 —— **读文件实际长度，不读内存计数器**。见类注释的设计前提 1。
         */
        val received: Long get() = partFile.let { if (it.isFile) it.length() else 0L }

        /** 下一个应该收的分片序号。客户端乱序时用它纠正。 */
        fun nextIndex(chunkSize: Long): Long = if (chunkSize <= 0) 0 else received / chunkSize
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    /**
     * 最近完成的会话结果，供 `complete` 幂等。
     *
     * 为什么需要：网络重传会让客户端在服务端已经完成后再发一次 `complete`。
     * 那时会话早已删除，若直接回 410，用户看到的是一次**假失败**（文件其实已经传好了）。
     * 所以完成时留一条短期记录，第二次调用回同样的 success。
     */
    private val recentlyCompleted = ConcurrentHashMap<String, CompletedResult>()

    /** [complete] 的结果，也是 `recentlyCompleted` 的值类型。 */
    class CompletedResult(val path: String, val size: Long, @Volatile internal var at: Long)

    /** 会话无活动超时（秒），回给客户端做参考。 */
    val ttlSeconds: Long get() = ttlMs / 1000

    /**
     * 开一个新会话。
     *
     * @param dir **已通过 `safeResolve` 的真实目录**。这个前提由调用方保证，见设计前提 3。
     * @param fileName **已 sanitize + 已完成自动改名**的最终文件名。
     */
    fun open(dir: String, fileName: String, declaredSize: Long): Session {
        purgeExpired()
        evictIfNeeded()
        val bytes = ByteArray(SESSION_ID_BYTES)
        random.nextBytes(bytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val s = Session(id, dir.trimEnd('/'), fileName, declaredSize, nowMs())
        sessions[id] = s
        return s
    }

    /**
     * 按 id 取会话并**续期**（滑动过期）。不存在或已过期返回 null。
     *
     * 滑动而非固定过期：传一个 2GB 的文件本身可能超过 30 分钟，固定过期会在传到
     * 一半时把会话判死。
     */
    fun find(id: String): Session? {
        if (id.isBlank()) return null
        val s = sessions[id] ?: return null
        if (isExpired(s)) {
            discard(id)
            return null
        }
        s.lastUsedAt = nowMs()
        return s
    }

    /**
     * 在目标目录里找一个可续传的会话。
     *
     * 这是"断点续传"的入口，覆盖三种场景：
     * - 同一页面内网络抖动后重试（内存里还有会话，但这里也能找到）；
     * - **core 重启后**（内存全丢，只剩 `.ufipart` 文件）；
     * - 用户刷新页面后重新选同一个文件（浏览器不能持久化 File 句柄，
     *   所以前端只能引导用户重选，选完靠 name+size 匹配到这里）。
     *
     * 匹配判据是 **name + size 都一致**。size 不一致说明是另一个文件（或者用户改过），
     * 此时不续传 —— 那会拼出一个损坏的文件。
     *
     * 只扫**一个目录**（`listFiles()`），不做递归。
     */
    fun findResumable(dir: String, fileName: String, declaredSize: Long): Session? {
        purgeExpired()
        val d = File(dir)
        if (!d.isDirectory) return null
        val candidates = d.listFiles()?.filter { it.isFile && it.name.endsWith(PART_SUFFIX) } ?: return null
        for (f in candidates) {
            val meta = parsePartName(f.name) ?: continue
            if (meta.name != fileName || meta.size != declaredSize) continue
            // 文件还在但已经放了太久 ⇒ 当垃圾清掉，不续传（闸门 3 的兜底）
            if (isStalePart(f, nowMs(), ttlMs)) {
                f.delete()
                sessions.remove(meta.sessionId)
                continue
            }
            // 内存里可能已经有这条（同页面重试），复用以保持 lastUsedAt 连续
            val existing = sessions[meta.sessionId]
            if (existing != null) {
                existing.lastUsedAt = nowMs()
                return existing
            }
            // core 重启后的恢复路径：凭文件名重建会话，received 由文件长度给出
            val revived = Session(meta.sessionId, dir.trimEnd('/'), meta.name, meta.size, nowMs())
            sessions[meta.sessionId] = revived
            return revived
        }
        return null
    }

    /**
     * 完成会话：登记幂等记录并从表里摘掉。**不动文件**（rename 由调用方做）。
     */
    fun complete(id: String, finalPath: String, size: Long): CompletedResult {
        val result = CompletedResult(finalPath, size, nowMs())
        recentlyCompleted[id] = result
        sessions.remove(id)
        purgeCompleted()
        return result
    }

    /** 查最近完成记录，供 `complete` 幂等（见 [recentlyCompleted]）。 */
    fun findCompleted(id: String): CompletedResult? {
        purgeCompleted()
        return recentlyCompleted[id]
    }

    /**
     * 丢弃会话**并删除 `.ufipart`**。用户取消、参数校验失败、过期清理都走这里。
     */
    fun discard(id: String) {
        val s = sessions.remove(id) ?: return
        runCatching { s.partFile.delete() }
    }

    /**
     * 当前所有会话占用的 `.ufipart` 总字节数，供配额判定（闸门 4）。
     *
     * 注意它只统计**内存里还有会话的那些**。core 重启后的孤儿不在内,
     * 由 `/list` 的惰性清理负责 —— 这是刻意的取舍：为了统计孤儿要全卷扫描，
     * 代价远大于收益，而孤儿本身有 TTL 兜底。
     */
    fun activeBytes(): Long {
        purgeExpired()
        return sessions.values.sumOf { it.received }
    }

    /** 会话数（测试与诊断用）。 */
    fun size(): Int = sessions.size

    // ──────────────────────────── 内部 ────────────────────────────

    private fun isExpired(s: Session): Boolean {
        val now = nowMs()
        // 时钟被往前调过时 lastUsedAt 会落在"未来"，那种情况也当过期处理，
        // 否则会话会永不过期（now - future 是负数，永远小于 ttl）
        if (s.lastUsedAt > now) return true
        return now - s.lastUsedAt > ttlMs
    }

    private fun purgeExpired() {
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val (_, s) = it.next()
            if (isExpired(s)) {
                runCatching { s.partFile.delete() }
                it.remove()
            }
        }
    }

    private fun purgeCompleted() {
        val now = nowMs()
        recentlyCompleted.entries.removeAll { (_, v) -> v.at > now || now - v.at > COMPLETED_TTL_MS }
    }

    /** 超上限时淘汰最久未活动的那条（连文件一起删，否则淘汰等于制造垃圾）。 */
    private fun evictIfNeeded() {
        while (sessions.size >= maxEntries) {
            val oldest = sessions.values.minByOrNull { it.lastUsedAt } ?: return
            discard(oldest.id)
        }
    }

    companion object {
        /**
         * 分片文件后缀。以 `.` 开头 + 这个后缀是**我们自己的私有约定**，
         * `/list` 靠它识别并过滤，所以不会误删用户文件。
         */
        const val PART_SUFFIX = ".ufipart"

        /** 默认无活动超时 30 分钟。 */
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L

        /** 同时存在的会话上限。串行上传下实际只会有 1~2 条，32 是纯防御值。 */
        const val DEFAULT_MAX_ENTRIES = 32

        /** 完成记录保留 5 分钟，够覆盖客户端的重试窗口。 */
        const val COMPLETED_TTL_MS = 5 * 60 * 1000L

        private const val SESSION_ID_BYTES = 12

        /**
         * 分片文件名：`.<name>.<size>.<sessionId>.ufipart`
         *
         * 顺序不能改：解析时从**右往左**切，因为 sessionId 是 base64url（不含 `.`）、
         * size 是纯数字（不含 `.`），而 name **可能含多个 `.`**（`a.b.c.mp4`）。
         * 从右往左切两次之后剩下的整段就是 name，无歧义。
         */
        fun partNameOf(name: String, size: Long, sessionId: String): String =
            ".$name.$size.$sessionId$PART_SUFFIX"

        /** [parsePartName] 的结果。 */
        class PartMeta(val name: String, val size: Long, val sessionId: String)

        /**
         * 解析分片文件名。不是我们的分片文件时返回 null（调用方据此跳过）。
         */
        fun parsePartName(fileName: String): PartMeta? {
            if (!fileName.endsWith(PART_SUFFIX)) return null
            if (!fileName.startsWith(".")) return null
            val body = fileName.removeSuffix(PART_SUFFIX).removePrefix(".")
            val idCut = body.lastIndexOf('.')
            if (idCut <= 0) return null
            val sessionId = body.substring(idCut + 1)
            if (sessionId.isEmpty()) return null
            val rest = body.substring(0, idCut)
            val sizeCut = rest.lastIndexOf('.')
            if (sizeCut <= 0) return null
            val size = rest.substring(sizeCut + 1).toLongOrNull() ?: return null
            val name = rest.substring(0, sizeCut)
            if (name.isEmpty()) return null
            return PartMeta(name, size, sessionId)
        }

        /**
         * 这个 `.ufipart` 是不是该清掉的垃圾。
         *
         * 判据是 **mtime**（而不是内存里的 lastUsedAt）：core 重启后内存没了，
         * 文件的修改时间是唯一能用的活动痕迹。
         *
         * `mtime` 落在未来时也判为过期 —— 系统时间被往回调过会出现这种情况，
         * 不处理的话这个文件就永远清不掉。
         */
        fun isStalePart(f: File, now: Long, ttlMs: Long): Boolean {
            val m = f.lastModified()
            if (m <= 0L) return false // 拿不到时间就别删，宁可留着
            if (m > now) return true
            return now - m > ttlMs
        }
    }
}
