package com.ufi_axis.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import com.ufi_axis.data.model.*
import com.ufi_axis.data.monitor.MonitorPrefsPayload
import com.ufi_axis.data.monitor.MonitorPrefsUpdateResponse
import com.ufi_axis.data.notification.NotificationConfigDto
import com.ufi_axis.data.notification.NotificationConfigUpdateResponse
import okhttp3.ResponseBody
import retrofit2.Response
import okhttp3.MultipartBody
import retrofit2.http.*

interface UfiAxisApi {

    // ========== Health ==========
    @GET("health")
    suspend fun getHealth(): HealthResponse

    // ========== Device ==========
    @GET("api/device/info")
    suspend fun getDeviceInfo(): DeviceInfoResponse

    @GET("api/device/identity")
    suspend fun getDeviceIdentity(): Map<String, String>

    @GET("api/device/version")
    suspend fun getDeviceVersion(): DeviceVersionResponse

    /**
     * EPS 承载 QoS（QCI / 上下行 AMBR，2026-09-22）。
     *
     * core 侧走 `AT+CGEQOSRDP`，5 分钟缓存。**不要放进仪表盘的 10s 轮询** ——
     * AT 通道全局互斥，抢锁超时会把整张仪表盘一起拖住。进页面静默拉一次就够
     * （`DashboardModule.loadDeviceQosSilently`，与固件版本同一手法）。
     *
     * AT 不可用时仍回 200 + `available = false`，不抛异常。
     */
    @GET("api/device/qos")
    suspend fun getDeviceQos(): DeviceQosResponse

    @GET("api/device/model")
    suspend fun getDeviceModel(): DeviceModel

    @GET("api/device/magisk")
    suspend fun getMagiskStatus(): MagiskStatus

    // ========== System ==========
    @GET("api/system/cpu")
    suspend fun getCpuInfo(): CpuInfo

    @GET("api/system/cpu/history")
    suspend fun getCpuHistory(@Query("hours") hours: Int = 24): CpuHistoryResponse

    @GET("api/system/memory")
    suspend fun getMemoryInfo(): MemoryInfo

    @GET("api/system/battery")
    suspend fun getBatteryInfo(): BatteryInfo

    @GET("api/system/storage")
    suspend fun getStorageInfo(): StorageInfo

    @GET("api/system/uptime")
    suspend fun getUptime(): UptimeInfo

    /**
     * Core 进程首次启动时间（绝对 epoch ms，UTC）。用于监控页"自定义时间范围"对话框 minDateMs 下限。
     * 2026-08-08 12:09 新增：返回 Long 不需要额外 model（retrofit 自动解析 JSON 数字）。
     */
    @GET("api/system/startup-time")
    suspend fun getStartupTime(): Long

    // ========== Traffic ==========
    @GET("api/traffic/realtime")
    suspend fun getTrafficRealtime(): TrafficRealtime

    @GET("api/traffic/history")
    suspend fun getTrafficHistory(@Query("hours") hours: Int = 24): TrafficHistoryResponse

    @GET("api/traffic/summary")
    suspend fun getTrafficSummary(): TrafficSummary

    /**
     * 分段流量用量（「流量管理 → 流量历史」卡片）。
     *
     * 与 [getTrafficHistory] 的分工：后者回**原始速率采样**（近 N 小时逐条），
     * 本端点回**按段聚合后的完整桶序列** + 中文文案，见 [TrafficUsageResponse]。
     *
     * @param range `day` / `week` / `month` / `year`（core 对非法值回落到 day，不回 400）
     * @param anchor 锚点时刻（epoch ms）；null = core 按"当前所在的那一段"取
     */
    @GET("api/traffic/usage")
    suspend fun getTrafficUsage(
        @Query("range") range: String,
        @Query("anchor") anchor: Long? = null
    ): TrafficUsageResponse

    // ========== Media Center（媒体中心，2026-09-16）==========
    //
    // core 侧查系统媒体库（MediaStore）。**播放 / 查看仍走 `/api/files/stream?path=`**，
    // 这里不出现第二条取字节流的路径；缩略图是唯一的例外（它是 core 生成的 JPEG，不是原文件）。

    /** 三类媒体各自的授权状态 + 当前扫描目录。某类未授权时界面要显示引导，不许显示空列表。 */
    @GET("api/media/status")
    suspend fun getMediaStatus(): MediaStatusResponse

    /**
     * 列某一类媒体（分页）。
     *
     * @param type [MEDIA_TYPE_VIDEO] / [MEDIA_TYPE_AUDIO] / [MEDIA_TYPE_IMAGE]
     * @param sort `date`（默认）/ `name` / `size`
     * @param order `desc`（默认）/ `asc`
     * @param album 只列这个专辑（**仅音频**，取 [getMediaGroups] 回的 key 原值）
     * @param artist 只列这个歌手（**仅音频**）
     * @param dir 只列这个目录（**仅音频**，目录绝对路径）
     *
     * 后三个是 2026-09-20 为音频分组回查加的，与 core 侧的扫描目录过滤是 **AND** 关系
     * （超出扫描范围的专辑筛不出东西是预期行为）。传空串等于没传 —— core 按"参数缺失"处理，
     * 会回整库，所以分组里 key 为空串的那一组不能走这条路回查。
     *
     * ## 后三个必须传**已编码**的值（`encoded = true`，2026-09-21 修"专辑里没有歌"）
     * Retrofit 默认会帮忙编码，但它底层的 OkHttp 在 query 里**不编码 `+`** ——
     * 而 Ktor 解 query 时按 form 语义把 `+` 当空格。于是专辑名 `万岁2001 新曲+精选`
     * 传到 core 变成 `万岁2001 新曲 精选`，一条曲目都匹配不上。
     * 现在由调用方用 `encodeUriComponent` 编好（它把 `+` 编成 `%2B`、空格编成 `%20`），
     * 这里只负责原样带上。调用点见 `MediaModule.groupFilters`。
     */
    @GET("api/media/list")
    suspend fun getMediaList(
        @Query("type") type: String,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("album", encoded = true) album: String? = null,
        @Query("artist", encoded = true) artist: String? = null,
        @Query("dir", encoded = true) dir: String? = null
    ): MediaListResponse

    /**
     * 按专辑 / 歌手 / 文件夹聚合音频（`type` 只支持 [MEDIA_TYPE_AUDIO]）。
     *
     * 与 [getMediaList] 的分工：这个回"有哪些组"（不分页，core 一次算完），
     * 点进某一组再用 [getMediaList] 的 album / artist / dir 回查组内曲目。
     *
     * @param by [MEDIA_GROUP_ALBUM] / [MEDIA_GROUP_ARTIST] / [MEDIA_GROUP_FOLDER]
     */
    @GET("api/media/groups")
    suspend fun getMediaGroups(
        @Query("type") type: String,
        @Query("by") by: String
    ): MediaGroupsResponse

    /**
     * 按目录列一层（媒体库的文件夹视图）：子目录 + 这一层的媒体文件。
     *
     * 与 [getMediaList] 的分工：那个是整库平铺分页，这个是"这一层有什么"（不分页）。
     * [path] 留空时由 core 决定起点：只配了一个扫描目录就直接进那一个，配了多个则回
     * `roots` 让用户先选。
     */
    @GET("api/media/browse")
    suspend fun browseMedia(
        @Query("type") type: String,
        @Query("path") path: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null
    ): MediaBrowseResponse

    /** 读某一类的扫描目录。 */
    @GET("api/media/config")
    suspend fun getMediaConfig(@Query("type") type: String): MediaConfigResponse
    /**
     * 写某一类的扫描目录。空数组 = 这一类不限目录（列整个媒体库里的该类型）。
     *
     * 按类型分开存（2026-09-16 媒体中心拆成三页）：视频页只改视频那一份，
     * 所以三页各有可写入口也不会互相覆盖。
     */
    @PUT("api/media/config")
    suspend fun putMediaConfig(
        @Query("type") type: String,
        @Body body: MediaDirsRequest
    ): MediaConfigResponse

    /**
     * 请系统重新收录某一类的目录（`dirs` 为空时用该类型已配置的目录）。
     * 只提交该类型的文件；收录是异步的：成功返回不代表 [getMediaList] 立刻能查到新文件。
     */
    @POST("api/media/rescan")
    suspend fun rescanMedia(
        @Query("type") type: String,
        @Body body: MediaDirsRequest
    ): MediaRescanResponse

    /**
     * 旁挂歌词（同名 `.lrc` / `.txt`）。没有歌词时回 `found = false`（不是 404）。
     *
     * 封面走 `api/media/cover?id=`（原始内嵌图，播放页用），**不在这里声明** ——
     * 那是给 Coil 直接吃的图片 URL，不经 Retrofit。
     */
    @GET("api/media/lyrics")
    suspend fun getMediaLyrics(@Query("id") id: Long): MediaLyricsResponse

    /**
     * 某个视频可用的**外挂字幕**（`.srt` / `.ass` / `.vtt` …）。
     *
     * [scope]：
     *  · 省略或 `matched` —— 只回按文件名判定属于这个视频的，用于"打开就自动挂上字幕"；
     *  · `folder` —— 回同目录**全部**字幕，用于"手动选字幕文件"的列表
     *    （现实里字幕名常和视频名对不上：压制组命名、单独下载的字幕包）。
     *
     * 匹配规则写在 core（`subtitleBelongsTo`），客户端不复制一份 —— 两个播放入口
     * 各写一遍必然分叉。
     *
     * 字幕**内容**不在这里取：见 `api/media/subtitle?path=`，那条会把 GB18030/Big5
     * 统一转成 UTF-8（播放器只认 UTF-8），所以必须走 core 而不是直接拉 `/files/stream`。
     */
    @GET("api/media/subtitles")
    suspend fun getMediaSubtitles(
        @Query("path") path: String,
        @Query("scope") scope: String? = null
    ): MediaSubtitlesResponse

    /**
     * 清空**设备侧**的缩略图缓存。
     *
     * ## 为什么必须有这一条
     * 缩略图一共三层缓存，缺了它就有一层永远清不掉：
     *  1. 图片加载库（磁盘 + 内存，按 URL 命中）；
     *  2. App 自己抽帧的成果（`media-thumbs/`）；
     *  3. **设备上客户端回传的成果**（core 的 `filesDir/thumbs/`）← 本条清的是这层。
     *
     * `/thumbnail` 是"缓存命中就直接返回"，而 URL 只含 (type, id) 不含内容指纹。
     * 于是一张算错的图（典型：抽到黑场）会一直被原样发下去 ——
     * 只清前两层的话，再次请求又被设备上的旧图命中，表现就是"怎么清都还是那张黑图"。
     *
     * [type] 省略清全部；给了只清那一类（换抽帧策略时通常只需重算 video）。
     */
    @DELETE("api/media/thumbnail-cache")
    suspend fun clearMediaThumbnailCache(
        @Query("type") type: String? = null
    ): JsonElement

    /**
     * 单首音频的标签（曲名 / 艺术家 / 专辑 / 时长）。
     *
     * `/list` 里已经带了同名字段（来自系统扫描），这一条是**缺失时的补强**：
     * core 会直接对文件再读一次标签。播放页进页面就能拉到正确的歌名，
     * 不必等播放器解出容器元数据才更新。
     */
    @GET("api/media/tags")
    suspend fun getMediaTags(@Query("id") id: Long): MediaTagsResponse

    /**
     * 换一张免鉴权播放票据（`/media/stream?ticket=…`）。
     *
     * 手机端给「设备解不出画面」的视频抽缩略图时要用：`MediaMetadataRetriever` 自己发 HTTP
     * 请求、加不了签名头，而签名的 nonce 是一次性的，多个 Range 请求必然从第二个起被拒。
     */
    @POST("api/files/stream-ticket")
    suspend fun createStreamTicket(@Body body: StreamTicketRequest): StreamTicketResponse

    // ========== 音频歌单（2026-09-21）==========
    //
    // 歌单存在 core，app 与 web 看同一份。曲目以**真实路径**为标识（MediaStore id 重扫会变，
    // 拿它存歌单等于存一个会失效的引用），所以写操作传的都是 paths。

    /** 全部歌单（不含曲目明细）。 */
    @GET("api/playlists")
    suspend fun getPlaylists(): PlaylistsResponse

    /**
     * 某个歌单的曲目。
     *
     * 回的 item 与 [getMediaList] 的完全同构（多一个 `missing`），所以曲目列表组件可以直接复用。
     * **顺序就是播放顺序** —— 不要再按音乐页的排序偏好重排；已失效的条目照样在列表里
     * （`missing = true`，`id = 0`），画占位而不是隐藏。
     */
    @GET("api/playlists/{id}/items")
    suspend fun getPlaylistItems(@Path("id") id: String): PlaylistItemsResponse

    /** 新建歌单。id 由 core 生成 —— 客户端传 id 不被接受。 */
    @POST("api/playlists")
    suspend fun createPlaylist(@Body body: PlaylistNameRequest): PlaylistCreateResponse

    /** 重命名。同名（忽略大小写）会被 core 以 409 `ALREADY_EXISTS` 拒绝。 */
    @PUT("api/playlists/{id}")
    suspend fun renamePlaylist(
        @Path("id") id: String,
        @Body body: PlaylistNameRequest
    ): JsonElement

    @DELETE("api/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: String): JsonElement

    /**
     * 加歌。不在媒体库里的路径会被 core 跳过并计入 `skipped`（不会变成一条坏数据）。
     * `position` 省略即追加到尾部。
     */
    @POST("api/playlists/{id}/items")
    suspend fun addPlaylistItems(
        @Path("id") id: String,
        @Body body: PlaylistPathsRequest
    ): PlaylistAddResponse

    /**
     * 移出曲目。
     *
     * 走**重复 query 参数**（`?path=a&path=b`）而不是 DELETE 带 body：Retrofit 的 `@DELETE`
     * 不允许 `@Body`（要加就得换成 `@HTTP(hasBody=true)`），而带 body 的 DELETE 在部分代理上
     * 还会被丢掉。core 两种写法都收（web 侧用的是 body）。
     *
     * `paths` 必须是**已编码**的值（`encoded = true`），理由同 [getMediaList] 的 album/artist/dir：
     * OkHttp 在 query 里不编码 `+`，而 Ktor 解 query 时把 `+` 当空格 —— 路径含 `+` 的曲目
     * （`C++.mp3`、`新曲+精选/…`）会因此永远移不掉，只回「已移出 0 首」。
     * 调用方用 `encodeUriComponent` 编好再传。
     */
    @DELETE("api/playlists/{id}/items")
    suspend fun removePlaylistItems(
        @Path("id") id: String,
        @Query("path", encoded = true) paths: List<String>
    ): PlaylistRemoveResponse

    /**
     * 整表重排：`paths` 是新的完整顺序。
     *
     * 语义是「只调顺序，不增不删」—— 不在歌单里的 path 被忽略，没提到的条目留在尾部。
     */
    @PUT("api/playlists/{id}/items")
    suspend fun reorderPlaylistItems(
        @Path("id") id: String,
        @Body body: PlaylistPathsRequest
    ): JsonElement

    // ========== Weather（2026-09-17，core 代理 Open-Meteo）==========

    /**
     * 当前天气。默认用设备上保存的坐标；[lat]/[lon] 只在「设置页里选了城市、想先看一眼」
     * 时传（保存前的预览），正常显示不要传。
     */
    @GET("api/weather")
    suspend fun getWeatherNow(
        @Query("lat") lat: Double? = null,
        @Query("lon") lon: Double? = null,
        @Query("city") city: String? = null
    ): WeatherNowResponse

    /** 城市搜索（core 侧缓存 1 天）。 */
    @GET("api/weather/search")
    suspend fun searchWeatherCity(
        @Query("name") name: String,
        @Query("lang") lang: String = "zh"
    ): WeatherSearchResponse

    @GET("api/weather/config")
    suspend fun getWeatherConfig(): WeatherConfigResponse

    /** 字段级合并：只传要改的字段。 */
    @PUT("api/weather/config")
    suspend fun updateWeatherConfig(@Body body: WeatherConfigRequest): WeatherConfigUpdateResponse

    // ========== 今日诗词（2026-09-18，core 代理 jinrishici v2）==========

    /**
     * 当前推荐的一句诗。标签匹配由上游按设备 IP 自动完成（季节/天气/时辰/地理），
     * 命中的标签在 `match_tags` 里回传。
     *
     * @param refresh 传 `"1"` 绕过 core 的本地缓存；上游对同一 token 有约 10 分钟的
     *   预生成缓存，所以短时间内仍可能是同一句 —— 这是它的机制，不是失败。
     */
    @GET("api/poetry")
    suspend fun getPoetry(@Query("refresh") refresh: String? = null): PoetryResponse

    @GET("api/poetry/config")
    suspend fun getPoetryConfig(): PoetryConfigResponse

    /** 字段级合并：只传要改的字段。 */
    @PUT("api/poetry/config")
    suspend fun updatePoetryConfig(@Body body: PoetryConfigRequest): PoetryConfigUpdateResponse

    // ========== 出网国家/地区（2026-09-18，检测在 core）==========

    /**
     * 设备的出网国家/地区。**app 不再自己做地理检测** —— 判据是出网 IP 的归属，
     * 而设备才是出网点；每端各测一次只会得到几份可能不一致的结果。
     *
     * core 在自己启动时已经测过一次并落盘，这里通常是直接读那份结果（`source=cache`）；
     * 结果缺失或超过 7 天时 core 会顺手重测（`source=fresh`），故本调用偶尔会慢几秒。
     */
    @GET("api/geo")
    suspend fun getGeo(): GeoResponse

    /**
     * 把手机端抽好的缩略图交给 core 缓存（raw JPEG 字节）。
     *
     * core 侧 `/api/media/thumbnail` 的第一级就是这份缓存，所以传一次之后
     * **Web 端和其它客户端也能看到**这张缩略图 —— 这正是"抽完要回传"而不是只存本地的理由。
     */
    @PUT("api/media/thumbnail")
    suspend fun putMediaThumbnail(
        @Query("type") type: String,
        @Query("id") id: Long,
        @Body body: okhttp3.RequestBody
    ): MediaThumbUploadResponse

    // ========== Network ==========
    @GET("api/network/signal")
    suspend fun getSignalInfo(): SignalInfo

    @GET("api/network/signal/history")
    suspend fun getSignalHistory(@Query("hours") hours: Int = 24): SignalHistoryResponse

    @GET("api/network/status")
    suspend fun getNetworkStatus(): NetworkStatusResponse

    @POST("api/network/data")
    suspend fun setMobileData(@Body body: Map<String, Boolean>): EnabledResponse

    @POST("api/network/airplane")
    suspend fun setAirplaneMode(@Body body: Map<String, Boolean>): AirplaneResponse

    @POST("api/network/band")
    suspend fun setBandLock(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // 查询当前频段锁定状态
    @GET("api/network/band-status")
    suspend fun getBandStatus(): BandStatusResponse

    @POST("api/network/mode")
    suspend fun setNetworkMode(@Body body: ModeRequest): ModeResponse

    // ========== SIM / SMS ==========
    @POST("api/sms/send")
    suspend fun sendSms(@Body body: SmsSendRequest): SmsSendResponse

    @GET("api/sms/list")
    suspend fun getSmsList(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("phone") phone: String? = null
    ): SmsListResponse

    @GET("api/sms/contacts")
    suspend fun getSmsContacts(): SmsContactListResponse

    /**
     * 设备短信总数 / 未读数。**全局口径的唯一来源** —— 见 [SmsCountResponse] 里为什么
     * 不能用 `/api/sms/list` 的 `total` 或联系人 `unread` 求和代替。
     */
    @GET("api/sms/count")
    suspend fun getSmsCount(): SmsCountResponse

    // ========== Shell ==========
    @POST("api/shell/exec")
    suspend fun shellExec(@Body body: ShellExecRequest): ShellExecResponse

    // ========== 终端命令历史（core 存，app / web 共享） ==========
    /** @param channel `shell` / `at`，不传则两个通道混排 */
    @GET("api/console/history")
    suspend fun getConsoleHistory(
        @Query("channel") channel: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("cursor_ts") cursorTs: Long? = null,
        @Query("cursor_id") cursorId: Long? = null
    ): ConsoleHistoryResponse

    @DELETE("api/console/history")
    suspend fun clearConsoleHistory(@Query("channel") channel: String? = null): SuccessResponse

    @DELETE("api/console/history/{id}")
    suspend fun deleteConsoleHistoryItem(@Path("id") id: Long): SuccessResponse

    /** 旧版本地历史的一次性导入（升级后只调一次，见 ToolsModule 的迁移逻辑）。 */
    @POST("api/console/history/import")
    suspend fun importConsoleHistory(
        @Body body: ConsoleHistoryImportRequest
    ): ConsoleHistoryImportResponse

    // ========== AT ==========
    @POST("api/at/command")
    suspend fun sendAtCommand(@Body body: AtCommandRequest): AtCommandResponse

    @GET("api/at/status")
    suspend fun getAtStatus(): AtStatusResponse

    @GET("api/at/platform")
    suspend fun getPlatformInfo(): PlatformInfo

    // ========== Alerts ==========
    @GET("api/alerts/config")
    suspend fun getAlertConfig(): AlertConfig

    @PUT("api/alerts/config")
    suspend fun updateAlertConfig(@Body body: AlertConfig): AlertConfigUpdateResponse

    /**
     * 告警列表。core 把 `limit` 钳制在 1..200（[com.ufi_axis_core.contract.Alerts.ListQuery]），
     * 传更大的值不会报错但只返回 200 条 —— 要取全量必须用 `cursor` 逐页拉（T12）。
     *
     * `cursor` 是响应里的 `nextCursor`（base64 的 `ts.id`），向更早的记录翻页；
     * `level`/`type`/`unread` 为服务端过滤，响应的 `counts` 与过滤条件一致。
     */
    @GET("api/alerts/list")
    suspend fun getAlertList(
        @Query("limit") limit: Int = 20,
        @Query("start_time") startTimeMs: Long? = null,
        @Query("end_time") endTimeMs: Long? = null,
        @Query("cursor") cursor: String? = null,
        @Query("level") level: String? = null,
        @Query("type") type: String? = null,
        @Query("unread") unreadOnly: Boolean? = null
    ): AlertListResponse

    @POST("api/alerts/ack")
    suspend fun ackAlert(@Body body: AckRequest): SuccessResponse

    /** 批量已读（事件中心「全部已读」/分类已读/应用内 banner 关闭）。对应 Core 的 POST /api/alerts/ack-all。 */
    @POST("api/alerts/ack-all")
    suspend fun ackAllAlerts(@Body body: AckAllRequest): SuccessResponse

    /**
     * 只确认**已恢复**的告警（`POST /api/alerts/ack-resolved`）。
     *
     * 与 [ackAllAlerts] 的区别：ack-all 是「无脑清空未读」，本端点只动「问题已经消失」的那些，
     * 仍在持续的告警保持未读 —— 所以它是「清理历史噪音」而不是「掩盖当前故障」。
     *
     * body 传 `{"minAgeSec": 3600}` = 只确认恢复超过 1 小时的；传 `emptyMap()` = 不限时间。
     * core 用 `receiveJsonObject()` 读体，**必须带 JSON 体**（`{}` 也行），不能空体。
     */
    @POST("api/alerts/ack-resolved")
    suspend fun ackResolvedAlerts(@Body body: Map<String, Long>): AlertAckResolvedResponse


    /** 删除单条告警（事件中心「更多操作 → 删除」；对应 Core 的 POST /api/alerts/delete） */
    @POST("api/alerts/delete")
    suspend fun deleteAlert(@Body body: AlertDeleteRequest): SuccessResponse

    // ========== WiFi ==========
    @POST("api/wifi/ssid")
    suspend fun setWifiSsid(@Body body: WifiSsidRequest): SuccessResponse

    @POST("api/wifi/password")
    suspend fun setWifiPassword(@Body body: WifiPasswordRequest): SuccessResponse

    @POST("api/wifi/config")
    suspend fun setWifiConfig(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    /**
     * WiFi 频段切换（`POST /api/wifi/band`，body `{ "chip": "chip1" | "chip2" }`）。
     *
     * **和 `/config` 是两条路**：频段不能塞进 `setWifiConfig` 的 `chip_index` —— 那一项落到
     * 设备的 `setAccessPointInfo → ChipIndex`，**设备不认**（用户实测「改了没反应」）。
     * 真正换频段的设备命令是 `goformId=switchWiFiChip&ChipEnum=chip1|chip2&GuestEnable=0`
     * （2026-09-22 真机抓包，chip1 = 2.4G、chip2 = 5G），core 把它包成本端点。
     *
     * 取值只认 `chip1` / `chip2`：`"0"`/`"1"`（读侧编码）与 `"2.4G"`/`"5G"`（界面文案）都不是
     * 传输值，core 侧 profile 的 validate 会直接回 400 + 原因。
     *
     * **副作用**：这条命令等于「在该频段上启用 WiFi」—— 会重启 WiFi 模块，正通过 WiFi 连着的
     * 客户端（包括本机）会掉线，且原本关着的 WiFi 会被打开。调用前必须让用户确认。
     */
    @POST("api/wifi/band")
    suspend fun setWifiBand(@Body body: Map<String, String>): SuccessResponse

    @POST("api/wifi/power")
    suspend fun setWifiPower(@Body body: Map<String, Int>): SuccessResponse

    @GET("api/wifi/settings")
    suspend fun getWifiSettings(): WifiSettingsResponse

    @GET("api/wifi/module-info")
    suspend fun getWifiModuleInfo(): kotlinx.serialization.json.JsonElement

    @GET("api/wifi/clients")
    suspend fun getWifiClients(): WifiClientsResponse

    /**
     * WiFi 接入控制名单（拉黑）。
     *
     * 设备侧只有「整表替换」一条命令，读-改-写全部收在 core：这里**只发单台设备的 mac/name**，
     * 不要自己拼完整名单。三个写端点回的都是 core 写完**回读设备**的真实名单，直接覆盖本地状态。
     * 重复拉黑 / 解除不在名单里的设备都是幂等成功。
     */
    @GET("api/wifi/acl")
    suspend fun getWifiAcl(): WifiAclResponse

    /** body: `mac`（必填，`xx:xx:...` 六段）+ `name`（可选，一般传 clients 里的 hostname）。 */
    @POST("api/wifi/acl/block")
    suspend fun blockWifiDevice(@Body body: Map<String, String>): WifiAclResponse

    /** body: `mac`（必填，大小写不敏感）。 */
    @POST("api/wifi/acl/unblock")
    suspend fun unblockWifiDevice(@Body body: Map<String, String>): WifiAclResponse

    /** 清空黑名单（白名单原样保留），无请求体。 */
    @POST("api/wifi/acl/clear")
    suspend fun clearWifiAcl(): WifiAclResponse

    /**
     * WiFi 连接二维码（`GET /api/wifi/qrcode`）。
     *
     * **响应体是图片二进制**（Content-Type 由设备给，通常 image/png），不是 JSON —— 因此
     * 返回 [ResponseBody] 而不是数据类，调用方自己 decode。二维码由**设备**生成、core 只转发，
     * 所以它反映设备当前的 SSID/密码：改完 WiFi 配置必须重新拉一次。
     *
     * 设备读不到时 core 回 503 + JSON 失败信封（不是空图），这里会抛 HttpException。
     *
     * @param chip 只接受 `chip1` / `chip2`，其它值 core 直接 400。
     * @param ssidIndex SSID 序号，非数字时 core 静默回退 1。
     */
    @Streaming
    @GET("api/wifi/qrcode")
    suspend fun getWifiQrCode(
        @Query("chip") chip: String = "chip1",
        @Query("ssid_index") ssidIndex: Int = 1
    ): ResponseBody

    // ========== Device Control ==========
    @POST("api/device/reboot")
    suspend fun rebootDevice(): DeviceControlResponse

    @POST("api/device/factory-reset")
    suspend fun factoryReset(): DeviceControlResponse

    @POST("api/device/debug")
    suspend fun setDeviceMode(@Body body: Map<String, Boolean>): DeviceDebugResponse

    // ========== Service Control（2026-08-26） ==========
    // 停/启的是后端"后台采集服务"，HTTP 服务不受影响 —— 停止后仍可远程再启动。
    // 路径**故意保留字面量**：`scripts/verify-api-contract.mjs` 靠扫描字面量比对 core 路由，
    // 换成常量后校验器会看不到这 5 个端点（实测 app 端点数 173→168）。
    // 与 contract 的一致性由 `ServiceEndpointContractTest` 用反射断言锁死。
    @GET("api/service/status")
    suspend fun getServiceStatus(): ServiceStatusResponse

    @POST("api/service/start")
    suspend fun startBackgroundService(): ServiceStatusResponse

    @POST("api/service/stop")
    suspend fun stopBackgroundService(): ServiceStatusResponse

    /** 重启后端服务：HTTP 会中断约 10 秒（进程通常不退出，只是 Service 组件重建）。 */
    @POST("api/service/restart")
    suspend fun restartBackendService(): ServiceRestartResponse

    @POST("api/service/autostart")
    suspend fun setServiceAutoStart(@Body body: Map<String, Boolean>): ServiceStatusResponse

    /**
     * 上一次 core 崩溃信息（2026-09-04）。core 崩溃后由 keepalive 脚本 / START_STICKY 自动拉起，
     * app 此前完全无感（只表现为数据断一下）。连上设备后拉一次，与本地"已提示过的时间戳"
     * 比对，不同就弹一次窗。去重放本地而不是服务端 ack —— 否则 app 一 ack，web 就再也看不到。
     */
    @GET("api/service/crash")
    suspend fun getCoreCrashReport(): CoreCrashResponse

    @POST("api/device/password")
    suspend fun changePassword(@Body body: Map<String, String>): SuccessResponse

    // 查询设备设置状态（LED、性能模式、漫游、Bearer 等）
    @GET("api/device/settings")
    suspend fun getDeviceSettings(): DeviceSettingsResponse

    @POST("api/device/data-limit")
    suspend fun setDataLimit(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @POST("api/device/flow-calibration")
    suspend fun calibrateFlow(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @GET("api/device/traffic-limit")
    suspend fun getTrafficLimit(): TrafficLimitConfig

    @GET("api/device/lan-settings")
    suspend fun getLanSettings(): LanSettingsResponse

    // ========== Network Control (new) ==========
    @POST("api/network/bearer")
    suspend fun setBearerPreference(@Body body: Map<String, String>): BearerPreferenceResponse

    @POST("api/network/connect")
    suspend fun connectNetwork(): DeviceControlResponse

    @POST("api/network/disconnect")
    suspend fun disconnectNetwork(): DeviceControlResponse

    @POST("api/network/connection-mode")
    suspend fun setConnectionMode(@Body body: Map<String, String>): ConnectionModeResponse

    @GET("api/network/cell-info")
    suspend fun getCellInfo(): CellInfoResponse

    /**
     * 邻区列表（实时向设备查询，**不走 core 的 responseCache**）。
     * 一次调用会向设备发一次 goform 查询，所以只在用户手动刷新时调，不要放进定时轮询。
     * 设备侧不支持时回空数组而不是错误；core 完全没有查询通道时才回 503。
     */
    @GET("api/network/neighbor-cells")
    suspend fun getNeighborCells(): NeighborCellsResponse

    // ========== SMS / SIM Actions ==========
    @POST("api/sms/delete")
    suspend fun deleteSms(@Body body: Map<String, String>): SmsActionResponse

    /** 按号码删除整段会话（2026-09-21）。 */
    @POST("api/sms/delete-conversation")
    suspend fun deleteConversation(@Body body: Map<String, String>): SmsActionResponse

    /** 批量删除多条短信（2026-09-21）。 */
    @POST("api/sms/delete-batch")
    suspend fun deleteBatch(@Body body: Map<String, @JvmSuppressWildcards Any>): SmsActionResponse

    @POST("api/sms/read")
    suspend fun markSmsRead(@Body body: Map<String, String>): SmsActionResponse

    @POST("api/sms/read-conversation")
    suspend fun markConversationRead(@Body body: Map<String, String>): SmsActionResponse

    /** 把设备上**全部**短信标为已读（`POST /api/sms/mark-all-read`，无请求体）。 */
    @POST("api/sms/mark-all-read")
    suspend fun markAllSmsRead(): SmsActionResponse

    @GET("api/sms/verification-codes")
    suspend fun getVerificationCodes(): VerificationCodeListResponse

    // ========== 短信拦截（号码黑名单 + 关键词） ==========
    //
    // 判定全在 core：命中就不发邮件、不推 WS、不入验证码库、不进列表与计数。
    // app 这七个端点只做「规则的增删改查」与「拦截记录的读/清」，**不做任何判定**。
    //
    // 刻意没有 `rules/test`（试算）：去掉正则之后 contains/equals/prefix/suffix 行为可预测，
    // 「我的规则有没有生效」由 hit_count + 拦截记录回答。

    @GET("api/sms/rules")
    suspend fun getSmsRules(): SmsRuleListResponse

    @POST("api/sms/rules")
    suspend fun createSmsRule(@Body body: SmsRuleRequest): SmsFilterMutationResponse

    /**
     * 修改规则（含 enabled 切换）。core 侧是**字段级合并** —— 请求体里没给值的字段保留原值，
     * 所以「只切开关」传 `SmsRuleRequest(enabled = …)` 就够，不会把 pattern/scope 一起重置。
     */
    @PUT("api/sms/rules/{id}")
    suspend fun updateSmsRule(@Path("id") id: Long, @Body body: SmsRuleRequest): SmsFilterMutationResponse

    @DELETE("api/sms/rules/{id}")
    suspend fun deleteSmsRule(@Path("id") id: Long): SmsFilterMutationResponse

    /**
     * 拦截记录列表：**keyset 游标**分页，没有 offset。
     *
     * 翻页把上一页响应里的 `next_cursor_ts` / `next_cursor_id` 原样传回来；首页两个都传 null。
     * 用两个游标而不是单个时间戳：同一毫秒可能落多条记录，只按 ts 翻页会漏或重。
     */
    @GET("api/sms/blocked")
    suspend fun getSmsBlocked(
        @Query("limit") limit: Int = 50,
        @Query("cursor_ts") cursorTs: Long? = null,
        @Query("cursor_id") cursorId: Long? = null
    ): SmsBlockedListResponse

    @DELETE("api/sms/blocked")
    suspend fun clearSmsBlocked(): SmsFilterMutationResponse

    @DELETE("api/sms/blocked/{id}")
    suspend fun deleteSmsBlocked(@Path("id") id: Long): SmsFilterMutationResponse

    @POST("api/sim/switch")
    suspend fun switchSimSlot(@Body body: Map<String, Int>): SimSwitchResponse

    // ========== Config ==========
    @GET("api/config/version")
    suspend fun getServerVersion(): ServerVersionInfo

    // ========== 配置备份与恢复（2026-09-11） ==========
    // 三条与二进制打交道的端点都不是 JSON：导出**回** ZIP，预览/恢复**收** ZIP。
    // 口令走 X-Backup-Passphrase 请求头而不是 query —— query 会进访问日志。

    @GET("api/backup/info")
    suspend fun getBackupInfo(): BackupInfoResponse

    /** 导出：请求体是 JSON，响应体是 `.ufibak`（外层 ZIP）二进制。 */
    @Streaming
    @POST("api/backup/export")
    suspend fun exportBackup(@Body body: BackupExportRequest): ResponseBody

    /**
     * 预览：只读清单，不落任何配置。
     *
     * [body] 必须自带 `application/octet-stream` —— [com.ufi_axis.data.api.RetrofitClient]
     * 会给没有 Content-Type 的请求体补 `application/json`。
     */
    @POST("api/backup/preview")
    suspend fun previewBackup(
        @Body body: okhttp3.RequestBody,
        @Header("X-Backup-Passphrase") passphrase: String? = null
    ): BackupPreviewResponse

    /** 恢复。[mode] 传 `replace` 才清掉本机多出来的项，默认 `merge` 只覆盖包里有的。 */
    @POST("api/backup/import")
    suspend fun importBackup(
        @Body body: okhttp3.RequestBody,
        @Query("mode") mode: String = BACKUP_MODE_MERGE,
        @Header("X-Backup-Passphrase") passphrase: String? = null
    ): BackupImportResponse

    // ========== Update（2026-08-10：后端自拉取 + 前端兜底推送） ==========
    @POST("api/update/check")
    suspend fun triggerDeviceUpdate(): UpdateStatusResponse

    @GET("api/update/status")
    suspend fun getDeviceUpdateStatus(): UpdateStatusResponse

    @POST("api/update/upload")
    @Multipart
    suspend fun pushUpdateApk(@Part file: MultipartBody.Part): UpdateUploadResponse

    @POST("api/update/install-local")
    suspend fun installLocalApk(@Body body: Map<String, String>): UpdateInstallResponse

    @POST("api/update/reset")
    suspend fun resetDeviceUpdate(): UpdateStatusResponse

    // ========== Frontend App Update（2026-08-10 C5：经 Core 转发前端更新信息） ==========
    @GET("api/update/frontend-info")
    suspend fun getFrontendUpdateInfo(): FrontendUpdateInfo

    /**
     * Core 自身的更新信息（**只检查、不安装**，2026-09-06）。
     *
     * core 读清单 backend 对象并自己做版本比对，返回 `has_update` —— 这是 App 端判断
     * 「设备上的 core 有没有新版本」的唯一正确数据源。真正触发安装仍走
     * [triggerDeviceUpdate]（`POST /api/update/check`，core 自下载 + 安装 + 重启）。
     *
     * core 拉不到更新源时返回 **HTTP 502**，Retrofit 抛 HttpException，调用方按"检查失败"处理。
     */
    @GET("api/update/backend-info")
    suspend fun getBackendUpdateInfo(): BackendUpdateInfo

    // ========== Web 控制面板资源（2026-08-27） ==========
    // 换的是 core 里那份 Web 控制面板产物（override 落在 filesDir/web），跟 core APK 更新是两套东西。
    // 路径同样保留字面量，一致性由 ServiceEndpointContractTest 反射断言兜住。
    /** 返回 `{mode:"override"|"bundled", version, bundledVersion, hasBackup, ...}`，字段随 core 演进故用 JsonElement。 */
    @GET("api/web/version")
    suspend fun getWebAssetVersion(): kotlinx.serialization.json.JsonElement

    /**
     * 清除 web override，恢复 APK 内置面板。用于上传了坏资源导致面板白屏、无法从面板自身恢复的情况。
     * core 删完会复核 `hasOverride()`，删不干净返回 **HTTP 500 + success:false**（Retrofit 会抛 HttpException）。
     * 注意会连备份一起删，clear 之后回滚也没有备份可回。
     */
    @POST("api/web/clear")
    suspend fun clearWebAssets(): kotlinx.serialization.json.JsonElement

    /** Web 面板自动更新状态快照。**只能轮询**（本组没有 WS 推送），语义见 [WebUpdateStatusResponse]。 */
    @GET("api/web/status")
    suspend fun getWebUpdateStatus(): WebUpdateStatusResponse

    /**
     * 触发从 `version.json` 的 `web` 对象自拉取并覆盖安装。
     * 返回的是**状态快照**而不是「本次是否发起成功」：已经在跑时它不报错、只回当前状态。
     */
    @POST("api/web/check")
    suspend fun checkWebAssetUpdate(): WebUpdateStatusResponse

    /**
     * 手动上传面板 ZIP（multipart，字段名**必须是 `file`**，core 只认这个名字）。
     * 限制：≤50MB（core 侧 `WEB_UPDATE_BODY_SIZE` 也按这个放行），ZIP **根目录**必须同时含
     * `index.html` 与 `version.json`，否则 400 `{"error": "..."}`。
     */
    @Multipart
    @POST("api/web/update")
    suspend fun uploadWebAssets(@Part file: MultipartBody.Part): kotlinx.serialization.json.JsonElement

    /**
     * 回滚到上一版面板。**一次性**：成功后备份就没了（`hasBackup` 变 false）。
     * 没有可用备份时 400 `{"error": "没有可回滚的备份版本"}` —— 判据比 `hasBackup` 严
     * （要求备份里同时有 `version.json` 和 `index.html`），所以 `hasBackup == true` 也可能回滚失败。
     */
    @POST("api/web/rollback")
    suspend fun rollbackWebAssets(): kotlinx.serialization.json.JsonElement


    @GET("api/config")
    suspend fun getConfig(): AppConfig

    @PUT("api/config")
    suspend fun updateConfig(@Body body: Map<String, @JvmSuppressWildcards Any>): ConfigUpdateResponse

    /**
     * 同步更新源 / 镜像前缀到设备端 Core（2026-08-12）：
     * body 传 `update_url`（后端 version.json URL）+ `update_mirror_base`（镜像前缀，空串=直连）。
     * 与 [updateConfig] 同路由，语义独立便于调用方表达意图。
     */
    @PUT("api/config")
    suspend fun updateUpdateSource(@Body body: Map<String, @JvmSuppressWildcards Any>): ConfigUpdateResponse

    /**
     * core 的更新源决策（2026-09-22）。app 的 APK 自更新刻意不走 core 代理
     * （core 挂了也得能更新自己），所以只取决策、自己拼 URL 下载。
     */
    @GET("api/update/source")
    suspend fun getUpdateSource(): UpdateSourceInfo

    @POST("api/config/reset")
    suspend fun resetConfig(): ConfigResetResponse

    // ========== App Management ==========
    @GET("api/apps")
    suspend fun getAppList(@Query("filter") filter: String = "user"): AppListResponse

    @GET("api/apps/{packageName}")
    suspend fun getAppDetail(@Path("packageName") packageName: String): AppDetailResponse

    @POST("api/apps/install")
    suspend fun installApp(@Body body: AppInstallRequest): AppInstallResponse

    @POST("api/apps/install-url")
    suspend fun installAppFromUrl(@Body body: AppInstallUrlRequest): AppInstallResponse

    @POST("api/apps/uninstall")
    suspend fun uninstallApp(@Body body: AppActionRequest): AppInstallResponse

    @POST("api/apps/disable")
    suspend fun disableApp(@Body body: AppActionRequest): AppActionResponse

    @POST("api/apps/enable")
    suspend fun enableApp(@Body body: AppActionRequest): AppActionResponse

    @POST("api/apps/clear")
    suspend fun clearAppData(@Body body: AppActionRequest): AppActionResponse

    @POST("api/apps/force-stop")
    suspend fun forceStopApp(@Body body: AppActionRequest): AppActionResponse

    @POST("api/apps/permission")
    suspend fun managePermission(@Body body: AppPermissionRequest): AppPermissionResponse

    /**
     * 一次授予该应用**全部**已声明的运行时权限（core 走 `pm grant` 批量执行，需要特权 shell）。
     *
     * **高风险动作**：不可撤销地把定位/短信/通话记录等全部打开，UI 必须先弹红色确认弹窗。
     * 语义与失败形态见 [AppGrantAllResponse]。
     */
    @POST("api/apps/grant-all-permissions")
    suspend fun grantAllAppPermissions(@Body body: AppActionRequest): AppGrantAllResponse

    @POST("api/apps/freeze")
    suspend fun freezeApp(@Body body: AppActionRequest): AppActionResponse

    @POST("api/apps/unfreeze")
    suspend fun unfreezeApp(@Body body: AppActionRequest): AppActionResponse

    // ========== 邮件通知（路径仍是 /api/sms-forward） ==========
    /**
     * 读邮件通知全量配置。
     *
     * 2026-09-10 起响应里也带「规则同构」那一组：`min_level` / `daily_limit` 两个旋钮，
     * `sent_today` / `quota_remaining` 两个只读位（`daily_limit = 0` 时后者是 **null** =
     * 不限），以及 `levels` / `daily_limit_min|max` 三个值域。全部是 core 的判据，UI 直接渲染。
     */
    @GET("api/sms-forward/config")
    suspend fun getSmsForwardConfig(): SmsForwardConfig

    /**
     * 写邮件通知配置。
     *
     * **2026-09-10 起可能返回 400**（Retrofit 抛 `HttpException`）：`daily_limit` 越界
     * 或 `min_level` 认不出时。此前这个端点没有任何校验、永远 `success:true`，
     * 所以调用方必须有失败分支 —— 错误体是 `{"error": 中文原因}`，把那句话原样显示出来
     * 比"保存失败"有用（口径同 Webhook / 本机短信的 PUT）。
     */
    @POST("api/sms-forward/config")
    suspend fun saveSmsForwardConfig(@Body body: SmsForwardConfig): SmsForwardSaveResponse

    @POST("api/sms-forward/test")
    suspend fun testSmsForward(): SmsForwardTestResponse

    /** 邮件通知诊断（`GET /api/sms-forward/diagnose`）：只回派生屏蔽位，不回凭据原值。 */
    @GET("api/sms-forward/diagnose")
    suspend fun diagnoseSmsForward(): SmsForwardDiagnose

    /**
     * 通知投递历史：**keyset 游标**分页，参数与 [getSmsBlocked] 同形。
     *
     * 三条渠道共用这一份记录表（响应 DTO 见 [MailHistoryListResponse]），路径仍是
     * `sms-forward` —— 端点名是跨端契约，改它只会破坏 web 与 API 手册。
     *
     * @param channel `"mail"` / `"webhook"` / `"local_sms"`；传 null 不过滤（= 全部渠道）。
     * @param result **三态**筛选：`"success"`（已发出）/ `"failed"`（发起了但失败）/
     *   `"skipped"`（被闸门拦下、没发起）；传 null 不过滤。
     *   过滤放在服务端而不是客户端筛内存：只看失败时用户想翻的是**全表**的失败记录，
     *   本地筛当前页会出现「明明 total 里有 12 条失败，列表只显示 2 条」。
     */
    @GET("api/sms-forward/history")
    suspend fun getMailHistory(
        @Query("limit") limit: Int = 50,
        @Query("cursor_ts") cursorTs: Long? = null,
        @Query("cursor_id") cursorId: Long? = null,
        @Query("channel") channel: String? = null,
        @Query("result") result: String? = null
    ): MailHistoryListResponse

    /**
     * 清空投递记录。
     *
     * @param channel 只清这一条渠道（`mail` / `webhook` / `local_sms`）；
     *   传 null = **全清**（三条渠道共用一张表）。所以调用方的确认文案必须跟这个参数对齐 ——
     *   说成"一并清空"而实际只清了一条，或者反过来，都会让用户以为记录莫名丢了。
     */
    @DELETE("api/sms-forward/history")
    suspend fun clearMailHistory(
        @Query("channel") channel: String? = null
    ): SmsForwardSaveResponse

    /**
     * 删单条投递记录（详情弹窗「删除」）。
     *
     * 旧 core 无此端点会回 404/405，调用方需按「设备端不支持」提示，不要当网络故障重试刷屏。
     */
    @DELETE("api/sms-forward/history/{id}")
    suspend fun deleteMailHistoryById(
        @Path("id") id: Long
    ): MailHistoryDeleteResponse

    /** 三渠道配置页统计卡（总投递 / 失败 / 拦截 / 最近成功）。 */
    @GET("api/sms-forward/history/stats")
    suspend fun getMailHistoryStats(
        @Query("channel") channel: String
    ): MailHistoryStatsResponse

    // ========== 通用 Webhook 通知渠道 ==========
    /**
     * 读全量配置。响应里连**预设表与占位符清单**一起回（`presets` / `placeholders`）——
     * 那是 core 的数据，UI 直接渲染，不在 app 里抄第二份（抄了就会分叉）。
     */
    @GET("api/notify/webhook/config")
    suspend fun getWebhookConfig(): WebhookConfigResponse

    /**
     * 字段级合并；body 只带改动的键（null 等于"不改"，见 [WebhookConfigPatch]）。
     * 响应带立即回读的 `config`，"存进去没有"当场就能验掉。
     */
    @PUT("api/notify/webhook/config")
    suspend fun updateWebhookConfig(@Body body: WebhookConfigPatch): WebhookConfigSaveResponse

    /**
     * 发一条测试通知（manual 口径：不受总开关 / 免打扰 / 场景勾选约束，但仍要求配置齐全）。
     * 响应带 HTTP 状态码与响应体摘要 —— 那是用户排 Webhook 时唯一的线索。
     */
    @POST("api/notify/webhook/test")
    suspend fun testWebhook(): WebhookTestResponse

    // ========== 本机短信回发渠道 ==========
    /**
     * 读全量配置。响应里带 `configured` / `sent_today` / `quota_remaining` 三个只读位
     * 与 `levels` / `daily_limit_min|max` 三个值域 —— 都是 core 的判据，UI 直接渲染。
     */
    @GET("api/notify/sms/config")
    suspend fun getLocalSmsConfig(): LocalSmsConfigResponse

    /**
     * 字段级合并；body 只带改动的键（null 等于"不改"，见 [LocalSmsConfigPatch]）。
     * 响应带立即回读的 `config`，"存进去没有"当场就能验掉。
     */
    @PUT("api/notify/sms/config")
    suspend fun updateLocalSmsConfig(@Body body: LocalSmsConfigPatch): LocalSmsConfigSaveResponse

    /**
     * 发一条测试短信。**这会真的从设备 SIM 发出一条短信、产生费用、并消耗一条今日配额** ——
     * 所以 UI 必须在调用之前让用户确认（不是"点了再说"的按钮）。
     * 响应带固件结论、是否计入配额与剩余配额。
     */
    @POST("api/notify/sms/test")
    suspend fun testLocalSms(): LocalSmsTestResponse

    // ========== Scheduled Tasks ==========
    @GET("api/tasks")
    suspend fun getTaskList(): TaskListResponse

    @GET("api/tasks/{id}")
    suspend fun getTask(@Path("id") id: String): ScheduledTask

    @POST("api/tasks")
    suspend fun createTask(@Body body: ScheduledTask): TaskCreateResponse

    @PUT("api/tasks/{id}")
    suspend fun updateTask(@Path("id") id: String, @Body body: ScheduledTask): SuccessResponse

    @DELETE("api/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String): SuccessResponse

    @POST("api/tasks/clear")
    suspend fun clearTasks(): SuccessResponse

    @GET("api/tasks/{id}/logs")
    suspend fun getTaskLogs(@Path("id") id: String): TaskLogsResponse

    // ========== Automation Rules (条件触发 · 当…就…) ==========
    @GET("api/rules")
    suspend fun getRuleList(): RuleListResponse

    @GET("api/rules/{id}")
    suspend fun getRule(@Path("id") id: String): AutomationRule

    @GET("api/rules/{id}/logs")
    suspend fun getRuleLogs(@Path("id") id: String): RuleLogsResponse

    @POST("api/rules")
    suspend fun createRule(@Body body: AutomationRule): RuleCreateResponse

    @PUT("api/rules/{id}")
    suspend fun updateRule(@Path("id") id: String, @Body body: AutomationRule): SuccessResponse

    @DELETE("api/rules/{id}")
    suspend fun deleteRule(@Path("id") id: String): SuccessResponse

    @POST("api/rules/clear")
    suspend fun clearRules(): SuccessResponse

    // ========== Enhanced Device ==========
    @GET("api/device/thermal")
    suspend fun getThermalZones(): JsonElement

    @GET("api/device/connections")
    suspend fun getConnectionCounts(): JsonElement

    @GET("api/device/data-usage")
    suspend fun getDataUsage(): JsonElement

    @GET("api/dashboard/summary")
    suspend fun getDashboardSummary(): DashboardSummaryResponse

    @GET("api/device/selinux")
    suspend fun getSelinuxStatus(): JsonElement

    @POST("api/device/fota")
    suspend fun setFotaDisabled(@Body body: Map<String, Boolean>): SuccessResponse

    @POST("api/device/performance")
    suspend fun setPerformanceMode(@Body body: Map<String, String>): SuccessResponse

    @POST("api/device/led")
    suspend fun setLedEnabled(@Body body: Map<String, Boolean>): SuccessResponse

    @POST("api/device/roaming")
    suspend fun setRoamingEnabled(@Body body: Map<String, Boolean>): SuccessResponse

    // ========== WiFi Enable ==========
    @POST("api/wifi/enable")
    suspend fun setWifiEnabled(@Body body: Map<String, Boolean>): SuccessResponse

    // ========== WiFi Sleep ==========
    @POST("api/wifi/sleep")
    suspend fun setWifiSleep(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // ========== SAMBA ==========
    @POST("api/device/samba")
    suspend fun setSambaSetting(@Body body: Map<String, Boolean>): SuccessResponse

    // ========== Cell Lock ==========
    @POST("api/device/cell-lock")
    suspend fun cellLock(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @POST("api/device/cell-unlock")
    suspend fun unlockAllCell(): SuccessResponse

    // ========== Shutdown ==========
    @POST("api/device/shutdown")
    suspend fun shutdownDevice(): DeviceControlResponse

    // ========== Restart Schedule ==========
    @POST("api/device/restart-schedule")
    suspend fun setRestartSchedule(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // ========== DHCP ==========
    @POST("api/device/dhcp")
    suspend fun setDhcpSetting(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    // ========== Speed Test ==========
    @Streaming
    @GET("api/speedtest")
    suspend fun speedTest(@Query("ckSize") ckSize: Int = 10): ResponseBody

    /**
     * 零负载延迟探针：只取响应头，不搬运任何字节。
     * 延迟/抖动要采样十几次，用 GET 每次都要白搬 1MiB 并占一个并发位，所以走 HEAD。
     */
    @HEAD("api/speedtest")
    suspend fun speedTestPing()

    /**
     * 上行测速：请求体被后端读完即丢弃，只回 `{"success":true,"bytes":N}`。
     * [body] 必须显式带 `application/octet-stream`——[com.ufi_axis.util.NetworkLogInterceptor]
     * 对「文本类型 + 未知长度」的请求体会整体缓冲进内存。
     */
    @POST("api/speedtest/upload")
    suspend fun speedTestUpload(@Body body: okhttp3.RequestBody): ResponseBody

    // ========== Debug Logs ==========
    @GET("api/debug-logs")
    suspend fun getDebugLogs(@Query("level") level: String? = null, @Query("limit") limit: Int = 200): DebugLogsResponse

    @HTTP(method = "DELETE", path = "api/debug-logs", hasBody = false)
    suspend fun clearDebugLogs(): kotlinx.serialization.json.JsonElement

    /** 列出 core 的落盘日志文件（私有目录，文件管理器读不到）。 */
    @GET("api/debug-logs/files")
    suspend fun getDebugLogFiles(): DebugLogFilesResponse

    /**
     * 读取单个落盘日志文件的**尾部**（core 侧限制 1KB~2MB，默认 256KB）。
     * 返回 `text/plain` 原文，不是 JSON——单文件可达几十 MB，整读会 OOM。
     *
     * ## 路径形状只有一种（2026-08-30 统一）
     * core 侧是**唯一一条尾通配路由** `get("/{name...}")`（`DebugLogRoutes.kt`），把捕获到的
     * 各段用 `/` 拼回来后按 `^\d{4}-\d{2}-\d{2}/(app|at|error)\.log(\.1)?$` 白名单校验。
     * 也就是说 [name] 是**带一个斜杠的相对路径**（`2026-08-28/app.log`），不是两个独立参数
     * ——手册里曾写成 `{date}/{file}`，那只是排版，core 并没有第二种实现。
     *
     * `encoded = true` 是必须的：默认编码会把斜杠转义成 `%2F`。Ktor 解码后虽然仍能匹配，
     * 但 `%2F` 会被相当多反向代理规范化或直接拒绝 —— 本项目自带内网穿透（frp / cloudflared），
     * 远程访问时就会只在隧道链路上坏掉。白名单已把字符限制在 `[0-9-]`、`/` 和固定文件名里，
     * 没有需要转义的字符，因此原样透传是安全的。
     */
    @GET("api/debug-logs/files/{name}")
    suspend fun getDebugLogFileTail(
        @Path(value = "name", encoded = true) name: String,
        @Query("max_bytes") maxBytes: Int = 256 * 1024
    ): ResponseBody

    /** 删除 core 全部落盘日志文件（内存缓冲用 [clearDebugLogs] 清）。 */
    @HTTP(method = "DELETE", path = "api/debug-logs/files", hasBody = false)
    suspend fun deleteDebugLogFiles(): kotlinx.serialization.json.JsonElement

    // ========== File Management ==========
    @GET("api/files/status")
    suspend fun getStorageStatus(): StorageStatusResponse

    @GET("api/files/list")
    suspend fun listFiles(@Query("path") path: String): FileListResponse

    @GET("api/files/info")
    suspend fun getFileInfo(@Query("path") path: String): FileInfoResponse

    @POST("api/files/read")
    suspend fun readFile(@Body body: Map<String, String>): FileReadResponse

    @POST("api/files/write")
    suspend fun writeFile(@Body body: Map<String, @JvmSuppressWildcards Any>): SuccessResponse

    @POST("api/files/delete")
    suspend fun deleteFile(@Body body: Map<String, String>): SuccessResponse

    @POST("api/files/rename")
    suspend fun renameFile(@Body body: Map<String, String>): SuccessResponse

    @POST("api/files/move")
    suspend fun moveFile(@Body body: Map<String, String>): SuccessResponse

    @POST("api/files/copy")
    suspend fun copyFile(@Body body: Map<String, String>): SuccessResponse

    @POST("api/files/mkdir")
    suspend fun createDirectory(@Body body: Map<String, String>): SuccessResponse

    @GET("api/files/search")
    suspend fun searchFiles(@Query("path") path: String, @Query("query") query: String, @Query("depth") depth: Int = 3): SearchFilesResponse

    @GET("api/files/disk-usage")
    suspend fun getDiskUsage(): DiskUsageResponse

    @POST("api/files/touch")
    suspend fun touchFile(@Body body: Map<String, String>): SuccessResponse

    // ── Archive ops ──
    /** 解压压缩包：body { path, destination? }；支持 zip / tar.gz / tgz / tar / gz。 */
    @POST("api/files/extract")
    suspend fun extractArchive(@Body body: Map<String, String>): ArchiveExtractResponse

    /** 压缩：body { paths:[...], destination? }；生成单个 .zip。 */
    @POST("api/files/compress")
    suspend fun compressFiles(@Body body: Map<String, @JvmSuppressWildcards Any>): ArchiveCompressResponse

    /** 校验和：body { path, algorithms? }；默认 md5/sha1/sha256，可选 sha512。 */
    @POST("api/files/checksum")
    suspend fun checksumFile(@Body body: Map<String, String>): ArchiveChecksumResponse

    // ── 远端上传的第二阶段：core 暂存 → 外部存储源 ──
    // 第一阶段（手机 → core）走 `upload/session|chunk|complete`，那条链路是裸 OkHttp
    // （要按偏移切片、逐字节推进度，见 ChunkedFileUploader）。第二阶段只是查状态与下指令，
    // 走 Retrofit 即可，不必再手搓。

    /** 列出推送作业（新的在前，含 6 小时内的已完成/失败记录）。UI 靠轮询它驱动任务面板。 */
    @GET("api/files/remote-push")
    suspend fun listRemotePushJobs(): RemotePushListResponse

    /** 取消在途作业。已结束的作业回 `success:false`（正常竞态，不是错误）。 */
    @DELETE("api/files/remote-push")
    suspend fun cancelRemotePushJob(@Query("job") jobId: String): SuccessResponse

    /** 重试失败/已取消的作业：body { job_id }。暂存文件已被清理时 core 回 409。 */
    @POST("api/files/remote-push/retry")
    suspend fun retryRemotePushJob(@Body body: Map<String, String>): SuccessResponse

    /** 清掉所有已结束的记录（失败作业的暂存文件一并删除）。 */
    @POST("api/files/remote-push/clear")
    suspend fun clearRemotePushJobs(): SuccessResponse

    // ========== External Storage Sources ==========
    //
    // 外部存储源（FTP / WebDAV）的增删改查。这里**只管配置**：真正的列目录 / 读写
    // 仍然走上面那套 `/api/files/*`，路径写成 `remote:<sourceId>/<相对路径>`，
    // core 侧按前缀派发给对应 provider。也就是说 app 不需要第二套文件 API，
    // 远端与本地在文件层面是同一条链路（见 [FileItem.source]）。

    @GET("api/storage/sources")
    suspend fun listStorageSources(): StorageSourcesResponse

    @GET("api/storage/sources/{id}")
    suspend fun getStorageSource(@Path("id") id: String): StorageSourceInfo

    @POST("api/storage/sources")
    suspend fun addStorageSource(@Body body: StorageSourceRequest): StorageSourceMutationResponse

    /** 改配置。`password` 传 `"********"` 表示「不动原密码」（core 侧约定，响应里也从不回密码）。 */
    @PUT("api/storage/sources/{id}")
    suspend fun updateStorageSource(@Path("id") id: String, @Body body: StorageSourceRequest): SuccessResponse

    @DELETE("api/storage/sources/{id}")
    suspend fun deleteStorageSource(@Path("id") id: String): SuccessResponse

    /** 测已保存的源（用库里那份密码）。 */
    @POST("api/storage/sources/{id}/test")
    suspend fun testStorageSource(@Path("id") id: String): StorageSourceTestResponse

    /** 测还没保存的配置（整份配置进 body），供「先测通再保存」。 */
    @POST("api/storage/sources/test")
    suspend fun testStorageSourceConfig(@Body body: StorageSourceRequest): StorageSourceTestResponse

    // ========== Monitor ==========
    // points 默认 240（2026-08-26 性能：原 360）——图表宽度只有约 1000px，
    // 更细的桶换不来任何可见精度，却要多付 JSON 解析与曲线几何构建的开销。
    //
    // bucketMs（2026-09-03）：显式指定桶宽，服务端优先采用它而不是按 (end-start)/points 自算。
    // 增量刷新的请求窗口只有一两个桶那么长，让服务端自算会得到远细于首屏的网格，两批点的 t
    // 互不相交，客户端按 t 去重就完全失效（点数无上限增长、尾部时间重复）。
    @GET("api/monitor/history")
    suspend fun getMonitorHistory(
        @Query("type") type: String,
        @Query("hours") hours: Int,
        @Query("points") points: Int = 240,
        @Query("bucket_ms") bucketMs: Long? = null
    ): MonitorHistoryResponse

    /** 区间模式：start_time/end_time（epoch millis，≤30 天，与 hours 互斥），SQL 层桶聚合；响应字段不变 */
    @GET("api/monitor/history")
    suspend fun getMonitorHistoryRange(
        @Query("type") type: String,
        @Query("start_time") startTimeMs: Long,
        @Query("end_time") endTimeMs: Long,
        @Query("points") points: Int = 240,
        @Query("bucket_ms") bucketMs: Long? = null
    ): MonitorHistoryResponse

    @GET("api/monitor/storage")
    suspend fun getMonitorStorage(): MonitorStorageResponse

    @POST("api/monitor/clean")
    suspend fun cleanHistory(@Body body: CleanHistoryRequest): CleanHistoryResponse

    /** 监控总开关（P6-a）：enabled=false 停止 DataScheduler 采集；请求体 {"enabled": true|false} */
    @POST("api/monitor/control")
    suspend fun setMonitorControl(@Body body: Map<String, Boolean>): JsonElement

    /**
     * 监控个性化偏好（T40-5）：core 是唯一真源，app 的 `monitor_settings_v1` 降级为缓存。
     * 不含 `collectEnabled`（真源在 `/api/service/status`）。
     */
    @GET("api/monitor/preferences")
    suspend fun getMonitorPreferences(): MonitorPrefsPayload

    /** 字段级合并；响应回显服务端校验后的最终值，客户端应以回显为准而不是自己的入参。 */
    @PUT("api/monitor/preferences")
    suspend fun updateMonitorPreferences(@Body body: MonitorPrefsPayload): MonitorPrefsUpdateResponse

    // ========== Notifications ==========
    /**
     * 客户端通知配置（T40-6）：core 是唯一真源，app 的 `ufi_axis_prefs` 那 10 个键降级为缓存。
     * 与 `/api/alerts/config` 的边界：这里是「客户端要不要投递」，那里是「服务端要不要产生告警」。
     */
    @GET("api/notifications/config")
    suspend fun getNotificationConfig(): NotificationConfigDto

    /** 字段级合并；body 可以只带改动的键。 */
    @PUT("api/notifications/config")
    suspend fun updateNotificationConfig(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): NotificationConfigUpdateResponse

    // ========== Downloads ==========
    @GET("api/downloads")
    suspend fun getDownloads(): JsonElement

    @POST("api/downloads")
    suspend fun createDownload(@Body body: Map<String, @JvmSuppressWildcards Any>, @Query("force") force: Boolean = false): Response<JsonElement>

    @POST("api/downloads/{id}/pause")
    suspend fun pauseDownload(@Path("id") id: String): Response<JsonElement>

    @POST("api/downloads/{id}/resume")
    suspend fun resumeDownload(@Path("id") id: String): Response<JsonElement>

    @DELETE("api/downloads/{id}")
    suspend fun deleteDownload(@Path("id") id: String, @Query("delete_file") deleteFile: Boolean = false): Response<JsonElement>

    @POST("api/downloads/{id}/rename")
    suspend fun renameDownload(@Path("id") id: String, @Body body: Map<String, String>): Response<JsonElement>

    @POST("api/downloads/{id}/retry")
    suspend fun retryDownload(@Path("id") id: String): Response<JsonElement>

    @POST("api/downloads/clear-completed")
    suspend fun clearCompletedDownloads(): Response<JsonElement>

    @PUT("api/downloads/config")
    suspend fun updateDownloadConfig(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<JsonElement>

    @GET("api/downloads/validate-path")
    suspend fun validateDownloadPath(@Query("path") path: String): JsonElement

    @POST("api/downloads/trackers/refresh")
    suspend fun refreshTrackers(): Response<JsonElement>

    @GET("api/downloads/trackers")
    suspend fun getTrackers(): JsonElement

    @POST("api/downloads/trackers/save")
    suspend fun saveTrackers(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<JsonElement>

    // ========== Pairing ==========
    @GET("api/pairing/status")
    suspend fun getPairingStatus(): JsonElement

    @POST("api/pairing/unpair")
    suspend fun unpairAll(): JsonElement

    @POST("api/pairing/unpair/{fingerprint}")
    suspend fun unpairFingerprint(@Path("fingerprint") fingerprint: String): JsonElement

    @PUT("api/pairing/config")
    suspend fun updatePairingConfig(@Body body: Map<String, @JvmSuppressWildcards Any>): JsonElement

    // ========== Pairing Devices（2026-08-11：配对密码认证 + 配对设备管理） ==========
    /** 已配对设备列表（Bearer；GET /api/pairing/devices）。 */
    @GET("api/pairing/devices")
    suspend fun getPairedDevices(): JsonElement

    /** 重命名已配对设备（Bearer；body {device_name}，1-32 字符）。 */
    @PATCH("api/pairing/devices/{fingerprint}")
    suspend fun renamePairedDevice(
        @Path("fingerprint") fingerprint: String,
        @Body body: Map<String, String>
    ): JsonElement

    /** 移除已配对设备（Bearer + 配对密码；body {password}；最后一台移除触发 token 轮换）。 */
    @HTTP(method = "DELETE", path = "api/pairing/devices/{fingerprint}", hasBody = true)
    suspend fun removePairedDevice(
        @Path("fingerprint") fingerprint: String,
        @Body body: Map<String, String>
    ): JsonElement

    /** 修改配对密码（root 路径免鉴权，旧密码即门禁；body {old_password, new_password}）。 */
    @POST("pairing/change-password")
    suspend fun changeDevicePassword(@Body body: Map<String, String>): JsonElement

    // ========== Tunnel (内网穿透) ==========

    @GET("api/tunnel/status")
    suspend fun getTunnelStatus(): JsonElement

    @POST("api/tunnel/stop")
    suspend fun stopTunnel(): JsonElement

    /** 隧道看护设置（断开自动重连 / 巡检间隔 / 失败提醒）；后端 AppSettings 是唯一真源 */
    @GET("api/tunnel/settings")
    suspend fun getTunnelSettings(): JsonElement

    /** 更新看护设置（只传要改的字段） */
    @PUT("api/tunnel/settings")
    suspend fun updateTunnelSettings(@Body body: Map<String, @JvmSuppressWildcards Any>): JsonElement

    @GET("api/tunnel/frp/configs")
    suspend fun listFrpConfigs(): JsonElement

    @GET("api/tunnel/frp/config/{name}")
    suspend fun readFrpConfig(@Path("name") name: String): JsonElement

    @PUT("api/tunnel/frp/config/{name}")
    suspend fun saveFrpConfigFile(@Path("name") name: String, @Body body: Map<String, @JvmSuppressWildcards Any>): JsonElement

    @DELETE("api/tunnel/frp/config/{name}")
    suspend fun deleteFrpConfig(@Path("name") name: String): JsonElement

    @POST("api/tunnel/frp/config/{name}/activate")
    suspend fun activateFrpConfig(@Path("name") name: String): JsonElement

    /** 启动指定 FRP 通道（多实例：不影响其它通道与 CF 隧道） */
    @POST("api/tunnel/frp/config/{name}/start")
    suspend fun startFrpConfig(@Path("name") name: String): JsonElement

    /** 停止指定 FRP 通道 */
    @POST("api/tunnel/frp/config/{name}/stop")
    suspend fun stopFrpConfig(@Path("name") name: String): JsonElement

    /**
     * 某条 FRP 通道的运行日志（多实例下日志按通道隔离，不再走 /status）。
     * @param full 1 = 读运行期日志文件（完整启动过程，尾部最多 256KB）；0 = 只读内存最近 200 行
     */
    @GET("api/tunnel/frp/config/{name}/log")
    suspend fun getFrpConfigLog(@Path("name") name: String, @Query("full") full: Int): JsonElement

    /** 清空某条 FRP 通道的日志缓冲 */
    @POST("api/tunnel/frp/config/{name}/log/clear")
    suspend fun clearFrpConfigLog(@Path("name") name: String): JsonElement

    /** 停止全部 FRP 通道 */
    @POST("api/tunnel/frp/stop")
    suspend fun stopAllFrp(): JsonElement

    /** 清空全部实例（FRP + CF）的日志缓冲 */
    @POST("api/tunnel/logs/clear")
    suspend fun clearAllTunnelLogs(): JsonElement

    // ── Cloudflare Tunnel（多隧道 / 仅 token） ──

    @GET("api/tunnel/cf/tunnels")
    suspend fun listCfTunnels(): JsonElement

    @GET("api/tunnel/cf/tunnel/{name}")
    suspend fun getCfTunnel(@Path("name") name: String): JsonElement

    @PUT("api/tunnel/cf/tunnel/{name}")
    suspend fun saveCfTunnel(@Path("name") name: String, @Body body: Map<String, @JvmSuppressWildcards Any>): JsonElement

    @DELETE("api/tunnel/cf/tunnel/{name}")
    suspend fun deleteCfTunnel(@Path("name") name: String): JsonElement

    @POST("api/tunnel/cf/tunnel/{name}/activate")
    suspend fun activateCfTunnel(@Path("name") name: String): JsonElement

    @POST("api/tunnel/cf/tunnel/{name}/start")
    suspend fun startCfTunnel(@Path("name") name: String): JsonElement

    /** 停止指定 CF 隧道 */
    @POST("api/tunnel/cf/tunnel/{name}/stop")
    suspend fun stopCfTunnel(@Path("name") name: String): JsonElement

    /**
     * 某条 CF 隧道的运行日志。
     * @param full 1 = 读运行期日志文件尾部；0 = 只读内存最近 200 行
     */
    @GET("api/tunnel/cf/tunnel/{name}/log")
    suspend fun getCfTunnelLog(@Path("name") name: String, @Query("full") full: Int): JsonElement

    /** 清空某条 CF 隧道的日志缓冲 */
    @POST("api/tunnel/cf/tunnel/{name}/log/clear")
    suspend fun clearCfTunnelLog(@Path("name") name: String): JsonElement

    /** 停止全部 CF 隧道 */
    @POST("api/tunnel/cf/stop")
    suspend fun stopAllCf(): JsonElement

    // ========== 可选二进制组件（2026-09-01：frpc / cloudflared 不随 APK 分发） ==========
    // 组件解压后合计约 50MB，曾占 core APK 压缩体积的一半，改为用户按需下载。
    // 本地上传兜底（POST /api/components/{id}/upload）只在 Web 面板提供：
    // App 端要走 SAF 选文件 + 几十 MB 流式上传，收益不足，离线场景用 Web 面板覆盖。

    /**
     * 组件列表（已装版本 / 远端最新版本 / 体积 / 安装状态）。
     * @param refresh "true" = 强制重拉 version.json（会打网络，用于「检查更新」）；省略则用 core 进程内缓存
     */
    @GET("api/components")
    suspend fun listComponents(@Query("refresh") refresh: String? = null): JsonElement

    /** 当前安装任务进度（下载/校验/解包/安装），供轮询 */
    @GET("api/components/status")
    suspend fun getComponentStatus(): JsonElement

    /** 触发下载安装（异步，立即返回；进度经 [getComponentStatus] 轮询） */
    @POST("api/components/{id}/install")
    suspend fun installComponent(@Path("id") id: String): JsonElement

    /** 卸载组件（还有实例在跑时 core 返回 409） */
    @POST("api/components/{id}/uninstall")
    suspend fun uninstallComponent(@Path("id") id: String): JsonElement

    // ========== 诊断 / 运维（2026-08-30：喂给「运行诊断」页） ==========
    // 这一组读的都是 **core 自己的状态**，不打设备，所以可以随页面刷新自由调用。
    // 唯一例外是 [getDiagnose] 带 fields=1 的那条路径 —— 见它的 KDoc。

    /**
     * core 诊断快照。
     * @param fields 传 `1` 才计算字段覆盖率，而那会**逐分组向设备发查询**（最多 10 组）。
     *   默认 null = 不算；诊断页只有用户点「检测字段覆盖率」时才传 1，绝不能进轮询。
     */
    @GET("api/diagnose")
    suspend fun getDiagnose(@Query("fields") fields: String? = null): DiagnoseResponse

    /** QoS / 线程池状态。注意 `enabled` 是硬编码 true、`cpu_temp` 是毫摄氏度，见 [QosStatusResponse]。 */
    @GET("api/qos/status")
    suspend fun getQosStatus(): QosStatusResponse

    /** 响应缓存统计。`entries` 只回前 50 条，见 [CacheStatsResponse]。 */
    @GET("api/cache/stats")
    suspend fun getCacheStats(): CacheStatsResponse

    /** 清空全部响应缓存（无请求体）。 */
    @POST("api/cache/clear")
    suspend fun clearCache(): CacheActionResponse

    /**
     * 按 pattern 失效缓存。**`pattern` 是 glob 不是正则**（`device:*` 这种）。
     *
     * 危险默认值：core 读不到 `pattern` 字段时**回落 `"*"`，等同清空全部缓存**。
     * 所以字段名一个字母写错就会静默变成 clear —— 调用方必须传满 `mapOf("pattern" to ...)`
     * 且 pattern 非空。
     */
    @POST("api/cache/invalidate")
    suspend fun invalidateCache(@Body body: Map<String, String>): CacheActionResponse

    /** Root 可用性（只回 `hasRoot`）。想知道「以什么方式取得的特权」用 [getShellRoot]。 */
    @GET("api/system/root-check")
    suspend fun getRootCheck(): RootCheckResponse

    /** 特权 shell 状态：`root` / `uid` / `method`。替代文档里那个不存在的 `api/adb/status`。 */
    @GET("api/shell/root")
    suspend fun getShellRoot(): ShellRootResponse
}

@Serializable
data class MagiskStatus(
    val installed: Boolean,
    val version: String
)

/**
 * 一条「core 暂存 → 远端存储源」的推送作业（`GET /api/files/remote-push` 的 `jobs` 项）。
 *
 * 字段名与 core 的 `FileRoutes.pushJobJson` 一一对应，snake_case 直接映射，不做重命名 ——
 * 两端对不上时报的是"字段缺失默认值"这种静默错误，改名只会让它更难查。
 *
 * @param state `queued` / `pushing` / `success` / `failed` / `cancelled`
 * @param retryable 失败/取消后还能重试吗（暂存文件还在才行）
 */
@Serializable
data class RemotePushJobInfo(
    val id: String = "",
    val file_name: String = "",
    val source_id: String = "",
    val source_label: String = "",
    val dest_path: String = "",
    val state: String = "",
    val progress: Float = -1f,
    val sent_bytes: Long = 0,
    val total_bytes: Long = 0,
    val error: String? = null,
    /** 完整错误详情（异常链 + 推送上下文），供「错误详情」弹窗展示与一键复制。 */
    val error_detail: String? = null,
    val created_at: Long = 0,
    val finished_at: Long = 0,
    val retryable: Boolean = false
)

@Serializable
data class RemotePushListResponse(
    val jobs: List<RemotePushJobInfo> = emptyList(),
    val active_count: Int = 0
)

@Serializable
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val permissions: String = "",
    val isSymlink: Boolean = false,
    /**
     * 这一条来自哪个存储源：本地是 `"local"`，远端是 `"<protocol>:<sourceId>"`（如 `ftp:abc123`）。
     *
     * 本地响应**不带**这个字段，所以缺省值必须是 `"local"` —— 少了缺省，历史 core 的
     * 本地列表会直接解析失败（而不是"少个字段"这么温和）。
     */
    val source: String = "local"
)

// ── 外部存储源（FTP / WebDAV / SMB / S3）配置模型 ──
// 契约要点：响应里**永远没有** password 字段（core 不回传密码）；
// 改配置时把 password 传成 "********" 表示沿用原密码。
// S3 复用凭据字段：username = Access Key，password = Secret Key。

@Serializable
data class StorageSourcesResponse(
    val sources: List<StorageSourceInfo> = emptyList()
)

/**
 * 一个已保存的外部存储源。
 *
 * @param capabilities core 报告的能力清单（`LIST` / `READ` / `WRITE` / `DELETE` / `RENAME` /
 *   `MKDIR` / `UPLOAD` / `DOWNLOAD` …）。UI 必须据此隐藏做不到的动作 —— FTP / WebDAV 都没有
 *   「解压 / 压缩 / 校验和」这类需要在设备本地跑计算的能力，菜单里留着就是点了必然失败的项。
 */
@Serializable
data class StorageSourceInfo(
    val id: String = "",
    val label: String = "",
    val protocol: String = "",
    val host: String = "",
    val port: Int = 0,
    /**
     * 用户名 / Access Key。**会回显**——它不是秘密，而且不回显的话编辑弹窗一保存
     * 就把它清成空串（密码靠 `"********"` 占位兜住，用户名没有等价机制）。
     */
    val username: String = "",
    val basePath: String = "/",
    val useTls: Boolean = false,
    val passive: Boolean = true,
    val encoding: String = "UTF-8",
    val trustAllCerts: Boolean = false,
    /** SMB 专用：Windows 域 / 工作组，留空表示无域。 */
    val domain: String = "",
    /** SMB 专用：共享名（NAS 上的共享文件夹名）。 */
    val share: String = "",
    /** S3 专用：Bucket 名。 */
    val bucket: String = "",
    /** S3 专用：Region（MinIO 等自建服务用 us-east-1 即可）。 */
    val region: String = "us-east-1",
    /** S3 专用：自定义 endpoint 主机名（不含协议前缀），留空走 AWS 官方地址。 */
    val endpoint: String = "",
    /** S3 专用：路径式寻址（MinIO 必须开启）。 */
    val pathStyle: Boolean = true,
    val timeoutSec: Int = 15,
    val enabled: Boolean = true,
    val capabilities: List<String> = emptyList()
)

/** 新增 / 修改 / 试连的请求体（三处共用一份，字段集完全一致）。 */
@Serializable
data class StorageSourceRequest(
    val label: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val basePath: String = "/",
    val useTls: Boolean = false,
    val passive: Boolean = true,
    val encoding: String = "UTF-8",
    val trustAllCerts: Boolean = false,
    /** SMB 专用：Windows 域 / 工作组，留空表示无域。 */
    val domain: String = "",
    /** SMB 专用：共享名（NAS 上的共享文件夹名）。SMB 源必填。 */
    val share: String = "",
    /** S3 专用：Bucket 名。S3 源必填。 */
    val bucket: String = "",
    /** S3 专用：Region（MinIO 等自建服务用 us-east-1 即可）。 */
    val region: String = "us-east-1",
    /** S3 专用：自定义 endpoint 主机名（不含协议前缀），留空走 AWS 官方地址。 */
    val endpoint: String = "",
    /** S3 专用：路径式寻址（MinIO 必须开启）。 */
    val pathStyle: Boolean = true,
    val timeoutSec: Int = 15,
    val enabled: Boolean = true
)

@Serializable
data class StorageSourceMutationResponse(
    val success: Boolean = false,
    val source: StorageSourceInfo? = null,
    val message: String? = null
)

@Serializable
data class StorageSourceTestResponse(
    val success: Boolean = false,
    val message: String = "",
    @SerialName("latency_ms") val latencyMs: Long = 0
)


@Serializable
data class FileListResponse(
    val files: List<FileItem>,
    val path: String,
    val parent: String? = null,
    val error: String? = null,
    val message: String? = null
)

@Serializable
data class FileInfoResponse(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val permissions: String,
    val owner: String = "",
    val group: String = ""
)

@Serializable
data class FileReadResponse(
    val content: String,
    /** 服务端实际使用的解码字符集。请求里指定的编码若不被支持，这里回显的是实际用的那个。 */
    val encoding: String = "utf-8",
    /** 文件真实字节数。Long 而非 Int：过大分支会回真实长度，可能超过 Int 上限 */
    val size: Long,
    /** content 不是完整文件内容（过大 / 二进制 / 非文件） */
    val truncated: Boolean = false,
    /**
     * 内容不可用的具体原因：`too_large` / `binary` / `not_file`；内容完整时为 null。
     *
     * 2026-09-11 新增。此前三种情况都只置 `truncated = true` 并把说明文字塞进 [content]，
     * 客户端无法区分，只能给一句「服务端已截断」的误导文案。老版本 core 不回这个字段，
     * 客户端按 `truncated` 回落（见 `FileManagerModule.readTextFile`）。
     */
    val reason: String? = null,
    /**
     * 以 UTF-8 解码时出现了替换字符（U+FFFD），内容可能是 GBK 等其它编码。
     *
     * 只上报、不自动切换：猜错编码比不猜更糟（GBK 误判会把中文变成乱码却"看起来能读"）。
     */
    val encoding_suspect: Boolean = false
)

@Serializable
data class ArchiveExtractResponse(
    val success: Boolean,
    /** 解压目标目录（gz 为输出文件所在目录）。 */
    val destination: String? = null,
    /** 归档类型：zip / tgz / tar / gz。 */
    val kind: String? = null,
    val error: String? = null,
    val message: String? = null
)

@Serializable
data class ArchiveCompressResponse(
    val success: Boolean,
    /** 生成的 .zip 完整路径。 */
    val path: String? = null,
    val size: Long = 0,
    val error: String? = null,
    val message: String? = null
)

@Serializable
data class ArchiveChecksumResponse(
    val success: Boolean,
    val path: String? = null,
    /** 算法名(小写) → 十六进制摘要：md5 / sha1 / sha256 / sha512。 */
    val algorithms: Map<String, String> = emptyMap(),
    val error: String? = null,
    val message: String? = null
)
