package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.AppLogger
import java.io.File
import java.security.SecureRandom

/**
 * dufs 的适配器：生成一份带**随机口令**的配置，把服务目录钉在 `{CONTENT}`。
 *
 * ## 为什么必须适配器（而不是纯清单）
 *
 * dufs 的口令只能通过 `--auth user:pass@/...` 或配置文件给，**没有"首次启动自动生成"这回事**。
 * 而清单里的 argv 是公开的（远端 `version.json` 谁都能看），把口令写进去等于没有口令。
 * 所以由 core 在启动前生成一份 `{CONF}/dufs.yaml`，口令随机、只在本机。
 *
 * 这跟 OpenList 是同一个范式：**清单描述"是什么"，适配器负责"首次怎么初始化"**。
 *
 * ## 口令存哪、怎么取
 *
 * 直接存在 YAML 的 `auth:` 行里（**唯一真源**，不另存第二份）：
 * - 文件在 `{CONF}`，与应用私有目录同级、`chmod 600`，同 uid 之外读不到；
 * - `bootstrapSecret()` 从 YAML 里把 `user:pass` 抠出来给 UI 显示（列表只给 `has_secret`）；
 * - 忘了口令走 `resetBootstrapSecret()` 重新生成 —— 不重装、不动数据。
 *
 * 为什么不"只在内存里留一次"（OpenList 的做法）：dufs 没有用户库，口令丢了就真的进不去，
 * 每次 core 重启都换一次口令会让用户永远登录不上。**持久化 + 可重置**才是这里的正确语义。
 *
 * ## 权限口径
 *
 * `allow-upload: true` 但 `allow-delete: false`：手机/电脑能往设备里丢文件，但不能删。
 * 删除交给 core 的文件管理器（那边有更明确的确认流程）。
 * dufs 的全局开关会压制账号权限（见上游 README），所以 `:rw` 账号也只能传不能删。
 */
class DufsAdapter : ProgramAdapter {

    override val id = "dufs"

    override fun preflight(ctx: ProgramContext): Result<Unit> = runCatching {
        ctx.confDir.mkdirs()
        ctx.contentDir.mkdirs()

        val conf = File(ctx.confDir, CONF_NAME)
        confFiles[ctx.id] = conf
        val existing = if (conf.exists()) conf.readText() else ""
        val credential = credentialOf(existing) ?: randomCredential()
        val text = render(ctx, credential)

        if (text != existing) {
            writeAtomically(conf, text)
            AppLogger.i(TAG, "dufs.yaml 已写入（serve-path=${ctx.contentDir.absolutePath}，port=${ctx.internalPort}）")
        }
    }

    /** 口令就是 YAML 里那条 auth 规则本身，不再另存副本。 */
    override fun bootstrapSecret(id: String): String? = currentCredential(id)

    override fun resetBootstrapSecret(ctx: ProgramContext, password: String?): Result<String> = runCatching {
        val conf = File(ctx.confDir, CONF_NAME)
        confFiles[ctx.id] = conf
        // 路由层已校验字符集；这里仍兜底拒绝空串，避免生成 "ufi:" 这种无效凭据。
        val credential = if (password.isNullOrBlank()) randomCredential() else "ufi:$password"
        writeAtomically(conf, render(ctx, credential))
        AppLogger.i(TAG, "dufs 口令已重置")
        credential
    }

    private fun currentCredential(id: String): String? {
        val conf = confFiles[id] ?: return null
        return runCatching { credentialOf(conf.readText()) }.getOrNull()
    }

    /**
     * 渲染配置。**每次启动都重写**（幂等）：`serve-path` / `port` 必须跟清单一致，
     * 用户若手改过 YAML，这里会把它拉回来 —— 与 OpenList 的"每次校正监听地址"同一个理由。
     */
    private fun render(ctx: ProgramContext, credential: String): String = buildString {
        appendLine("# 由 UFI-AXIS 生成：口令改动请用「重置口令」，不要手改（下次启动会被覆盖）")
        appendLine("serve-path: '${ctx.contentDir.absolutePath.replace("'", "''")}'")
        // 与 Cloudreve 同一条规则：gateway 模式只绑回环，direct 才暴露到局域网。
        appendLine("bind: ${if (ctx.accessMode == "gateway") "127.0.0.1" else "0.0.0.0"}")
        appendLine("port: ${ctx.internalPort}")
        appendLine("auth:")
        appendLine("  - '$credential@/:rw'")
        appendLine("allow-upload: true")
        appendLine("allow-delete: false")
        appendLine("allow-search: true")
        appendLine("allow-archive: true")
    }

    /** 从 YAML 里抠出 `user:pass`；没有则返回 null。 */
    private fun credentialOf(text: String): String? =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("- ") && it.contains('@') }
            ?.removePrefix("- ")
            ?.trim('\'', '"')
            ?.substringBefore('@')
            ?.takeIf { it.contains(':') }

    private fun randomCredential(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rnd = SecureRandom()
        fun pick(n: Int) = buildString(n) { repeat(n) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
        return "ufi:" + pick(16)
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        runCatching { tmp.setReadable(false, false); tmp.setReadable(true, true) }
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "DufsAdapter"
        const val CONF_NAME = "dufs.yaml"

        /**
         * 实例 id → 配置文件路径。
         *
         * 适配器在注册表里是**共享单例**（`ProgramAdapterRegistry` 只存一个实例），
         * 而口令存在各自程序的 YAML 里 —— 所以按 id 记，取口令时再读。
         * 用 map 而不是一个 volatile 引用：同一种程序将来允许多实例时不会串号。
         */
        val confFiles = java.util.concurrent.ConcurrentHashMap<String, File>()
    }
}
