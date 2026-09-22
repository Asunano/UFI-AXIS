package com.ufi_axis_core.api.files

import kotlinx.serialization.Serializable

/**
 * 远程存储源的持久化配置。
 *
 * 每条记录描述一个 FTP / WebDAV / SMB / S3 服务器的连接参数，由 [StorageSourceManager] 序列化为
 * JSON 数组存入 SharedPreferences（单键 `storage_sources`）。
 *
 * 密码明文存储，与现有 goform_password / tunnel_cf_token 等敏感字段保持一致。
 * API 响应中密码字段一律替换为 `"********"`。
 *
 * ## S3 的字段复用口径
 * S3 没有「用户名 / 密码」这回事，但凭据的语义位置完全对得上，所以不新增字段而是复用：
 * - [username] = Access Key ID
 * - [password] = Secret Access Key
 * - [useTls]   = 是否用 https（自建 MinIO 常跑纯 http，所以留成开关而不是强制 https）
 * - [basePath] = key 前缀（S3 没有目录，"根路径"就是一段 key 前缀）
 * - [host] / [port] 对 S3 无意义（endpoint 单独一个字段），只有 MinIO 这类自定义端口才用到 [port]
 */
@Serializable
data class StorageSourceConfig(
    val id: String = "",
    val label: String = "",
    /** 协议标识：`"ftp"` / `"webdav"` / `"smb"` / `"s3"`。 */
    val protocol: String = "",
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /** 远端根路径，默认 `"/"`。 */
    val basePath: String = "/",
    val useTls: Boolean = false,
    /** FTP 专用：被动模式，默认 true。 */
    val passive: Boolean = true,
    /** FTP 专用：控制连接编码，默认 `"UTF-8"`。 */
    val encoding: String = "UTF-8",
    /** WebDAV 专用：信任所有证书，默认 false。 */
    val trustAllCerts: Boolean = false,
    /** SMB 专用：Windows 域 / 工作组，留空表示无域。 */
    val domain: String = "",
    /** SMB 专用：共享名（NAS 上的共享文件夹名，如 `"video"`）。 */
    val share: String = "",
    /** S3 专用：Bucket 名。 */
    val bucket: String = "",
    /** S3 专用：Region。MinIO 等自建服务填 `"us-east-1"` 即可（签名要用，服务端通常不校验）。 */
    val region: String = "us-east-1",
    /**
     * S3 专用：自定义 endpoint 主机名（不含协议前缀）。留空表示走 AWS 官方地址
     * （`s3.{region}.amazonaws.com`）。MinIO / OSS / COS / R2 必填。
     */
    val endpoint: String = "",
    /**
     * S3 专用：路径式寻址（`host/bucket/key`）而非虚拟主机式（`bucket.host/key`）。
     * MinIO 默认只认路径式，所以默认 true。
     */
    val pathStyle: Boolean = true,
    /** 连接 / 读取超时（秒），默认 15。 */
    val timeoutSec: Int = 15,
    /** 是否启用（启用后才注册到 FileProviderRegistry）。 */
    val enabled: Boolean = true
)
