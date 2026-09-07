package com.ufi_axis_core.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 可选二进制组件的本地仓库（2026-09-01：frpc / cloudflared 从 APK 剥离后的落地点）。
 *
 * 设计要点：
 * 1. **目录独立**：`filesDir/components/`，与 [AssetExtractor] 托管的 `filesDir/shell/` 完全分开 ——
 *    后者是"随包内置、每次启动全量覆盖"，前者是"用户按需下载、生命周期由用户控制"，
 *    混在一个目录里会让"这个文件该不该被 extractAll 覆盖"变成无法回答的问题。
 * 2. 每个组件两个文件：`<id>` 可执行二进制 + `<id>.json` 元数据（版本/校验和/来源/安装时间）。
 *    元数据是"已装什么版本"的唯一真源 —— 不能靠跑 `--version` 反推，因为组件缺失时根本跑不了。
 * 3. 安装走 `tmp → setExecutable → renameTo` 三步，与 [AssetExtractor] 同源：
 *    直接覆盖正在被执行的二进制会拿到 ETXTBSY，rename 只改目录项，运行中的进程仍持有旧 inode。
 * 4. [validateAarch64Elf] 在安装前做体检：既然二进制来自公网（上游 release 直链），
 *    必须确认拿到的是 arm64 的 ELF 可执行文件，而不是 .deb / x86 版本 / 一个 HTML 错误页。
 */
class BinaryComponentStore(private val appContext: Context) {

    /** 组件根目录（惰性建目录，与工程内其他 workDir 写法一致） */
    val dir: File
        get() = File(appContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun binaryFile(id: String): File = File(dir, requireValidId(id))

    private fun metaFile(id: String): File = File(dir, "${requireValidId(id)}.json")

    /** 组件是否已安装（文件存在且非空） */
    fun isInstalled(id: String): Boolean = try {
        val f = binaryFile(id)
        f.exists() && f.length() > 0
    } catch (_: Exception) {
        false
    }

    /** 组件二进制的绝对路径（无论是否已安装都返回，调用方自行判存在性） */
    fun pathOf(id: String): String = binaryFile(id).absolutePath

    /** 读取元数据；文件缺失或解析失败返回 null */
    fun readMeta(id: String): BinaryComponentMeta? = try {
        val f = metaFile(id)
        if (!f.exists()) null else json.decodeFromString(BinaryComponentMeta.serializer(), f.readText())
    } catch (e: Exception) {
        AppLogger.w(TAG, "readMeta[$id] 失败: ${e.message}")
        null
    }

    /**
     * 安装组件：把已下载/已解包好的 [src] 文件提交为 `<id>`，并写入元数据。
     *
     * [src] 必须位于同一文件系统（约定放 `filesDir` 下），这样 rename 才是原子的。
     * 成功后 [src] 不再存在（被 rename 走）；失败时 [src] 由调用方负责清理。
     */
    fun install(id: String, src: File, meta: BinaryComponentMeta): Result<Unit> = runCatching {
        val validId = requireValidId(id)
        require(src.exists() && src.length() > 0) { "安装源文件不存在或为空: ${src.name}" }
        validateAarch64Elf(src)?.let { throw IllegalArgumentException(it) }

        val target = binaryFile(validId)
        val tmp = File(dir, "$validId.installing")
        tmp.delete()
        if (!src.renameTo(tmp)) {
            // 跨文件系统等极少数场景：退化为拷贝
            src.copyTo(tmp, overwrite = true)
            src.delete()
        }
        tmp.setExecutable(true, false)
        tmp.setReadable(true, false)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            target.setReadable(true, false)
            tmp.delete()
        }
        writeMeta(validId, meta.copy(id = validId, size = target.length()))
        AppLogger.i(TAG, "组件已安装: $validId v${meta.version} (${target.length()} bytes, ${meta.source})")
    }

    /** 更新元数据（不动二进制）——用于安装后回填 `--version` 探测结果 */
    fun updateMeta(id: String, transform: (BinaryComponentMeta) -> BinaryComponentMeta) {
        val cur = readMeta(id) ?: BinaryComponentMeta(id = id)
        runCatching { writeMeta(requireValidId(id), transform(cur)) }
            .onFailure { AppLogger.w(TAG, "updateMeta[$id] 失败: ${it.message}") }
    }

    /** 卸载：删二进制 + 元数据。二进制删除失败即视为失败（元数据留着更能反映真实状态） */
    fun remove(id: String): Boolean {
        val validId = requireValidId(id)
        val bin = binaryFile(validId)
        val ok = !bin.exists() || bin.delete()
        if (ok) {
            metaFile(validId).delete()
            AppLogger.i(TAG, "组件已卸载: $validId")
        } else {
            AppLogger.w(TAG, "组件卸载失败（二进制删除被拒）: $validId")
        }
        return ok
    }

    /** 临时下载目录（与组件目录同文件系统，保证 rename 原子） */
    fun tempFile(name: String): File = File(dir, ".tmp_$name")

    /** 清理遗留的临时文件（服务启动时调用，避免上次中断的下载常驻占盘） */
    fun cleanupTemp() {
        runCatching {
            dir.listFiles { f -> f.isFile && (f.name.startsWith(".tmp_") || f.name.endsWith(".installing")) }
                ?.forEach { it.delete() }
        }
    }

    /** 组件目录占用总字节数（供前端展示） */
    fun totalBytes(): Long = runCatching {
        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    private fun writeMeta(id: String, meta: BinaryComponentMeta) {
        val text = json.encodeToString(BinaryComponentMeta.serializer(), meta)
        val f = metaFile(id)
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "BinaryComponentStore"
        private const val DIR_NAME = "components"

        /** 组件 id（同时是磁盘文件名与可执行文件名） */
        const val ID_FRPC = "frpc"
        const val ID_CLOUDFLARED = "cloudflared"

        /** 元数据 source 取值 */
        const val SOURCE_REMOTE = "remote"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_LEGACY = "legacy"

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

        /**
         * 组件 id 白名单校验：id 直接拼进文件路径，且来自 HTTP 路径参数，
         * 必须挡住 `..` / 分隔符 / 空串，否则是明摆着的路径穿越。
         */
        fun requireValidId(id: String): String {
            require(ID_REGEX.matches(id)) { "非法组件 id: $id" }
            return id
        }

        /**
         * 校验文件是 aarch64 的 ELF 可执行文件；通过返回 null，否则返回中文错误原因。
         *
         * 读 ELF 头前 20 字节：magic(0..3) = 7F 45 4C 46；EI_CLASS(4) = 2 表示 64 位；
         * e_machine(18..19, 小端) = 0xB7 表示 EM_AARCH64。
         */
        fun validateAarch64Elf(file: File): String? {
            val head = try {
                file.inputStream().use { ins ->
                    val buf = ByteArray(20)
                    var read = 0
                    while (read < buf.size) {
                        val n = ins.read(buf, read, buf.size - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < buf.size) return "文件过小，不是有效的 ELF 可执行文件（${file.length()} 字节）"
                    buf
                }
            } catch (e: Exception) {
                return "读取文件头失败: ${e.message}"
            }
            if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
            ) {
                return "不是 ELF 可执行文件（可能下载到的是错误页面或安装包）"
            }
            if (head[4].toInt() != 2) return "不是 64 位 ELF（EI_CLASS=${head[4].toInt()}）"
            val machine = (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
            if (machine != 0xB7) {
                return "架构不匹配：期望 arm64-v8a(0xB7)，实际 0x${machine.toString(16)}"
            }
            return null
        }

        /** 流式计算文件 SHA-256（十六进制小写） */
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** 已安装组件的元数据（落盘为 `filesDir/components/<id>.json`） */
@Serializable
data class BinaryComponentMeta(
    val id: String,
    /** 版本号：远端安装取清单值，手动上传/旧版迁移取 `--version` 探测值，探测不到为空 */
    val version: String = "",
    /** 远端安装时校验通过的 sha256（手动上传/迁移为空，表示未经校验） */
    val sha256: String = "",
    val size: Long = 0,
    /** remote / manual / legacy */
    val source: String = BinaryComponentStore.SOURCE_REMOTE,
    val installedAt: Long = 0
)
