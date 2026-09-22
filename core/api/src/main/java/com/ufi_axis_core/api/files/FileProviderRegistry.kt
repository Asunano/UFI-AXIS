package com.ufi_axis_core.api.files

import java.util.concurrent.ConcurrentHashMap

/**
 * FileProvider 注册表 — 管理所有已注册的存储提供者。
 *
 * ## 路径解析规则
 * - `remote:<sourceId>/<relativePath>` → 从 `<sourceId>` 对应的远程提供者中查找
 * - 其它所有路径 → 路由到 id="local" 的本地提供者
 *
 * ## 线程安全
 * 基于 [ConcurrentHashMap]，注册/注销/查找均可并发执行。
 */
class FileProviderRegistry {

    private val providers = ConcurrentHashMap<String, FileProvider>()

    /**
     * 注册一个提供者。
     *
     * @param key 注册键，**必须与 `remote:<key>/…` 路径里的那一段一致**。
     *   远程源传配置 id（`2bc39d4aa08f`），不要传 [FileProvider.id]——后者带协议前缀
     *   （`webdav:2bc39d4aa08f`），而路径里只有裸配置 id。
     *
     *   2026-09-21 修：此前签名是 `register(provider)` 并拿 `provider.id` 当键，于是
     *   注册进去的是 `webdav:xxx`、[resolve] 查的是 `xxx`，远端源一进就报
     *   「存储源不存在」——而 `/files/status` 同时又列得出它，现象非常误导。
     *   把键显式化，避免再出现两边各按自己的理解拼 id。
     */
    fun register(provider: FileProvider, key: String = provider.id) {
        providers[key] = provider
    }


    /**
     * 注销指定 id 的提供者。
     *
     * **不允许注销 "local"**：本地提供者是整个文件系统的基座，移除后所有不带前缀的路径
     * 都会解析失败，表现为"打开文件管理器就白屏"这类难以定位的问题。
     */
    fun unregister(id: String) {
        if (id == LOCAL_ID) return
        providers.remove(id)
    }

    /**
     * 解析路径到 (provider, 去掉前缀的实际路径)。
     *
     * @throws FileProvider.ProviderException 路径格式非法或对应提供者不存在时抛出。
     */
    fun resolve(path: String): Pair<FileProvider, String> {
        if (path.startsWith(REMOTE_PREFIX)) {
            val rest = path.removePrefix(REMOTE_PREFIX)
            val slash = rest.indexOf('/')
            val sourceId: String
            val relativePath: String
            if (slash < 0) {
                sourceId = rest
                relativePath = "/"
            } else {
                sourceId = rest.substring(0, slash)
                relativePath = rest.substring(slash) // 保留开头的 '/'
            }
            if (sourceId.isBlank()) {
                throw FileProvider.ProviderException("远程路径缺少 sourceId: $path")
            }
            val provider = providers[sourceId]
                ?: throw FileProvider.ProviderException("存储源不存在: $sourceId")
            return provider to relativePath
        }
        // 非 remote: 前缀 → 本地
        val local = providers[LOCAL_ID]
            ?: throw FileProvider.ProviderException("本地存储提供者未注册")
        return local to path
    }

    /**
     * 解析路径，找不到时返回 null 而不是抛异常。
     */
    fun resolveOrNull(path: String): Pair<FileProvider, String>? {
        return runCatching { resolve(path) }.getOrNull()
    }

    /**
     * 列出所有已注册的存储源。
     *
     * `id` 用**注册键**而不是 [FileProvider.id]：调用方（`/files/status`）拿这个 id 是为了
     * 拼 `remote:<id>/` 路径，给带协议前缀的那份只会让客户端拼出解析不了的路径。
     */
    fun listSources(): List<StorageSource> {
        return providers.map { (key, p) ->
            StorageSource(
                id = key,
                label = p.label,
                protocol = p.protocol,
                enabled = true,
                capabilities = p.capabilities
            )
        }
    }


    /**
     * 按 id 获取提供者。
     */
    fun get(id: String): FileProvider? = providers[id]

    /**
     * 从 `remote:<id>/…` 中取出注册键；非远端路径返回 null。
     */
    fun sourceIdOf(path: String): String? {
        if (!path.startsWith(REMOTE_PREFIX)) return null
        val rest = path.removePrefix(REMOTE_PREFIX)
        val slash = rest.indexOf('/')
        val id = if (slash < 0) rest else rest.substring(0, slash)
        return id.takeIf { it.isNotBlank() }
    }

    /**
     * 把 provider 返回的**相对路径**还原成客户端可直接回传的路径。
     *
     * 这一步是必须的：provider 只认自己那套相对路径（`/Server/`），而客户端拿到
     * 什么就会把什么原样喂回 `/files/list`。不加前缀的话 `/Server/` 会被当成本地
     * 绝对路径走到 [LocalFileProvider]，撞上白名单直接 400 —— 而且客户端会因为
     * 路径里没有 `remote:` 而以为自己在本地，连"返回上一级"都会走错分支。
     *
     * 目录一律去掉尾部 `/`，与本地路径的形态对齐（本地目录不带尾斜杠）；
     * 否则面包屑按 `/` 切会多出一个空段。
     */
    fun toClientPath(sourceId: String, relativePath: String): String {
        val rel = relativePath.trimEnd('/').ifBlank { "" }
        return "$REMOTE_PREFIX$sourceId${if (rel.startsWith("/")) rel else "/$rel"}"
    }

    /**
     * 远端路径的上一级，供响应信封的 `parent` 用。
     *
     * 已经在源根时返回 null —— 客户端据此回到"虚拟根"（卷 + 外部源列表），
     * 而不是继续往上切出一个不存在的路径。
     */
    fun parentClientPath(sourceId: String, relativePath: String): String? {
        val rel = relativePath.trimEnd('/')
        if (rel.isBlank() || rel == "/") return null
        val parent = rel.substringBeforeLast('/', "")
        return toClientPath(sourceId, parent.ifBlank { "/" })
    }


    companion object {
        const val LOCAL_ID = "local"
        const val REMOTE_PREFIX = "remote:"
    }
}
