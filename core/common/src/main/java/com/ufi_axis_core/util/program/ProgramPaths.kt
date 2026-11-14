package com.ufi_axis_core.util.program

import android.content.Context
import com.ufi_axis_core.util.BinaryComponentStore
import com.ufi_axis_core.util.LogPaths
import java.io.File
import java.nio.file.Files

/**
 * 第三方程序的目录布局定义。
 *
 * ## 独立于 `filesDir/components`
 *
 * [com.ufi_axis_core.util.BinaryComponentStore] 管理 frpc / cloudflared 这类单文件组件：
 * 无数据目录、无运行态。第三方程序需要 `bin/ + data/ + conf/ + run/ + state.json` 五部分，
 * 与组件混放会使「某个文件是否允许被覆盖」无法判定。因此使用独立目录树 `filesDir/programs`。
 *
 * ## 位于应用内部存储
 *
 * - `/data` 与 core 同卷，`rename` 为原子操作（安装的原子替换依赖此特性）；
 * - 权限天然满足：同 uid 可读写，不依赖 MANAGE_EXTERNAL_STORAGE；
 * - 第三方程序的数据库可能保存云盘 token，公共存储对同机所有应用可见。
 *
 * 代价是 `/data` 空间有限，由 `ProgramManifest.resources.dataQuotaMb` 约束：超限时告警，
 * 并拒绝启动，不静默失败。
 *
 * ## 目录布局
 *
 * ```
 * filesDir/programs/
 * ├── .tmp/                 下载/解包临时区（与 bin 同卷，保证 rename 原子）
 * └── <id>/
 *     ├── app.json           安装态元数据（版本/sha256/来源/入口名/实际端口）
 *     ├── state.json         运行态（期望运行/失败计数/熔断/最近一次退出原因）
 *     ├── bin/<entry>        可执行文件（chmod 700）
 *     ├── data/              程序自己的数据目录（argv 的 {DATA}）
 *     ├── conf/              配置（argv 的 {CONF}）
 *     └── run/               pid 文件、启动时间戳
 * ```
 *
 * 日志不在此目录：写入公共目录 `log/programs/<id>/`（见 [LogPaths]），以便文件管理器与
 * 导出功能直接读取；`/data` 对用户不可见。
 *
 * @param appContext 只用 `filesDir` / `cacheDir`，不持有 Activity 引用。
 */
class ProgramPaths(private val appContext: Context) {

    /** 程序根目录 `filesDir/programs`，首次访问时创建。 */
    val root: File
        get() = File(appContext.filesDir, ROOT_DIR).apply { if (!exists()) mkdirs() }

    /**
     * 下载/解包临时区。
     *
     * 必须与 [root] 同卷：安装最后一步是 `renameTo`，跨卷会退化为拷贝，而拷贝不是原子的 ——
     * 中途失败会留下不完整的可执行文件，且被误认为安装成功。
     */
    val tmpDir: File
        get() = File(root, TMP_DIR).apply { if (!exists()) mkdirs() }

    /** 程序自身的缓存目录（argv 的 `{CACHE}`）。位于 `cacheDir` 而非 `filesDir`，由系统回收。 */
    fun cacheDir(id: String): File = cacheDirPath(id).apply { if (!exists()) mkdirs() }

    /**
     * 缓存目录的纯路径，不创建目录。
     *
     * 体积统计与卸载必须使用本方法：`File.mkdirs()` 有副作用，`cacheBytes()` 仅测量大小时
     * 会创建目录；卸载时则先建空目录再删除。
     */
    private fun cacheDirPath(id: String): File = File(appContext.cacheDir, "$ROOT_DIR/${requireId(id)}")

    fun dirOf(id: String): File = File(root, requireId(id))

    fun binDir(id: String): File = File(dirOf(id), "bin")

    /** 可执行文件的绝对路径。不校验存在性，由调用方判断。 */
    fun binFile(id: String, entry: String): File = File(binDir(id), requireEntry(entry))

    /**
     * 数据目录。位置可配置：内部存储（`/data/data`，FBE 加密，速度较慢）或外部存储
     * （`/sdcard/Download/UFI-AXIS/programs/<id>/data`，速度快、用户可见、可备份）。
     *
     * 二进制始终位于内部存储：`/sdcard` 是 FUSE 挂载且为 `noexec`，无法执行。
     */
    fun dataDir(id: String, storage: StorageKind = StorageKind.INTERNAL): File =
        if (storage == StorageKind.EXTERNAL) File(externalDir(id), "data") else File(dirOf(id), "data")

    fun confDir(id: String, storage: StorageKind = StorageKind.INTERNAL): File =
        if (storage == StorageKind.EXTERNAL) File(externalDir(id), "conf") else File(dirOf(id), "conf")

    /**
     * 内容目录：程序写入大文件的位置（上传物、媒体库、共享目录）。
     *
     * 与 `data/`（数据库、密钥、用户信息）刻意分开：
     * - `data/` 保存凭据与状态，必须位于内部分区（`requires.private_data`）；
     * - `content/` 保存用户内容，可放在 SD 卡：设备内部存储通常为 18GB，扩展存储卡是常见配置。
     *
     * [rootOverride] 为安装时确定的绝对父目录（例如 `/storage/1A2B-3C4D/UFI-AXIS/content`）；
     * 为空表示使用内部默认位置（`programs/<id>/content`）。
     */
    fun contentDir(id: String, rootOverride: String? = null): File =
        if (rootOverride.isNullOrBlank()) File(dirOf(id), CONTENT_DIR)
        else File(rootOverride, requireId(id))

    /** 公共存储上的内容根：`<外部>/UFI-AXIS/programs/content`。 */
    fun externalContentRoot(): File = File(externalRoot(), CONTENT_DIR)

    /** 某个程序内容目录的字节数（空目录/不存在 = 0）。 */
    fun contentBytes(id: String, rootOverride: String? = null): Long =
        runCatching { dirSize(contentDir(id, rootOverride)) }.getOrDefault(0L)

    fun runDir(id: String): File = File(dirOf(id), "run")

    /** 安装态元数据 `app.json`。 */
    fun metaFile(id: String): File = File(dirOf(id), "app.json")

    /** 运行态 `state.json`（期望运行 / 失败计数 / 熔断标记 / 最近一次退出原因）。 */
    fun stateFile(id: String): File = File(dirOf(id), "state.json")

    /** 程序日志目录：`<外部存储>/UFI-AXIS/log/programs/<id>`。 */
    fun logDir(id: String): File =
        File(LogPaths.dir(LogPaths.Component.PROGRAMS), requireId(id))

    /**
     * 建齐程序所需的全部目录。安装时调用一次，启动前再次调用（幂等），
     * 防止用户或清理工具删除 `data/` 后程序自行在其他位置创建目录。
     */
    fun ensureDirs(id: String, storage: StorageKind = StorageKind.INTERNAL) {
        // bin/ 与 run/ 始终在内部存储；data/ 与 conf/ 由 storage 决定
        listOf(binDir(id), runDir(id), logDir(id), dataDir(id, storage), confDir(id, storage)).forEach {
            if (!it.exists()) it.mkdirs()
        }
    }

    /** 外部存储下的程序根：`/sdcard/Download/UFI-AXIS/programs/<id>` */
    fun externalDir(id: String): File = File(externalRoot(), requireId(id))

    /**
     * 外部存储当前是否可用。
     *
     * 判据是能否写入探针文件，而非 `dir.exists()`：存储卡拔出后挂载点通常仍然存在
     * （FUSE 缓存目录），`exists()` 返回 true 但写入失败。仅检查 `exists()` 会把已失效的
     * 数据目录判定为可用，问题延迟到运行期才暴露。
     */
    fun isStorageWritable(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        val probe = File(dir, ".ufi_write_probe")
        probe.writeText("1")
        probe.delete()
        true
    }.getOrDefault(false)

    /**
     * 该程序占用的字节数：`bin/` + `data/` + `conf/` + `content/` + `{CACHE}`。
     *
     * 必须显式传入 storage：data/ 可能位于内部或外部存储，按默认 INTERNAL 计算会使
     * 安装到外部存储的程序只显示二进制大小。
     *
     * `conf/` 与 `{CACHE}` 原先不计入：程序可以把大文件全塞进 TMPDIR 或配置目录，
     * 配额判据（`used > quota`）就永不成立 —— 等于没有配额。`run/` 仍不计（只放 socket/pid）。
     */
    fun usedBytes(
        id: String,
        storage: StorageKind = StorageKind.INTERNAL,
        contentRoot: String? = null
    ): Long = runCatching {
        dirSize(binDir(id)) +
            dirSize(dataDir(id, storage)) +
            dirSize(confDir(id, storage)) +
            dirSize(contentDir(id, contentRoot)) +
            dirSize(cacheDirPath(id))
    }.getOrDefault(0L)

    /**
     * 某程序的日志占用（`/sdcard/.../log/programs/<id>`）。
     *
     * 单独统计而不并入 [usedBytes]：日志与程序数据属于不同的配额语义
     * （日志按文件滚动截断，数据是用户内容），合并统计会使占用数字失去可解释性。
     */
    fun logBytes(id: String): Long = runCatching { dirSize(logDir(id)) }.getOrDefault(0L)

    /** 某程序的缓存占用（`<cacheDir>/programs/<id>`，程序的 TMPDIR）。系统可回收，但卸载时必须清。 */
    fun cacheBytes(id: String): Long = runCatching { dirSize(cacheDirPath(id)) }.getOrDefault(0L)

    /**
     * 商店总占用：内部程序目录 + 外部程序目录 + 缓存 + 程序日志。
     *
     * 必须包含日志与缓存：这几部分位于同一 `userdata` 分区（`/sdcard` 是 `/data/media` 的
     * emulated 视图，不是独立存储），漏算会使总占用明显偏小。
     */
    fun totalBytes(): Long = runCatching {
        val internal = root.listFiles()
            ?.filter { it.isDirectory && it.name != TMP_DIR }
            ?.sumOf { dirSize(it) } ?: 0L
        val external = externalRoot().listFiles()
            ?.filter { it.isDirectory }
            ?.sumOf { dirSize(it) } ?: 0L
        val cache = File(appContext.cacheDir, ROOT_DIR).let { if (it.exists()) dirSize(it) else 0L }
        val logs = dirSize(File(LogPaths.dir(LogPaths.Component.PROGRAMS)))
        internal + external + cache + logs
    }.getOrDefault(0L)

    /**
     * 清理临时区，服务启动时调用一次。安装中断会留下 `.tmp/xxx.download`，
     * 单个文件可达数十 MB（OpenList 的包为 49MB），长期占用存储空间。
     */
    fun cleanupTemp() {
        runCatching {
            tmpDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * 删除某个程序的全部内容（卸载）。
     *
     * `bin/` 中可能存在指向 `data/` 的软链（Cloudreve 的 `bin/data`），`deleteRecursively`
     * 会跟随该软链 —— 其目标为本程序自己的数据目录，因此结果与「卸载即删数据」一致；
     * 禁止将软链指向程序目录之外，否则卸载会删除其他程序的数据。
     *
     * 删除顺序为先 `bin/` 后其余目录：二进制是「是否已安装」的判据，优先移除后，
     * 即使 `data/` 删除失败，UI 也不会出现「已卸载但仍可启动」的不一致状态。
     *
     * @return 未能删除的路径（空 = 全部删除成功）。未能删除通常因文件仍被进程打开。
     */
    fun deleteAll(
        id: String,
        storage: StorageKind = StorageKind.INTERNAL,
        contentRoot: String? = null,
        /**
         * 是否同时删除 `log/programs/<id>/`（彻底卸载）。
         *
         * 默认 false：日志是卸载前事件的唯一记录，删除后停止、重启、卸载的过程无法回溯。
         * 仅在用户显式确认「同时删除日志」时传 true。
         */
        deleteLogs: Boolean = false,
    ): List<String> {
        val failures = mutableListOf<String>()
        val dirs = buildList {
            add(binDir(id))
            add(dataDir(id, storage))
            add(confDir(id, storage))
            add(runDir(id))
            add(dirOf(id))
            // 外部存储的子目录：data/conf 位于 <外部>/programs/<id>/ 下
            add(externalDir(id))
            // {CACHE} 必须一并删除：程序的 TMPDIR 指向该目录，残留可达数十 MB
            add(cacheDirPath(id))
            if (deleteLogs) add(logDir(id))
        }

        // 内容目录可能位于 SD 卡，且不在上述任何路径中，必须单独删除：否则卸载后数 GB 的
        // 上传物会保留在卡上，用户无从发现。
        // 删除目标是 `<用户选的根>/<id>`，该子目录由安装流程创建，不是用户自建目录。
        contentRoot?.takeIf { it.isNotBlank() }?.let { root ->
            val dir = contentDir(id, root)
            if (dir.exists() && !deleteTreeNoFollow(dir) && dir.exists()) {
                failures += dir.absolutePath
            }
        }
        for (d in dirs) {
            if (!d.exists()) continue
            if (!deleteTreeNoFollow(d) && d.exists()) failures += d.absolutePath
        }
        return failures
    }

    /**
     * 递归删除，**不跟随软链**。
     *
     * `File.deleteRecursively()` 会穿过目录里的符号链接：程序（与 core 同 uid）在 `bin/` 里放一条
     * `data -> /DCIM` 或 `run -> /data/data/别的应用` 的软链，用户点一次「卸载」就会把目标目录
     * 的内容删掉，现场看起来还像"卸载"造成的。
     *
     * `Files.walkFileTree` 默认就不跟随（没有 `FOLLOW_LINKS` 选项），对软链调用 `delete`
     * 只删链接本身。
     *
     * @return true = 目录已不存在
     */
    private fun deleteTreeNoFollow(root: File): Boolean = runCatching {
        java.nio.file.Files.walkFileTree(
            root.toPath(),
            object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(
                    file: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    runCatching { java.nio.file.Files.deleteIfExists(file) }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: java.nio.file.Path,
                    exc: java.io.IOException?,
                ): java.nio.file.FileVisitResult {
                    runCatching { java.nio.file.Files.deleteIfExists(dir) }
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
        !root.exists()
    }.getOrDefault(false)

    /**
     * 递归算目录大小。
     *
     * 刻意不限制深度、不做缓存：一次只统计 1–2 个程序，量级为百 MB，遍历数万文件的
     * 耗时在毫秒级。缓存会引入数字不更新的问题：数据目录由第三方程序写入，
     * core 无法知晓其何时失效。
     */
    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        // 不跟随软链：Cloudreve 的 `bin/data` 是指向 `{DATA}/data` 的软链，
        // 跟随会使同一份数据被统计两次（bin 与 data 各一次），占用数值翻倍。
        if (Files.isSymbolicLink(dir.toPath())) return 0L
        if (dir.isFile) return dir.length()
        var sum = 0L
        dir.listFiles()?.forEach { sum += dirSize(it) }
        return sum
    }

    /** 数据目录的位置。二进制始终位于内部存储，不受此值影响。 */
    enum class StorageKind { INTERNAL, EXTERNAL }

    companion object {
        private const val ROOT_DIR = "programs"
        private const val TMP_DIR = ".tmp"

        /** 内容目录的固定名字：与 data/conf 同级或挂在外部根下。 */
        const val CONTENT_DIR = "content"

        /** 外部存储的程序根：`<外部存储>/UFI-AXIS/programs`，与日志目录同级 */
        fun externalRoot(): File = File(LogPaths.appRoot(), ROOT_DIR)

        /** 把 manifest/元数据里的字符串解析成枚举；未知值按内部处理（保守） */
        fun parseStorage(raw: String?): StorageKind =
            if (raw.equals("external", ignoreCase = true)) StorageKind.EXTERNAL else StorageKind.INTERNAL

        /**
         * id 白名单校验，**复用** [BinaryComponentStore.requireValidId]。
         *
         * 不在此处重复实现正则：id 会被直接拼接进文件路径，且来源于 HTTP 路径参数，
         * 构成路径穿越攻击面。「id 的合法格式」只保留一份实现，后续收紧规则
         * （例如禁用 `-`）时不会遗漏。
         */
        fun requireId(id: String): String = BinaryComponentStore.requireValidId(id)

        /**
         * 入口文件名只允许单段文件名。
         *
         * 与 [requireId] 同理：`entry` 来自远端清单（`version.json`），清单一旦被污染，
         * `../../shared_prefs/x` 可写入任意位置。此处只允许 `[A-Za-z0-9._-]`，
         * 且拒绝 `.` / `..`，因此不含路径分隔符，无法穿越。
         */
        fun requireEntry(entry: String): String {
            require(entry.isNotBlank()) { "入口文件名为空" }
            require(entry != "." && entry != "..") { "非法入口文件名: $entry" }
            require(ENTRY_REGEX.matches(entry)) { "非法入口文件名: $entry" }
            return entry
        }

        private val ENTRY_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}
