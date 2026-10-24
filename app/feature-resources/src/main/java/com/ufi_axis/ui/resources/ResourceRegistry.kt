package com.ufi_axis.ui.resources

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Web
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.util.NetworkErrorClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File

/**
 * 资源中心 —— **登记表**（2026-10-14）。
 *
 * 本批落地三条描述符：Web 前端 ZIP（单例）、frpc、cloudflared（两条集合，出自同一个工厂）。
 *
 * ## 为什么 frpc 与 cloudflared 是"同一个工厂的两个实例"
 * 它们对 core 来说是同一套端点的同一个清单里的两条（`GET /api/components` 一次返回两条），
 * 差异只有 id / 名字 / 说明 / 图标。写成 `componentDescriptor(id, …)` 的两个调用点之后，
 * **第三条组件（比如以后加 tailscale）就是再加一行**，不需要新屏幕、不需要新分支 ——
 * 这正是用户点名的"后续添加新的资源文件无需重写重复代码"。
 *
 * ## 端点来源（全部是 core 里已存在的，本批**不新增任何 Retrofit 注解**）
 *  · Web：`GET /api/web/version`、`GET /api/web/status`（裸 GET 取 `max_upload_bytes`）、
 *    `POST /api/web/check`、`POST /api/web/update`(multipart, field=`file`)、
 *    `POST /api/web/rollback`、`POST /api/web/clear`；
 *  · 组件：`GET /api/components`(`?refresh=true`)、`GET /api/components/status`、
 *    `POST /api/components/{id}/install`、`POST /api/components/{id}/uninstall`。
 *
 * ## 组件的本地上传为什么没做
 * core 有 `POST /api/components/{id}/upload`，但 app 侧要 SAF 选裸二进制 + 几十 MB 流式上传；
 * 这是仓内**既有结论**（写在 `UfiAxisApi` 的组件分组注释里），离线兜底留给 Web 面板。
 * 所以两条组件描述符的 [ResourceDescriptor.accept] 是空的、[ResourceDescriptor.upload] 是 null，
 * 卡片因此不会画上传按钮 —— 而不是画一个点了报错的按钮。
 */
object ResourceRegistry {

    /**
     * Web 前端 ZIP 的资源 id。
     *
     * core 侧没有这个名字（它不是 components 清单里的一项），由本层定义；
     * 取值与 web 端 `resourceRegistry.ts` 的 `id: 'web'` **逐字一致** ——
     * 两端描述符是同一套概念，id 不一致会让"这条资源在另一端的槽位/日志"对不上号。
     */
    const val WEB_ZIP_ID = "web"

    // ── Web 前端资源（单例）──
    val webZip: ResourceDescriptor = ResourceDescriptor(
        id = WEB_ZIP_ID,
        name = "Web 前端资源",
        desc = "设备上那套网页控制面板（ZIP）。core 可从更新源自拉覆盖安装，也可本地上传；" +
            "上传坏产物后面板本身打不开，所以恢复入口必须留在 App 里。",
        icon = Icons.Default.Web,
        kind = ResourceKind.SINGLETON,
        accept = listOf("application/zip", "application/octet-stream"),
        fetch = { api -> fetchWebZip(api) },
        // 「检查更新」= **只读地**重读一次版本信息（与 web 端 `refresh: loadVersion` 同一语义）。
        // 它**不**触发下载安装 —— 那个动词是 install，见下。
        refresh = { api -> api.service.getWebAssetVersion() },
        // 在线更新 = POST /api/web/check（检查 + 下载 + 校验 + 安装一条龙）。
        // 异步：200 只代表"已受理/已忽略重复触发"，结论要靠轮询 /api/web/status 落终态；
        // 且"已是最新版本"时 core 压根不会进入进行中 → installPoll 必须是 AWAIT_START。
        install = { api, _ -> runRequest { api.service.checkWebAssetUpdate() } },
        installPoll = PollMode.AWAIT_START,
        // 上传在 core 侧是**同步**的（WebUpdateRoutes 里 installFromZip 直接跑完再响应），
        // 所以响应即终态，不需要轮询；进度只有"客户端 → core"这一层。
        upload = { api, file, onProgress -> uploadWebZip(api, file, onProgress) },
        rollback = { api -> runRequest { api.service.rollbackWebAssets() } },
        clear = { api -> runRequest { api.service.clearWebAssets() } },
        extraRows = { snapshot ->
            listOf(
                "当前来源" to (snapshot.fields["mode_label"] ?: "—"),
                "当前版本" to (snapshot.fields["version"] ?: "未知"),
                "内置版本" to (snapshot.fields["bundled_version"] ?: "未知"),
                "可回滚备份" to (snapshot.fields["has_backup"] ?: "无")
            )
        },
        // Web ZIP 的 core 侧进度实质只有 0/100（WebUpdateManager 只走状态机，不报字节进度），
        // 这里**不改**状态名，只是把"上传那一层"的话说清楚，免得用户以为 100% 之后卡住了。
        stateMeta = { state ->
            when (state) {
                "installing" -> StateMeta("安装中（已推送完毕，core 正在覆盖 assets/web）", StateTone.PROGRESS)
                else -> null
            }
        },
        onFinished = { ResourceChangeBus.notifyChanged() },
        onInstalled = { ResourceChangeBus.notifyChanged() },
    )

    // ── 可选二进制组件（集合）：两条同构描述符 ──
    val frpc: ResourceDescriptor = componentDescriptor(
        id = "frpc",
        name = "frpc",
        desc = "FRP 内网穿透客户端，用于把本机端口映射到自有 frps 服务器。缺它时 FRP 隧道无法启动。",
        icon = Icons.Default.Hub,
    )

    val cloudflared: ResourceDescriptor = componentDescriptor(
        id = "cloudflared",
        name = "cloudflared",
        desc = "Cloudflare Tunnel 客户端，用于通过 Cloudflare 边缘网络暴露本机服务。缺它时 CF 隧道无法启动。",
        icon = Icons.Default.Cloud,
    )

    /**
     * 全部资源 —— [ResourcesScreen] 就是遍历这个列表。
     *
     * ⚠ 顺序即界面顺序。定义在所有描述符**之后**（object 的初始化按书写顺序执行）。
     */
    val all: List<ResourceDescriptor> = listOf(webZip, frpc, cloudflared)
}

/**
 * 「资源发生了变化」的进程内广播（2026-10-14）。
 *
 * 存在的理由：描述符的 [ResourceDescriptor.onFinished] / [ResourceDescriptor.onInstalled] 是
 * 冻结字段，必须有用武之地；而它们的语义正是"刚刚动了某个资源，别人看到的数据可能过期了"。
 * 装了 frpc 之后，**另一张卡**（cloudflared）上"组件目录占用"那一行同样变了；
 * 上传了新 Web ZIP 之后，服务器配置页那颗行卡的版本号也变了。
 *
 * 做成一个自增 tick 而不是事件对象：消费者只有"要不要重读"这一种反应，
 * 一个 Int 就够了；而且 `collectAsState` + `LaunchedEffect(tick)` 是仓内既有的写法。
 */
object ResourceChangeBus {
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick.asStateFlow()

    fun notifyChanged() {
        _tick.value += 1
    }
}

// ────────────────────────── 组件（集合）描述符工厂 ──────────────────────────

/**
 * 组件描述符工厂。**新增一个可选二进制组件 = 再调一次本函数**。
 *
 * 三个 lambda 里唯一的"身份"是闭包捕获的 [id]，它只用来从清单里挑出自己那一条、
 * 以及拼 `POST /api/components/{id}/…` 的路径 —— **不构成 UI 分支**。
 */
private fun componentDescriptor(
    id: String,
    name: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
): ResourceDescriptor = ResourceDescriptor(
    id = id,
    name = name,
    desc = desc,
    icon = icon,
    kind = ResourceKind.COLLECTION,
    // accept 为空 + upload 为 null ⇒ 卡片不画上传按钮（理由见 ResourceRegistry 的类注释）
    fetch = { api -> fetchComponent(api, id) },
    refresh = { api -> api.service.listComponents("true") },
    install = { api, itemId -> runRequest { api.service.installComponent(itemId) } },
    uninstall = { api, itemId -> runRequest { api.service.uninstallComponent(itemId) } },
    extraRows = { snapshot ->
        snapshot.fields["dir_bytes"]?.let { listOf("组件目录占用" to it) } ?: emptyList()
    },
    // 组件侧状态名与缺省表逐一对应（多一档 extracting，缺省表里已经有）。
    // 不覆盖 stateMeta：缺省表就是权威，覆盖只会造成"两处各写一份"。
    onFinished = { ResourceChangeBus.notifyChanged() },
    onInstalled = { ResourceChangeBus.notifyChanged() },
)

/**
 * 读一次组件清单，只留下本描述符负责的那一条。
 *
 * ## 进度槽要按 id 认领，不能照抄
 * core 的进度槽是**全局唯一**的（`ComponentManager.busy`：同一时刻只允许一个安装任务），
 * 所以 `state`/`percent` 只属于 `progress.id` 那一个组件。这里必须判 `root["id"] == id`，
 * 否则装 frpc 时 cloudflared 那张卡会显示成"它也在下载 37%" —— web 端
 * `resourceRegistry.ts` 里 `progress.id === id` 那一行是同一个坑的同一处修复。
 */
private suspend fun fetchComponent(api: ResourceApi, id: String): ResourceSnapshot {
    val root = api.service.listComponents() as? JsonObject
        ?: return ResourceSnapshot(error = "组件接口返回了非对象")
    val manifestLoading = root["manifest_loading"]?.jsonPrimitive?.booleanOrNull ?: false
    val items = root["components"]?.jsonArray
        ?.mapNotNull { toResourceItem(it.jsonObject, manifestLoading) }
        ?.filter { it.id == id }
        ?: emptyList()
    val progressId = root["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return ResourceSnapshot(
        items = items,
        // 不是我的任务 ⇒ 一律 idle（percent 归零），否则另一张卡会显示成它也在装
        task = if (progressId == id) toTaskStatus(root) else ResourceTaskStatus(),
        manifestLoading = manifestLoading,
        manifestError = root["manifest_error"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        // 上限由端点下发，不在 app 侧写死（理由见 ResourceSnapshot.uploadLimitBytes）
        uploadLimitBytes = root["max_upload_bytes"]?.jsonPrimitive?.longOrNull,
        fields = mapOfNotNull(
            "dir_bytes" to root["dir_bytes"]?.jsonPrimitive?.longOrNull
                ?.let { FormatUtils.formatBytes(it) }
        ),
    )
}

/** `GET /api/components` 里的一条 → [ResourceItem]（文案拼法与 `TunnelSettingsComponentsScreen` 一致）。 */
private fun toResourceItem(o: JsonObject, manifestLoading: Boolean): ResourceItem? {
    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val installed = o["installed"]?.jsonPrimitive?.booleanOrNull ?: false
    val installedVersion = o["installed_version"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val installedSize = o["installed_size"]?.jsonPrimitive?.longOrNull ?: 0L
    val source = o["source"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val latestVersion = o["latest_version"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val downloadSize = o["download_size"]?.jsonPrimitive?.longOrNull ?: 0L
    val available = o["available"]?.jsonPrimitive?.booleanOrNull ?: false
    val updateAvailable = o["update_available"]?.jsonPrimitive?.booleanOrNull ?: false

    val parts = mutableListOf<String>()
    if (installed) {
        parts += "已安装 " + installedVersion.ifBlank { "版本未知" }
        if (installedSize > 0) parts += FormatUtils.formatBytes(installedSize)
        when (source) {
            "manual" -> parts += "本地上传"
            "legacy" -> parts += "旧版迁移"
        }
        if (updateAvailable) parts += "有新版 $latestVersion"
        else if (manifestLoading) parts += "正在检查新版本…"
    } else {
        parts += "未安装"
        if (downloadSize > 0) parts += "需下载约 " + FormatUtils.formatBytes(downloadSize)
        // 清单还在后台拉时"没有可用版本"只是"还没拿到"，不能直接报无可用版本（原页同款判断）
        if (!available) parts += if (manifestLoading) "正在获取最新版本…" else "更新源无可用版本"
    }
    return ResourceItem(
        id = id,
        title = o["name"]?.jsonPrimitive?.contentOrNull ?: id,
        detail = parts.joinToString(" · "),
        installed = installed,
        updateAvailable = updateAvailable,
        // 有可用直链才谈得上装：未装 → 「下载安装」，已装且有新版 → 「更新」，其余 → 没有动作。
        // 这条判断留在描述符里（而不是让卡片去组合 available/installed/updateAvailable），
        // 见 ResourceItem.actionLabel 的 KDoc。
        actionLabel = when {
            !available -> null
            !installed -> "下载安装"
            updateAvailable -> "更新"
            else -> null
        },
        uninstallable = installed,
    )
}

/** `GET /api/components`（以及 `/status`）里的任务三元组。 */
private fun toTaskStatus(o: JsonObject): ResourceTaskStatus = ResourceTaskStatus(
    state = o["state"]?.jsonPrimitive?.contentOrNull ?: "idle",
    percent = o["percent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
    message = o["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
)

// ────────────────────────── Web 前端（单例）描述符 ──────────────────────────

/** `GET /api/web/version` + `GET /api/web/status` 合成一张单例快照。 */
private suspend fun fetchWebZip(api: ResourceApi): ResourceSnapshot {
    val version = runCatching { api.service.getWebAssetVersion() }.getOrNull()?.jsonObject
        // 读不到版本信息时整卡报错：core 未连接时这一页本来就没什么可做的
        ?: return ResourceSnapshot(error = "读取 Web 面板信息失败（Core 未连接时不可用）")
    val status = runCatching { api.service.getWebUpdateStatus() }.getOrNull()

    fun str(key: String) = version[key]?.jsonPrimitive?.contentOrNull
    val isOverride = str("mode") == "override"
    val current = str("version").orEmpty()
    // bundled 模式下 core 不返回 bundledVersion，此时它就是 version 本身（NetworkModule 同款口径）
    val bundled = str("bundledVersion") ?: current
    val hasBackup = version["hasBackup"]?.jsonPrimitive?.booleanOrNull ?: false

    return ResourceSnapshot(
        items = listOf(
            ResourceItem(
                id = ResourceRegistry.WEB_ZIP_ID,
                title = if (isOverride) "已上传（override）" else "APK 内置",
                detail = if (isOverride) {
                    "当前 $current · 内置 $bundled"
                } else {
                    "内置版本 ${current.ifBlank { "未知" }}"
                },
                // 单例语义：installed = "自定义资源正在生效" → 「恢复内置」是否可用
                installed = isOverride,
                // 单例语义：updateAvailable = "存在可切换的另一版" → 「回滚」是否可用
                updateAvailable = hasBackup,
                // 单例的动作**恒定可用**：core 会自己去更新源比版本，没新版就回一句"已是最新"。
                // 这与组件不同（组件没有可用直链时那颗按钮必须消失），差异写在描述符里。
                actionLabel = "在线更新",
            )
        ),
        task = ResourceTaskStatus(
            state = status?.state ?: "idle",
            // Web 侧 progress 实质只有 0/100（WebUpdateManager 只走状态机）—— 原样透传，
            // 不在这里"补齐"成字节百分比：那是编数据。
            percent = status?.progress ?: 0,
            message = status?.message.orEmpty(),
        ),
        // max_upload_bytes 是路由层拼上去的，强类型响应里没有它 → 走裸 GET 读原文
        uploadLimitBytes = api.rawGetJson("/api/web/status")
            ?.get("max_upload_bytes")?.jsonPrimitive?.longOrNull,
        fields = mapOf(
            "mode_label" to if (isOverride) "已上传（override）" else "APK 内置",
            "version" to current.ifBlank { "未知" },
            "bundled_version" to bundled.ifBlank { "未知" },
            "has_backup" to if (hasBackup) "有" else "无",
        ),
    )
}

/**
 * 上传 ZIP（multipart，字段名**必须是 `file`** —— core 的 multipart 处理只认这个名字）。
 *
 * [onProgress] 报的是**客户端 → core** 的百分比（请求体写了多少字节），与 core 内部的
 * 安装进度是两层，见文件头与 [ResourceDescriptor.upload] 的说明。
 */
private suspend fun uploadWebZip(api: ResourceApi, file: File, onProgress: (Int) -> Unit): String? =
    runRequest {
        val body = file.asRequestBody("application/zip".toMediaType())
        api.service.uploadWebAssets(
            MultipartBody.Part.createFormData("file", file.name, ProgressRequestBody(body, onProgress))
        )
    }

/**
 * 带进度的请求体：包一层 OkHttp 的 sink，边写边报百分比。
 *
 * OkHttp 自己没有上传进度回调（只有下载有），而这一层百分比是**用户唯一能看到的东西** ——
 * core 的 `installFromZip` 在请求内同步跑完，期间 app 侧拿不到任何服务端进度。
 */
private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Int) -> Unit,
) : RequestBody() {

    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = delegate.contentLength()
        val counting = object : ForwardingSink(sink) {
            private var written = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) {
                    onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

// ────────────────────────── 公共小工具 ──────────────────────────

/**
 * 跑一个"请求返回即终态"的 core 调用：成功 → null，失败 → **core 说的那句话**。
 *
 * 这就是描述符回调 `String?` 约定的落地点：只有这里 catch，[ResourceTaskController] 里
 * 就只剩一层兜底。
 */
private suspend fun runRequest(block: suspend () -> Any?): String? = try {
    block()
    null
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    describeFailure(e)
}

/**
 * 把一次请求异常翻成"能读懂的一句话"，**优先用 core 响应体里的 `error`**。
 *
 * ## 为什么用反射取 `HttpException`
 * `:app:feature-resources` 的**编译类路径上没有 retrofit**（`:app:data` 与 `:app:viewmodel`
 * 都以 `implementation` 声明它，不透传给下游模块），而本仓禁止为了一条错误分支新增依赖。
 * 这里只依赖 `HttpException.response().errorBody().string()` 这三个稳定公开方法，
 * 判定顺序与 `TrafficReportScreen.describeReportError` 一致：传输层 → core 的 error → 兜底。
 *
 * core 的 `{"error": "..."}` 里是**中文原因**（"已有组件正在安装中，请稍候"、
 * "还有实例在运行，请先全部停止后再卸载"），比"HTTP 409"有用得多，值得这几行反射。
 */
internal fun describeFailure(e: Throwable): String {
    NetworkErrorClassifier.describe(e)?.let { return it }
    errorBodyOf(e)?.let { body ->
        val msg = runCatching {
            (Json.parseToJsonElement(body) as? JsonObject)
                ?.get("error")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (!msg.isNullOrBlank()) return msg
    }
    return e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
}

/** 反射取 `HttpException.response()?.errorBody()?.string()`；不是 HttpException / 取不到时返回 null。 */
private fun errorBodyOf(e: Throwable): String? {
    if (e.javaClass.name != "retrofit2.HttpException") return null
    return runCatching {
        val response = e.javaClass.getMethod("response").invoke(e) ?: return@runCatching null
        val errorBody = response.javaClass.getMethod("errorBody").invoke(response)
            ?: return@runCatching null
        errorBody.javaClass.getMethod("string").invoke(errorBody) as? String
    }.getOrNull()
}

/** `mapOf` 的可空值版本：值为 null 的键直接不进结果（省掉一堆 `if (x != null)`）。 */
private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
