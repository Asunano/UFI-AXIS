package com.ufi_axis_core.service

import com.ufi_axis_core.util.AppSettings
import org.jupnp.UpnpServiceConfiguration
import org.jupnp.UpnpServiceImpl
import org.jupnp.binding.annotations.AnnotationLocalServiceBinder
import org.jupnp.model.meta.DeviceDetails
import org.jupnp.model.meta.DeviceIdentity
import org.jupnp.model.meta.LocalDevice
import org.jupnp.model.meta.LocalService
import org.jupnp.model.meta.ManufacturerDetails
import org.jupnp.model.meta.ModelDetails
import org.jupnp.model.types.DLNACaps
import org.jupnp.model.types.DLNADoc
import org.jupnp.model.types.UDADeviceType
import org.jupnp.model.types.UDN
import org.jupnp.android.AndroidRouter
import org.jupnp.android.AndroidUpnpServiceConfiguration
import org.jupnp.protocol.ProtocolFactory
import org.jupnp.registry.Registry
import org.jupnp.support.contentdirectory.AbstractContentDirectoryService
import org.jupnp.support.contentdirectory.ContentDirectoryErrorCode
import org.jupnp.support.contentdirectory.ContentDirectoryException
import org.jupnp.support.contentdirectory.DIDLParser
import org.jupnp.support.model.BrowseFlag
import org.jupnp.support.model.BrowseResult
import org.jupnp.support.model.DIDLContent
import org.jupnp.support.model.DIDLObject
import org.jupnp.support.model.Res
import org.jupnp.support.model.ProtocolInfo
import org.jupnp.support.model.SortCriterion
import org.jupnp.support.model.container.Container
import org.jupnp.support.model.container.StorageFolder
import org.jupnp.support.model.item.Item
import org.jupnp.support.model.item.MusicTrack
import org.jupnp.support.model.item.Photo
import org.jupnp.support.model.item.VideoItem
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLNA MediaServer（2026-10-09）。
 *
 * ## 能力与边界
 * - SSDP 通告（UDP 1900 多播）+ 设备/服务描述 HTTP 服务（jUPnP 内部 stream server，随机端口）；
 * - ContentDirectory：两级浏览 —— 根下三个分类容器（视频/音乐/图片，跨共享目录平铺）+
 *   每个共享目录一个 StorageFolder（目录树原样映射）；
 * - 媒体资源 URL：`http://<设备IP>:<core端口>/media/stream?ticket=…`（FileRoutes 的凭票
 *   流式端点，Range/seek 已由该端点支持）。DLNA 播放器不带鉴权头，只能走票据路径；
 *   票据在 browse 时**现场签发**（滑动 8 小时），过期后重新浏览目录即换新。
 *
 * ## 生命周期
 * - [start]：构造 UpnpService（含 AndroidRouter：自动持 MulticastLock/WifiLock、监听网络切换）
 *   并注册 LocalDevice → SSDP alive 通告；
 * - [stop]：shutdown（SSDP byebye）。jUPnP 不支持原地重启，目录变更走 stop→start 重建。
 *
 * ## 真机验证项（本服务启动成功 ≠ 可被发现）
 * F50 固件网络栈对 UDP 多播的处理未知。验证：同 WiFi 下电视端播放器（BubbleUPnP/nPlayer）
 * 能否发现「UFI-AXIS Media」；或 `tcpdump -i wlan0 udp port 1900` 看通告是否发出。
 */
class DlnaService(
    private val androidContext: android.content.Context,
    private val settings: AppSettings,
    /** core 的 HTTP 端口（票据端点 `/media/stream` 挂上面）。 */
    private val corePort: Int,
    /** 为一条真实路径签发流媒体票据（复用 FileRoutes 的 MediaTicketStore，8 小时滑动过期）。 */
    private val issueTicket: (File) -> String,
) {
    companion object {
        const val KEY_ENABLED = "dlna_enabled"
        const val KEY_DIRS = "dlna_dirs_json"

        private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "3gp", "flv")
        private val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "opus", "wma")
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")

        /** 单个分类容器平铺的文件数上限：电视端列表一次渲染几千条会卡，且 browse 响应也是 XML。 */
        private const val MAX_FILES_PER_DIR = 1000
    }

    private val tag = "DlnaService"

    private var upnp: UpnpServiceImpl? = null
    private val started = AtomicBoolean(false)
    val isRunning: Boolean get() = started.get()

    // ─── 配置（SharedPreferences 直存，AppSettings.getRawString/setRawString） ───

    fun isEnabled(): Boolean = settings.getRawString(KEY_ENABLED) == "true"

    fun setEnabled(enabled: Boolean) {
        settings.setRawString(KEY_ENABLED, enabled.toString())
        if (enabled) start() else stop()
    }

    fun dirs(): List<String> =
        settings.getRawString(KEY_DIRS)
            ?.removePrefix("[")?.removeSuffix("]")
            ?.split(',')
            ?.map { it.trim().trim('"').trimEnd('/') }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun setDirs(dirs: List<String>) {
        val cleaned = dirs.map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        val json = "[" + cleaned.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" } + "]"
        settings.setRawString(KEY_DIRS, json)
        if (started.get()) { stop(); start() }
    }

    /** 未配置目录时禁止开启（web/app 设置页据此置灰开关）。 */
    fun readyToEnable(): Boolean = dirs().isNotEmpty()

    // ─── 启停 ──────────────────────────────────────────────

    @Synchronized
    fun start() {
        if (started.get()) return
        if (dirs().isEmpty()) {
            com.ufi_axis_core.util.AppLogger.w(tag, "start skipped: no shared dirs configured")
            return
        }
        try {
            val service = UfiUpnpImpl(createConfiguration(), androidContext)
            upnp = service
            // jUPnP 3.0.5 的 UpnpServiceImpl 构造器只赋字段不启动（字节码实锤）：startup()
            // 只由 OSGi @Activate 或官方 AndroidUpnpServiceImpl.onCreate 显式触发。
            // 我们不走 Service/OSGi，必须手动调，否则 registry 为 null，addDevice 直接 NPE。
            service.startup()
            service.registry.addDevice(createDevice())
            started.set(true)
            com.ufi_axis_core.util.AppLogger.i(tag, "DLNA MediaServer started, dirs=${dirs().size}")
        } catch (e: Exception) {
            com.ufi_axis_core.util.AppLogger.w(tag, "DLNA start failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { upnp?.shutdown() }
            upnp = null
        }
    }

    @Synchronized
    fun stop() {
        val s = upnp
        upnp = null
        started.set(false)
        if (s == null) return
        try {
            s.shutdown()
            com.ufi_axis_core.util.AppLogger.i(tag, "DLNA MediaServer stopped")
        } catch (e: Exception) {
            com.ufi_axis_core.util.AppLogger.w(tag, "DLNA stop error: ${e.message}")
        }
    }

    /**
     * Android 配置：jUPnP 的 Android 网络层（MulticastLock / 网络切换监听）。
     * 描述/事件流服务端口 0 = 系统分配（与 core 的 8088 互不干扰 —— 播放 URL 仍指向 8088）。
     * 第二个参数是多播响应监听端口，0 = 系统分配；**不能给 <1024 的值**（特权端口，非 root
     * 绑定直接失败 —— r24 实机曾误传 10）。
     */
    private fun createConfiguration(): AndroidUpnpServiceConfiguration =
        AndroidUpnpServiceConfiguration(0, 0)

    private fun deviceIp(): String =
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"

    private fun createDevice(): LocalDevice {
        // UDN 用设备身份派生的稳定 UUID：重装/重启不变，客户端书签不失效
        val udn = UDN(UUID.nameUUIDFromBytes("ufi-axis-dlna-${settings.deviceId}".toByteArray()))
        val details = DeviceDetails(
            "UFI-AXIS Media",
            ManufacturerDetails("UFI-AXIS"),
            ModelDetails("UFI-AXIS Core", "DLNA Media Server", "1.0"),
            arrayOf(DLNADoc("M-DMS", DLNADoc.Version.V1_5)),
            DLNACaps(arrayOf("av-upload", "image-upload", "audio-upload"))
        )
        val contentDirectory: LocalService<UfiContentDirectory> =
            AnnotationLocalServiceBinder().read(UfiContentDirectory::class.java) as
                LocalService<UfiContentDirectory>
        contentDirectory.manager = InstanceServiceManager(contentDirectory, UfiContentDirectory(this))
        val connectionManager: LocalService<org.jupnp.support.connectionmanager.ConnectionManagerService> =
            AnnotationLocalServiceBinder().read(org.jupnp.support.connectionmanager.ConnectionManagerService::class.java)
                as LocalService<org.jupnp.support.connectionmanager.ConnectionManagerService>
        connectionManager.manager = InstanceServiceManager(
            connectionManager, org.jupnp.support.connectionmanager.ConnectionManagerService()
        )
        return LocalDevice(
            DeviceIdentity(udn),
            UDADeviceType("MediaServer", 1),
            details,
            arrayOf(),  // 图标：电视端一般用列表，不阻塞上线
            arrayOf(contentDirectory, connectionManager)
        )
    }

    // ─── ContentDirectory 枚举（UfiContentDirectory 委托回来） ───────────────

    fun classify(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in VIDEO_EXT -> "video"
            ext in AUDIO_EXT -> "audio"
            ext in IMAGE_EXT -> "image"
            else -> null
        }
    }

    private fun mimeOf(file: File): String = when (classify(file.name)) {
        "video" -> when (file.extension.lowercase()) {
            "mkv" -> "video/x-matroska"; "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"
            "webm" -> "video/webm"; "ts" -> "video/mp2t"; "3gp" -> "video/3gpp"
            "flv" -> "video/x-flv"; else -> "video/mp4"
        }
        "audio" -> when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"
            else -> "audio/mpeg"
        }
        "image" -> when (file.extension.lowercase()) {
            "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "bmp" -> "image/bmp"; "heic" -> "image/heic"; else -> "image/jpeg"
        }
        else -> "application/octet-stream"
    }

    fun resFor(file: File): Res {
        // DLNA.ORG_OP=01 声明「支持字节 Range seek」：第四段写 * 等于 OP=00（都不支持），
        // 按规范实现的播放器会禁用拖进度条 —— 2026-10-10 实机「拖一下卡住回 0」的主因之一。
        // 服务端 /media/stream 的 Range 处理是完善的（206 + Content-Range + 后缀式）。
        val pi = ProtocolInfo("http-get:*:${mimeOf(file)}:DLNA.ORG_OP=01;DLNA.ORG_CI=0")
        val res = Res(pi, file.length(), "http://${deviceIp()}:$corePort/media/stream?ticket=" +
            java.net.URLEncoder.encode(issueTicket(file), "UTF-8"))
        // duration 元数据：播放器把「拖到 50%」换算成字节偏移需要它。现场用
        // MediaMetadataRetriever 探测（MMR 失败=无内嵌元数据/格式不支持 → 留空不阻塞 browse）。
        runCatching {
            android.media.MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(file.absolutePath)
                val ms = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: return@use
                val totalSec = ms / 1000
                if (totalSec > 0) {
                    res.duration = "%d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
                }
            }
        }
        return res
    }

    fun browseRoot(): DIDLContent {
        val c = DIDLContent()
        c.addContainer(Container("cat:video", "0", "视频", "UFI-AXIS", DIDLObject.Class("object.container.storageFolder"), childCount("video")))
        c.addContainer(Container("cat:audio", "0", "音乐", "UFI-AXIS", DIDLObject.Class("object.container.storageFolder"), childCount("audio")))
        c.addContainer(Container("cat:image", "0", "图片", "UFI-AXIS", DIDLObject.Class("object.container.storageFolder"), childCount("image")))
        for (d in dirs()) {
            val f = File(d)
            if (!f.isDirectory) continue
            c.addContainer(storageFolder(f))
        }
        return c
    }

    fun browseCategory(kind: String): DIDLContent {
        val c = DIDLContent()
        var count = 0
        for (d in dirs()) {
            val files = File(d).listFiles { f -> f.isFile && classify(f.name) == kind } ?: continue
            for (f in files.sortedBy { it.name }) {
                if (count >= MAX_FILES_PER_DIR) return c
                c.addItem(itemFor(f, "cat:$kind"))
                count++
            }
        }
        return c
    }

    fun browseDir(path: String): DIDLContent {
        val c = DIDLContent()
        val dir = File(path)
        if (!dir.isDirectory) return c
        val entries = dir.listFiles() ?: return c
        for (sub in entries.filter { it.isDirectory }.sortedBy { it.name }) {
            c.addContainer(storageFolder(sub))
        }
        for (f in entries.filter { it.isFile && classify(it.name) != null }.sortedBy { it.name }) {
            c.addItem(itemFor(f, "dir:${dir.absolutePath}"))
        }
        return c
    }

    private fun storageFolder(f: File): StorageFolder =
        StorageFolder(
            "dir:${f.absolutePath}", if (f.parentFile != null && dirs().none { it == f.absolutePath }) "dir:${f.parentFile.absolutePath}" else "0",
            f.name, "object.container.storageFolder",
            f.listFiles()?.size ?: 0, storageUsed(f)
        )

    private fun childCount(kind: String): Int =
        dirs().sumOf { d -> File(d).listFiles { f -> f.isFile && classify(f.name) == kind }?.size ?: 0 }

    private fun storageUsed(dir: File): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    private fun itemFor(f: File, parentId: String): Item {
        val id = "file:${f.absolutePath}"
        val res = resFor(f)
        return when (classify(f.name)) {
            "video" -> VideoItem(id, parentId, f.name, "UFI-AXIS", res)
            "audio" -> MusicTrack(id, parentId, f.nameWithoutExtension, "UFI-AXIS", "", "", res)
            else -> Photo(id, parentId, f.name, "UFI-AXIS", "", res)
        }
    }
}

/**
 * ServiceManager：固定返回我们持有的实例（[org.jupnp.model.DefaultServiceManager] 默认
 * 反射 newInstance，构造签名不匹配时炸 —— 覆写 createServiceInstance 是官方注入点）。
 */
private class InstanceServiceManager<T>(service: LocalService<T>, private val impl: T) :
    org.jupnp.model.DefaultServiceManager<T>(service) {
    override fun createServiceInstance(): T = impl
}

/**
 * UpnpServiceImpl 子类：覆写 createRouter 注入 AndroidRouter。
 * 这是 jUPnP 官方 Android 集成模式（AndroidUpnpServiceImpl 的匿名子类就是这么做的）——
 * 我们不走 Android Service 组件（core 已有自己的前台服务），直接用普通对象承载。
 */
private class UfiUpnpImpl(
    configuration: UpnpServiceConfiguration,
    private val context: android.content.Context
) : UpnpServiceImpl(configuration) {
    override fun createRouter(protocolFactory: ProtocolFactory, registry: Registry): AndroidRouter =
        AndroidRouter(configuration, protocolFactory, context)
}

/**
 * ContentDirectory 实现。@UpnpService 注解在 [AbstractContentDirectoryService] 上且
 * @Inherited —— 子类经 [AnnotationLocalServiceBinder]（内部走 getMethods，公有方法含继承）
 * 即可获得完整 Browse/GetSearchCapabilities/GetSortCapabilities/GetSystemUpdateID 绑定。
 */
class UfiContentDirectory(private val svc: DlnaService) : AbstractContentDirectoryService(
    emptyList(),
    listOf("dc:title", "upnp:class", "res@size")
) {
    @Throws(ContentDirectoryException::class)
    override fun browse(
        objectID: String,
        flag: BrowseFlag,
        filter: String,
        firstResult: Long,
        maxResults: Long,
        orderBy: Array<SortCriterion>
    ): BrowseResult {
        val content = when {
            objectID.isBlank() || objectID == "0" -> svc.browseRoot()
            objectID.startsWith("cat:") -> svc.browseCategory(objectID.removePrefix("cat:"))
            objectID.startsWith("dir:") -> svc.browseDir(objectID.removePrefix("dir:"))
            else -> DIDLContent()
        }
        val n = (content.containers.size + content.items.size).toLong()
        return try {
            BrowseResult(DIDLParser().generate(content), n, n)
        } catch (e: Exception) {
            throw ContentDirectoryException(ContentDirectoryErrorCode.CANNOT_PROCESS, e.message)
        }
    }
}
