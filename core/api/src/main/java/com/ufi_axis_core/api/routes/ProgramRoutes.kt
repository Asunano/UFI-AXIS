package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.program.ProgramInstaller
import com.ufi_axis_core.controller.program.ProgramAdapterRegistry
import com.ufi_axis_core.controller.program.ProgramCatalog
import com.ufi_axis_core.controller.program.ProgramEnterTickets
import com.ufi_axis_core.controller.program.ProgramContext
import com.ufi_axis_core.controller.program.ProgramGateway
import com.ufi_axis_core.controller.program.ProgramGatewaySecret
import com.ufi_axis_core.controller.program.ProgramManifest
import com.ufi_axis_core.controller.program.ProgramRuntime
import com.ufi_axis_core.controller.program.ProgramSettings
import com.ufi_axis_core.controller.program.ProgramStore
import com.ufi_axis_core.controller.program.ProgramSupervisor
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.program.ProgramPaths
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 第三方程序商店路由（`/api/programs`）。
 *
 * ## 与 `/api/apps` 的区分
 *
 * `/api/apps` 已由 APK 管理占用（`AppRoutes` → `AppManager`）。两者语义不同：
 * APK 由系统托管，第三方 Linux 程序由 core 拉起。路径重名会导致实现混淆。
 *
 * ## 鉴权
 *
 * 全部端点走标准 `/api` 鉴权（Bearer + 设备签名）。
 * 本功能可下载并执行任意二进制，权限影响面大于安装 APK，调用方必须明确这一点。
 */
class ProgramRoutes(
    private val catalog: ProgramCatalog,
    private val paths: ProgramPaths,
    private val store: ProgramStore,
    private val runtime: ProgramRuntime,
    private val supervisor: ProgramSupervisor,
    private val installer: ProgramInstaller,
    /** `native_exec` 自检结果：enforcing 机型上整条功能不可用，需在列表响应中直接告知前端 */
    private val nativeExecOk: () -> Boolean = { true },
    /** Gateway 的 cookie 密钥；空 = 不启用访问入口 */
    private val gatewaySecret: ByteArray = ByteArray(0),
    /**
     * 可选的「内容目录」落点（内部存储 / 公共存储 / SD 卡）。
     * 由装配层注入：`:core:api` 不能反向依赖 `:core`，而枚举卷需要 Android Context。
     */
    private val volumes: () -> List<VolumeOption> = {
        // 装配层未注入时的默认值：至少保留「内部默认」，
        // 否则前端会收到空列表，且无法得知为何只能安装到内部存储。
        listOf(VolumeOption("internal", "内部存储（应用私有）", null, removable = false))
    }
) {

    /**
     * 校验 `content_root`：只接受卷列表中出现过的路径。
     *
     * 缺少该白名单校验将导致任意目录写入与任意目录删除：
     * 内容目录会被创建，卸载时 `deleteAll` 还会递归删除 `<root>/<id>`。
     * 校验必须在服务端完成，不能只依赖前端下拉框。
     *
     * @return 不合法时返回原因文本（中文）；`null`/空串表示使用默认位置，合法。
     */
    private fun contentRootIssue(requested: String?): String? =
        com.ufi_axis_core.controller.program.ProgramContentRoots.issue(
            requested,
            volumes().mapNotNull { it.path }
        )

    /** 一个可选的内容落点。[path] 为 null 表示"内部默认"（`programs/<id>/content`）。 */
    data class VolumeOption(
        val id: String,
        val label: String,
        /** 内容目录的父路径；null = 内部默认（`programs/<id>/content`） */
        val path: String?,
        val removable: Boolean,
        /**
         * 卷的挂载点（`/storage/emulated/0`、`/storage/1A2B-3C4D`）。
         *
         * 用于判断卷是否仍然存在：首次使用时内容目录可能尚未创建，
         * 因此不得以 `<mount>/UFI-AXIS/content` 是否存在作为挂载判据，
         * 否则会把尚未使用过的新卡误报为「未挂载」。null = 内部默认位置。
         */
        val mountPoint: String? = null,
        val totalBytes: Long = 0,
        val usedBytes: Long = 0
    )

    /** 安装是异步的（48MB 下载），需要独立 scope；不使用 GlobalScope，core 退出时必须可取消 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 服务停机时取消安装协程；下载函数本身是阻塞的，该调用至少保证不再派发新任务。 */
    fun close() {
        scope.cancel()
    }

    fun register(route: Route) {
        route.route("/programs") {

            // ── 读 ──

            get {
                // 列表不统计目录大小，该统计由 itemOf(detailed = true) 完成。
                // 前端每 10s 轮询一次列表；内容目录含数万文件（如 Cloudreve）时，
                // 一次遍历即产生数千次 stat 调用。
                val list = catalog.idsForDisplay().map { id -> itemOf(id) }
                call.respond(
                    toJsonElement(
                        mapOf(
                            "programs" to list,
                            "count" to list.size,
                            // 仅用于展示：告知本机可能无法运行外部程序。
                            // 不作为安装门禁 —— 安装只涉及下载与落盘，需要 exec 的是启动阶段，
                            // 启动失败时由运行时的 spawn 给出精确原因（EACCES/ENOEXEC）。
                            // 启动期的一次探测误判曾使整个商店返回 503，因此该结果不参与门禁。
                            "native_exec" to nativeExecOk(),
                            "total_bytes" to totalBytesCached()
                        )
                    )
                )
            }

            // 内容目录的可选落点（内部 / 公共存储 / SD 卡）。必须先于 `/{id}` 注册。
            // 仅返回「路径 + 剩余空间」，由前端单选；实际可写校验在安装时执行。
            get("/volumes") {
                val list = volumes().map { v ->
                    // 不得向上查找祖先目录：路径不存在（卡被拔出、卷已卸载）时回退到 /storage 或 /
                    // 会把其他卷的剩余空间报给当前卷，产生「88KB 可用 / 1004GB」这类自相矛盾的组合。
                    // 卷不可用时明确返回不可用：free_bytes = -1，由前端显示「未挂载」并禁用选项。
                    // 挂载判据使用卷根，不使用内容子目录（首次使用时该目录尚不存在）。
                    val mount = v.mountPoint?.let { java.io.File(it) }
                    val mounted = mount == null || (mount.exists() && mount.isDirectory && mount.canWrite())
                    val free = if (!mounted) {
                        -1L
                    } else {
                        // 用 StatFs 读取卷的可用空间；目录可能尚未创建，此时可用字节数可能为 0
                        runCatching { android.os.StatFs((mount ?: paths.root).absolutePath).availableBytes }
                            .getOrDefault(0L)
                            .takeIf { it > 0 } ?: -1L
                    }
                    mapOf(
                        "id" to v.id,
                        "label" to v.label,
                        "path" to v.path,
                        "removable" to v.removable,
                        "mounted" to mounted,
                        "total_bytes" to v.totalBytes,
                        "used_bytes" to v.usedBytes,
                        "free_bytes" to free
                    )
                }
                call.respond(toJsonElement(mapOf("volumes" to list)))
            }

            // 必须注册在 `/{id}` 之前，否则会被当成 id="status"
            get("/status") {
                val p = installer.progress
                call.respond(
                    toJsonElement(
                        mapOf(
                            "id" to p.id,
                            "phase" to p.phase,
                            "downloaded_bytes" to p.downloaded,
                            "total_bytes" to p.total,
                            "candidate_index" to p.candidateIndex,
                            "message" to p.message
                        )
                    )
                )
            }

            /**
             * 保存程序设置：访问方式 / 内部端口 / 外部端口 / 开机自启。
             *
             * 端口与访问方式由程序启动时读取配置（argv/env/config.json）决定，因此程序运行中拒绝修改；
             * `autostart` 只写入 app.json，任何状态都可修改。
             */
            patch("/{id}/settings") {
                val id = call.parameters["id"].orEmpty()
                val manifest = catalog.get(id)
                if (manifest == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "清单里没有 $id")
                    return@patch
                }
                val meta = store.readMeta(id)
                if (meta == null) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "程序尚未安装")
                    return@patch
                }
                val body = call.receiveJsonObject()
                val accessMode = body["access_mode"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: meta.accessMode
                val internalPort = body["internal_port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: ProgramSettings.internal(meta, manifest)
                val externalPort = body["external_port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: ProgramSettings.external(meta, manifest)
                val autostart = body["autostart"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: meta.autostart

                ProgramSettings.validate(internalPort, externalPort, accessMode)?.let { reason ->
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.OUT_OF_RANGE, reason)
                    return@patch
                }
                val oldInternal = ProgramSettings.internal(meta, manifest)
                val oldExternal = ProgramSettings.external(meta, manifest)
                val wiringChanged = accessMode != meta.accessMode || internalPort != oldInternal || externalPort != oldExternal
                if (wiringChanged && runtime.isRunning(id)) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "程序正在运行，请先停止再修改端口或访问方式")
                    return@patch
                }
                if (internalPort != oldInternal) {
                    if (!runtime.isPortAvailable(internalPort, wildcard = true)) {
                        call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "内部端口 $internalPort 已被占用")
                        return@patch
                    }
                    portClaimedByOther(id, internalPort)?.let { other ->
                        call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "内部端口 $internalPort 已被程序 $other 占用")
                        return@patch
                    }
                }
                if (accessMode == "gateway" && externalPort != oldExternal) {
                    if (!runtime.isPortAvailable(externalPort, wildcard = true)) {
                        call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "外部端口 $externalPort 已被占用")
                        return@patch
                    }
                    portClaimedByOther(id, externalPort)?.let { other ->
                        call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "外部端口 $externalPort 已被程序 $other 占用")
                        return@patch
                    }
                }
                store.updateMeta(id) {
                    it.copy(
                        accessMode = accessMode,
                        internalPort = internalPort,
                        externalPort = externalPort,
                        autostart = autostart
                    )
                }
                AppLogger.i(
                    TAG,
                    "程序设置已保存: $id access=$accessMode internal=$internalPort external=$externalPort autostart=$autostart"
                )
                call.respond(toJsonElement(mapOf("success" to true, "program" to itemOf(id))))
            }

            get("/{id}") {
                val id = call.parameters["id"].orEmpty()
                val item = runCatching { itemOf(id, detailed = true) }.getOrNull()
                if (item == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "程序不存在: $id")
                } else {
                    call.respond(toJsonElement(item))
                }
            }

            get("/{id}/logs") {
                // 日志可能包含程序输出的 token / 会话信息，读取权限与初始口令（secret）同级。
                // 既然"同级"，`/secret` 要密码、这里也必须 —— 否则门形同虚设。
                // [owner] GET /api/programs/{id}/logs
                if (!call.requireOwnerFromHeader()) return@get
                val id = call.parameters["id"].orEmpty()
                val tailLines = call.request.queryParameters["tail"]?.toIntOrNull()?.coerceIn(1, 2000) ?: 200
                val dir = runCatching { paths.logDir(id) }.getOrNull()
                val stdout = dir?.let { tail(File(it, "stdout.log"), tailLines) }.orEmpty()
                val stderr = dir?.let { tail(File(it, "stderr.log"), tailLines) }.orEmpty()
                call.respond(toJsonElement(mapOf("id" to id, "stdout" to stdout, "stderr" to stderr)))
            }

            // ── 写 ──

            post("/{id}/install") {
                if (call.rejectIfUpdating()) return@post
                val id = call.parameters["id"].orEmpty()
                val manifest = catalog.get(id)
                if (manifest == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "清单里没有 $id（可能未拉取到最新清单）")
                    return@post
                }
                // 仅拦截该程序：其他程序运行不影响本次安装（支持多实例）。
                // 安装自身必须先停止 —— 安装会替换 bin/<entry>，运行中的进程仍持有旧 inode。
                if (runtime.isRunning(id)) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "$id 正在运行，请先停止后再安装")
                    return@post
                }
                if (installer.isBusy) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "已有安装任务在进行，请稍候")
                    return@post
                }
                // 异步执行：48MB 下载不应占用当前请求。
                // 数据位置固定为内部存储：外部存储对同机所有应用可见，而程序数据可能包含凭据；
                // 大文件通过 content_root 迁移。
                // 兼容旧客户端：仍接受 `storage` 参数，但忽略除 internal 以外的值。
                call.request.queryParameters["storage"]?.let {
                    if (it != "internal") AppLogger.w(TAG, "忽略 storage=$it：数据目录固定内部存储")
                }
                val storage = "internal"
                val mode = call.request.queryParameters["access_mode"]
                if (mode != null && mode !in setOf("direct", "gateway")) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.OUT_OF_RANGE, "access_mode 只能是 direct / gateway")
                    return@post
                }
                val contentRoot = call.request.queryParameters["content_root"]
                contentRootIssue(contentRoot)?.let { msg ->
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.OUT_OF_RANGE, msg)
                    return@post
                }
                AppLogger.i(
                    TAG,
                    "开始安装 $id（v${manifest.artifact.version}，${manifest.artifact.size / 1024 / 1024}MB，" +
                        "storage=${storage ?: manifest.run.storage}，content=${contentRoot ?: manifest.content.defaultRoot}）"
                )
                scope.launch { installer.installFromRemote(manifest, storage, mode, contentRoot) }
                call.respond(
                    toJsonElement(mapOf("accepted" to true, "id" to id, "message" to "已开始下载"))
                )
            }

            post("/{id}/upload") {
                if (call.rejectIfUpdating()) return@post
                // 上传的字节直接来自客户端，装完就能 start —— 与 shell/exec 同级的执行能力。
                // 从清单安装（`/{id}/install`）不要密码，只有这条要。
                // [owner] POST /api/programs/{id}/upload
                if (!call.requireOwnerFromHeader()) return@post
                val id = call.parameters["id"].orEmpty()
                val manifest = catalog.get(id)
                if (manifest == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "清单里没有 $id")
                    return@post
                }
                if (runtime.isRunning(id)) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "$id 正在运行，请先停止后再上传")
                    return@post
                }
                if (installer.isBusy) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "已有安装任务在进行，请稍候")
                    return@post
                }
                // 数据位置固定为内部存储：外部存储对同机所有应用可见，而程序数据可能包含凭据；
                // 大文件通过 content_root 迁移。
                // 兼容旧客户端：仍接受 `storage` 参数，但忽略除 internal 以外的值。
                call.request.queryParameters["storage"]?.let {
                    if (it != "internal") AppLogger.w(TAG, "忽略 storage=$it：数据目录固定内部存储")
                }
                val storage = "internal"
                val access = call.request.queryParameters["access_mode"]
                if (access != null && access !in setOf("direct", "gateway")) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.OUT_OF_RANGE, "access_mode 只能是 direct / gateway")
                    return@post
                }
                val uploadContentRoot = call.request.queryParameters["content_root"]
                contentRootIssue(uploadContentRoot)?.let { msg ->
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.OUT_OF_RANGE, msg)
                    return@post
                }
                // 可选声明哈希：给了就必须与实测一致（`?sha256=<hex>`）
                val expectedSha = call.request.queryParameters["sha256"]
                val result = withContext(Dispatchers.IO) {
                    installer.installFromStream(
                        manifest,
                        call.receiveStream(),
                        storage,
                        access,
                        uploadContentRoot,
                        expectedSha256 = expectedSha
                    )
                }
                result.fold(
                    onSuccess = { meta ->
                        call.respond(toJsonElement(mapOf("installed" to true, "version" to meta.version)))
                    },
                    onFailure = { e ->
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "安装失败")
                    }
                )
            }

            post("/{id}/uninstall") {
                // 卸载不可逆：默认连数据目录一起删（`delete_logs` 还会删日志）。
                // 与「安装」不是一类 —— 装错可以卸了重装，卸载之后数据就没了。
                // [owner] POST /api/programs/{id}/uninstall
                if (!call.requireOwnerFromHeader()) return@post
                val id = call.parameters["id"].orEmpty()
                if (runtime.isRunning(id)) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "$id 正在运行，请先停止")
                    return@post
                }
                supervisor.setDesired(id, false)
                // 默认只删除程序与数据，日志保留（作为排障依据）；前端显式勾选「彻底卸载」时才删除日志。
                val deleteLogs = call.request.queryParameters["delete_logs"]?.toBooleanStrictOrNull() ?: false
                store.uninstall(id, deleteLogs = deleteLogs).fold(
                    onSuccess = { failures ->
                        call.respond(
                            toJsonElement(
                                mapOf(
                                    "uninstalled" to true,
                                    "residual" to failures,
                                    "logs_deleted" to deleteLogs,
                                    "message" to when {
                                        failures.isNotEmpty() -> "二进制已删除，但有 ${failures.size} 个路径删不掉"
                                        deleteLogs -> "已彻底卸载（含日志）"
                                        else -> "已卸载（日志保留）"
                                    }
                                )
                            )
                        )
                    },
                    onFailure = { e -> call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, e.message ?: "卸载失败") }
                )
            }

            post("/{id}/start") {
                val id = call.parameters["id"].orEmpty()
                val manifest = catalog.get(id)
                if (manifest == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "清单里没有 $id")
                    return@post
                }
                // 多实例：其他程序运行不影响启动本程序；本程序已在运行时 runtime.start 幂等返回 pid。
                val result = withContext(Dispatchers.IO) {
                    runCatching { runtime.start(manifest) }
                        .getOrElse { e -> Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName)) }
                }
                result.fold(
                    onSuccess = { pid ->
                        // 启动成功后才设置启动意图：失败不应留下 desired=true 使 Supervisor 反复重试。
                        supervisor.setDesired(id, true)
                        store.writeState(id, store.readState(id).copy(lastStartAt = System.currentTimeMillis()))
                        call.respond(toJsonElement(mapOf("started" to true, "pid" to pid)))
                    },
                    onFailure = { e ->
                        call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, e.message ?: "启动失败")
                    }
                )
            }

            post("/{id}/stop") {
                val id = call.parameters["id"].orEmpty()
                // 先清除启动意图，否则 Supervisor 会在 10s 后重新拉起
                supervisor.setDesired(id, false)
                val manifest = catalog.get(id)
                val grace = manifest?.run?.stop?.graceS ?: 10
                val result = runtime.stop(id, "用户请求", grace)
                result.fold(
                    onSuccess = { call.respond(toJsonElement(mapOf("stopped" to true))) },
                    onFailure = { e ->
                        call.respondFail(
                            HttpStatusCode.InternalServerError,
                            ErrorCode.INTERNAL_ERROR,
                            e.message ?: "停止失败"
                        )
                    }
                )
            }

            /**
             * 切换访问方式（direct / gateway）。必须先停止程序。
             *
             * 访问地址由程序启动时读取 config 决定，修改后需重启才生效
             * （Adapter 在 preflight 中写入 address）。
             */
            post("/{id}/access-mode") {
                val id = call.parameters["id"].orEmpty()
                val mode = call.request.queryParameters["mode"].orEmpty()
                if (mode !in setOf("direct", "gateway")) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.OUT_OF_RANGE, "mode 只能是 direct / gateway")
                    return@post
                }
                if (runtime.isRunning(id)) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "程序正在运行，请先停止再切换")
                    return@post
                }
                store.updateMeta(id) { it.copy(accessMode = mode) }
                call.respond(toJsonElement(mapOf("access_mode" to mode)))
            }

            /**
             * 签发访问凭证并返回前端应访问的端口。
             *
             * 走完整的 `/api` 鉴权：该操作对外暴露一个入口，与 start/stop 同级。
             * 返回端口号而非完整 URL —— 访问可能经隧道域名进入，前端使用 `location.hostname`
             * 拼接更准确。
             */
            post("/{id}/open") {
                val id = call.parameters["id"].orEmpty()
                val manifest = catalog.get(id)
                if (manifest == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "清单里没有 $id")
                    return@post
                }
                if (!runtime.isRunning(id)) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "程序未运行，请先启动")
                    return@post
                }
                val metaForOpen = store.readMeta(id)
                val mode = metaForOpen?.accessMode ?: "direct"
                if (mode == "direct") {
                    // 直连模式不需要 cookie：直接返回前端应访问的端口
                    call.respond(
                        toJsonElement(
                            mapOf(
                                "direct" to true,
                                "scheme" to "http",
                                "port" to ProgramSettings.internal(metaForOpen, manifest),
                                "path" to manifest.ui.openPath
                            )
                        )
                    )
                    return@post
                }
                val actualExternalPort = ProgramSettings.external(metaForOpen, manifest)
                if (actualExternalPort <= 0) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "清单未声明 external_port")
                    return@post
                }
                if (gatewaySecret.isEmpty()) {
                    call.respondFail(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorCode.UNAVAILABLE,
                        "访问入口未启用（Gateway 密钥缺失）"
                    )
                    return@post
                }
                // cookie 不区分端口：在 8088 写入后，浏览器访问 18080 时会自动携带
                val cookieValue = ProgramGatewaySecret.cookieValue(gatewaySecret, id)
                call.response.cookies.append(
                    io.ktor.http.Cookie(
                        name = ProgramGateway.cookieName(id),
                        value = cookieValue,
                        path = "/",
                        maxAge = 86_400,
                        httpOnly = true
                    )
                )
                // 一次性入口票据：系统浏览器打开时没有 core 的 cookie，
                // 浏览器先访问 8088 上的 /apps/{id}/enter?t=... 换取 cookie，再跳转到程序端口。
                val enterTicket = ProgramEnterTickets.issue(id)
                call.respond(
                    toJsonElement(
                        mapOf(
                            "scheme" to "http",
                            "port" to actualExternalPort,
                            "path" to manifest.ui.openPath,
                            "expires_in" to 86_400,
                            "enter_path" to "/apps/$id/enter?t=$enterTicket"
                        )
                    )
                )
            }

            /**
             * 消费一次性初始口令。
             *
             * 列表只返回 has_secret，口令值仅通过本接口下发。取出后 Adapter 清空内存值，
             * 下一次列表 has_secret 变为 false —— 与 UI 的「只显示一次」语义一致。
             */
            post("/{id}/secret") {
                // 凭据外发：这里返回的是程序的管理员初始口令，任何持 Bearer 的客户端读到就等于拿到那个程序。
                // [owner] POST /api/programs/{id}/secret
                if (!call.requireOwnerFromHeader()) return@post
                val id = call.parameters["id"].orEmpty()
                if (store.readMeta(id) == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "程序尚未安装: $id")
                    return@post
                }
                val secret = adapterFor(id)?.consumeBootstrapSecret(id)
                if (secret.isNullOrBlank()) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "没有可查看的初始口令（可能已消费或 Core 已重启）")
                    return@post
                }
                call.respond(toJsonElement(mapOf("id" to id, "secret" to secret)))
            }

            /** 初始口令丢失或旧数据没有管理员时，可在停止程序后重置一次。 */
            post("/{id}/reset-secret") {
                if (call.rejectIfUpdating()) return@post
                // 与 `/{id}/secret` 同能力（都能拿到/重置程序管理员口令），必须同样要密码。
                // **用头**：这个端点 body 里的 `password` 是"要设置的新口令"，
                // 拿它当主人密码比对会把新口令本身当凭据。
                // [owner] POST /api/programs/{id}/reset-secret
                if (!call.requireOwnerFromHeader()) return@post
                val id = call.parameters["id"].orEmpty()
                val manifest = catalog.get(id)
                if (manifest == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "清单里没有 $id")
                    return@post
                }
                if (runtime.isRunning(id)) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "重置前请先停止程序")
                    return@post
                }
                if (installer.isBusy) {
                    call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, "已有安装任务在进行，请稍候")
                    return@post
                }
                val meta = store.readMeta(id)
                if (meta == null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "程序尚未安装: $id")
                    return@post
                }
                val adapter = adapterFor(id)
                if (adapter == null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "该程序不支持重置初始口令")
                    return@post
                }
                // 可选自定义口令：为空/缺省时仍走随机生成。字符集限制是为了避免
                // 口令里的引号/换行破坏 dufs.yaml 或 shell argv；8~64 位。
                val customPassword = runCatching {
                    call.receiveJsonObject()["password"]?.jsonPrimitive?.contentOrNull?.trim()
                }.getOrNull()?.takeIf { it.isNotEmpty() }
                if (customPassword != null && !CUSTOM_PASSWORD_REGEX.matches(customPassword)) {
                    call.respondFail(
                        HttpStatusCode.BadRequest,
                        ErrorCode.BAD_REQUEST,
                        "自定义口令需为 8–64 位字母、数字或 . _ -"
                    )
                    return@post
                }
                val storage = ProgramPaths.parseStorage(meta.storage)
                val ctx = ProgramContext(
                    id = id,
                    binPath = paths.binFile(id, meta.entry),
                    dataDir = paths.dataDir(id, storage),
                    confDir = paths.confDir(id, storage),
                    runDir = paths.runDir(id),
                    contentDir = paths.contentDir(id, meta.contentDir),
                    contentEnabled = manifest.content.enabled,
                    internalPort = ProgramSettings.internal(meta, manifest),
                    accessMode = meta.accessMode
                )
                val result = withContext(Dispatchers.IO) {
                    adapter.resetBootstrapSecret(ctx, customPassword)
                }
                result.fold(
                    onSuccess = { secret -> call.respond(toJsonElement(mapOf("id" to id, "secret" to secret))) },
                    onFailure = { e ->
                        call.respondFail(
                            HttpStatusCode.InternalServerError,
                            ErrorCode.INTERNAL_ERROR,
                            e.message ?: "重置初始口令失败"
                        )
                    }
                )
            }

            post("/{id}/restart") {
                val id = call.parameters["id"].orEmpty()
                val manifest = catalog.get(id)
                if (manifest == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "清单里没有 $id")
                    return@post
                }
                // 重启期间先清除启动意图，避免 stop 后 Supervisor 同时拉起旧进程与新进程。
                supervisor.setDesired(id, false)
                val stopped = withContext(Dispatchers.IO) { runtime.stop(id, "重启", manifest.run.stop.graceS) }
                if (stopped.isFailure) {
                    call.respondFail(
                        HttpStatusCode.InternalServerError,
                        ErrorCode.INTERNAL_ERROR,
                        stopped.exceptionOrNull()?.message ?: "重启失败：停止旧进程失败"
                    )
                    return@post
                }
                val result = withContext(Dispatchers.IO) { runtime.start(manifest) }
                result.fold(
                    onSuccess = { pid ->
                        supervisor.setDesired(id, true)
                        store.writeState(id, store.readState(id).copy(lastStartAt = System.currentTimeMillis()))
                        call.respond(toJsonElement(mapOf("started" to true, "pid" to pid)))
                    },
                    onFailure = { e ->
                        call.respondFail(HttpStatusCode.Conflict, ErrorCode.CONFLICT, e.message ?: "重启失败")
                    }
                )
            }
        }
    }

    // ── 组装列表项 ──

    /** 端口是否已被另一个程序（含未运行的）配置占用。 */
    private fun portClaimedByOther(id: String, port: Int): String? =
        catalog.idsForDisplay().firstOrNull { other ->
            if (other == id) return@firstOrNull false
            val otherManifest = catalog.get(other) ?: return@firstOrNull false
            val otherMeta = store.readMeta(other)
            ProgramSettings.internal(otherMeta, otherManifest) == port ||
                ProgramSettings.external(otherMeta, otherManifest) == port
        }

    /**
     * 组装单个程序的列表项。
     *
     * @param detailed 是否统计磁盘占用。列表接口必须传 false（每 10s 轮询一次），
     *   详情页传 true —— 目录大小需要递归遍历，成本随文件数增长。
     */
    private fun itemOf(id: String, detailed: Boolean = false): Map<String, Any?> {
        val manifest: ProgramManifest? = catalog.get(id)
        val meta = store.readMeta(id)
        val snap = runtime.snapshot(id)
        val skeleton = catalog.skeletonOf()[id]
        val state = store.readState(id)
        return mapOf(
            "id" to id,
            "name" to (manifest?.name ?: skeleton?.name ?: id),
            "summary" to (manifest?.summary ?: skeleton?.summary ?: ""),
            "homepage" to (manifest?.homepage ?: ""),
            "license" to (manifest?.license ?: ""),
            // 远端清单可用 = 存在下载信息；否则为「暂无可用版本」（断网或清单未拉取）
            "available" to (manifest != null),
            "latest_version" to (manifest?.artifact?.version ?: ""),
            "download_size" to (manifest?.artifact?.size ?: 0L),
            "installed" to store.isInstalled(id),
            "installed_version" to (meta?.version ?: ""),
            "installed_size" to (meta?.size ?: 0L),
            "source" to (meta?.source ?: ""),
            "phase" to snap.phase.name.lowercase(),
            "pid" to snap.pid,
            "rss_mb" to snap.rssMb,
            "uptime_ms" to snap.uptimeMs,
            "last_error" to snap.lastError,
            "last_exit_code" to snap.lastExitCode,
            "desired" to state.desired,
            "circuit_open" to state.circuitOpen,
            "consecutive_failures" to state.consecutiveFailures,
            "external_port" to if (manifest != null) ProgramSettings.external(meta, manifest) else (meta?.externalPort ?: 0),
            "internal_port" to if (manifest != null) ProgramSettings.internal(meta, manifest) else (meta?.internalPort ?: 0),
            // 开机自启（与运行态 desired 分开）：只影响 Core 下次启动时是否拉起
            "autostart" to (meta?.autostart ?: false),
            "requires_root" to (manifest?.requires?.root ?: false),
            "used_bytes" to if (detailed) runCatching {
                paths.usedBytes(id, ProgramPaths.parseStorage(meta?.storage), meta?.contentDir)
            }.getOrDefault(0L) else 0L,
            // 日志与缓存不计入 used_bytes，但位于同一 userdata 分区。
            // 单独返回这两项，前端才能说明卸载后残留的数据，并避免求和错误。
            "log_bytes" to if (detailed) paths.logBytes(id) else 0L,
            "cache_bytes" to if (detailed) paths.cacheBytes(id) else 0L,
            // 数据目录含凭据 → 禁止选外部存储（安装接口会拒，前端据此把选项置灰）
            "private_data" to (manifest?.requires?.privateData ?: false),
            // 内容目录（大文件）与数据目录分开：可以放到 SD 卡，不要求 internal
            "content_enabled" to (manifest?.content?.enabled ?: false),
            "content_dir" to runCatching {
                paths.contentDir(id, meta?.contentDir).absolutePath
            }.getOrDefault(""),
            "content_bytes" to if (detailed) paths.contentBytes(id, meta?.contentDir) else 0L,
            // 数据落点及当前可用性：外部存储被拔出时为 false，
            // 前端据此显示「存储不可用」并禁用启动，而不是等到启动时才失败。
            "storage" to (meta?.storage ?: manifest?.run?.storage ?: "internal"),
            "storage_ready" to runtime.isStorageReady(id),
            // 内容目录（大文件）可能单独位于 SD 卡/U 盘：拔出后数据卷仍可用，但程序写入内容会失败。
            // 分别返回两者状态，前端才能定位是哪块存储不可用。
            "content_ready" to runtime.isContentReady(id),
            // 访问方式：direct = 浏览器直接开 internal_port；gateway = 经 core 转发（要 cookie）
            "access_mode" to (meta?.accessMode ?: if (manifest?.run?.bind == "loopback") "gateway" else "direct"),
            "ui_path" to (manifest?.ui?.openPath ?: "/"),
            // 初始口令不随列表下发：读列表只需普通鉴权，而管理员口令属于敏感秘密。
            // 前端看到 has_secret 后显式调用 POST /{id}/secret 消费一次。
            "has_secret" to (meta != null && bootstrapSecretOf(id) != null)
        )
    }

    /** 商店总占用的缓存（60s）：统计需遍历所有程序目录，不能随 10s 轮询执行。 */
    private val totalCache = java.util.concurrent.atomic.AtomicReference(0L to 0L)

    private fun totalBytesCached(): Long {
        val now = System.currentTimeMillis()
        val (ts, value) = totalCache.get()
        if (now - ts in 0..60_000 && value > 0) return value
        val fresh = runCatching { paths.totalBytes() + contentOutsideRoots() }.getOrDefault(0L)
        totalCache.set(now to fresh)
        return fresh
    }

    /**
     * 落在受管根之外的内容目录（典型：SD 卡上的 `/storage/XXXX/UFI-AXIS/content/<id>`）。
     *
     * [ProgramPaths.totalBytes] 只扫描内部与公共存储两个根，不包含 SD 卡上的内容；
     * 而内部默认的内容目录已被统计，重复计算会得到双倍值。因此这里只补充差额。
     */
    private fun contentOutsideRoots(): Long = catalog.idsForDisplay().sumOf { id ->
        val meta = store.readMeta(id) ?: return@sumOf 0L
        val dir = paths.contentDir(id, meta.contentDir).absolutePath
        val inside = dir.startsWith(paths.root.absolutePath) ||
            dir.startsWith(com.ufi_axis_core.util.program.ProgramPaths.externalRoot().absolutePath)
        if (inside) 0L else paths.contentBytes(id, meta.contentDir)
    }

    private fun adapterFor(id: String): com.ufi_axis_core.controller.program.ProgramAdapter? =
        catalog.get(id)?.firstRun?.adapter?.let { ProgramAdapterRegistry.get(it) }

    private fun bootstrapSecretOf(id: String): String? = adapterFor(id)?.bootstrapSecret(id)

    /** 读文件尾 N 行。文件不存在返回空串。 */
    private fun tail(file: File, lines: Int): String {
        if (!file.exists()) return ""
        return runCatching {
            file.useLines { seq ->
                val deque = ArrayDeque<String>(lines)
                seq.forEach { line ->
                    if (deque.size >= lines) deque.removeFirst()
                    deque.addLast(line)
                }
                deque.joinToString("\n")
            }
        }.getOrDefault("")
    }

    /**
     * 公开路由（不挂在 `/api` 下，因此不经过 ECDSA 鉴权）：
     * `GET /apps/{id}/enter?t=<一次性票据>`。
     *
     * 校验票据 → 种 Gateway cookie → 302 到 `http://<当前 Host>:<external_port><ui_path>`。
     * 浏览器访问的是同一个 Host 的不同端口，cookie 不区分端口，于是 Gateway 放行。
     */
    fun registerPublic(route: Route) {
        route.route("/apps") {
            get("/{id}/enter") {
                val id = call.parameters["id"].orEmpty()
                val ticket = call.request.queryParameters["t"].orEmpty()
                if (!ProgramEnterTickets.consume(id, ticket)) {
                    call.respondText(
                        "链接已失效（一次性入口，60 秒内有效）。请回控制台重新点「打开」。",
                        status = HttpStatusCode.Gone
                    )
                    return@get
                }
                val manifest = catalog.get(id)
                val meta = store.readMeta(id)
                val externalPort = if (manifest != null && meta != null) {
                    ProgramSettings.external(meta, manifest)
                } else {
                    0
                }
                if (manifest == null || meta == null || externalPort <= 0) {
                    call.respondText("程序不可用或未声明外部端口", status = HttpStatusCode.NotFound)
                    return@get
                }
                if (gatewaySecret.isEmpty()) {
                    call.respondText("访问入口未启用（Gateway 密钥缺失）", status = HttpStatusCode.ServiceUnavailable)
                    return@get
                }
                call.response.cookies.append(
                    io.ktor.http.Cookie(
                        name = ProgramGateway.cookieName(id),
                        value = ProgramGatewaySecret.cookieValue(gatewaySecret, id),
                        path = "/",
                        maxAge = 86_400,
                        httpOnly = true
                    )
                )
                val host = call.request.host()
                val path = manifest.ui.openPath.ifBlank { "/" }
                call.respondRedirect("http://$host:$externalPort$path", permanent = false)
            }
        }
    }

    private companion object {
        /** 自定义管理员口令的字符集：避免 YAML/shell 注入，同时足够用户手抄。 */
        val CUSTOM_PASSWORD_REGEX = Regex("^[A-Za-z0-9._-]{8,64}$")

        const val TAG = "ProgramRoutes"
    }
}
