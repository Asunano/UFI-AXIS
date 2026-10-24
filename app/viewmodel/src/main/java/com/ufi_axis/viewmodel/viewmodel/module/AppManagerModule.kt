package com.ufi_axis.viewmodel.module

import android.content.Context
import android.os.SystemClock
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.AppActionRequest
import com.ufi_axis.data.model.AppInstallRequest
import com.ufi_axis.data.model.AppInstallUrlRequest
import com.ufi_axis.viewmodel.state.AppManageState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AppManagerModule(
    private val api: UfiAxisApi,
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(AppManageState())
    val state: StateFlow<AppManageState> = _state.asStateFlow()

    /**
     * 当前 `apps` 里那份列表**成功落地时用的 filter**（`null` = 本进程还没成功读到过任何列表）。
     *
     * 与下载页的 `downloadsSuccessElapsed == 0L` 是同一条口径（"从未成功加载过"），
     * 只是本方法带 [filter] 形参，所以记的是"成功过的那个 filter"而不是时刻。
     */
    @Volatile private var loadedFilter: String? = null

    /**
     * 同一 filter 下**上次成功**拉取的时刻（`SystemClock.elapsedRealtime()`，0 = 从未成功）。
     *
     * 单调时钟而非 `currentTimeMillis()`：后者能被用户/NTP 改 —— 往前跳会把陈旧列表判成新鲜
     * （这一页在 core 之外没人改列表，用户改的就是靠手动刷新打破），往后跳会让新数据被判成过期。
     * 口径与 `DownloadModule.downloadsSuccessElapsed` / `NetworkModule` 完全一致。
     */
    @Volatile private var listSuccessElapsedMs: Long = 0L

    /**
     * 读应用列表（`GET /api/apps?filter=…`）。
     *
     * ## 只有「没有可显示的列表」时才写 loading 态（2026-09-05）
     * `AppScreen:109` 的 `if (state.isLoading) UfiSkeletonGroup(...)` 会把**整块内容区**
     * （筛选栏以下全部）换成骨架 —— 与列表 / 空态是互斥子树，翻一次 `isLoading` 就是一次
     * 硬子树替换。判据 [loadedFilter] `!= filter` 一条同时覆盖必须遮住的两种情况：
     * - `loadedFilter == null`：本进程从未成功读到过 ⇒ 骨架屏必须保留（设备上几百个包，
     *   `pm list` + 逐包取 label 要好几秒，此时没有骨架屏页面就是全空的）；
     * - `loadedFilter != filter`：**切筛选页签**。现有 `apps` 属于上一个 filter，
     *   不遮住它就会出现"点了系统应用、却继续列着用户应用好几秒"，比闪一下更糟。
     *
     * 判据刻意**不是** `apps.isEmpty()`：那样"该 filter 下确实一个应用都没有"（例如设备上
     * 没有第三方应用、或某个筛选结果为空）会让每一次进页面 / 手动刷新 / 写后回读都重新写
     * `isLoading = true` ⇒ `UfiEmptyState("未找到应用")` 被骨架顶掉再挂回来 = **空态闪一下**。
     * 这与下载页"进页面时空态图标和描述闪一下、添加任务后就正常"是同一个 bug 形态
     * （见 `DownloadModule.loadDownloads`）。列表非空时本来就不会闪，所以只有空结果暴露它。
     *
     * 被排除掉的还有"白付"的那几条：`performAppAction` / 安装成功后的**写后回读**，
     * 以及 2026-10-21 起 `AppScreen` 的**静默轮询** —— 同一 filter 且已成功加载过，
     * 翻 `isLoading` 只会把内容区整棵换成骨架再换回来。现在是原地更新。
     * （顶栏手动刷新按钮已在 2026-10-21 按用户要求删除。）
     *
     * ## 新鲜度闸门（2026-10-12 应用列表缓存批次）
     *
     * 原来这里刻意**没有**闸门，理由是"应用集合随时会变，进页面就该是最新的"。那个前提本身
     * 仍然成立，但它不构成"每 30 秒就得重问一次"的理由 —— 上一段写的判据是
     * [loadedFilter]（"有没有可显示的列表"），管的是**要不要骨架屏**，不是**要不要发请求**。
     *
     * 于是加 [isForegroundDataFresh] + [APP_LIST_FRESH_MS]：
     * - **进页面（`LaunchedEffect(state.filter)`）**：`force = false` —— 新鲜窗口内一个请求都不发。
     *   这是本批次客户端侧的一半收益；另一半在 core（它自己有 5 分钟进程内缓存），
     *   两边叠加才让"重进页面"真的不付那 4 条特权 shell 的钱。
     * - **页签切换 / 写后回读**：`force = true` —— 必须绕过闸门真打一次。
     *   页签切换另有一层判据（见下），写后回读更不能吃缓存（值刚被改过）。
     *   （顶栏刷新按钮已在 2026-10-21 按用户要求删除，界面侧不再有这一路 force 调用点。）
     * - **静默**：新鲜窗口内命中缓存那条路**不发请求**，自然也谈不上 loading。
     *   `AppScreen` 的静默轮询正是走这条：非 force、间隔略大于窗口，
     *   命中窗口时一次请求都不发。
     *
     * ## 为什么不另起一套缓存字段
     * 「上次成功时刻 + 窗口 + 单调时钟」这套判据本仓已经有了（[DataFreshness.kt]，下载页 /
     * 网络页 / 监控页都在用），并且带单测。这里复用而不是自己写一份 `if (now - t < 30_000)`
     * —— 两份判据迟早漂移，而漂移后的形态是"有的页面复用有的不白费"，很难查。
     */
    fun loadAppList(filter: String = "user", force: Boolean = false) {
        // ① 页签切换必须真拉：**内存里那份是另一个 filter 的**，闸门会把它判成"新鲜"直接返回，
        //    页面就永远停在旧页签的数据上。判据用 loadedFilter（成功过的 filter）而不是
        //    state.filter —— 后者在请求发出前就被写成了新值，拿它判会把每次切页签都误判成同 filter。
        //    切页签频率极低（用户手动点），这一条例外不吃掉任何自动刷新场景。
        val filterChanged = loadedFilter != null && loadedFilter != filter
        // ② 新鲜窗口闸门（自动路径才走）：用户 30s 内反复进出/切页签不该重复发。
        //    force=true 的两条（页签切换、写后回读）永远绕过。
        if (!force && !filterChanged &&
            isForegroundDataFresh(listSuccessElapsedMs, SystemClock.elapsedRealtime(), APP_LIST_FRESH_MS)
        ) {
            return
        }
        scope.launch {
            val needSkeleton = loadedFilter != filter
            _state.value = _state.value.copy(
                // 结构相等时 MutableStateFlow 不发射（AppManageState 是 data class），
                // 所以"同一 filter + 已成功加载过 + 无错误"这条常见路径在这里是零重组。
                isLoading = if (needSkeleton) true else _state.value.isLoading,
                filter = filter,
                errorMessage = null
            )
            try {
                val resp = api.getAppList(filter)
                // 只在成功落地后记 filter 与成功时刻，失败不记 —— 否则下一次进来会以为"已经有列表了"，
                // 空着的内容区连骨架都不给；失败还去记时刻的话，一次网络抖动会让 30s 内
                // 后续所有进入都被闸门挡掉，页面一直停在空态。
                loadedFilter = filter
                listSuccessElapsedMs = SystemClock.elapsedRealtime()
                _state.value = _state.value.copy(apps = resp.apps.sortedByDescending { it.isFrozen || !it.isEnabled }, hasRoot = resp.root, isLoading = false)
            } catch (e: Exception) { _state.value = _state.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun loadAppDetail(packageName: String) {
        scope.launch {
            try { _state.value = _state.value.copy(selectedApp = api.getAppDetail(packageName)) }
            catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "获取详情失败: ${e.message}") }
        }
    }

    fun dismissAppDetail() { _state.value = _state.value.copy(selectedApp = null) }

    fun performAppAction(action: String, packageName: String) {
        scope.launch {
            try {
                when (action) {
                    "uninstall" -> api.uninstallApp(AppActionRequest(packageName))
                    "disable" -> api.disableApp(AppActionRequest(packageName))
                    "enable" -> api.enableApp(AppActionRequest(packageName))
                    "clear" -> api.clearAppData(AppActionRequest(packageName))
                    "force-stop" -> api.forceStopApp(AppActionRequest(packageName))
                    "freeze" -> api.freezeApp(AppActionRequest(packageName))
                    "unfreeze" -> api.unfreezeApp(AppActionRequest(packageName))
                }
                _state.value = _state.value.copy(selectedApp = null, errorMessage = null)
                // force=true：值刚被写过，闸门会把"刚冻结的应用还显示启用"钉住 30 秒
                loadAppList(_state.value.filter, force = true)
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "操作失败: ${e.message}") }
        }
    }

    /**
     * 一次授予该应用**全部**已声明的运行时权限（`POST /api/apps/grant-all-permissions`）。
     *
     * 为什么不塞进 [performAppAction]：那些动作都是「成功即关详情页 + 重拉列表」的同构操作，
     * 而这一个是**高风险且不可撤销**的 —— 调用方必须先弹红色确认弹窗，成功后还要把 core 给的
     * 「授予了几项」原文回显给用户。所以走 suspend 返回结果，而不是写进 State。
     *
     * 失败时 core 回 **HTTP 500**，`message` 是 shell 原始报错（可能是一长串英文），
     * 所以这里只把它当补充信息带回去，人话提示由 UI 自己兜。
     *
     * @return 成功 = true to core 的 message；失败 = false to 失败原因
     */
    suspend fun grantAllPermissions(packageName: String): Pair<Boolean, String> = try {
        val resp = api.grantAllAppPermissions(AppActionRequest(packageName))
        resp.success to resp.message
    } catch (e: Exception) {
        false to (e.message ?: "授予失败")
    }

    fun installAppFromUrl(url: String) {
        scope.launch {
            _state.value = _state.value.copy(installLoading = true)
            try {
                val resp = api.installAppFromUrl(AppInstallUrlRequest(url))
                _state.value = _state.value.copy(installLoading = false, errorMessage = if (!resp.success) resp.message else null)
                if (resp.success) loadAppList(_state.value.filter, force = true)
            } catch (e: Exception) { _state.value = _state.value.copy(installLoading = false, errorMessage = "安装失败: ${e.message}") }
        }
    }

    fun installAppFromPath(path: String) {
        scope.launch {
            _state.value = _state.value.copy(installLoading = true)
            try {
                val resp = api.installApp(AppInstallRequest(path))
                _state.value = _state.value.copy(installLoading = false, errorMessage = if (!resp.success) resp.message else null)
                if (resp.success) loadAppList(_state.value.filter, force = true)
            } catch (e: Exception) { _state.value = _state.value.copy(installLoading = false, errorMessage = "安装失败: ${e.message}") }
        }
    }
}