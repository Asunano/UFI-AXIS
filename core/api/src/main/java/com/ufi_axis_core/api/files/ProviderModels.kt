package com.ufi_axis_core.api.files

/**
 * Provider 层的内部数据模型。
 *
 * 与 app 侧的 `FileItem` / `FileListResponse` / `FileReadResponse` 字段对齐，
 * 但**不加** `@Serializable` —— 这些是 core 内部模型，不直接序列化到 HTTP 响应。
 * 路由层负责将它们转成 `toJsonElement(mapOf(...))` 保持现有响应格式不变。
 */

/**
 * 单个文件/目录的元信息。
 *
 * @param source 来源标识（`"local"` / provider id），路由层据此判断是否需要远程操作。
 */
data class ProviderFileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val permissions: String,
    val isSymlink: Boolean,
    val source: String
)

/**
 * 目录列表结果。
 *
 * @param truncated 结果是否被截断（远程提供者可能有分页限制）。
 */
data class ProviderListResult(
    val files: List<ProviderFileInfo>,
    val path: String,
    val parent: String?,
    val truncated: Boolean = false
)

/**
 * 文本文件读取结果。
 *
 * @param reason 内容不可用的原因（`too_large` / `binary` / `not_file`），null 表示正常。
 * @param encodingSuspect UTF-8 解码时出现替换字符，内容可能是其它编码。
 */
data class ProviderReadResult(
    val content: String,
    val encoding: String,
    val size: Long,
    val truncated: Boolean,
    val reason: String? = null,
    val encodingSuspect: Boolean = false
)

/**
 * 存储卷/分区信息。
 */
data class ProviderVolumeInfo(
    val label: String,
    val mountPath: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val availBytes: Long
)

/**
 * 注册表对外暴露的"存储源"摘要。
 *
 * @param capabilities 该源支持的能力集，UI 层据此决定哪些按钮可用。
 */
data class StorageSource(
    val id: String,
    val label: String,
    val protocol: String,
    val enabled: Boolean,
    val capabilities: Set<FileProvider.Capability>
)
