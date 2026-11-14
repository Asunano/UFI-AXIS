package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.AppLogger
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * Cloudreve 的适配器：把 SQLite 库钉到 `{DATA}` 里，并保证监听端口与清单一致。
 *
 * ## 为什么需要它（不写适配器会怎样）
 *
 * Cloudreve v4 里"相对路径"有两套基准（源码 `pkg/util/path.go`）：
 * `util.RelativePath()` 在 `-w` 下返回原样（相对 cwd），否则拼到可执行目录。
 * 但 2026-11-04 两次实测（t1 不带 `-w`、t2 带 `-w`）都显示：**不写 `[Database] DBFile` 时，
 * 库稳定落在 `<可执行目录>/data/cloudreve.db`**。在本项目里那就是 `programs/<id>/bin/`。
 *
 * 把库放进二进制目录有三个问题：
 *
 * 1. **`storage=external` 的用户被坑**：他选"外部存储"就是为了数据可见/可备份，
 *    结果 `{DATA}` 在 /sdcard、库却留在内部 —— 备份出来的是一堆文件加一个空站点；
 * 2. **bin/ 是"可整体替换"的目录**：`ProgramStore.install` 会替换 `bin/<entry>`，
 *    把数据放里面等于赌"将来没有任何清理逻辑会碰 bin/"；
 * 3. UI 的"占用"是 bin + data 之和，库混在 bin 里，用户分不清哪部分才是自己的数据。
 *
 * **配额不在这三条里**：`ProgramPaths.usedBytes` 统计的是 bin + data，bin 里的库一样被计入。
 * 所以"配额看不见"的说法是错的，不要拿它当理由。
 *
 * 结论：在**首次启动前**写一份 `conf.ini`，把 `[Database] DBFile` 钉成绝对路径，
 * 让库跟着 `{DATA}` 走。
 *
 * ## 上传文件的落点：用一条软链钉死，不靠猜
 *
 * 上传目录由存储策略决定（DB 里 `storage_policies.dir_name_rule = "data/uploads/{uid}/{path}"`，
 * 实测值），是**相对路径**。它有两种可能的基准：`-w` 生效时是 cwd（= `{DATA}`），
 * 不生效时是可执行目录（`{BIN}`）。而 DB 路径的实测行为说明"`-w` 未必在所有初始化之前生效"，
 * 所以两种都可能。
 *
 * 解法不是二选一，而是**让两条路通向同一个地方**：在 `{BIN}/data` 放一条指向 `{DATA}/data`
 * 的软链。于是：
 * - `-w` 生效 → 上传落 `{DATA}/data/uploads`
 * - `-w` 没生效 → 上传落 `{BIN}/data/uploads` → 软链 → `{DATA}/data/uploads`
 *
 * 两条路都落在 `{DATA}` 里，配额、备份、卸载三件事的口径才唯一。
 * 建立失败时**不阻断启动**（宁可位置不理想，也不让用户打不开网盘），只记一条 WARN。
 *
 * ## 为什么敢自己生成 conf.ini（而不是像 OpenList 那样先跑一次让它生成）
 *
 * OpenList 的 `config.json` 含 `jwt_secret` 等"猜错就起不来"的字段，所以那边是
 * "跑一次 → 改一处"。Cloudreve 的 `conf.ini` 只有三样东西有意义，且**缺省项由程序在内存里补**：
 *
 * ```ini
 * [System]
 * Listen = :5212
 * SessionSecret = <64 位随机>
 *
 * [Database]
 * Type = sqlite3
 * DBFile = <绝对路径>
 * ```
 *
 * 2026-11-04 用这份最小配置在真机同架构二进制上实跑：库建在指定绝对路径、`/api/v4/site/ping`
 * 返回 200、`/setup` 返回 200。**没有出现"缺字段起不来"**。所以不需要多一次启动
 * （多一次启动等于多一次"杀掉半初始化的库"的风险）。
 *
 * ## 三件必须守住的事
 *
 * 1. **幂等**：每次启动都会调 [preflight]，改完再改必须 no-op（不能每次启动都重写文件）。
 * 2. **不覆盖用户的 `DBFile`**：只有当键缺失、或值等于我们自己的旧默认位置时才改写。
 *    用户手工改到别处是他的选择，静默改回去等于"库不见了"。
 * 3. **旧库要跟着搬**：老版本（键缺失、库在 `bin/data/`）升级上来时，把库 rename 到新位置，
 *    否则用户看到的是一个空网盘。
 *
 * ## 不做的事
 *
 * 不设管理员（Cloudreve 的首次配置是浏览器向导 `/setup`，没有"初始口令"这种东西），
 * 也因此不实现 `bootstrapSecret`。
 */
class CloudreveAdapter : ProgramAdapter {

    override val id = "cloudreve"

    override fun preflight(ctx: ProgramContext): Result<Unit> = runCatching {
        ctx.dataDir.mkdirs()
        ctx.confDir.mkdirs()

        // 顺序有讲究：先让 bin/data 指向 {DATA}/data，再写 conf ——
        // 万一后续某一步失败，程序也不会以"上传落在 bin/"的状态起来。
        ensureDataLink(ctx)
        ensureUploadsLink(ctx)
        ensureConf(ctx)
    }

    /**
     * 写/校正 `{CONF}/conf.ini`。
     *
     * 配置放 `{CONF}` 而不是 `{DATA}`：库里除了口令哈希还有会话密钥，而 `{CONF}` 的语义
     * 就是"这个程序的配置"。顺带把老版本放在 `{DATA}/conf.ini` 的那份搬过来
     * （否则会生成一份新的、把用户的 SessionSecret 换掉 → 所有登录态失效）。
     */
    private fun ensureConf(ctx: ProgramContext) {
        val confFile = File(ctx.confDir, CONF_NAME)
        val legacyConf = File(ctx.dataDir, CONF_NAME)
        val dbFile = File(ctx.dataDir, DB_NAME)
        // gateway 模式必须钉在回环，否则用户在 App 里选了"经后台转发"，程序仍然监听
        // 0.0.0.0，局域网可绕过 Gateway 直接访问。direct 才用 ":port"（所有网卡）。
        val listen = if (ctx.accessMode == "gateway") {
            "127.0.0.1:${ctx.internalPort}"
        } else {
            ":${ctx.internalPort}"
        }

        if (!confFile.exists() && legacyConf.exists() && legacyConf.length() > 0) {
            val moved = runCatching { legacyConf.renameTo(confFile) }.getOrDefault(false)
            if (moved) {
                AppLogger.i(TAG, "conf.ini 已从 {DATA} 迁到 {CONF}")
            } else {
                // 跨卷/被占用：退化成拷贝，拷贝成功后再删旧的，避免两头各留一份互相打架
                runCatching { legacyConf.copyTo(confFile, overwrite = true); legacyConf.delete() }
            }
        }

        if (!confFile.exists() || confFile.length() == 0L) {
            writeAtomically(
                confFile,
                CloudreveConf.render(
                    listen = listen,
                    sessionSecret = randomSecret(),
                    dbPath = dbFile.absolutePath
                )
            )
            migrateLegacyDb(ctx, dbFile)
            AppLogger.i(TAG, "已生成 conf.ini（Listen=$listen，DB=${dbFile.absolutePath}）")
            return
        }

        val patch = CloudreveConf.patch(
            text = confFile.readText(),
            listen = listen,
            sessionSecret = null,
            dbPath = dbFile.absolutePath
        )

        patch.notes.forEach { AppLogger.w(TAG, it) }

        if (patch.changed) {
            writeAtomically(confFile, patch.text)
            AppLogger.i(TAG, "conf.ini 已按清单校正（变更：${patch.changedKeys.joinToString()}）")
            if (patch.dbFileIntroduced) migrateLegacyDb(ctx, dbFile)
        }
    }

    /**
     * 把 `{BIN}/data` 变成指向 `{DATA}/data` 的软链。
     *
     * - 已经是软链且指向正确 → no-op（幂等）
     * - 已经是软链但指向别处（例如上次软链到了旧的数据目录）→ 重建
     * - 是**真实目录**（老版本没有软链时 cloudreve 自己建的空目录）→ 先把里面的内容并进
     *   `{DATA}/data`，再删目录、建软链。**必须先搬再删**，否则那几次上传就没了。
     *
     * 用 `Files.isSymbolicLink` 而不是 `exists()`：断链时 `exists()` 返回 false，
     * 会被当成"不存在"再建一次，而 `createSymbolicLink` 对已存在的断链会抛 FileAlreadyExists。
     */
    private fun ensureDataLink(ctx: ProgramContext) {
        val dataRoot = File(ctx.dataDir, LINK_NAME).apply { if (!exists()) mkdirs() }
        val link = File(ctx.binPath.parentFile, LINK_NAME)
        val linkPath = link.toPath()

        if (Files.isSymbolicLink(linkPath)) {
            val current = runCatching { Files.readSymbolicLink(linkPath).toFile().canonicalPath }.getOrNull()
            if (current == runCatching { dataRoot.canonicalPath }.getOrNull()) return
            runCatching { Files.delete(linkPath) }
                .onFailure { AppLogger.w(TAG, "删除旧软链失败：${it.message}"); return }
        } else if (link.exists()) {
            mergeInto(link, dataRoot)
            if (!link.deleteRecursively()) {
                AppLogger.w(TAG, "${link.absolutePath} 不是软链且无法替换，上传可能落在 bin/ 下")
                return
            }
        }

        runCatching { Files.createSymbolicLink(linkPath, dataRoot.toPath()) }
            .onSuccess { AppLogger.i(TAG, "${link.absolutePath} → ${dataRoot.absolutePath}") }
            .onFailure { AppLogger.w(TAG, "建立软链失败（${it.message}），上传可能落在 bin/ 下") }
    }

    /**
     * 把 `{DATA}/data/uploads` 指到 `{CONTENT}` —— 上传物（大文件）与数据目录分家。
     *
     * 为什么是软链而不是改存储策略：策略存在 Cloudreve 自己的数据库里（`storage_policies.dir_name_rule`），
     * 改它等于让适配器去动表结构，跨版本必坏；而策略本来就是相对路径，软链一层就够，且对版本无感。
     *
     * 两种基准在这里同样会收敛：
     * - `-w` 生效 → `{DATA}/data/uploads`
     * - 不生效 → `{BIN}/data/uploads` →（`bin/data` 那条软链）→ `{DATA}/data/uploads`
     *
     * 老数据（真实目录）先并入 `{CONTENT}` 再替换，避免升级后"上传的东西不见了"。
     */
    private fun ensureUploadsLink(ctx: ProgramContext) {
        val uploads = File(ctx.dataDir, "$LINK_NAME/uploads")
        val content = ctx.contentDir.apply { mkdirs() }
        uploads.parentFile?.mkdirs()

        if (Files.isSymbolicLink(uploads.toPath())) {
            val current = runCatching { Files.readSymbolicLink(uploads.toPath()).toFile().canonicalPath }.getOrNull()
            if (current == runCatching { content.canonicalPath }.getOrNull()) return
            runCatching { Files.delete(uploads.toPath()) }
                .onFailure { AppLogger.w(TAG, "删除旧 uploads 软链失败：${it.message}"); return }
        } else if (uploads.exists()) {
            mergeInto(uploads, content)
            if (!uploads.deleteRecursively()) {
                AppLogger.w(TAG, "${uploads.absolutePath} 无法替换，上传仍落在数据目录")
                return
            }
        }

        runCatching { Files.createSymbolicLink(uploads.toPath(), content.toPath()) }
            .onSuccess { AppLogger.i(TAG, "上传目录 → ${content.absolutePath}") }
            .onFailure { AppLogger.w(TAG, "建立 uploads 软链失败（${it.message}），上传仍落在数据目录") }
    }

    /** 把 [from] 里的内容并到 [into]（同名不覆盖：保守，避免把新数据冲掉）。 */
    private fun mergeInto(from: File, into: File) {
        into.mkdirs()
        from.listFiles()?.forEach { child ->
            val target = File(into, child.name)
            if (target.exists()) {
                AppLogger.w(TAG, "迁移跳过同名项：${target.absolutePath}")
                return@forEach
            }
            val ok = runCatching { child.renameTo(target) }.getOrDefault(false)
            if (!ok) runCatching { child.copyRecursively(target, overwrite = false); child.deleteRecursively() }
        }
    }

    /**
     * 把老位置（`bin/data/cloudreve.db`）的库搬到 `{DATA}`。
     *
     * 只在"新位置还没有库"时搬，且只搬 SQLite 的三个文件（db / -wal / -shm）：
     * 少搬 `-wal` 会丢最近未 checkpoint 的事务，这是"能打开但少了最后几条记录"的经典来源。
     */
    private fun migrateLegacyDb(ctx: ProgramContext, target: File) {
        if (target.exists()) return
        val legacyDir = File(ctx.binPath.parentFile, LEGACY_SUBDIR)
        val legacy = File(legacyDir, DB_NAME)
        if (!legacy.exists()) return

        val moved = runCatching { legacy.renameTo(target) }.getOrDefault(false)
        if (!moved) {
            AppLogger.w(TAG, "旧库搬家失败，保留原位置：${legacy.absolutePath}")
            return
        }
        for (suffix in listOf("-wal", "-shm")) {
            val side = File(legacyDir, DB_NAME + suffix)
            if (side.exists()) runCatching { side.renameTo(File(target.absolutePath + suffix)) }
        }
        AppLogger.i(TAG, "旧库已搬到 ${target.absolutePath}")
    }

    /**
     * 原子写：先写同目录 `.tmp` 再 rename。
     *
     * 设备随时可能被拔电，`writeText` 中途断电会留下**半截 conf.ini** —— 那比没有更糟：
     * Cloudreve 会把它当合法配置读，然后以一个残缺的 `[Database]` 起来。
     */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun randomSecret(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return buildString(64) { repeat(64) { append(chars[rnd.nextInt(chars.length)]) } }
    }

    private companion object {
        const val TAG = "CloudreveAdapter"
        const val CONF_NAME = "conf.ini"
        const val DB_NAME = "cloudreve.db"
        /** cloudreve 自己的数据子目录名：`bin/data` 与 `{DATA}/data` 用同一个名字，软链才对得上 */
        const val LINK_NAME = "data"
        /** Cloudreve 把相对 DBFile 解析到"可执行文件目录/data/" */
        const val LEGACY_SUBDIR = "data"
    }
}

/**
 * `conf.ini` 的最小读写器（纯函数，便于单测）。
 *
 * 只做两件事：从零渲染一份可用配置；在既有配置上"增量校正"指定键。
 * **不解析 `[Section]` 之外的语法**（Cloudreve 的 ini 只有 `key = value` 与空行），
 * 未知行原样保留 —— 这条决定了"用户的其它手工配置不会被吃掉"。
 */
internal object CloudreveConf {

    data class PatchResult(
        val text: String,
        val changed: Boolean,
        val changedKeys: List<String>,
        /** 本次是否"新写了 DBFile"（据此决定要不要搬旧库） */
        val dbFileIntroduced: Boolean,
        val notes: List<String>
    )

    fun render(listen: String, sessionSecret: String, dbPath: String): String =
        buildString {
            appendLine("[System]")
            appendLine("Listen = $listen")
            appendLine("SessionSecret = $sessionSecret")
            appendLine()
            appendLine("[Database]")
            appendLine("Type = sqlite3")
            appendLine("DBFile = $dbPath")
        }

    /**
     * 增量校正。
     *
     * - `Listen`：**强制对齐清单端口**。健康检查探的就是 `internal_port`，端口被改动后
     *   程序会一直"启动即失败"并被看护反复重启 —— 那是比"尊重用户手改"更糟的结局。
     * - `SessionSecret`：缺失才补（`null` 表示调用方不希望生成，测试用）。
     * - `DBFile`：**只在缺失时写**；已存在且指向别处时保持不动，只给一条提示。
     *
     * 返回值里的 `dbFileIntroduced` 是给"搬旧库"用的：只有本次真的是我们填的值，
     * 老库搬到这个新位置才有意义。
     */
    fun patch(text: String, listen: String, sessionSecret: String?, dbPath: String): PatchResult {
        val lines = text.split('\n').toMutableList()
        val changed = mutableListOf<String>()
        val notes = mutableListOf<String>()

        var section = ""
        var seenListen = false
        var seenSecret = false
        var seenType = false
        var seenDb = false
        var dbFileIntroduced = false

        for (i in lines.indices) {
            val raw = lines[i]
            val header = SECTION_RE.find(raw)?.groupValues?.get(1)?.trim()
            if (header != null) {
                section = header
                continue
            }
            val kv = parseKv(raw) ?: continue
            when {
                section == SECTION_SYSTEM && kv.key.equals("Listen", true) -> {
                    seenListen = true
                    if (kv.value != listen) {
                        lines[i] = rebuild(kv, listen)
                        changed += "System.Listen"
                    }
                }
                section == SECTION_SYSTEM && kv.key.equals("SessionSecret", true) -> {
                    seenSecret = true
                    if (sessionSecret != null && kv.value.isBlank()) {
                        lines[i] = rebuild(kv, sessionSecret)
                        changed += "System.SessionSecret"
                    }
                }
                section == SECTION_DATABASE && kv.key.equals("Type", true) -> {
                    seenType = true
                    if (kv.value.isBlank()) {
                        lines[i] = rebuild(kv, "sqlite3")
                        changed += "Database.Type"
                    } else if (!kv.value.equals("sqlite3", true) && !kv.value.equals("sqlite", true)) {
                        notes += "数据库类型为「${kv.value}」，非内置 sqlite，配额统计将不覆盖它"
                    }
                }
                section == SECTION_DATABASE && kv.key.equals("DBFile", true) -> {
                    seenDb = true
                    if (kv.value.isBlank() || kv.value == LEGACY_RELATIVE) {
                        lines[i] = rebuild(kv, dbPath)
                        changed += "Database.DBFile"
                        dbFileIntroduced = true
                    } else if (kv.value != dbPath) {
                        notes += "数据库已被配置到「${kv.value}」，保持不动（数据不会自动搬迁）"
                    }
                }
            }
        }

        // 缺段落/缺键：按需要补在文件末尾（Cloudreve 不要求顺序，只要求段名正确）
        if (!seenListen) {
            changed += "System.Listen"
            lines.add(ensureSection(lines, SECTION_SYSTEM), "Listen = $listen")
        }
        if (sessionSecret != null && !seenSecret) {
            changed += "System.SessionSecret"
            lines.add(ensureSection(lines, SECTION_SYSTEM), "SessionSecret = $sessionSecret")
        }
        if (!seenType) {
            changed += "Database.Type"
            lines.add(ensureSection(lines, SECTION_DATABASE), "Type = sqlite3")
        }
        if (!seenDb) {
            changed += "Database.DBFile"
            dbFileIntroduced = true
            lines.add(ensureSection(lines, SECTION_DATABASE), "DBFile = $dbPath")
        }

        return PatchResult(
            text = lines.joinToString("\n").trimEnd('\n') + "\n",
            changed = changed.isNotEmpty(),
            changedKeys = changed,
            dbFileIntroduced = dbFileIntroduced,
            notes = notes
        )
    }

    /** 保证段存在；返回"该段末尾"的下标（用于插入新键）。段不存在时在文件末尾新建。 */
    private fun ensureSection(lines: MutableList<String>, name: String): Int {
        val start = lines.indexOfFirst { SECTION_RE.find(it)?.groupValues?.get(1)?.trim() == name }
        if (start < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("[$name]")
            lines.add("")
            return lines.size - 1
        }
        var end = lines.size
        for (i in start + 1 until lines.size) {
            if (SECTION_RE.find(lines[i]) != null) { end = i; break }
        }
        // 收敛掉段尾空行，保证新键落在段内
        var insertAt = end
        while (insertAt > start + 1 && lines[insertAt - 1].isBlank()) insertAt--
        return insertAt
    }

    private data class Kv(val indent: String, val key: String, val value: String, val comment: String)

    private fun parseKv(line: String): Kv? {
        val eq = line.indexOf('=')
        if (eq < 0) return null
        val left = line.substring(0, eq)
        if (left.trimStart().startsWith(";") || left.trimStart().startsWith("#")) return null
        val key = left.trim()
        if (key.isEmpty()) return null
        val rest = line.substring(eq + 1)
        val commentAt = rest.indexOfFirst { it == ';' || it == '#' }
        val value = (if (commentAt >= 0) rest.substring(0, commentAt) else rest).trim()
        val comment = if (commentAt >= 0) rest.substring(commentAt).trim() else ""
        return Kv("", key, value, comment)
    }

    private fun rebuild(kv: Kv, newValue: String): String {
        val suffix = if (kv.comment.isNotEmpty()) "  ${kv.comment}" else ""
        return "${kv.key} = $newValue$suffix"
    }

    private val SECTION_RE = Regex("""^\s*\[([^\]]+)]\s*$""")
    private const val SECTION_SYSTEM = "System"
    private const val SECTION_DATABASE = "Database"
    /** Cloudreve 自己写出来的相对 DBFile；等于它时说明"从没被我们接管过" */
    private const val LEGACY_RELATIVE = "data/cloudreve.db"
}
