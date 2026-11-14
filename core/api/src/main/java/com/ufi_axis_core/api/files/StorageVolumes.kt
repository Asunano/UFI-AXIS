package com.ufi_axis_core.api.files

import android.content.Context
import android.os.StatFs
import com.ufi_axis_core.util.AppLogger
import java.io.File

/**
 * 本机存储卷的唯一枚举源（内部存储、SD 卡、U 盘）。
 *
 * ## 唯一性要求
 *
 * 「文件管理」的存储弹窗（`/api/files/disk-usage`）与「应用商店」的内容目录选择器
 * （`/api/programs/volumes`）查询同一集合：本机有哪些卷、各剩多少空间。
 * 同一集合存在多份枚举实现时结果会漂移：文件管理可见的卷，商店可能只显示其中一部分。
 * 因此所有调用方必须使用本实现。
 *
 * ## 枚举策略
 *
 * 1. 内部存储：直接 `StatFs("/storage/emulated/0")`；
 * 2. 外接卷：走 `StorageManager.storageVolumes`（framework 的卷表），
 *    不得以 `File("/storage").listFiles()` 作为主路径 —— targetSdk 30+ 上它是挂载视图的
 *    不完整拷贝，可能漏掉已挂载的卷，且 `runCatching` 会吞掉异常；
 * 3. 仅在 StorageManager 完全无法枚举时退回列目录。
 *
 * 枚举失败一律降级，不抛异常：调用方是只读接口，不应因单个卷异常返回 500。
 */
object StorageVolumes {

    /** 内部存储挂载点。`/sdcard` 与 `/storage/self/primary` 都是它的别名。 */
    const val INTERNAL = "/storage/emulated/0"

    data class Volume(
        val label: String,
        val mount: String,
        val totalBytes: Long,
        val usedBytes: Long,
        val availableBytes: Long,
        /** `StorageVolume.isRemovable`：SD 卡 / U 盘为 true，内部存储为 false */
        val removable: Boolean
    )

    fun list(appContext: Context?): List<Volume> {
        val out = mutableListOf<Volume>()

        runCatching {
            val sf = StatFs(INTERNAL)
            val total = sf.totalBytes
            if (total > 0) {
                val avail = sf.availableBytes
                out += Volume("内部存储", INTERNAL, total, total - avail, avail, removable = false)
            }
        }.onFailure { AppLogger.w(TAG, "StatFs($INTERNAL) 失败: ${it.message}") }

        var sawExternal = false
        runCatching {
            val ctx = appContext ?: return@runCatching
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
                ?: return@runCatching
            sm.storageVolumes.forEach { v ->
                val dir = runCatching { v.directory }.getOrNull() ?: return@forEach
                if (!dir.isDirectory) return@forEach
                // 只收已挂载且可写的卷：
                // 光盘/ISO 分区（例如 UEFI_NTFS 启动盘）、只读挂载、正在弹出的卷都会出现在
                // storageVolumes 中，但无法写入。仅判 isDirectory 与 total>0 时，88KB 的只读
                // ISO 分区也会成为内容目录候选，直到开始下载才失败。
                // StorageVolume.getState() 返回 Environment 的挂载状态字符串（"mounted"/"unmounted"…）；
                // StorageVolume.STATE_MOUNTED 不是公开常量，不得使用。
                if (runCatching { v.state }.getOrNull() != android.os.Environment.MEDIA_MOUNTED) {
                    return@forEach
                }
                if (!dir.canWrite()) return@forEach
                val abs = dir.absolutePath.trimEnd('/')
                // 内部存储已登记；`/storage/emulated` 与 `/storage/self` 是同一卷的别名
                if (abs == INTERNAL || abs == "/storage/emulated" || abs == "/storage/self") return@forEach
                runCatching {
                    val sf = StatFs(abs)
                    val total = sf.totalBytes
                    // 容量过小的卷（EFI/启动分区，常见 1MB 左右）不构成有效的内容目录，
                    // 即使可写也不进入候选；同时排除 availableBytes == 0 的满卷与未知卷。
                    if (total >= MIN_CONTENT_VOLUME_BYTES && sf.availableBytes > 0) {
                        sawExternal = true
                        val avail = sf.availableBytes
                        val label = runCatching { v.getDescription(ctx) }.getOrNull()
                            ?.takeIf { it.isNotBlank() } ?: "外部存储"
                        out += Volume(label, abs, total, total - avail, avail, removable = v.isRemovable)
                    }
                }.onFailure { AppLogger.w(TAG, "StatFs($abs) 失败: ${it.message}") }
            }
        }.onFailure { AppLogger.w(TAG, "StorageManager 枚举失败: ${it.message}") }

        if (!sawExternal) {
            File("/storage").listFiles().orEmpty().forEach { d ->
                if (!d.isDirectory) return@forEach
                val name = d.name
                if (name == "emulated" || name == "self") return@forEach
                runCatching {
                    val sf = StatFs(d.absolutePath)
                    val total = sf.totalBytes
                    if (total > 0) {
                        val avail = sf.availableBytes
                        out += Volume("外部存储", d.absolutePath, total, total - avail, avail, removable = true)
                    }
                }.onFailure { AppLogger.w(TAG, "StatFs(${d.absolutePath}) 失败: ${it.message}") }
            }
        }

        if (out.isEmpty()) out += Volume("内部存储", INTERNAL, 0, 0, 0, removable = false)
        return out
    }

    /** 小于这个容量的卷不进入"内容目录"候选（EFI/启动分区）。与安装预检的 128MB 下限一致。 */
    private const val MIN_CONTENT_VOLUME_BYTES = 128L * 1024 * 1024

    private const val TAG = "StorageVolumes"
}
