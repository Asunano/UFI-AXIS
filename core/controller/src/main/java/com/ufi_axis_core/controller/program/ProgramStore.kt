package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.program.ElfInspector
import com.ufi_axis_core.util.program.ProgramPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 已安装程序的本地仓库：落盘、体检、原子替换、元数据与运行态。
 *
 * ## 与 `BinaryComponentStore` 的关系
 *
 * 后者管理单个文件，此处管理一个目录树与两份 JSON。以下三条约束同样适用：
 *
 * 1. **安装流程为 `tmp → chmod → rename`**：直接覆盖正在被执行的二进制会返回 `ETXTBSY`；
 *    `rename` 只改目录项，运行中的进程仍持有旧 inode，所以升级可以先替换后重启。
 * 2. **体检在安装前**：二进制来自公网且要被执行，必须在落盘前确认它是 arm64、
 *    且解释器是 Android 能认的（[ElfInspector]）。
 * 3. **临时文件与目标同卷**：跨卷 `rename` 会退化成拷贝，拷贝不是原子的。
 *
 * ## 为什么元数据要落盘而不是每次跑 `--version`
 *
 * 执行 `--version` 需要创建进程，而进程创建可能因 SELinux、架构或文件损坏失败，
 * 此时无法确定已安装的版本。因此「已安装版本」只能来自 `app.json`。
 */
class ProgramStore(private val paths: ProgramPaths) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // ── 查询 ──

    /** 是否已安装：`bin/` 下有非空文件且 `app.json` 可读。**两者缺一均视为未安装**。 */
    fun isInstalled(id: String): Boolean {
        val meta = readMeta(id) ?: return false
        val bin = runCatching { paths.binFile(id, meta.entry) }.getOrNull() ?: return false
        return bin.exists() && bin.length() > 0
    }

    fun readMeta(id: String): ProgramInstalledMeta? = runCatching {
        val f = paths.metaFile(id)
        if (!f.exists()) return@runCatching null
        json.decodeFromString(ProgramInstalledMeta.serializer(), f.readText())
    }.getOrNull()

    fun readState(id: String): ProgramState = runCatching {
        val f = paths.stateFile(id)
        if (!f.exists()) return@runCatching ProgramState()
        json.decodeFromString(ProgramState.serializer(), f.readText())
    }.getOrDefault(ProgramState())

    fun writeState(id: String, state: ProgramState) {
        runCatching {
            paths.ensureDirs(id)
            // 先写临时文件再 rename：避免掉电留下不完整的 JSON（无法解析等同于状态丢失）
            val tmp = File(paths.dirOf(id), "state.json.tmp")
            tmp.writeText(json.encodeToString(ProgramState.serializer(), state))
            val target = paths.stateFile(id)
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { AppLogger.w(TAG, "writeState[$id] 失败: ${it.message}") }
    }

    // ── 安装 ──

    /**
     * 从本地文件安装。
     *
     * 由调用方负责（本方法不处理，因需要运行时状态）：
     * - 确认程序当前未运行：否则替换二进制后运行的仍是旧进程，状态与实际不一致；
     * - 完成清单校验与下载/解包。
     *
     * @param src 已解包的可执行文件（位于 `filesDir/programs/.tmp/` 下，与目标同卷）
     * @param source `remote` / `upload`，仅用于展示
     */
    fun install(
        manifest: ProgramManifest,
        src: File,
        source: String = SOURCE_REMOTE,
        /** 覆盖清单里的默认存储位置；null = 用清单值 */
        storageOverride: String? = null,
        /** `direct` / `gateway`；null = 用清单的 bind 推导 */
        accessModeOverride: String? = null,
        /** 内容目录的父路径；null/空 = 清单的默认值（internal 或 external） */
        contentRoot: String? = null,
        /**
         * 上传安装时**实测**的 sha256。
         *
         * 非 null 表示"这是本机收到的字节，哈希已经算过"—— 此时 `app.json` 记这个值，
         * 而不是清单里声明的那个。否则事后审计会把一份未校验的上传件误认为"与发布版本一致"。
         */
        verifiedSha256: String? = null
    ): Result<ProgramInstalledMeta> = runCatching {
        val id = ProgramPaths.requireId(manifest.id)
        require(src.exists() && src.length() > 0) { "安装源文件不存在或为空: ${src.name}" }
        // 覆盖安装与升级保留用户的端口与自启设置：重新执行 install 时
        // 不得将其重置为清单默认值。
        val previous = readMeta(id)

        // 体检：链接方式不正确的二进制可以安装但无法执行，必须在写入磁盘前拦截。
        ElfInspector.validate(src, manifest.artifact.abi)?.let { throw IllegalArgumentException(it) }

        val entry = entryOf(manifest)
        val storage = ProgramPaths.parseStorage(storageOverride ?: manifest.run.storage)

        // 含凭据的程序禁止落在公共存储：/sdcard/Download/... 可被任何持有存储权限的应用读取，
        // 而 OpenList/Syncthing/Cloudreve 的数据目录包含云盘 token、设备私钥、口令哈希。
        // 校验放在此处而非仅在 route 层：安装有两条入口（远端下载 / 本地上传），
        // 任一条缺少校验都会绕过限制。
        require(!(storage == ProgramPaths.StorageKind.EXTERNAL && manifest.requires.privateData)) {
            "该程序的数据目录含凭据，只能放在内部存储（外部存储对同机所有应用可见）"
        }

        paths.ensureDirs(id, storage)

        val target = paths.binFile(id, entry)
        val staging = File(paths.binDir(id), ".installing")
        staging.delete()

        src.copyTo(staging, overwrite = true)
        staging.setExecutable(true, false)
        staging.setReadable(true, false)

        // 保留旧入口名：升级时 entry 若变化，需删除旧文件，否则 bin/ 中会残留两个二进制
        val oldEntry = previous?.entry

        if (!staging.renameTo(target)) {
            // 少数场景（目标被占用或跨卷）：退化为拷贝，再删除暂存文件
            target.delete()
            staging.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            target.setReadable(true, false)
            staging.delete()
        }

        if (!oldEntry.isNullOrBlank() && oldEntry != entry) {
            runCatching { paths.binFile(id, oldEntry).delete() }
        }

        val meta = ProgramInstalledMeta(
            id = id,
            version = manifest.artifact.version,
            sha256 = (verifiedSha256 ?: manifest.artifact.sha256).lowercase(),
            size = target.length(),
            entry = entry,
            archive = manifest.artifact.archive,
            source = source,
            installedAt = System.currentTimeMillis(),
            // 保留上一次的对外端口（可能已被 Gateway 顺延过）；没有才用清单默认。
            externalPort = previous?.externalPort?.takeIf { it in ProgramSettings.MIN_PORT..ProgramSettings.MAX_PORT }
                ?: manifest.run.externalPort,
            internalPort = previous?.internalPort ?: 0,
            autostart = previous?.autostart ?: false,
            storage = if (storage == ProgramPaths.StorageKind.EXTERNAL) "external" else "internal",
            accessMode = accessModeOverride
                ?: if (manifest.run.bind == "loopback") "gateway" else "direct",
            contentDir = resolveContentRoot(manifest, contentRoot)
        )
        writeMeta(meta)
        AppLogger.i(TAG, "程序已安装: $id v${meta.version} (${meta.size} bytes, $source)")
        meta
    }

    /** 更新元数据（不动二进制）—— 例如实际分配的 external_port 与清单不同时回填。 */
    fun updateMeta(id: String, transform: (ProgramInstalledMeta) -> ProgramInstalledMeta) {
        val cur = readMeta(id) ?: return
        runCatching { writeMeta(transform(cur)) }
            .onFailure { AppLogger.w(TAG, "updateMeta[$id] 失败: ${it.message}") }
    }

    /**
     * 卸载：删除数据与二进制。
     *
     * 顺序为先删二进制、再删数据（见 [ProgramPaths.deleteAll]）：二进制是「是否已安装」的判据，
     * 优先移除可避免出现「已卸载但仍可启动」的不一致状态。
     *
     * @return 未能删除的路径（空 = 卸载干净）
     */
    fun uninstall(
        id: String,
        keepData: Boolean = false,
        deleteLogs: Boolean = false,
    ): Result<List<String>> = runCatching {
        val validId = ProgramPaths.requireId(id)
        require(isInstalled(validId) || paths.dirOf(validId).exists()) { "$validId 尚未安装" }
        val failures = if (keepData) {
            // 只删除二进制与元数据，保留 data/，以便重新安装后继续使用。
            val bin = paths.binDir(validId)
            val list = mutableListOf<String>()
            if (bin.exists() && !bin.deleteRecursively() && bin.exists()) list += bin.absolutePath
            runCatching { paths.metaFile(validId).delete() }
            runCatching { paths.stateFile(validId).delete() }
            // keepData 与彻底卸载是两个独立维度：显式选择删除日志时仍然执行。
            if (deleteLogs) {
                val logs = paths.logDir(validId)
                if (logs.exists() && !logs.deleteRecursively() && logs.exists()) list += logs.absolutePath
            }
            list
        } else {
            // 数据可能位于外部存储：按元数据记录的 storage 删除，否则会留下用户不可见的残留
            val meta = readMeta(validId)
            val storage = ProgramPaths.parseStorage(meta?.storage)
            paths.deleteAll(validId, storage, meta?.contentDir, deleteLogs)
        }
        if (failures.isEmpty()) {
            AppLogger.i(TAG, "程序已卸载: $validId (keepData=$keepData, deleteLogs=$deleteLogs)")
        } else {
            AppLogger.w(TAG, "程序卸载残留 $validId: ${failures.joinToString()}")
        }
        failures
    }

    /**
     * 决定内容目录的父路径。
     *
     * 三种来源，优先级从高到低：
     * 1. 用户安装时选择的绝对路径（SD 卡）：原样返回，[ProgramPaths.contentDir] 会再拼接 `<id>`；
     * 2. 清单 `content.default_root`：`external` 为公共存储根，`internal` 为空字符串（内部默认）；
     * 3. 清单未声明 `content.enabled`：空字符串（不使用内容目录）。
     */
    fun resolveContentRoot(manifest: ProgramManifest, requested: String?): String {
        if (!manifest.content.enabled) return ""
        val picked = requested?.trim().orEmpty()
        if (picked.isNotEmpty()) {
            require(picked.startsWith("/")) { "内容目录必须是绝对路径：$picked" }
            require(picked != "/") { "内容目录不能是根目录" }
            return picked
        }
        return if (manifest.content.defaultRoot == "external") paths.externalContentRoot().absolutePath else ""
    }

    /** 入口文件名。raw 包未声明 entry 时以 id 兜底（上游通常直接发布裸二进制）。 */
    fun entryOf(manifest: ProgramManifest): String {
        val declared = manifest.artifact.entry
        val raw = if (declared.isBlank() && manifest.artifact.archive == "raw") manifest.id else declared
        return ProgramPaths.requireEntry(raw)
    }

    private fun writeMeta(meta: ProgramInstalledMeta) {
        val id = ProgramPaths.requireId(meta.id)
        val tmp = File(paths.dirOf(id), "app.json.tmp")
        tmp.writeText(json.encodeToString(ProgramInstalledMeta.serializer(), meta))
        val target = paths.metaFile(id)
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "ProgramStore"

        const val SOURCE_REMOTE = "remote"
        const val SOURCE_UPLOAD = "upload"
    }
}

/** 安装态元数据（`app.json`），已安装版本的唯一数据源。 */
@Serializable
data class ProgramInstalledMeta(
    val id: String,
    val version: String = "",
    val sha256: String = "",
    val size: Long = 0,
    /** `bin/` 下的文件名 */
    val entry: String = "",
    val archive: String = "raw",
    /** remote / upload */
    val source: String = ProgramStore.SOURCE_REMOTE,
    val installedAt: Long = 0,
    /** 实际使用的 Gateway 对外端口（可能与清单不同：清单端口被占时顺延） */
    val externalPort: Int = 0,
    /** `internal` / `external`：`data/` 与 `conf/` 落在哪。二进制不受影响 */
    val storage: String = "internal",
    /**
     * 内容目录的绝对父路径（例如 `/storage/1A2B-3C4D/UFI-AXIS/content`）。
     * 空 = 内部默认（`programs/<id>/content`）。只有清单声明 `content.enabled` 时才非空。
     */
    val contentDir: String = "",
    /** `direct`（直连端口）或 `gateway`（经 core 转发，需 cookie 校验）。 */
    val accessMode: String = "direct",
    /** 用户自定义的内部端口；0 表示使用清单默认值（`run.internal_port`）。 */
    val internalPort: Int = 0,
    /**
     * Core 启动后自动拉起。
     *
     * 与运行态 `ProgramState.desired`（用户当前希望其运行）是两个独立字段：
     * 停止程序只清除 desired，不修改 autostart；下次 Core 启动仍按此字段恢复。
     */
    val autostart: Boolean = false
)

/**
 * 运行态（`state.json`）。
 *
 * 不变量：`desired` 表示用户希望程序运行，不表示程序正在运行。后者只能由进程对象判定，
 * 不能来自持久化配置。
 */
@Serializable
data class ProgramState(
    /** 期望运行（开机自启、看护拉起的依据） */
    val desired: Boolean = false,
    /** 连续失败次数（成功运行一段时间后清零） */
    val consecutiveFailures: Int = 0,
    /** 熔断：达到上限后不再自动拉起，需用户手动启动。 */
    val circuitOpen: Boolean = false,
    /** 下一次允许自动重启的时刻（毫秒时间戳）；0 = 可立即重试。跨 core 重启保留，退避不会被重启绕过 */
    val nextRetryAt: Long = 0,
    val lastExitCode: Int? = null,
    /** 人类可读的退出原因（主动停止、崩溃、疑似被系统回收、内存超限）。 */
    val lastExitReason: String = "",
    val lastStartAt: Long = 0,
    val lastStopAt: Long = 0,
    /** 是否已执行过首次初始化（Adapter.preflight）。 */
    val initialized: Boolean = false
)
