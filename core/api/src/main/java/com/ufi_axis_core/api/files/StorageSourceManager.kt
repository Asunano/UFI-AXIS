package com.ufi_axis_core.api.files

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 远程存储源的生命周期管理器。
 *
 * 职责：
 * 1. CRUD 存储源配置（持久化到 [AppSettings] 的 SharedPreferences）
 * 2. 根据配置创建 [FileProvider] 实例并注册到 [FileProviderRegistry]
 * 3. 提供连接测试能力
 *
 * 配置以 JSON 数组的形式存在单个 SharedPreferences 键 [KEY_STORAGE_SOURCES] 中。
 */
class StorageSourceManager(
    private val settings: AppSettings,
    private val registry: FileProviderRegistry
) {

    companion object {
        private const val TAG = "StorageSourceManager"
        private const val KEY_STORAGE_SOURCES = "storage_sources"
        private const val PASSWORD_MASK = "********"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    data class TestResult(
        val success: Boolean,
        val message: String,
        val latencyMs: Long
    )

    // ───────── 配置持久化 ─────────

    /**
     * 从 SharedPreferences 加载全部存储源配置。
     */
    fun loadAll(): List<StorageSourceConfig> {
        val raw = settings.getRawString(KEY_STORAGE_SOURCES)
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<StorageSourceConfig>>(raw)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse storage_sources: ${e.message}")
            emptyList()
        }
    }

    /**
     * 将配置列表序列化并写入 SharedPreferences。
     */
    fun save(configs: List<StorageSourceConfig>) {
        settings.setRawString(KEY_STORAGE_SOURCES, json.encodeToString(configs))
    }

    /**
     * 添加一个新的存储源。自动生成 UUID 作为 id。
     * 如果 enabled，同时创建并注册对应的 FileProvider。
     */
    fun add(config: StorageSourceConfig): StorageSourceConfig {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val newConfig = config.copy(id = id)
        val configs = loadAll().toMutableList()
        configs.add(newConfig)
        save(configs)
        if (newConfig.enabled) {
            try {
                val provider = createProvider(newConfig)
                registry.register(provider, newConfig.id)
                AppLogger.i(TAG, "Registered provider: ${provider.id} (${newConfig.protocol}://${newConfig.host})")

            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to create provider for ${newConfig.id}: ${e.message}")
            }
        }
        return newConfig
    }

    /**
     * 更新已有存储源。先注销旧 provider，保存新配置，再按需注册新 provider。
     */
    fun update(id: String, config: StorageSourceConfig) {
        val configs = loadAll().toMutableList()
        val index = configs.indexOfFirst { it.id == id }
        if (index < 0) throw IllegalArgumentException("Storage source not found: $id")
        // 注销旧 provider
        unregisterProvider(configs[index])
        val updated = config.copy(id = id)
        configs[index] = updated
        save(configs)
        if (updated.enabled) {
            try {
                val provider = createProvider(updated)
                registry.register(provider, updated.id)
                AppLogger.i(TAG, "Re-registered provider: ${provider.id}")

            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to create provider for $id: ${e.message}")
            }
        }
    }

    /**
     * 删除一个存储源。同时注销对应的 provider。
     */
    fun remove(id: String) {
        val configs = loadAll().toMutableList()
        val removed = configs.find { it.id == id }
        configs.removeAll { it.id == id }
        save(configs)
        if (removed != null) {
            unregisterProvider(removed)
            AppLogger.i(TAG, "Removed storage source: $id")
        }
    }

    /**
     * 按 id 获取单个配置。
     */
    fun get(id: String): StorageSourceConfig? = loadAll().find { it.id == id }

    /**
     * 测试存储源连接。创建临时 provider，尝试 list("/")，测量延迟。
     */
    fun testConnection(config: StorageSourceConfig): TestResult {
        val start = System.currentTimeMillis()
        return try {
            val provider = createProvider(config)
            try {
                // 使用 runBlocking 因为这个方法本身不是 suspend
                // 调用方（路由层）已在 withContext(Dispatchers.IO) 中
                kotlinx.coroutines.runBlocking {
                    provider.list("/")
                }
                val latency = System.currentTimeMillis() - start
                TestResult(success = true, message = "Connection successful", latencyMs = latency)
            } finally {
                closeProvider(provider)
            }
        } catch (e: FileProvider.ProviderAuthException) {
            val latency = System.currentTimeMillis() - start
            TestResult(success = false, message = "Authentication failed: ${e.message}", latencyMs = latency)
        } catch (e: FileProvider.ProviderTimeoutException) {
            val latency = System.currentTimeMillis() - start
            TestResult(success = false, message = "Connection timed out: ${e.message}", latencyMs = latency)
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            TestResult(success = false, message = "Connection failed: ${e.message}", latencyMs = latency)
        }
    }

    /**
     * 启动时调用：加载所有已启用的配置，创建并注册 provider。
     */
    fun initializeProviders() {
        val configs = loadAll()
        var count = 0
        for (cfg in configs) {
            if (!cfg.enabled) continue
            try {
                val provider = createProvider(cfg)
                registry.register(provider, cfg.id)
                count++

            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to initialize provider ${cfg.id} (${cfg.protocol}://${cfg.host}): ${e.message}")
            }
        }
        AppLogger.i(TAG, "Initialized $count/${configs.size} storage source providers")
    }

    /**
     * 工厂方法：根据协议创建对应的 FileProvider 实例。
     */
    fun createProvider(config: StorageSourceConfig): FileProvider {
        return when (config.protocol) {
            "webdav" -> WebDavFileProvider(config)
            "ftp" -> FtpFileProvider(config)
            "smb" -> SmbFileProvider(config)
            "s3" -> S3FileProvider(config)
            else -> throw IllegalArgumentException("Unsupported protocol: ${config.protocol}")
        }
    }

    // ───────── 内部工具 ─────────

    /**
     * 注销某个配置对应的 provider。
     *
     * 注册键就是配置 id（与 `remote:<id>/…` 路径里那一段同一个值），
     * 不是带协议前缀的 [FileProvider.id]——2026-09-21 之前这里用前缀版，
     * 与 [FileProviderRegistry.resolve] 的查找键对不上。
     */
    private fun unregisterProvider(config: StorageSourceConfig) {
        val existing = registry.get(config.id)
        if (existing != null) {
            registry.unregister(config.id)

            closeProvider(existing)
        }
    }

    private fun closeProvider(provider: FileProvider) {
        try {
            when (provider) {
                is WebDavFileProvider -> provider.close()
                is FtpFileProvider -> provider.close()
                is SmbFileProvider -> provider.close()
                is S3FileProvider -> provider.close()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error closing provider ${provider.id}: ${e.message}")
        }
    }

    // ───────── 密码脱敏 ─────────

    /**
     * 将配置中的密码替换为掩码，用于 API 响应。
     */
    fun maskPassword(config: StorageSourceConfig): StorageSourceConfig =
        config.copy(password = PASSWORD_MASK)

    /**
     * 判断密码是否为掩码值（用于 PUT 时保留旧密码）。
     */
    fun isPasswordMasked(password: String): Boolean = password == PASSWORD_MASK
}
