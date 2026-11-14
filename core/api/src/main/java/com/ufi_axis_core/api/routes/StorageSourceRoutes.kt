package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.files.StorageSourceConfig
import com.ufi_axis_core.api.files.StorageSourceManager
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 远程存储源管理 API 路由。
 *
 * 端点（均在 `/api/storage` 下）：
 * - GET    /sources             — 列出所有源（密码脱敏）
 * - GET    /sources/{id}        — 单个源（密码脱敏）
 * - POST   /sources             — 新增
 * - PUT    /sources/{id}        — 更新（密码为 "********" 时保留旧值）
 * - DELETE /sources/{id}        — 删除
 * - POST   /sources/{id}/test   — 测试已保存的源
 * - POST   /sources/test        — 测试内联配置（未保存）
 */
class StorageSourceRoutes(private val manager: StorageSourceManager) {

    fun register(route: Route) {
        route.route("/storage") {

            // ── GET /sources — 列出所有 ──
            get("/sources") {
                val configs = withContext(Dispatchers.IO) { manager.loadAll() }
                val sources = configs.map { configToResponse(it) }
                call.respond(toJsonElement(mapOf("sources" to sources)))
            }

            // ── GET /sources/{id} — 单个源 ──
            get("/sources/{id}") {
                val id = call.parameters["id"] ?: ""
                val config = withContext(Dispatchers.IO) { manager.get(id) }
                if (config == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Storage source not found: $id")
                    return@get
                }
                call.respond(toJsonElement(mapOf("source" to configToResponse(config))))
            }

            // ── POST /sources — 新增 ──
            post("/sources") {
                val body = call.receiveJsonObject()
                val config = try {
                    bodyToConfig(body)
                } catch (e: Exception) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid config: ${e.message}")
                    return@post
                }
                val protocol = config.protocol
                if (protocol != "ftp" && protocol != "webdav" && protocol != "smb" && protocol != "s3") {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Unsupported protocol: $protocol")
                    return@post
                }
                validationError(config)?.let { (code, msg) ->
                    call.respondFail(HttpStatusCode.BadRequest, code, msg)
                    return@post
                }
                val saved = withContext(Dispatchers.IO) { manager.add(config) }
                call.respond(toJsonElement(mapOf("success" to true, "source" to configToResponse(saved))))
            }

            // ── PUT /sources/{id} — 更新 ──
            put("/sources/{id}") {
                val id = call.parameters["id"] ?: ""
                val existing = withContext(Dispatchers.IO) { manager.get(id) }
                if (existing == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Storage source not found: $id")
                    return@put
                }
                val body = call.receiveJsonObject()
                var config = try {
                    bodyToConfig(body)
                } catch (e: Exception) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid config: ${e.message}")
                    return@put
                }
                // 密码为掩码时保留旧密码
                if (manager.isPasswordMasked(config.password)) {
                    config = config.copy(password = existing.password)
                }
                // 与 POST 同一份校验：少了它，PUT 能把非法协议/缺必填的配置写进存档
                val protocol = config.protocol
                if (protocol != "ftp" && protocol != "webdav" && protocol != "smb" && protocol != "s3") {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Unsupported protocol: $protocol")
                    return@put
                }
                validationError(config)?.let { (code, msg) ->
                    call.respondFail(HttpStatusCode.BadRequest, code, msg)
                    return@put
                }
                try {
                    withContext(Dispatchers.IO) { manager.update(id, config) }
                } catch (e: IllegalArgumentException) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, e.message ?: "Source not found")
                    return@put
                }
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            // ── DELETE /sources/{id} — 删除 ──
            delete("/sources/{id}") {
                val id = call.parameters["id"] ?: ""
                val existing = withContext(Dispatchers.IO) { manager.get(id) }
                if (existing == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Storage source not found: $id")
                    return@delete
                }
                withContext(Dispatchers.IO) { manager.remove(id) }
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            // ── POST /sources/{id}/test — 测试已保存的源 ──
            post("/sources/{id}/test") {
                val id = call.parameters["id"] ?: ""
                val config = withContext(Dispatchers.IO) { manager.get(id) }
                if (config == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Storage source not found: $id")
                    return@post
                }
                val result = withContext(Dispatchers.IO) { manager.testConnection(config) }
                call.respond(toJsonElement(mapOf(
                    "success" to result.success,
                    "message" to result.message,
                    "latency_ms" to result.latencyMs
                )))
            }

            // ── POST /sources/test — 测试内联配置 ──
            post("/sources/test") {
                val body = call.receiveJsonObject()
                val config = try {
                    bodyToConfig(body)
                } catch (e: Exception) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid config: ${e.message}")
                    return@post
                }
                validationError(config)?.let { (code, msg) ->
                    call.respondFail(HttpStatusCode.BadRequest, code, msg)
                    return@post
                }
                val result = withContext(Dispatchers.IO) { manager.testConnection(config) }
                call.respond(toJsonElement(mapOf(
                    "success" to result.success,
                    "message" to result.message,
                    "latency_ms" to result.latencyMs
                )))
            }
        }
    }

    // ───────── 校验 ─────────

    /**
     * 必填项校验。返回 null 表示通过，否则是 (错误码, 文案)。
     *
     * 新增与试连两处共用同一份，避免"保存时报错、试连时不报"这种前后不一致。
     */
    private fun validationError(config: StorageSourceConfig): Pair<String, String>? {
        // S3 的服务地址在 endpoint / region 里，host 字段对它没有意义，所以不能一刀切要求 host。
        if (config.protocol != "s3" && config.host.isBlank()) {
            return ErrorCode.BLANK_VALUE to "host is required"
        }
        // SMB 没有共享名连不到任何东西，和 host 一样属于必填。
        if (config.protocol == "smb" && config.share.isBlank()) {
            return ErrorCode.BLANK_VALUE to "share is required for smb"
        }
        if (config.protocol == "s3") {
            if (config.bucket.isBlank()) {
                return ErrorCode.BLANK_VALUE to "bucket is required for s3"
            }
            // endpoint 留空时地址由 region 推导（s3.{region}.amazonaws.com），所以两者不能同时为空。
            if (config.endpoint.isBlank() && config.region.isBlank()) {
                return ErrorCode.BLANK_VALUE to "endpoint or region is required for s3"
            }
        }
        return null
    }

    // ───────── 内部转换 ─────────

    /**
     * 将配置转为 API 响应 map（密码脱敏，附带 capabilities）。
     */
    private fun configToResponse(config: StorageSourceConfig): Map<String, Any?> {
        // 获取 capabilities：尝试从 registry 拿，拿不到就根据协议推断
        val capabilities = capabilitiesFor(config.protocol)
        return mapOf(
            "id" to config.id,
            "label" to config.label,
            "protocol" to config.protocol,
            "host" to config.host,
            "port" to config.port,
            // 2026-09-21 修：此前漏了 username。响应里没有它，app 的编辑弹窗回填时
            // 用户名框就是空的，一保存就把用户名清成空串（密码有 "********" 占位兜着，
            // 用户名没有任何保护）。四个协议全中招。
            "username" to config.username,

            "basePath" to config.basePath,
            "useTls" to config.useTls,
            "passive" to config.passive,
            "encoding" to config.encoding,
            "trustAllCerts" to config.trustAllCerts,
            "domain" to config.domain,
            "share" to config.share,
            "bucket" to config.bucket,
            "region" to config.region,
            "endpoint" to config.endpoint,
            "pathStyle" to config.pathStyle,
            "timeoutSec" to config.timeoutSec,
            "enabled" to config.enabled,
            "capabilities" to capabilities
        )
    }

    /**
     * 根据协议推断 capabilities 列表。
     */
    private fun capabilitiesFor(protocol: String): List<String> = when (protocol) {
        "webdav" -> listOf("LIST", "READ", "WRITE", "DELETE", "RENAME", "MOVE", "COPY", "MKDIR", "UPLOAD", "DOWNLOAD")
        "ftp" -> listOf("LIST", "READ", "WRITE", "DELETE", "RENAME", "MKDIR", "UPLOAD", "DOWNLOAD")
        // SMB 与 SmbFileProvider.capabilities 保持一致：多出 DISK_USAGE（share information 能报容量）。
        "smb" -> listOf(
            "LIST", "READ", "WRITE", "DELETE", "RENAME", "MOVE", "COPY", "MKDIR",
            "UPLOAD", "DOWNLOAD", "DISK_USAGE"
        )
        // S3 与 S3FileProvider.capabilities 保持一致：没有 MOVE（目录移动非原子）、
        // 没有 SEARCH（只能按前缀过滤）、没有 DISK_USAGE（对象存储不报容量）。
        "s3" -> listOf(
            "LIST", "READ", "WRITE", "DELETE", "RENAME", "COPY", "MKDIR",
            "UPLOAD", "DOWNLOAD"
        )
        else -> emptyList()
    }

    /**
     * 从 JsonObject 手动提取字段构建 StorageSourceConfig。
     */
    private fun bodyToConfig(body: JsonObject): StorageSourceConfig {
        return StorageSourceConfig(
            id = (body["id"] as? JsonPrimitive)?.contentOrNull ?: "",
            label = (body["label"] as? JsonPrimitive)?.contentOrNull ?: "",
            protocol = (body["protocol"] as? JsonPrimitive)?.contentOrNull ?: "",
            host = (body["host"] as? JsonPrimitive)?.contentOrNull ?: "",
            port = (body["port"] as? JsonPrimitive)?.intOrNull ?: 0,
            username = (body["username"] as? JsonPrimitive)?.contentOrNull ?: "",
            password = (body["password"] as? JsonPrimitive)?.contentOrNull ?: "",
            basePath = (body["basePath"] as? JsonPrimitive)?.contentOrNull ?: "/",
            useTls = (body["useTls"] as? JsonPrimitive)?.booleanOrNull ?: false,
            passive = (body["passive"] as? JsonPrimitive)?.booleanOrNull ?: true,
            encoding = (body["encoding"] as? JsonPrimitive)?.contentOrNull ?: "UTF-8",
            trustAllCerts = (body["trustAllCerts"] as? JsonPrimitive)?.booleanOrNull ?: false,
            domain = (body["domain"] as? JsonPrimitive)?.contentOrNull ?: "",
            share = (body["share"] as? JsonPrimitive)?.contentOrNull ?: "",
            bucket = (body["bucket"] as? JsonPrimitive)?.contentOrNull ?: "",
            region = (body["region"] as? JsonPrimitive)?.contentOrNull ?: "us-east-1",
            endpoint = (body["endpoint"] as? JsonPrimitive)?.contentOrNull ?: "",
            pathStyle = (body["pathStyle"] as? JsonPrimitive)?.booleanOrNull ?: true,
            // 夹到有限区间：OkHttp / commons-net / smbj 都把 0 当"永不超时"，
            // 显式传 0 会让不可达的源把 IO 线程永久挂住。
            timeoutSec = (body["timeoutSec"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { it > 0 }?.coerceIn(3, 120) ?: 15,
            enabled = (body["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
        )
    }
}
