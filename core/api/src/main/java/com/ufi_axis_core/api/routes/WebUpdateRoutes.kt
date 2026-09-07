package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.update.WebUpdateManager
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.WebResourceManager
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.ByteArrayOutputStream

/**
 * Web 前端独立更新路由
 *
 * 自动通道（2026-08-18，从 root version.json 的 `web` 对象自拉取）：
 * - POST /api/web/check   触发 Web 自动更新（Core 拉 web.apkUrl → SHA256 → installFromZip）
 * - GET  /api/web/status  查询 Web 自动更新状态
 *
 * 手动通道（保留，管理员上传 ZIP 覆盖 assets/web）：
 * - GET  /api/web/version  查询当前 web 版本信息
 * - POST /api/web/update   上传 ZIP 安装新 web 前端
 * - POST /api/web/rollback 回滚到上一版本
 * - POST /api/web/clear    清除 override，恢复内置版本
 */
class WebUpdateRoutes(
    private val webResourceManager: WebResourceManager,
    private val webUpdateManager: WebUpdateManager
) {

    companion object {
        private const val TAG = "WebUpdateRoutes"
        private const val MAX_ZIP_UPLOAD = 50L * 1024 * 1024  // 50MB
    }

    fun register(route: Route) {
        route.route("/web") {
            // 自动通道：从 root version.json 的 web 对象自拉取 Web ZIP 并覆盖安装
            post("/check") {
                webUpdateManager.checkAndUpdate()
                call.respond(toJsonElement(webUpdateManager.statusToMap()))
            }

            get("/status") {
                call.respond(toJsonElement(webUpdateManager.statusToMap()))
            }

            get("/version") {
                call.respond(toJsonElement(webResourceManager.getVersionInfo()))
            }

            post("/update") {
                try {
                    val multipart = call.receiveMultipart()
                    var zipBytes: ByteArray? = null

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                if (part.name == "file") {
                                    val baos = ByteArrayOutputStream()
                                    part.streamProvider().use { input ->
                                        val buf = ByteArray(64 * 1024)
                                        var total = 0L
                                        while (true) {
                                            val n = input.read(buf)
                                            if (n < 0) break
                                            total += n
                                            if (total > MAX_ZIP_UPLOAD) {
                                                throw IllegalArgumentException("ZIP 超过 ${MAX_ZIP_UPLOAD / 1024 / 1024}MB 上传限制")
                                            }
                                            baos.write(buf, 0, n)
                                        }
                                    }
                                    zipBytes = baos.toByteArray()
                                }
                            }
                            else -> {}
                        }
                        part.dispose()
                    }

                    if (zipBytes == null || zipBytes!!.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf(
                            "error" to "未收到文件数据，请上传 .zip 文件"
                        )))
                        return@post
                    }

                    val result = webResourceManager.installFromZip(zipBytes!!.inputStream())
                    result.fold(
                        onSuccess = { info ->
                            AppLogger.i(TAG, "Web 前端更新成功")
                            call.respond(toJsonElement(info + ("success" to true)))
                        },
                        onFailure = { e ->
                            AppLogger.e(TAG, "Web 前端更新失败: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf(
                                "error" to (e.message ?: "安装失败")
                            )))
                        }
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf(
                        "error" to (e.message ?: "参数错误")
                    )))
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Web 前端更新异常: ${e.message}")
                    call.respond(HttpStatusCode.InternalServerError, toJsonElement(mapOf(
                        "error" to "更新失败: ${e.message}"
                    )))
                }
            }

            post("/rollback") {
                val result = webResourceManager.rollback()
                result.fold(
                    onSuccess = { info ->
                        AppLogger.i(TAG, "Web 前端已回滚")
                        call.respond(toJsonElement(info + ("success" to true)))
                    },
                    onFailure = { e ->
                        call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf(
                            "error" to (e.message ?: "回滚失败")
                        )))
                    }
                )
            }

            post("/clear") {
                // clearOverride 现在会复核 hasOverride()：删不干净就不能对客户端谎报成功，
                // 否则用户以为已恢复内置版本、实际还在用坏掉的 override（白屏依旧）。
                val cleared = webResourceManager.clearOverride()
                if (cleared) {
                    AppLogger.i(TAG, "Web override 已清除，恢复内置版本")
                    call.respond(toJsonElement(webResourceManager.getVersionInfo() + mapOf(
                        "success" to true,
                        "message" to "已恢复内置版本"
                    )))
                } else {
                    AppLogger.e(TAG, "Web override 清除失败，override 仍然有效")
                    call.respond(HttpStatusCode.InternalServerError, toJsonElement(mapOf(
                        "success" to false,
                        "error" to "清除未完成：override 目录仍存在，请重启服务后重试",
                        "message" to "清除未完成：override 目录仍存在，请重启服务后重试"
                    )))
                }
            }
        }
    }
}
