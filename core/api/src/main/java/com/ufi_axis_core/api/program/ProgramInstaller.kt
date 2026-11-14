package com.ufi_axis_core.api.program

import com.ufi_axis_core.api.update.MirrorResolver
import com.ufi_axis_core.controller.program.ProgramAdapterRegistry
import com.ufi_axis_core.controller.program.ProgramContext
import com.ufi_axis_core.controller.program.ProgramInstalledMeta
import com.ufi_axis_core.controller.program.ProgramManifest
import com.ufi_axis_core.controller.program.ProgramManifestCodec
import com.ufi_axis_core.controller.program.ProgramSettings
import com.ufi_axis_core.controller.program.ProgramStore
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.TarGzExtractor
import com.ufi_axis_core.util.program.ProgramPaths
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 第三方程序产物的获取与落盘（外挂式分发的下载侧）。
 *
 * ## 独立成类的原因
 *
 * 「从何处下载」会变化（镜像、直链、本地文件、差分包），「如何安装」不应随之变化。
 * [ProgramStore] 只接收已存在于本地的可执行文件，因此下载、解包与校验全部集中在此，
 * 更换来源时不涉及存储层。
 *
 * ## 与 `ComponentManager` 的关系
 *
 * 两者的下载逻辑高度相似（镜像回退、HTTPS 强制、流式 SHA-256、从 tar.gz 取 entry），
 * 但刻意未抽取公共类：
 * - 抽取时必须同时修改 `ComponentManager`（frpc / cloudflared 处于生产运行状态），
 *   属于不同的风险面，不应与新增功能合并；
 * - 另有一处差异：`size` 在商店侧于安装前使用（体积提示、磁盘预检），在组件侧仅用于展示。
 *
 * 两者如需合并，应放入统一的远端产物获取器。当前实现中的重复是有意保留的。
 *
 * ## 外挂式分发的三条强制约束
 *
 * 1. 只能由用户显式触发：下载可能消耗计费流量，禁止在后台自动执行；
 * 2. sha256 必填：目标产物会被执行，不允许无校验的公网下载；
 * 3. 安装包不保留：解包成功后立即删除 `.tmp` 中的文件，避免占用 `/data` 上百 MB。
 */
class ProgramInstaller(
    private val paths: ProgramPaths,
    private val store: ProgramStore,
    private val settings: AppSettings
) {

    /** 同一时刻只允许一个安装任务：并发下载会互相拖慢并重复占用磁盘。 */
    private val busy = AtomicBoolean(false)

    /** 只读，供路由在受理前同步拒绝，避免先受理、再在协程中因 busy 失败造成静默假成功。 */
    val isBusy: Boolean get() = busy.get()

    data class Progress(
        val id: String = "",
        val phase: String = "idle",
        /** 已下载字节 */
        val downloaded: Long = 0,
        val total: Long = 0,
        /** 第几个候选（1 起）。0 = 未知 */
        val candidateIndex: Int = 0,
        val message: String = ""
    )

    @Volatile
    var progress: Progress = Progress()
        private set

    /**
     * 内容目录磁盘预检。
     *
     * 保留系数为 4 倍而非 3 倍：实测 OpenList 的 tar.gz 为 51.8MB，解出的二进制为 157.8MB
     * （压缩比 3.0x），峰值为压缩包与解出物之和 209.6MB，即声明体积的 4.05 倍。
     * 按 3 倍计算仅预留 155MB，会在 `extractEntry` 中途触发 ENOSPC；此时压缩包已下载完毕、
     * 临时文件已占用磁盘，而错误信息仅为「写入失败」。
     */
    private fun requireContentSpace(manifest: ProgramManifest, contentRoot: String?) {
        if (!manifest.content.enabled) return
        // 必须使用与落盘相同的解析结果：清单 default_root=external 且用户未选择存储卷时，
        // 实际落点为外部内容根下的 `<id>`；此处若按 null 探测内部卷，检查的是另一块存储。
        val dir = paths.contentDir(manifest.id, store.resolveContentRoot(manifest, contentRoot))
        var probe: File? = dir
        while (probe != null && !probe.exists()) {
            val parent = probe.parentFile
            // 不得越过卷根继续向上查找：外接卷被拔出时 `/storage` 乃至 `/` 仍然存在，
            // 继续向上会以其他存储的空闲空间通过预检，最终在 mkdirs 或写入时失败。
            if (parent == null || parent.absolutePath == "/storage" || parent.absolutePath == "/") {
                probe = null
                break
            }
            probe = parent
        }
        val free = probe?.usableSpace ?: 0L
        // 内容增长不可预知，此处只保证可创建且有基本余量，上限由 data_quota_mb 约束。
        // free == 0 表示探测失败（卷已卸载或 StatFs 失败），而非空间恰好耗尽：
        // 若按 `free in 1 until MIN` 处理会将其判定为通过，用户在下载完成后才会看到「不可写」。
        if (free <= 0) {
            throw IllegalStateException(
                "无法确认内容目录的剩余空间（${dir.absolutePath} 不存在或不可访问），请换一个位置"
            )
        }
        if (free < CONTENT_MIN_FREE_BYTES) {
            throw IllegalStateException(
                "内容目录所在存储空间不足：需要约 ${CONTENT_MIN_FREE_BYTES / 1024 / 1024}MB，" +
                    "可用 ${free / 1024 / 1024}MB"
            )
        }
        if (!paths.isStorageWritable(dir)) {
            throw IllegalStateException("内容目录不可写：${dir.absolutePath}（SD 卡未挂载或只读）")
        }
    }

    private fun requireDisk(manifest: ProgramManifest, storageOverride: String? = null) {
        val declared = manifest.artifact.size
        val need = if (declared > 0) declared * 4 else 256L * 1024 * 1024
        val free = paths.root.usableSpace
        if (free < need) {
            throw IllegalStateException(
                "磁盘空间不足：需要约 ${need / 1024 / 1024}MB，可用 ${free / 1024 / 1024}MB"
            )
        }

        // 选择外部存储时，数据目录位于 /sdcard，属于另一条写盘路径：内部空间充足不代表其充足。
        // 此处只要求容纳数据目录的初始内容，实时增长由运行期的 data_quota_mb 约束
        // （超配额拒绝启动，见 ProgramRuntime）。
        if (ProgramPaths.parseStorage(storageOverride ?: manifest.run.storage) == ProgramPaths.StorageKind.EXTERNAL) {
            val ext = ProgramPaths.externalRoot()
            var probe: File? = ext
            while (probe != null && !probe.exists()) probe = probe.parentFile
            val extFree = probe?.usableSpace ?: 0L
            val wantMb = if (declared > 0) declared / 1024 / 1024 else 64L
            if (extFree in 1 until wantMb * 1024 * 1024) {
                throw IllegalStateException(
                    "外部存储空间不足：需要约 ${wantMb}MB，可用 ${extFree / 1024 / 1024}MB"
                )
            }
        }
    }

    /**
     * 安装后的首次初始化（Adapter preflight）。
     *
     * 在安装阶段执行而非首次启动：OpenList 首次需要启动一次 server 生成 config（约 30s），
     * 再执行 admin set。放在启动路径上会持有 runtime 锁并与 Web 端 15s 超时冲突；
     * 放在安装协程中可直接展示进度，启动路径只保留一次快速的地址校正。
     */
    private fun provision(manifest: ProgramManifest, meta: ProgramInstalledMeta, wasInstalled: Boolean) {
        val adapterId = manifest.firstRun?.adapter
        if (adapterId.isNullOrBlank()) return
        val adapter = ProgramAdapterRegistry.get(adapterId)
            ?: throw IllegalStateException("未注册的适配器「$adapterId」")
        progress = progress.copy(phase = "initializing", message = "正在初始化 ${manifest.name}...")
        val storage = ProgramPaths.parseStorage(meta.storage)
        val ctx = ProgramContext(
            id = meta.id,
            binPath = paths.binFile(meta.id, meta.entry),
            dataDir = paths.dataDir(meta.id, storage),
            confDir = paths.confDir(meta.id, storage),
            runDir = paths.runDir(meta.id),
            contentDir = paths.contentDir(meta.id, meta.contentDir),
            contentEnabled = manifest.content.enabled,
            internalPort = ProgramSettings.internal(meta, manifest),
            accessMode = meta.accessMode
        )
        val result = runCatching { adapter.preflight(ctx) }
            .getOrElse { e -> Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName)) }
        if (result.isFailure) {
            // 全新安装初始化失败时回滚，避免留下「已安装但无法启动」的不完整状态；
            // 更新失败不回滚数据目录，保留用户数据以便重试或手动处理。
            if (!wasInstalled) {
                runCatching { store.uninstall(meta.id) }
                    .onFailure { AppLogger.w(TAG, "初始化失败后回滚 ${meta.id} 失败: ${it.message}") }
            }
            throw IllegalStateException("初始化失败：${result.exceptionOrNull()?.message ?: "未知原因"}")
        }
    }

    /**
     * 从清单声明的远端地址安装（外挂式分发的常规路径）。
     *
     * 阻塞式方法：调用方负责在 IO 线程或协程中执行。进度通过 [progress] 轮询。
     */
    fun installFromRemote(
        manifest: ProgramManifest,
        storageOverride: String? = null,
        accessModeOverride: String? = null,
        contentRoot: String? = null
    ): Result<ProgramInstalledMeta> {
        if (!busy.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("已有安装任务在进行，请稍候"))
        }
        val id = manifest.id
        // 先校验存储策略：数据含凭据的程序不允许放在外部存储。该校验位于下载之前，
        // 避免下载数十 MB 后才被拒绝。
        if (manifest.requires.privateData &&
            ProgramPaths.parseStorage(storageOverride ?: manifest.run.storage) == ProgramPaths.StorageKind.EXTERNAL
        ) {
            return Result.failure(
                IllegalStateException("该程序的数据目录含凭据，只能放在内部存储（外部存储对同机所有应用可见）")
            )
        }
        val wasInstalled = store.isInstalled(id)
        val download = paths.tmpDir.resolve("$id.download")
        val unpacked = paths.tmpDir.resolve("$id.bin")
        try {
            val errors = ProgramManifestCodec.validate(manifest)
            if (errors.isNotEmpty()) {
                throw IllegalStateException("清单校验失败：" + errors.joinToString("；"))
            }
            ProgramManifestCodec.requireSecureUrl(manifest.artifact.url)
                .getOrElse { throw IllegalStateException(it.message ?: "下载地址不安全") }
            requireDisk(manifest, storageOverride)
            requireContentSpace(manifest, contentRoot)

            progress = Progress(id, "downloading", 0, manifest.artifact.size, 0, "准备下载...")
            val actualSha = downloadWithFallback(manifest, download) { done, total, idx ->
                progress = progress.copy(
                    downloaded = done,
                    total = if (total > 0) total else manifest.artifact.size,
                    candidateIndex = idx
                )
            }

            progress = progress.copy(phase = "verifying", message = "正在校验 SHA-256...")
            if (!actualSha.equals(manifest.artifact.sha256, ignoreCase = true)) {
                throw IllegalStateException(
                    "SHA-256 校验失败：期望 ${manifest.artifact.sha256}，实际 $actualSha（可能镜像缓存了旧版本）"
                )
            }

            val binary = when (manifest.artifact.archive) {
                "tar.gz" -> {
                    progress = progress.copy(phase = "extracting", message = "正在从压缩包提取...")
                    val entry = if (manifest.artifact.entry.isNotBlank()) manifest.artifact.entry else id
                    TarGzExtractor.extractEntry(
                        download, entry, unpacked,
                        ProgramManifestCodec.MAX_ARTIFACT_BYTES
                    ).getOrElse { throw IllegalStateException("解包失败：${it.message}") }
                    unpacked
                }
                else -> download
            }

            progress = progress.copy(phase = "installing", message = "正在安装...")
            val meta = store.install(
                manifest, binary, ProgramStore.SOURCE_REMOTE,
                storageOverride, accessModeOverride, contentRoot
            )
                .getOrElse { throw IllegalStateException("安装失败：${it.message}") }
            provision(manifest, meta, wasInstalled)

            progress = Progress(id, "done", progress.downloaded, progress.total, 0, "已安装 ${meta.version}")
            AppLogger.i(TAG, "程序安装完成: $id v${meta.version}")
            return Result.success(meta)
        } catch (e: Exception) {
            progress = progress.copy(phase = "failed", message = e.message ?: "安装失败")
            AppLogger.w(TAG, "install[$id] 失败: ${e.message}")
            return Result.failure(e)
        } finally {
            // 安装包不保留：无论成败都在结束时删除临时文件
            runCatching { download.delete() }
            runCatching { unpacked.delete() }
            busy.set(false)
        }
    }

    /**
     * 从上传流安装（无外网环境时的备用路径）。
     *
     * 按文件头识别格式：`1F 8B` 按 tar.gz 提取，其余按裸二进制处理。
     * 该来源没有可比对的期望 sha256，因此来源标记为 `upload`，UI 据此区分。
     */
    fun installFromStream(
        manifest: ProgramManifest,
        input: InputStream,
        storageOverride: String? = null,
        accessModeOverride: String? = null,
        contentRoot: String? = null,
        /**
         * 调用方声明的 sha256（可选）。
         *
         * 上传是唯一一条**没有**"清单说应该是哪个哈希"的安装路径：字节来自客户端。
         * 给了这个参数就必须与实测一致，否则拒绝；没给就记下实测值，
         * **不要**再用清单里那份（否则 `app.json` 会把一份未校验的上传件标成与发布版本一致）。
         */
        expectedSha256: String? = null
    ): Result<ProgramInstalledMeta> {
        if (!busy.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("已有安装任务在进行，请稍候"))
        }
        val id = manifest.id
        val wasInstalled = store.isInstalled(id)
        val upload = paths.tmpDir.resolve("$id.upload")
        val unpacked = paths.tmpDir.resolve("$id.bin")
        try {
            val errors = ProgramManifestCodec.validate(manifest)
            if (errors.isNotEmpty()) {
                throw IllegalStateException("清单校验失败：" + errors.joinToString("；"))
            }
            requireDisk(manifest, storageOverride)
            requireContentSpace(manifest, contentRoot)
            progress = Progress(id, "installing", 0, 0, 0, "正在接收上传...")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            upload.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > ProgramManifestCodec.MAX_ARTIFACT_BYTES) {
                        throw IllegalStateException(
                            "上传体积超过 ${ProgramManifestCodec.MAX_ARTIFACT_BYTES / 1024 / 1024}MB 限制"
                        )
                    }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                }
            }
            require(total > 0) { "上传内容为空" }

            val head = upload.inputStream().use { ins -> ByteArray(2).also { ins.read(it) } }
            val isGzip = head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()
            val binary = if (isGzip) {
                progress = progress.copy(phase = "extracting", message = "正在从压缩包提取...")
                val entry = if (manifest.artifact.entry.isNotBlank()) manifest.artifact.entry else id
                TarGzExtractor.extractEntry(
                    upload, entry, unpacked,
                    ProgramManifestCodec.MAX_ARTIFACT_BYTES
                ).getOrElse { throw IllegalStateException("解包失败：${it.message}") }
                unpacked
            } else {
                upload
            }

            // 实测哈希：既用于（可选的）调用方声明比对，也用于 `app.json` —— 上传件不能
            // 冒用清单声明的 sha256。
            val computedSha = digest.digest().joinToString("") { "%02x".format(it) }
            val declared = expectedSha256?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            if (declared != null && declared != computedSha) {
                throw IllegalStateException("sha256 不匹配：声明 $declared，实测 $computedSha")
            }
            val meta = store.install(
                manifest, binary, ProgramStore.SOURCE_UPLOAD,
                storageOverride, accessModeOverride, contentRoot,
                verifiedSha256 = computedSha
            )
                .getOrElse { throw IllegalStateException("安装失败：${it.message}") }
            provision(manifest, meta, wasInstalled)
            progress = Progress(id, "done", total, total, 0, "已安装（本地上传）")
            return Result.success(meta)
        } catch (e: Exception) {
            progress = progress.copy(phase = "failed", message = e.message ?: "安装失败")
            return Result.failure(e)
        } finally {
            runCatching { upload.delete() }
            runCatching { unpacked.delete() }
            busy.set(false)
        }
    }

    // ── 下载 ──

    /**
     * 逐候选下载（镜像 → 原地址）。切换候选前清除未完成的临时文件。
     *
     * 关键行为：sha256 不匹配时切换到下一个候选，而非直接失败。镜像常滞后一两个版本，
     * 直接失败会使「新清单 + 旧镜像」的组合无法安装。
     */
    private fun downloadWithFallback(
        manifest: ProgramManifest,
        target: File,
        onProgress: (Long, Long, Int) -> Unit
    ): String {
        val candidates = MirrorResolver.candidates(settings, manifest.artifact.url)
        if (candidates.isEmpty()) throw IllegalStateException("下载地址为空")

        val expected = manifest.artifact.sha256
        val errors = mutableListOf<String>()

        candidates.forEachIndexed { index, url ->
            try {
                ProgramManifestCodec.requireSecureUrl(url).getOrElse { throw IllegalArgumentException(it.message) }
                val sha = downloadTo(url, target, manifest.artifact.size) { done, total ->
                    onProgress(done, total, index + 1)
                }
                if (!sha.equals(expected, ignoreCase = true)) {
                    throw IllegalStateException("校验不符（该候选可能是旧版本）")
                }
                if (index > 0) AppLogger.i(TAG, "第 ${index + 1}/${candidates.size} 个候选成功: $url")
                return sha
            } catch (e: Exception) {
                target.delete()
                errors += "[${index + 1}] $url → ${e.message}"
                AppLogger.w(TAG, "候选下载失败: ${e.message}")
            }
        }
        throw IllegalStateException("全部 ${candidates.size} 个候选都失败：\n" + errors.joinToString("\n"))
    }

    private fun downloadTo(
        urlStr: String,
        target: File,
        declaredSize: Long,
        onProgress: (Long, Long) -> Unit
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: declaredSize
            if (total > ProgramManifestCodec.MAX_ARTIFACT_BYTES) {
                throw IllegalStateException("下载体积 ${total / 1024 / 1024}MB 超过上限")
            }

            target.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastReport = 0L
            conn.inputStream.use { ins ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        if (written > ProgramManifestCodec.MAX_ARTIFACT_BYTES) {
                            throw IllegalStateException("下载体积超过上限")
                        }
                        // 回调间隔 500ms：逐字节回调会占满 UI 线程，间隔过长则慢速网络下
                        // 无法区分进度停滞与卡死
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastReport >= 500) {
                            lastReport = nowMs
                            onProgress(written, total)
                        }
                    }
                }
            }
            onProgress(written, total)
            conn.disconnect()
            conn = null
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    companion object {
        /** 内容目录至少要留的空间：够建库/索引与第一批文件，不追求精确。 */
        private const val CONTENT_MIN_FREE_BYTES = 128L * 1024 * 1024

        private const val TAG = "ProgramInstaller"
    }
}
