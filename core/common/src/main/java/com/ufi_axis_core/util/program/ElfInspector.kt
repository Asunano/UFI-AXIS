package com.ufi_axis_core.util.program

import java.io.File
import java.io.RandomAccessFile

/**
 * ELF 可执行文件体检（2026-10-30 应用商店 P0）。
 *
 * ## 为什么不直接用 `BinaryComponentStore.validateAarch64Elf`
 *
 * 那个函数只读前 20 字节（magic / EI_CLASS / e_machine），对 frpc、cloudflared 够用 ——
 * 它们都是上游明确给 Linux 准备的静态 Go 二进制。但对"第三方程序"不够，会放过两类
 * **装完才炸**的包：
 *
 * 1. **动态链接 glibc 的二进制**。典型例子：OpenList 的 `openlist-linux-arm64` 是
 *    `interpreter /lib/ld-linux-aarch64.so.1`，而 Android 既没有 glibc 也没有这个路径，
 *    一执行就是 `ENOENT`。只看 e_machine 的话它是"完美合法"的 arm64 ELF。
 * 2. **架构对但链接方式不对**的其他变体（musl 静态 vs bionic 动态 vs glibc 动态）。
 *
 * 所以这里必须读 **program headers**，把 `PT_INTERP`（解释器路径）拿出来做判定。
 *
 * ## 判定口径（2026-10-30 调研实测得出，见 `dev-docs/第三方Linux程序-Android运行调研-2026-10-30.md`）
 *
 * | interp | 判定 | 说明 |
 * |---|---|---|
 * | 不存在 | [Linkage.STATIC] | 全静态，最理想（AdGuardHome 就是这种） |
 * | `/system/bin/linker64` 或 `/system/bin/linker` | [Linkage.ANDROID_DYNAMIC] | bionic，**正常可跑**（OpenList 的 android 构建） |
 * | 含 `ld-linux` / `ld-musl`（如 `/lib/ld-linux-aarch64.so.1`） | [Linkage.FOREIGN_DYNAMIC] | glibc/musl 解释器，Android 上没有，**必须拒装** |
 * | 其他 | [Linkage.UNKNOWN_DYNAMIC] | 不认识就拒，不赌 |
 *
 * ## 额外检查：页对齐
 *
 * Android 15 起部分设备是 16KB page。`p_align = 0x1000`（4KB）的可执行文件在 16KB
 * 设备上加载不了。这里**只记录不拒绝** —— 目标机型（Android 11–13 的 UFI/CPE）都是 4KB，
 * 拒了等于把现在能用的机器挡在门外。真要拦，等有 16KB 机型实测再说。
 *
 * ## 不做的事
 *
 * 不校验签名、不跑 `--version`、不判断"这个程序会不会自己崩"。体检只回答
 * "内核会不会拒绝 exec 它"，其他都在运行时暴露。
 */
object ElfInspector {

    private const val TAG = "ElfInspector"

    /** ELF64 头长度 */
    private const val EHDR_SIZE = 64

    /** ELF64 program header 长度 */
    private const val PHDR_SIZE = 56

    private const val PT_LOAD = 1L
    private const val PT_INTERP = 3L

    /** `e_machine` = EM_AARCH64 */
    private const val EM_AARCH64 = 0xB7

    /** 异常清单的防御上限：真实文件不会超过几十个段，超过就是文件坏了 */
    private const val MAX_PHDRS = 512

    enum class Linkage {
        /** 无 `PT_INTERP`：全静态 */
        STATIC,

        /** `/system/bin/linker64`：Android bionic，正常可跑 */
        ANDROID_DYNAMIC,

        /** `ld-linux*` / `ld-musl*`：非 Android 解释器，跑不起来 */
        FOREIGN_DYNAMIC,

        /** 有 `PT_INTERP` 但路径不认识 */
        UNKNOWN_DYNAMIC
    }

    data class Info(
        val is64Bit: Boolean,
        val machine: Int,
        val linkage: Linkage,
        /** 原始解释器路径；静态为 null */
        val interp: String?,
        /** 是否 PIE（`e_type == 3`） */
        val isPie: Boolean,
        /** 所有 `PT_LOAD` 段里最小的 `p_align`；0 表示没读到 */
        val minLoadAlign: Long
    ) {
        /** 是否适合在本设备执行（只看链接方式） */
        val runnable: Boolean
            get() = linkage == Linkage.STATIC || linkage == Linkage.ANDROID_DYNAMIC

        /** 给人看的一句话 */
        fun describe(): String = when (linkage) {
            Linkage.STATIC -> "静态链接（无解释器）"
            Linkage.ANDROID_DYNAMIC -> "Android 动态链接（${interp.orEmpty()}）"
            Linkage.FOREIGN_DYNAMIC -> "非 Android 动态链接（${interp.orEmpty()}），Android 上无法执行"
            Linkage.UNKNOWN_DYNAMIC -> "未知解释器（${interp.orEmpty()}），出于安全拒绝"
        }
    }

    /**
     * 读 ELF 头与 program headers。
     *
     * @return 解析失败时 `Result.failure`，message 是**给人看的中文原因**
     *         （会直接出现在安装失败提示里，不要写英文异常串）。
     */
    fun inspect(file: File): Result<Info> = runCatching {
        require(file.exists()) { "文件不存在: ${file.name}" }
        require(file.isFile) { "不是普通文件: ${file.name}" }
        val len = file.length()
        require(len >= EHDR_SIZE) { "文件过小（$len 字节），不是有效的可执行文件" }

        RandomAccessFile(file, "r").use { raf ->
            val ehdr = ByteArray(EHDR_SIZE)
            raf.readFully(ehdr)

            // magic: 7F 'E' 'L' 'F'
            require(
                ehdr[0] == 0x7F.toByte() && ehdr[1] == 'E'.code.toByte() &&
                    ehdr[2] == 'L'.code.toByte() && ehdr[3] == 'F'.code.toByte()
            ) { "不是 ELF 可执行文件（可能下载到的是 HTML 错误页或压缩包）" }

            val eiClass = ehdr[4].toInt() and 0xFF
            require(eiClass == 2) { "不是 64 位 ELF（EI_CLASS=$eiClass）" }

            val eiData = ehdr[5].toInt() and 0xFF
            require(eiData == 1) { "不是小端 ELF（EI_DATA=$eiData）" }

            val eType = u16(ehdr, 16)
            val machine = u16(ehdr, 18)
            require(machine == EM_AARCH64) {
                "架构不匹配：期望 arm64-v8a(0xB7)，实际 0x${machine.toString(16)}"
            }

            val phOff = u64(ehdr, 32)
            val phEntSize = u16(ehdr, 54)
            val phNum = u16(ehdr, 56)

            // 没有 program header（可重定位目标文件）不是可执行文件
            require(phOff > 0 && phNum > 0) { "没有 program header，不是可执行文件" }
            require(phEntSize >= PHDR_SIZE) { "program header 尺寸异常（$phEntSize）" }
            require(phNum <= MAX_PHDRS) { "program header 数量异常（$phNum）" }
            require(phOff + phNum.toLong() * phEntSize <= len) { "program header 超出文件末尾（文件被截断？）" }

            val phdr = ByteArray(PHDR_SIZE)
            var interpPath: String? = null
            var minAlign = 0L

            for (i in 0 until phNum) {
                raf.seek(phOff + i.toLong() * phEntSize)
                raf.readFully(phdr)
                val pType = u32(phdr, 0)
                val pOffset = u64(phdr, 8)
                val pFilesz = u64(phdr, 32)
                val pAlign = u64(phdr, 48)

                when (pType) {
                    PT_INTERP -> {
                        // 防御：解释器路径不可能超过 4KB，超了就是文件坏了
                        if (pFilesz in 1..4096 && pOffset + pFilesz <= len) {
                            val buf = ByteArray(pFilesz.toInt())
                            raf.seek(pOffset)
                            raf.readFully(buf)
                            interpPath = String(buf, Charsets.UTF_8).trimEnd('\u0000').trim()
                        }
                    }
                    PT_LOAD -> {
                        if (pAlign > 0 && (minAlign == 0L || pAlign < minAlign)) minAlign = pAlign
                    }
                }
            }

            val linkage = classify(interpPath)
            Info(
                is64Bit = true,
                machine = machine,
                linkage = linkage,
                interp = interpPath,
                isPie = eType == 3,
                minLoadAlign = minAlign
            )
        }
    }

    /**
     * 安装前校验：通过返回 null，否则返回**中文原因**（口径与
     * `BinaryComponentStore.validateAarch64Elf` 一致，方便调用方原样透传给前端）。
     */
    fun validate(file: File, abi: String = "arm64-v8a"): String? {
        require(abi == "arm64-v8a") { "暂不支持 ABI: $abi" }
        val info = inspect(file).getOrElse { e -> return e.message ?: "无法解析可执行文件" }
        return when (info.linkage) {
            Linkage.STATIC -> null
            Linkage.ANDROID_DYNAMIC -> null
            Linkage.FOREIGN_DYNAMIC ->
                "这是 ${info.interp} 动态链接的程序，Android 没有该解释器，装上也无法执行（请换用 android 构建或静态构建）"
            Linkage.UNKNOWN_DYNAMIC ->
                "解释器 ${info.interp} 不是 Android 支持的路径，已拒绝安装"
        }
    }

    /** 解释器路径 → 链接方式。空 = 静态。 */
    private fun classify(interp: String?): Linkage {
        if (interp.isNullOrBlank()) return Linkage.STATIC
        // Android 的链接器：/system/bin/linker64（arm64）/ linker（32 位）
        if (interp.startsWith("/system/bin/linker")) return Linkage.ANDROID_DYNAMIC
        // glibc / musl：Linux 发行版才有
        if (interp.contains("ld-linux") || interp.contains("ld-musl")) return Linkage.FOREIGN_DYNAMIC
        return Linkage.UNKNOWN_DYNAMIC
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun u64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }
}
